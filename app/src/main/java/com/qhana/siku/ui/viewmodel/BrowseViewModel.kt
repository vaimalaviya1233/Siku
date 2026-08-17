package com.qhana.siku.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qhana.siku.R
import com.qhana.siku.data.coordinator.SyncManager
import com.qhana.siku.data.local.AlbumSummary
import com.qhana.siku.data.local.ArtistEntity
import com.qhana.siku.data.local.ArtistSummary
import com.qhana.siku.data.local.GenreSummary
import com.qhana.siku.data.model.AlbumSortOrder
import com.qhana.siku.data.model.ArtistSortOrder
import com.qhana.siku.data.model.Song
import com.qhana.siku.data.model.SongSourceFilter
import com.qhana.siku.data.preferences.MusicPreferences
import com.qhana.siku.data.repository.ArtistImageRepository
import com.qhana.siku.data.repository.ArtworkRepository
import com.qhana.siku.data.repository.BrowseRepository
import com.qhana.siku.data.repository.DeezerArtistCandidate
import com.qhana.siku.data.util.SnackbarManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import com.qhana.siku.data.util.WhileUiSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Estado del picker manual de artista (molde del flujo de búsqueda de letras). */
sealed interface ArtistPickerState {
    data object Hidden : ArtistPickerState
    data object Loading : ArtistPickerState
    data class Loaded(val candidates: List<DeezerArtistCandidate>) : ArtistPickerState
    data class Error(val message: String) : ArtistPickerState
}

/**
 * ViewModel de navegación por Artistas/Álbumes (pestañas + pantallas de detalle).
 * Separado de LibraryViewModel a propósito: ese ya concentra búsqueda/orden/selección/
 * playlists/ajustes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BrowseViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val browseRepository: BrowseRepository,
    private val artistImageRepository: ArtistImageRepository,
    // Extracción del seed de la foto de artista para el tema local del detalle (ver [artistSeed]).
    private val artworkRepository: ArtworkRepository,
    private val musicPreferences: MusicPreferences,
    private val snackbarManager: SnackbarManager,
    // Solo para saber cuándo la biblioteca está en movimiento y espaciar las consultas de
    // agregación mientras tanto (ver [sampledDuringSync]). No se sincroniza nada desde aquí.
    syncManager: SyncManager
) : ViewModel() {

    /** Ver [settlingFlow] y [sampledDuringSync]. */
    private val libraryIsSettling = syncManager.settlingFlow()

    init {
        // Backfill de fotos pendientes al arrancar la sesión de navegación: cubre bibliotecas
        // ya sincronizadas donde no correrá ningún scan que dispare el hook de SyncManager.
        // Idempotente y con rate-limit en el repo; dispararlo dos veces es gratis.
        viewModelScope.launch(Dispatchers.IO) {
            artistImageRepository.backfillMissingImages()
        }
        // Re-disparo del backfill al cambiar la red, colgado del ciclo de vida de ESTA pantalla y no
        // del singleton: antes vivía en un `init` de por vida y salía a Deezer en cada salto
        // WiFi↔datos aunque nadie hubiera abierto nunca la pestaña Artistas (proceso de un worker).
        // Ver [ArtistImageRepository.backfillOnNetworkChanges].
        viewModelScope.launch(Dispatchers.IO) {
            artistImageRepository.backfillOnNetworkChanges().collect { }
        }
    }

    // --- Orden de las pestañas de browse (persistido, como el de canciones) ---

    private val _artistSortOrder = MutableStateFlow(musicPreferences.loadArtistSortOrder())
    val artistSortOrder: StateFlow<ArtistSortOrder> = _artistSortOrder.asStateFlow()

    fun setArtistSortOrder(order: ArtistSortOrder) {
        musicPreferences.saveArtistSortOrder(order)
        _artistSortOrder.value = order
    }

    private val _albumSortOrder = MutableStateFlow(musicPreferences.loadAlbumSortOrder())
    val albumSortOrder: StateFlow<AlbumSortOrder> = _albumSortOrder.asStateFlow()

    fun setAlbumSortOrder(order: AlbumSortOrder) {
        musicPreferences.saveAlbumSortOrder(order)
        _albumSortOrder.value = order
    }

    // --- Filtros de origen de las pestañas Artistas/Álbumes (unión, igual que en Todas) ---
    // Semántica "≥1 canción que cumple": el WHERE va antes del GROUP BY (ver
    // SongDao.buildArtistsQuery/buildAlbumsQuery). NO se persisten: son un filtro de sesión,
    // como los chips de la pestaña Todas.

    private val _artistSourceFilters = MutableStateFlow<Set<SongSourceFilter>>(emptySet())
    val artistSourceFilters: StateFlow<Set<SongSourceFilter>> = _artistSourceFilters.asStateFlow()

    fun toggleArtistSourceFilter(filter: SongSourceFilter) {
        _artistSourceFilters.value = _artistSourceFilters.value.toMutableSet().apply {
            if (!add(filter)) remove(filter)
        }
    }

    private val _albumSourceFilters = MutableStateFlow<Set<SongSourceFilter>>(emptySet())
    val albumSourceFilters: StateFlow<Set<SongSourceFilter>> = _albumSourceFilters.asStateFlow()

    fun toggleAlbumSourceFilter(filter: SongSourceFilter) {
        _albumSourceFilters.value = _albumSourceFilters.value.toMutableSet().apply {
            if (!add(filter)) remove(filter)
        }
    }

    // distinctUntilChanged: Room re-emite en CADA invalidación de las tablas del SQL aunque
    // el resultado sea idéntico. En Artistas eso pasa constantemente durante el fetch Deezer:
    // cada not-found persiste su intento en `artists` → requery → lista IGUAL (thumbUrl sigue
    // null) → recomposición inútil en pleno scroll. Los flows de álbumes lo heredan gratis
    // (playCount escribe en `songs` sin cambiar los agregados).
    //
    // `sampledDuringSync` ataca lo que el `distinctUntilChanged` no puede: ese descarta el
    // RESULTADO repetido, pero la consulta —un GROUP BY sobre la tabla entera— ya se ejecutó. Son
    // las dos agregaciones más caras de la app y la biblioteca las tiene colectadas siempre.
    val artists: StateFlow<List<ArtistSummary>> =
        combine(_artistSortOrder, _artistSourceFilters) { sort, filters -> sort to filters }
            .flatMapLatest { (sort, filters) -> browseRepository.getArtists(sort, filters) }
            .sampledDuringSync(libraryIsSettling)
            .distinctUntilChanged()
            .stateIn(viewModelScope, WhileUiSubscribed, emptyList())

    val albums: StateFlow<List<AlbumSummary>> =
        combine(_albumSortOrder, _albumSourceFilters) { sort, filters -> sort to filters }
            .flatMapLatest { (sort, filters) -> browseRepository.getAlbums(sort, filters) }
            .sampledDuringSync(libraryIsSettling)
            .distinctUntilChanged()
            .stateIn(viewModelScope, WhileUiSubscribed, emptyList())

    // --- Géneros ---

    /**
     * "Incluir géneros compuestos": persistido, y compartido con los chips de género del inicio
     * (`LibraryViewModel`), para que un mismo género reproduzca lo mismo se toque donde se toque.
     */
    val genrePartialMatch: StateFlow<Boolean> = musicPreferences.genrePartialMatchFlow
        .stateIn(
            viewModelScope,
            WhileUiSubscribed,
            musicPreferences.loadGenrePartialMatch()
        )

    fun setGenrePartialMatch(enabled: Boolean) = musicPreferences.saveGenrePartialMatch(enabled)

    /**
     * Géneros de la pestaña. Con la coincidencia parcial activa, el conteo de cada género SUMA
     * el de los grupos cuyo tag lo contiene ("Rock" ← "Rock/Metal", "Hard Rock"): el número que
     * se ve tiene que ser el de la lista que se abre al tocarlo. Se calcula en memoria y no con
     * otra consulta porque cada canción cae en exactamente un grupo, así que sumar grupos da el
     * mismo resultado que el LIKE del detalle — sin N consultas correlacionadas.
     */
    val genres: StateFlow<List<GenreSummary>> =
        combine(browseRepository.getGenres(GENRE_MIN_COUNT), genrePartialMatch) { list, partial ->
            if (!partial) list else list.map { genre ->
                val total = list
                    .filter { it.name.contains(genre.name, ignoreCase = true) }
                    .sumOf { it.songCount }
                genre.copy(songCount = total)
            }
        }
            .sampledDuringSync(libraryIsSettling)
            .distinctUntilChanged()
            .stateIn(viewModelScope, WhileUiSubscribed, emptyList())

    /** Canciones de un género para su detalle; sigue en vivo el ajuste de coincidencia parcial. */
    fun getGenreSongs(genre: String): Flow<List<Song>> =
        genrePartialMatch.flatMapLatest { partial -> browseRepository.getSongsByGenre(genre, partial) }

    /** Álbumes del momento (por total de reproducciones) para la sección de la home. */
    val topAlbums: StateFlow<List<AlbumSummary>> = browseRepository.getTopAlbums(TOP_ALBUMS_LIMIT)
        .sampledDuringSync(libraryIsSettling)
        .distinctUntilChanged()
        .stateIn(viewModelScope, WhileUiSubscribed, emptyList())

    // --- Detalle (coleccionar con collectAsStateWithLifecycle en la pantalla) ---

    fun getArtistSongs(artist: String): Flow<List<Song>> = browseRepository.getSongsByArtist(artist)

    fun getArtistAlbums(artist: String): Flow<List<AlbumSummary>> =
        browseRepository.getAlbumsByArtist(artist)

    fun getArtistInfo(artist: String): Flow<ArtistEntity?> = browseRepository.getArtistInfo(artist)

    fun getAlbumSongs(album: String): Flow<List<Song>> = browseRepository.getSongsByAlbum(album)

    // --- Seed del tema local del detalle de artista ---

    /**
     * Color del que el detalle de artista seedea su tema (ver `DetailContentTheme`). `null` = esa
     * pantalla no tiene imagen de la que sacar color y se queda con el tema global.
     *
     * A diferencia del detalle de álbum —cuyo seed viaja YA extraído en `Song.colors`— la foto de
     * un artista no pertenece a ninguna fila de `songs`, así que hay que extraerlo de la imagen.
     * Es un `StateFlow` y no un valor porque la foto puede **llegar tarde**: la baja el backfill de
     * Deezer y puede aparecer con la pantalla ya abierta.
     */
    private val _artistSeed = MutableStateFlow<Int?>(null)
    val artistSeed: StateFlow<Int?> = _artistSeed.asStateFlow()

    private var artistSeedJob: Job? = null

    /**
     * Pide el seed de [imageUrl], que debe ser **la imagen que la pantalla está mostrando de
     * verdad** — o sea el resultado de la cascada foto → carátula de un álbum suyo, no la foto a
     * secas. Si el color saliera de otra imagen, el tema no tendría que ver con lo que se ve.
     *
     * Cancela la petición anterior: al cambiar la imagen, el seed de la vieja ya no interesa y
     * dejarlo correr podría pisar al nuevo si termina después.
     */
    fun requestArtistSeed(imageUrl: String?) {
        artistSeedJob?.cancel()
        if (imageUrl == null) {
            _artistSeed.value = null
            return
        }
        artistSeedJob = viewModelScope.launch {
            _artistSeed.value = artworkRepository.seedForImage(imageUrl)
        }
    }

    // --- Banner "fotos en pausa por red móvil" (pestaña Artistas) ---

    /** true = el backfill se abstuvo por estar en datos móviles; la pestaña muestra el banner. */
    val artistPhotosPausedOnMobile: StateFlow<Boolean> = artistImageRepository.meteredBackfillPending

    /** "Descargar" del banner: permite la red medida esta sesión y relanza el backfill. */
    fun downloadArtistPhotosOnMobile() {
        viewModelScope.launch(Dispatchers.IO) {
            artistImageRepository.resumeBackfillOnMetered()
        }
    }

    /** "Ahora no" del banner: dismiss de sesión + pista de dónde vive el control permanente. */
    fun dismissArtistPhotosBanner() {
        artistImageRepository.dismissMeteredBackfillBanner()
        snackbarManager.show(context.getString(R.string.artist_photos_banner_hint))
    }

    // --- Ajustes de fotos de artistas (Ajustes → Descargas) ---

    private val _artistPhotosOnMetered = MutableStateFlow(musicPreferences.loadArtistPhotosOnMetered())
    val artistPhotosOnMetered: StateFlow<Boolean> = _artistPhotosOnMetered.asStateFlow()

    private val _artistPhotosBannerEnabled = MutableStateFlow(musicPreferences.loadArtistPhotosBannerEnabled())
    val artistPhotosBannerEnabled: StateFlow<Boolean> = _artistPhotosBannerEnabled.asStateFlow()

    private val _artistPhotoDetailOnMetered = MutableStateFlow(musicPreferences.loadArtistPhotoDetailOnMetered())
    val artistPhotoDetailOnMetered: StateFlow<Boolean> = _artistPhotoDetailOnMetered.asStateFlow()

    /** "Descargar con datos móviles": al activarlo, lo pendiente se resuelve al momento. */
    fun setArtistPhotosOnMetered(enabled: Boolean) {
        musicPreferences.saveArtistPhotosOnMetered(enabled)
        _artistPhotosOnMetered.value = enabled
        if (enabled) {
            artistImageRepository.clearMeteredBannerPending()
            viewModelScope.launch(Dispatchers.IO) { artistImageRepository.backfillMissingImages() }
        }
    }

    /** "Preguntar en red móvil": apagar oculta un banner ya visible sin dismiss de sesión. */
    fun setArtistPhotosBannerEnabled(enabled: Boolean) {
        musicPreferences.saveArtistPhotosBannerEnabled(enabled)
        _artistPhotosBannerEnabled.value = enabled
        if (!enabled) artistImageRepository.clearMeteredBannerPending()
    }

    fun setArtistPhotoDetailOnMetered(enabled: Boolean) {
        musicPreferences.saveArtistPhotoDetailOnMetered(enabled)
        _artistPhotoDetailOnMetered.value = enabled
    }

    /**
     * Fire-and-forget: fetch de la foto Deezer al entrar al DETALLE de un artista. Con el
     * backfill en background esto es normalmente redundante; queda como reintento dirigido
     * para cuando aquel falló por red (el repo limpia el intento de sesión en ese caso).
     * La pestaña de artistas ya NO llama esto por fila.
     */
    fun onArtistShown(artistName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            artistImageRepository.ensureArtistImageOnDemand(artistName)
        }
    }

    // --- Picker manual de artista (Deezer) ---

    private val _pickerState = MutableStateFlow<ArtistPickerState>(ArtistPickerState.Hidden)
    val pickerState: StateFlow<ArtistPickerState> = _pickerState.asStateFlow()

    private var searchJob: Job? = null

    fun searchArtistCandidates(artistName: String) {
        searchJob?.cancel()
        _pickerState.value = ArtistPickerState.Loading
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            artistImageRepository.searchCandidates(artistName)
                .onSuccess { _pickerState.value = ArtistPickerState.Loaded(it) }
                .onFailure { _pickerState.value = ArtistPickerState.Error(context.getString(R.string.error_no_deezer)) }
        }
    }

    fun selectArtistCandidate(artistName: String, candidate: DeezerArtistCandidate) {
        viewModelScope.launch(Dispatchers.IO) {
            artistImageRepository.setManualArtist(artistName, candidate)
            _pickerState.value = ArtistPickerState.Hidden
        }
    }

    /**
     * "Ninguno de estos": deja al artista sin foto de forma DELIBERADA (ver
     * [ArtistImageRepository.clearArtistImage]). La pantalla cae entonces a la carátula de su
     * primer álbum, o al placeholder si tampoco la hay.
     */
    fun clearArtistImage(artistName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            artistImageRepository.clearArtistImage(artistName)
            _pickerState.value = ArtistPickerState.Hidden
        }
    }

    fun dismissArtistPicker() {
        searchJob?.cancel()
        _pickerState.value = ArtistPickerState.Hidden
    }

    private companion object {
        const val TOP_ALBUMS_LIMIT = 12

        /**
         * Mínimo de canciones para que un género salga en la PESTAÑA: todos. A diferencia de los
         * chips del inicio (que filtran a ≥5 para no llenarse de géneros anecdóticos), aquí una
         * lista que esconde géneros se lee como biblioteca incompleta.
         */
        const val GENRE_MIN_COUNT = 1
    }
}
