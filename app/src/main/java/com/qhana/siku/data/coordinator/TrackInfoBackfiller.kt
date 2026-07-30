package com.qhana.siku.data.coordinator

import android.net.Uri
import android.util.Log
import com.qhana.siku.data.model.Song
import com.qhana.siku.data.preferences.MusicPreferences
import com.qhana.siku.data.repository.IMusicRepository
import com.qhana.siku.data.source.MusicSourceRegistry
import com.qhana.siku.data.util.AudioFileAnalyzer
import com.qhana.siku.data.util.NetworkManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Migración de datos de UNA sola pasada: rellena número de pista y año en la biblioteca que ya
 * existía antes de que esos tags se leyeran.
 *
 * `songs.trackNumber` y `songs.year` son columnas desde la v12, pero nada las escribía nunca. El
 * efecto visible era que `ORDER BY trackNumber ASC, title ASC` (detalle de álbum y de artista)
 * degeneraba en orden alfabético: un álbum se mostraba con sus temas desordenados.
 *
 * Corregir la extracción arregla lo que entre a partir de ahora, pero no la biblioteca ya
 * escaneada — de ahí esta fase. Cubre los dos casos por el camino más barato de cada uno:
 *  - **audio en el dispositivo** (`file://` descargado o `content://` local): se abre el archivo,
 *    sin red;
 *  - **nube sin descargar**: se pide solo la cabecera con `Range` (`fetchLightMetadata`), y por
 *    eso ese tramo exige WiFi. Sin WiFi la pasada se limita a lo local y NO se cierra, así que el
 *    próximo sync con WiFi termina el trabajo.
 *
 * **Por qué el cierre va en preferencias y no en una columna centinela**: el trabajo es finito y
 * ocurre una vez. La lista "las que están a 0" no se auto-vacía —un archivo sin el tag sigue a 0
 * después de mirarlo—, así que sin el flag se re-analizaría la biblioteca entera en cada sync.
 * Es la diferencia con `artworkAttemptedAt`, que sella un estado permanente porque la carátula
 * puede llegar por otros caminos más tarde.
 */
@Singleton
class TrackInfoBackfiller @Inject constructor(
    private val musicRepository: IMusicRepository,
    private val audioFileAnalyzer: AudioFileAnalyzer,
    private val sourceRegistry: MusicSourceRegistry,
    private val networkManager: NetworkManager,
    private val musicPreferences: MusicPreferences
) {

    private val counterLock = Mutex()

    /**
     * @param isStopped stop cooperativo (logout / pull-to-refresh): se consulta por canción.
     * @return cuántas filas recibieron número de pista. 0 también significa "ya estaba hecho".
     */
    suspend fun run(isStopped: () -> Boolean): Int = coroutineScope {
        if (musicPreferences.loadTrackInfoBackfilled()) return@coroutineScope 0

        // Con WiFi se barre TODO; sin él, solo lo que no cuesta red.
        val canUseNetwork = networkManager.isWifi()
        val pending = musicRepository.getSongsNeedingTrackInfo(localOnly = !canUseNetwork)
        if (pending.isEmpty()) {
            // Nada que mirar. Solo se cierra si la pasada fue COMPLETA: una biblioteca sin
            // canciones locales pendientes puede tener de nube esperando a que haya WiFi.
            if (canUseNetwork) musicPreferences.saveTrackInfoBackfilled(true)
            return@coroutineScope 0
        }

        Log.i(TAG, "Backfill de pista/año: ${pending.size} canciones (red=$canUseNetwork)")
        var updated = 0
        val semaphore = Semaphore(PARALLELISM)
        pending.map { song ->
            launch {
                if (isStopped()) return@launch
                semaphore.withPermit {
                    if (isStopped()) return@withPermit
                    if (backfill(song)) counterLock.withLock { updated++ }
                }
            }
        }.forEach { it.join() }

        // Se cierra solo si la pasada llegó al final Y pudo abarcar toda la biblioteca. Cortarla
        // a mitad (logout, pull-to-refresh) o correrla sin WiFi deja trabajo pendiente, y cerrar
        // ahí condenaría a esas canciones a no ordenarse nunca.
        if (!isStopped() && canUseNetwork) {
            musicPreferences.saveTrackInfoBackfilled(true)
            Log.i(TAG, "Backfill de pista/año completado: $updated actualizadas")
        }
        updated
    }

    /** @return true si la canción declaraba número de pista. */
    private suspend fun backfill(song: Song): Boolean = try {
        when {
            song.path.startsWith(FILE_SCHEME) || song.path.startsWith(CONTENT_SCHEME) ->
                backfillFromFile(song)
            else -> backfillFromHeader(song)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Una canción ilegible no debe cortar la pasada: se queda a 0 y ordenará por título,
        // que es exactamente como estaba antes.
        Log.w(TAG, "No se pudo leer ${song.id}: ${e.message}")
        false
    }

    private suspend fun backfillFromFile(song: Song): Boolean {
        val analysis = if (song.path.startsWith(FILE_SCHEME)) {
            audioFileAnalyzer.analyzeFile(File(Uri.parse(song.path).path ?: return false))
        } else {
            audioFileAnalyzer.analyzeContentUri(Uri.parse(song.path), song.title)
        }
        if (!analysis.isValid) return false
        return write(song, analysis.trackNumber, analysis.year)
    }

    /**
     * Nube sin descargar: la cabecera basta y cuesta una fracción del archivo. Reusa el mismo
     * `fetchLightMetadata` de la fase de metadata ligera, así que un proveedor que no lo
     * implemente devuelve null y sus canciones se saltan esta pasada sin ruido.
     */
    private suspend fun backfillFromHeader(song: Song): Boolean {
        val meta = sourceRegistry.fetchLightMetadata(song) ?: return false
        return write(song, meta.trackNumber, meta.year)
    }

    /**
     * Se escribe aunque no se haya declarado nada (0/0, un no-op sobre una fila que ya está a 0):
     * la fila queda igual y es el flag quien evita volver a mirarla.
     */
    private suspend fun write(song: Song, trackNumber: Int, year: Int): Boolean {
        musicRepository.updateTrackInfo(song.id, trackNumber, year)
        return trackNumber > 0
    }

    private companion object {
        const val TAG = "TrackInfoBackfiller"

        /**
         * Conservador a propósito: la rama local abre archivos (CPU + I/O) y la remota pide
         * cabeceras por la red, y esta fase corre al final de un sync que ya dejó el dispositivo
         * cargado. No es un límite de rate remoto — las peticiones de Graph ya van serializadas
         * por el RequestCoordinator.
         */
        const val PARALLELISM = 4

        const val FILE_SCHEME = "file://"
        const val CONTENT_SCHEME = "content://"
    }
}
