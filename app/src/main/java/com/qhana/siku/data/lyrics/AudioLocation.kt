package com.qhana.siku.data.lyrics

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
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

/**
 * Subcarpeta que le corresponde a una canción DENTRO de la carpeta de letras, sin el nombre del
 * archivo (`Music/Rock/Album`). Cadena vacía si la canción está en la raíz de su volumen.
 *
 * Existe porque la carpeta de letras es un espacio PLANO por defecto y eso colisiona: `01 Intro.lrc`
 * es el mismo nombre en todos los discos de una biblioteca rippeada, así que sin replicar la
 * jerarquía cada álbum sobreescribiría la letra del anterior — y la lectura devolvería la de otra
 * canción, que es peor que no encontrar ninguna.
 *
 * Se prefiere `RELATIVE_PATH` de MediaStore porque conserva las mayúsculas originales: derivarlo de
 * `song.relativePath` funcionaría igual para evitar choques, pero está normalizado a minúsculas y
 * crearía un árbol `music/rock/` paralelo al `Music/Rock/` real si el usuario elige como carpeta de
 * letras la de su propia música.
 */
fun lyricsSubdirectoryOf(context: Context, uri: Uri, song: Song): String {
    val fromMediaStore = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        runCatching {
            context.contentResolver.query(
                uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null
            )?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
    } else null

    val raw = fromMediaStore ?: song.relativePath?.substringBeforeLast('/', "")
    return raw?.trim('/').orEmpty()
}

/**
 * Nombre legible de una carpeta concedida por SAF, para poder nombrarla en la UI. El tree URI trae
 * el id del documento codificado (`…/tree/primary%3AMusic%2FLyrics`), así que se descodifica y se
 * toma el último tramo: lo que el usuario reconoce como "la carpeta que elegí".
 */
fun safFolderDisplayName(treeUri: String): String =
    Uri.decode(treeUri).substringAfterLast(':').substringAfterLast('/')

/** Nombre del `.lrc` que acompaña a un archivo de audio: mismo nombre base, otra extensión. */
fun lrcNameFor(audioFileName: String): String = "${audioFileName.substringBeforeLast('.')}.$LRC_EXTENSION"

const val LRC_EXTENSION = "lrc"

/**
 * MIME con el que se CREAN los `.lrc` vía SAF. `application/octet-stream` no es un descuido:
 * **con `text/plain` el archivo acaba llamándose `Cancion.lrc.txt`**.
 *
 * `DocumentsProvider.createDocument` pasa por `FileUtils.splitFileName`, que exige que la extensión
 * del nombre corresponda al MIME pedido y, si no, le AÑADE la del MIME. Como `MimeTypeMap` de
 * Android no conoce `.lrc`, deduce `application/octet-stream` de la extensión; pidiendo `text/plain`
 * los dos no coinciden y añade `.txt`. Pidiendo `application/octet-stream` sí coinciden y el nombre
 * se respeta tal cual.
 *
 * Ojo: esto solo aplica a SAF. Para SUBIR el `.lrc` a la nube se usa [LRC_UPLOAD_MIME_TYPE], que es
 * el tipo honesto del contenido y no pasa por esta lógica.
 */
const val LRC_MIME_TYPE = "application/octet-stream"

/** El `.lrc` ES texto; al subirlo a la nube se declara como tal (ahí nadie renombra nada). */
const val LRC_UPLOAD_MIME_TYPE = "text/plain"
