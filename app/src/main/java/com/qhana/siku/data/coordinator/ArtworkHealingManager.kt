package com.qhana.siku.data.coordinator

import androidx.core.net.toUri
import com.qhana.siku.data.config.AppConfig
import com.qhana.siku.data.model.Song
import com.qhana.siku.data.repository.ArtworkRepository
import com.qhana.siku.data.repository.IMusicRepository
import com.qhana.siku.data.util.AppLogger
import com.qhana.siku.data.util.AudioFileAnalyzer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mantiene sanas las carátulas de la biblioteca. Tres entradas, todas permanentes y todas
 * baratas cuando no hay nada que hacer:
 *
 *  - [heal] en cada arranque: URIs que apuntan a un archivo que ya no existe (una limpieza de
 *    caché del sistema, un borrado externo).
 *  - [resolvePendingArtwork] al final de cada sync: canciones sin portada cuya resolución sigue
 *    pendiente. Cubre por igual a las recién indexadas y a las que arrastran el fallo de
 *    escritura de la 1.0.1, sin un camino especial para estas últimas.
 *  - [pruneCovers] al final de cada sync: archivos que ya no referencia nadie.
 *
 * Estrategia de recuperación, común a las dos primeras:
 *  - Si el audio está en el dispositivo —descargado (`file://`) o de la fuente local
 *    (`content://`)— re-extrae la carátula embebida del archivo.
 *  - Si eso no da nada, hereda la del álbum si alguna hermana conserva una viva: las
 *    carátulas se comparten por álbum (`setAlbumArt`), así que lo normal es que la que
 *    falta sea el archivo, no la imagen.
 *  - Si tampoco, deja el URI en null (placeholder limpio). Para una canción de nube aún
 *    sin descargar, `finalizeDownload` la repondrá al descargarla.
 *  - En todos los casos invalida los colores asociados para que `ArtworkWorker` los
 *    regenere en su próximo ciclo.
 *
 * Idempotente: re-correrlo no produce trabajo cuando todos los URIs son válidos.
 */
@Singleton
class ArtworkHealingManager @Inject constructor(
    private val musicRepository: IMusicRepository,
    private val artworkRepository: ArtworkRepository,
    private val audioFileAnalyzer: AudioFileAnalyzer,
    private val appLogger: AppLogger
) {
    companion object { private const val TAG = "ArtworkHealing" }

    private val mutex = Mutex()

    /**
     * Resultado de mirar el audio, en TRES estados y no en dos.
     *
     * La diferencia entre [NoArt] y [Unavailable] es la que decide si el intento se sella: un
     * archivo que se leyó entero y no trae portada es un hecho definitivo (no hay por qué
     * volver a abrirlo nunca), mientras que un audio que todavía no está en el dispositivo, o
     * que el retriever no pudo leer, no ha contestado nada. Colapsar los dos en "null" era lo
     * que hacía que una canción de nube sin descargar —o un análisis que se pasó de timeout
     * durante un sync masivo— quedara marcada como "ya se intentó" sin haberse podido intentar.
     */
    private sealed interface Extraction {
        data class Found(val uri: String) : Extraction
        data object NoArt : Extraction
        data object Unavailable : Extraction
    }

    suspend fun heal() = mutex.withLock {
        withContext(Dispatchers.IO) { healOrphans() }
    }

    /**
     * Barre las carátulas que dejaron de estar referenciadas (portadas sustituidas por una
     * reedición de tags en el origen).
     *
     * Va al FINAL del sync, no en el arranque, y esa es toda su seguridad: el escaneo escribe la
     * carátula al analizar el archivo pero inserta la fila por lotes, así que mientras corre hay
     * portadas legítimas que todavía no referencia nadie. Esperar a que el sync termine —una
     * señal— es lo que garantiza que "sin referencias" significa de verdad "sobra", sin depender
     * de ninguna ventana de tiempo.
     */
    suspend fun pruneCovers() = mutex.withLock {
        withContext(Dispatchers.IO) {
            val pruned = audioFileAnalyzer.pruneUnreferencedCovers {
                musicRepository.getReferencedArtUris()
            }
            if (pruned > 0) appLogger.lifecycle("$TAG: $pruned carátula(s) sin referencias eliminada(s)")
        }
    }

    /** URIs que apuntan a un archivo que ya no está. */
    private suspend fun healOrphans() {
        val songs = musicRepository.getSongsWithLocalArt()
        if (songs.isEmpty()) return

        val orphans = songs.filter { song ->
            val artPath = song.albumArtUri?.toString()?.removePrefix("file://") ?: return@filter false
            !File(artPath).exists()
        }
        if (orphans.isEmpty()) return

        appLogger.lifecycle("$TAG: ${orphans.size} carátula(s) huérfana(s) detectada(s)")
        var recovered = 0
        // Las que se quedan sin portada vuelven a la cola de pendientes: su sello describía un
        // estado que ya no existe (había una portada y se perdió), y sin levantarlo la canción
        // quedaría fuera de [resolvePendingArtwork] para siempre.
        val reopened = ArrayList<String>()

        for (song in orphans) {
            try {
                val newArtUri = (extractArt(song) as? Extraction.Found)?.uri ?: inheritFromAlbum(song)

                musicRepository.updateAlbumArtUri(song.id, newArtUri)
                artworkRepository.invalidateCache(song.id)
                if (newArtUri != null) recovered++ else reopened.add(song.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                appLogger.error("$TAG: error en ${song.id}: ${e.message}")
            }
        }
        if (reopened.isNotEmpty()) musicRepository.clearArtworkAttempted(reopened)
        appLogger.lifecycle("$TAG: recuperadas=$recovered, limpiadas=${reopened.size}")
    }

    /**
     * Resuelve la carátula de las canciones que la tienen PENDIENTE: sin portada y sin un
     * intento CONCLUYENTE previo (`artworkAttemptedAt IS NULL`).
     *
     * Es una regla permanente del sistema, no un backfill: la indexación puede dejar una canción
     * sin portada por muchos motivos —el archivo no traía arte, el análisis falló, la escritura
     * falló— y ninguno de ellos se reintenta solo, porque el escaneo salta los ids ya indexados
     * y [healOrphans] únicamente mira URIs rotos. Esta fase es la que cierra ese hueco, para las
     * canciones de ayer y las de dentro de tres versiones.
     *
     * Que además repare las bibliotecas de la 1.0.1 —donde el id local llevaba `/` y la
     * escritura fallaba en silencio— es una consecuencia de la migración v25, que deja la
     * columna en NULL para todo lo ya indexado. No hay un camino especial que retirar luego.
     *
     * Va al FINAL del sync, después de la metadata ligera y de las descargas, no antes:
     *  - antes, las canciones de nube recién descubiertas todavía llevan el centinela
     *    `Unknown Album` y su audio no está en el dispositivo, así que no hay nada que mirar;
     *  - después, cada fila tiene su álbum real y las que se bajaron traen su portada, que es
     *    justo lo que las demás pueden heredar.
     * También necesita los ids locales ya migrados por el discover: reparar en paralelo
     * escribiría sobre filas que `migrateLegacyId` está a punto de borrar.
     *
     * @param localOnly limita el trabajo a la fuente local. Lo usa el refresco en primer plano
     *        (`SyncManager.refreshLocalSources`), que no toca la red: sin este filtro cargaría
     *        en cada vuelta a la app las canciones de nube pendientes, que ahí no puede resolver.
     */
    suspend fun resolvePendingArtwork(localOnly: Boolean = false) = mutex.withLock {
        withContext(Dispatchers.IO) { resolvePendingArtworkInternal(localOnly) }
    }

    private suspend fun resolvePendingArtworkInternal(localOnly: Boolean) {
        val songs = musicRepository.getSongsWithPendingArtwork(localOnly)
        if (songs.isEmpty()) return

        appLogger.lifecycle("$TAG: resolviendo carátula de ${songs.size} canción(es)")
        var recovered = 0
        // La lista viene agrupada por álbum, así que la portada del disco en curso se consulta
        // UNA vez y sirve para todas sus canciones. La herencia va antes que la re-extracción
        // (al revés que en el healing de huérfanas): aquí se recorre la biblioteca entera y una
        // consulta es órdenes de magnitud más barata que abrir un MediaMetadataRetriever.
        var currentAlbum: String? = null
        var albumArt: String? = null
        val attempted = ArrayList<String>(songs.size)

        for (song in songs) {
            try {
                // Las canciones sin álbum no forman grupo: cada una va por su cuenta y su
                // portada NO se comparte con nadie (ver AppConfig.isUnknownAlbum).
                val groupable = !AppConfig.isUnknownAlbum(song.album)
                if (!groupable) {
                    currentAlbum = null
                    albumArt = null
                } else if (song.album != currentAlbum) {
                    currentAlbum = song.album
                    albumArt = inheritFromAlbum(song)
                    // Una sola escritura reparte la portada por todo el disco: `setAlbumArt`
                    // alcanza a las hermanas sin portada, que son justo las de esta lista.
                    if (albumArt != null) musicRepository.setAlbumArt(song.album, albumArt)
                }

                if (albumArt != null) {
                    // Ya la cubrió el setAlbumArt del álbum: heredar es un intento concluyente.
                    artworkRepository.invalidateCache(song.id)
                    attempted.add(song.id)
                    recovered++
                    continue
                }

                when (val extraction = extractArt(song)) {
                    is Extraction.Found -> {
                        if (groupable) {
                            // Lo que acaba de salir del archivo vale para el resto del disco.
                            musicRepository.setAlbumArt(song.album, extraction.uri)
                            albumArt = extraction.uri
                        } else {
                            musicRepository.updateAlbumArtUri(song.id, extraction.uri)
                        }
                        artworkRepository.invalidateCache(song.id)
                        attempted.add(song.id)
                        recovered++
                    }
                    // El archivo se leyó y no trae portada: se sella para que deje de aparecer
                    // en esta lista, o la fase pasaría de ser barata a re-analizar la
                    // biblioteca entera en cada sync.
                    Extraction.NoArt -> attempted.add(song.id)
                    // No se pudo mirar (audio sin descargar, ilegible, disco lleno): NO se
                    // sella. Es la única forma de distinguir "esta canción no tiene carátula"
                    // de "todavía no he podido verla", y es lo que permite que las pistas que
                    // el tope de almacenamiento dejó sin bajar hereden la portada en cuanto
                    // una hermana suya se descargue.
                    Extraction.Unavailable -> Unit
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                appLogger.error("$TAG: error resolviendo ${song.id}: ${e.message}")
            }
        }
        // Al final y no por canción: si esto se cancela a medias, lo no sellado se reintenta en
        // el siguiente sync, que es exactamente lo que se quiere.
        if (attempted.isNotEmpty()) musicRepository.markArtworkAttempted(attempted)
        appLogger.lifecycle("$TAG: carátulas resueltas=$recovered de ${songs.size} pendientes")
    }

    /**
     * Mira el audio si sigue en el dispositivo. Las dos formas cuentan: `file://` para lo que
     * descargó la app y `content://` para la fuente local, que NUNCA tiene ruta de archivo
     * (mirar solo `file://` dejaba a toda la biblioteca local sin recuperación posible).
     *
     * Devuelve [Extraction.Unavailable] —y no "sin portada"— siempre que no haya llegado a leer
     * el archivo: `isValid = false` es como `AudioFileAnalyzer` reporta tanto un timeout como un
     * audio ilegible, y ninguno de los dos autoriza a dar el asunto por cerrado.
     */
    private suspend fun extractArt(song: Song): Extraction {
        val path = song.path
        val analysis = when {
            path.startsWith("file://") -> {
                val audioFile = File(path.removePrefix("file://"))
                if (!audioFile.exists()) return Extraction.Unavailable
                audioFileAnalyzer.analyzeFile(audioFile)
            }
            path.startsWith("content://") ->
                audioFileAnalyzer.analyzeContentUri(path.toUri(), song.title)
            else -> return Extraction.Unavailable
        }
        if (!analysis.isValid) return Extraction.Unavailable
        val art = analysis.embeddedArt ?: return Extraction.NoArt
        // Si la escritura falla (disco lleno) tampoco es concluyente: hay portada, no se pudo
        // guardar, y el próximo intento debe volver a probar.
        return audioFileAnalyzer.persistArtwork(art)
            ?.let { Extraction.Found(it) }
            ?: Extraction.Unavailable
    }

    /**
     * Carátula viva de otra canción del mismo álbum. Se comprueba que el archivo exista: el
     * álbum puede estar respondiendo con otro URI huérfano que aún no se ha procesado, y
     * propagarlo solo movería el problema de sitio.
     */
    private suspend fun inheritFromAlbum(song: Song): String? {
        if (AppConfig.isUnknownAlbum(song.album)) return null
        val uri = musicRepository.getAlbumArtUri(song.album) ?: return null
        if (!uri.startsWith("file://")) return uri
        return uri.takeIf { File(it.removePrefix("file://")).exists() }
    }
}
