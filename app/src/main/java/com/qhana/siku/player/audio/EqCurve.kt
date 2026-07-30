package com.qhana.siku.player.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

/**
 * Matemática de la curva del ecualizador en versión PURA, para la UI (indicador de headroom).
 *
 * ¿Por qué existe si [EqualizerAudioProcessor] ya sabe hacer esto? Porque las dos rutas tienen
 * restricciones opuestas: la del processor corre en el HILO DE AUDIO y no puede asignar ni un
 * objeto (ver el kdoc de esa clase), así que trabaja sobre arrays preasignados y evalúa solo en
 * las frecuencias centrales; esta corre en la UI, necesita barrer una rejilla densa para
 * encontrar el pico REAL de la curva (que cae entre centros, por el ripple) y puede asignar.
 * Fundirlas obligaría a una de las dos a cargar con las limitaciones de la otra.
 *
 * Lo que NO puede pasar es que diverjan: [compensate] es el mismo punto fijo Gauss-Seidel con las
 * mismas iteraciones y el mismo techo que `recomputeCompensation`. Si se toca una, la otra
 * también.
 *
 * El DISEÑO del filtro ya no está duplicado: los coeficientes salen de
 * [EqualizerAudioProcessor.matchedPeakCoeffs], que vive en el companion del processor porque las
 * dos rutas necesitan exactamente la misma fórmula y tenerla dos veces era la forma más fácil de
 * que se separaran. Lo que sigue duplicado a propósito es esto: el punto fijo y el barrido de la
 * rejilla, que son justo las partes con restricciones opuestas.
 *
 * Se evalúa a [REFERENCE_SAMPLE_RATE] porque el indicador es informativo y el pico es
 * indistinguible entre 44.1, 48 y 96 kHz (medido: mismas ganancias compensadas hasta el segundo
 * decimal). El processor sí usa el sample rate real.
 *
 * Rendimiento: esto se recalcula en cada frame de arrastre de un slider, así que los coeficientes
 * de cada filtro se calculan UNA vez y la rejilla de `z` está precalculada. Sin eso serían miles
 * de senos y cosenos por frame.
 */
object EqCurve {

    /** Sample rate de referencia del cálculo de UI (ver kdoc). */
    private const val REFERENCE_SAMPLE_RATE = 44_100.0

    /**
     * Puntos de la rejilla logarítmica del barrido. Suficientes para no perderse un pico, y
     * también para dibujar la curva sin que se vean los segmentos (ver [response]).
     */
    const val GRID_POINTS = 160

    const val GRID_LOW_HZ = 20.0

    /** Extremo alto de la rejilla: Nyquist del sample rate de referencia. */
    const val GRID_HIGH_HZ = REFERENCE_SAMPLE_RATE / 2.0

    /**
     * Frecuencia del punto [index] de la rejilla. La rejilla es LOGARÍTMICA, así que el gráfico
     * puede repartir los puntos a intervalos iguales en X y obtener un eje de frecuencia
     * logarítmico —que es como se percibe el tono— sin hacer ninguna conversión.
     */
    fun frequencyAt(index: Int): Double =
        GRID_LOW_HZ * (GRID_HIGH_HZ / GRID_LOW_HZ).pow(index / (GRID_POINTS - 1.0))


    /** 5 doubles consecutivos por filtro (b0,b1,b2,a1,a2); la constante es del processor. */
    private const val COEFFS_PER_FILTER = EqualizerAudioProcessor.COEFFS_PER_FILTER

    // Rejilla de evaluación: z = e^{-jω} y z² en cada punto, calculados una sola vez.
    private val gridZRe = DoubleArray(GRID_POINTS)
    private val gridZIm = DoubleArray(GRID_POINTS)
    private val gridZ2Re = DoubleArray(GRID_POINTS)
    private val gridZ2Im = DoubleArray(GRID_POINTS)

    init {
        val nyquist = REFERENCE_SAMPLE_RATE / 2.0
        for (i in 0 until GRID_POINTS) {
            val f = GRID_LOW_HZ * (nyquist / GRID_LOW_HZ).pow(i / (GRID_POINTS - 1.0))
            val w = 2.0 * PI * f / REFERENCE_SAMPLE_RATE
            val zr = cos(w)
            val zi = -sin(w)
            gridZRe[i] = zr
            gridZIm[i] = zi
            gridZ2Re[i] = zr * zr - zi * zi
            gridZ2Im[i] = 2.0 * zr * zi
        }
    }

    /**
     * Pico en dB de la curva COMPLETA: bandas (ya compensadas) más los dos refuerzos. Es lo que la
     * señal puede ganar sobre su nivel original, o sea el headroom que hace falta para que un
     * máster a fondo de escala no recorte.
     *
     * Los centros de los refuerzos llegan por parámetro y NO se leen de las constantes: el usuario
     * los mueve, y mover el centro cambia el pico (un refuerzo de graves a 250 Hz se solapa con
     * bandas distintas que uno a 40 Hz). Leer el default aquí haría que el indicador midiera una
     * curva que no es la que suena.
     *
     * NO incluye el preamp A PROPÓSITO: este valor es lo que pide la CURVA, y de él sale el preamp
     * SUGERIDO por la UI (que es exactamente su negativo, la convención de los perfiles de AutoEQ).
     * Si el preamp entrara aquí, sugerirlo se volvería circular. El headroom que se muestra al
     * usuario es este número MÁS el preamp, y esa suma la hace quien lo pinta.
     *
     * LIMITACIÓN: esto mide la ganancia de la CURVA, no el nivel de salida — no sabe a qué nivel
     * está masterizada la canción. Sobre un máster moderno a 0 dBFS un refuerzo modesto ya recorta
     * con el indicador en verde. Ver el comentario de los umbrales en `EqualizerSheet`.
     */
    fun peakGainDb(
        bandGainsDb: FloatArray,
        frequencies: FloatArray,
        bassBoostDb: Float,
        trebleBoostDb: Float,
        bassBoostFreqHz: Double,
        trebleBoostFreqHz: Double
    ): Float {
        var peak = 0f
        val curve = response(
            bandGainsDb, frequencies, bassBoostDb, trebleBoostDb, bassBoostFreqHz, trebleBoostFreqHz
        )
        for (db in curve) if (db > peak) peak = db
        return peak
    }

    /**
     * Respuesta en frecuencia COMPLETA: [GRID_POINTS] magnitudes en dB sobre la rejilla
     * logarítmica, en el mismo orden que [frequencyAt]. Es lo que dibuja el gráfico de la hoja del
     * ecualizador.
     *
     * Incluye los refuerzos, que es justo el punto: son ADITIVOS sobre la curva de bandas y su
     * efecto combinado no se puede deducir mirando los controles por separado — no verlo fue una
     * de las causas de que los boosts sonaran mal (ver el kdoc de [EqualizerAudioProcessor]).
     *
     * Se recalcula en cada frame de arrastre de un slider. Por eso los coeficientes de cada filtro
     * se calculan una sola vez y la rejilla de `z` está precalculada: sin eso serían miles de senos
     * y cosenos por frame. [peakGainDb] es el máximo de este mismo barrido.
     */
    fun response(
        bandGainsDb: FloatArray,
        frequencies: FloatArray,
        bassBoostDb: Float,
        trebleBoostDb: Float,
        bassBoostFreqHz: Double,
        trebleBoostFreqHz: Double,
        /**
         * Desplaza la curva ENTERA, que es justo lo que hace un preamp: una ganancia global no
         * cambia la forma, solo la altura. Va aquí y no en [peakGainDb] porque el gráfico tiene
         * que enseñar lo que de verdad va a sonar — si el preamp no bajara la curva, el usuario
         * lo movería y no vería absolutamente nada.
         */
        preampDb: Float = 0f
    ): FloatArray {
        val q = qFor(frequencies.size)
        val compensated = compensate(bandGainsDb, frequencies, q)

        // Filtros activos: bandas compensadas + los dos refuerzos (que NO se compensan).
        val gains = ArrayList<Double>(compensated.size + 2)
        val freqs = ArrayList<Double>(compensated.size + 2)
        val qs = ArrayList<Double>(compensated.size + 2)
        for (i in compensated.indices) {
            if (abs(compensated[i]) < EqualizerAudioProcessor.IDENTITY_EPSILON_DB) continue
            gains.add(compensated[i])
            freqs.add(frequencies[i].toDouble())
            qs.add(q)
        }
        if (abs(bassBoostDb) >= EqualizerAudioProcessor.IDENTITY_EPSILON_DB) {
            gains.add(bassBoostDb.toDouble())
            freqs.add(bassBoostFreqHz)
            qs.add(EqualizerAudioProcessor.BOOST_Q)
        }
        if (abs(trebleBoostDb) >= EqualizerAudioProcessor.IDENTITY_EPSILON_DB) {
            gains.add(trebleBoostDb.toDouble())
            freqs.add(trebleBoostFreqHz)
            qs.add(EqualizerAudioProcessor.BOOST_Q)
        }
        // Curva plana: no hay filtros que evaluar y la respuesta es el preamp en todos los puntos
        // (una recta a su altura, que es exactamente lo que hace un preamp sin EQ).
        if (gains.isEmpty()) return FloatArray(GRID_POINTS) { preampDb }

        val coeffs = DoubleArray(gains.size * COEFFS_PER_FILTER)
        for (i in gains.indices) {
            peakCoeffs(gains[i], freqs[i], qs[i], coeffs, i * COEFFS_PER_FILTER)
        }

        return FloatArray(GRID_POINTS) { p ->
            var db = preampDb.toDouble()
            for (i in gains.indices) {
                db += magnitudeDb(coeffs, i * COEFFS_PER_FILTER, p)
            }
            db.toFloat()
        }
    }

    private fun qFor(bandCount: Int): Double =
        if (bandCount == EqualizerAudioProcessor.BANDS_10_COUNT) {
            EqualizerAudioProcessor.Q_10_BANDS
        } else {
            EqualizerAudioProcessor.Q_5_BANDS
        }

    /**
     * Mismo punto fijo Gauss-Seidel que `EqualizerAudioProcessor.recomputeCompensation`: devuelve
     * la ganancia que hay que darle a cada biquad para que la curva valga lo pedido en los centros.
     */
    private fun compensate(targetDb: FloatArray, frequencies: FloatArray, q: Double): DoubleArray {
        val n = minOf(targetDb.size, frequencies.size)
        val target = DoubleArray(n) { targetDb[it].toDouble() }
        val out = DoubleArray(n) { target[it] }
        val coeffs = DoubleArray(COEFFS_PER_FILTER)
        repeat(EqualizerAudioProcessor.COMPENSATION_ITERATIONS) {
            for (i in 0 until n) {
                val at = frequencies[i].toDouble()
                var response = 0.0
                for (j in 0 until n) {
                    if (abs(out[j]) < EqualizerAudioProcessor.IDENTITY_EPSILON_DB) continue
                    peakCoeffs(out[j], frequencies[j].toDouble(), q, coeffs, 0)
                    response += magnitudeDbAt(coeffs, 0, at)
                }
                out[i] = (out[i] + (target[i] - response)).coerceIn(
                    -EqualizerAudioProcessor.COMPENSATION_CEILING_DB,
                    EqualizerAudioProcessor.COMPENSATION_CEILING_DB
                )
            }
        }
        return out
    }

    /**
     * Coeficientes de un peaking-EQ **matched**, escritos en [out] desde [offset]. Delega en
     * [EqualizerAudioProcessor.matchedPeakCoeffs] a [REFERENCE_SAMPLE_RATE], que es la MISMA
     * función que usa el hilo de audio: por construcción, el gráfico no puede dibujar una curva
     * distinta de la que suena.
     */
    private fun peakCoeffs(
        gainDb: Double,
        f0: Double,
        q: Double,
        out: DoubleArray,
        offset: Int
    ) {
        EqualizerAudioProcessor.matchedPeakCoeffs(
            gainDb, f0, q, REFERENCE_SAMPLE_RATE, out, offset
        )
    }

    /** Magnitud en dB en el punto [gridIndex] de la rejilla precalculada. */
    private fun magnitudeDb(coeffs: DoubleArray, offset: Int, gridIndex: Int): Double =
        magnitude(
            coeffs, offset,
            gridZRe[gridIndex], gridZIm[gridIndex],
            gridZ2Re[gridIndex], gridZ2Im[gridIndex]
        )

    /** Magnitud en dB en una frecuencia arbitraria (la usa la compensación, fuera de rejilla). */
    private fun magnitudeDbAt(coeffs: DoubleArray, offset: Int, at: Double): Double {
        val w = 2.0 * PI * at / REFERENCE_SAMPLE_RATE
        val zr = cos(w)
        val zi = -sin(w)
        return magnitude(coeffs, offset, zr, zi, zr * zr - zi * zi, 2.0 * zr * zi)
    }

    private fun magnitude(
        coeffs: DoubleArray,
        offset: Int,
        zr: Double,
        zi: Double,
        z2r: Double,
        z2i: Double
    ): Double {
        val b0 = coeffs[offset]
        val b1 = coeffs[offset + 1]
        val b2 = coeffs[offset + 2]
        val a1 = coeffs[offset + 3]
        val a2 = coeffs[offset + 4]
        val numRe = b0 + b1 * zr + b2 * z2r
        val numIm = b1 * zi + b2 * z2i
        val denRe = 1.0 + a1 * zr + a2 * z2r
        val denIm = a1 * zi + a2 * z2i
        val numMag2 = numRe * numRe + numIm * numIm
        val denMag2 = denRe * denRe + denIm * denIm
        if (numMag2 <= 0.0 || denMag2 <= 0.0) return 0.0
        return 10.0 * log10(numMag2 / denMag2)
    }
}
