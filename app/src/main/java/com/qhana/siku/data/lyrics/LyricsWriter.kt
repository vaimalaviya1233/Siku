package com.qhana.siku.data.lyrics

import android.app.RecoverableSecurityException
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.util.Log
import com.qhana.siku.data.model.LyricsSaveMode
import com.qhana.siku.data.model.Song
import com.qhana.siku.data.preferences.MusicPreferences
import com.qhana.siku.data.util.NetworkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Guarda la letra donde el usuario pueda volver a encontrarla: en un `.lrc` al lado de la canción
 * o dentro del propio archivo.
 *
 * **El destino lo decide el origen de la canción, no el usuario.** Una letra guardada en la copia
 * privada de una canción de OneDrive no la ve nadie —ni él desde su PC—, así que para las
 * canciones de nube el destino es la nube. Para las locales, el disco.
 *
 * Todas las operaciones son de UNA canción. No hay guardado por lotes a propósito: en una
 * biblioteca grande, re-subir los archivos serían decenas de GB.
 */
@Singleton
class LyricsWriter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val locationResolver: AudioLocationResolver,
    private val embeddedWriter: EmbeddedLyricsWriter,
    private val cloudLyricsStore: CloudLyricsStore,
    private val musicPreferences: MusicPreferences,
    private val networkManager: NetworkManager
) {

    /** Qué se le puede ofrecer al usuario para esta canción, y qué implica cada opción. */
    suspend fun optionsFor(song: Song): LyricsSaveOptions = withContext(Dispatchers.IO) {
        val location = locationResolver.resolve(song)
        val goesToCloud = song.sourceType.isCloud
        val needsConsent = goesToCloud && !cloudLyricsStore.hasWriteConsent()
        val fileName = fileNameOf(song, location)

        val cloudEffects = buildSet {
            if (goesToCloud) add(SaveEffect.UPLOADS_TO_CLOUD)
            if (needsConsent) add(SaveEffect.NEEDS_CLOUD_CONSENT)
        }

        // Modo MediaStore: el .lrc no puede ir junto a la canción y hay que decirlo — el usuario
        // esperaría que otros reproductores lo leyeran, y desde una carpeta aparte no lo harán.
        val lyricsFolder = musicPreferences.loadLyricsFolderUri()
        val needsLyricsFolder = location is AudioLocation.MediaStoreItem

        val lrc = when {
            goesToCloud && song.remoteId == null -> SaveOption.Blocked(SaveBlocker.NO_REMOTE_HANDLE)
            needsLyricsFolder && lyricsFolder == null -> SaveOption.Blocked(SaveBlocker.NO_LYRICS_FOLDER)
            needsLyricsFolder -> SaveOption.Available(cloudEffects + SaveEffect.SAVED_TO_LYRICS_FOLDER)
            else -> SaveOption.Available(cloudEffects)
        }

        val embedded = when {
            location is AudioLocation.Cloud -> SaveOption.Blocked(SaveBlocker.NOT_DOWNLOADED)
            !embeddedWriter.supports(fileName) -> SaveOption.Blocked(SaveBlocker.FORMAT_UNSUPPORTED)
            else -> SaveOption.Available(cloudEffects + SaveEffect.REWRITES_FILE)
        }

        LyricsSaveOptions(
            lrc = lrc,
            embedded = embedded,
            uploadBytes = if (goesToCloud) song.size else 0L,
            lyricsFolderName = lyricsFolder?.let(::safFolderDisplayName)
        )
    }

    suspend fun save(song: Song, lyrics: String, mode: LyricsSaveMode): LyricsSaveResult =
        withContext(Dispatchers.IO) {
            if (lyrics.isBlank() || lyrics == INSTRUMENTAL_SENTINEL) {
                return@withContext LyricsSaveResult.Failed(FailureReason.NOTHING_TO_SAVE)
            }
            try {
                when (mode) {
                    LyricsSaveMode.LRC_FILE -> saveLrc(song, lyrics)
                    LyricsSaveMode.EMBEDDED -> saveEmbedded(song, lyrics)
                    // Preguntar es cosa de la UI: aquí no hay a quién preguntar.
                    LyricsSaveMode.ASK -> LyricsSaveResult.Failed(FailureReason.UNEXPECTED)
                }
            } catch (e: SecurityException) {
                // MediaStore desde Android 10: el archivo es de otra app y el sistema exige que
                // sea el usuario quien autorice esta escritura concreta. El `is` va dentro del
                // catch y no en uno propio: RecoverableSecurityException no existe en API 26 y
                // nombrarla como tipo de captura arriesga la verificación del método.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && e is RecoverableSecurityException) {
                    LyricsSaveResult.NeedsSystemPermission(e.userAction.actionIntent.intentSender)
                } else {
                    Log.w(TAG, "Sin permiso para escribir ${song.title}: ${e.message}")
                    LyricsSaveResult.Failed(FailureReason.NO_PERMISSION)
                }
            } catch (e: IllegalStateException) {
                // `check(...)` de las capas de abajo: falta de consentimiento, respuesta no OK…
                Log.w(TAG, "No se pudo guardar la letra de ${song.title}: ${e.message}")
                LyricsSaveResult.Failed(FailureReason.UNEXPECTED, e.message)
            } catch (e: Exception) {
                Log.e(TAG, "Error guardando la letra de ${song.title}", e)
                LyricsSaveResult.Failed(FailureReason.UNEXPECTED, e.message)
            }
        }

    // --- .lrc ---------------------------------------------------------------------------------

    private suspend fun saveLrc(song: Song, lyrics: String): LyricsSaveResult {
        if (song.sourceType.isCloud) {
            if (!cloudLyricsStore.hasWriteConsent()) return LyricsSaveResult.NeedsCloudConsent
            cloudLyricsStore.saveLrc(song, lyrics)
            // Copia local para que la próxima reproducción la encuentre sin pedir nada a la red.
            locationResolver.downloadedFileOf(song)?.let { audio ->
                File(locationResolver.appLyricsDir(), lrcNameFor(audio.name)).writeText(lyrics)
            }
            return LyricsSaveResult.Success
        }

        return when (val location = locationResolver.resolve(song)) {
            is AudioLocation.SafDocument -> {
                val name = lrcNameFor(displayNameOf(location.uri) ?: song.title)
                val parent = parentDocumentUri(location.uri)
                    ?: return LyricsSaveResult.Failed(FailureReason.UNEXPECTED)
                writeDocument(parent, name, lyrics)
                LyricsSaveResult.Success
            }

            is AudioLocation.MediaStoreItem -> {
                val treeUri = musicPreferences.loadLyricsFolderUri()?.let(Uri::parse)
                    ?: return LyricsSaveResult.Failed(FailureReason.NO_LYRICS_FOLDER)
                val baseName = displayNameOf(location.uri)
                    ?: song.relativePath?.substringAfterLast('/')
                    ?: song.title
                // Se replica la jerarquía de la canción: la carpeta de letras es plana y los
                // nombres de archivo se repiten entre álbumes (ver [lyricsSubdirectoryOf]).
                val parent = ensureDirectoryPath(
                    treeUri,
                    lyricsSubdirectoryOf(context, location.uri, song)
                )
                writeDocument(parent, lrcNameFor(baseName), lyrics)
                LyricsSaveResult.Success
            }

            is AudioLocation.AppFile -> {
                File(locationResolver.appLyricsDir(), lrcNameFor(location.file.name)).writeText(lyrics)
                LyricsSaveResult.Success
            }

            AudioLocation.Cloud -> LyricsSaveResult.Failed(FailureReason.UNEXPECTED)
        }
    }

    // --- Tag embebido -------------------------------------------------------------------------

    private suspend fun saveEmbedded(song: Song, lyrics: String): LyricsSaveResult {
        val location = locationResolver.resolve(song)
        val fileName = fileNameOf(song, location)
        if (!embeddedWriter.supports(fileName)) {
            return LyricsSaveResult.Failed(FailureReason.FORMAT_UNSUPPORTED)
        }

        when (location) {
            is AudioLocation.AppFile -> {
                if (song.sourceType.isCloud) {
                    if (!cloudLyricsStore.hasWriteConsent()) return LyricsSaveResult.NeedsCloudConsent
                    // Subir decenas de MB por datos móviles no se hace sin permiso explícito, y la
                    // app ya tiene esa política para las descargas.
                    if (!networkManager.isWifi()) return LyricsSaveResult.Failed(FailureReason.NEEDS_WIFI)
                }
                embeddedWriter.writeToFile(location.file, lyrics)
                if (song.sourceType.isCloud) cloudLyricsStore.replaceAudio(song, location.file)
            }

            is AudioLocation.SafDocument -> embeddedWriter.writeToDocument(location.uri, fileName, lyrics)
            is AudioLocation.MediaStoreItem -> embeddedWriter.writeToDocument(location.uri, fileName, lyrics)
            AudioLocation.Cloud -> return LyricsSaveResult.Failed(FailureReason.NOT_DOWNLOADED)
        }
        return LyricsSaveResult.Success
    }

    // --- Utilidades ---------------------------------------------------------------------------

    private fun fileNameOf(song: Song, location: AudioLocation): String = when (location) {
        is AudioLocation.AppFile -> location.file.name
        is AudioLocation.SafDocument -> displayNameOf(location.uri)
        is AudioLocation.MediaStoreItem -> displayNameOf(location.uri)
        AudioLocation.Cloud -> song.relativePath
    } ?: song.relativePath ?: song.title

    /**
     * Recorre (creando lo que falte) la ruta [relativeDir] dentro del árbol concedido, y devuelve
     * el documento de la carpeta final. Con ruta vacía devuelve la raíz del árbol.
     *
     * Cada tramo se comprueba antes de crearlo por el mismo motivo que en [writeDocument]:
     * `createDocument` sobre un nombre ocupado no falla, crea `Rock (1)` — y guardar dos letras del
     * mismo álbum acabaría fabricando un árbol de carpetas duplicadas.
     */
    private fun ensureDirectoryPath(treeUri: Uri, relativeDir: String): Uri {
        var current = DocumentsContract.buildDocumentUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri)
        )
        relativeDir.split('/').filter { it.isNotBlank() }.forEach { segment ->
            val candidate = DocumentsContract.buildDocumentUriUsingTree(
                current, "${DocumentsContract.getDocumentId(current)}/$segment"
            )
            current = if (exists(candidate)) candidate else {
                DocumentsContract.createDocument(
                    context.contentResolver, current, DocumentsContract.Document.MIME_TYPE_DIR, segment
                ) ?: error("No se pudo crear la carpeta $segment")
            }
        }
        return current
    }

    /**
     * Crea el documento o, si ya existía, lo sobrescribe. Importa el orden: `createDocument` sobre
     * un nombre ocupado no falla, crea `nombre (1).lrc` — y el usuario acabaría con un reguero de
     * duplicados cada vez que reguarda la misma letra.
     */
    private fun writeDocument(parent: Uri, name: String, text: String) {
        val existing = DocumentsContract.buildDocumentUriUsingTree(
            parent, "${DocumentsContract.getDocumentId(parent)}/$name"
        )
        val target = if (exists(existing)) existing else {
            DocumentsContract.createDocument(context.contentResolver, parent, LRC_MIME_TYPE, name)
                ?: error("No se pudo crear $name")
        }
        context.contentResolver.openOutputStream(target, WRITE_TRUNCATE)?.use {
            it.write(text.toByteArray())
        } ?: error("No se pudo escribir $name")
    }

    private fun exists(uri: Uri): Boolean = runCatching {
        context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
            ?.use { it.moveToFirst() } == true
    }.getOrDefault(false)

    private fun parentDocumentUri(documentUri: Uri): Uri? = runCatching {
        val documentId = DocumentsContract.getDocumentId(documentUri)
        val parentId = documentId.substringBeforeLast('/', "")
        if (parentId.isEmpty()) null
        else DocumentsContract.buildDocumentUriUsingTree(documentUri, parentId)
    }.getOrNull()

    private fun displayNameOf(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()

    private companion object {
        const val TAG = "LyricsWriter"
        const val WRITE_TRUNCATE = "wt"
        const val INSTRUMENTAL_SENTINEL = "[INSTRUMENTAL]"
    }
}

// --- Modelos de la operación -------------------------------------------------------------------

/** Qué opciones tiene sentido ofrecer para una canción concreta, y qué implican. */
data class LyricsSaveOptions(
    val lrc: SaveOption,
    val embedded: SaveOption,
    /** Cuánto se subiría al re-subir el audio, para poder decirlo antes de hacerlo. */
    val uploadBytes: Long,
    /** Carpeta de letras elegida, para nombrarla en [SaveEffect.SAVED_TO_LYRICS_FOLDER]. */
    val lyricsFolderName: String? = null
)

sealed interface SaveOption {
    data class Available(val effects: Set<SaveEffect>) : SaveOption
    data class Blocked(val reason: SaveBlocker) : SaveOption
}

/** Consecuencias que el usuario debe conocer ANTES de confirmar. */
enum class SaveEffect {
    /** Se reescribe el archivo de audio. */
    REWRITES_FILE,

    /** Se sube a OneDrive (el `.lrc` son KB; el audio, decenas de MB). */
    UPLOADS_TO_CLOUD,

    /**
     * El `.lrc` va a la carpeta de letras y NO junto a la canción, porque en el modo "todo el
     * dispositivo" el sistema no autoriza a crear archivos ahí. Es una restricción de Android, y el
     * mensaje lo dice: si no, parece una carencia de la app y el usuario espera que otros
     * reproductores encuentren la letra, que no lo harán.
     */
    SAVED_TO_LYRICS_FOLDER,

    /** Falta el permiso de escritura en OneDrive: habrá que concederlo primero. */
    NEEDS_CLOUD_CONSENT
}

enum class SaveBlocker {
    /** El contenedor no tiene un campo de letra que los reproductores lean (WAV). */
    FORMAT_UNSUPPORTED,

    /** Está solo en la nube: habría que bajarla entera para poder etiquetarla. */
    NOT_DOWNLOADED,

    /** Modo MediaStore sin carpeta de letras elegida: no hay dónde dejar el `.lrc`. */
    NO_LYRICS_FOLDER,

    /** Canción de nube sin handle remoto (fila antigua): no se sabe a qué archivo corresponde. */
    NO_REMOTE_HANDLE
}

sealed interface LyricsSaveResult {
    data object Success : LyricsSaveResult

    /** Hay que pasar por [com.qhana.siku.data.auth.AuthManager.requestWriteConsent] y reintentar. */
    data object NeedsCloudConsent : LyricsSaveResult

    /** MediaStore en API 30+: el sistema pide que el usuario autorice esta escritura. */
    data class NeedsSystemPermission(val intentSender: IntentSender) : LyricsSaveResult

    data class Failed(val reason: FailureReason, val detail: String? = null) : LyricsSaveResult
}

enum class FailureReason {
    NOTHING_TO_SAVE,
    FORMAT_UNSUPPORTED,
    NOT_DOWNLOADED,
    NO_LYRICS_FOLDER,
    NO_PERMISSION,
    NEEDS_WIFI,
    UNEXPECTED
}
