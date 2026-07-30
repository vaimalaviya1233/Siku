package com.qhana.siku.data.model

import androidx.media3.common.PlaybackException

/**
 * Tipo semántico de error de reproducción, derivado del código de Media3.
 * Permite manejar el error sin depender de strings frágiles del mensaje.
 */
enum class PlaybackErrorType {
    NETWORK,
    FILE_NOT_FOUND,
    DECODER,
    UNKNOWN
}

/**
 * Información estructurada de error de reproducción emitida por MusicController.
 *
 * @property errorCode código de Media3 (`PlaybackException.ERROR_CODE_*`), o `-1` si es genérico.
 * @property message mensaje original para logs y UI.
 * @property songId canción que FALLÓ. Es parte del evento y no se deduce de `currentSong` al
 *   recibirlo: entre la emisión y el consumo puede haber una transición de item (auto-avance,
 *   botón de la notificación), y entonces la recuperación —marcar corrupta, borrar el audio,
 *   forzar descarga— recaía sobre la canción EQUIVOCADA. `null` solo en errores sin item
 *   asociado, donde el consumidor cae a la canción en curso.
 */
data class PlaybackErrorInfo(
    val errorCode: Int,
    val message: String,
    val songId: String? = null
) {
    val type: PlaybackErrorType
        get() = when (errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> PlaybackErrorType.NETWORK

            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> PlaybackErrorType.FILE_NOT_FOUND

            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> PlaybackErrorType.DECODER

            // Sin código conocido, UNKNOWN — y el recovery intenta sanar (refrescar URL,
            // re-descargar) antes de rendirse. Aquí había una rama que declaraba LOOP_DETECTED
            // —tratado como corrupción, o sea marcar la canción y borrar su audio— buscando
            // "loop detected" en el mensaje, justo lo que el kdoc de arriba dice evitar. Era
            // frágil por partida doble: el texto de Media3 no es API (cambia entre versiones y
            // no está traducido), y el caso real que pretendía cazar —HTTP 508 Loop Detected—
            // llega como ERROR_CODE_IO_BAD_HTTP_STATUS y lo captura la rama NETWORK de arriba,
            // así que nunca alcanzaba este `else`. Un bucle de redirecciones se cura
            // refrescando la URL, que es exactamente lo que hace el camino UNKNOWN.
            else -> PlaybackErrorType.UNKNOWN
        }

    companion object {
        val UNKNOWN = PlaybackErrorInfo(-1, "Unknown playback error")
    }
}
