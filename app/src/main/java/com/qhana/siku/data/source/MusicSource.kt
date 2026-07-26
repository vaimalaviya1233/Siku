package com.qhana.siku.data.source

import com.qhana.siku.data.model.Song
import com.qhana.siku.data.model.SourceType

/**
 * Abstracción de un proveedor de música (OneDrive, carpeta local, …). Fase 2: seam para las
 * operaciones específicas del proveedor, de modo que agregar una fuente nueva no obligue a
 * tocar el player ni el pipeline genérico.
 *
 * El descubrimiento (scan/delta) se añade a esta interfaz en el siguiente incremento; por ahora
 * cubre la resolución de reproducción y la extracción de metadata, que es lo que el player
 * consumía directo de `OneDriveRepository`.
 */
interface MusicSource {
    /** Tipo de fuente que maneja esta implementación (se rutea por `song.sourceType`). */
    val type: SourceType

    /**
     * ¿El usuario configuró esta fuente? (OneDrive: hay sesión; local: hay carpeta elegida).
     * El orquestador sólo llama a [discover] sobre las fuentes configuradas — así una app
     * "sólo local" no falla por auth, y una "sólo nube" no escanea carpetas inexistentes.
     */
    suspend fun isConfigured(): Boolean

    /**
     * Descubre el contenido de la fuente y aplica los cambios en la BD (upsert/delete):
     * OneDrive = delta de Graph; local (Fase 3) = walk de la carpeta. Reporta progreso y
     * respeta el stop cooperativo vía [ctx]. Devuelve los contadores de la corrida.
     *
     * Lanza [SourceAuthException] si la fuente requiere auth y esta falla.
     */
    suspend fun discover(force: Boolean, ctx: DiscoverContext): DiscoverResult

    /**
     * URL/URI fresca para descargar o hacer stream de [song]. Para cloud resuelve una URL
     * firmada (con caché TTL); para fuentes locales devuelve el path tal cual. `null` si no se
     * pudo resolver. [forceRefresh] invalida cualquier caché previa.
     */
    suspend fun resolveDownloadUrl(song: Song, forceRefresh: Boolean = false): String?

    /** Completa la metadata de [song] leyendo los tags del archivo local ya descargado. */
    suspend fun extractMetadata(song: Song): Song

    /**
     * Metadata (y carátula) SIN traerse el audio: es lo que permite que la biblioteca se vea
     * entera —artista, álbum, portada— en minutos, mucho antes de que las descargas terminen.
     *
     * Cada proveedor decide CÓMO: OneDrive pide los primeros KB del archivo con `Range` y lee la
     * cabecera de tags; otro proveedor podría tener un endpoint de metadata. Devolver `null`
     * significa "esta fuente no sabe hacerlo" y NO es un error: el orquestador sigue con el camino
     * de siempre (los tags se leen al descargar o al reproducir), así que ninguna fuente se rompe
     * por no implementarlo — de ahí el default.
     */
    suspend fun fetchLightMetadata(song: Song): LightMetadata? = null
}

/**
 * Metadata leída sin descargar la canción.
 *
 * @param artwork portada que vino en el mismo trozo que los tags (coste cero).
 * @param fetchArtwork petición aparte para la portada cuando NO cabía en ese trozo. Se expone como
 *        función y no como offset para que el orquestador no tenga que saber nada del formato: la
 *        llama solo si de verdad quiere esa imagen (p. ej. si el álbum aún no tiene ninguna), y
 *        así una portada se pide UNA vez por álbum en lugar de una por canción.
 */
data class LightMetadata(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val genre: String? = null,
    /** 0 si la cabecera no la declara; entonces la fila conserva la duración que ya tuviera. */
    val durationMs: Long = 0L,
    val artwork: ByteArray? = null,
    val fetchArtwork: (suspend () -> ByteArray?)? = null
) {
    /** Sin texto útil no vale la pena escribir en BD (se seguiría viendo "Unknown Artist"). */
    val hasText: Boolean get() = !artist.isNullOrBlank() || !album.isNullOrBlank() || !title.isNullOrBlank()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LightMetadata) return false
        return title == other.title && artist == other.artist && album == other.album &&
            albumArtist == other.albumArtist && genre == other.genre &&
            (artwork?.contentEquals(other.artwork) ?: (other.artwork == null))
    }

    override fun hashCode(): Int {
        var result = title?.hashCode() ?: 0
        result = 31 * result + (artist?.hashCode() ?: 0)
        result = 31 * result + (album?.hashCode() ?: 0)
        result = 31 * result + (albumArtist?.hashCode() ?: 0)
        result = 31 * result + (genre?.hashCode() ?: 0)
        result = 31 * result + (artwork?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * Contexto que el orquestador ([com.qhana.siku.data.coordinator.SyncManager]) pasa a
 * [MusicSource.discover]: cómo reportar progreso de escaneo y cómo consultar el stop cooperativo
 * (logout / pull-to-refresh) — infraestructura compartida que vive en el orquestador, no en la fuente.
 */
class DiscoverContext(
    val reportScanning: (found: Int, message: String) -> Unit,
    val isStopped: () -> Boolean
)

/** Contadores de una corrida de [MusicSource.discover]. */
data class DiscoverResult(val added: Int, val deleted: Int)

/** La fuente requiere autenticación y esta falló (mapea a SyncOutcome.Failed(isAuthError=true)). */
class SourceAuthException(message: String) : Exception(message)

/**
 * La carpeta configurada de la fuente no existe. Se distingue de un error de red cualquiera para
 * poder decir QUÉ carpeta falta: el usuario tiene que poder relacionarlo con la que eligió, y un
 * "HTTP 404" no lleva a ninguna parte.
 */
class SourceFolderMissingException(val folder: String) : Exception("No se encontró la carpeta \"$folder\"")
