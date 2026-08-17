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
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Ecualizador gráfico de 5/10 bandas + refuerzo de graves y agudos, como [AudioProcessor] de
 * Media3 y con SALIDA EN FLOAT: acepta PCM de 16 bits (lo que decodifica ExoPlayer para FLAC/MP3
 * 16-bit) o float, procesa los biquads en double y emite `ENCODING_PCM_FLOAT`. Peaking-EQ
 * **matched** ([matchedPeakCoeffs]), uno por banda y canal, en cascada; estado del filtro por
 * canal.
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
 * aparte). Son **peakings anchos** ([BOOST_Q]), no shelves, con el centro ELEGIBLE por el usuario
 * dentro de [BASS_BOOST_FREQ_MIN_HZ]..[BASS_BOOST_FREQ_MAX_HZ] y
 * [TREBLE_BOOST_FREQ_MIN_HZ]..[TREBLE_BOOST_FREQ_MAX_HZ]; los defaults son los valores que se
 * midieron abajo. Arriba eso está medido y es claramente lo correcto: un peaking Q 0.7 en 10 kHz ya cubre
 * de 5 kHz al final del espectro, así que da MÁS brillo que un high shelf de 6 kHz (6–10 kHz:
 * +4.75 contra +4.47) con casi el mismo aire, y deja quieto el 14–20 kHz (+1.58 contra +5.99),
 * que es donde vive el hiss del máster.
 *
 * Abajo la elección es un COMPROMISO, no un óptimo, y conviene saber por qué (27 jul 2026):
 *
 * - En forma pura, un low shelf de 200 Hz cubre mejor el rango de bajo: a igual ganancia nominal
 *   da +5.83 dB en 40–120 Hz contra +4.95 del peaking y +3.72 en 120–250 contra +2.63. El peaking
 *   es un montículo centrado en 80 Hz y deja fuera el cuerpo del bombo y del bajo.
 * - **Pero entrega ~1 dB más de energía total, y eso es lo que decide.** Medido sobre música real
 *   (687 Days, máster a 0.00 dBFS con 36 400 muestras ya pegadas al tope): con el refuerzo a +6
 *   el peaking recorta el 4.93 % de las muestras y el shelf el 6.58 %. Se probó por escucha y el
 *   veredicto fue que el shelf suena PEOR. Con un máster sin headroom, más grave = más clipping,
 *   y el clipping domina sobre cualquier ventaja de forma.
 *
 * Ojo con el error de razonamiento que llevó a probar el shelf: es FALSO que un shelf gaste más
 * headroom que un peaking (a igual ganancia nominal los dos topan en el mismo pico, +6.00 dB).
 * Lo que gasta más es la ENERGÍA que entrega, que no es lo mismo y no se ve mirando el pico de la
 * curva. Si algún día se resuelve el headroom de verdad (limitador con lookahead o atenuación
 * dentro del processor), el shelf de 200 Hz vuelve a ser la forma preferible — y solo entonces.
 *
 * Los refuerzos **NO entran en la compensación**: son deliberadamente ADITIVOS sobre la curva del
 * EQ, porque eso es lo que un usuario espera de un "booster". Por eso la hoja muestra el headroom
 * ([EqCurve.peakGainDb]): con el preset Bass y el refuerzo a +6 el pico real es +11.4 dB, y eso
 * solo cabe por debajo de ~80 % de volumen. Fue exactamente la falta de esa visibilidad —no la
 * forma de los filtros— lo que hizo que los boosts de julio sonaran a ruido: se sumaban al preset
 * hasta +12..14 dB y clipaban.
 *
 * ## La saga del "suena a ruido": CERRADA (27 jul 2026)
 *
 * Esta clase arrastró durante semanas la pregunta de por qué los refuerzos sonaban a ruido. La
 * respuesta resultó ser CUATRO cosas distintas, no una, y por eso ningún arreglo suelto convencía:
 *
 * 1. **Ruido LITERAL**: `rebuildFilters` asignaba en el hilo de audio en cada frame de arrastre
 *    del slider → GC → xruns → crackle. Resuelto (regla 1 de arriba).
 * 2. **Zipper noise**: los coeficientes saltaban de golpe. Resuelto (regla 2).
 * 3. **Clipping invisible**: los refuerzos se suman al preset hasta +12..14 dB y nada lo decía.
 *    Resuelto por la compensación (regla 3) más el indicador de headroom de la hoja.
 * 4. **El MATERIAL**, que no es un defecto de esta clase y no tiene arreglo aquí: un máster
 *    moderno a 0 dBFS recorta con cualquier refuerzo. Medido, con +6 dB a 110 Hz: `687 Days`
 *    (RMS −11.6) recorta el 4.93 % de las muestras, mientras que material con margen normal
 *    —Dream Theater, RMS −16.9— recorta 0.26 % y 0.05 %. El problema era de una canción concreta,
 *    no del ecualizador.
 *
 * Descartado por medición, NO reabrir sin datos nuevos: los coeficientes (se verificaron uno a uno
 * y estaban CORRECTOS — el 29 jul se cambió el DISEÑO a matched, que es otra cosa: no había un
 * error de cálculo sino un límite estructural de la transformada bilineal, ver [matchedPeakCoeffs]),
 * la forma de los filtros (el low shelf se probó y sonó peor,
 * ver abajo), y el realce psicoacústico tipo "Pure Bass+" de ViPER4Android — se implementó
 * completo, se validó numéricamente y el usuario lo rechazó por escucha: es un generador de
 * distorsión armónica, y en música densa ese contenido inventado se oye como suciedad (medido:
 * ensucia 250–800 Hz entre +1.5 y +2.3 dB más que un peaking). Ojo con el origen de ese intento:
 * el usuario recordaba usar V4A a 110 Hz / 6–8 dB, pero con **Natural Bass**, o sea el LINEAL.
 *
 * Lo que SÍ resolvió el problema de diseño real fue hacer ELEGIBLE el centro de cada refuerzo:
 * el debate sobre dónde poner la energía no se gana con una constante mejor, se gana dándole el
 * control a quien escucha.
 *
 * ## Protección de nivel: preamp manual + limitador con lookahead (29 jul 2026)
 *
 * Hasta esta fecha no había ninguna: los picos que la curva empujaba sobre fondo de escala salían
 * >1.0f y se confiaba en que la atenuación DIGITAL del volumen de media (mixer float de
 * AudioFlinger, antes del recorte) los devolviera a rango. Eso es cierto POR CABLE y por debajo
 * del ~77 % de volumen — la ruta del autor de la app— y **falla en silencio en dos casos que
 * cubren a buena parte de los usuarios**: volumen al 100 % (no hay atenuación que aplicar) y
 * Bluetooth con volumen absoluto (AVRCP manda el volumen al auricular y el teléfono transmite a
 * nivel fijo, así que el mixer no atenúa NADA, esté donde esté el control). Ver
 * `AudioRouteMonitor`, que es lo que permite al indicador de headroom dejar de mentir ahí.
 *
 * Hay DOS mecanismos porque responden a criterios distintos y ninguno domina al otro:
 *
 * - **[setPreamp]** — lineal, distorsión cero, sin dinámica. Cuesta volumen SIEMPRE, haga falta
 *   o no. Es la opción del que quiere la cadena estrictamente transparente.
 * - **Limitador** — no cuesta absolutamente nada cuando no engancha (y con el umbral en 0 dBFS y
 *   material normal no engancha nunca), a cambio de meter una no linealidad cuando sí. Reacciona
 *   a la señal real, así que se adapta al material y al hardware sin saber nada del auricular.
 *   Con el umbral por debajo de 0 dBFS deja de ser solo una red y pasa a ser un compresor de
 *   picos, que es otra cosa y se elige a propósito (ver [LIMITER_THRESHOLD_MIN_DB]).
 *
 * **Los dos vienen APAGADOS** (preamp a 0, limitador off). Es deliberado y sigue el criterio de
 * toda la app: informar, no corregir por detrás. El aviso de headroom dice cuándo harían falta y
 * el medidor de reducción dice cuánto actuaría el limitador ANTES de encenderlo, así que el
 * usuario decide con el dato delante en vez de recibir una cadena que ya viene tocada.
 * Consecuencia asumida: quien escuche por Bluetooth y no abra nunca esta pantalla se queda sin
 * red — el aviso está, pero hay que leerlo.
 *
 * El limitador que estuvo APAGADO entre el 20 y el 29 de julio era, de manual, un mal limitador:
 * ataque instantáneo, sin lookahead, sin rampa y release único de 150 ms. Con un bajo de 80 Hz
 * (periodo 12.5 ms) los picos llegaban doce veces más rápido de lo que la envolvente se
 * recuperaba, así que nunca bajaba del umbral y modulaba TODO el espectro al ritmo del bajo. No
 * era un problema de calibrar la constante: con esa estructura, el release ES una máquina de
 * agachar los medios y agudos con el bombo. El de ahora corrige los tres fallos (lookahead con
 * rampa lineal, release en dos etapas, techo a fondo de escala en vez de −0.13 dBFS).
 *
 * NO REINTRODUCIR: el auto-preamp que baja el nivel global por su cuenta (rechazado 2 veces; el
 * preamp de ahora es MANUAL y el valor sugerido se ve antes de aplicarse) ni el ruteo de
 * ReplayGain dentro del processor (RG va por `player.volume` en MusicController, como siempre —
 * o sea DESPUÉS de este limitador, lo cual es inocuo porque RG solo atenúa).
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
class EqualizerAudioProcessor @Inject constructor(
    /**
     * Exciter armónico, insertado entre los refuerzos y el detector del limitador (ver
     * [queueInput]). Vive DENTRO de este processor y no como un `AudioProcessor` aparte por dos
     * motivos: la cadena ya está en `double` (uno propio costaría dos conversiones por bloque) y su
     * salida TIENE que pasar por el limitador, porque añade picos que no existían — por fuera solo
     * podría ir antes del EQ o después del limitador, y las dos posiciones están descartadas.
     */
    private val clarity: com.qhana.siku.player.audio.clarity.Clarity
) : BaseAudioProcessor() {

    companion object {
        /** Frecuencias centrales clásicas de 5 bandas de Android. */
        private val BANDS_5 = floatArrayOf(60f, 230f, 910f, 3_600f, 14_000f)

        /** 10 bandas ISO por octava (EQ gráfico clásico). */
        private val BANDS_10 = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1_000f, 2_000f, 4_000f, 8_000f, 16_000f)

        /**
         * Los dos modos de la interfaz. Estaban escritos como `5` y `10` sueltos en siete sitios
         * (aquí, `MusicPreferences` ×3, `PlaybackViewModel`, `EqCurve` y el chip de la hoja), y de
         * ellos solo uno tenía nombre: el layout de bandas es una propiedad del PROCESSOR, así que
         * es él quien los publica.
         *
         * Son un par CERRADO, no un rango: el modo se elige entre estos dos y cualquier otro valor
         * —una preferencia corrupta, un perfil de una versión futura— cae al de 5 por
         * [bandsFor], que es el default histórico.
         */
        internal const val BANDS_5_COUNT = 5

        /** Tamaño del modo de 10 bandas; lo usa [EqCurve] para deducir el Q. */
        internal const val BANDS_10_COUNT = 10

        /** El otro modo, para cuando hay que tocar los dos (aplanar, migrar una curva). */
        internal fun otherBandCount(count: Int): Int =
            if (count == BANDS_10_COUNT) BANDS_5_COUNT else BANDS_10_COUNT

        /**
         * Lleva a uno de los dos modos válidos cualquier número que venga de FUERA (DataStore, el
         * JSON de un perfil). El `if (x == 10) 10 else 5` estaba copiado en tres sitios de
         * `MusicPreferences`, que es donde entran precisamente los valores en los que no se puede
         * confiar.
         */
        internal fun normalizedBandCount(count: Int): Int =
            if (count == BANDS_10_COUNT) BANDS_10_COUNT else BANDS_5_COUNT

        /** Layout de bandas del modo [count]. Copia defensiva: los arrays maestros son privados. */
        fun bandsFor(count: Int): FloatArray =
            (if (count == BANDS_10_COUNT) BANDS_10 else BANDS_5).copyOf()

        /**
         * Paso de TODOS los sliders de ganancia del EQ, en dB. Vive aquí y no en la hoja porque de
         * él se deriva [GAIN_MATCH_EPSILON_DB], que es lógica y no presentación.
         */
        const val GAIN_STEP_DB = 0.5f

        /**
         * Tolerancia para dar dos curvas por iguales. **Derivada del paso del slider**: medio paso
         * es, por construcción, menos que la diferencia más pequeña que el usuario PUEDE introducir
         * y mucho más que el error de redondeo que dejan la interpolación entre modos y el viaje a
         * DataStore. Así el emparejado con un preset no depende de un epsilon elegido a ojo — si
         * algún día el slider se afina, esto lo sigue solo.
         */
        const val GAIN_MATCH_EPSILON_DB = GAIN_STEP_DB / 2f

        const val MAX_GAIN_DB = 12f

        /** Tope de los refuerzos de graves/agudos. */
        const val MAX_BOOST_DB = 12f

        // Q por modo: con 10 bandas por octava los picos deben ser más angostos para no
        // solaparse (Q≈1.41 es el estándar de EQ gráfico de octava); con 5 bandas, más
        // anchos para cubrir el espectro entre centros.
        //
        // Q_5_BANDS SE QUEDA EN 0.9 (revisado el 29 jul y decidido NO cambiarlo). El diagnóstico
        // de que 0.9 da 1.54 octavas de ancho contra ~1.95 de espaciado entre centros —o sea que
        // quedan valles que la compensación, que corrige EN los centros, no puede tapar— es
        // correcto, y el valor que cerraría el hueco sería Q = 2/3 (BW = (2/ln2)·asinh(1/2Q), y
        // asinh(3/4) = ln 2, o sea 2 octavas exactas). Pero el layout de 5 bandas imita a
        // propósito el clásico de Android/AudioFx, y tocar el Q cambiaría EN SILENCIO el sonido
        // de los 10 presets de fábrica y de las curvas que los usuarios de la app publicada ya
        // tienen guardadas. Cambiar estado ajeno sin avisar no se hace en este proyecto.
        internal const val Q_5_BANDS = 0.9
        internal const val Q_10_BANDS = 1.41

        /**
         * Centros y Q de los refuerzos. 80 Hz concentra el punch donde el auricular sí entrega en
         * vez de gastarlo en subgrave, y 10 kHz da aire quedándose por debajo de la zona de hiss
         * del máster. Q 0.7 = ancho de banda de ~2 octavas, que es lo que hace que se perciba
         * como un tono general y no como una banda del ecualizador.
         *
         * Son los DEFAULT: el centro lo elige el usuario dentro de los rangos de abajo. El de
         * graves quedó en 80 Hz como compromiso frente al low shelf de 200 Hz, que cubre mejor el
         * rango pero entrega ~1 dB más de energía y recorta más sobre másters modernos — ver el
         * kdoc de la clase antes de cambiar el default.
         */
        internal const val BASS_BOOST_FREQ_DEFAULT_HZ = 80.0
        internal const val TREBLE_BOOST_FREQ_DEFAULT_HZ = 10_000.0
        internal const val BOOST_Q = 0.7

        /**
         * Rangos del centro de cada refuerzo. El usuario los elige, así que la app ya no tiene que
         * adivinar DÓNDE poner la energía — que era el fondo de la discusión peaking-vs-shelf.
         *
         * Los topes no son arbitrarios: por debajo de 40 Hz el refuerzo solo gasta headroom en
         * algo que la mayoría de auriculares no entrega, y por encima de 250 Hz deja de ser
         * "graves" y empieza a embarrar los medios. Arriba, 2 kHz es el límite inferior de lo que
         * se percibe como brillo, y 16 kHz ya es la zona de hiss del máster (medido: un realce ahí
         * aporta ruido, no aire).
         */
        internal const val BASS_BOOST_FREQ_MIN_HZ = 40.0
        internal const val BASS_BOOST_FREQ_MAX_HZ = 250.0

        internal const val TREBLE_BOOST_FREQ_MIN_HZ = 2_000.0
        internal const val TREBLE_BOOST_FREQ_MAX_HZ = 16_000.0

        /** Nº de biquads de refuerzo (graves + agudos) al final de la cascada. */
        private const val BOOST_COUNT = 2

        /** Ganancias menores a esto son identidad: el filtro ni se calcula. */
        internal const val IDENTITY_EPSILON_DB = 0.05


        /**
         * Iteraciones del punto fijo de [recomputeCompensation]. Re-medido con los filtros matched
         * ([matchedPeakCoeffs]) sobre los 10 presets de fábrica en ambos modos MÁS casos sintéticos
         * duros (todas las bandas a ±12, curva en V, escalón): 1 iteración deja ≤1.58 dB de error,
         * 2 ≤0.39 y 4 ≤0.049. Sigue sirviendo 4; el cambio de diseño de los filtros no la degradó
         * (con RBJ el peor caso a 4 iteraciones era 0.040 dB).
         *
         * Matiz honesto sobre "converge": el error no baja monótonamente hasta cero, se ASIENTA en
         * un suelo de ~0.04 dB en 4 de los 28 casos y ahí puede subir y bajar unas milésimas entre
         * iteraciones. Ese suelo es [IDENTITY_EPSILON_DB]: una banda cuya ganancia compensada cae
         * por debajo de 0.05 dB se salta entera, así que la curva no se puede corregir por debajo
         * de esa cifra. Pasa IGUAL con los coeficientes RBJ (0.040 dB), o sea que no lo trae el
         * diseño nuevo, y de la 4ª a la 8ª iteración se queda plano: se asienta, no oscila ni
         * diverge.
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

        /**
         * Rango del preamp. Solo NEGATIVO, igual que el "precut" de Rockbox: su trabajo es
         * devolver el headroom que consume la curva, y un preamp positivo no compra nada que no
         * dé ya el volumen del sistema — solo acerca el recorte. La UI sugiere
         * −[EqCurve.peakGainDb], que es la convención de los perfiles de AutoEQ.
         *
         * Es la alternativa LINEAL al limitador: distorsión exactamente cero y sin ninguna
         * dinámica de por medio, a cambio de costar volumen siempre, se necesite o no. El
         * limitador es lo contrario (no cuesta nada cuando no engancha, pero mete una no
         * linealidad). Están los dos porque sirven a criterios distintos y el usuario elige.
         */
        const val PREAMP_MIN_DB = -12f
        const val PREAMP_MAX_DB = 0f

        /**
         * Umbral del limitador en dBFS, ELEGIBLE por el usuario. Cambia lo que el limitador ES:
         *
         * - **0 dB** (default) = fondo de escala. Pura protección: solo toca lo que de verdad
         *   recortaría, y con material que no llega al tope no hace absolutamente nada.
         * - **Por debajo** = compresor de picos. Reduce SOLO lo que asoma por encima del umbral y
         *   deja intacto todo lo que queda debajo, así que no es lo mismo que bajar el nivel con
         *   [setPreamp] — ese desplaza la señal entera.
         *
         * La salvedad honesta: sobre un máster moderno, donde casi todo vive pegado al tope, casi
         * ninguna muestra queda "por debajo" y la distinción se difumina — ahí un umbral bajo sí
         * acaba pareciéndose a una atenuación constante, y el preamp hace ese trabajo mejor porque
         * es lineal. El umbral rinde en material con dinámica de verdad.
         *
         * El umbral SÍ es de true-peak desde el 29 jul: el detector sobremuestrea ×4 y ve los picos
         * ENTRE muestras (ver [TRUE_PEAK_PHASES]), así que 0 dBFS aquí significa 0 dBTP y protege
         * de verdad contra el remuestreo y la cuantización del códec. Antes no era cierto —el
         * limitador solo garantizaba el techo EN las muestras— y este kdoc decía justamente que
         * bajar el umbral "no compra seguridad contra el codec, solo recorta antes". Ya no aplica:
         * lo que compra seguridad es el detector, y viene de serie con el umbral en 0.
         *
         * El AUTOMÁTICO de la hoja es exactamente [LIMITER_THRESHOLD_MAX_DB], o sea 0 dBFS fijo.
         * Estuvo atado a −pico de la curva hasta el 29 jul y eso lo convertía en un compresor que
         * engancha siempre que hay curva: el mismo "corregir por detrás" que este proyecto ya
         * rechazó dos veces en el preamp. NO reintroducirlo.
         */
        const val LIMITER_THRESHOLD_MIN_DB = -12f
        const val LIMITER_THRESHOLD_MAX_DB = 0f

        /**
         * Umbral que de verdad se aplica, dado el modo y la posición del slider manual.
         *
         * Vive aquí —y no en quien lo usa— porque tiene DOS escritores del mismo parámetro del
         * processor: `PlaybackViewModel` (mientras hay UI) y `EqProfileManager` (que aplica un
         * perfil al cambiar la ruta de salida, con la app en segundo plano y sin ViewModel vivo).
         * Dos escritores son tolerables solo mientras escriban EL MISMO valor, y eso únicamente se
         * garantiza si la fórmula existe una sola vez (convención 14).
         */
        fun effectiveLimiterThresholdDb(auto: Boolean, manualDb: Float): Float =
            if (auto) LIMITER_THRESHOLD_MAX_DB else manualDb

        /**
         * Lookahead. Es LA diferencia con el limitador que estuvo apagado desde el 20 jul 2026:
         * la reducción se rampa durante los [LIMITER_LOOKAHEAD_SECONDS] ANTERIORES a que el pico
         * salga, así que la ganancia ya llegó a su destino cuando el pico aparece y no hace falta
         * ningún salto instantáneo. Un escalón de ganancia es multiplicar por una función escalón,
         * o sea distorsión de banda ancha — no un artefacto sutil.
         *
         * 2 ms sobran para un pico de audio y son inaudibles como latencia (~88 frames a 44.1 kHz;
         * no hay vídeo con el que sincronizar). La latencia REAL del processor es un poco mayor,
         * 95 frames / 2.15 ms, porque la línea de retardo suma el retardo de grupo del detector
         * true-peak — pero la ventana de RAMPA sigue siendo estos 2 ms, que es lo que fija
         * `limiterAttackSlew`. Ver [TRUE_PEAK_DELAY_FRAMES].
         */
        private const val LIMITER_LOOKAHEAD_SECONDS = 0.002

        /**
         * Release en DOS etapas, tomando el MÍNIMO de ambas. La rápida devuelve el nivel enseguida
         * tras un transitorio aislado (para no agachar medio compás por un solo golpe); la lenta
         * domina en pasajes fuertes sostenidos, donde la reducción queda casi ESTÁTICA y por tanto
         * inaudible. Como el mínimo manda, el comportamiento sale dependiente del programa sin
         * detectar nada: si la lenta apenas bajó, la rápida gobierna y se recupera ya.
         *
         * El pumping aparece cuando el release es del ORDEN del periodo de la modulación. El
         * limitador viejo tenía release único de 150 ms contra los 12.5 ms de un bajo de 80 Hz:
         * la envolvente nunca bajaba del umbral y modulaba TODO el espectro al ritmo del bajo
         * (medios y agudos agachándose con el bombo). Con 400 ms eso no puede pasar.
         *
         * **OJO — hasta el 29 jul (tarde) esto era MENTIRA y la etapa rápida estaba MUERTA.** El
         * branch de ataque sincronizaba las DOS envolventes (`fast = slow = limiterGain`), así que
         * el release siempre arrancaba con las dos iguales, la lenta se quedaba por debajo desde el
         * primer frame y `min(fast, slow)` era SIEMPRE la lenta: 400 ms de recuperación pase lo que
         * pase, o sea un golpe de caja agachando el espectro casi medio segundo. Medido replicando
         * el algoritmo: con un transitorio aislado, 401.5 ms al 63 % (y el 90 % no llegaba en
         * 800 ms) contra los 51.5 ms de ahora.
         *
         * Lo que lo arregla es que la LENTA no se sincroniza: recibe su propio ataque one-pole con
         * [LIMITER_SLOW_ATTACK_SECONDS] hacia [limiterGain]. Un transitorio breve apenas la mueve
         * → sigue muy por encima → gobierna la rápida → ~50 ms. Limitación sostenida la hace
         * converger hacia la ganancia aplicada → gobierna la lenta → sin pumping. Medido: tras un
         * pasaje denso de 1 s a +6 dB sobre el umbral, la lenta gobierna el 79.5 % de los frames
         * del release y la recuperación al 63 % pasa a 330 ms (con solo la rápida serían 101 ms).
         * La rápida SÍ se sincroniza, y eso es lo que preserva la continuidad: sale del ataque
         * valiendo exactamente [limiterGain], así que el mínimo no puede saltar hacia arriba.
         */
        private const val LIMITER_RELEASE_FAST_SECONDS = 0.050
        private const val LIMITER_RELEASE_SLOW_SECONDS = 0.400

        /**
         * Constante del ataque de la envolvente LENTA (ver [LIMITER_RELEASE_FAST_SECONDS]). Es lo
         * que decide cuánta limitación seguida hace falta para que el release pase de rápido a
         * lento, así que es el mando de la dependencia del programa:
         *
         * - Mucho más corta (≈ el lookahead) y la lenta seguiría a la rápida → todo release lento,
         *   que es exactamente el bug que se acaba de corregir.
         * - Mucho más larga y nunca convergería en pasajes de unos pocos cientos de ms → todo
         *   release rápido, y con eso vuelve el riesgo de pumping en material comprimido.
         *
         * 100 ms = el orden de un tiempo musical corto: un golpe suelto no la mueve, un compás
         * fuerte sí. Medido con las dos condiciones de arriba.
         */
        private const val LIMITER_SLOW_ATTACK_SECONDS = 0.100

        /**
         * Distancia a 1.0 por debajo de la cual el release se da por terminado y la ganancia
         * SNAPEA a la unidad.
         *
         * Los dos releases son one-pole, o sea asintóticos: sin esto `limiterGain` se queda para
         * siempre en 0.9999… y la rama de release corre en cada frame durante el resto de la
         * canción, con el medidor reportando residuos de ~0.0001 dB en vez del 0 exacto que
         * significa "aquí el limitador no está haciendo nada".
         *
         * 1e-5 ≈ 0.00009 dB: cuatro órdenes de magnitud por debajo de la resolución del medidor
         * (0.1 dB) y muy por debajo del LSB de 24 bits, así que el snap es inaudible por
         * construcción.
         */
        private const val LIMITER_GAIN_SNAP_EPSILON = 1e-5

        /**
         * Detector de pico TRUE-PEAK (inter-muestra) al estilo ITU-R BS.1770-4: sobremuestreo 4×
         * SOLO del detector, con un FIR polifásico de [TRUE_PEAK_TAPS] taps repartidos en
         * [TRUE_PEAK_PHASES] fases de [TRUE_PEAK_TAPS_PER_PHASE]. **La ruta de señal no se toca**:
         * lo único que cambia es el número que entra en el máximo deslizante.
         *
         * ¿Por qué hace falta? Porque una señal digital pasa POR ENCIMA de sus propias muestras
         * entre ellas, y un limitador que solo mira las muestras deja escapar esos picos, que
         * recortan aguas abajo — en el remuestreo 44.1→48 kHz del mixer y en la cuantización a
         * int16/int24 del códec Bluetooth, que es precisamente la ruta donde este limitador más
         * importa (volumen absoluto = sin atenuación del mixer que absorba nada). Medido: un seno a
         * 0.24·fs con la fase que pone el pico entre dos muestras marca −0.017 dBFS en las muestras
         * y 0.00 dBFS de verdad; el detector lo estima con 0.046 dB de error, y el peor caso de un
         * barrido de 0.05 a 0.45·fs es 0.108 dB.
         *
         * 12 taps por fase con ventana de HAMMING: medido contra Hann (0.141 dB) y Blackman
         * (0.264 dB) sobre ese mismo barrido. Cada fase se normaliza a suma 1 para que una señal
         * lenta no gane ni pierda nivel al interpolarse. No es un detector de laboratorio: 4× es
         * el mínimo de la norma y subestima décimas de dB en contenido pegado a Nyquist (medido:
         * 0.62 dB con ruido blanco a fondo de escala, que no es música). El pico de MUESTRA sigue
         * entrando en el máximo, así que el techo sobre las muestras se mantiene EXACTO pase lo que
         * pase con la estimación.
         *
         * Coste: 4 fases × 12 taps × 2 canales = 96 MAC por frame (4.2 M MAC/s a 44.1 kHz), del
         * orden de lo que ya cuesta la cascada de 10 biquads. Corre SIEMPRE, también con el
         * limitador apagado, porque el medidor de reducción tiene que seguir diciendo la verdad
         * (ver [gainReductionDb]).
         */
        private const val TRUE_PEAK_PHASES = 4
        private const val TRUE_PEAK_TAPS_PER_PHASE = 12
        private const val TRUE_PEAK_TAPS = TRUE_PEAK_PHASES * TRUE_PEAK_TAPS_PER_PHASE

        /** Muestras que guarda la historia POR CANAL: la ventana duplicada ([truePeakHistory]). */
        private const val TRUE_PEAK_SPAN = 2 * TRUE_PEAK_TAPS_PER_PHASE

        /**
         * CONTABILIDAD DEL RETARDO — es lo que conserva la garantía de la rampa, así que no se
         * toca sin recalcularla.
         *
         * El prototipo del FIR es simétrico alrededor de la muestra sobremuestreada
         * ([TRUE_PEAK_TAPS] − 1)/2 = 23.5, o sea 23.5/4 = 5.875 muestras de ENTRADA de retardo de
         * grupo; y las cuatro fases de un mismo frame estiman instantes que abarcan 0.75 muestras.
         * Consecuencia: un pico que ocurre en el instante τ no acaba de quedar cubierto por el
         * detector hasta el frame ⌈5.875⌉ + 1 = 7 posiciones después.
         *
         * Por eso la línea de retardo pasa a medir `lookahead + 7` frames en vez de `lookahead`,
         * mientras que `attackSlew` sigue valiendo 1/lookahead: entre el último frame que puede
         * detectar un pico y el frame en que ese pico SALE quedan exactamente `lookahead` frames de
         * rampa, que es lo que la pendiente máxima necesita para recorrer todo el rango de la
         * ganancia. Sin alargar el retardo, el detector avisaría 7 frames tarde y la rampa se
         * quedaría corta justo en los picos más rápidos. Coste: 2.15 ms de latencia en vez de 2.00.
         */
        private val TRUE_PEAK_DELAY_FRAMES =
            ceil((TRUE_PEAK_TAPS - 1) / (2.0 * TRUE_PEAK_PHASES)).toInt() + 1

        /**
         * Taps del FIR polifásico, dispuestos como `[m * TRUE_PEAK_PHASES + fase]` para que los
         * cuatro coeficientes de un mismo retardo `m` queden contiguos: así el detector recorre la
         * historia UNA vez y acumula las cuatro fases a la vez, que es la disposición amable con la
         * caché. Se calculan una sola vez al cargar la clase (nunca en el hilo de audio).
         */
        private val TRUE_PEAK_COEFFS = buildTruePeakTaps()

        private fun buildTruePeakTaps(): DoubleArray {
            val center = (TRUE_PEAK_TAPS - 1) / 2.0
            val proto = DoubleArray(TRUE_PEAK_TAPS)
            for (k in 0 until TRUE_PEAK_TAPS) {
                val x = (k - center) / TRUE_PEAK_PHASES
                val sinc = if (abs(x) < SINC_SINGULARITY) 1.0 else sin(PI * x) / (PI * x)
                val t = k / (TRUE_PEAK_TAPS - 1.0)
                val window = HAMMING_A0 - (1.0 - HAMMING_A0) * cos(2.0 * PI * t)
                proto[k] = sinc * window
            }
            // Normalización POR FASE: cada fase es un interpolador por derecho propio y tiene que
            // dar ganancia 1 en continua. Normalizar el prototipo entero dejaría a cada fase con
            // un error de unas décimas de por ciento, que se traduce en un rizado del estimador.
            val out = DoubleArray(TRUE_PEAK_TAPS)
            for (p in 0 until TRUE_PEAK_PHASES) {
                var sum = 0.0
                for (m in 0 until TRUE_PEAK_TAPS_PER_PHASE) sum += proto[m * TRUE_PEAK_PHASES + p]
                for (m in 0 until TRUE_PEAK_TAPS_PER_PHASE) {
                    val i = m * TRUE_PEAK_PHASES + p
                    out[i] = proto[i] / sum
                }
            }
            return out
        }

        /** Coeficiente de la ventana de Hamming (el clásico 0.54/0.46). */
        private const val HAMMING_A0 = 0.54

        /** Bajo esto, `sin(πx)/(πx)` se evalúa como su límite 1 en vez de dividir por ~0. */
        private const val SINC_SINGULARITY = 1e-12

        /**
         * Diferencia relativa por debajo de la cual el suavizado del CENTRO de un refuerzo hace
         * snap. Una parte en 10 000 son ~0.00014 octavas: por debajo de eso el filtro es el mismo
         * a todos los efectos, y seguir iterando solo mantendría vivo el recálculo de coeficientes.
         */
        private const val FREQ_SNAP_EPSILON_RATIO = 0.0001

        /** Nº de coeficientes de un biquad (b0,b1,b2,a1,a2), compartido con [EqCurve]. */
        internal const val COEFFS_PER_FILTER = 5

        /**
         * Peaking-EQ **matched** en forma cerrada (Martin Vicanek, *Matched Second Order Digital
         * Filters*, 2016), escrito en [out] desde [offset]. Sustituyó al peaking del cookbook RBJ
         * el 29 jul 2026. MISMA semántica de parámetros: [gainDb] en el centro, [f0] el centro y
         * [q] el ancho proporcional, con el mismo prototipo analógico que usa RBJ
         *
         *     H(s) = (s² + s·A/Q + 1) / (s² + s/(A·Q) + 1),  A = 10^(gainDb/40), s = j·f/f0
         *
         * y eso último es load-bearing: con otro prototipo (por ejemplo con la ganancia entera en
         * el numerador) la CAMPANA cambiaría de forma a todas las frecuencias y con ella el sonido
         * de todos los presets. Aquí solo cambia el mapeo analógico→digital.
         *
         * ## Qué corrige (y qué NO estaba roto)
         *
         * Los coeficientes RBJ eran CORRECTOS: se verificaron uno a uno en su día y esta
         * sustitución no los desmiente. Lo que se corrige es un límite ESTRUCTURAL de la
         * transformada bilineal: comprime el eje de frecuencia infinito del prototipo analógico
         * dentro de (0, fs/2), así que fuerza 0 dB exactos en Nyquist y aplasta las campanas cuyo
         * centro está cerca. Medido a 44.1 kHz contra el prototipo analógico, en 16–20 kHz:
         *
         * | banda            | error RBJ | error matched |
         * |------------------|-----------|---------------|
         * | 14 kHz, +6 dB    | 3.73 dB   | 0.50 dB       |
         * | 14 kHz, +12 dB   | 7.11 dB   | 0.79 dB       |
         * | 16 kHz, +6 dB    | 3.88 dB   | 0.53 dB       |
         * | 16 kHz, +12 dB   | 7.35 dB   | 0.84 dB       |
         *
         * O sea: la banda de 14 kHz (modo 5) y la de 16 kHz (modo 10) entregaban bastante menos
         * realce del nominal en la parte alta de su campana. En f0 bajas no cambia NADA (medido:
         * ≤0.053 dB de desviación contra RBJ para f0 entre 60 y 1000 Hz, y el matched queda incluso
         * más cerca del analógico: 0.029 dB contra 0.083). Es mejora de fidelidad, no reapertura
         * del debate de julio sobre la forma de los filtros.
         *
         * ## Cómo
         *
         * 1. **Polos** casados: mismo decaimiento y misma frecuencia de oscilación que el resonador
         *    analógico (mapeo de la envolvente de la respuesta impulsional), en vez de bilineal.
         * 2. **Numerador** resuelto en forma cerrada para que la MAGNITUD coincida exactamente con
         *    el prototipo en tres puntos —DC, f0 y Nyquist— usando la identidad de Vicanek
         *    `|B(e^jω)|² = (b0+b1+b2)² − 4(b0b1 + 4b0b2 + b1b2)·Φ + 16·b0·b2·Φ²`, con `Φ = sin²(ω/2)`.
         *    Que valga exactamente [gainDb] en el centro no es un detalle: es lo que la UI promete y
         *    lo que la compensación mide (verificado, error ≤4·10⁻¹⁰ dB en todo el rango de uso).
         * 3. **Los recortes son el RECÍPROCO exacto del realce del mismo módulo.** No es un atajo:
         *    el prototipo RBJ tiene esa simetría por construcción (A → 1/A intercambia numerador y
         *    denominador; verificado a 1e-16 en los propios coeficientes RBJ), así que un realce
         *    seguido de su recorte se cancela. Y además es lo que evita un modo de fallo real: con
         *    el sistema de 3 puntos aplicado DIRECTAMENTE a un recorte profundo cerca de Nyquist no
         *    existe solución real y el diseño degenera en un NULO — medido, un recorte de −14 dB en
         *    16 kHz daba −35 dB en 14 kHz, o sea un agujero en los agudos. Por el recíproco eso no
         *    puede pasar: solo se diseñan realces.
         *
         * Verificado en todo el rango de uso (f0 de 31 Hz a 16 kHz, Q 0.7/0.9/1.41, ganancias de
         * ±0.05 a ±18 dB —el techo de la compensación—, a 44.1/48/96/192 kHz): 0 filtros
         * inestables, 0 recíprocos inestables, |coeficiente| ≤ 3.77 y peor error contra el
         * analógico 1.40 dB (contra 8.05 dB del RBJ), en el extremo f0 = 16 kHz con Q 0.7.
         *
         * Vive en el companion y no duplicada en [EqCurve] a propósito: la matemática de los
         * coeficientes es la MISMA en las dos rutas y tenerla dos veces era la forma más fácil de
         * que divergieran. Lo que sigue duplicado (y debe seguirlo) es el punto fijo y el barrido,
         * que tienen restricciones opuestas — ver el kdoc de [EqCurve].
         */
        internal fun matchedPeakCoeffs(
            gainDb: Double,
            f0: Double,
            q: Double,
            sampleRate: Double,
            out: DoubleArray,
            offset: Int
        ) {
            if (gainDb >= 0.0) {
                matchedBoostCoeffs(gainDb, f0, q, sampleRate, out, offset)
                return
            }
            matchedBoostCoeffs(-gainDb, f0, q, sampleRate, out, offset)
            // Recíproco: numerador y denominador se intercambian y se renormaliza a a0 = 1.
            val inv = 1.0 / out[offset]
            val b1 = out[offset + 1]
            val b2 = out[offset + 2]
            out[offset] = inv
            out[offset + 1] = out[offset + 3] * inv
            out[offset + 2] = out[offset + 4] * inv
            out[offset + 3] = b1 * inv
            out[offset + 4] = b2 * inv
        }

        /** Realce casado ([gainDb] ≥ 0). Ver [matchedPeakCoeffs], que es su único llamador. */
        private fun matchedBoostCoeffs(
            gainDb: Double,
            f0: Double,
            q: Double,
            sampleRate: Double,
            out: DoubleArray,
            offset: Int
        ) {
            val a = 10.0.pow(gainDb / 40.0)
            val w0 = 2.0 * PI * f0 / sampleRate
            // (1) Polos del resonador analógico s² + s/(A·Q) + 1 por su envolvente impulsional.
            val damp = 1.0 / (2.0 * a * q)
            val decay = exp(-damp * w0)
            // La rama cosh es el caso sobreamortiguado (damp > 1, o sea Q·A < 0.5). Con los Q de
            // esta clase y A ≥ 1 no se alcanza, pero un sqrt de negativo aquí sería NaN en el hilo
            // de audio: si algún día se añade una banda con Q < 0.5, esto sigue dando un filtro.
            val a1 = if (damp <= 1.0) {
                -2.0 * decay * cos(sqrt(1.0 - damp * damp) * w0)
            } else {
                -2.0 * decay * cosh(sqrt(damp * damp - 1.0) * w0)
            }
            val a2 = decay * decay
            // (2) |A| en los tres puntos de ajuste, vía la identidad de Vicanek.
            val aDc = 1.0 + a1 + a2
            val aNyq = 1.0 - a1 + a2
            val halfSin = sin(w0 / 2.0)
            val phi0 = halfSin * halfSin
            val aCenter2 =
                aDc * aDc - 4.0 * (a1 + 4.0 * a2 + a1 * a2) * phi0 + 16.0 * a2 * phi0 * phi0
            // |H| del prototipo analógico en Nyquist (Ω = (fs/2)/f0).
            val om = sampleRate / (2.0 * f0)
            val flat = (1.0 - om * om) * (1.0 - om * om)
            val zero = om * a / q
            val pole = om / (a * q)
            val mNyq = sqrt((flat + zero * zero) / (flat + pole * pole))
            // (3) b0+b1+b2 lo fija DC (|H| = 1), b0-b1+b2 lo fija Nyquist, y el centro cierra el
            // sistema sobre b0·b2 (raíces de x² - S·x + P).
            val bSum = aDc
            val bDiff = mNyq * aNyq
            val b1 = (bSum - bDiff) / 2.0
            val s = (bSum + bDiff) / 2.0
            val center2 = 10.0.pow(gainDb / 10.0) * aCenter2
            val p = (center2 - bSum * bSum + 4.0 * b1 * s * phi0) / (16.0 * phi0 * (phi0 - 1.0))
            var disc = s * s - 4.0 * p
            // Barrido completo del rango de uso: disc nunca sale negativa para un REALCE (mínimo
            // normalizado medido 1.3e-7, en el filtro casi-identidad de 31 Hz). El clamp está para
            // que un redondeo en el borde no propague un NaN por toda la cascada.
            if (disc < 0.0) disc = 0.0
            val b0 = (s + sqrt(disc)) / 2.0
            out[offset] = b0
            out[offset + 1] = b1
            out[offset + 2] = s - b0
            out[offset + 3] = a1
            out[offset + 4] = a2
        }
    }

    /**
     * Frecuencias + ganancias + refuerzos como snapshot ATÓMICO: el hilo de audio lo lee entero.
     *
     * Es `data class` por el [copy]: los setters cambian UN campo y arrastran el resto, y con un
     * constructor posicional cada campo nuevo obliga a tocar los seis setters con el riesgo de
     * olvidar uno en silencio. El `equals`/`hashCode` generados no se usan (comparar `FloatArray`
     * sería por referencia).
     */
    private data class EqConfig(
        val frequencies: FloatArray,
        val gainsDb: FloatArray,
        val bassBoostDb: Float,
        val trebleBoostDb: Float,
        val bassBoostFreq: Double,
        val trebleBoostFreq: Double,
        val preampDb: Float,
        val limiterThresholdDb: Float
    )

    @Volatile
    private var enabled = false

    @Volatile
    private var config = EqConfig(
        BANDS_5, FloatArray(BANDS_5.size), 0f, 0f,
        BASS_BOOST_FREQ_DEFAULT_HZ, TREBLE_BOOST_FREQ_DEFAULT_HZ, 0f, LIMITER_THRESHOLD_MAX_DB
    )

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

    /**
     * Preamp: se suaviza EN dB con el mismo one-pole que las bandas y se convierte a lineal una
     * vez por bloque. Un escalón de ganancia global daría exactamente el mismo zipper noise que se
     * corrigió en las bandas, así que no puede ir sin rampa.
     */
    private var preampTargetDb = 0.0
    private var preampCurrentDb = 0.0

    /** `e^{-jω}` en cada frecuencia central de BANDA, precalculado para el punto fijo. */
    private var centerZRe: DoubleArray = DoubleArray(0)
    private var centerZIm: DoubleArray = DoubleArray(0)

    /** Hay ganancias en tránsito hacia su objetivo (evita recalcular coeficientes sin motivo). */
    private var smoothingActive = false

    /**
     * Centro OBJETIVO de cada refuerzo. El centro ACTUAL vive en `filterFreq[bandCount]` y
     * `filterFreq[bandCount + 1]`, y persigue a estos con el mismo one-pole que las ganancias
     * (ver [advanceBoostFreq]): sin eso, arrastrar el slider del centro escribía coeficientes
     * nuevos sobre el estado viejo del biquad en cada frame, por escalón — exactamente el zipper
     * noise que la propiedad 2 del kdoc de la clase corrigió para las ganancias, colado por la
     * puerta de al lado.
     *
     * Arrancan en el default y no en 0 para que la primera pasada tenga un valor válido aunque el
     * bloque de `coeffsDirty` no hubiera corrido todavía (0 Hz daría un filtro sin sentido).
     */
    private var bassBoostTargetFreq = BASS_BOOST_FREQ_DEFAULT_HZ
    private var trebleBoostTargetFreq = TREBLE_BOOST_FREQ_DEFAULT_HZ


    /**
     * La curva debe aplicarse DE GOLPE en el siguiente buffer. Lo arma [onFlush]: rampar tiene
     * sentido cuando el usuario mueve un slider, no al configurar la pipeline ni tras un seek —
     * si no, el primer buffer de cada canción saldría plano y el EQ "entraría" con un fade.
     */
    private var snapPending = true

    /**
     * Coeficientes de trabajo (b0,b1,b2,a1,a2): UN array preasignado, no cinco campos. Cambió al
     * compartir el diseño del filtro con [EqCurve] ([matchedPeakCoeffs] escribe en un array con
     * offset, que es la forma que le sirve a las dos rutas). Escribir en un array ya asignado no
     * viola la regla de cero asignación.
     */
    private val scratchCoeffs = DoubleArray(COEFFS_PER_FILTER)

    // --- Limitador con lookahead. Todo preasignado en [ensureFilters], como el resto.
    //
    // La línea de retardo está SIEMPRE activa mientras lo esté el EQ, incluso con el limitador
    // apagado (entonces la ganancia es 1.0 y el retardo solo copia). Así la latencia del processor
    // no depende del toggle, y encenderlo o apagarlo en caliente no produce un salto en el audio
    // — que es lo que pasaría si la cadena cambiara de longitud a mitad de una canción.

    @Volatile
    private var limiterEnabled = false

    /**
     * Umbral en amplitud lineal, derivado del dB del config una vez por bloque (no por muestra:
     * `pow` es caro y el valor solo cambia cuando el usuario mueve el slider).
     */
    private var limiterThreshold = 1.0

    /**
     * Retardo circular de [limiterDelayFrames] frames, intercalado por canal.
     *
     * [limiterDelayFrames] = [limiterLookaheadFrames] + [TRUE_PEAK_DELAY_FRAMES]: la ventana de
     * rampa MÁS el retardo de grupo del detector true-peak. La contabilidad está en el kdoc de
     * [TRUE_PEAK_DELAY_FRAMES] y es lo que sostiene la garantía del techo.
     */
    private var limiterDelay: DoubleArray = DoubleArray(0)
    private var limiterDelayFrames = 0
    private var limiterDelayPos = 0

    /**
     * Frames de la ventana de rampa (lookahead puro, SIN el retardo del detector). De aquí sale
     * [limiterAttackSlew] — y no de [limiterDelayFrames], que es más largo.
     */
    private var limiterLookaheadFrames = 0

    /** Muestras del frame en curso (ya con preamp y biquads), antes de entrar al retardo. */
    private var limiterFrame: DoubleArray = DoubleArray(0)

    /**
     * Historia del detector true-peak: [TRUE_PEAK_TAPS_PER_PHASE] muestras por canal, DUPLICADAS
     * (2·M por canal). Cada muestra se escribe en `pos` y en `pos + M`, de modo que la ventana de
     * las M últimas queda siempre CONTIGUA en `[pos+1, pos+M]` y el FIR la recorre sin un `%` por
     * tap. Ese módulo por tap serían 96 divisiones por frame en el hilo de audio.
     */
    private var truePeakHistory: DoubleArray = DoubleArray(0)
    private var truePeakPos = 0

    /** Acumulador de las [TRUE_PEAK_PHASES] fases del frame en curso. Preasignado y reutilizado. */
    private var truePeakAcc: DoubleArray = DoubleArray(0)

    // Máximo deslizante de la ventana de lookahead por DEQUE MONÓTONO sobre arrays circulares:
    // O(1) amortizado y cero asignaciones. Guarda valor + posición absoluta del frame para poder
    // expirar por ventana. Ver [pushLimiterPeak].
    private var limiterDequeVal: DoubleArray = DoubleArray(0)
    private var limiterDequePos: LongArray = LongArray(0)
    private var limiterDequeHead = 0
    private var limiterDequeTail = 0
    private var limiterDequeCount = 0
    private var limiterFramePos = 0L

    /** Ganancia aplicada y los dos estados de release cuyo mínimo la gobierna (ver constantes). */
    private var limiterGain = 1.0
    private var limiterGainFast = 1.0
    private var limiterGainSlow = 1.0

    /** Derivados del sample rate en [ensureFilters]: pendiente máxima de bajada y releases. */
    private var limiterAttackSlew = 1.0
    private var limiterReleaseFast = 0.0
    private var limiterReleaseSlow = 0.0
    private var limiterSlowAttack = 0.0

    /**
     * Reducción MÁXIMA que el limitador calculó en el último bloque, en dB positivos (0 = no habría
     * tocado nada). La lee la UI para el medidor, y ese medidor es la parte anti-humo del asunto:
     * un procesador que se puede ver NO trabajando es lo contrario de un placebo.
     *
     * Se calcula ESTÉ O NO aplicándose ([limiterEnabled]): apagado, el número es lo que reduciría.
     * Es lo que convierte el medidor en un diagnóstico útil para decidir si hace falta encenderlo,
     * en vez de en un adorno que solo funciona cuando ya tomaste la decisión.
     *
     * Con el limitador apagado eso vale **mientras alguien esté mirando** ([meterObservers]): la
     * envolvente se sigue calculando siempre —es aritmética por frame—, pero el detector inter-
     * muestra que la alimenta se salta si nadie va a leer el resultado. Sin observadores el valor
     * queda en el último publicado, que es exactamente lo que nadie está mirando; al abrir la hoja
     * vuelve a ser true-peak desde el primer bloque.
     */
    @Volatile
    var gainReductionDb: Float = 0f
        private set

    /**
     * Cuántos consumidores tiene AHORA MISMO el medidor de [gainReductionDb] — en la práctica, 0 o
     * 1: la hoja del ecualizador abierta.
     *
     * Existe porque el detector true-peak no es gratis y con el limitador apagado su único cliente
     * es ese medidor. El FIR polifásico cuesta [TRUE_PEAK_TAPS] MAC por canal y frame (96 en
     * estéreo, ~4,2 M MAC/s a 44,1 kHz), del mismo orden que la cascada entera de biquads: o sea que
     * calcularlo con el limitador apagado y la hoja cerrada venía a DUPLICAR el coste de CPU del DSP
     * propio durante horas de reproducción, para un número que nadie lee. Y es el peor sitio posible
     * para gastar de más, porque con el ecualizador activo el offload al DSP está desactivado y esta
     * cuenta la paga la CPU.
     *
     * Es un CONTADOR y no un booleano por la razón de siempre: dos colectores del mismo flow (una
     * hoja que se recompone mientras otra se va) apagarían el detector al cerrarse el primero.
     */
    private val meterObservers = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Lo setea `MusicPlaybackService` para que reconstruya la pipeline del sink cuando el resultado
     * de [shouldProcess] cambie por un motivo que NO pasa por el toggle ni por un cambio de ruta:
     * abrir o cerrar la hoja del EQ (cruce de [meterObservers] por 0). El sink solo lee
     * [shouldProcess] al configurarse, así que sin este aviso la hoja abierta sobre una curva plana
     * no llegaría a sonar. Se invoca en el hilo del colector del medidor; el servicio reprograma al
     * suyo antes de tocar el player.
     */
    @Volatile
    var onProcessingConditionChanged: (() -> Unit)? = null

    /**
     * Lo llama el flow FRÍO que alimenta el medidor (`PlaybackViewModel.eqGainReductionDb`) al
     * empezar y al terminar de colectarse. Mientras nadie mire y el limitador esté apagado, el
     * detector inter-muestra se salta ([queueInput]).
     *
     * El cruce por 0 avisa a [onProcessingConditionChanged]: "hoja abierta" es uno de los términos
     * de [shouldProcess], así que abrir la primera vista o cerrar la última puede cambiar si el
     * processor transforma o cede el offload.
     */
    fun addMeterObserver() {
        if (meterObservers.getAndIncrement() == 0) onProcessingConditionChanged?.invoke()
    }

    fun removeMeterObserver() {
        val prev = meterObservers.getAndUpdate { if (it > 0) it - 1 else 0 }
        if (prev == 1) onProcessingConditionChanged?.invoke()
    }

    fun isEnabled(): Boolean = enabled

    /** Solo cambia el flag; la pipeline debe reconstruirse (ver kdoc de la clase). */
    fun setEnabled(value: Boolean) {
        enabled = value
    }

    /**
     * ¿La configuración actual altera el audio de forma AUDIBLE, o es un passthrough transparente?
     *
     * Con el toggle encendido pero todo neutro —bandas a 0, sin refuerzos, preamp 0, limitador y
     * Clarity apagados— [queueInput] emite bit-perfect (un preamp de 0 dB es ×1.0 exacto y las
     * bandas por debajo del umbral se saltan): el ÚNICO efecto de tener el processor en la cadena
     * es impedir el audio offload del sink, o sea gastar CPU para no cambiar ni un bit. El servicio
     * usa esto para devolverle el offload al DSP en ese caso (ver [shouldProcess]).
     *
     * El umbral por banda es [IDENTITY_EPSILON_DB], el MISMO por debajo del cual la cascada ya
     * salta la banda por identidad ([recomputeCompensation]): lo que el DSP considera transparente
     * para no gastar un biquad cuenta aquí como transparente para ceder el offload. Los refuerzos y
     * el preamp usan el mismo suelo. El limitador y Clarity cuentan si están encendidos (el primero
     * arrastra además el detector true-peak, que no es gratis).
     */
    fun isAudiblyActive(): Boolean {
        val c = config
        for (g in c.gainsDb) if (abs(g) >= IDENTITY_EPSILON_DB) return true
        if (c.bassBoostDb >= IDENTITY_EPSILON_DB) return true
        if (c.trebleBoostDb >= IDENTITY_EPSILON_DB) return true
        if (abs(c.preampDb) >= IDENTITY_EPSILON_DB) return true
        if (limiterEnabled) return true
        if (clarity.isEnabled() && clarity.getGainDb() >= IDENTITY_EPSILON_DB) return true
        return false
    }

    /**
     * ¿Debe el processor TRANSFORMAR el audio (pipeline float, sin offload) o puede quedarse
     * transparente y cederle el offload al DSP? Es la condición ÚNICA de la que dependen a la vez
     * [onConfigure] (devolver un formato float o NOT_SET) y el modo de offload del sink
     * (`MusicPlaybackService.isOffloadRequested`) — tenerla en un solo sitio es lo que impide que el
     * processor procese mientras el sink cree que hay offload, o al revés.
     *
     * Tres términos:
     *  - [enabled]: el toggle del usuario. Apagado, nunca se procesa y el offload queda libre.
     *  - [isAudiblyActive]: encendido pero neutro no cambia el audio → se cede el offload igual.
     *  - [meterObservers] > 0: la hoja del EQ está abierta. Aunque la curva esté plana se PROCESA,
     *    para que subir un slider desde plano suene EN VIVO; sin esto no se oiría hasta cerrar la
     *    hoja, porque el sink solo consulta esta condición al reconstruirse. El precio es un único
     *    reajuste de pipeline al ABRIR la hoja sobre una curva plana —entrar al modo de edición—,
     *    nunca durante el arrastre (mientras la hoja está abierta la condición ya es `true` y no
     *    vuelve a cambiar).
     */
    fun shouldProcess(): Boolean = enabled && (isAudiblyActive() || meterObservers.get() > 0)

    /** Ganancia en vivo de una banda; audible en el siguiente buffer procesado. */
    fun setBandGain(band: Int, db: Float) {
        val current = config
        if (band !in current.gainsDb.indices) return
        val next = current.gainsDb.copyOf()
        next[band] = db.coerceIn(-MAX_GAIN_DB, MAX_GAIN_DB)
        config = current.copy(gainsDb = next)
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
        config = current.copy(frequencies = frequencies.copyOf(), gainsDb = gains)
        coeffsDirty = true
    }

    fun setBandGains(db: FloatArray) {
        val current = config
        val next = FloatArray(current.frequencies.size) { i ->
            (db.getOrNull(i) ?: 0f).coerceIn(-MAX_GAIN_DB, MAX_GAIN_DB)
        }
        config = current.copy(gainsDb = next)
        coeffsDirty = true
    }

    /** Refuerzo de graves en dB (0..[MAX_BOOST_DB]). Aditivo sobre la curva del EQ. */
    fun setBassBoost(db: Float) {
        val current = config
        val value = db.coerceIn(0f, MAX_BOOST_DB)
        if (value == current.bassBoostDb) return
        config = current.copy(bassBoostDb = value)
        coeffsDirty = true
    }

    /** Refuerzo de agudos en dB (0..[MAX_BOOST_DB]). Aditivo sobre la curva del EQ. */
    fun setTrebleBoost(db: Float) {
        val current = config
        val value = db.coerceIn(0f, MAX_BOOST_DB)
        if (value == current.trebleBoostDb) return
        config = current.copy(trebleBoostDb = value)
        coeffsDirty = true
    }

    /**
     * Centro del refuerzo de graves en Hz. EN VIVO: no cambia el layout de la cascada (los dos
     * biquads de refuerzo siguen ahí), así que no reconstruye nada — solo marca los coeficientes
     * como sucios.
     */
    fun setBassBoostFreq(hz: Double) {
        val current = config
        val value = hz.coerceIn(BASS_BOOST_FREQ_MIN_HZ, BASS_BOOST_FREQ_MAX_HZ)
        if (value == current.bassBoostFreq) return
        config = current.copy(bassBoostFreq = value)
        coeffsDirty = true
    }

    /** Centro del refuerzo de agudos en Hz. EN VIVO, igual que [setBassBoostFreq]. */
    fun setTrebleBoostFreq(hz: Double) {
        val current = config
        val value = hz.coerceIn(TREBLE_BOOST_FREQ_MIN_HZ, TREBLE_BOOST_FREQ_MAX_HZ)
        if (value == current.trebleBoostFreq) return
        config = current.copy(trebleBoostFreq = value)
        coeffsDirty = true
    }

    fun getBassBoost(): Float = config.bassBoostDb

    fun getTrebleBoost(): Float = config.trebleBoostDb


    fun getBassBoostFreq(): Double = config.bassBoostFreq

    fun getTrebleBoostFreq(): Double = config.trebleBoostFreq

    /**
     * Ganancia global PREVIA a los filtros, en dB ([PREAMP_MIN_DB]..[PREAMP_MAX_DB]). EN VIVO.
     *
     * En una cadena lineal da exactamente igual si va antes o después de los biquads —un escalar
     * conmuta con un filtro LTI, y aquí no hay ninguna saturación intermedia que rompa esa
     * equivalencia—, pero va DELANTE por convención: es el mismo "Preamp" que encabeza los
     * perfiles paramétricos de Equalizer APO y AutoEQ, donde significa precisamente esto. El
     * "pre" viene de la era del punto fijo, donde el orden sí decidía dónde desbordaba.
     */
    fun setPreamp(db: Float) {
        val current = config
        val value = db.coerceIn(PREAMP_MIN_DB, PREAMP_MAX_DB)
        if (value == current.preampDb) return
        config = current.copy(preampDb = value)
        coeffsDirty = true
    }

    fun getPreamp(): Float = config.preampDb

    /**
     * Enciende/apaga el limitador SIN reconstruir la pipeline: la línea de retardo sigue activa
     * pase lo que pase (ver los campos del limitador), así que apagarlo solo deja la ganancia
     * clavada en 1.0 y no cambia la latencia.
     */
    fun setLimiterEnabled(value: Boolean) {
        limiterEnabled = value
    }

    fun isLimiterEnabled(): Boolean = limiterEnabled

    /**
     * Umbral del limitador en dBFS ([LIMITER_THRESHOLD_MIN_DB]..[LIMITER_THRESHOLD_MAX_DB]).
     * EN VIVO: no cambia el layout de nada, solo el nivel a partir del cual se calcula la
     * reducción. El salto de objetivo que provoca lo absorben la rampa de ataque y el release,
     * que ya están ahí — no hace falta suavizarlo aparte.
     */
    fun setLimiterThreshold(db: Float) {
        val current = config
        val value = db.coerceIn(LIMITER_THRESHOLD_MIN_DB, LIMITER_THRESHOLD_MAX_DB)
        if (value == current.limiterThresholdDb) return
        config = current.copy(limiterThresholdDb = value)
        coeffsDirty = true
    }

    fun getLimiterThreshold(): Float = config.limiterThresholdDb


    /** Las ganancias PEDIDAS (lo que muestra la UI), no las compensadas que se aplican. */
    fun getBandGains(): FloatArray = config.gainsDb.copyOf()

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        // La transparencia va PRIMERO, antes de mirar el encoding: cuando el processor no debe
        // transformar ([shouldProcess] false — apagado, o encendido pero neutro y con la hoja del
        // EQ cerrada) tiene que ser transparente ante CUALQUIER formato (NOT_SET = inactivo, la
        // pipeline queda idéntica a la stock y el sink puede hacer offload). Al revés —comprobando
        // el encoding antes— un formato que no fuera 16-bit ni float tumbaba el sink con una
        // UnhandledAudioFormatException por un processor que ni siquiera iba a hacer nada; o sea que
        // estar inactivo no bastaba para quitarlo de en medio, que es justo lo único que se le pide.
        if (!shouldProcess()) return AudioProcessor.AudioFormat.NOT_SET
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
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
        //
        // La bandera se limpia ANTES de leer el config, y ese orden es load-bearing: si la UI
        // escribe un config nuevo entre las dos lecturas, vuelve a marcarla y el siguiente buffer
        // recoge el valor. Al revés —snapshot primero y limpiar después— el hilo de audio aplicaba
        // el config VIEJO y borraba la marca del nuevo, así que el último valor de un slider (justo
        // el que queda al soltar el dedo) se podía perder PARA SIEMPRE.
        //
        // La limpieza es CONDICIONAL y eso también es load-bearing: escribir false incondicional
        // dejaba una ventana de una instrucción en la que una marca recién puesta por la UI se
        // pisaba con dirty leído en false — la misma pérdida, por una rendija más chica. Si solo
        // se limpia habiendo leído true, el único caso que borra la marca lee el config DESPUÉS
        // de borrarla, así que cualquier cambio anterior queda capturado en el snapshot y
        // cualquier cambio posterior la vuelve a poner. Sin atómicas: es el patrón
        // read-then-conditional-clear, que con un solo consumidor no pierde avisos.
        val dirty = coeffsDirty
        if (dirty) coeffsDirty = false
        val snapshot = config
        // Devuelve `true` si tuvo que rehacer el layout: ese es el segundo motivo para recalcular, y
        // se COMBINA con la bandera en vez de marcarla (marcarla después de haberla limpiado dejaba
        // un recálculo redundante colgando para el buffer siguiente).
        val layoutChanged = ensureFilters(
            snapshot.frequencies, channels, sampleRate,
            snapshot.bassBoostFreq, snapshot.trebleBoostFreq
        )
        // Idempotente: sale por la primera comparación salvo que cambien la tasa o los canales.
        clarity.configure(inputAudioFormat.sampleRate, channels)
        if (dirty || layoutChanged) {
            // Sin getOrNull: devuelve Float? y eso BOXEA en el hilo de audio.
            val gains = snapshot.gainsDb
            for (i in 0 until bandCount) {
                targetDb[i] = if (i < gains.size) gains[i].toDouble() else 0.0
            }
            targetDb[bandCount] = snapshot.bassBoostDb.toDouble()
            targetDb[bandCount + 1] = snapshot.trebleBoostDb.toDouble()
            // Los CENTROS de los refuerzos se refrescan aquí y no en [ensureFilters]: esa solo
            // corre cuando cambia el layout de bandas, los canales o el sample rate, y el usuario
            // puede mover la frecuencia sin que ninguna de las tres cambie. Aquí solo se fija el
            // OBJETIVO; el valor que leen los coeficientes lo rampa [advanceBoostFreq].
            bassBoostTargetFreq = snapshot.bassBoostFreq
            trebleBoostTargetFreq = snapshot.trebleBoostFreq
            preampTargetDb = snapshot.preampDb.toDouble()
            limiterThreshold = 10.0.pow(snapshot.limiterThresholdDb / 20.0)
            recomputeCompensation()
            smoothingActive = true
        }
        if (snapPending) {
            snapPending = false
            smoothingActive = false
            for (i in currentDb.indices) currentDb[i] = appliedDb[i]
            preampCurrentDb = preampTargetDb
            // Los centros también hacen snap: rampar la frecuencia a través de un configure o un
            // seek tendría el mismo sinsentido que rampar la ganancia (ver [snapPending]).
            filterFreq[bandCount] = bassBoostTargetFreq
            filterFreq[bandCount + 1] = trebleBoostTargetFreq
            updateCoefficients()
        } else if (smoothingActive) {
            advanceSmoothing(frames, sampleRate)
        }

        val output = replaceOutputBuffer(frames * channels * 4)
        // Un preamp de 0 dB da 1.0 EXACTO, y multiplicar por 1.0 no altera ni un bit: con la curva
        // plana la salida sigue siendo bit-perfect (solo retardada por el lookahead).
        val preamp = if (preampCurrentDb == 0.0) 1.0 else 10.0.pow(preampCurrentDb / 20.0)
        val cascade = filters
        val indices = activeIndices
        val count = activeCount
        val frame = limiterFrame
        val delay = limiterDelay
        val delayFrames = limiterDelayFrames
        val limiting = limiterEnabled
        val threshold = limiterThreshold
        // ¿Hace falta el detector inter-muestra en este bloque? Solo si alguien va a usar su
        // resultado: el limitador aplicándose, o el medidor de la hoja del EQ abierto (ver
        // [meterObservers]). Se lee UNA vez por bloque, como el resto de la configuración del
        // limitador, y no por frame.
        //
        // Lo que se salta es el FIR, que es lo caro; la HISTORIA se sigue escribiendo siempre (dos
        // asignaciones a un array por muestra, ruido comparado con 48 MAC por canal). Esa asimetría
        // es deliberada: mantenerla fresca hace que encender el limitador —o abrir la hoja— empiece
        // a estimar con la ventana real desde el primer frame, en vez de arrastrar 12 muestras de
        // historia obsoleta o de ceros justo en el instante en que el usuario pide protección.
        val detectTruePeak = limiting || meterObservers.get() > 0
        var minGain = 1.0

        var f = 0
        while (f < frames) {
            // (1) Preamp + cascada de biquads sobre el frame entero, quedándose con su pico. El
            // pico es COMPARTIDO entre canales a propósito: una ganancia distinta por canal
            // movería la imagen estéreo en cada transitorio.
            var framePeak = 0.0
            var ch = 0
            while (ch < channels) {
                var sample = if (floatInput) {
                    inputBuffer.float.toDouble()
                } else {
                    inputBuffer.short / 32768.0
                }
                sample *= preamp
                for (k in 0 until count) {
                    sample = cascade[indices[k]].process(sample, ch)
                }
                frame[ch] = sample
                ch++
            }

            // (1b) Exciter, DESPUÉS de todo el shaping tonal y ANTES del limitador. El orden no es
            // negociable en ninguno de los dos extremos: delante del EQ, los refuerzos volverían a
            // amplificar los armónicos que Clarity acaba de sintetizar; detrás del limitador, los
            // picos que añade saldrían sin nadie que los atrape. Trabaja sobre el frame ya montado,
            // así que el detector true-peak ve la señal definitiva.
            //
            // Latencia CERO: no lleva línea de retardo, así que su interruptor no cambia la
            // sincronización de nada y no hay que drenarlo al final de la pista.
            clarity.processFrame(frame)

            ch = 0
            while (ch < channels) {
                val sample = frame[ch]
                val mag = if (sample < 0) -sample else sample
                if (mag > framePeak) framePeak = mag
                // Historia del detector, duplicada para que su ventana quede contigua.
                val histBase = ch * TRUE_PEAK_SPAN
                truePeakHistory[histBase + truePeakPos] = sample
                truePeakHistory[histBase + truePeakPos + TRUE_PEAK_TAPS_PER_PHASE] = sample
                ch++
            }
            // (1b) Pico TRUE-PEAK: las cuatro estimaciones inter-muestra de cada canal, contra el
            // pico de muestra. El máximo de los dos es lo que entra en la ventana — así el techo
            // sobre las muestras se sigue cumpliendo exactamente aunque el estimador se quede
            // corto, y de paso el medidor pasa a reflejar el overshoot inter-muestra, que es lo
            // que de verdad recorta aguas abajo. Ver [TRUE_PEAK_PHASES].
            if (detectTruePeak) {
                ch = 0
                while (ch < channels) {
                    val tp = truePeakOf(ch)
                    if (tp > framePeak) framePeak = tp
                    ch++
                }
            }
            truePeakPos++
            if (truePeakPos == TRUE_PEAK_TAPS_PER_PHASE) truePeakPos = 0

            // (2) El pico entra en la ventana ANTES de que su frame salga del retardo: en eso
            // consiste el lookahead. El máximo deslizante mantiene el objetivo bajo mientras el
            // pico siga en vuelo, así que la rampa de (3) dispone de la ventana entera.
            val peakAhead = pushLimiterPeak(framePeak)

            // (3) Objetivo y transición. BAJADA por rampa lineal acotada a [limiterAttackSlew] =
            // 1/lookaheadFrames por frame: como la caída máxima posible es 1.0 (la ganancia vive
            // en (0,1]), eso GARANTIZA llegar al objetivo dentro de la ventana — o sea, techo
            // respetado y sin un solo escalón de ganancia. SUBIDA por los dos releases, el mínimo.
            val target = if (peakAhead > threshold) threshold / peakAhead else 1.0
            if (target < limiterGain) {
                limiterGain -= limiterAttackSlew
                if (limiterGain < target) limiterGain = target
                // La RÁPIDA se sincroniza: eso es lo que hace que el release arranque exactamente
                // donde acabó el ataque (sin ese enganche, el mínimo podría saltar hacia arriba).
                limiterGainFast = limiterGain
                // La LENTA no. Persigue la ganancia aplicada con su propio ataque one-pole, así que
                // un transitorio suelto apenas la mueve (se queda arriba → gobierna la rápida →
                // ~50 ms) y una limitación sostenida la hace converger (→ gobierna la lenta → sin
                // pumping). Sincronizarla, como hacía la primera versión, dejaba la etapa rápida
                // MUERTA: ver [LIMITER_RELEASE_FAST_SECONDS]. Nunca puede adelantar a limiterGain
                // (viene de arriba y el one-pole no sobrepasa), así que el mínimo no la elige aquí.
                limiterGainSlow += (limiterGain - limiterGainSlow) * limiterSlowAttack
            } else if (limiterGain < 1.0) {
                limiterGainFast += (target - limiterGainFast) * limiterReleaseFast
                limiterGainSlow += (target - limiterGainSlow) * limiterReleaseSlow
                limiterGain =
                    if (limiterGainFast < limiterGainSlow) limiterGainFast else limiterGainSlow
                if (limiterGain > target) limiterGain = target
                // Cierre del release: los one-pole son asintóticos y sin esto la ganancia nunca
                // vuelve a 1.0 exacto (ver [LIMITER_GAIN_SNAP_EPSILON]). Las tres a la vez, para
                // que las envolventes no arranquen el siguiente ataque desde un valor distinto
                // del aplicado.
                if (target >= 1.0 && 1.0 - limiterGain < LIMITER_GAIN_SNAP_EPSILON) {
                    limiterGain = 1.0
                    limiterGainFast = 1.0
                    limiterGainSlow = 1.0
                }
            }
            val gain = if (limiting) limiterGain else 1.0
            // Se MIDE `limiterGain` y no `gain`: con el limitador apagado el medidor sigue
            // diciendo cuánto REDUCIRÍA. Sin eso, para saber si te hace falta tendrías que
            // encenderlo, y el dato que informa esa decisión desaparecía justo al plantearla.
            if (limiterGain < minGain) minGain = limiterGain

            // (4) Sale el frame de hace [delayFrames] y el actual ocupa su hueco.
            val base = limiterDelayPos * channels
            ch = 0
            while (ch < channels) {
                output.putFloat((delay[base + ch] * gain).toFloat())
                delay[base + ch] = frame[ch]
                ch++
            }
            limiterDelayPos++
            if (limiterDelayPos == delayFrames) limiterDelayPos = 0
            f++
        }
        // Los buffers de audio vienen alineados a frame. Si alguna vez no lo estuvieran, se
        // descarta la cola en lugar de dejar el buffer a medio consumir — el sink lo interpretaría
        // como que el processor se atascó y volvería a entregar lo mismo indefinidamente.
        inputBuffer.position(inputBuffer.limit())
        gainReductionDb = if (minGain >= 1.0) 0f else (-20.0 * log10(minGain)).toFloat()
        output.flip()
    }

    /**
     * Máximo |interpolado| de las [TRUE_PEAK_PHASES] fases para el canal [ch], o sea el pico
     * INTER-MUESTRA estimado alrededor de este frame. Ver [TRUE_PEAK_PHASES] para el diseño y
     * [TRUE_PEAK_DELAY_FRAMES] para la contabilidad del retardo.
     *
     * Recorre la historia UNA vez acumulando las cuatro fases a la vez (los taps de un mismo
     * retardo están contiguos), y se salta las muestras exactamente nulas: en silencio o al arrancar
     * una pista eso deja el detector en casi nada de trabajo. Sin asignaciones.
     */
    private fun truePeakOf(ch: Int): Double {
        val acc = truePeakAcc
        var p = 0
        while (p < TRUE_PEAK_PHASES) {
            acc[p] = 0.0
            p++
        }
        // La ventana contigua de las M últimas muestras empieza en pos+1 y acaba en pos+M (la más
        // nueva). El tap `m` multiplica la muestra de hace m frames.
        val newest = ch * TRUE_PEAK_SPAN + truePeakPos + TRUE_PEAK_TAPS_PER_PHASE
        var m = 0
        while (m < TRUE_PEAK_TAPS_PER_PHASE) {
            val x = truePeakHistory[newest - m]
            if (x != 0.0) {
                val off = m * TRUE_PEAK_PHASES
                p = 0
                while (p < TRUE_PEAK_PHASES) {
                    acc[p] += TRUE_PEAK_COEFFS[off + p] * x
                    p++
                }
            }
            m++
        }
        var peak = 0.0
        p = 0
        while (p < TRUE_PEAK_PHASES) {
            val v = acc[p]
            val mag = if (v < 0) -v else v
            if (mag > peak) peak = mag
            p++
        }
        return peak
    }

    /**
     * Empuja el pico del frame en curso y devuelve el MÁXIMO de la ventana.
     *
     * Deque monótono decreciente sobre arrays circulares preasignados: cada frame entra y sale como
     * mucho una vez, así que es O(1) amortizado y no asigna nada. Un barrido ingenuo del máximo
     * sería O(ventana) por frame — con 95 frames de ventana, ~8,4 millones de comparaciones por
     * segundo en el hilo de audio.
     *
     * La ventana mide [limiterDelayFrames] (lookahead + retardo del detector) y NO solo el
     * lookahead: tiene que cubrir el frame que está saliendo del retardo, y con el detector de por
     * medio la información sobre ese frame llegó unas posiciones más tarde. Sobra margen a
     * propósito — mantener el máximo unas décimas de milisegundo de más es inocuo, quedarse corto
     * dejaría salir un pico sin gobernar.
     *
     * Expira ANTES de insertar, y eso es load-bearing: así la ventana deja hueco garantizado para
     * el frame nuevo y el deque nunca puede desbordar su capacidad (ventana + 1). Al revés, con
     * la ventana llena, la escritura pisaría la cabeza.
     */
    private fun pushLimiterPeak(peak: Double): Double {
        val cap = limiterDequeVal.size
        val pos = limiterFramePos
        limiterFramePos = pos + 1
        val oldest = pos - limiterDelayFrames
        while (limiterDequeCount > 0 && limiterDequePos[limiterDequeHead] < oldest) {
            limiterDequeHead++
            if (limiterDequeHead == cap) limiterDequeHead = 0
            limiterDequeCount--
        }
        // Todo lo que ya sea menor o igual que el pico nuevo no puede volver a ser máximo: sale.
        while (limiterDequeCount > 0) {
            val tailPrev = if (limiterDequeTail == 0) cap - 1 else limiterDequeTail - 1
            if (limiterDequeVal[tailPrev] > peak) break
            limiterDequeTail = tailPrev
            limiterDequeCount--
        }
        limiterDequeVal[limiterDequeTail] = peak
        limiterDequePos[limiterDequeTail] = pos
        limiterDequeTail++
        if (limiterDequeTail == cap) limiterDequeTail = 0
        limiterDequeCount++
        return limiterDequeVal[limiterDequeHead]
    }

    /**
     * Fin de pista: en la línea de retardo quedan [limiterDelayFrames] frames ya procesados que
     * todavía no han salido. Sin volcarlos aquí, cada canción perdería sus últimos milisegundos —
     * y en reproducción sin huecos eso ES un hueco.
     */
    override fun onQueueEndOfStream() {
        val channels = filterChannels
        val delayFrames = limiterDelayFrames
        if (channels <= 0 || delayFrames <= 0 || limiterDelay.size < delayFrames * channels) return
        val output = replaceOutputBuffer(delayFrames * channels * 4)
        val gain = if (limiterEnabled) limiterGain else 1.0
        var f = 0
        while (f < delayFrames) {
            val base = limiterDelayPos * channels
            var ch = 0
            while (ch < channels) {
                output.putFloat((limiterDelay[base + ch] * gain).toFloat())
                limiterDelay[base + ch] = 0.0
                ch++
            }
            limiterDelayPos++
            if (limiterDelayPos == delayFrames) limiterDelayPos = 0
            f++
        }
        output.flip()
    }

    /** Deja el limitador como recién configurado: sin retardo acumulado y sin reducción. */
    private fun resetLimiterState() {
        limiterDelay.fill(0.0)
        limiterDelayPos = 0
        // La historia del detector también: si no, tras un seek las primeras estimaciones
        // interpolarían entre el audio nuevo y el de antes del salto, y un pico inventado ahí
        // agacharía la ganancia sin que nada lo justifique.
        truePeakHistory.fill(0.0)
        truePeakPos = 0
        limiterDequeHead = 0
        limiterDequeTail = 0
        limiterDequeCount = 0
        limiterFramePos = 0L
        limiterGain = 1.0
        limiterGainFast = 1.0
        limiterGainSlow = 1.0
        gainReductionDb = 0f
    }

    override fun onFlush() {
        // Seek/cambio de tema: el estado del filtro arrastra energía del audio anterior. La curva
        // se resuelve DE GOLPE en el siguiente buffer — no tiene sentido rampar una ganancia a
        // través de un corte, y así la primera muestra tras el seek ya sale con la curva pedida.
        snapPending = true
        smoothingActive = false
        for (filter in filters) filter.clearState()
        // El retardo guarda audio de ANTES del salto: soltarlo tras un seek sonaría como un eco
        // del punto anterior, y su pico ya expirado seguiría gobernando la ganancia.
        resetLimiterState()
        // Clarity arrastra el estado de sus filtros, que traen energía del punto anterior.
        clarity.reset()
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
        limiterDelay = DoubleArray(0)
        limiterFrame = DoubleArray(0)
        limiterDequeVal = DoubleArray(0)
        limiterDequePos = LongArray(0)
        limiterDelayFrames = 0
        limiterLookaheadFrames = 0
        truePeakHistory = DoubleArray(0)
        truePeakAcc = DoubleArray(0)
        resetLimiterState()
        preampCurrentDb = 0.0
        bassBoostTargetFreq = BASS_BOOST_FREQ_DEFAULT_HZ
        trebleBoostTargetFreq = TREBLE_BOOST_FREQ_DEFAULT_HZ
        coeffsDirty = true
    }

    /**
     * ÚNICO punto de asignación del hilo de audio, y solo cuando cambia el layout de bandas, el
     * nº de canales o el sample rate (es decir: al configurar y al alternar 5↔10, no por buffer).
     * Tras un cambio de layout los índices apuntan a frecuencias distintas, así que el estado
     * arranca de cero: arrastrar estado ajeno mete un transitorio peor.
     *
     * Devuelve `true` si rehízo el layout, o sea "hay que recalcular compensación y coeficientes".
     * ANTES marcaba `coeffsDirty` por su cuenta, y eso choca con el orden que necesita [queueInput]
     * (limpiar la bandera antes de leer el config): la marca habría quedado puesta DESPUÉS de la
     * limpieza y habría arrastrado un recálculo redundante al buffer siguiente. Con el Boolean, los
     * dos motivos —config sucio y layout nuevo— se combinan en el sitio y no se pisan.
     */
    private fun ensureFilters(
        frequencies: FloatArray,
        channels: Int,
        sampleRate: Double,
        bassBoostFreq: Double,
        trebleBoostFreq: Double
    ): Boolean {
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
            if (sameLayout) return false
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
        filterQ[n] = BOOST_Q
        filterQ[n + 1] = BOOST_Q
        // Los CENTROS de los refuerzos se SIEMBRAN aquí con el valor del config (los arrays son
        // nuevos y arrancarían en 0.0, que como frecuencia no significa nada y ni siquiera admite
        // el suavizado logarítmico). Un layout nuevo estrena filtros, así que el centro empieza YA
        // en su sitio en vez de rampar desde ninguna parte; a partir de ahí quien lo mueve es
        // [advanceBoostFreq] desde el objetivo del snapshot.
        filterFreq[n] = bassBoostFreq
        filterFreq[n + 1] = trebleBoostFreq

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

        // Limitador: la ventana del lookahead se mide en FRAMES, así que depende del sample rate y
        // hay que redimensionarla aquí (este es el único sitio donde se puede asignar). El deque
        // guarda como mucho un frame por posición de la ventana, más el que entra.
        limiterLookaheadFrames = max(1, (LIMITER_LOOKAHEAD_SECONDS * sampleRate).roundToInt())
        // El retardo TOTAL suma el retardo de grupo del detector true-peak; la rampa sigue midiendo
        // solo el lookahead. Ver la contabilidad en [TRUE_PEAK_DELAY_FRAMES].
        limiterDelayFrames = limiterLookaheadFrames + TRUE_PEAK_DELAY_FRAMES
        limiterDelay = DoubleArray(limiterDelayFrames * channels)
        limiterFrame = DoubleArray(channels)
        limiterDequeVal = DoubleArray(limiterDelayFrames + 1)
        limiterDequePos = LongArray(limiterDelayFrames + 1)
        truePeakHistory = DoubleArray(channels * TRUE_PEAK_SPAN)
        truePeakAcc = DoubleArray(TRUE_PEAK_PHASES)
        resetLimiterState()
        // La ganancia baja como mucho 1/lookahead por frame: recorrer todo su rango (1.0 → 0.0)
        // cuesta exactamente la ventana de rampa, que es lo que queda entre el último frame capaz
        // de detectar un pico y el frame en que ese pico sale. De ahí sale la garantía de no
        // sobrepasar el techo. Los releases avanzan una vez por FRAME (no por muestra intercalada,
        // como el limitador viejo): la ganancia es una sola para todo el frame.
        limiterAttackSlew = 1.0 / limiterLookaheadFrames
        limiterReleaseFast = 1.0 - exp(-1.0 / (LIMITER_RELEASE_FAST_SECONDS * sampleRate))
        limiterReleaseSlow = 1.0 - exp(-1.0 / (LIMITER_RELEASE_SLOW_SECONDS * sampleRate))
        limiterSlowAttack = 1.0 - exp(-1.0 / (LIMITER_SLOW_ATTACK_SECONDS * sampleRate))
        // El layout cambió: hay que recalcular compensación y coeficientes desde cero (lo señala el
        // `true` de vuelta). NO se marca [snapPending] a propósito — un cambio 5↔10 en caliente
        // entra por rampa desde plano (los filtros son nuevos y su estado está limpio, así que es la
        // transición más suave posible). El arranque real ya hace snap por [onFlush], que Media3
        // llama después de configurar.
        smoothingActive = false
        return true
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
            val numRe = scratchCoeffs[0] + scratchCoeffs[1] * zr + scratchCoeffs[2] * z2r
            val numIm = scratchCoeffs[1] * zi + scratchCoeffs[2] * z2i
            val denRe = 1.0 + scratchCoeffs[3] * zr + scratchCoeffs[4] * z2r
            val denIm = scratchCoeffs[3] * zi + scratchCoeffs[4] * z2i
            val numMag2 = numRe * numRe + numIm * numIm
            val denMag2 = denRe * denRe + denIm * denIm
            if (denMag2 > 0.0 && numMag2 > 0.0) totalDb += 10.0 * log10(numMag2 / denMag2)
        }
        return totalDb
    }

    /**
     * Peaking-EQ matched en [scratchCoeffs] (sin asignar objeto). La matemática vive en el
     * companion y la comparte [EqCurve]: ver [matchedPeakCoeffs].
     */
    private fun computePeakCoeffs(gainDb: Double, freq: Double, q: Double) {
        matchedPeakCoeffs(gainDb, freq, q, filterSampleRate, scratchCoeffs, 0)
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
        // El preamp se rampa con el MISMO one-pole: es una ganancia más, y aplicarla de golpe daría
        // el mismo escalón que se corrigió en las bandas (propiedad 2 del kdoc de la clase).
        val preampDiff = preampTargetDb - preampCurrentDb
        if (abs(preampDiff) < GAIN_SNAP_EPSILON_DB) {
            preampCurrentDb = preampTargetDb
        } else {
            preampCurrentDb += preampDiff * alpha
            moving = true
        }
        // Y los CENTROS de los refuerzos, por el mismo motivo: mover el centro cambia los
        // coeficientes exactamente igual que mover la ganancia, y hasta ahora ese slider los
        // escribía por ESCALÓN en cada frame de arrastre sobre el estado viejo del biquad.
        if (advanceBoostFreq(bandCount, bassBoostTargetFreq, alpha)) moving = true
        if (advanceBoostFreq(bandCount + 1, trebleBoostTargetFreq, alpha)) moving = true
        smoothingActive = moving
        updateCoefficients()
    }

    /**
     * Acerca el centro actual del refuerzo [index] a [targetHz] con el mismo one-pole que las
     * ganancias, pero en dominio LOGARÍTMICO de frecuencia: la interpolación es multiplicativa,
     * que es como se percibe el tono y como está trazado el propio slider. En lineal, un salto de
     * 40 a 250 Hz correría al principio y se arrastraría al final.
     *
     * `pow(alpha)` es exactamente `exp(lnActual + alpha·(lnObjetivo − lnActual))` con una sola
     * llamada, y esto corre una vez por BLOQUE, no por muestra. Devuelve si sigue en movimiento.
     */
    private fun advanceBoostFreq(index: Int, targetHz: Double, alpha: Double): Boolean {
        val current = filterFreq[index]
        // Defensivo: 0 (o negativo) no tiene logaritmo y propagaría un NaN por la cascada entera.
        // [ensureFilters] siembra el centro, así que no debería pasar nunca.
        if (current <= 0.0) {
            filterFreq[index] = targetHz
            return false
        }
        val ratio = targetHz / current
        if (abs(ratio - 1.0) < FREQ_SNAP_EPSILON_RATIO) {
            filterFreq[index] = targetHz
            return false
        }
        filterFreq[index] = current * ratio.pow(alpha)
        return true
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
            filters[i].setCoeffs(
                scratchCoeffs[0], scratchCoeffs[1], scratchCoeffs[2],
                scratchCoeffs[3], scratchCoeffs[4]
            )
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
