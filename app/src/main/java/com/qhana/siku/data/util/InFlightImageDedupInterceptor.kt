package com.qhana.siku.data.util

import coil3.Extras
import coil3.intercept.Interceptor
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.size.Precision
import coil3.size.Scale
import coil3.size.Size
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.ConcurrentHashMap

/**
 * Hace que **dos peticiones idénticas que corren a la vez decodifiquen UNA sola vez**.
 *
 * Coil tiene caché de memoria, pero no fusiona peticiones **en vuelo**: la segunda mira el caché,
 * no encuentra nada porque la primera aún no terminó, y decodifica en paralelo. Medido con Perfetto
 * (release, 19 ago 2026): la misma portada de 1500×1500 decodificada dos veces a 800×800 con 29 ms
 * de diferencia —162 y 136 ms— y la misma otra vez dos veces a 156×156, que son dos filas del mismo
 * álbum entrando juntas en pantalla. Igualar la clave de caché no lo arregla: el problema no es que
 * las claves difieran sino que las dos salen antes de que ninguna haya llegado.
 *
 * Va como interceptor y no en los call sites a propósito: el caso se repite en superficies que no
 * se conocen entre sí (dos filas, un carrusel y su detalle, la píldora y el reproductor) y taparlo
 * de una en una deja la puerta abierta para la siguiente.
 *
 * **La cancelación es lo que hace esto seguro, y por eso el primero NO corre en un scope aparte.**
 * La petición que llega primera se ejecuta en SU propia corrutina, cancelable igual que siempre —
 * si su fila sale de pantalla durante un scroll, muere como antes. Lo único que cambia es que las
 * que llegan detrás esperan su resultado en vez de duplicarlo, y si esa primera muere o falla, cada
 * una lo carga por su cuenta. Un scope propio "para que no se cancele" habría hecho incancelables
 * las cargas de un scroll rápido, o sea MÁS trabajo justo donde menos sobra.
 */
class InFlightImageDedupInterceptor : Interceptor {

    /**
     * Todo lo que decide cómo sale el bitmap. Es deliberadamente conservadora: si dos peticiones
     * difieren en cualquier cosa —una transformación, `allowHardware`, la precisión— NO se comparten
     * y cada una hace su trabajo. Compartir de menos cuesta una decodificación; compartir de más
     * entrega un bitmap que no es el que pidieron.
     *
     * [Extras] entra entera (lleva transformaciones y flags de decodificación) y compara por valor,
     * que es lo que la anotación `@Poko` de Coil le da.
     */
    private data class Key(
        val data: Any,
        val explicitCacheKey: String?,
        val size: Size,
        val scale: Scale,
        val precision: Precision,
        val extras: Extras,
    )

    private val inFlight = ConcurrentHashMap<Key, CompletableDeferred<ImageResult>>()

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        // Solo las peticiones que usan el caché de memoria NORMAL, que son las de la UI. Quedan
        // fuera las que lo desactivan a propósito y no quieren compartir nada: el análisis de color
        // (`CachePolicy.DISABLED` + `allowHardware(false)`, un insumo que nadie más vuelve a pedir)
        // y el precalentado de una descarga recién terminada (`WRITE_ONLY`).
        if (request.memoryCachePolicy != CachePolicy.ENABLED) return chain.proceed()

        val key = request.dedupKey(chain.size)
        val mine = CompletableDeferred<ImageResult>()
        val running = inFlight.putIfAbsent(key, mine)

        if (running != null) {
            // Ya hay una igual en vuelo: esperar la suya. Cualquier final que no sea un éxito
            // —error, cancelación de la otra, o cancelación PROPIA— cae al camino normal, y en ese
            // último caso `ensureActive` corta aquí en vez de lanzar una petición condenada.
            val shared = runCatching { running.await() }.getOrNull()
            if (shared is SuccessResult) {
                // Con el request PROPIO: los listeners y el `AsyncImagePainter` de quien esperaba
                // leen de aquí, y devolverles el request ajeno les daría datos de otra petición.
                return shared.copy(request = request)
            }
            currentCoroutineContext().ensureActive()
            return chain.proceed()
        }

        return try {
            chain.proceed().also { mine.complete(it) }
        } catch (t: Throwable) {
            mine.completeExceptionally(t)
            throw t
        } finally {
            // `remove(key, value)` y no `remove(key)`: entre el fin de esta petición y esta línea,
            // otra pudo ocupar el hueco con SU deferred, y borrarlo dejaría a sus esperadores
            // colgados de un futuro que ya no publica nadie.
            inFlight.remove(key, mine)
        }
    }

    private fun ImageRequest.dedupKey(size: Size) = Key(
        data = data,
        explicitCacheKey = memoryCacheKey,
        size = size,
        scale = scale,
        precision = precision,
        extras = extras,
    )
}
