package com.qhana.siku.data.repository

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import com.qhana.siku.data.auth.AuthManager
import com.qhana.siku.data.auth.AuthResult
import com.qhana.siku.data.model.Song
import com.qhana.siku.data.remote.OneDriveApi
import com.qhana.siku.data.coordinator.RequestCoordinator
import com.qhana.siku.data.util.AudioFileAnalyzer

@Singleton
class OneDriveRepository @Inject constructor(
    private val oneDriveApi: OneDriveApi,
    private val authManager: AuthManager,
    private val requestCoordinator: RequestCoordinator,
    private val audioFileAnalyzer: AudioFileAnalyzer,
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "OneDriveRepo"

        // Códigos HTTP que [isTransient] considera repetibles. Nombrados igual que en
        // `OneDriveFolderBrowser`, donde ya se hacía: un `>= 500` suelto se lee como un umbral
        // ajustable cuando en realidad es la frontera fija entre "culpa del cliente" (4xx, no
        // cambia por repetir) y "culpa del servidor" (5xx, sí puede cambiar).
        private const val HTTP_REQUEST_TIMEOUT = 408
        private const val HTTP_SERVER_ERROR = 500
    }

    
    /**
     * Reintentos con backoff exponencial, SOLO para fallos transitorios ([isTransient]).
     *
     * Antes reintentaba cualquier excepción que no fuera 401/403/404, lo que hacía esperar
     * 3 segundos en vano ante errores que nunca iban a cambiar de respuesta (un 400, un fallo
     * de parseo) y, peor, trataba la CANCELACIÓN como un fallo más: se la tragaba, la anotaba
     * como "reintento fallido" y seguía el bucle. Cancelar el sync (logout, red medida, worker
     * reemplazado) debe cortar aquí y ahora — es lo que hace el resto de la app.
     */
    private suspend fun <T> executeWithRetry(
        times: Int = 3,
        initialDelay: Long = 1000,
        factor: Double = 2.0,
        block: suspend () -> T
    ): T {
        var currentDelay = initialDelay
        var lastException: Exception? = null

        repeat(times) { attempt ->
            try {
                return block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isTransient(e)) throw e
                lastException = e

                android.util.Log.w(TAG, "Reintento fallido (${attempt + 1}/$times). Error: ${e.message}")

                if (attempt < times - 1) {
                    kotlinx.coroutines.delay(currentDelay)
                    currentDelay = (currentDelay * factor).toLong()
                }
            }
        }

        android.util.Log.e(TAG, "Todos los $times intentos fallaron")
        throw lastException ?: IllegalStateException("Retry failed without exception")
    }

    /**
     * ¿Vale la pena repetir esta petición tal cual? Solo si el fallo es de transporte o del
     * servidor. Un 429 NO entra: tiene su propio mecanismo (cabecera `Retry-After` →
     * `RequestCoordinator.notifyThrottled`), y machacarlo con reintentos rápidos solo alarga
     * el castigo de OneDrive.
     */
    private fun isTransient(e: Exception): Boolean = when (e) {
        is retrofit2.HttpException -> e.code() == HTTP_REQUEST_TIMEOUT || e.code() >= HTTP_SERVER_ERROR
        is java.io.IOException -> true
        else -> false
    }

    private suspend fun fetchDownloadUrl(remoteId: String, usePriorityPermit: Boolean): String? {
        val authResult = authManager.getAccessToken().first()
        if (authResult !is AuthResult.Success) return null
        
        val tokenStr = authResult.token
        if (tokenStr.isBlank()) {
            android.util.Log.e(TAG, "Token de acceso vacío. Abortando solicitud.")
            return null
        }
        
        val token = "Bearer $tokenStr"

        return try {
            executeWithRetry {
                if (usePriorityPermit) {
                    requestCoordinator.acquirePriorityPermit()
                } else {
                    requestCoordinator.acquireRequestPermit()
                }
                
                val item = oneDriveApi.getItem(token, remoteId)
                item.downloadUrl
            }
        } catch (e: retrofit2.HttpException) {
            if (e.code() == 429) {
                val retryAfter = e.response()?.headers()?.get("Retry-After")?.toLongOrNull() ?: 60
                android.util.Log.w(TAG, "Throttled by OneDrive. Retry-After: ${retryAfter}s")
                requestCoordinator.notifyThrottled(retryAfter)
            }
            android.util.Log.e(TAG, "HTTP error refrescando URL ($remoteId): ${e.code()}", e)
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error refrescando URL ($remoteId)", e)
            null
        }
    }

    suspend fun getDownloadUrl(remoteId: String): String? {
        return fetchDownloadUrl(remoteId, usePriorityPermit = false)
    }

    /**
     * Función para worker en segundo plano: Lee metadata real del archivo y extrae carátula
     */
    suspend fun extractMetadata(song: Song): Song = withContext(Dispatchers.IO) {
        // Sin reintento: leer los tags de un archivo es determinista, así que repetirlo da el
        // mismo resultado, y el analizador YA trae sus propios timeouts (generosos a propósito).
        // Reintentar solo los duplicaba, dejando a un archivo ilegible bloqueando el pipeline el
        // doble de tiempo.
        val analysis = try {
            if (song.path.startsWith("file://")) {
                val file = File(song.path.removePrefix("file://"))
                audioFileAnalyzer.analyzeFile(file)
            } else {
                audioFileAnalyzer.analyzeUrl(song.path)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Análisis de metadata fallido (${song.title})", e)
            null
        }

        if (analysis != null && analysis.isValid) {
            // Una sola vía de escritura para las carátulas (nombre por contenido, deduplicado):
            // aquí había una copia a mano de la misma lógica, y fue la que se quedó atrás cuando
            // el esquema de nombres cambió.
            val artUri = analysis.embeddedArt
                ?.let { audioFileAnalyzer.persistArtwork(it) }
                ?.let { Uri.parse(it) }
                ?: song.albumArtUri

            song.copy(
                title = analysis.title ?: song.title,
                artist = analysis.artist ?: song.artist,
                album = analysis.album ?: song.album,
                genre = analysis.genre ?: song.genre,
                trackNumber = analysis.trackNumber.takeIf { it > 0 } ?: song.trackNumber,
                year = analysis.year.takeIf { it > 0 } ?: song.year,
                duration = if (song.duration == 0L) analysis.duration else song.duration,
                albumArtUri = artUri
            )
        } else {
            song
        }
    }

}