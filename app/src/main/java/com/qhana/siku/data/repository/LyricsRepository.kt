package com.qhana.siku.data.repository

import com.qhana.siku.data.remote.HttpStatus
import com.qhana.siku.data.remote.LrcLibApi
import com.qhana.siku.data.remote.LrcLibResponse
import kotlinx.coroutines.withTimeout
import java.io.IOException
import javax.inject.Inject

/**
 * Repositorio para obtener letras de canciones desde LrcLib.
 *
 * Estrategia:
 * - Búsqueda automática: `/api/get` con título/artista crudos (sin limpiar para
 *   respetar versiones específicas tipo "Taylor's Version", "Remastered", etc.).
 *   La duración se omite si es ≤ 0 (canciones sin metadata aún extraída).
 * - Búsqueda manual: `/api/search` devuelve candidatos para que el usuario elija
 *   cuando el match exacto falla o devuelve la versión equivocada.
 */
class LyricsRepository @Inject constructor(
    private val lrcLibApi: LrcLibApi
) : ILyricsRepository {

    companion object {
        private const val PROVIDER_TIMEOUT_MS = 10_000L
    }

    override suspend fun getLyricsWithResult(
        title: String,
        artist: String,
        album: String?,
        durationSeconds: Double?
    ): LyricsResult {
        return try {
            withTimeout(PROVIDER_TIMEOUT_MS) {
                fetchFromLrcLib(title, artist, album, durationSeconds)
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            LyricsResult.Error(LyricsErrorReason.TIMEOUT)
        }
    }

    override suspend fun searchCandidates(title: String, artist: String): LyricsCandidatesResult {
        return try {
            withTimeout(PROVIDER_TIMEOUT_MS) {
                val results = lrcLibApi.searchLyrics(title, artist.takeIf { it.isNotBlank() })
                val candidates = results.mapNotNull { it.toCandidate() }
                if (candidates.isEmpty()) LyricsCandidatesResult.Empty
                else LyricsCandidatesResult.Found(candidates)
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            LyricsCandidatesResult.Error(LyricsErrorReason.TIMEOUT)
        } catch (e: IOException) {
            LyricsCandidatesResult.Error(LyricsErrorReason.NETWORK, isOffline = e.isUnreachable())
        } catch (e: retrofit2.HttpException) {
            if (e.code() == HttpStatus.NOT_FOUND) LyricsCandidatesResult.Empty
            else LyricsCandidatesResult.Error(LyricsErrorReason.SERVER)
        } catch (e: Exception) {
            LyricsCandidatesResult.Error(LyricsErrorReason.UNKNOWN)
        }
    }

    private suspend fun fetchFromLrcLib(title: String, artist: String, album: String?, durationSeconds: Double?): LyricsResult {
        return try {
            // Omitir duration si es ≤ 0 (canciones sin metadata aún): LrcLib filtra por
            // duración cercana y enviar 0.0 garantiza NotFound aunque la letra exista.
            val safeDuration = durationSeconds?.takeIf { it > 0.0 }
            val response = lrcLibApi.getLyrics(artist, title, album, safeDuration)
            when {
                response.instrumental == true -> LyricsResult.Found("[INSTRUMENTAL]")
                !response.syncedLyrics.isNullOrEmpty() -> LyricsResult.Found(response.syncedLyrics)
                !response.plainLyrics.isNullOrEmpty() -> LyricsResult.Found(response.plainLyrics)
                else -> LyricsResult.NotFound
            }
        } catch (e: IOException) {
            LyricsResult.Error(LyricsErrorReason.NETWORK, isOffline = e.isUnreachable())
        } catch (e: retrofit2.HttpException) {
            if (e.code() == HttpStatus.NOT_FOUND) LyricsResult.NotFound
            else LyricsResult.Error(LyricsErrorReason.SERVER)
        } catch (e: com.google.gson.JsonSyntaxException) {
            LyricsResult.NotFound
        } catch (e: Exception) {
            LyricsResult.Error(LyricsErrorReason.UNKNOWN)
        }
    }

    /**
     * ¿El fallo fue no llegar al servidor? `UnknownHostException` es el caso típico sin datos ni
     * WiFi (el DNS no resuelve), y los otros dos cubren la red conectada que no encamina a
     * internet. Un timeout NO cuenta: ahí el servidor puede existir y estar lento, y decirle al
     * usuario "no hay conexión" sería mentirle.
     */
    private fun IOException.isUnreachable(): Boolean = when (this) {
        is java.net.UnknownHostException,
        is java.net.ConnectException,
        is java.net.NoRouteToHostException -> true
        else -> false
    }

    private fun LrcLibResponse.toCandidate(): LyricsCandidate? {
        val candidateId = id ?: return null
        val track = trackName?.takeIf { it.isNotBlank() } ?: return null
        val artistName = artistName?.takeIf { it.isNotBlank() } ?: return null
        // Filtrar candidatos sin nada útil: ni synced, ni plain, ni marca de instrumental.
        if (instrumental != true && syncedLyrics.isNullOrBlank() && plainLyrics.isNullOrBlank()) return null
        return LyricsCandidate(
            id = candidateId,
            trackName = track,
            artistName = artistName,
            albumName = albumName?.takeIf { it.isNotBlank() },
            durationSeconds = duration?.takeIf { it > 0.0 },
            syncedLyrics = syncedLyrics,
            plainLyrics = plainLyrics,
            instrumental = instrumental == true
        )
    }
}
