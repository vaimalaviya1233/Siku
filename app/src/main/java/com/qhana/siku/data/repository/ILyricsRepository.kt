package com.qhana.siku.data.repository

/**
 * Motivo de un fallo al buscar letras, TIPADO (no un texto).
 *
 * La capa de datos no fabrica mensajes de UI: hacerlo dejaba llegar a la pantalla literales en
 * inglés sin traducir —"Timeout", "Network error", "HTTP 500"— iguales en los dos idiomas. La UI
 * mapea cada motivo a un string localizado (ver `PlaybackViewModel.lyricsErrorMessage`).
 *
 * "No se pudo ALCANZAR el servidor" no vive aquí sino en el flag `isOffline` de [LyricsResult.Error]:
 * tiene su propio tratamiento en la UI (estado sin red, sin ofrecer "buscar en Google").
 */
enum class LyricsErrorReason {
    /** Se agotó el tiempo de espera del proveedor. */
    TIMEOUT,
    /** Error de red que no es "servidor inalcanzable" (ese va por `isOffline`). */
    NETWORK,
    /** El servidor respondió con un error (5xx u otro código no esperado). */
    SERVER,
    /** Cualquier otro fallo no clasificado. */
    UNKNOWN
}

/**
 * Resultado de búsqueda de letras.
 */
sealed class LyricsResult {
    data class Found(val lyrics: String) : LyricsResult()
    object NotFound : LyricsResult()

    /**
     * @param reason motivo tipado del fallo (la UI lo traduce; ver [LyricsErrorReason]).
     * @param isOffline el fallo fue no poder ALCANZAR el servidor (DNS, ruta, conexión), no una
     *        respuesta suya. Se distingue aquí, donde se conoce el tipo de excepción, porque es lo
     *        único que sabe de verdad si hay internet: la comprobación previa de conectividad da
     *        `true` con una red conectada que no llega a ninguna parte, y entonces al usuario le
     *        salía en pantalla el texto crudo `Unable to resolve host "lrclib.net"`.
     */
    data class Error(val reason: LyricsErrorReason, val isOffline: Boolean = false) : LyricsResult()
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

    /** @param reason/isOffline ver [LyricsResult.Error]. */
    data class Error(val reason: LyricsErrorReason, val isOffline: Boolean = false) : LyricsCandidatesResult()
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
