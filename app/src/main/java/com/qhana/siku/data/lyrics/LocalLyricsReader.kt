package com.qhana.siku.data.lyrics

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.qhana.siku.data.model.Song
import com.qhana.siku.data.preferences.MusicPreferences
import com.qhana.siku.data.source.MusicSourceRegistry
import com.qhana.siku.data.util.tags.HttpRangeFetcher
import com.qhana.siku.data.util.tags.PartialTagReaders
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Busca la letra que ya viene CON la música, antes de pedirla a internet.
 *
 * Quien trae su biblioteca de otro reproductor suele traer también sus letras, y bajarlas otra vez
 * de LrcLib es tirar una versión que el usuario ya eligió (a veces corregida a mano). Se mira, en
 * este orden:
 *
 * 1. Un archivo `.lrc` con el mismo nombre que la canción.
 * 2. La letra embebida en los tags del propio archivo.
 *
 * Lo remoto ([includeRemote]) queda FUERA del camino automático: leer los tags de una canción que
 * solo está en la nube cuesta una petición, y el `.lrc` remoto dos más. Ir directo a LrcLib es una
 * sola y acierta más. Cuando el usuario pide la letra a mano sí se busca en la nube, porque ahí la
 * espera es suya y explícita.
 */
@Singleton
class LocalLyricsReader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val locationResolver: AudioLocationResolver,
    private val tagReaders: PartialTagReaders,
    private val rangeFetcher: HttpRangeFetcher,
    private val sourceRegistry: MusicSourceRegistry,
    private val cloudLyricsStore: CloudLyricsStore,
    private val musicPreferences: MusicPreferences
) {

    suspend fun read(song: Song, includeRemote: Boolean = false): String? = withContext(Dispatchers.IO) {
        val location = locationResolver.resolve(song)
        readLrcFile(song, location, includeRemote)?.let {
            Log.d(TAG, "Letra de '${song.title}' leída de un archivo .lrc")
            return@withContext it
        }
        readEmbedded(song, location, includeRemote)?.let {
            Log.d(TAG, "Letra de '${song.title}' leída del tag del archivo")
            return@withContext it
        }
        // Diagnóstico: sin esto, "no había letra local" y "la busqué en el sitio equivocado" son
        // indistinguibles desde fuera, y el usuario solo ve que la app se va a la red.
        Log.d(TAG, "Sin letra local para '${song.title}' (ubicación: ${location.javaClass.simpleName})")
        null
    }

    // --- Archivo .lrc -------------------------------------------------------------------------

    private suspend fun readLrcFile(song: Song, location: AudioLocation, includeRemote: Boolean): String? =
        when (location) {
            is AudioLocation.AppFile -> {
                val staged = File(locationResolver.appLyricsDir(), lrcNameFor(location.file.name))
                staged.takeIf { it.isFile }?.readTextOrNull()
                    ?: if (includeRemote) cloudLyricsStore.readLrc(song) else null
            }

            // El `let` y no un `?: return`: si no se puede saber el nombre del documento, se
            // renuncia al `.lrc` pero NO al tag embebido, que sigue siendo alcanzable.
            is AudioLocation.SafDocument -> displayNameOf(location.uri)?.let { name ->
                safSibling(location.uri, name)?.let { readUriText(it) }
            }

            // MediaStore no permite leer archivos no-media ajenos: el `.lrc` de al lado es
            // invisible aunque exista. Solo se puede mirar la carpeta que el usuario haya
            // concedido a propósito para las letras.
            is AudioLocation.MediaStoreItem -> readFromLyricsFolder(song, location.uri)

            AudioLocation.Cloud -> if (includeRemote) cloudLyricsStore.readLrc(song) else null
        }

    /**
     * Carpeta de letras elegida en Ajustes. El `.lrc` se busca en la MISMA subcarpeta que escribe
     * [LyricsWriter] (la jerarquía de la canción, ver [lyricsSubdirectoryOf]): plano colisionaría
     * entre álbumes y devolvería la letra de otra canción.
     *
     * El id del documento se construye en vez de listar el árbol: si no existe, la lectura falla y
     * devolvemos null, más barato que enumerar carpetas en cada cambio de canción.
     */
    private fun readFromLyricsFolder(song: Song, audioUri: Uri): String? {
        val treeUri = musicPreferences.loadLyricsFolderUri()?.let(Uri::parse) ?: return null
        val baseName = displayNameOf(audioUri)
            ?: song.relativePath?.substringAfterLast('/')
            ?: song.title
        val subdirectory = lyricsSubdirectoryOf(context, audioUri, song)
        val documentId = buildString {
            append(DocumentsContract.getTreeDocumentId(treeUri))
            if (subdirectory.isNotEmpty()) append('/').append(subdirectory)
            append('/').append(lrcNameFor(baseName))
        }
        return readUriText(DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId))
    }

    // --- Tag embebido -------------------------------------------------------------------------

    private suspend fun readEmbedded(song: Song, location: AudioLocation, includeRemote: Boolean): String? {
        val fileName = when (location) {
            is AudioLocation.AppFile -> location.file.name
            is AudioLocation.SafDocument -> displayNameOf(location.uri)
            is AudioLocation.MediaStoreItem -> displayNameOf(location.uri)
            AudioLocation.Cloud -> song.relativePath
        } ?: song.relativePath ?: song.title

        val reader = tagReaders.forExtension(fileName.substringAfterLast('.', "")) ?: return null

        val head = when (location) {
            is AudioLocation.AppFile ->
                readHead({ FileInputStream(location.file) }, reader.preferredFragmentBytes)

            is AudioLocation.SafDocument ->
                readHead({ context.contentResolver.openInputStream(location.uri) }, reader.preferredFragmentBytes)

            is AudioLocation.MediaStoreItem ->
                readHead({ context.contentResolver.openInputStream(location.uri) }, reader.preferredFragmentBytes)

            AudioLocation.Cloud -> {
                if (!includeRemote) return null
                val url = sourceRegistry.resolveDownloadUrl(song) ?: return null
                rangeFetcher.fetch(url, 0L, reader.preferredFragmentBytes)
            }
        } ?: return null

        return runCatching { reader.read(head)?.lyrics }
            .onFailure { Log.w(TAG, "No se pudo leer la letra embebida de ${song.title}: ${it.message}") }
            .getOrNull()
    }

    // --- Utilidades ---------------------------------------------------------------------------

    /**
     * Hermano dentro del mismo árbol SAF: mismo directorio, otro nombre. Se construye el id del
     * documento en vez de listar la carpeta — si no existe, la lectura falla y devolvemos null,
     * que es más barato que enumerar cientos de archivos por cada canción.
     */
    private fun safSibling(uri: Uri, audioName: String): Uri? = runCatching {
        val documentId = DocumentsContract.getDocumentId(uri)
        val parentId = documentId.substringBeforeLast('/', "")
        if (parentId.isEmpty()) {
            Log.d(TAG, "El documento no tiene carpeta padre en su id: $documentId")
            return null
        }

        // Dos convenciones conviven en la práctica: `Numb.lrc` (la habitual) y `Numb.mp3.lrc`
        // (la que produce algún exportador, conservando la extensión del audio). Se aceptan las
        // dos, y la comparación es insensible a mayúsculas.
        val base = audioName.substringBeforeLast('.')
        val accepted = setOf(
            "$base.$LRC_EXTENSION".lowercase(),
            "$audioName.$LRC_EXTENSION".lowercase()
        )

        val children = DocumentsContract.buildChildDocumentsUriUsingTree(uri, parentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME
        )
        context.contentResolver.query(children, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(1) ?: continue
                if (name.lowercase() in accepted) {
                    return@runCatching DocumentsContract.buildDocumentUriUsingTree(uri, cursor.getString(0))
                }
            }
        }
        Log.d(TAG, "Sin .lrc para '$audioName' en la carpeta (se buscaba: $accepted)")
        null
    }.getOrNull()

    private fun displayNameOf(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(DISPLAY_NAME_COLUMN), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    private fun readUriText(uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun File.readTextOrNull(): String? =
        runCatching { readText().takeIf { it.isNotBlank() } }.getOrNull()

    /**
     * Los primeros [bytes] del stream. Se insiste hasta llenar el buffer porque `read` puede
     * devolver de a poco (sobre todo en `content://`), y un fragmento corto haría que el lector
     * de tags no encuentre el bloque de la letra y devuelva null sin motivo real.
     */
    private fun readHead(open: () -> InputStream?, bytes: Int): ByteArray? = runCatching {
        open()?.use { stream ->
            val buffer = ByteArray(bytes)
            var read = 0
            while (read < bytes) {
                val n = stream.read(buffer, read, bytes - read)
                if (n <= 0) break
                read += n
            }
            when {
                read <= 0 -> null
                read == bytes -> buffer
                else -> buffer.copyOf(read)
            }
        }
    }.getOrNull()

    private companion object {
        const val TAG = "LocalLyricsReader"
        const val DISPLAY_NAME_COLUMN = DocumentsContract.Document.COLUMN_DISPLAY_NAME
    }
}
