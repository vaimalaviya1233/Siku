package com.qhana.siku.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.qhana.siku.data.config.AppConfig
import com.qhana.siku.data.model.AlbumSortOrder
import com.qhana.siku.data.model.ArtistSortOrder
import com.qhana.siku.data.model.DownloadControlState
import com.qhana.siku.data.model.DuplicatePolicy
import com.qhana.siku.data.model.EqCustomPreset
import com.qhana.siku.data.model.LibraryTabState
import com.qhana.siku.data.model.LibraryTabsConfig
import com.qhana.siku.data.model.LyricsSaveMode
import com.qhana.siku.data.model.PlaybackContext
import com.qhana.siku.data.model.PlayerToolbarConfig
import com.qhana.siku.data.model.ReplayGainMode
import com.qhana.siku.data.model.ToolbarActionState
import com.qhana.siku.data.model.SongFilter
import com.qhana.siku.data.model.SortOrder
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Preferencias de usuario persistidas con DataStore.
 *
 * Mantiene API síncrona via caché en memoria para callers que no pueden suspender
 * (p.ej. `init` de ViewModels). La caché se llena en el constructor con `runBlocking`
 * una sola vez por instancia (y MusicPreferences es `@Singleton`).
 *
 * Los datos existentes en SharedPreferences `music_player_prefs` se migran
 * automáticamente en el primer acceso gracias a `SharedPreferencesMigration`.
 */
class MusicPreferences(context: Context) {

    private val dataStore: DataStore<Preferences> = context.musicPrefsDataStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * ESTADO EN MEMORIA = la única fuente de verdad, y también lo que emiten los flows
     * reactivos. Se llena sync al construir (una vez; la clase es `@Singleton`) y a partir de
     * ahí solo lo escribe [update]. El disco es un destino, no una fuente.
     *
     * Antes había DOS verdades: `loadX()` leía este caché (síncrono, ya actualizado) mientras
     * los `xFlow` leían `dataStore.data` (asíncrono, va por detrás). Un `collect` de
     * `dataStore.data` reasignaba además el caché, así que el disco podía hacer RETROCEDER el
     * estado en RAM — dos escritores para el mismo estado, justo lo que la app tiene prohibido
     * en otro sitio por haber costado ya un bug. Con esto, escribir una preferencia se ve en el
     * mismo frame en todo lo que la observe, sin esperar al disco ni depender de su orden.
     */
    private val prefs = MutableStateFlow(
        runBlocking {
            try {
                dataStore.data.first()
            } catch (_: Exception) {
                emptyPreferences()
            }
        }
    )

    private val cache: Preferences get() = prefs.value

    /**
     * Cola FIFO de volcados a disco con UN SOLO consumidor. Cada escritura vuelca el snapshot
     * COMPLETO (clear + volcado), así que el orden importa: con un `launch` por escritura sobre
     * `Dispatchers.IO` —lo que había antes— dos volcados casi simultáneos corren en hilos
     * distintos y nada garantiza que el más nuevo llegue el último. Cuando se invertían, el
     * snapshot viejo pisaba al nuevo y la preferencia recién guardada desaparecía del disco (y,
     * cuando los flows leían de ahí, también de la UI). Ya había mordido con las carpetas
     * locales y se parcheó fusionando esas dos escrituras a mano; esto lo arregla para todas.
     */
    private val diskWrites = Channel<Preferences>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (snapshot in diskWrites) {
                try {
                    dataStore.edit { target ->
                        target.clear()
                        @Suppress("UNCHECKED_CAST")
                        snapshot.asMap().forEach { (k, v) -> target[k as Preferences.Key<Any>] = v }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("MusicPreferences", "Error persistiendo preferencias", e)
                }
            }
        }
    }

    private fun update(block: (MutablePreferences) -> Unit) {
        // Aplicar en memoria de forma SÍNCRONA para que `loadX()` posteriores vean el cambio
        // inmediatamente. Sin esto hay race condition: por ejemplo `clearDeltaToken()` seguido
        // de `loadDeltaToken()` en la misma función devolvería el token viejo, porque la
        // escritura a disco corre en background.
        val mutated = cache.toMutablePreferences()
        block(mutated)
        val newCache = mutated.toPreferences()
        prefs.value = newCache
        // Se persiste el SNAPSHOT (no se re-ejecuta `block`): así un `block` no idempotente
        // —incremento, append— no se aplica dos veces sobre bases distintas (memoria vs. disco)
        // divergiendo. `trySend` sobre un canal UNLIMITED nunca falla ni bloquea.
        diskWrites.trySend(newCache)
    }

    /**
     * Flow derivado del estado en memoria. `distinctUntilChanged` porque [prefs] emite en CADA
     * escritura de cualquier preferencia y un observador de una sola clave no debe recomponer
     * porque se guardó la posición de reproducción.
     */
    private fun <T> prefFlow(read: (Preferences) -> T): Flow<T> =
        prefs.map(read).distinctUntilChanged()

    // --- Sort order ---

    fun saveSortOrder(order: SortOrder, filter: SongFilter) = update {
        it[stringPreferencesKey(KEY_SORT_ORDER + filter.name)] = order.name
    }

    fun loadSortOrder(filter: SongFilter): SortOrder {
        val name = cache[stringPreferencesKey(KEY_SORT_ORDER + filter.name)]
        return try {
            SortOrder.valueOf(name ?: SortOrder.TITLE_ASC.name)
        } catch (_: Exception) {
            SortOrder.TITLE_ASC
        }
    }

    fun saveArtistSortOrder(order: ArtistSortOrder) = update { it[KEY_ARTIST_SORT] = order.name }
    fun loadArtistSortOrder(): ArtistSortOrder = try {
        ArtistSortOrder.valueOf(cache[KEY_ARTIST_SORT] ?: ArtistSortOrder.NAME.name)
    } catch (_: Exception) {
        ArtistSortOrder.NAME
    }

    fun saveAlbumSortOrder(order: AlbumSortOrder) = update { it[KEY_ALBUM_SORT] = order.name }
    fun loadAlbumSortOrder(): AlbumSortOrder = try {
        AlbumSortOrder.valueOf(cache[KEY_ALBUM_SORT] ?: AlbumSortOrder.NAME.name)
    } catch (_: Exception) {
        AlbumSortOrder.NAME
    }

    // --- Keep screen on ---

    fun saveKeepScreenOn(enabled: Boolean) = update {
        it[KEY_KEEP_SCREEN_ON] = enabled
    }

    fun loadKeepScreenOn(): Boolean = cache[KEY_KEEP_SCREEN_ON] ?: false

    // --- Colores manuales ---
    // Ids de canciones cuyo color eligió el USUARIO (picker del NowPlaying). Vive en DataStore,
    // no como columna de `songs`, por dos razones: no fuerza un bump destructivo del schema, y
    // sobrevive a la recreación de la BD. Es solo la MARCA; el color en sí sigue en la BD.

    fun loadManualColorIds(): Set<String> = cache[KEY_MANUAL_COLOR_IDS] ?: emptySet()

    fun addManualColorId(songId: String) = update {
        it[KEY_MANUAL_COLOR_IDS] = (it[KEY_MANUAL_COLOR_IDS] ?: emptySet()) + songId
    }

    fun addManualColorIds(songIds: Collection<String>) = update {
        it[KEY_MANUAL_COLOR_IDS] = (it[KEY_MANUAL_COLOR_IDS] ?: emptySet()) + songIds
    }

    fun removeManualColorId(songId: String) = update {
        it[KEY_MANUAL_COLOR_IDS] = (it[KEY_MANUAL_COLOR_IDS] ?: emptySet()) - songId
    }

    fun clearManualColorIds() = update { it.remove(KEY_MANUAL_COLOR_IDS) }

    // --- Session (queue + position) ---

    /**
     * Guarda los IDs de la cola (String desde v13). Se serializa usando ``
     * (Unit Separator) como delimitador: inofensivo dentro de IDs remotos de OneDrive.
     */
    fun saveQueueIds(queueIds: List<String>) = update {
        it[KEY_LAST_QUEUE] = queueIds.joinToString(QUEUE_DELIMITER)
    }

    fun loadQueueIds(): List<String> {
        val raw = cache[KEY_LAST_QUEUE] ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return try {
            raw.split(QUEUE_DELIMITER).filter { it.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveLastState(index: Int, position: Long, songId: String? = null) = update {
        it[KEY_LAST_INDEX] = index
        it[KEY_LAST_POSITION] = position
        // ID de la canción actual: la restauración lo prefiere sobre el índice, que se
        // desplaza si alguna canción de la cola guardada fue borrada de la BD.
        if (songId != null) it[KEY_LAST_SONG_ID] = songId else it.remove(KEY_LAST_SONG_ID)
    }

    fun loadLastState(): Triple<Int, Long, String?> {
        val index = cache[KEY_LAST_INDEX] ?: 0
        val position = cache[KEY_LAST_POSITION] ?: 0L
        return Triple(index, position, cache[KEY_LAST_SONG_ID])
    }

    /**
     * Estado de aleatorio de la sesión. Se guarda junto al ORDEN ORIGINAL de la cola porque la
     * cola persistida ya está barajada: sin el orden original, al restaurar no habría a dónde
     * volver al apagar el aleatorio (y el botón quedaría encendido mintiendo).
     */
    fun saveShuffleState(enabled: Boolean, originalQueueIds: List<String>) = update {
        it[KEY_SHUFFLE_ENABLED] = enabled
        if (enabled && originalQueueIds.isNotEmpty()) {
            it[KEY_ORIGINAL_QUEUE] = originalQueueIds.joinToString(QUEUE_DELIMITER)
        } else {
            it.remove(KEY_ORIGINAL_QUEUE)
        }
    }

    fun loadShuffleEnabled(): Boolean = cache[KEY_SHUFFLE_ENABLED] ?: false

    fun loadOriginalQueueIds(): List<String> {
        val raw = cache[KEY_ORIGINAL_QUEUE] ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return raw.split(QUEUE_DELIMITER).filter { it.isNotBlank() }
    }

    // --- Audio offload (batería) ---

    /**
     * "El offload de audio está ROTO en este dispositivo". Lo marca el watchdog de
     * `MusicPlaybackService` la primera (y única) vez que detecta el cuelgue.
     *
     * El offload manda los bytes comprimidos al DSP y deja dormir la CPU (buen ahorro de
     * batería), pero algunos DSP (visto en Xiaomi/MIUI con MP3) ACEPTAN el AudioTrack y luego
     * no consumen ni reportan nada → el player se queda en BUFFERING para siempre. Media3 no
     * puede detectarlo (su fallback solo cubre "el device declara no soportar el formato").
     *
     * Persistir el veredicto es lo que hace viable tener offload activo por defecto: el
     * dispositivo roto tropieza UNA vez y nunca más; el sano conserva el ahorro.
     */
    fun saveOffloadBroken(broken: Boolean) = update { it[KEY_OFFLOAD_BROKEN] = broken }

    fun loadOffloadBroken(): Boolean = cache[KEY_OFFLOAD_BROKEN] ?: false

    // --- Scan / sync state ---

    fun saveDeltaToken(token: String) = update {
        it[KEY_DELTA_TOKEN] = token
    }

    fun loadDeltaToken(): String? = cache[KEY_DELTA_TOKEN]

    fun clearDeltaToken() = update {
        it.remove(KEY_DELTA_TOKEN)
    }

    // --- Fuente local: o TODO el dispositivo (MediaStore), o N carpetas SAF. Excluyentes ---

    /**
     * Tree URIs (`ACTION_OPEN_DOCUMENT_TREE`, con permiso persistido) de las carpetas de música.
     * Vacío = no hay carpetas configuradas.
     *
     * Migra en lectura la clave de carpeta ÚNICA de las versiones anteriores: mientras el set no
     * exista, el valor viejo se presenta como un set de un elemento. La reescritura ocurre sola en
     * el primer [saveLocalFolderUris] y, hasta entonces, un usuario que actualice y no toque nada
     * conserva su carpeta.
     */
    fun loadLocalFolderUris(): Set<String> = readLocalFolderUris(cache)

    /**
     * Reactivo. La configuración de fuentes se toca desde DOS instancias distintas de
     * `SourcesViewModel` (el onboarding vive en su propio `NavBackStackEntry`), así que un
     * `MutableStateFlow` local dejaría a una sin enterarse de lo que hizo la otra — el mismo
     * motivo por el que el toggle del ecualizador se observa por flow.
     */
    val localFolderUrisFlow: Flow<Set<String>> = prefFlow { readLocalFolderUris(it) }

    private fun readLocalFolderUris(prefs: Preferences): Set<String> =
        prefs[KEY_LOCAL_FOLDER_URIS] ?: prefs[KEY_LOCAL_FOLDER_URI]?.let { setOf(it) } ?: emptySet()

    /** Fija el conjunto completo de carpetas (añadir y quitar son operaciones de la capa de arriba). */
    fun saveLocalFolderUris(uris: Set<String>) = update {
        it[KEY_LOCAL_FOLDER_URIS] = uris
        // La clave vieja deja de tener sentido en cuanto se escribe el set: si sobreviviera,
        // vaciar las carpetas haría "reaparecer" la carpeta original en la siguiente lectura.
        it.remove(KEY_LOCAL_FOLDER_URI)
    }

    /**
     * Fija carpetas y modo dispositivo en UNA sola escritura. Ambos ajustes son excluyentes y
     * cambian juntos (añadir carpeta apaga el escaneo completo; activarlo vacía las carpetas).
     *
     * Partirlo en dos [update] encadenados hacía que sus volcados a disco —cada uno un snapshot
     * completo del caché— compitieran en el scope de IO: al no garantizarse el orden, el snapshot
     * más viejo (con la lista de carpetas de ANTES) podía pisar al nuevo, y la carpeta recién
     * añadida "desaparecía" del flujo reactivo aunque el caché en memoria ya la tuviera.
     *
     * El "stash" (carpetas guardadas para restaurar tras un episodio de escaneo del dispositivo) se
     * fija/limpia en la MISMA transacción por el mismo motivo: dos escrituras separadas competirían.
     *
     * @param stashFolders si != null, fija el stash; usar al activar el dispositivo para no perder
     *        las carpetas que había.
     * @param clearStash true al restaurar: vacía el stash una vez devueltas las carpetas.
     */
    fun saveLocalSources(
        folderUris: Set<String>,
        scanWholeDevice: Boolean,
        stashFolders: Set<String>? = null,
        clearStash: Boolean = false
    ) = update {
        it[KEY_LOCAL_FOLDER_URIS] = folderUris
        it.remove(KEY_LOCAL_FOLDER_URI)
        it[KEY_SCAN_WHOLE_DEVICE] = scanWholeDevice
        if (stashFolders != null) it[KEY_LOCAL_FOLDER_URIS_STASH] = stashFolders
        if (clearStash) it.remove(KEY_LOCAL_FOLDER_URIS_STASH)
    }

    /** Carpetas guardadas al activar el escaneo del dispositivo (vacío si no hay nada que restaurar). */
    fun loadStashedFolderUris(): Set<String> = cache[KEY_LOCAL_FOLDER_URIS_STASH] ?: emptySet()

    /** Reactivo: gobierna si en Ajustes se puede apagar el escaneo del dispositivo (habría a dónde volver). */
    val localFolderStashFlow: Flow<Set<String>> =
        prefFlow { it[KEY_LOCAL_FOLDER_URIS_STASH] ?: emptySet() }

    /**
     * ¿Indexar TODA la música del dispositivo (MediaStore) en vez de carpetas concretas?
     * Es el único modo local que necesita permiso de lectura de audio.
     */
    fun loadScanWholeDevice(): Boolean = cache[KEY_SCAN_WHOLE_DEVICE] == true

    /** Reactivo, por el mismo motivo que [localFolderUrisFlow]. */
    val scanWholeDeviceFlow: Flow<Boolean> = prefFlow { it[KEY_SCAN_WHOLE_DEVICE] == true }

    fun saveScanWholeDevice(enabled: Boolean) = update {
        it[KEY_SCAN_WHOLE_DEVICE] = enabled
    }

    fun clearQueue() = update {
        it.remove(KEY_LAST_QUEUE)
        it.remove(KEY_LAST_INDEX)
        it.remove(KEY_LAST_POSITION)
        it.remove(KEY_LAST_SONG_ID)
        it.remove(KEY_DELTA_TOKEN)
        it.remove(KEY_SHUFFLE_ENABLED)
        it.remove(KEY_ORIGINAL_QUEUE)
    }

    // Los 7 tunables del extractor de color (quantization, objetivos de luminancia, saturación
    // máxima, factor de peso, stiffness, población mínima) se eliminaron al pasar a
    // QuantizerCelebi + Score: ese pipeline no tiene parámetros que ajustar. Las claves viejas
    // quedan huérfanas en el DataStore y se ignoran (no hace falta migrarlas).

    // --- ReplayGain ---

    fun saveReplayGainMode(mode: ReplayGainMode) = update { it[KEY_REPLAYGAIN_MODE] = mode.name }
    fun loadReplayGainMode(): ReplayGainMode = try {
        ReplayGainMode.valueOf(cache[KEY_REPLAYGAIN_MODE] ?: ReplayGainMode.TRACK.name)
    } catch (_: Exception) {
        ReplayGainMode.TRACK
    }

    fun saveReplayGainPreamp(db: Float) = update { it[KEY_REPLAYGAIN_PREAMP] = db }
    fun loadReplayGainPreamp(): Float = cache[KEY_REPLAYGAIN_PREAMP] ?: 0f

    // --- Ecualizador ---

    fun saveEqEnabled(enabled: Boolean) = update { it[KEY_EQ_ENABLED] = enabled }
    fun loadEqEnabled(): Boolean = cache[KEY_EQ_ENABLED] ?: false

    /**
     * Flow reactivo del toggle del EQ: lo cambia PlaybackViewModel (hoja del NowPlaying) y lo
     * observa MusicPlaybackService, que necesita reconstruir la pipeline de audio (y ceder o
     * recuperar el offload) cuando cambia.
     */
    val eqEnabledFlow: Flow<Boolean> =
        prefFlow { it[KEY_EQ_ENABLED] ?: false }

    // --- Toolbar del NowPlaying (orden + barra/overflow de cada acción) ---
    // Reactivo: el NowPlaying observa el flow y la barra se reordena en vivo al guardar en Ajustes.
    val toolbarConfigFlow: Flow<List<ToolbarActionState>> =
        prefFlow { PlayerToolbarConfig.decode(it[KEY_TOOLBAR_CONFIG]) }

    fun loadToolbarConfig(): List<ToolbarActionState> =
        PlayerToolbarConfig.decode(cache[KEY_TOOLBAR_CONFIG])

    fun saveToolbarConfig(list: List<ToolbarActionState>) = update {
        it[KEY_TOOLBAR_CONFIG] = PlayerToolbarConfig.encode(list)
    }

    // --- Contextos reproducidos recientes (sección "Seguir escuchando" del home) ---
    // Historial de "lugares" reanudables (álbum/artista/lista/favoritos/aleatorio/biblioteca), de
    // más reciente a más antiguo. Reactivo: la home se actualiza sola al grabar un contexto.
    val recentContextsFlow: Flow<List<PlaybackContext>> =
        prefFlow { PlaybackContext.decode(it[KEY_RECENT_CONTEXTS]) }

    /** Antepone un contexto al historial (dedup por identidad + tope). Read-modify-write atómico. */
    fun recordContext(ctx: PlaybackContext) = update {
        val current = PlaybackContext.decode(it[KEY_RECENT_CONTEXTS])
        it[KEY_RECENT_CONTEXTS] = PlaybackContext.encode(PlaybackContext.prepend(current, ctx))
    }

    fun clearRecentContexts() = update { it.remove(KEY_RECENT_CONTEXTS) }

    /** Nº de bandas del EQ propio (5 o 10). */
    fun saveEqBandCount(count: Int) = update { it[KEY_EQ_BAND_COUNT] = count }
    fun loadEqBandCount(): Int = (cache[KEY_EQ_BAND_COUNT] ?: 5).let { if (it == 10) 10 else 5 }

    // Refuerzos de graves/agudos: NO van por modo de bandas (a diferencia de las ganancias), son
    // dos peakings anchos fijos que se suman a cualquier curva, así que alternar 5↔10 los conserva.
    fun saveEqBassBoost(db: Float) = update { it[KEY_EQ_BASS_BOOST] = db }
    fun loadEqBassBoost(): Float = cache[KEY_EQ_BASS_BOOST] ?: 0f

    fun saveEqTrebleBoost(db: Float) = update { it[KEY_EQ_TREBLE_BOOST] = db }
    fun loadEqTrebleBoost(): Float = cache[KEY_EQ_TREBLE_BOOST] ?: 0f

    // Las ganancias se guardan POR MODO (clave distinta para 5 y 10 bandas): al alternar
    // el nº de bandas se recupera la curva que el usuario tenía en ese modo, en vez de
    // truncar/estirar una a la otra (las frecuencias centrales no se corresponden).
    private fun eqGainsKey(bandCount: Int) =
        if (bandCount == 10) KEY_EQ_GAINS_10 else KEY_EQ_GAINS

    fun saveEqBandGains(bandCount: Int, gains: FloatArray) = update {
        it[eqGainsKey(bandCount)] = gains.joinToString(",")
    }

    /** Ganancias (dB) del modo de [bandCount] bandas; ceros si nunca se configuró. */
    fun loadEqBandGains(bandCount: Int): FloatArray {
        val raw = cache[eqGainsKey(bandCount)] ?: return FloatArray(bandCount)
        return try {
            val parsed = raw.split(",").map { it.toFloat() }
            FloatArray(bandCount) { i -> parsed.getOrNull(i) ?: 0f }
        } catch (_: Exception) {
            FloatArray(bandCount)
        }
    }

    // --- Presets personalizados del EQ ---
    // Se serializan como un JSON array en una sola clave (org.json, sin Gson → sin regla
    // ProGuard). Cada preset guarda su curva cruda y el modo de bandas de captura; el nombre
    // puede contener comas/saltos, por eso NO se usa el encoding delimitado de las ganancias.

    fun loadCustomEqPresets(): List<EqCustomPreset> =
        parseCustomPresets(cache[KEY_EQ_CUSTOM_PRESETS])

    fun saveCustomEqPresets(presets: List<EqCustomPreset>) = update {
        val arr = JSONArray()
        presets.forEach { p ->
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("bandCount", p.bandCount)
                    .put("gains", JSONArray().apply { p.gains.forEach { put(it.toDouble()) } })
            )
        }
        it[KEY_EQ_CUSTOM_PRESETS] = arr.toString()
    }

    /** Reactivo: la hoja del EQ edita/aplica presets; puede haber varias instancias del ViewModel. */
    val customEqPresetsFlow: Flow<List<EqCustomPreset>> =
        prefFlow { parseCustomPresets(it[KEY_EQ_CUSTOM_PRESETS]) }

    private fun parseCustomPresets(raw: String?): List<EqCustomPreset> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val g = o.getJSONArray("gains")
                EqCustomPreset(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    bandCount = if (o.getInt("bandCount") == 10) 10 else 5,
                    gains = FloatArray(g.length()) { g.getDouble(it).toFloat() }
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Preferir el ecualizador DEL SISTEMA (MIUI/panel estándar): el botón EQ del NowPlaying
     * lo abre directamente en vez de la hoja propia. Activarlo apaga el EQ propio (evita
     * ecualizar dos veces) — eso lo hace el setter del ViewModel, no esta capa.
     */
    fun saveUseSystemEq(enabled: Boolean) = update { it[KEY_USE_SYSTEM_EQ] = enabled }
    fun loadUseSystemEq(): Boolean = cache[KEY_USE_SYSTEM_EQ] ?: false

    /** "No volver a mostrar" del aviso de doble ecualización al encender el EQ propio. */
    fun saveEqConflictWarningSuppressed(suppressed: Boolean) =
        update { it[KEY_EQ_CONFLICT_WARNING_SUPPRESSED] = suppressed }
    fun loadEqConflictWarningSuppressed(): Boolean = cache[KEY_EQ_CONFLICT_WARNING_SUPPRESSED] ?: false

    // --- Onboarding: paso del tope de descargas a medias ---

    /**
     * true mientras el usuario conectó una fuente de NUBE durante el onboarding pero todavía no
     * llegó a fijar el tope de descargas. Sobrevive a un cierre forzado, así que al reabrir se
     * vuelve a ese paso en vez de caer en la biblioteca con el onboarding a medias (y sin haber
     * decidido cuánto espacio puede ocupar el audio).
     *
     * NO es un flag "onboarding completado" (ese sería estado redundante frente a "¿hay alguna
     * fuente?", capaz de quedar en true sin fuentes = biblioteca vacía sin salida). Este solo
     * puede RETENER en el onboarding, nunca sacar de él, y por defecto es false: quien ya tenía
     * la app instalada no ve ningún cambio.
     */
    fun saveOnboardingStoragePending(pending: Boolean) =
        update { it[KEY_ONBOARDING_STORAGE_PENDING] = pending }
    fun loadOnboardingStoragePending(): Boolean = cache[KEY_ONBOARDING_STORAGE_PENDING] ?: false

    /** null = aún no se preguntó (el sync detecta y dispara el diálogo de decisión). */
    fun saveDuplicatePolicy(policy: DuplicatePolicy) = update { it[KEY_DUPLICATE_POLICY] = policy.name }
    fun loadDuplicatePolicy(): DuplicatePolicy? =
        cache[KEY_DUPLICATE_POLICY]?.let { runCatching { DuplicatePolicy.valueOf(it) }.getOrNull() }

    // --- Carpeta de OneDrive que se escanea ---

    /**
     * Ruta relativa a la raíz del OneDrive del usuario (`Music`, `Documentos/Música`…). Cadena
     * vacía = la cuenta entera.
     *
     * **Cambiarla invalida el delta token**: el token describe el estado de UN subárbol concreto,
     * y reutilizarlo apuntando a otra carpeta daría un incremental sobre cambios que no son los de
     * esa carpeta. Por eso [saveOneDriveFolderPath] lo limpia en la misma escritura y no en el
     * llamador — un olvido ahí produciría una biblioteca incoherente muy difícil de diagnosticar.
     */
    fun loadOneDriveFolderPath(): String = readOneDriveFolderPath(cache)
    val oneDriveFolderPathFlow: Flow<String> = prefFlow { readOneDriveFolderPath(it) }

    private fun readOneDriveFolderPath(prefs: Preferences): String =
        prefs[KEY_ONEDRIVE_FOLDER] ?: AppConfig.ONEDRIVE_DEFAULT_FOLDER

    fun saveOneDriveFolderPath(path: String) = update {
        it[KEY_ONEDRIVE_FOLDER] = path.trim().trim('/')
        it.remove(KEY_DELTA_TOKEN)
    }

    // --- Guardado de letras (pantalla de letras + Ajustes → Reproducción) ---

    /**
     * Cómo guardar las letras. [LyricsSaveMode.ASK] = preguntar cada vez; el diálogo lo cambia
     * cuando el usuario marca "no volver a preguntar", y Ajustes puede devolverlo a ASK.
     */
    fun saveLyricsSaveMode(mode: LyricsSaveMode) = update { it[KEY_LYRICS_SAVE_MODE] = mode.name }
    fun loadLyricsSaveMode(): LyricsSaveMode = readLyricsSaveMode(cache)
    val lyricsSaveModeFlow: Flow<LyricsSaveMode> = prefFlow { readLyricsSaveMode(it) }

    private fun readLyricsSaveMode(prefs: Preferences): LyricsSaveMode =
        prefs[KEY_LYRICS_SAVE_MODE]?.let { runCatching { LyricsSaveMode.valueOf(it) }.getOrNull() }
            ?: LyricsSaveMode.ASK

    /**
     * Carpeta (árbol SAF) donde dejar los `.lrc` de las canciones a las que no se les puede
     * escribir al lado: las indexadas por MediaStore, donde el permiso de audio no autoriza a
     * crear archivos no-media. Vacío = no configurada.
     */
    fun saveLyricsFolderUri(uri: String?) = update {
        if (uri == null) it.remove(KEY_LYRICS_FOLDER_URI) else it[KEY_LYRICS_FOLDER_URI] = uri
    }
    fun loadLyricsFolderUri(): String? = cache[KEY_LYRICS_FOLDER_URI]
    val lyricsFolderUriFlow: Flow<String?> = prefFlow { it[KEY_LYRICS_FOLDER_URI] }

    // --- Fotos de artistas (Deezer): política de red (Ajustes → Descargas) ---

    /** Backfill masivo también en red medida (datos móviles), sin preguntar. */
    fun saveArtistPhotosOnMetered(enabled: Boolean) = update { it[KEY_ARTIST_PHOTOS_METERED] = enabled }
    fun loadArtistPhotosOnMetered(): Boolean = cache[KEY_ARTIST_PHOTOS_METERED] ?: false

    /** Mostrar el banner "fotos en pausa" en la pestaña Artistas al estar en datos. */
    fun saveArtistPhotosBannerEnabled(enabled: Boolean) = update { it[KEY_ARTIST_PHOTOS_BANNER] = enabled }
    fun loadArtistPhotosBannerEnabled(): Boolean = cache[KEY_ARTIST_PHOTOS_BANNER] ?: true

    /** Fetch de la foto al abrir el DETALLE de un artista aunque la red sea medida. */
    fun saveArtistPhotoDetailOnMetered(enabled: Boolean) = update { it[KEY_ARTIST_PHOTO_DETAIL_METERED] = enabled }
    fun loadArtistPhotoDetailOnMetered(): Boolean = cache[KEY_ARTIST_PHOTO_DETAIL_METERED] ?: true

    /** Reactivo: lo cambia LibraryViewModel (Ajustes) y lo observa PlaybackViewModel (botón EQ). */
    val useSystemEqFlow: Flow<Boolean> =
        prefFlow { it[KEY_USE_SYSTEM_EQ] ?: false }

    // --- Control de descargas (pausa / stop persistentes) ---

    /**
     * Estado de control de las descargas masivas. Se persiste para que el gate del sync lo
     * respete incluso tras reiniciar el proceso (un ScanWorker que arranca en frío lee esto).
     */
    fun saveDownloadControlState(state: DownloadControlState) = update {
        it[KEY_DOWNLOAD_CONTROL_STATE] = state.name
    }

    fun loadDownloadControlState(): DownloadControlState = try {
        DownloadControlState.valueOf(cache[KEY_DOWNLOAD_CONTROL_STATE] ?: DownloadControlState.ACTIVE.name)
    } catch (_: Exception) {
        DownloadControlState.ACTIVE
    }

    /** Reactivo: lo cambia el Download Manager y lo observan el banner (Library) y el propio panel. */
    val downloadControlStateFlow: Flow<DownloadControlState> =
        prefFlow {
            try {
                DownloadControlState.valueOf(it[KEY_DOWNLOAD_CONTROL_STATE] ?: DownloadControlState.ACTIVE.name)
            } catch (_: Exception) {
                DownloadControlState.ACTIVE
            }
        }

    /**
     * Cierre "una vez" del banner de descargas pausadas/detenidas: lo oculta sin reanudar y se
     * resetea ante la próxima pausa/detención/reanudación (SyncManager). Es distinto del estado
     * de control (que sigue en PAUSED/STOPPED) y del silencio permanente (banner muted).
     */
    fun saveStopBannerDismissed(dismissed: Boolean) = update { it[KEY_STOP_BANNER_DISMISSED] = dismissed }
    fun loadStopBannerDismissed(): Boolean = cache[KEY_STOP_BANNER_DISMISSED] ?: false
    val stopBannerDismissedFlow: Flow<Boolean> =
        prefFlow { it[KEY_STOP_BANNER_DISMISSED] ?: false }

    /**
     * "No volver a mostrar": silencia el banner de descargas PARA SIEMPRE. Nadie lo resetea
     * (a diferencia del cierre "una vez"); el estado de descargas sigue visible y controlable
     * desde el Download Manager, que tiene sus propios botones en la top bar.
     */
    fun saveDownloadBannerMuted(muted: Boolean) = update { it[KEY_DOWNLOAD_BANNER_MUTED] = muted }
    fun loadDownloadBannerMuted(): Boolean = cache[KEY_DOWNLOAD_BANNER_MUTED] ?: false
    val downloadBannerMutedFlow: Flow<Boolean> =
        prefFlow { it[KEY_DOWNLOAD_BANNER_MUTED] ?: false }

    // --- Tope de almacenamiento para descargas (caché LRU) ---

    /**
     * Límite de bytes que puede ocupar el audio descargado de la nube. 0 = sin límite.
     * Al superarse, el sync masivo frena y las descargas por reproducción desalojan las
     * canciones menos/menos-recientemente reproducidas para hacer sitio.
     */
    fun saveStorageLimitBytes(bytes: Long) = update { it[KEY_STORAGE_LIMIT_BYTES] = bytes.coerceAtLeast(0L) }
    fun loadStorageLimitBytes(): Long = cache[KEY_STORAGE_LIMIT_BYTES] ?: 0L
    val storageLimitBytesFlow: Flow<Long> =
        prefFlow { it[KEY_STORAGE_LIMIT_BYTES] ?: 0L }

    // --- Now Playing ---

    fun saveNowPlayingSolidBackground(enabled: Boolean) = update {
        it[KEY_NOW_PLAYING_SOLID_BG] = enabled
    }

    fun loadNowPlayingSolidBackground(): Boolean = cache[KEY_NOW_PLAYING_SOLID_BG] ?: false

    /**
     * Flow reactivo del ajuste de fondo: lo cambia LibraryViewModel (Ajustes) y lo observa
     * PlaybackViewModel (NowPlaying), que son instancias distintas — la caché síncrona no
     * alcanza para propagar el cambio en vivo.
     */
    val nowPlayingSolidBackgroundFlow: Flow<Boolean> =
        prefFlow { it[KEY_NOW_PLAYING_SOLID_BG] ?: false }

    /**
     * Barra de progreso ONDULADA (M3 Expressive) en el NowPlaying, en vez de la píldora plana.
     * Solo aplica al reproductor grande: en el MiniPlayer la barra mide 3dp y la onda no se
     * leería. Default false (la píldora es el diseño actual).
     */
    fun saveNowPlayingWavyProgress(enabled: Boolean) = update {
        it[KEY_NOW_PLAYING_WAVY] = enabled
    }

    fun loadNowPlayingWavyProgress(): Boolean = cache[KEY_NOW_PLAYING_WAVY] ?: false

    /** Reactivo por el mismo motivo que [nowPlayingSolidBackgroundFlow] (Ajustes ↔ NowPlaying). */
    val nowPlayingWavyProgressFlow: Flow<Boolean> =
        prefFlow { it[KEY_NOW_PLAYING_WAVY] ?: false }

    /**
     * Chip de formato del NowPlaying EXTENDIDO (`FLAC · 16 bit · 44.1 kHz`) en vez de solo el
     * contenedor. Se escribe desde dos sitios —el switch de Ajustes → Apariencia y el tap sobre
     * el propio chip—, de ahí que sea reactivo: son instancias de ViewModel distintas.
     */
    fun saveNowPlayingDetailedFormat(enabled: Boolean) = update {
        it[KEY_NOW_PLAYING_DETAILED_FORMAT] = enabled
    }

    fun loadNowPlayingDetailedFormat(): Boolean = cache[KEY_NOW_PLAYING_DETAILED_FORMAT] ?: false

    val nowPlayingDetailedFormatFlow: Flow<Boolean> =
        prefFlow { it[KEY_NOW_PLAYING_DETAILED_FORMAT] ?: false }

    /**
     * Gestos del reproductor: deslizar la carátula para cambiar de canción, deslizar hacia abajo
     * para cerrar el player, deslizar hacia arriba en el mini para abrirlo y doble toque en los
     * laterales de la carátula para saltar unos segundos.
     *
     * Es UN solo ajuste para los cuatro a propósito: son el mismo contrato ("la carátula y el
     * player responden al dedo"), y trocearlo en cuatro switches obligaría al usuario a razonar
     * sobre gestos que aún no descubrió. Encendido por defecto —es lo que todo reproductor hace—
     * y apagable porque conviven con el long-press del selector de color y con el arrastre del
     * slider, y a quien le estorben tiene que poder quitarlos.
     */
    fun savePlayerGestures(enabled: Boolean) = update {
        it[KEY_PLAYER_GESTURES] = enabled
    }

    fun loadPlayerGestures(): Boolean = cache[KEY_PLAYER_GESTURES] ?: DEFAULT_PLAYER_GESTURES

    /** Reactivo por el mismo motivo que [nowPlayingSolidBackgroundFlow] (Ajustes ↔ reproductor). */
    val playerGesturesFlow: Flow<Boolean> =
        prefFlow { it[KEY_PLAYER_GESTURES] ?: DEFAULT_PLAYER_GESTURES }

    // --- Biblioteca ---

    /**
     * Pestañas de la biblioteca: orden + cuáles se muestran. Reactivo porque lo edita Ajustes y
     * lo consume `LibraryScreen` (el pager se reconstruye en vivo al guardar).
     */
    val libraryTabsConfigFlow: Flow<List<LibraryTabState>> =
        prefFlow { LibraryTabsConfig.decode(it[KEY_LIBRARY_TABS_CONFIG]) }

    fun loadLibraryTabsConfig(): List<LibraryTabState> =
        LibraryTabsConfig.decode(cache[KEY_LIBRARY_TABS_CONFIG])

    fun saveLibraryTabsConfig(list: List<LibraryTabState>) = update {
        it[KEY_LIBRARY_TABS_CONFIG] = LibraryTabsConfig.encode(list)
    }

    /**
     * Géneros por coincidencia PARCIAL: con esto activo, abrir "Rock" trae también las canciones
     * etiquetadas "Rock/Metal" o "Hard Rock" (LIKE), no solo las de tag exacto. Los tags GENRE
     * reales son un desastre y no hay forma de normalizarlos sin perder información, así que la
     * decisión es del usuario. Default apagado = lo que se ve es lo que dice el tag.
     */
    fun saveGenrePartialMatch(enabled: Boolean) = update {
        it[KEY_GENRE_PARTIAL_MATCH] = enabled
    }

    fun loadGenrePartialMatch(): Boolean = cache[KEY_GENRE_PARTIAL_MATCH] ?: false

    val genrePartialMatchFlow: Flow<Boolean> =
        prefFlow { it[KEY_GENRE_PARTIAL_MATCH] ?: false }

    // --- Tema ---

    /**
     * Estilo de paleta con el que MaterialKolor genera el ColorScheme a partir del color del
     * álbum. Se guarda el NOMBRE del enum (no el ordinal): reordenar `PaletteStyle` en una
     * futura versión de la librería cambiaría los ordinales y el ajuste guardado pasaría a
     * significar otro estilo en silencio.
     *
     * Default `TonalSpot`, que es el de Material You en Android y el de la propia librería.
     */
    fun saveThemePaletteStyle(styleName: String) = update {
        it[KEY_THEME_PALETTE_STYLE] = styleName
    }

    fun loadThemePaletteStyle(): String = cache[KEY_THEME_PALETTE_STYLE] ?: DEFAULT_PALETTE_STYLE

    /** Reactivo: lo cambia Ajustes y lo observa el tema en MainActivity (instancias distintas). */
    val themePaletteStyleFlow: Flow<String> =
        prefFlow { it[KEY_THEME_PALETTE_STYLE] ?: DEFAULT_PALETTE_STYLE }

    companion object {
        private const val DATASTORE_NAME = "music_player_prefs"
        private const val KEY_SORT_ORDER = "sort_order"
        private val KEY_ARTIST_SORT = stringPreferencesKey("artist_sort_order")
        private val KEY_ALBUM_SORT = stringPreferencesKey("album_sort_order")
        private val KEY_DUPLICATE_POLICY = stringPreferencesKey("duplicate_policy")
        private val KEY_ONBOARDING_STORAGE_PENDING =
            booleanPreferencesKey("onboarding_storage_step_pending")
        private const val QUEUE_DELIMITER = "" // Unit Separator

        // DataStore keys tipadas
        private val KEY_KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")

        private val KEY_REPLAYGAIN_MODE = stringPreferencesKey("replaygain_mode")
        private val KEY_REPLAYGAIN_PREAMP = floatPreferencesKey("replaygain_preamp")
        private val KEY_EQ_ENABLED = booleanPreferencesKey("eq_enabled")
        private val KEY_EQ_GAINS = stringPreferencesKey("eq_band_gains")
        private val KEY_EQ_GAINS_10 = stringPreferencesKey("eq_band_gains_10")
        private val KEY_EQ_BAND_COUNT = intPreferencesKey("eq_band_count")
        private val KEY_EQ_BASS_BOOST = floatPreferencesKey("eq_bass_boost")
        private val KEY_EQ_TREBLE_BOOST = floatPreferencesKey("eq_treble_boost")
        private val KEY_EQ_CUSTOM_PRESETS = stringPreferencesKey("eq_custom_presets")
        private val KEY_EQ_CONFLICT_WARNING_SUPPRESSED = booleanPreferencesKey("eq_conflict_warning_suppressed")
        private val KEY_USE_SYSTEM_EQ = booleanPreferencesKey("use_system_eq")
        private val KEY_ARTIST_PHOTOS_METERED = booleanPreferencesKey("artist_photos_metered")
        private val KEY_ARTIST_PHOTOS_BANNER = booleanPreferencesKey("artist_photos_banner_enabled")
        private val KEY_ARTIST_PHOTO_DETAIL_METERED = booleanPreferencesKey("artist_photo_detail_metered")
        private val KEY_TOOLBAR_CONFIG = stringPreferencesKey("player_toolbar_config")
        private val KEY_LIBRARY_TABS_CONFIG = stringPreferencesKey("library_tabs_config")
        private val KEY_NOW_PLAYING_DETAILED_FORMAT = booleanPreferencesKey("now_playing_detailed_format")
        private val KEY_GENRE_PARTIAL_MATCH = booleanPreferencesKey("genre_partial_match")
        private val KEY_RECENT_CONTEXTS = stringPreferencesKey("recent_playback_contexts")
        private val KEY_NOW_PLAYING_SOLID_BG = booleanPreferencesKey("now_playing_solid_bg")
        private val KEY_THEME_PALETTE_STYLE = stringPreferencesKey("theme_palette_style")

        /** Igual que el default de Material You y el de MaterialKolor. */
        const val DEFAULT_PALETTE_STYLE = "TonalSpot"
        private val KEY_NOW_PLAYING_WAVY = booleanPreferencesKey("now_playing_wavy_progress")
        private val KEY_PLAYER_GESTURES = booleanPreferencesKey("player_gestures")

        /** Los gestos vienen ENCENDIDOS: es el comportamiento que espera cualquiera. */
        private const val DEFAULT_PLAYER_GESTURES = true
        private val KEY_DOWNLOAD_CONTROL_STATE = stringPreferencesKey("download_control_state")
        private val KEY_STOP_BANNER_DISMISSED = booleanPreferencesKey("download_stop_banner_dismissed")
        private val KEY_DOWNLOAD_BANNER_MUTED = booleanPreferencesKey("download_banner_muted")
        private val KEY_STORAGE_LIMIT_BYTES = longPreferencesKey("download_storage_limit_bytes")

        private val KEY_LAST_QUEUE = stringPreferencesKey("last_queue_ids")
        private val KEY_LAST_INDEX = intPreferencesKey("last_index")
        private val KEY_LAST_POSITION = longPreferencesKey("last_position")
        private val KEY_LAST_SONG_ID = stringPreferencesKey("last_song_id")
        private val KEY_SHUFFLE_ENABLED = booleanPreferencesKey("shuffle_enabled")
        private val KEY_OFFLOAD_BROKEN = booleanPreferencesKey("audio_offload_broken")
        private val KEY_ORIGINAL_QUEUE = stringPreferencesKey("original_queue_ids")
        private val KEY_DELTA_TOKEN = stringPreferencesKey("delta_token")
        /** Legacy: carpeta local ÚNICA. Solo se LEE, para migrar al set (ver loadLocalFolderUris). */
        private val KEY_LOCAL_FOLDER_URI = stringPreferencesKey("local_folder_uri")
        private val KEY_LOCAL_FOLDER_URIS = stringSetPreferencesKey("local_folder_uris")
        /** Carpetas guardadas al activar el escaneo del dispositivo, para restaurarlas al apagarlo. */
        private val KEY_LOCAL_FOLDER_URIS_STASH = stringSetPreferencesKey("local_folder_uris_stash")
        private val KEY_SCAN_WHOLE_DEVICE = booleanPreferencesKey("scan_whole_device")
        private val KEY_MANUAL_COLOR_IDS = stringSetPreferencesKey("manual_color_song_ids")
        private val KEY_ONEDRIVE_FOLDER = stringPreferencesKey("onedrive_folder_path")
        private val KEY_LYRICS_SAVE_MODE = stringPreferencesKey("lyrics_save_mode")
        private val KEY_LYRICS_FOLDER_URI = stringPreferencesKey("lyrics_folder_uri")

        /**
         * DataStore de preferencias con migración automática desde SharedPreferences.
         * Los usuarios existentes conservarán sort order, delta token, cola, etc.
         */
        private val Context.musicPrefsDataStore: DataStore<Preferences> by preferencesDataStore(
            name = DATASTORE_NAME,
            produceMigrations = { ctx -> listOf(SharedPreferencesMigration(ctx, DATASTORE_NAME)) }
        )
    }
}
