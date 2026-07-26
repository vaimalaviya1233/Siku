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
        readLrcFile(song, location, includeRemote)?.let { return@withContext it }
        readEmbedded(song, location, includeRemote)
    }

    // --- Archivo .lrc -------------------------------------------------------------------------

    private suspend fun readLrcFile(song: Song, location: AudioLocation, includeRemote: Boolean): String? =
        when (location) {
            is AudioLocation.AppFile -> {
                val staged = File(locationResolver.appLyricsDir(), lrcNameFor(location.file.name))
                staged.takeIf { it.isFile }?.readTextOrNull()
                    ?: if (includeRemote) cloudLyricsStore.readLrc(song) else null
            }

            is AudioLocation.SafDocument ->
                safSibling(location.uri, lrcNameFor(displayNameOf(location.uri) ?: return null))
                    ?.let { readUriText(it) }

            // MediaStore no permite leer archivos no-media ajenos: el `.lrc` de al lado es
            // invisible aunque exista. Solo se puede mirar la carpeta que el usuario haya
            // concedido a propósito para las letras.
            is AudioLocation.MediaStoreItem -> readFromLyricsFolder(song)

            AudioLocation.Cloud -> if (includeRemote) cloudLyricsStore.readLrc(song) else null
        }

    /** Carpeta de letras elegida en Ajustes: se busca por nombre de canción, sin listar el árbol. */
    private fun readFromLyricsFolder(song: Song): String? {
        val treeUri = musicPreferences.loadLyricsFolderUri()?.let(Uri::parse) ?: return null
        val baseName = song.relativePath?.substringAfterLast('/') ?: song.title
        val target = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            "${DocumentsContract.getTreeDocumentId(treeUri)}/${lrcNameFor(baseName)}"
        )
        return readUriText(target)
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
    private fun safSibling(uri: Uri, name: String): Uri? = runCatching {
        val documentId = DocumentsContract.getDocumentId(uri)
        val parentId = documentId.substringBeforeLast('/', "")
        if (parentId.isEmpty()) return null
        DocumentsContract.buildDocumentUriUsingTree(uri, "$parentId/$name")
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
