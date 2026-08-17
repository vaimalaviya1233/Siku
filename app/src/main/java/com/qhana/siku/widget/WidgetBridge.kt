package com.qhana.siku.widget

import android.content.Context
import com.qhana.siku.data.model.PlaybackState
import com.qhana.siku.data.model.Song
import com.qhana.siku.data.repository.ArtworkRepository
import com.qhana.siku.data.repository.IPlaylistRepository
import com.qhana.siku.player.MusicController
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Puente reproductor → widgets: observa el estado del [MusicController] y, ante cualquier
 * cambio relevante (canción, play/pausa, cola, favorito), persiste un [WidgetSnapshot]
 * y pide el re-render de ambos widgets. Vive mientras viva el proceso (lo arranca
 * MusicPlayerApp); si el proceso muere, los widgets conservan su último render y el
 * snapshot en disco los rehidrata en el siguiente arranque.
 */
@Singleton
class WidgetBridge @Inject constructor(
    @ApplicationContext private val context: Context,
    private val musicController: MusicController,
    private val playlistRepository: IPlaylistRepository,
    private val artworkRepository: ArtworkRepository
) {

    private companion object {
        const val UP_NEXT_LIMIT = 30
    }

    /**
     * Lo que el snapshot necesita saber, sin construirlo todavía.
     *
     * Existe para que el [debounce] vaya ANTES del trabajo y no después: el snapshot cuesta una
     * consulta de colores más el recorte de la cola, y el `combine` emite por cosas que no son un
     * cambio de canción (cada transición de estado del player, cada avance de índice). Con el
     * debounce detrás, una ráfaga —restaurar la sesión, saltar cinco pistas seguidas— pagaba el
     * cómputo entero N veces para tirar N−1 resultados; delante, se paga una.
     */
    private data class WidgetSignals(
        val song: Song?,
        val state: PlaybackState,
        val playlist: List<Song>,
        val index: Int,
        val favorites: Set<String>
    )

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    fun start(scope: CoroutineScope) {
        scope.launch {
            var hadSession = false
            // Solo observamos el estado del reproductor cuando hay al menos un widget colocado. La
            // fuente `getFavoritesIds` está suscrita a `songs` (JOIN), así que un sync masivo la
            // re-ejecuta decenas de veces por segundo; sostener eso en un proceso sin widgets —el
            // que levanta un worker— era CPU y consultas para nadie. `WidgetPresence.changes` reemite
            // al añadirse/quitarse un widget y `flatMapLatest` engancha o suelta el `combine` en
            // consecuencia; su semilla evalúa la presencia al arrancar. Ver [WidgetPresence].
            WidgetPresence.changes
                .map { WidgetPresence.hasAnyWidget(context) }
                .distinctUntilChanged()
                .flatMapLatest { hasWidgets ->
                    if (!hasWidgets) emptyFlow()
                    else playerStateFlow()
                }
                .collect { snapshot ->
                    when {
                        snapshot.songId != null -> {
                            hadSession = true
                            WidgetUpdater.push(context, snapshot)
                        }
                        // Null DESPUÉS de haber tenido sesión = stop() real (logout): limpiar
                        // los widgets en vez de dejarlos mostrando una canción que ya no existe.
                        hadSession -> {
                            hadSession = false
                            WidgetUpdater.push(context, WidgetSnapshot.EMPTY)
                        }
                        // Null SIN sesión previa = proceso recién levantado (currentSong aún
                        // null hasta que el restore termina): no pisar el snapshot del disco.
                    }
                }
        }
    }

    /**
     * El estado del reproductor convertido en [WidgetSnapshot], listo para renderizar. Se separa del
     * gate de presencia para que [flatMapLatest] pueda engancharlo y soltarlo entero.
     */
    @OptIn(FlowPreview::class)
    private fun playerStateFlow() =
            combine(
                musicController.currentSong,
                musicController.playbackState,
                musicController.playlist,
                musicController.currentIndex,
                playlistRepository.getFavoritesIds()
            ) { song, state, playlist, index, favorites ->
                WidgetSignals(song, state, playlist, index, favorites)
            }
                // Coalesce de ráfagas (restore de sesión, saltos rápidos) en un solo render.
                .debounce(150)
                .distinctUntilChanged()
                .map { s ->
                    // Acento de la carátula, como el NowPlaying (caché RAM→BD→extracción; para la
                    // canción en reproducción casi siempre es un hit de RAM porque el player ya
                    // la pidió). runCatching: un fallo de extracción no debe tumbar el snapshot.
                    val song = s.song
                    val colors = song?.let {
                        runCatching { artworkRepository.getAlbumColors(it) }.getOrNull()
                    }
                    WidgetSnapshot(
                        songId = song?.id,
                        title = song?.title,
                        artist = song?.artist,
                        artPath = song?.albumArtUriString,
                        isPlaying = s.state == PlaybackState.PLAYING ||
                            s.state == PlaybackState.BUFFERING,
                        isFavorite = song?.id != null && song.id in s.favorites,
                        currentIndex = s.index,
                        upNext = s.playlist
                            .drop((s.index + 1).coerceAtLeast(0))
                            .take(UP_NEXT_LIMIT)
                            .mapIndexed { offset, item ->
                                WidgetQueueItem(s.index + 1 + offset, item.title, item.artist)
                            },
                        accentLight = colors?.primary,
                        accentDark = colors?.secondary
                    )
                }
                .distinctUntilChanged()
}
