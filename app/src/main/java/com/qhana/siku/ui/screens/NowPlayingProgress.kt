package com.qhana.siku.ui.screens

import java.util.Locale
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.qhana.siku.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qhana.siku.ui.components.*
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.PI
import kotlin.math.sin

/*
 * Barra de progreso del NowPlaying en sus dos variantes (píldora plana y onda propia, ver
 * Ajustes → Apariencia), el nivel de búfer, los chips de tiempo y el chip de formato con su
 * ficha técnica.
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProgressSlider(
    currentPositionFlow: StateFlow<Long>,
    durationFlow: StateFlow<Long>,
    /** Hasta dónde hay audio cargado: tercer nivel del track, solo visible en streaming. */
    bufferedPositionFlow: StateFlow<Long>,
    /**
     * Si esta canción SE ESTÁ STREAMEANDO. El player reporta `bufferedPosition` para cualquier
     * fuente —con un archivo en disco salta a la duración entera en cuanto abre—, así que sin
     * esta puerta el nivel aparecería también en lo local y lo descargado, donde no informa de
     * nada. Es el ORIGEN lo que decide, no el valor del búfer.
     */
    showBuffer: Boolean,
    onSeek: (Long) -> Unit,
    trackColor: Color,
    inactiveTrackColor: Color,
    textColor: Color,
    format: AudioFormatInfo,
    /** Chip de formato con ficha técnica en vez de solo el contenedor. */
    detailedFormat: Boolean,
    onToggleDetailedFormat: () -> Unit,
    /** Ajustes → Reproducción: track ONDULADO (Expressive) en vez de la píldora plana. */
    wavy: Boolean = false,
    /** Solo para [wavy]: en pausa la onda se APLANA (como el reproductor de Android 16). */
    isPlaying: Boolean = true,
    modifier: Modifier = Modifier
) {
    val currentPosition by currentPositionFlow.collectAsStateWithLifecycle()
    val duration by durationFlow.collectAsStateWithLifecycle()
    val bufferedPosition by bufferedPositionFlow.collectAsStateWithLifecycle()

    var sliderPosition by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    val displayPosition by remember {
        derivedStateOf {
            if (isDragging) sliderPosition
            else if (duration > 0) currentPosition.toFloat() / duration.toFloat() else 0f
        }
    }

    // Fracción del búfer: solo en streaming (ver [showBuffer]) y mientras quede algo por cargar
    // —una vez descargada entera, el nivel lleno de un tercer color sería ruido permanente.
    //
    // La clave `showBuffer` es OBLIGATORIA: es un Boolean plano, no un State, así que el
    // `derivedStateOf` lo CAPTURA y sin re-crearlo se queda con el valor del primer paso. Ese era
    // el bug de "el búfer no desaparece cuando termina la descarga": la canción pasa de STREAMING
    // a DOWNLOADED en cuanto la fila de BD trae el `file://`, showBuffer llega en false... y el
    // derivado seguía leyendo el true de cuando abrió el reproductor.
    val bufferedFraction by remember(showBuffer) {
        derivedStateOf {
            if (!showBuffer || duration <= 0L) 0f
            else (bufferedPosition.toFloat() / duration.toFloat())
                .coerceIn(0f, 1f)
                .takeIf { it < BUFFER_COMPLETE_FRACTION } ?: 0f
        }
    }

    // Progreso hablado. Sin esto el Slider se anuncia en PORCENTAJE ("45 por ciento"), que en una
    // canción no significa nada para quien no ve la pantalla.
    val spokenProgress = stringResource(
        R.string.np_progress_state,
        spokenTime(if (isDragging) (sliderPosition * duration).toLong() else currentPosition),
        spokenTime(duration)
    )

    Column(modifier = modifier.fillMaxWidth()) {
        // Decisión final del usuario tras iterar: barra de 12.dp SIN gap (thumbTrackGapSize
        // del spec descartado — con la canción por terminar el hueco se veía raro) y con el
        // fill en píldora de BORDE REDONDO superpuesta al riel + stop indicator, dibujados a
        // mano en el track. AL ARRASTRAR aparece el handle de barra vertical como indicador.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Slider(
                value = displayPosition,
                onValueChange = { newValue ->
                    isDragging = true
                    sliderPosition = newValue
                },
                onValueChangeFinished = {
                    isDragging = false
                    onSeek((sliderPosition * duration).toLong())
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { stateDescription = spokenProgress },
                thumb = {
                    // "Palo" vertical (handle del spec) + GOTA con el tiempo, ambos solo
                    // mientras se arrastra. La gota es el pin clásico: cuadrado con 3 esquinas
                    // al 50% rotado 45° (la esquina viva apunta al palo), texto contra-rotado.
                    val thumbAlpha by animateFloatAsState(
                        targetValue = if (isDragging) 1f else 0f,
                        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
                        label = "thumbAlpha"
                    )
                    Box(
                        modifier = Modifier.size(width = 5.dp, height = 26.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .graphicsLayer { alpha = thumbAlpha }
                                .background(Color.White, RoundedCornerShape(percent = 50))
                        )
                        // requiredSize + offset: flota sobre el palo sin alterar la medida
                        // del thumb (que posiciona el gap/track del slider).
                        Box(
                            modifier = Modifier
                                .requiredSize(46.dp)
                                .offset(y = (-52).dp)
                                .graphicsLayer {
                                    alpha = thumbAlpha
                                    rotationZ = 45f
                                }
                                .background(
                                    trackColor,
                                    RoundedCornerShape(
                                        topStartPercent = 50, topEndPercent = 50,
                                        bottomEndPercent = 0, bottomStartPercent = 50
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = formatTime((sliderPosition * duration).toLong()),
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = onContainerColor(trackColor),
                                modifier = Modifier.graphicsLayer { rotationZ = -45f }
                            )
                        }
                    }
                },
                track = { sliderState ->
                    val activeColor = trackColor
                    val inactiveColor = inactiveTrackColor.copy(alpha = 0.25f)
                    if (wavy) {
                        WavyTrack(
                            fraction = sliderState.value.coerceIn(0f, 1f),
                            bufferedFraction = bufferedFraction,
                            isPlaying = isPlaying,
                            isDragging = isDragging,
                            activeColor = activeColor,
                            inactiveColor = inactiveColor
                        )
                    } else {
                        // Track dibujado a mano: el fill es una PÍLDORA (borde redondo) superpuesta
                        // al riel inactivo. El Track oficial no superpone segmentos: redondear su
                        // borde interior (trackInsideCornerSize) con gap 0 deja muescas transparentes
                        // donde las curvas del fill y del riel se separan.
                        // Puntito de contraste dentro del fill (espejo del stop indicator del
                        // otro extremo): marca la posición actual aunque no haya thumb visible.
                        val fillDotColor = onContainerColor(activeColor)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                // Mismo alto que la onda: si divergen, alternar el ajuste de
                                // Apariencia movería el bloque de controles.
                                .height(ComponentConfig.ProgressTrackHeight)
                                .clip(RoundedCornerShape(percent = 50))
                                .drawBehind {
                                    val dotRadius = ComponentConfig.ProgressStopIndicatorSize.toPx() / 2f
                                    drawRect(inactiveColor)
                                    // Búfer: entre el riel apagado y el fill, para que se lea como
                                    // "esto ya está cargado" sin competir con el progreso real.
                                    if (bufferedFraction > 0f) {
                                        drawRect(
                                            color = activeColor.copy(alpha = BUFFER_TRACK_ALPHA),
                                            size = Size(size.width * bufferedFraction, size.height)
                                        )
                                    }
                                    // Ancho mínimo = un círculo completo, para que la píldora no se
                                    // deforme al inicio de la canción.
                                    val fillWidth = (size.width * sliderState.value.coerceIn(0f, 1f))
                                        .coerceAtLeast(size.height)
                                    drawRoundRect(
                                        color = activeColor,
                                        size = Size(fillWidth, size.height),
                                        cornerRadius = CornerRadius(size.height / 2)
                                    )
                                    // Punta del fill: inset media altura, misma geometría que el
                                    // stop indicator pero en color de contraste.
                                    drawCircle(
                                        color = fillDotColor,
                                        radius = dotRadius,
                                        center = Offset(fillWidth - size.height / 2, size.height / 2)
                                    )
                                    // Stop indicator del spec: inset media altura, oculto cuando
                                    // el progreso lo alcanza (igual que el oficial).
                                    val indicatorX = size.width - size.height / 2
                                    if (fillWidth < indicatorX) {
                                        drawCircle(
                                            color = activeColor,
                                            radius = dotRadius,
                                            center = Offset(indicatorX, size.height / 2)
                                        )
                                    }
                                }
                        )
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Tiempos a los extremos y FORMATO al medio. Un Box (no una Row con SpaceBetween):
        // así el chip de formato queda centrado con la barra de verdad, sin depender de que
        // los dos tiempos midan lo mismo (no lo hacen: "0:07" vs "12:41").
        Box(modifier = Modifier.fillMaxWidth()) {
            TimeChip(
                text = formatTime(if (isDragging) (sliderPosition * duration).toLong() else currentPosition),
                textColor = textColor,
                modifier = Modifier.align(Alignment.CenterStart)
            )
            FormatChip(
                format = format,
                detailed = detailedFormat,
                onToggleDetailed = onToggleDetailedFormat,
                modifier = Modifier.align(Alignment.Center)
            )
            TimeChip(
                text = formatTime(duration),
                textColor = textColor,
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
    }
}

/**
 * Track ONDULADO del reproductor (Ajustes → Apariencia). Dibujado a mano A PROPÓSITO: el
 * `LinearWavyProgressIndicator` oficial anima el aplanado con `DecreasingAmplitudeAnimationSpec`,
 * una constante INTERNA FIJA (500 ms) que no expone por parámetro — al pausar terminaba
 * siempre después que el resto de las animaciones del reproductor y el desfase se notaba.
 * Aquí la amplitud usa el MISMO spring que el morph del botón play, así todo cierra a la vez.
 *
 * La FASE avanza solo mientras suena y se congela al pausar (un `Animatable` cancelado
 * conserva su valor): sin salto al reanudar y sin gastar frames con el audio detenido.
 * Geometría igual que la píldora plana (alto 12dp, stop indicator) para que alternar el
 * ajuste no mueva el layout. La punta la remata un THUMB redondo permanente (el equivalente
 * al puntito de contraste de la píldora plana, en grande) que viaja montado en la cresta.
 */
@Composable
private fun WavyTrack(
    fraction: Float,
    /** Búfer cargado (0 = no hay nada que contar); se dibuja recto sobre el riel apagado. */
    bufferedFraction: Float,
    isPlaying: Boolean,
    /** El usuario está arrastrando: el track debe seguir al dedo sin interpolar. */
    isDragging: Boolean,
    activeColor: Color,
    inactiveColor: Color
) {
    val amplitudeFraction by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0f,
        // MISMA rigidez que el morph del botón play (ver rememberPlayButtonSpin) para que
        // ambos cierren a la vez; sin rebote, que en la amplitud invertiría la onda.
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "waveAmplitude"
    )
    // Fase en "número de ondas recorridas"; el LaunchedEffect se cancela al pausar y el
    // Animatable se queda donde estaba.
    val phase = remember { Animatable(0f) }
    LaunchedEffect(isPlaying) {
        // UN ciclo por vuelta, en bucle. Antes se animaba a un horizonte lejano (600 ciclos ≈
        // 10 min) en una sola llamada, y al agotarse la onda SE CONGELABA: seguía moviéndose la
        // punta (que depende del progreso) pero no la ondulación, y solo se recuperaba al salir
        // y volver al reproductor, que recomponía el efecto. Con canciones largas —Dream
        // Theater— eso pasaba dentro de la misma pista.
        //
        // El `% 1f` antes de cada tramo mantiene la fase acotada: como entra en un seno, es
        // periódica en 1 vuelta, así que reencuadrarla no produce ningún salto visible y evita
        // que el Float pierda precisión decimal tras horas de reproducción.
        while (isPlaying) {
            phase.snapTo(phase.value % 1f)
            phase.animateTo(
                targetValue = phase.value + 1f,
                animationSpec = tween(
                    durationMillis = WAVE_MS_PER_CYCLE.toInt(),
                    easing = LinearEasing
                )
            )
        }
    }

    // El progreso llega a TIRONES: la posición se refresca una vez por segundo (el bucle de
    // MusicPlayerScreen, deliberadamente lento para no despertar el main thread cada frame).
    // En la píldora plana ese escalón se nota poco, pero aquí estira la onda de golpe y se ve
    // como un tropiezo — más aún porque el ciclo de la onda dura también 1s y el salto caía
    // siempre en la misma fase. Se interpola entre ticks a velocidad constante.
    val smoothFraction = remember { Animatable(fraction) }
    LaunchedEffect(fraction, isPlaying, isDragging) {
        val jump = kotlin.math.abs(fraction - smoothFraction.value)
        // Un seek (o el cambio de canción) NO se interpola: sería un barrido de un segundo
        // recorriendo toda la barra. Tampoco en pausa, donde no hay avance que suavizar, ni
        // arrastrando: ahí el track tiene que ir pegado al dedo y no un segundo por detrás.
        if (!isPlaying || isDragging || jump > PROGRESS_SNAP_THRESHOLD) {
            smoothFraction.snapTo(fraction)
        } else {
            smoothFraction.animateTo(
                targetValue = fraction,
                animationSpec = tween(POSITION_TICK_MS, easing = LinearEasing)
            )
        }
    }
    val drawnFraction = smoothFraction.value

    // Path reutilizado: este bloque se redibuja en cada frame mientras suena.
    val wavePath = remember { Path() }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(ComponentConfig.ProgressTrackHeight)
    ) {
        val centerY = size.height / 2f
        val stroke = ComponentConfig.ProgressWaveStroke.toPx()
        val radius = stroke / 2f
        val thumbRadius = ComponentConfig.ProgressWaveThumbSize.toPx() / 2f
        // El recorrido útil se acorta al RADIO DEL THUMB (no al del trazo): es lo que impide
        // que el círculo se salga del contenedor al principio y al final de la canción.
        val startX = thumbRadius
        val endX = size.width - thumbRadius
        val activeEndX = startX + (endX - startX) * drawnFraction

        // Riel inactivo: recto siempre (solo la parte reproducida ondula, igual que el spec).
        // El gap arranca DESPUÉS del thumb (su radio + un trazo), que es lo que separa el
        // círculo del riel; cuando ya no cabe, el riel simplemente no se dibuja (la canción
        // está por terminar).
        val inactiveStartX = activeEndX + thumbRadius + stroke
        if (inactiveStartX < endX) {
            drawLine(
                color = inactiveColor,
                start = Offset(inactiveStartX, centerY),
                end = Offset(endX, centerY),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
            // Búfer, ENCIMA del riel apagado y recto (ondular también este tramo lo confundiría
            // con lo ya reproducido, que es justo lo que el nivel viene a distinguir).
            val bufferedEndX = startX + (endX - startX) * bufferedFraction
            if (bufferedFraction > 0f && bufferedEndX > inactiveStartX) {
                drawLine(
                    color = activeColor.copy(alpha = BUFFER_TRACK_ALPHA),
                    start = Offset(inactiveStartX, centerY),
                    end = Offset(bufferedEndX, centerY),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round
                )
            }
            // Stop indicator del final (desaparece cuando el progreso lo alcanza).
            drawCircle(color = inactiveColor, radius = radius, center = Offset(endX, centerY))
        }

        // Tramo reproducido: sinusoide muestreada; con amplitud 0 queda una recta perfecta.
        val amplitudePx = ComponentConfig.ProgressWaveAmplitude.toPx() * amplitudeFraction
        val wavelengthPx = ComponentConfig.ProgressWaveLength.toPx()
        val phaseTurns = phase.value
        // Y de la sinusoide en una x dada (x = startX ⇒ solo la fase, así el ARRANQUE también
        // ondula: anclarlo a centerY lo dejaba clavado mientras el resto se movía, además de
        // meter un pico vertical en el primer segmento).
        fun waveY(atX: Float): Float {
            val theta = ((atX - startX) / wavelengthPx + phaseTurns) * FULL_TURN_RADIANS
            return centerY + sin(theta) * amplitudePx
        }
        val step = WAVE_SAMPLE_STEP_PX
        wavePath.reset()
        wavePath.moveTo(startX, waveY(startX))
        var x = startX + step
        while (x < activeEndX) {
            wavePath.lineTo(x, waveY(x))
            x += step
        }
        // Punto final exacto: con el paso de muestreo la punta quedaría corta.
        wavePath.lineTo(activeEndX, waveY(activeEndX))
        drawPath(
            path = wavePath,
            color = activeColor,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // Thumb REDONDO montado en la punta de la onda. Va dibujado acá dentro y no como
        // `thumb` del Slider a propósito: el thumb del Slider se posiciona con el valor CRUDO
        // —que llega a tirones, una vez por segundo— mientras la onda avanza con la fracción
        // interpolada, así que se verían desincronizados. Aquí comparte `activeEndX` y `waveY`
        // con el trazo, de modo que cabalga la cresta en vez de flotar sobre ella.
        drawCircle(
            color = activeColor,
            radius = thumbRadius,
            center = Offset(activeEndX, waveY(activeEndX))
        )
    }
}

// --- Búfer del track (ver [ProgressSlider]) ---

/**
 * Opacidad del nivel de búfer. Se deriva del color ACTIVO y no de un rol propio: así el tramo
 * cargado se lee como "esto ya es tuyo" —el mismo tono, más apagado— en vez de como un tercer
 * elemento compitiendo con el progreso.
 */
private const val BUFFER_TRACK_ALPHA = 0.35f

/**
 * A partir de aquí el búfer se considera COMPLETO y deja de dibujarse. Un archivo en disco
 * reporta la canción entera bufferizada, y pintar siempre la barra llena de un tercer color
 * sería ruido permanente en la biblioteca descargada, que es el caso normal. No es 1.0 exacto
 * porque el player suele quedarse unos milisegundos por debajo del total declarado.
 */
private const val BUFFER_COMPLETE_FRACTION = 0.995f

// --- Constantes de la onda del track (ver [WavyTrack]) ---
/** Cuánto tarda la onda en recorrer un ciclo: marca la velocidad del desplazamiento. */
private const val WAVE_MS_PER_CYCLE = 1000f

/**
 * Periodo con el que la UI refresca la posición de reproducción (el bucle de
 * `MusicPlayerScreen`). La interpolación del track dura exactamente eso: cada tick llega
 * justo cuando el anterior terminó de dibujarse, así el avance se ve continuo.
 */
private const val POSITION_TICK_MS = 1000

/**
 * Salto de progreso (fracción de la barra) por encima del cual NO se interpola. Un tick normal
 * avanza `1s / duración` — con la canción más corta de una biblioteca típica (~1 min) eso es
 * ~0.017, así que 0.05 deja pasar el avance natural y ataja solo los seeks y los cambios de
 * pista, que deben ser instantáneos.
 */
private const val PROGRESS_SNAP_THRESHOLD = 0.05f

/**
 * Paso de muestreo del path en píxeles. La sinusoide se dibuja como polilínea: 2px da una
 * curva suave a cualquier densidad sin inflar el número de segmentos.
 */
private const val WAVE_SAMPLE_STEP_PX = 2f

/** Una vuelta completa en radianes (la fase se mide en ciclos, no en radianes). */
private const val FULL_TURN_RADIANS = 2f * PI.toFloat()
/**
 * Formatos SIN PÉRDIDA que la app puede reportar. `M4A` queda FUERA a propósito: el contenedor
 * MP4 lleva tanto AAC (con pérdida, el caso normal) como ALAC (sin pérdida), y el formato se
 * deduce del MIME/extensión, que no distingue el códec de adentro. Ante la duda, no se promete
 * lossless.
 */
private val LOSSLESS_FORMATS = setOf("FLAC", "WAV", "ALAC", "AIFF", "APE", "WV")

/**
 * Chip de FORMATO del archivo (FLAC / MP3 / …), centrado entre los dos tiempos del slider.
 * Se separó del chip de origen: son dos datos distintos (QUÉ suena vs DE DÓNDE sale) y juntos
 * hacían una pastilla larga. Lleva el acento del álbum (el mismo del track activo de la barra
 * que tiene justo encima) para leerse como parte de ella y no como un tercer tiempo.
 *
 * SOLO TEXTO, sin icono (decisión del usuario): el formato ya se lee en la etiqueta. La calidad
 * se distingue por el peso del contenedor —los formatos sin pérdida ([LOSSLESS_FORMATS]) llevan
 * el acento más marcado— y por la descripción de accesibilidad.
 *
 * **Tocarlo conmuta la ficha técnica** (`FLAC · 16 bit · 44.1 kHz` / `MP3 · 320 kbps · 44.1 kHz`),
 * que es el mismo ajuste persistido del switch de Ajustes → Apariencia. Si no hay ningún dato que
 * añadir ([AudioFormatInfo.hasDetails] falso, p. ej. en streaming, donde no se abre el archivo) el
 * chip NO es tocable: un toggle que no cambia nada se lee como un fallo.
 */
@Composable
private fun FormatChip(
    format: AudioFormatInfo,
    detailed: Boolean,
    onToggleDetailed: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isLossless = format.format.uppercase() in LOSSLESS_FORMATS
    val label = formatLabel(format, detailed)
    val desc = stringResource(
        if (isLossless) R.string.np_format_desc_lossless else R.string.np_format_desc,
        label
    )
    val toggleable = format.hasDetails
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer,
        // Surface con onClick para tener ripple/estado táctil de M3; sin detalles que enseñar se
        // vuelve la Surface informativa de siempre.
        onClick = onToggleDetailed,
        enabled = toggleable,
        modifier = modifier.semantics { contentDescription = desc }
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.5.sp
            ),
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        )
    }
}

/**
 * Etiqueta del chip. En modo detallado se muestra lo que DISTINGUE a cada familia: en un formato
 * sin pérdida el bitrate es una consecuencia del material (y varía por canción), así que manda la
 * profundidad de bits; en uno con pérdida, el bitrate ES la calidad. Cada dato ausente se omite,
 * de modo que el chip nunca enseña un hueco ni un "null".
 */
private fun formatLabel(info: AudioFormatInfo, detailed: Boolean): String {
    if (!detailed || !info.hasDetails) return info.format
    val isLossless = info.format.uppercase() in LOSSLESS_FORMATS
    val parts = mutableListOf(info.format)
    if (isLossless) {
        info.bitsPerSample?.let { parts.add("$it bit") }
    } else {
        info.bitrateKbps?.let { parts.add("$it kbps") }
    }
    info.sampleRateHz?.let { parts.add(formatSampleRate(it)) }
    return parts.joinToString(FORMAT_SEPARATOR)
}

/** 44100 → "44.1 kHz"; 48000 → "48 kHz" (sin decimal inútil). */
private fun formatSampleRate(hz: Int): String {
    val khz = hz / HZ_PER_KHZ.toDouble()
    val text = if (khz % 1.0 == 0.0) khz.toInt().toString() else String.format(Locale.US, "%.1f", khz)
    return "$text kHz"
}

/** Separador de la ficha técnica: punto medio con espacios, como en las fichas de audio. */
private const val FORMAT_SEPARATOR = " · "

private const val HZ_PER_KHZ = 1000

/**
 * Duración EN PALABRAS ("3 minutos 45 segundos") para los lectores de pantalla. `formatTime` da
 * "3:45", que TalkBack lee como dos números sueltos y no como un tiempo. Los minutos se omiten
 * cuando no hay, para no anunciar "0 minutos 12 segundos".
 */
@Composable
private fun spokenTime(millis: Long): String {
    val totalSeconds = (millis / MILLIS_PER_SECOND).coerceAtLeast(0L).toInt()
    val minutes = totalSeconds / SECONDS_PER_MINUTE
    val seconds = totalSeconds % SECONDS_PER_MINUTE
    val secondsText = pluralStringResource(R.plurals.np_spoken_seconds, seconds, seconds)
    if (minutes == 0) return secondsText
    val minutesText = pluralStringResource(R.plurals.np_spoken_minutes, minutes, minutes)
    return "$minutesText $secondsText"
}

private const val MILLIS_PER_SECOND = 1000L
private const val SECONDS_PER_MINUTE = 60

/**
 * Chip de tiempo del slider: pastilla suave derivada del propio color del texto (translúcida),
 * así funciona sobre cualquier punto del gradiente/carátula sin plumbing de haze.
 */
@Composable
private fun TimeChip(text: String, textColor: Color, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}
