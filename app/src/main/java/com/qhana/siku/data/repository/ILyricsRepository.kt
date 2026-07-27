package com.qhana.siku.data.repository

/**
 * Resultado de búsqueda de letras.
 */
sealed class LyricsResult {
    data class Found(val lyrics: String) : LyricsResult()
    object NotFound : LyricsResult()

    /**
     * @param isOffline el fallo fue no poder ALCANZAR el servidor (DNS, ruta, conexión), no una
     *        respuesta suya. Se distingue aquí, donde se conoce el tipo de excepción, porque es lo
     *        único que sabe de verdad si hay internet: la comprobación previa de conectividad da
     *        `true` con una red conectada que no llega a ninguna parte, y entonces al usuario le
     *        salía en pantalla el texto crudo `Unable to resolve host "lrclib.net"`.
     */
    data class Error(val message: String, val isOffline: Boolean = false) : LyricsResult()
}

/**
 * Candidato devuelto por la búsqueda manual (`/api/search` de LrcLib).
 * Contiene los datos suficientes para mostrar en la UI y, al elegirlo,
 * obtener las letras directamente sin un segundo request.
 */
data class LyricsCandidate(
    val id: Long,
    val trackName: String,
    val artistName: String,
    val albumName: String?,
    val durationSeconds: Double?,
    val syncedLyrics: String?,
    val plainLyrics: String?,
    val instrumental: Boolean
) {
    val hasSynced: Boolean get() = !syncedLyrics.isNullOrBlank()
    val hasPlain: Boolean get() = !plainLyrics.isNullOrBlank()

    /** Texto de letras a guardar al elegir este candidato. */
    val resolvedLyrics: String?
        get() = when {
            instrumental -> "[INSTRUMENTAL]"
            !syncedLyrics.isNullOrBlank() -> syncedLyrics
            !plainLyrics.isNullOrBlank() -> plainLyrics
            else -> null
        }
}

sealed class LyricsCandidatesResult {
    data class Found(val candidates: List<LyricsCandidate>) : LyricsCandidatesResult()
    object Empty : LyricsCandidatesResult()

    /** @param isOffline ver [LyricsResult.Error]. */
    data class Error(val message: String, val isOffline: Boolean = false) : LyricsCandidatesResult()
}

/**
 * Interfaz para el repositorio de letras, permitiendo mocking en tests unitarios.
 */
interface ILyricsRepository {
    suspend fun getLyricsWithResult(
        title: String,
        artist: String,
        album: String?,
        durationSeconds: Double?
    ): LyricsResult

    /** Búsqueda manual: devuelve candidatos para que el usuario elija. */
    suspend fun searchCandidates(title: String, artist: String): LyricsCandidatesResult
}
