package com.qhana.siku.data.remote

/**
 * Códigos HTTP que la app distingue, y los predicados con los que decide qué hacer con ellos.
 *
 * Existe porque la misma clasificación vivía COPIADA en cinco sitios y ninguno coincidía con otro:
 * `OneDriveRepository` la tenía nombrada (408 y 5xx), `MusicDownloader` la escribía cruda añadiendo
 * el 429, `ScanWorker` usaba una lista CERRADA (`408, 429, 500, 502, 503, 504`) que dejaba fuera
 * cualquier otro 5xx, `OneDriveFolderBrowser` declaraba sus propias constantes de 401/403 y
 * `LyricsRepository` comparaba contra un `404` suelto. Un número repetido en cinco sitios no se
 * mantiene: se desincroniza, y aquí ya lo había hecho.
 *
 * **Lo que NO se unifica es el criterio**, porque las diferencias entre esos sitios no eran todas
 * descuido: el 429 queda FUERA de [isRetriableTransport] a propósito. Tiene su propio mecanismo
 * (cabecera `Retry-After` → `RequestCoordinator.notifyThrottled`) y machacarlo con reintentos
 * rápidos solo alarga el castigo de OneDrive; quien sí quiera tratarlo como repetible lo compone
 * explícitamente en el sitio donde se decide. Aplanar los dos casos en un único `isTransient`
 * habría borrado esa decisión sin que nada avisara.
 *
 * Ojo a la asimetría que sale de eso: "repetir la MISMA petición dentro de un segundo" y
 * "reprogramar el trabajo dentro de unos minutos" son cosas distintas, y un throttle prohíbe la
 * primera pero no la segunda. Por eso el `ScanWorker` y la cola de descargas sí cuentan el 429 como
 * reintentable y [isRetriableTransport] no.
 */
internal object HttpStatus {
    const val UNAUTHORIZED = 401
    const val FORBIDDEN = 403
    const val NOT_FOUND = 404
    const val REQUEST_TIMEOUT = 408
    const val TOO_MANY_REQUESTS = 429
    const val SERVICE_UNAVAILABLE = 503

    /**
     * Primer código de la familia 5xx. No es un umbral ajustable: es la frontera FIJA que define
     * HTTP entre "culpa del cliente" (4xx, repetir da lo mismo) y "culpa del servidor" (5xx, que
     * sí puede cambiar en el siguiente intento).
     */
    const val SERVER_ERROR = 500

    /** El servidor falló por su lado, sea cual sea el 5xx concreto. */
    fun isServerError(code: Int): Boolean = code >= SERVER_ERROR

    /**
     * ¿Vale la pena repetir la petición TAL CUAL? Solo si el fallo es de transporte o del servidor.
     * Deliberadamente SIN el 429 — ver la nota de la clase.
     */
    fun isRetriableTransport(code: Int): Boolean =
        code == REQUEST_TIMEOUT || isServerError(code)

    /**
     * ¿El servicio está pidiendo que bajemos el ritmo? La respuesta correcta entonces es esperar lo
     * que diga, no reintentar.
     *
     * **No es solo el 429.** La guía de escaneo a escala de OneDrive dice que el throttling también
     * se manifiesta como 503, y que en ambos casos hay que respetar `Retry-After`:
     *
     * > *"your application may get a 429 or 503 response from Microsoft Graph. This indicates that
     * > your request is currently being throttled. To recover (…) try again after waiting for the
     * > duration specified in the Retry-After field. (…) Apps that do not honor the retry after
     * > duration before calling back will be blocked due to abusive calling patterns."*
     *
     * Es la CABECERA la que distingue los dos 503 posibles, y por eso entra en la firma: con
     * `Retry-After` es el servicio regulándonos, y sin ella es un 5xx corriente (el servicio caído o
     * un proxy por medio) que sí debe pasar por el reintento normal de [isRetriableTransport]. El
     * 429 es throttle siempre, traiga cabecera o no.
     */
    fun isThrottleSignal(code: Int, hasRetryAfter: Boolean): Boolean =
        code == TOO_MANY_REQUESTS || (code == SERVICE_UNAVAILABLE && hasRetryAfter)

    /** La sesión no vale (o no alcanza): reintentar es inútil hasta que el usuario vuelva a entrar. */
    fun isAuthFailure(code: Int): Boolean = code == UNAUTHORIZED || code == FORBIDDEN
}

/**
 * Segundos que pide esperar la respuesta, o `null` si no lo dice.
 *
 * **Limitación conocida**: `Retry-After` admite además una fecha HTTP (RFC 9110), que aquí se lee
 * como ausencia. Graph siempre responde con segundos —su propia documentación lo ejemplifica como
 * `Retry-After: 10`— y tratar un formato inesperado como "no dijo nada" degrada a la política
 * creciente del coordinador, que es el lado seguro.
 */
internal fun retrofit2.HttpException.retryAfterSeconds(): Long? =
    response()?.headers()?.get("Retry-After")?.toLongOrNull()
