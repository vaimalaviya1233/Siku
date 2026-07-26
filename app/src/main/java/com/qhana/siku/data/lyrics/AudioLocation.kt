package com.qhana.siku.data.lyrics

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.qhana.siku.data.model.Song
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dónde vive REALMENTE el audio de una canción. Es lo que decide si se le puede escribir, con qué
 * permisos y a qué coste — el formato del archivo (FLAC, MP3…) es un problema aparte.
 *
 * La distinción no es cosmética: un `content://` no se puede abrir como [File] (ver
 * [EmbeddedLyricsWriter]), y dentro de los `content://` no es lo mismo uno de SAF —donde el usuario
 * concedió un árbol entero y podemos crear archivos hermanos— que uno de MediaStore, donde solo
 * tenemos lectura y escribir exige un diálogo del sistema por archivo.
 */
sealed interface AudioLocation {

    /**
     * Archivo dentro del sandbox de la app (`filesDir/music`): una canción de nube ya descargada.
     * Territorio propio: lectura y escritura directas, sin permisos y con rename atómico.
     */
    data class AppFile(val file: File) : AudioLocation

    /**
     * Documento SAF, de una de las carpetas que el usuario concedió. Se puede leer, reescribir y
     * crear hermanos (el `.lrc`) dentro del mismo árbol.
     */
    data class SafDocument(val uri: Uri) : AudioLocation

    /**
     * Entrada de MediaStore (modo "escanear todo el dispositivo"). `READ_MEDIA_AUDIO` da lectura;
     * para modificar el archivo hace falta `MediaStore.createWriteRequest`, y no se pueden crear
     * archivos no-media a su lado.
     */
    data class MediaStoreItem(val uri: Uri) : AudioLocation

    /** Solo en la nube: no hay ninguna copia en el dispositivo. Todo va por Graph. */
    data object Cloud : AudioLocation
}

/**
 * Traduce una [Song] a su [AudioLocation].
 *
 * El orden importa: una canción de OneDrive **ya descargada** es [AudioLocation.AppFile], no
 * [AudioLocation.Cloud] — se le puede escribir el tag gratis, y solo la subida posterior necesita
 * la red. Por eso se mira primero el disco y después el `sourceType`.
 */
@Singleton
class AudioLocationResolver @Inject constructor(
    @ApplicationContext private val context: Context
) {

    fun resolve(song: Song): AudioLocation {
        downloadedFileOf(song)?.let { return AudioLocation.AppFile(it) }

        val path = song.path
        if (path.startsWith(FILE_SCHEME)) {
            val file = File(path.removePrefix(FILE_SCHEME))
            if (file.exists() && file.length() > 0L) return AudioLocation.AppFile(file)
        }

        if (path.startsWith(CONTENT_SCHEME)) {
            val uri = Uri.parse(path)
            // isTreeUri distingue lo concedido por el selector de carpetas (SAF) de lo que
            // simplemente está indexado por MediaProvider.
            return if (DocumentsContract.isTreeUri(uri)) AudioLocation.SafDocument(uri)
            else AudioLocation.MediaStoreItem(uri)
        }

        return AudioLocation.Cloud
    }

    /**
     * El archivo descargado en `filesDir/music`, si está. Mismo patrón `"${id}."` que
     * `LocalFileCheckStep`: estrictamente más selectivo que un `startsWith(id)` pelado, que
     * confundiría ids que son prefijo de otros.
     */
    fun downloadedFileOf(song: Song): File? {
        val musicDir = File(context.filesDir, MUSIC_DIR)
        if (!musicDir.exists()) return null
        val prefix = "${song.id}."
        return musicDir.listFiles { file -> file.name.startsWith(prefix) && file.length() > 0L }
            ?.firstOrNull()
    }

    /**
     * Carpeta propia para los `.lrc` de canciones que no tienen dónde dejarlos al lado (las de
     * nube). Deliberadamente NO es `filesDir/music`: ahí `LocalFileCheckStep` busca el audio por
     * `"${id}."` y se llevaría el `.lrc` como si fuera la canción, rompiendo la reproducción.
     */
    fun appLyricsDir(): File = File(context.filesDir, LYRICS_DIR).apply { if (!exists()) mkdirs() }

    private companion object {
        const val FILE_SCHEME = "file://"
        const val CONTENT_SCHEME = "content://"
        const val MUSIC_DIR = "music"
        const val LYRICS_DIR = "lyrics"
    }
}

/** Nombre del `.lrc` que acompaña a un archivo de audio: mismo nombre base, otra extensión. */
fun lrcNameFor(audioFileName: String): String = "${audioFileName.substringBeforeLast('.')}.$LRC_EXTENSION"

const val LRC_EXTENSION = "lrc"

/** MIME con el que se crean los `.lrc` (SAF exige uno; no hay tipo registrado para LRC). */
const val LRC_MIME_TYPE = "text/plain"
