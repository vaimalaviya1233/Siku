package com.qhana.siku.player.audio

import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessorChain
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

/**
 * Ecualizador gráfico de 5/10 bandas + refuerzo de graves y agudos, como [AudioProcessor] de
 * Media3 y con SALIDA EN FLOAT: acepta PCM de 16 bits (lo que decodifica ExoPlayer para FLAC/MP3
 * 16-bit) o float, procesa los biquads en double y emite `ENCODING_PCM_FLOAT`. Peaking-EQ del
 * cookbook RBJ, uno por banda y canal, en cascada; estado del filtro por canal.
 *
 * La curva plana (bandas Y refuerzos a cero) es bit-perfect.
 *
 * ## Tres propiedades que NO se pueden romper (análisis medido del 26 jul 2026)
 *
 * 1. **CERO asignación en el hilo de audio.** Los biquads y todos los arrays de trabajo se
 *    asignan UNA vez en [ensureFilters] (solo cuando cambia el layout de bandas, el nº de canales
 *    o el sample rate) y a partir de ahí se MUTAN in-place. La versión anterior reconstruía la
 *    lista de biquads —con 4 `DoubleArray` por banda, un HashMap del `associateBy` y un
 *    `toTypedArray()`— dentro de `queueInput`, y como el slider de la UI llama [setBandGain] en
 *    cada frame de arrastre (60–120/s), eso asignaba ~10 objetos + 40 arrays POR BUFFER en un
 *    hilo de tiempo real: presión de GC → xruns → crackle audible justo mientras movías el
 *    control de graves. Era ruido LITERAL, no la curva. Ojo también con `indices.all{}` (crea un
 *    `IntRange`) y con `getOrNull` (boxea `Float?`): ambos estuvieron en el camino caliente.
 *
 * 2. **Ganancias suavizadas** ([GAIN_SMOOTHING_SECONDS]). Los coeficientes ya no saltan de golpe
 *    al mover un slider: [currentDb] persigue a [appliedDb] con un one-pole por bloque. Sin
 *    esto, un biquad con el estado viejo y coeficientes nuevos suelta un transitorio en cada
 *    cambio, y arrastrando el dedo eso es un tren de transitorios (zipper noise). También hace
 *    que activar/desactivar una banda entre y salga por rampa en vez de por escalón.
 *
 * 3. **Compensación de la interacción entre bandas** ([recomputeCompensation]). Los peakings
 *    vecinos se SUMAN, así que un EQ gráfico ingenuo entrega más de lo que promete: medido, el
 *    preset Bass en 10 bandas pedía +6 dB y daba +8.15 dB, y todas las bandas a [MAX_GAIN_DB]
 *    daban +18.4 dB (×8.3) en vez de +12. Aquí se resuelve por punto fijo: se aplica a cada
 *    banda la ganancia que hace que la CURVA RESULTANTE valga lo pedido en las frecuencias
 *    centrales. Converge a ≤0.04 dB en todos los presets de fábrica con [COMPENSATION_ITERATIONS].
 *    Efecto secundario deseado: recupera headroom (Bass 10b pasa de +8.15 a +6.04 dB de pico,
 *    y todas las bandas a tope de +18.4 a +12), lo que reduce el clipping SIN tocar el nivel
 *    global — que es justo lo que el usuario rechazó dos veces.
 *
 *    Ojo: corrige EN las frecuencias centrales. El ripple entre centros baja (3.58 → 2.89 dB en
 *    Bass 10b) pero no desaparece — es inherente a un gráfico de peakings.
 *
 * ## Refuerzo de graves y agudos
 *
 * Dos biquads extra al final de la cascada, bajo el MISMO toggle que el EQ (no son un módulo
 * aparte). Son **peakings anchos**, no shelves, y esa es la decisión de diseño importante: medido
 * a +6 dB, un low shelf de 120 Hz mete +5.97 dB de media por debajo de 40 Hz —donde unos ATH-M20x
 * no entregan nada— mientras que un peaking en [BASS_BOOST_FREQ_HZ] con [BOOST_Q] mete solo
 * +1.64 dB ahí y AUN ASÍ da más energía en la banda útil de 40–160 Hz (+4.68 contra +4.46). Lo
 * mismo arriba: el high shelf de 10 kHz realza +5.85 dB por encima de 14 kHz (que es donde vive
 * el hiss del máster), contra +1.57 dB del peaking. Mismo punch y mismo aire, bastante menos
 * headroom tirado en lo que no se oye.
 *
 * Los refuerzos **NO entran en la compensación**: son deliberadamente ADITIVOS sobre la curva del
 * EQ, porque eso es lo que un usuario espera de un "booster". Por eso la hoja muestra el headroom
 * ([EqCurve.peakGainDb]): con el preset Bass y el refuerzo a +6 el pico real es +11.4 dB, y eso
 * solo cabe por debajo de ~80 % de volumen. Fue exactamente la falta de esa visibilidad —no la
 * forma de los filtros— lo que hizo que los boosts de julio sonaran a ruido: se sumaban al preset
 * hasta +12..14 dB y clipaban.
 *
 * SALIDA SIN LIMITADOR ([LIMITER_ENABLED] = false, veredicto de escucha del usuario,
 * 20 jul 2026): los picos que la curva empuja sobre full scale salen >1.0f y la atenuación
 * DIGITAL del volumen de media (aplicada por pista en el mixer float de AudioFlinger, antes
 * del recorte) los devuelve a rango. El limiter queda como flag por si aparece clipping real.
 *
 * NO REINTRODUCIR: el auto-preamp que baja el nivel global (rechazado 2 veces; la vía elegida es
 * MOSTRAR el headroom, no corregirlo por detrás) ni el ruteo de ReplayGain dentro del processor
 * (RG va por `player.volume` en MusicController, como siempre).
 *
 * INTERACCIÓN CON OFFLOAD: en modo offload TODA la cadena de processors se salta, por eso
 * `MusicPlaybackService` desactiva el offload mientras el EQ está activo, y este processor
 * se declara inactivo ([onConfigure] → NOT_SET) cuando está deshabilitado para no forzar
 * la pipeline float (ni impedir el offload) con el EQ apagado.
 *
 * Cambiar [setEnabled] NO reconfigura la pipeline en caliente: el servicio debe
 * reconstruirla (stop/prepare). Las GANANCIAS sí son en vivo.
 */
@Singleton
class EqualizerAudioProcessor @Inject constructor() : BaseAudioProcessor() {

    companion object {
        /** Frecuencias centrales clásicas de 5 bandas de Android. */
        private val BANDS_5 = floatArrayOf(60f, 230f, 910f, 3_600f, 14_000f)

        /** 10 bandas ISO por octava (EQ gráfico clásico). */
        private val BANDS_10 = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1_000f, 2_000f, 4_000f, 8_000f, 16_000f)

        /** Tamaño del modo de 10 bandas; lo usa [EqCurve] para deducir el Q. */
        internal const val BANDS_10_COUNT = 10

        /** Layout de bandas del modo [count]. Copia defensiva: los arrays maestros son privados. */
        fun bandsFor(count: Int): FloatArray = (if (count == 10) BANDS_10 else BANDS_5).copyOf()

        const val MAX_GAIN_DB = 12f

        /** Tope de los refuerzos de graves/agudos. */
        const val MAX_BOOST_DB = 12f

        // Q por modo: con 10 bandas por octava los picos deben ser más angostos para no
        // solaparse (Q≈1.41 es el estándar de EQ gráfico de octava); con 5 bandas, más
        // anchos para cubrir el espectro entre centros.
        internal const val Q_5_BANDS = 0.9
        internal const val Q_10_BANDS = 1.41

        /**
         * Centros y Q de los refuerzos. Elegidos por medición (ver kdoc de la clase): 80 Hz
         * concentra el punch donde el auricular sí entrega en vez de gastarlo en subgrave, y
         * 10 kHz da aire quedándose por debajo de la zona de hiss del máster. Q 0.7 = ancho de
         * banda de ~2 octavas, que es lo que hace que se perciba como un tono general y no como
         * una banda del ecualizador.
         */
        internal const val BASS_BOOST_FREQ_HZ = 80.0
        internal const val TREBLE_BOOST_FREQ_HZ = 10_000.0
        internal const val BOOST_Q = 0.7

        /** Nº de biquads de refuerzo (graves + agudos) al final de la cascada. */
        private const val BOOST_COUNT = 2

        /** Ganancias menores a esto son identidad: el filtro ni se calcula. */
        internal const val IDENTITY_EPSILON_DB = 0.05

        /**
         * Iteraciones del punto fijo de [recomputeCompensation]. Medido sobre los 10 presets de
         * fábrica en ambos modos MÁS casos sintéticos duros (todas las bandas a ±12, curva en V,
         * escalón): 1 iteración deja ≤1.52 dB de error, 2 ≤0.33 y 4 ≤0.04. La convergencia es
         * MONÓTONA (se verificó hasta 8 iteraciones: no oscila), así que 4 es holgado.
         *
         * El barrido va in-place a propósito — Gauss-Seidel, no Jacobi: cada banda ya ve el valor
         * corregido de sus vecinas de índice menor dentro de la misma pasada. Medido, eso es un
         * orden de magnitud mejor con las mismas iteraciones (0.04 dB contra 0.53 de Jacobi).
         *
         * El coste es despreciable: solo corre cuando la UI cambia una ganancia, no por buffer.
         */
        internal const val COMPENSATION_ITERATIONS = 4

        /**
         * Techo de la ganancia COMPENSADA. Puede (y debe) superar [MAX_GAIN_DB]: para vencer el
         * solapamiento de los vecinos, una curva en V extrema necesita 15.2 dB de banda para
         * entregar 12 dB de curva (ese es el máximo medido sobre todos los presets y los casos
         * sintéticos duros, así que 18 deja margen y nunca se toca en uso normal). Existe solo
         * como tope de seguridad, para no fabricar biquads absurdos ante una curva imposible.
         */
        internal const val COMPENSATION_CEILING_DB = 18.0

        /**
         * Constante de tiempo del suavizado de ganancia. 40 ms es lo bastante lento para que no
         * se oiga el escalón de coeficientes y lo bastante rápido para que el slider siga
         * pareciendo instantáneo al oído.
         */
        private const val GAIN_SMOOTHING_SECONDS = 0.040

        /** Diferencia bajo la cual el suavizado hace snap (y la banda puede volver a identidad). */
        private const val GAIN_SNAP_EPSILON_DB = 0.001

        /** Techo del limitador (bajo 1.0 para margen de redondeo) y release de la envolvente. */
        private const val LIMITER_THRESHOLD = 0.985
        private const val LIMITER_RELEASE_SECONDS = 0.150

        /**
         * DECISIÓN por A/B de escucha (20 jul 2026): limitador APAGADO. Su gain riding
         * (ataque instantáneo + release 150 ms) comprimía audiblemente con cualquier preset
         * no plano (Jazz: "la canción se sentía rara") y en la ruta real del usuario los
         * overs >1.0f los absorbe la atenuación digital del volumen de media. Reactivar
         * (true) SOLO si aparece distorsión áspera real en pasajes fuertes (volumen al
         * máximo o BT con volumen absoluto); la mejora correcta sería un lookahead.
         */
        private const val LIMITER_ENABLED = false
    }

    /**
     * Frecuencias + ganancias + refuerzos como snapshot ATÓMICO: el hilo de audio lo lee entero.
     */
    private class EqConfig(
        val frequencies: FloatArray,
        val gainsDb: FloatArray,
        val bassBoostDb: Float,
        val trebleBoostDb: Float
    )

    @Volatile
    private var enabled = false

    @Volatile
    private var config = EqConfig(BANDS_5, FloatArray(BANDS_5.size), 0f, 0f)

    @Volatile
    private var coeffsDirty = true

    // --- Estado del hilo de audio. Todo preasignado en ensureFilters; NADA se asigna aquí luego.
    //
    // Layout de los arrays de filtro: [0, bandCount) = bandas del EQ (compensadas),
    // [bandCount] = refuerzo de graves, [bandCount + 1] = refuerzo de agudos (sin compensar).

    private var filters: Array<Biquad> = emptyArray()
    private var filterFreq: DoubleArray = DoubleArray(0)
    private var filterQ: DoubleArray = DoubleArray(0)

    /** Índices de los filtros activos + cuántos: evita ramificar por filtro inactivo por muestra. */
    private var activeIndices: IntArray = IntArray(0)
    private var activeCount = 0

    private var bandCount = 0
    private var filterChannels = 0
    private var filterSampleRate = 0.0

    /** Ganancia pedida, la que se aplica de verdad (bandas compensadas) y la suavizada. */
    private var targetDb: DoubleArray = DoubleArray(0)
    private var appliedDb: DoubleArray = DoubleArray(0)
    private var currentDb: DoubleArray = DoubleArray(0)

    /** `e^{-jω}` en cada frecuencia central de BANDA, precalculado para el punto fijo. */
    private var centerZRe: DoubleArray = DoubleArray(0)
    private var centerZIm: DoubleArray = DoubleArray(0)

    /** Hay ganancias en tránsito hacia su objetivo (evita recalcular coeficientes sin motivo). */
    private var smoothingActive = false

    /**
     * La curva debe aplicarse DE GOLPE en el siguiente buffer. Lo arma [onFlush]: rampar tiene
     * sentido cuando el usuario mueve un slider, no al configurar la pipeline ni tras un seek —
     * si no, el primer buffer de cada canción saldría plano y el EQ "entraría" con un fade.
     */
    private var snapPending = true

    // Coeficientes de trabajo del punto fijo: campos en vez de objeto, para no asignar.
    private var scratchB0 = 0.0
    private var scratchB1 = 0.0
    private var scratchB2 = 0.0
    private var scratchA1 = 0.0
    private var scratchA2 = 0.0

    // Limitador: envolvente de pico compartida entre canales (preserva la imagen estéreo) y
    // coeficiente de release por muestra INTERCALADA (se fija en ensureFilters).
    private var limiterEnv = 0.0
    private var limiterReleaseCoeff = 0.9999

    fun isEnabled(): Boolean = enabled

    /** Solo cambia el flag; la pipeline debe reconstruirse (ver kdoc de la clase). */
    fun setEnabled(value: Boolean) {
        enabled = value
    }

    /** Ganancia en vivo de una banda; audible en el siguiente buffer procesado. */
    fun setBandGain(band: Int, db: Float) {
        val current = config
        if (band !in current.gainsDb.indices) return
        val next = current.gainsDb.copyOf()
        next[band] = db.coerceIn(-MAX_GAIN_DB, MAX_GAIN_DB)
        config = EqConfig(current.frequencies, next, current.bassBoostDb, current.trebleBoostDb)
        coeffsDirty = true
    }

    /**
     * Cambia bandas + ganancias en un solo swap (también EN VIVO: el formato de salida no
     * depende del nº de bandas, así que alternar 5↔10 no exige reconstruir la pipeline).
     */
    fun setBands(frequencies: FloatArray, gainsDb: FloatArray) {
        val current = config
        val gains = FloatArray(frequencies.size) { i ->
            (gainsDb.getOrNull(i) ?: 0f).coerceIn(-MAX_GAIN_DB, MAX_GAIN_DB)
        }
        config = EqConfig(
            frequencies.copyOf(), gains, current.bassBoostDb, current.trebleBoostDb
        )
        coeffsDirty = true
    }

    fun setBandGains(db: FloatArray) {
        val current = config
        val next = FloatArray(current.frequencies.size) { i ->
            (db.getOrNull(i) ?: 0f).coerceIn(-MAX_GAIN_DB, MAX_GAIN_DB)
        }
        config = EqConfig(current.frequencies, next, current.bassBoostDb, current.trebleBoostDb)
        coeffsDirty = true
    }

    /** Refuerzo de graves en dB (0..[MAX_BOOST_DB]). Aditivo sobre la curva del EQ. */
    fun setBassBoost(db: Float) {
        val current = config
        val value = db.coerceIn(0f, MAX_BOOST_DB)
        if (value == current.bassBoostDb) return
        config = EqConfig(current.frequencies, current.gainsDb, value, current.trebleBoostDb)
        coeffsDirty = true
    }

    /** Refuerzo de agudos en dB (0..[MAX_BOOST_DB]). Aditivo sobre la curva del EQ. */
    fun setTrebleBoost(db: Float) {
        val current = config
        val value = db.coerceIn(0f, MAX_BOOST_DB)
        if (value == current.trebleBoostDb) return
        config = EqConfig(current.frequencies, current.gainsDb, current.bassBoostDb, value)
        coeffsDirty = true
    }

    fun getBassBoost(): Float = config.bassBoostDb

    fun getTrebleBoost(): Float = config.trebleBoostDb

    /** Las ganancias PEDIDAS (lo que muestra la UI), no las compensadas que se aplican. */
    fun getBandGains(): FloatArray = config.gainsDb.copyOf()

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        if (!enabled) return AudioProcessor.AudioFormat.NOT_SET
        coeffsDirty = true
        return AudioProcessor.AudioFormat(
            inputAudioFormat.sampleRate,
            inputAudioFormat.channelCount,
            C.ENCODING_PCM_FLOAT
        )
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val channels = inputAudioFormat.channelCount
        val sampleRate = inputAudioFormat.sampleRate.toDouble()
        val floatInput = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val bytesPerSample = if (floatInput) 4 else 2

        val samples = inputBuffer.remaining() / bytesPerSample
        val frames = if (channels > 0) samples / channels else 0

        // Orden: (1) layout → (2) compensación de interacción → (3) suavizado → (4) coeficientes.
        val snapshot = config
        ensureFilters(snapshot.frequencies, channels, sampleRate)
        if (coeffsDirty) {
            coeffsDirty = false
            // Sin getOrNull: devuelve Float? y eso BOXEA en el hilo de audio.
            val gains = snapshot.gainsDb
            for (i in 0 until bandCount) {
                targetDb[i] = if (i < gains.size) gains[i].toDouble() else 0.0
            }
            targetDb[bandCount] = snapshot.bassBoostDb.toDouble()
            targetDb[bandCount + 1] = snapshot.trebleBoostDb.toDouble()
            recomputeCompensation()
            smoothingActive = true
        }
        if (snapPending) {
            snapPending = false
            smoothingActive = false
            for (i in currentDb.indices) currentDb[i] = appliedDb[i]
            updateCoefficients()
        } else if (smoothingActive) {
            advanceSmoothing(frames, sampleRate)
        }

        val output = replaceOutputBuffer(samples * 4)
        // Limitador SOLO con filtros activos (y si el experimento lo tiene habilitado);
        // curva plana = bit-perfect.
        val limiting = LIMITER_ENABLED && activeCount > 0
        var env = limiterEnv
        val release = limiterReleaseCoeff
        val cascade = filters
        val indices = activeIndices
        val count = activeCount

        var channel = 0
        while (inputBuffer.remaining() >= bytesPerSample) {
            var sample = if (floatInput) {
                inputBuffer.float.toDouble()
            } else {
                inputBuffer.short / 32768.0
            }
            for (k in 0 until count) {
                sample = cascade[indices[k]].process(sample, channel)
            }
            if (limiting) {
                // Peak limiter: la envolvente sigue el pico (ataque instantáneo) y decae con
                // release exponencial; la ganancia (threshold/env) reduce SOLO mientras la
                // señal filtrada superaría el techo. Gain riding suave, no satura la onda.
                val ax = if (sample < 0) -sample else sample
                env = if (ax > env) ax else env * release
                if (env > LIMITER_THRESHOLD) sample *= LIMITER_THRESHOLD / env
            }
            output.putFloat(sample.toFloat())
            channel++
            if (channel == channels) channel = 0
        }
        limiterEnv = env
        output.flip()
    }

    override fun onFlush() {
        // Seek/cambio de tema: el estado del filtro arrastra energía del audio anterior. La curva
        // se resuelve DE GOLPE en el siguiente buffer — no tiene sentido rampar una ganancia a
        // través de un corte, y así la primera muestra tras el seek ya sale con la curva pedida.
        snapPending = true
        smoothingActive = false
        for (filter in filters) filter.clearState()
        limiterEnv = 0.0
    }

    override fun onReset() {
        filters = emptyArray()
        filterFreq = DoubleArray(0)
        filterQ = DoubleArray(0)
        activeIndices = IntArray(0)
        activeCount = 0
        bandCount = 0
        targetDb = DoubleArray(0)
        appliedDb = DoubleArray(0)
        currentDb = DoubleArray(0)
        centerZRe = DoubleArray(0)
        centerZIm = DoubleArray(0)
        filterChannels = 0
        filterSampleRate = 0.0
        smoothingActive = false
        snapPending = true
        limiterEnv = 0.0
        coeffsDirty = true
    }

    /**
     * ÚNICO punto de asignación del hilo de audio, y solo cuando cambia el layout de bandas, el
     * nº de canales o el sample rate (es decir: al configurar y al alternar 5↔10, no por buffer).
     * Tras un cambio de layout los índices apuntan a frecuencias distintas, así que el estado
     * arranca de cero: arrastrar estado ajeno mete un transitorio peor.
     */
    private fun ensureFilters(frequencies: FloatArray, channels: Int, sampleRate: Double) {
        // Comparación a mano: `indices.all { }` construye un IntRange, y esto se evalúa en CADA
        // buffer (es la guarda que decide si hay que reasignar). `for (i in x.indices)` sí lo
        // compila Kotlin a un bucle indexado sin objeto intermedio.
        if (filterChannels == channels && filterSampleRate == sampleRate &&
            bandCount == frequencies.size
        ) {
            var sameLayout = true
            for (i in frequencies.indices) {
                if (filterFreq[i] != frequencies[i].toDouble()) {
                    sameLayout = false
                    break
                }
            }
            if (sameLayout) return
        }

        val n = frequencies.size
        val total = n + BOOST_COUNT
        bandCount = n
        filterFreq = DoubleArray(total)
        filterQ = DoubleArray(total)
        val q = if (n == BANDS_10_COUNT) Q_10_BANDS else Q_5_BANDS
        for (i in 0 until n) {
            filterFreq[i] = frequencies[i].toDouble()
            filterQ[i] = q
        }
        filterFreq[n] = BASS_BOOST_FREQ_HZ
        filterQ[n] = BOOST_Q
        filterFreq[n + 1] = TREBLE_BOOST_FREQ_HZ
        filterQ[n + 1] = BOOST_Q

        filters = Array(total) { Biquad(channels) }
        activeIndices = IntArray(total)
        activeCount = 0
        targetDb = DoubleArray(total)
        appliedDb = DoubleArray(total)
        currentDb = DoubleArray(total)
        centerZRe = DoubleArray(n)
        centerZIm = DoubleArray(n)
        for (i in 0 until n) {
            // z = e^{-jω} en la frecuencia central i (la compensación se evalúa siempre ahí).
            val w = 2.0 * PI * filterFreq[i] / sampleRate
            centerZRe[i] = cos(w)
            centerZIm[i] = -sin(w)
        }
        filterChannels = channels
        filterSampleRate = sampleRate
        // Release por muestra INTERCALADA (la envolvente avanza una vez por muestra de cada
        // canal → sampleRate * channels pasos por segundo).
        limiterReleaseCoeff = exp(-1.0 / (LIMITER_RELEASE_SECONDS * sampleRate * channels))
        limiterEnv = 0.0
        // El layout cambió: hay que recalcular compensación y coeficientes desde cero. NO se marca
        // [snapPending] a propósito — un cambio 5↔10 en caliente entra por rampa desde plano (los
        // filtros son nuevos y su estado está limpio, así que es la transición más suave posible).
        // El arranque real ya hace snap por [onFlush], que Media3 llama después de configurar.
        coeffsDirty = true
        smoothingActive = false
    }

    /**
     * Resuelve por PUNTO FIJO qué ganancia hay que darle a cada BANDA para que la curva resultante
     * valga [targetDb] en las frecuencias centrales. En cada pasada se mide la respuesta real de
     * la cascada y se corrige cada banda por su propio error; converge rápido porque cada banda
     * domina en su centro. Sin asignaciones (ver [cascadeDbAt]).
     *
     * Los refuerzos de graves/agudos quedan FUERA: son aditivos a propósito, así que pasan tal
     * cual a [appliedDb] (compensarlos los anularía contra las bandas del EQ).
     */
    private fun recomputeCompensation() {
        for (i in 0 until bandCount) appliedDb[i] = targetDb[i]
        repeat(COMPENSATION_ITERATIONS) {
            for (i in 0 until bandCount) {
                val error = targetDb[i] - cascadeDbAt(appliedDb, i)
                appliedDb[i] = (appliedDb[i] + error)
                    .coerceIn(-COMPENSATION_CEILING_DB, COMPENSATION_CEILING_DB)
            }
        }
        appliedDb[bandCount] = targetDb[bandCount]
        appliedDb[bandCount + 1] = targetDb[bandCount + 1]
    }

    /**
     * Respuesta en dB de las BANDAS de [gains] evaluada en la frecuencia central [at]. Las
     * magnitudes se SUMAN en dB (|H₁·H₂| = |H₁|·|H₂|), así que no hace falta aritmética
     * compleja completa: basta |num|²/|den|² por filtro.
     */
    private fun cascadeDbAt(gains: DoubleArray, at: Int): Double {
        val zr = centerZRe[at]
        val zi = centerZIm[at]
        // z² por De Moivre sobre el z ya precalculado.
        val z2r = zr * zr - zi * zi
        val z2i = 2.0 * zr * zi
        var totalDb = 0.0
        for (j in 0 until bandCount) {
            if (abs(gains[j]) < IDENTITY_EPSILON_DB) continue
            computePeakCoeffs(gains[j], filterFreq[j], filterQ[j])
            val numRe = scratchB0 + scratchB1 * zr + scratchB2 * z2r
            val numIm = scratchB1 * zi + scratchB2 * z2i
            val denRe = 1.0 + scratchA1 * zr + scratchA2 * z2r
            val denIm = scratchA1 * zi + scratchA2 * z2i
            val numMag2 = numRe * numRe + numIm * numIm
            val denMag2 = denRe * denRe + denIm * denIm
            if (denMag2 > 0.0 && numMag2 > 0.0) totalDb += 10.0 * log10(numMag2 / denMag2)
        }
        return totalDb
    }

    /** Peaking-EQ RBJ en los campos de scratch (sin asignar objeto). */
    private fun computePeakCoeffs(gainDb: Double, freq: Double, q: Double) {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * freq / filterSampleRate
        val alpha = sin(w0) / (2.0 * q)
        val cosW0 = cos(w0)
        val a0 = 1.0 + alpha / a
        scratchB0 = (1.0 + alpha * a) / a0
        scratchB1 = (-2.0 * cosW0) / a0
        scratchB2 = (1.0 - alpha * a) / a0
        scratchA1 = (-2.0 * cosW0) / a0
        scratchA2 = (1.0 - alpha / a) / a0
    }

    /**
     * Acerca [currentDb] a [appliedDb] con un one-pole por BLOQUE (el coeficiente sale del
     * tamaño real del buffer, así que la constante de tiempo no depende de él). Recalcula
     * coeficientes solo si algo se movió.
     */
    private fun advanceSmoothing(frames: Int, sampleRate: Double) {
        if (frames <= 0) return
        val alpha = 1.0 - exp(-(frames / sampleRate) / GAIN_SMOOTHING_SECONDS)
        var moving = false
        for (i in currentDb.indices) {
            val diff = appliedDb[i] - currentDb[i]
            if (abs(diff) < GAIN_SNAP_EPSILON_DB) {
                currentDb[i] = appliedDb[i]
            } else {
                currentDb[i] += diff * alpha
                moving = true
            }
        }
        smoothingActive = moving
        updateCoefficients()
    }

    /** Vuelca [currentDb] a los biquads y rearma la lista de activos. Sin asignaciones. */
    private fun updateCoefficients() {
        var count = 0
        for (i in filters.indices) {
            val gain = currentDb[i]
            if (abs(gain) < IDENTITY_EPSILON_DB) {
                // Un filtro que vuelve a identidad limpia su estado: si más tarde se reactiva,
                // arrancaría soltando la energía vieja que quedó congelada dentro.
                if (filters[i].active) {
                    filters[i].disable()
                    filters[i].clearState()
                }
                continue
            }
            computePeakCoeffs(gain, filterFreq[i], filterQ[i])
            filters[i].setCoeffs(scratchB0, scratchB1, scratchB2, scratchA1, scratchA2)
            activeIndices[count++] = i
        }
        activeCount = count
    }

    /** Biquad Direct Form I con estado por canal y coeficientes MUTABLES (se reutiliza). */
    private class Biquad(channels: Int) {
        var active = false
            private set

        private var b0 = 1.0
        private var b1 = 0.0
        private var b2 = 0.0
        private var a1 = 0.0
        private var a2 = 0.0

        private val x1 = DoubleArray(channels)
        private val x2 = DoubleArray(channels)
        private val y1 = DoubleArray(channels)
        private val y2 = DoubleArray(channels)

        fun setCoeffs(b0: Double, b1: Double, b2: Double, a1: Double, a2: Double) {
            this.b0 = b0; this.b1 = b1; this.b2 = b2
            this.a1 = a1; this.a2 = a2
            active = true
        }

        fun disable() {
            active = false
        }

        fun process(x: Double, ch: Int): Double {
            if (ch >= x1.size) return x
            val y = b0 * x + b1 * x1[ch] + b2 * x2[ch] - a1 * y1[ch] - a2 * y2[ch]
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

/**
 * Cadena de processors del sink: [EqualizerAudioProcessor] + Sonic (velocidad/pitch, acepta
 * float). NO se usa [androidx.media3.exoplayer.audio.DefaultAudioSink.DefaultAudioProcessorChain]
 * porque mete [SilenceSkippingAudioProcessor] DESPUÉS de los custom y su onConfigure lanza
 * con cualquier input que no sea 16-bit AUNQUE esté desactivado — rompería la pipeline en
 * cuanto el EQ emite float. El skip-silence no se usa en la app.
 */
@UnstableApi
class EqAudioProcessorChain(
    private val equalizer: EqualizerAudioProcessor
) : AudioProcessorChain {

    private val sonic = SonicAudioProcessor()
    private val processors = arrayOf<AudioProcessor>(equalizer, sonic)

    override fun getAudioProcessors(): Array<AudioProcessor> = processors

    override fun applyPlaybackParameters(playbackParameters: PlaybackParameters): PlaybackParameters {
        sonic.setSpeed(playbackParameters.speed)
        sonic.setPitch(playbackParameters.pitch)
        return playbackParameters
    }

    override fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean): Boolean = false

    override fun getMediaDuration(playoutDuration: Long): Long =
        if (sonic.isActive) sonic.getMediaDuration(playoutDuration) else playoutDuration

    override fun getSkippedOutputFrameCount(): Long = 0L
}
