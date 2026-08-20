package com.qhana.siku.ui.components

import kotlinx.coroutines.delay

/**
 * Reloj de una animación continua que despierta **solo cuando hay un píxel que mover**, en vez de en
 * cada vsync.
 *
 * ## El problema que resuelve (medido con Perfetto, 20 ago 2026)
 *
 * Los relojes de esta app —onda del MiniPlayer, giro de la cookie, fase y progreso del NowPlaying—
 * seguían todos el mismo patrón:
 *
 * ```
 * while (true) { withFrameNanos { now -> if (avanceDesdeLoPublicado >= 1px) publicar() } }
 * ```
 *
 * La regla del píxel evita el DIBUJO, y funcionó: el buffer stuffing bajó del 50 % al 13 %. Pero el
 * bucle mantiene un frame callback pendiente **siempre**, y eso es justo lo que hace que el
 * Choreographer programe el siguiente vsync. Resultado medido en tres segundos sin una sola
 * invalidación (`draw` = 0, `measure` = 0, `Recomposer:animation` = 0):
 *
 * ```
 * Choreographer#doFrame        378 us  x121/s   = 45 ms de cada segundo
 * └─ animation                 296 us
 *    └─ scheduleVsyncLocked    260 us           <- la llamada binder a SurfaceFlinger
 * ```
 *
 * O sea **4,5 % del hilo principal quemado en pedir vsyncs para no dibujar nada**. Tres KDoc de la
 * app afirmaban que `withFrameNanos` "no produce frames por sí mismo"; la mitad cierta es que no
 * INVALIDA, pero despertar sí despierta, y el coste de despertar no es cero.
 *
 * ## Cómo lo resuelve
 *
 * `delay` en vez de `withFrameNanos`: no registra ningún frame callback, así que entre publicación y
 * publicación el pipeline queda de verdad en silencio y la cola de SurfaceFlinger drena. El intervalo
 * lo calcula el caller a partir de la velocidad REAL de su animación ([stepMillis]), así que cada
 * despertar tiene un píxel que enseñar. Para la barra de progreso del reproductor eso es pasar de
 * 120 despertares por segundo a menos de dos.
 *
 * ## Lo que NO cambia
 *
 * El caller sigue calculando su avance por TIEMPO transcurrido, nunca por número de ticks: `delay`
 * garantiza un mínimo, no una cadencia exacta, y un tick que llegue tarde no debe frenar la
 * animación. Por eso [onTick] recibe el reloj monótono y no un contador — el mismo contrato que
 * tenía `withFrameNanos`, de modo que la matemática de cada animación se queda como estaba.
 *
 * ## Cuándo NO usar esto
 *
 * Cuando la animación mueve un píxel o más en cada frame (un slide, un morph, un fling). Ahí cada
 * vsync tiene algo que enseñar, `withFrameNanos` es lo correcto y `delay` solo añadiría desfase
 * contra el ritmo de la pantalla.
 *
 * @param stepMillis cuánto tarda la animación en avanzar UN píxel. Se acota a [MIN_STEP_MS] para no
 *   despertar más que la propia pantalla si el caller pide un paso absurdo.
 * @param onTick recibe `System.nanoTime()`. Se llama una vez al entrar, para que el caller fije su
 *   origen de tiempo sin esperar al primer intervalo.
 */
internal suspend inline fun pixelPacedClock(
    stepMillis: Long,
    crossinline onTick: (nowNanos: Long) -> Unit
) {
    val step = stepMillis.coerceAtLeast(MIN_STEP_MS)
    onTick(System.nanoTime())
    while (true) {
        delay(step)
        onTick(System.nanoTime())
    }
}

/**
 * Suelo del intervalo: por debajo de un frame a 120 Hz no hay nada que ganar —la pantalla no puede
 * enseñarlo— y sí un despertar que pagar.
 */
internal const val MIN_STEP_MS = 8L

/**
 * Intervalo para una animación que recorre [pixelsPerSecond] píxeles por segundo, es decir cuánto
 * tarda en moverse uno. Con velocidad cero o negativa devuelve [Long.MAX_VALUE]: el caller no debería
 * haber arrancado el reloj.
 */
internal fun stepMillisFor(pixelsPerSecond: Float): Long =
    if (pixelsPerSecond <= 0f) Long.MAX_VALUE
    else (1000f / pixelsPerSecond).toLong().coerceAtLeast(MIN_STEP_MS)
