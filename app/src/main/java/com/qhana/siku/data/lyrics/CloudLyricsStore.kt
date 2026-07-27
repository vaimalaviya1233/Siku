package com.qhana.siku.data.lyrics

import android.net.Uri
import android.util.Log
import com.qhana.siku.data.auth.AuthManager
import com.qhana.siku.data.auth.AuthResult
import com.qhana.siku.data.model.Song
import com.qhana.siku.data.remote.AuthRefreshInterceptor
import com.qhana.siku.data.remote.OneDriveApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException
import java.io.File
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Letras que viven en OneDrive, junto a la canción.
 *
 * Dos operaciones muy distintas en coste:
 * - El **`.lrc`** son unos KB: va por Retrofit con el cliente normal.
 * - El **audio re-etiquetado** son decenas de MB: va por OkHttp con el cliente de descargas
 *   (timeouts de minutos), porque el cliente por defecto corta a los 30 s y una subida de 40 MB
 *   no cabe ahí ni con buena conexión.
 *
 * Leer NO cuesta permisos nuevos (`Files.Read` ya los cubre). Escribir exige `Files.ReadWrite`
 * sobre todo el drive — ver [AuthManager.requestWriteConsent].
 *
 * Es específico de OneDrive, como [com.qhana.siku.data.backup.PlaylistBackupRepository]. El día que
 * haya un segundo proveedor de nube, esto sube a una capacidad opcional de `MusicSource`.
 */
@Singleton
class CloudLyricsStore @Inject constructor(
    private val oneDriveApi: OneDriveApi,
    private val authManager: AuthManager,
    @Named("download") private val uploadClient: OkHttpClient
) {

    /** ¿La cuenta ya consintió la escritura? Si no, hay que pasar por el consentimiento interactivo. */
    suspend fun hasWriteConsent(): Boolean =
        authManager.getWriteAccessToken().firstOrNull() is AuthResult.Success

    /**
     * Lee el `.lrc` que acompaña a la canción en la nube, si existe. Un 404 significa
     * simplemente que no hay ninguno: no es un error.
     */
    suspend fun readLrc(song: Song): String? = withContext(Dispatchers.IO) {
        val remoteId = song.remoteId ?: return@withContext null
        try {
            val token = readToken()
            val item = oneDriveApi.getItem(token, remoteId, select = ITEM_SELECT)
            val parentId = item.parentReference?.id ?: return@withContext null
            val name = item.name ?: return@withContext null
            oneDriveApi.downloadFile(token, contentUrlFor(parentId, lrcNameFor(name)))
                .string()
                .takeIf { it.isNotBlank() }
        } catch (e: HttpException) {
            if (e.code() != HTTP_NOT_FOUND) Log.w(TAG, "No se pudo leer el .lrc remoto: ${e.code()}")
            null
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo leer el .lrc remoto: ${e.message}")
            null
        }
    }

    /** Crea o reemplaza el `.lrc` junto a la canción. Lanza si falta el permiso de escritura. */
    suspend fun saveLrc(song: Song, lyrics: String) = withContext(Dispatchers.IO) {
        val remoteId = requireNotNull(song.remoteId) { "La canción no tiene handle remoto" }
        // Un solo token para las dos llamadas: el de escritura incluye los scopes de lectura.
        val token = writeToken()
        val item = oneDriveApi.getItem(token, remoteId, select = ITEM_SELECT)
        val parentId = requireNotNull(item.parentReference?.id) { "Sin carpeta contenedora" }
        val name = requireNotNull(item.name) { "El archivo remoto no tiene nombre" }

        oneDriveApi.uploadFileWithWriteScope(
            token = token,
            writeScope = WRITE_SCOPE_MARKER,
            url = contentUrlFor(parentId, lrcNameFor(name)),
            body = lyrics.toRequestBody(LRC_UPLOAD_MIME_TYPE.toMediaType())
        )
        Unit
    }

    /**
     * Reemplaza el contenido del archivo en la nube por el de [file] (la copia local ya
     * re-etiquetada). El `item.id` no cambia al reemplazar contenido, así que el delta del
     * siguiente scan lo ve como modificado pero conserva el mismo id: no duplica la canción.
     *
     * Se sube por PUT simple: Graph lo admite hasta 250 MB y una canción no se acerca. Una upload
     * session daría reanudación tras un corte, pero esto es una acción individual y explícita —
     * si falla, se vuelve a pulsar.
     */
    suspend fun replaceAudio(song: Song, file: File) = withContext(Dispatchers.IO) {
        val remoteId = requireNotNull(song.remoteId) { "La canción no tiene handle remoto" }
        val token = writeToken()

        val request = Request.Builder()
            .url("$GRAPH_ITEMS$remoteId/content")
            .header("Authorization", token)
            // El cliente de descargas no lleva AuthRefreshInterceptor (sus URLs suelen venir
            // pre-firmadas), así que aquí no hay refresco automático: el token se acaba de pedir.
            .put(file.asRequestBody(AUDIO_MIME_TYPE.toMediaType()))
            .build()

        uploadClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "OneDrive devolvió ${response.code} al subir el audio" }
        }
    }

    private suspend fun readToken(): String = bearer(authManager.getAccessToken().firstOrNull())

    private suspend fun writeToken(): String = bearer(
        authManager.getWriteAccessToken().firstOrNull(),
        onMissing = "Falta el permiso de escritura en OneDrive"
    )

    private fun bearer(result: AuthResult?, onMissing: String = "Sin sesión de OneDrive"): String {
        check(result is AuthResult.Success && result.token.isNotBlank()) { onMissing }
        return "Bearer ${result.token}"
    }

    /**
     * `…/items/{carpeta}:/{nombre}:/content`. El nombre se codifica (espacios, acentos), pero los
     * `:` de la sintaxis de Graph deben quedar literales — por eso la URL se arma a mano y no con
     * un `@Path` de Retrofit, que los escaparía.
     */
    private fun contentUrlFor(parentId: String, fileName: String): String =
        "$GRAPH_ITEMS$parentId:/${Uri.encode(fileName)}:/content"

    private companion object {
        const val TAG = "CloudLyricsStore"
        const val GRAPH_ITEMS = "https://graph.microsoft.com/v1.0/me/drive/items/"
        const val ITEM_SELECT = "id,name,parentReference"
        const val AUDIO_MIME_TYPE = "application/octet-stream"
        const val HTTP_NOT_FOUND = 404
        /** Valor del header; lo que importa es su presencia (ver [AuthRefreshInterceptor]). */
        const val WRITE_SCOPE_MARKER = "1"
    }
}
