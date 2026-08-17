package com.qhana.siku.data.util

import android.os.SystemClock
import android.util.Log
import android.view.Choreographer

/**
 * Sonda de frames largos: durante una ventana tras un gesto ([arm]) mide el tiempo entre callbacks
 * del `Choreographer` y, cuando un frame supera [LONG_FRAME_MS], escribe en logcat su duración junto
 * con las últimas [marks] — qué pasó en el hilo principal en los milisegundos previos.
 *
 * Existe porque el jank al abrir una canción desde "Todas" sobrevivió a cuatro causas encontradas por
 * lectura de fuentes, y a partir de ahí adivinar sale más caro que medir.
 *
 * ## Se enciende en RELEASE, desde adb, sin tocar el código
 *
 * El jank solo se mide de verdad en release (con R8 y sin la instrumentación del debug), así que la
 * sonda NO va atada a `BuildConfig.DEBUG`. Se activa con el mecanismo estándar de Android para logs
 * bajo demanda — [Log.isLoggable] — que se lee UNA sola vez en [start]:
 *
 * ```
 * adb shell setprop log.tag.JankProbe DEBUG   # antes de abrir la app (o reabrirla después)
 * adb logcat -c && adb logcat -s JankProbe
 * ```
 *
 * Apagada (lo normal para cualquier usuario), cada `mark()` es un `if` sobre un booleano y nada más:
 * no construye strings ni toca el reloj. Los `mark()` repartidos por el código se quedan: son la
 * documentación viva de qué ocurre en el frame del tap.
 */
object JankProbe {
    private const val TAG = "JankProbe"
    private const val LONG_FRAME_MS = 20.0
    private const val WINDOW_MS = 2500L
    private const val MAX_MARKS = 24

    /** Se resuelve una vez en [start]; en release solo es `true` con la propiedad de sistema puesta. */
    @PublishedApi internal var enabled = false

    private val marks = ArrayDeque<String>()
    private var armedAtMs = 0L
    private var lastFrameNanos = 0L
    private var started = false

    // Histograma de la ventana en curso, para ver el RITMO además de los picos: una ventana que va
    // toda a 60 fps en un panel de 120 Hz (cada frame ~16,7 ms) no dispara ningún "frame largo" y aun
    // así se siente como lag — es exactamente lo que un umbral de 20 ms no puede ver.
    private var winFrames = 0
    private var winUnder9 = 0      // ≤ 1 vsync a 120 Hz: fluido
    private var winUnder17 = 0     // (9, 17]: un vsync perdido a 120 Hz (= 60 fps)
    private var winUnder25 = 0     // (17, 25]
    private var winOver25 = 0      // > 25
    private var winMaxMs = 0.0
    private var winLabel = ""
    private var winReported = true

    /** Arranca el bucle de frames (llamar una vez, desde `MainActivity.onCreate`). */
    fun start() {
        if (started) return
        started = true
        enabled = Log.isLoggable(TAG, Log.DEBUG)
        if (!enabled) return
        Log.i(TAG, "sonda ACTIVA (log.tag.$TAG=DEBUG); frames > ${LONG_FRAME_MS.toInt()} ms en los ${WINDOW_MS} ms tras cada gesto")
        Choreographer.getInstance().postFrameCallback(object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                val armed = SystemClock.uptimeMillis() - armedAtMs < WINDOW_MS
                if (lastFrameNanos != 0L && armed) {
                    val ms = (frameTimeNanos - lastFrameNanos) / 1_000_000.0
                    winFrames++
                    when {
                        ms <= 9.0 -> winUnder9++
                        ms <= 17.0 -> winUnder17++
                        ms <= 25.0 -> winUnder25++
                        else -> winOver25++
                    }
                    if (ms > winMaxMs) winMaxMs = ms
                    if (ms > LONG_FRAME_MS) {
                        Log.w(TAG, "FRAME LARGO %.0f ms (+%d ms) | %s".format(ms, sinceArm(), marks.joinToString(" › ")))
                        marks.clear()
                    }
                } else if (!armed && !winReported) {
                    // Cierre de ventana: el ritmo de TODOS sus frames, no solo los picos.
                    reportRhythm(partial = false)
                }
                lastFrameNanos = frameTimeNanos
                Choreographer.getInstance().postFrameCallback(this)
            }
        })
    }

    /**
     * Abre la ventana de medición (2,5 s) y anota el gesto que la abre. Lambda e `inline` a
     * propósito: apagada, el string ni se construye.
     */
    inline fun arm(reason: () -> String) {
        if (!enabled) return
        armNow(reason())
    }

    /** Anota un evento del hilo principal; se imprime junto al siguiente frame largo. Ver [arm]. */
    inline fun mark(what: () -> String) {
        if (!enabled) return
        markNow(what())
    }

    /**
     * Como [mark], pero se IMPRIME en el acto (dentro de la ventana armada), no solo si el frame es
     * largo: para seguir el ESTADO de una transición frame a frame —qué rama compone, qué target tiene
     * la capa, qué spec eligió el AnimatedContent— cuando el síntoma no es un frame lento sino una
     * animación que no ocurre. Apagada cuesta lo mismo que [mark]: un `if`.
     */
    inline fun note(what: () -> String) {
        if (!enabled) return
        noteNow(what())
    }

    @PublishedApi
    internal fun noteNow(what: String) {
        if (SystemClock.uptimeMillis() - armedAtMs >= WINDOW_MS) return
        Log.d(TAG, "   · +${sinceArm()} $what")
        markNow(what)
    }

    @PublishedApi
    internal fun armNow(reason: String) {
        // Un ARM sobre una ventana todavía abierta la cierra e imprime su ritmo (parcial) antes de
        // abrir la nueva: si no, un scroll continuo —que re-arma en cada fling— no imprimiría nunca.
        if (!winReported && winFrames > 0) reportRhythm(partial = true)
        winFrames = 0; winUnder9 = 0; winUnder17 = 0; winUnder25 = 0; winOver25 = 0; winMaxMs = 0.0
        winLabel = reason
        winReported = false
        armedAtMs = SystemClock.uptimeMillis()
        marks.clear()
        Log.i(TAG, "── ARM: $reason")
    }

    private fun reportRhythm(partial: Boolean) {
        winReported = true
        Log.i(
            TAG,
            "── RITMO%s '%s': %d frames | ≤9 ms: %d · 9-17: %d · 17-25: %d · >25: %d | máx %.0f ms".format(
                if (partial) " (parcial)" else "", winLabel, winFrames,
                winUnder9, winUnder17, winUnder25, winOver25, winMaxMs
            )
        )
    }

    @PublishedApi
    internal fun markNow(what: String) {
        if (SystemClock.uptimeMillis() - armedAtMs >= WINDOW_MS) return
        if (marks.size >= MAX_MARKS) marks.removeFirst()
        marks.addLast("+${sinceArm()} $what")
    }

    private fun sinceArm(): Long = SystemClock.uptimeMillis() - armedAtMs
}
