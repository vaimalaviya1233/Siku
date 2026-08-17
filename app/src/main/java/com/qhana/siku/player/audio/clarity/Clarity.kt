package com.qhana.siku.player.audio.clarity

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/**
 * **Clarity**: realce de la parte alta. Toma una copia de la señal por encima de [HPF_HZ], la pasa
 * por una saturación muy suave y la suma de vuelta.
 *
 * ```
 *  entrada ─┬─────────────────────────────────────────┬─ (+) ─ salida
 *           └─ HPF(1200) ─ tanh ─ HPF(1200) ─ ganancia ┘
 * ```
 *
 * Un interruptor y un control: cuánto.
 *
 * ## Qué es y qué NO es (importa, porque el nombre engaña)
 *
 * Esto **no es un exciter**, aunque nació queriendo serlo. Con [DRIVE] = 2.5 y una señal de nivel
 * normal, `tanh` opera en su zona recta: medido sobre música real, la rama es **99.95 % una copia
 * lineal** de la señal filtrada. O sea que funciona como un *shelf* de agudos con un rastro de
 * armónicos, y así es como hay que pensarlo. Consecuencia práctica que apareció en la escucha:
 * **subir la cantidad no "hace crecer" nada, solo escala** lo que ya hay, sibilancia incluida.
 *
 * Sigue aquí, con estos valores exactos, porque en un A/B contra la versión que SÍ era un exciter
 * de verdad —polinomio de grado 3, banda de entrada acotada, normalizador lento— **ganó esta**.
 * Aquella sintetizaba entre el 10 % y el 41 % de contenido nuevo, y ese contenido resultó ser sobre
 * todo ruido: un waveshaper sobre señal de banda ancha no genera armónicos limpios sino
 * INTERMODULACIÓN, del orden de N² productos cruzados entre los N parciales simultáneos que tiene
 * la música en cualquier instante.
 *
 * ## Historia, para no repetirla
 *
 * Siete rondas y cuatro reescrituras completas, todas las variantes descartadas por ESCUCHA: bajar
 * la Fc ("se parece a Brillante y ambos perdieron calidad"), subirla ("empeoró al 100 %"), extraer
 * el residuo armónico ("un ruido como tocadisco antiguo, hsssss") y el exciter de verdad
 * ("subiendo empeoraba todo"). Lo que se aprendió:
 *
 * - **Las métricas estaban mal ELEGIDAS, no mal calculadas.** Se midió "energía inarmónica añadida"
 *   y se minimizó, cuando en un exciter esa energía ES el efecto. Optimizar contra ella era
 *   optimizar contra lo que se buscaba.
 * - **El problema de fondo no era este módulo.** Lo que se quería —presencia en la voz— se resolvió
 *   en cinco minutos con el ECUALIZADOR (subir 500 Hz / 1 kHz / 2 kHz más el refuerzo de agudos),
 *   que es la herramienta correcta para eso: lineal, sin ruido y con el headroom a la vista.
 *
 * **Si alguien vuelve a plantearse "hacerlo un exciter de verdad": ya se hizo, y perdió el A/B.**
 *
 * ## Reglas del hilo de audio
 *
 * Las mismas que `EqualizerAudioProcessor`: cero asignaciones después de [configure], ningún
 * parámetro que salte de golpe (ganancia suavizada, encendido por rampa) y **anti-denormales** en
 * el estado de los biquads — en Kotlin no se puede activar el bit FZ del FPSCR, y un filtro que
 * decae en silencio acaba en el rango denormal, donde el hardware se vuelve entre 10× y 100× más
 * lento.
 *
 * Latencia: **cero**. No hay línea de retardo, así que no cambia la sincronización de nada ni hay
 * que drenarlo al final de la pista.
 */
@Singleton
class Clarity @Inject constructor() {

    companion object {
        const val MIN_GAIN_DB = 0f

        /**
         * Tope de la cantidad. **6 dB porque por encima de eso lo que se añade es ruido**, decidido
         * por escucha sobre material variado. Estuvo en 12 una temporada y ese recorrido de más
         * nunca fue utilizable.
         */
        const val MAX_GAIN_DB = 6f

        /**
         * Cantidad con la que arranca quien lo enciende sin tocar nada más: **el mínimo**.
         *
         * Es el valor con el que el efecto de verdad se usa. En las pruebas de escucha, el ajuste
         * que quedó fue 0 en todo el material —con buen máster y con loudness war—, porque subir de
         * ahí no aporta: al ser una saturación casi lineal (ver el kdoc de la clase) el control
         * escala lo que hay en vez de hacerlo crecer, sibilancia incluida. Arrancar en el mínimo es
         * además lo prudente para un módulo que suma energía a una cadena que puede venir con la
         * curva del ecualizador ya subida.
         */
        const val DEFAULT_GAIN_DB = MIN_GAIN_DB

        /**
         * Desde dónde trabaja. 1200 Hz sale de la escucha y no de un cálculo: es la variante que
         * "se escucha bien en general". Subirlo a 1500 se probó y el veredicto fue "empeoró al
         * 100 %"; bajarlo a 900 lo volvía sucio sobre la voz.
         */
        private const val HPF_HZ = 1_200.0

        /**
         * Cuánto aprieta la saturación. Con este valor la curva es prácticamente recta para señal
         * de nivel normal — ver el kdoc de la clase: eso es una descripción de lo que el módulo es,
         * no un defecto pendiente de arreglar.
         */
        private const val DRIVE = 2.5

        /** Butterworth de 2º orden. Con 4º la rotación de fase abría un agujero en 2–5 kHz. */
        private const val BUTTER_Q = 0.70710678

        /**
         * Techo del corte del filtro como fracción del sample rate, para no diseñar un biquad con
         * su f0 pegada a Nyquist (fs/2), donde la transformada bilineal degenera. Es una guarda
         * DEFENSIVA y no una decisión de timbre: con [HPF_HZ] en 1200 Hz solo podría activarse por
         * debajo de unos 2,7 kHz de sample rate, que ninguna ruta de audio real entrega. El 10 % de
         * margen bajo la mitad es lo que la deja fuera de la zona donde los coeficientes se vuelven
         * numéricamente frágiles.
         */
        private const val MAX_FC_NYQUIST_RATIO = 0.45

        /**
         * Cuánto vale el "0 dB" del control. **Ojo al comparar este número con el de otra app**: el
         * dB es universal como razón, pero la referencia la elige quien escribe el módulo.
         */
        private const val WET_CALIBRATION = 0.5

        /**
         * Suavizado de la cantidad. Sin esto, arrastrar el slider es un tren de escalones de
         * ganancia — el mismo zipper noise que costó dos semanas de diagnóstico en el ecualizador.
         */
        private const val GAIN_SMOOTHING_SECONDS = 0.020

        /**
         * Rampa de encendido y apagado. Lo que hay que tapar no es la transición de timbre sino el
         * CLICK de reiniciar el estado de los filtros.
         */
        private const val FADE_SECONDS = 0.010

        /**
         * Se suma al estado de cada biquad para que no caiga en el rango denormal. 1e-300 y no el
         * 1e-18 habitual: ese es el mínimo normal de `float`, y esta cadena es `double`, cuyo
         * mínimo normal es 2.2e-308.
         */
        private const val ANTI_DENORMAL = 1e-300
    }

    // --- Parámetros. @Volatile: los escribe la UI, los lee el hilo de audio.

    @Volatile private var enabled = false
    @Volatile private var requestedGainDb = DEFAULT_GAIN_DB

    /**
     * Marca de "algo cambió". Se limpia ANTES de leer los parámetros, igual que `coeffsDirty` en
     * `EqualizerAudioProcessor` y por el mismo motivo: al revés, una escritura de la UI entre las
     * dos lecturas se perdería para siempre — y el valor que se pierde es justo el último de un
     * slider, o sea el que el usuario quería dejar.
     */
    @Volatile private var paramsDirty = true

    fun isEnabled(): Boolean = enabled

    /** En vivo: no reconstruye nada, así que se puede comparar A/B con la música sonando. */
    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        paramsDirty = true
    }

    fun getGainDb(): Float = requestedGainDb

    fun setGainDb(value: Float) {
        val clamped = value.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
        if (requestedGainDb == clamped) return
        requestedGainDb = clamped
        paramsDirty = true
    }

    // --- Estado del hilo de audio. Todo se asigna en configure() y desde ahí solo se muta.

    private var channels = 0
    private var sampleRate = 0.0

    private var hpfIn = Biquad()
    private var hpfOut = Biquad()

    private var targetGain = 0.0
    private var currentGain = 0.0
    private var gainSmoothing = 0.0

    /** Rampa de encendido/apagado en [0,1]. */
    private var fade = 0.0
    private var fadeTarget = 0.0
    private var fadeStep = 1.0

    /** Está en reposo y su estado ya se limpió: se puede saltar el módulo entero. */
    private var wetIdle = true

    /**
     * ÚNICO punto de asignación. Lo llama el processor al configurar la pipeline, nunca por buffer.
     */
    fun configure(sampleRate: Int, channelCount: Int) {
        if (this.sampleRate == sampleRate.toDouble() && channels == channelCount) return
        this.sampleRate = sampleRate.toDouble()
        channels = channelCount

        hpfIn = Biquad(channelCount)
        hpfOut = Biquad(channelCount)

        gainSmoothing = 1.0 - exp(-1.0 / (GAIN_SMOOTHING_SECONDS * this.sampleRate))
        fadeStep = 1.0 / (FADE_SECONDS * this.sampleRate).coerceAtLeast(1.0)

        designFilters()
        // Configurar no es encender: el módulo no debe entrar con un fade en la primera canción.
        targetGain = 10.0.pow(requestedGainDb / 20.0) * WET_CALIBRATION
        fadeTarget = if (enabled) 1.0 else 0.0
        reset()
        paramsDirty = true
    }

    /** Limpia el estado (seek, cambio de pista): los filtros arrastran energía del audio anterior. */
    fun reset() {
        clearFilters()
        // Ni la ganancia ni el fade se rampan a través de un corte: al otro lado del seek el módulo
        // debe sonar ya como estaba, no entrar desvaneciéndose.
        currentGain = targetGain
        fade = fadeTarget
        wetIdle = fade <= 0.0
    }

    /** Procesa un frame INTERCALADO in-place. Latencia cero: no hay nada que drenar ni compensar. */
    fun processFrame(frame: DoubleArray) {
        if (channels <= 0) return
        if (paramsDirty) readParams()
        advanceRamps()

        if (fade <= 0.0 && wetIdle) return   // apagado y en reposo: ni un filtro

        wetIdle = false
        val gain = currentGain * fade
        var ch = 0
        while (ch < channels) {
            frame[ch] += wetSample(frame[ch], ch) * gain
            ch++
        }

        // Terminó de apagarse: se limpia UNA vez y a partir del siguiente frame no cuesta nada. Si
        // el estado sobreviviera, al volver a encender los filtros soltarían la energía vieja
        // congelada dentro, y eso es un transitorio audible bajo el fade-in.
        if (fade <= 0.0) {
            clearFilters()
            wetIdle = true
        }
    }

    private fun wetSample(x: Double, ch: Int): Double {
        val w = hpfIn.process(x, ch)
        // El segundo filtro quita lo que la saturación deja por debajo del corte, donde viven la voz
        // y los instrumentos principales.
        return hpfOut.process(tanh(DRIVE * w) / DRIVE, ch)
    }

    private fun readParams() {
        paramsDirty = false
        targetGain = 10.0.pow(requestedGainDb / 20.0) * WET_CALIBRATION
        fadeTarget = if (enabled) 1.0 else 0.0
    }

    private fun advanceRamps() {
        currentGain += (targetGain - currentGain) * gainSmoothing
        if (fade < fadeTarget) {
            fade = (fade + fadeStep).coerceAtMost(fadeTarget)
        } else if (fade > fadeTarget) {
            fade = (fade - fadeStep).coerceAtLeast(fadeTarget)
        }
    }

    private fun designFilters() {
        if (sampleRate <= 0.0) return
        val fc = HPF_HZ.coerceAtMost(MAX_FC_NYQUIST_RATIO * sampleRate)
        hpfIn.setHighpass(fc, sampleRate, BUTTER_Q)
        hpfOut.setHighpass(fc, sampleRate, BUTTER_Q)
    }

    private fun clearFilters() {
        hpfIn.clearState()
        hpfOut.clearState()
    }

    /**
     * Biquad Direct Form I con estado por canal y anti-denormales.
     *
     * No reutiliza el de `EqualizerAudioProcessor` porque aquel es privado de esa clase y, sobre
     * todo, porque no inyecta el término anti-denormal: allí los filtros se apagan solos cuando la
     * ganancia vuelve a cero, así que nunca decaen en silencio. Aquí sí — este módulo puede quedarse
     * encendido sobre un pasaje callado.
     */
    private class Biquad(channels: Int = 0) {
        private var b0 = 1.0
        private var b1 = 0.0
        private var b2 = 0.0
        private var a1 = 0.0
        private var a2 = 0.0

        private val x1 = DoubleArray(channels)
        private val x2 = DoubleArray(channels)
        private val y1 = DoubleArray(channels)
        private val y2 = DoubleArray(channels)

        fun setHighpass(freq: Double, sampleRate: Double, q: Double) {
            val w0 = 2.0 * PI * freq / sampleRate
            val alpha = sin(w0) / (2.0 * q)
            val cosW = cos(w0)
            val a0 = 1.0 + alpha
            b0 = (1.0 + cosW) / 2.0 / a0
            b1 = -(1.0 + cosW) / a0
            b2 = b0
            a1 = -2.0 * cosW / a0
            a2 = (1.0 - alpha) / a0
        }

        fun process(x: Double, ch: Int): Double {
            if (ch >= x1.size) return x
            val y = b0 * x + b1 * x1[ch] + b2 * x2[ch] - a1 * y1[ch] - a2 * y2[ch] + ANTI_DENORMAL
            x2[ch] = x1[ch]
            x1[ch] = x
            y2[ch] = y1[ch]
            y1[ch] = y
            return y
        }

        fun clearState() {
            x1.fill(0.0); x2.fill(0.0); y1.fill(0.0); y2.fill(0.0)
        }
    }
}
