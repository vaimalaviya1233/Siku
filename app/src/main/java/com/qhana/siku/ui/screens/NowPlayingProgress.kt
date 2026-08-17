package com.qhana.siku.ui.screens

import java.util.Locale
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.qhana.siku.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qhana.siku.ui.components.*
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

import com.qhana.siku.ui.theme.appSpatialSpec
import com.qhana.siku.ui.theme.appEffectsSpec

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
    /**
     * Grosor de la barra (Ajustes → Apariencia). Gobierna los DOS modos: de él sale toda la
     * geometría que escala (trazo y amplitud de la onda, indicador, alto del palo, zona táctil),
     * ver [progressMetricsFor].
     */
    trackHeight: Dp = ComponentConfig.ProgressTrackHeight,
    /** Solo para [wavy]: en pausa la onda se APLANA (como el reproductor de Android 16). */
    isPlaying: Boolean = true,
    modifier: Modifier = Modifier
) {
    val metrics = remember(trackHeight) { progressMetricsFor(trackHeight) }
    val currentPosition by currentPositionFlow.collectAsStateWithLifecycle()
    val duration by durationFlow.collectAsStateWithLifecycle()
    val bufferedPosition by bufferedPositionFlow.collectAsStateWithLifecycle()

    var sliderPosition by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    // Ancho medido de la barra. Lo necesita SOLO la gota del tiempo, que se coloca por layout; los
    // tracks lo reciben del tamaño de su propio canvas.
    var trackWidthPx by remember { mutableIntStateOf(0) }
    // El detector de gestos vive mientras la barra exista (su `pointerInput` no se rearma con cada
    // recomposición), así que capturaría la PRIMERA lambda de búsqueda y se quedaría con ella.
    val seek by rememberUpdatedState(onSeek)

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

    // Progreso hablado. Sin esto una barra de rango se anuncia en PORCENTAJE ("45 por ciento"), que
    // en una canción no significa nada para quien no ve la pantalla.
    val spokenProgress = stringResource(
        R.string.np_progress_state,
        spokenTime(if (isDragging) (sliderPosition * duration).toLong() else currentPosition),
        spokenTime(duration)
    )

    // Aparición de la GOTA (el pin con el tiempo) al arrastrar. El THUMB (palo) ya NO depende de
    // esto: va SIEMPRE visible en los dos modos —como el thumb de un Slider de M3, que es la
    // referencia que se está siguiendo—, así que a los tracks se les pasa `handleAlpha = 1f` fijo. La
    // gota sigue siendo la única affordance que aparece solo al buscar: mostrar el tiempo exacto todo
    // el rato sería ruido, el thumb solo marca la posición.
    // Effects: es alpha. Además es la garantía de que no rebota, que sobre una opacidad
    // significaría pasarse de 1 y volver.
    val bubbleAlpha by animateFloatAsState(
        targetValue = if (isDragging) 1f else 0f,
        animationSpec = appEffectsSpec(),
        label = "bubbleAlpha"
    )

    Column(modifier = modifier.fillMaxWidth()) {
        // Barra de grosor ajustable (12dp por defecto) con la geometría del `Slider` de M3: fill y
        // riel separados por un hueco permanente (`ProgressHandleGap`, el `ActiveHandleLeadingSpace`
        // del spec), extremos interiores con `ProgressTrackInsideCorner` y exteriores redondos,
        // stop indicator al final y el handle (thumb) SIEMPRE visible —como en las barras de progreso
        // del spec Expressive—. El hueco estuvo descartado un tiempo —"con la canción por terminar se
        // veía raro"— y volvió porque sin él el fill y el riel se tocaban y la barra se leía como una
        // sola pieza de dos colores.
        //
        // NO usa el `Slider` de M3, y no es reimplementar por gusto: de él solo quedaba el gesto y
        // el layout, porque riel, búfer, fill, indicador, onda y handle ya se dibujaban a mano. Eso
        // dejaba el borde del fill gobernado por DOS sistemas de coordenadas —el thumb lo colocaba
        // el LAYOUT, redondeando a píxel entero, mientras el fill se pintaba en FLOAT— y de ahí
        // salía el píxel de punta que asomaba por detrás del palo. Acá la posición sale de UNA
        // función ([progressHandleCenterX]) que usan el gesto y los dos tracks, así que el desfase
        // no puede existir. Lo que el Slider daba gratis —semántica de rango y acción de progreso—
        // se repone explícito.
        val handleWidth = ComponentConfig.ProgressHandleWidth
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(metrics.touchHeight)
                .semantics {
                    // Sin esto TalkBack no anuncia el control como una barra con rango, y
                    // `setProgress` es lo que permite moverlo sin poder arrastrar. El texto hablado
                    // va en tiempo, no en porcentaje: "45 por ciento" de una canción no dice nada.
                    progressBarRangeInfo = ProgressBarRangeInfo(displayPosition, 0f..1f)
                    stateDescription = spokenProgress
                    setProgress { target ->
                        onSeek((target.coerceIn(0f, 1f) * duration).toLong())
                        true
                    }
                }
                // UN solo detector para toque y arrastre, no `detectTapGestures` +
                // `detectHorizontalDragGestures`. Dos motivos, los dos de comportamiento:
                //  - los detectores de arrastre esperan al TOUCH SLOP, así que un dedo apoyado sin
                //    mover no mostraría el palo ni la gota; acá el pin aparece en el `down`, como en
                //    el Slider oficial.
                //  - un toque es un arrastre de longitud cero, así que tratar los dos por el mismo
                //    camino elimina la posibilidad de que difieran (y de que se peleen por el
                //    evento).
                .pointerInput(duration) {
                    val handleWidthPx = handleWidth.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        isDragging = true
                        sliderPosition =
                            progressFractionForX(down.position.x, size.width.toFloat(), handleWidthPx)
                        var cancelled = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            // El puntero desapareció (otro gesto se lo llevó): no se busca nada.
                            if (change == null) {
                                cancelled = true
                                break
                            }
                            if (change.changedToUpIgnoreConsumed()) {
                                change.consume()
                                break
                            }
                            sliderPosition = progressFractionForX(
                                change.position.x, size.width.toFloat(), handleWidthPx
                            )
                            change.consume()
                        }
                        isDragging = false
                        if (!cancelled) seek((sliderPosition * duration).toLong())
                    }
                }
                // El ancho lo necesita la GOTA, que se posiciona en el layout y no en el canvas.
                .onSizeChanged { trackWidthPx = it.width },
            contentAlignment = Alignment.Center
        ) {
            val activeColor = trackColor
            val inactiveColor = inactiveTrackColor.copy(alpha = ACCENT_TRACK_ALPHA)
            if (wavy) {
                WavyTrack(
                    fraction = displayPosition.coerceIn(0f, 1f),
                    bufferedFraction = bufferedFraction,
                    isPlaying = isPlaying,
                    isDragging = isDragging,
                    // Thumb permanente: el palo no aparece/desaparece, marca la posición siempre.
                    handleAlpha = 1f,
                    metrics = metrics,
                    activeColor = activeColor,
                    inactiveColor = inactiveColor
                )
            } else {
                FlatTrack(
                    fraction = displayPosition.coerceIn(0f, 1f),
                    bufferedFraction = bufferedFraction,
                    handleAlpha = 1f,
                    metrics = metrics,
                    activeColor = activeColor,
                    inactiveColor = inactiveColor
                )
            }

            // GOTA con el tiempo: el pin clásico (cuadrado con 3 esquinas al 50% rotado 45°, la
            // esquina viva apunta al palo, texto contra-rotado). Se posiciona con la MISMA función
            // que el palo, así que no puede quedar desalineada de él.
            //
            // `requiredSize` + `offset`: mide 46dp dentro de una caja de 24 y se sale por arriba a
            // propósito, así que no puede aceptar las constraints del padre.
            if (bubbleAlpha > 0f) {
                val bubbleSize = ComponentConfig.ProgressBubbleSize
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .requiredSize(bubbleSize)
                        .offset {
                            val centerX = progressHandleCenterX(
                                displayPosition, trackWidthPx.toFloat(), handleWidth.toPx()
                            )
                            IntOffset(
                                (centerX - bubbleSize.toPx() / 2f).roundToInt(),
                                -ComponentConfig.ProgressBubbleOffset.roundToPx()
                            )
                        }
                        .graphicsLayer {
                            alpha = bubbleAlpha
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
                        // Mismo criterio de contraste que el palo: la gota es del color del track,
                        // así que hereda su problema con los acentos medios.
                        color = maxContrastOn(trackColor),
                        modifier = Modifier.graphicsLayer { rotationZ = -45f }
                    )
                }
            }
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
 * Vista previa de la barra para Ajustes → Apariencia → Barra de progreso. Dibuja los tracks REALES
 * ([FlatTrack] / [WavyTrack]) con la misma geometría derivada, no una imitación: es la única forma
 * de que lo que se ve al elegir sea lo que se ve al reproducir — un dibujo aparte se desincroniza
 * del de verdad en el primer retoque y nadie se entera.
 *
 * Sin gesto y sin tiempos, pero CON el palo: es permanente en la barra real, así que la vista previa
 * también lo muestra (si no, se elegiría mirando algo distinto de lo que se ve al reproducir). La
 * onda se anima (`isPlaying = true`) porque el movimiento ES la diferencia entre los dos modos.
 */
@Composable
internal fun ProgressBarPreview(
    wavy: Boolean,
    trackHeight: Dp,
    activeColor: Color,
    inactiveColor: Color,
    modifier: Modifier = Modifier
) {
    val metrics = remember(trackHeight) { progressMetricsFor(trackHeight) }
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        if (wavy) {
            WavyTrack(
                fraction = PREVIEW_FRACTION,
                bufferedFraction = 0f,
                isPlaying = true,
                isDragging = false,
                handleAlpha = 1f,
                metrics = metrics,
                activeColor = activeColor,
                inactiveColor = inactiveColor
            )
        } else {
            FlatTrack(
                fraction = PREVIEW_FRACTION,
                bufferedFraction = 0f,
                handleAlpha = 1f,
                metrics = metrics,
                activeColor = activeColor,
                inactiveColor = inactiveColor
            )
        }
    }
}

/** Avance que enseña la vista previa: lo justo para que se lea como una canción en curso. */
private const val PREVIEW_FRACTION = 0.62f

/**
 * Centro del handle para una [fraction] dada, en píxeles. **Es la ÚNICA definición de "dónde está
 * la posición" en la barra**: la usan el gesto (a través de su inversa [progressFractionForX]), la
 * píldora plana y la onda. Tenerla en un solo sitio es lo que garantiza que el fill, el palo, la
 * gota y el dedo no puedan discrepar ni por un píxel — que es exactamente lo que pasaba cuando el
 * palo era el `thumb` de un `Slider` (colocado por layout) y el fill se pintaba aparte.
 *
 * El recorrido va inset media anchura de handle a cada lado, o el palo se saldría del contenedor al
 * principio y al final de la canción.
 */
private fun progressHandleCenterX(fraction: Float, width: Float, handleWidth: Float): Float =
    handleWidth / 2f + (width - handleWidth) * fraction.coerceIn(0f, 1f)

/** Inversa exacta de [progressHandleCenterX]: qué fracción representa un toque en [x]. */
private fun progressFractionForX(x: Float, width: Float, handleWidth: Float): Float {
    val usable = width - handleWidth
    return if (usable <= 0f) 0f else ((x - handleWidth / 2f) / usable).coerceIn(0f, 1f)
}

/**
 * Track PLANO del reproductor: píldora de borde redondo superpuesta al riel, con el nivel de búfer,
 * el stop indicator del spec y el handle (thumb) SIEMPRE visible marcando la posición.
 *
 * Todo en UN canvas y sin `clip`: el palo mide más que el track (asomar es lo que lo distingue del
 * riel), así que el riel y el búfer se redondean por geometría en vez de apoyarse en la máscara del
 * contenedor, que se lo comería.
 */
@Composable
private fun FlatTrack(
    fraction: Float,
    /** Búfer cargado (0 = no hay nada que contar). */
    bufferedFraction: Float,
    /**
     * Presencia del handle (1 = visible). Hoy los callers pasan 1f SIEMPRE —el thumb es permanente,
     * como en las barras de progreso del spec Expressive—; se mantiene como parámetro porque de él
     * sale, interpolada, la geometría del hueco fill↔palo↔riel (y a 1 queda pinada en "palo puesto").
     */
    handleAlpha: Float,
    /** Geometría derivada del grosor elegido en Ajustes. */
    metrics: ProgressMetrics,
    activeColor: Color,
    inactiveColor: Color
) {
    // Path reutilizado por los tres tramos (fill, riel, búfer): se redibujan en cada tick de
    // posición y en cada frame del arrastre.
    val trackPath = remember { Path() }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            // Mismo alto que la onda: si divergen, alternar el ajuste de Apariencia movería el
            // bloque de controles entero.
            .height(metrics.trackHeight)
    ) {
        val dotRadius = metrics.stopIndicator.toPx() / 2f
        val insideCorner = ComponentConfig.ProgressTrackInsideCorner.toPx()
        val handleW = ComponentConfig.ProgressHandleWidth.toPx()
        val gap = ComponentConfig.ProgressHandleGap.toPx()
        val radius = size.height / 2f
        val handleVisible = handleAlpha > 0f
        val handleCenterX = progressHandleCenterX(fraction, size.width, handleW)

        // Con el palo puesto, el fill se DETIENE antes de él y el riel arranca después: el palo
        // queda despegado de los dos por un hueco, que es como lo dibuja el spec. Eso es lo que
        // permite que sea del color del ACENTO en vez de un blanco/negro ajeno a la paleta — se
        // separa por GEOMETRÍA (los huecos y su altura), y eso funciona con cualquier acento.
        // Buscarle un color que contraste a la vez con el fill y con el riel no tiene solución
        // general: contra un acento medio, el blanco se funde con el riel claro y el negro con el
        // fill oscuro. Sin hueco por la izquierda el palo quedaría DENTRO del fill, del mismo color,
        // y solo se leería por las puntas que asoman.
        //
        // En reposo (sin palo) el fill termina en la posición y el riel arranca un `gap` después:
        // el hueco existe SIEMPRE, como en la onda. Antes el riel se dibujaba desde 0 y el fill
        // encima, así que en reposo los dos tramos se tocaban y la barra se leía como una sola
        // pieza de dos colores. El ancho mínimo de un círculo completo evita que la píldora se
        // deforme al inicio de la canción.
        //
        // Las dos medidas se INTERPOLAN con `handleAlpha` en vez de conmutar (mismo motivo que en
        // la onda: un `if` daría un salto en el frame en que aparece el palo). En reposo salen
        // `handleCenterX` y `handleCenterX + gap`; con el palo puesto, exactamente los bordes que
        // lo dejan flotando entre los dos huecos.
        val fillEnd = handleCenterX - (handleW / 2f + gap) * handleAlpha
        val fillWidth = if (handleVisible) fillEnd else fillEnd.coerceAtLeast(size.height)
        // Sale del fill YA acotado, no de `handleCenterX`: al principio de la canción la píldora
        // mide un círculo entero aunque el progreso sea menor, y con el riel calculado aparte se le
        // montaba encima justo ahí.
        val railStart = fillWidth + gap + (handleW + gap) * handleAlpha

        // FINAL DE LA CANCIÓN. Con el riel calculado a secas, el último tramo quedaba en un muñón
        // de unos pocos píxeles —del alto ENTERO de la barra, con el stop indicator dentro— pegado
        // al borde: una gota clara suelta al final del progreso. El riel deja de dibujarse en cuanto
        // no le cabe un círculo completo (es el criterio del `Slider` de M3 sin corner shrinking:
        // `inactiveTrackThreshold` le resta el `cornerSize`) y, en vez de dejar el hueco vacío, el
        // FILL se lo come hasta el borde y remata con la punta redonda entera.
        //
        // Los dos remates se interpolan con `railFade` en vez de conmutar, por lo mismo de siempre:
        // el corte cae en el último segundo de la canción, justo donde un salto se ve.
        // `railFade` es una RAMPA sobre lo que le queda al riel, no un interruptor: vale 0 mientras
        // le quepa un círculo entero y llega a 1 cuando se queda sin ancho. El fill se estira con
        // ella y, como se dibuja DESPUÉS, se va comiendo el muñón y el stop indicator en vez de
        // hacerlos desaparecer de golpe. Con el palo puesto no se estira nada (invadiría su sitio),
        // de ahí el factor `1 - handleAlpha`.
        val railWidth = size.width - railStart
        val railFade = (1f - railWidth / size.height).coerceIn(0f, 1f) * (1f - handleAlpha)
        val fillRight = fillWidth + (size.width - fillWidth) * railFade
        val fillEndCorner = insideCorner + (radius - insideCorner) * railFade
        if (railWidth > 0f) {
            // Extremos ASIMÉTRICOS, como el track del `Slider` de M3: redondo entero por fuera y
            // `insideCorner` por dentro (el lado que da al hueco). Ver [drawTrackSegment].
            drawTrackSegment(
                left = railStart,
                right = size.width,
                color = inactiveColor,
                startCorner = insideCorner,
                endCorner = radius,
                path = trackPath
            )
            // Búfer: entre el riel apagado y el fill, para que se lea como "esto ya está cargado"
            // sin competir con el progreso real. Acotado al riel visible, o invadiría el hueco.
            // Sus DOS extremos son interiores: el izquierdo comparte borde con el hueco y el
            // derecho es un corte dentro del riel (si llegara al final, el búfer ya no se dibuja).
            val bufferedEndX = size.width * bufferedFraction
            if (bufferedFraction > 0f && bufferedEndX > railStart) {
                drawTrackSegment(
                    left = railStart,
                    right = bufferedEndX,
                    color = activeColor.copy(alpha = BUFFER_TRACK_ALPHA),
                    startCorner = insideCorner,
                    endCorner = insideCorner,
                    path = trackPath
                )
            }
            // Stop indicator del spec, con SU fórmula de inset (`ProgressIndicator.drawStopIndicator`
            // de material3): media altura, pero TOPADA a `ProgressStopIndicatorTrailingSpace`. El
            // `Slider` usa media altura a secas porque su track es fijo; con el grosor ajustable eso
            // hundía el punto 12dp adentro en 24dp de barra. Con 12dp el tope no muerde y sale el
            // mismo píxel de siempre.
            //
            // Va DENTRO del bloque del riel a propósito: en el Slider oficial el stop se dibuja
            // junto al track inactivo y desaparece con él, no cuando el progreso lo alcanza.
            val stopInset = ((size.height - dotRadius * 2f) / 2f)
                .coerceAtMost(ComponentConfig.ProgressStopIndicatorTrailingSpace.toPx())
            val indicatorX = size.width - dotRadius - stopInset
            if (fillWidth < indicatorX && indicatorX > railStart) {
                drawCircle(color = activeColor, radius = dotRadius, center = Offset(indicatorX, radius))
            }
        }

        // Al principio de la canción el fill no llega a medir un círculo: se omite en vez de
        // dibujar una píldora deformada, porque ahí el palo ya marca la posición.
        //
        // Su borde derecho es INTERIOR (da al hueco), así que lleva `insideCorner` y no el radio
        // entero: con la punta redonda completa asomaban "hombros" de riel a los lados del palo
        // —radio del track contra 4dp de palo—, que fue lo que llevó a cortarlo recto. 2dp es lo
        // que hace el Slider oficial y no los produce.
        if (fillRight >= size.height) {
            drawTrackSegment(
                left = 0f,
                right = fillRight,
                color = activeColor,
                startCorner = radius,
                endCorner = fillEndCorner,
                path = trackPath
            )
        }
        // El fill NO lleva punto de contraste en la punta. Lo tuvo (el espejo del stop indicator) y
        // se RETIRÓ por decisión del usuario: con el palo puesto se pisaban, y sin él no aportaba
        // nada que el borde de la píldora no dijera ya. Con eso desaparece de paso el único sitio
        // de la barra que necesitaba resolver contraste contra el fill.

        // HANDLE del spec: mismo `handleCenterX` y mismo canvas que el fill y el riel, que es lo
        // único que garantiza que los huecos midan lo que dicen y que no asome un píxel por detrás.
        // Del color del ACENTO, flotando entre los dos huecos.
        //
        // La onda NO necesita el hueco izquierdo y por eso no lo tiene: su trazo mide 4dp contra los
        // 26 del palo, así que la punta se distingue sola. Acá el track mide 12 y sin hueco el palo
        // quedaría embebido en el fill.
        if (handleVisible) {
            val handleH = metrics.handleHeight.toPx()
            drawRoundRect(
                color = activeColor,
                alpha = handleAlpha,
                topLeft = Offset(handleCenterX - handleW / 2f, (size.height - handleH) / 2f),
                size = Size(handleW, handleH),
                cornerRadius = CornerRadius(handleW / 2f)
            )
        }
    }
}

/**
 * Un tramo del track plano, con los dos extremos redondeados por SEPARADO. Es lo que hace
 * `SliderDefaults.drawTrackPath` en material3 y no se puede con `drawRoundRect`, que solo acepta un
 * radio para las cuatro esquinas: el extremo exterior de cada tramo va redondo entero y el interior
 * —el que da al hueco— con `ProgressTrackInsideCorner`.
 *
 * El [path] se pasa desde fuera y se rebobina: este dibujo corre en cada frame del arrastre, y una
 * `Path` nueva por tramo serían tres asignaciones por frame.
 */
private fun DrawScope.drawTrackSegment(
    left: Float,
    right: Float,
    color: Color,
    startCorner: Float,
    endCorner: Float,
    path: Path
) {
    if (right <= left) return
    path.rewind()
    path.addRoundRect(
        RoundRect(
            rect = Rect(Offset(left, 0f), Size(right - left, size.height)),
            topLeft = CornerRadius(startCorner),
            topRight = CornerRadius(endCorner),
            bottomRight = CornerRadius(endCorner),
            bottomLeft = CornerRadius(startCorner)
        )
    )
    drawPath(path, color)
}

/**
 * Track ONDULADO del reproductor (Ajustes → Apariencia). Dibujado a mano A PROPÓSITO: el
 * `LinearWavyProgressIndicator` oficial anima el aplanado con `DecreasingAmplitudeAnimationSpec`,
 * una constante INTERNA FIJA (500 ms) que no expone por parámetro — al pausar terminaba
 * siempre después que el resto de las animaciones del reproductor y el desfase se notaba.
 * Aquí la amplitud usa el MISMO token del MotionScheme que el morph del botón play
 * (`defaultSpatial`), así todo cierra a la vez.
 *
 * La FASE avanza solo mientras suena y se congela al pausar (un `Animatable` cancelado
 * conserva su valor): sin salto al reanudar y sin gastar frames con el audio detenido.
 * Geometría igual que la píldora plana (alto 12dp, stop indicator) para que alternar el
 * ajuste no mueva el layout. En reposo la punta la remata el propio trazo (cap redondo): el HANDLE
 * aparece SOLO al buscar, igual que en la píldora plana — lo llevaba permanente y se retiró por
 * decisión del usuario.
 */
@Composable
private fun WavyTrack(
    fraction: Float,
    /** Búfer cargado (0 = no hay nada que contar); se dibuja recto sobre el riel apagado. */
    bufferedFraction: Float,
    isPlaying: Boolean,
    /** El usuario está arrastrando: el track debe seguir al dedo sin interpolar. */
    isDragging: Boolean,
    /**
     * Presencia del handle (1 = visible). Los callers pasan 1f SIEMPRE (thumb permanente); se
     * mantiene como parámetro porque de él sale, interpolado, el ancho del hueco onda↔palo↔riel.
     */
    handleAlpha: Float,
    /** Geometría derivada del grosor elegido en Ajustes. */
    metrics: ProgressMetrics,
    activeColor: Color,
    inactiveColor: Color
) {
    // MISMO token que el morph del botón play y que las dimensiones del transporte
    // (`defaultSpatial`, ver rememberPlayButtonSpin) para que todos cierren a la vez. Antes eran
    // dos `spring(...)` con los mismos números escritos en dos archivos, que es la forma más fácil
    // de que dejen de coincidir.
    //
    // El RECORTE es obligatorio aquí y no es cosmético: los springs spatial del scheme rebotan, y
    // una amplitud negativa no atenúa la onda — la INVIERTE.
    val rawAmplitude by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0f,
        animationSpec = appSpatialSpec(),
        label = "waveAmplitude"
    )
    val amplitudeFraction = rawAmplitude.coerceIn(0f, 1f)
    // Fase en "número de ondas recorridas", acotada a [0,1): entra en un seno, así que es periódica
    // en 1 vuelta y reencuadrarla no produce ningún salto visible (y el Float no pierde precisión
    // tras horas). El LaunchedEffect se cancela al pausar y el estado se queda donde estaba: sin
    // salto al reanudar.
    //
    // **El reloj es continuo (`withFrameNanos`), pero la fase se PUBLICA solo cuando la onda se
    // ha desplazado al menos un píxel** (`1 / wavelengthPx` vueltas). Antes era un `Animatable`
    // en bucle de un ciclo, y un Animatable invalida el dibujo en CADA vsync: con la onda a 22 dp/s
    // eso son 0,6 px por frame a 120 Hz — la app producía 120 frames por segundo para mover medio
    // píxel. Medido con Perfetto (17 ago): además del gasto, esa producción continua es lo que deja
    // a la app "un buffer por delante" de SurfaceFlinger tras cualquier frame tardío (buffer
    // stuffing) — el estado no drena mientras no haya vsyncs vacíos, sobrevive al cierre del
    // reproductor y se come el primer scroll de la lista. Con vsyncs vacíos entre publicaciones la
    // cola drena sola. `withFrameNanos` NO produce frames por sí mismo: sin invalidación no hay
    // buffer. Misma regla que el giro de la cookie del play: no dibujar lo que no mueve un píxel.
    val density = LocalDensity.current
    val wavelengthPxForStep = with(density) { ComponentConfig.ProgressWaveLength.toPx() }
    val phase = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(isPlaying, wavelengthPxForStep) {
        if (!isPlaying) return@LaunchedEffect
        val stepTurns = 1.0 / wavelengthPxForStep
        val basePhase = phase.floatValue.toDouble()
        val startNanos = withFrameNanos { it }
        var published = basePhase
        while (true) {
            withFrameNanos { now ->
                val turns = basePhase + (now - startNanos) / (WAVE_MS_PER_CYCLE * 1_000_000.0)
                if (turns - published >= stepTurns) {
                    published = turns
                    phase.floatValue = (turns % 1.0).toFloat()
                }
            }
        }
    }

    // El progreso llega a TIRONES: la posición se refresca una vez por segundo (el bucle de
    // MusicPlayerScreen, deliberadamente lento para no despertar el main thread cada frame).
    // En la píldora plana ese escalón se nota poco, pero aquí estira la onda de golpe y se ve
    // como un tropiezo — y cuando el ciclo de la onda duraba también 1s (antes de derivar el periodo
    // de la velocidad) el salto caía SIEMPRE en la misma fase, que lo hacía aún más visible. Se
    // interpola entre ticks a velocidad constante.
    //
    // Este reloj y el de la fase son los DOS únicos de la barra que no salen del MotionScheme, por
    // el mismo motivo: los dos tienen que ser LINEALES y durar exactamente lo que dura otra cosa
    // (un tick de posición / un ciclo de onda). Un spring aceleraría y frenaría dentro del tramo,
    // que es justo el tropiezo que se está corrigiendo.
    //
    // Y la misma regla que la fase para el avance del progreso: el tramo entre dos ticks se
    // recorre a velocidad constante, pero el valor dibujado solo se publica cuando el borde del
    // relleno se ha movido al menos un píxel del ancho del track — en una canción de 4 minutos son
    // ~4 px por segundo, o sea 4 publicaciones por segundo y no 120 (el tween anterior invalidaba
    // cada vsync para mover tres centésimas de píxel).
    var trackWidthPx by remember { mutableIntStateOf(0) }
    val smoothFraction = remember { mutableFloatStateOf(fraction) }
    LaunchedEffect(fraction, isPlaying, isDragging, trackWidthPx) {
        val from = smoothFraction.floatValue
        val jump = kotlin.math.abs(fraction - from)
        // Un seek (o el cambio de canción) NO se interpola: sería un barrido de un segundo
        // recorriendo toda la barra. Tampoco en pausa, donde no hay avance que suavizar, ni
        // arrastrando: ahí el track tiene que ir pegado al dedo y no un segundo por detrás.
        if (!isPlaying || isDragging || jump > PROGRESS_SNAP_THRESHOLD) {
            smoothFraction.floatValue = fraction
            return@LaunchedEffect
        }
        val stepFraction = if (trackWidthPx > 0) 1f / trackWidthPx else 0f
        val startNanos = withFrameNanos { it }
        var published = from
        var t = 0.0
        while (t < 1.0) {
            withFrameNanos { now ->
                t = ((now - startNanos) / (POSITION_TICK_MS * 1_000_000.0)).coerceAtMost(1.0)
                val value = (from + (fraction - from) * t).toFloat()
                if (kotlin.math.abs(value - published) >= stepFraction || t >= 1.0) {
                    published = value
                    smoothFraction.floatValue = value
                }
            }
        }
    }
    val drawnFraction = smoothFraction.floatValue

    // Path reutilizado: este bloque se redibuja en cada frame mientras suena.
    val wavePath = remember { Path() }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(metrics.trackHeight)
            // Ancho real del track, para publicar el progreso solo cuando avanza un píxel (ver arriba).
            .onSizeChanged { trackWidthPx = it.width }
    ) {
        val centerY = size.height / 2f
        val stroke = metrics.waveStroke.toPx()
        val radius = stroke / 2f
        val handleW = ComponentConfig.ProgressHandleWidth.toPx()
        val handleH = metrics.handleHeight.toPx()
        val handleVisible = handleAlpha > 0f
        // MISMA función de posición que el gesto y que la píldora plana: alternar el ajuste de
        // Apariencia no puede mover el punto donde cae el dedo.
        val startX = handleW / 2f
        val endX = size.width - handleW / 2f
        val activeEndX = progressHandleCenterX(drawnFraction, size.width, handleW)

        // Riel inactivo: recto siempre (solo la parte reproducida ondula, igual que el spec).
        //
        // El hueco se mide entre BORDES, no entre centros, y ese es el arreglo: `drawLine` coloca
        // su cap redondo CENTRADO en el punto, así que separar los dos puntos por un trazo dejaba
        // los caps tocándose (medio trazo cada uno) y el riel parecía la continuación de la onda.
        // Sumando medio trazo a cada lado, el aire visible es exactamente `ProgressHandleGap` — el
        // mismo que el palo deja en el modo plano, así que no hay un número nuevo que calibrar.
        //
        // El hueco se INTERPOLA con la aparición del palo en vez de conmutar: al buscar hay que
        // dejar sitio al palo (más ancho que el trazo). Con un `if` el riel daría un salto justo en
        // el frame en que aparece el handle. Cuando ya no cabe, el riel simplemente no se dibuja
        // (la canción está por terminar).
        val gap = ComponentConfig.ProgressHandleGap.toPx()
        val restGap = radius + gap + radius
        val dragGap = handleW / 2f + gap + radius
        val inactiveStartX = activeEndX + restGap + (dragGap - restGap) * handleAlpha
        // FINAL DE LA CANCIÓN: mismo criterio que la píldora plana. Sin él, el último tramo queda
        // en un cap redondo suelto con el stop indicator encima —un puntito flotando tras el hueco—
        // en vez de terminar. El riel se apaga cuando le queda menos que su propio grosor, y como
        // la onda ya llega hasta `endX` no hay nada que rellenar: aquí el fill no se estira (sería
        // un tramo recto pegado a una onda), simplemente el resto se desvanece.
        val inactiveWidth = endX - inactiveStartX
        val railAlpha = (inactiveWidth / stroke).coerceIn(0f, 1f)
        if (railAlpha > 0f) {
            drawLine(
                color = inactiveColor,
                alpha = railAlpha,
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
            // Stop indicator del final. Se desvanece con el riel (misma `railAlpha`): es su
            // remate, no un elemento aparte, y quedarse solo al final era justo lo que se veía mal.
            drawCircle(
                color = inactiveColor,
                alpha = railAlpha,
                radius = radius,
                center = Offset(endX, centerY)
            )
        }

        // Tramo reproducido: sinusoide muestreada; con amplitud 0 queda una recta perfecta.
        val amplitudePx = metrics.waveAmplitude.toPx() * amplitudeFraction
        val wavelengthPx = ComponentConfig.ProgressWaveLength.toPx()
        val phaseTurns = phase.floatValue
        // Y de la sinusoide en una x dada (x = startX ⇒ solo la fase, así el ARRANQUE también
        // ondula: anclarlo a centerY lo dejaba clavado mientras el resto se movía, además de
        // meter un pico vertical en el primer segmento).
        fun waveY(atX: Float): Float {
            val theta = ((atX - startX) / wavelengthPx + phaseTurns) * FULL_TURN_RADIANS
            return centerY + sin(theta) * amplitudePx
        }
        // La onda TERMINA un `dragGap` antes del thumb, no en su centro: así queda un hueco entre la
        // onda y el palo, SIMÉTRICO con el que el riel deja después (inactiveStartX = activeEndX +
        // dragGap). Como el trazo lleva cap redondo, su borde derecho cae en `waveEndX + radius`, o
        // sea a `gap` del borde izquierdo del palo — el mismo aire medido entre BORDES que en el modo
        // plano y en la referencia del spec. Antes la onda llegaba hasta `activeEndX` (el centro del
        // palo) y se metía por debajo de él sin hueco.
        val waveEndX = activeEndX - dragGap
        // Al principio de la canción no cabe onda antes del palo (waveEndX <= startX): se omite, como
        // el fill del modo plano, y el palo marca la posición solo.
        if (waveEndX > startX) {
            val step = WAVE_SAMPLE_STEP_PX
            wavePath.reset()
            wavePath.moveTo(startX, waveY(startX))
            var x = startX + step
            while (x < waveEndX) {
                wavePath.lineTo(x, waveY(x))
                x += step
            }
            // Punto final exacto: con el paso de muestreo la punta quedaría corta.
            wavePath.lineTo(waveEndX, waveY(waveEndX))
            drawPath(
                path = wavePath,
                color = activeColor,
                style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }

        // HANDLE del spec Expressive (palo vertical), SIEMPRE visible (como las barras de progreso
        // del spec). Estuvo un tiempo solo-al-arrastrar y volvió a permanente por decisión del
        // usuario. Va CENTRADO en vertical con la onda pasándole por detrás, no rematando la punta.
        //
        // Va dibujado acá dentro y no como `thumb` del Slider a propósito: el thumb del Slider se
        // posiciona con el valor CRUDO —que llega a tirones, una vez por segundo— mientras la onda
        // avanza con la fracción interpolada, así que se verían desincronizados. Aquí el palo se
        // centra en `activeEndX` y la onda termina un `dragGap` antes: los dos salen del MISMO
        // `activeEndX` interpolado, así que el hueco entre ambos no baila.
        //
        // CENTRADO en vertical, NO montado en la cresta: un palo de 26dp subiendo y bajando 3dp por
        // frame se balancea, y en el slider ondulado del spec el handle está quieto y la onda le
        // pasa por detrás.
        if (handleVisible) {
            drawRoundRect(
                color = activeColor,
                alpha = handleAlpha,
                topLeft = Offset(activeEndX - handleW / 2f, centerY - handleH / 2f),
                size = Size(handleW, handleH),
                cornerRadius = CornerRadius(handleW / 2f)
            )
        }
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
/**
 * Cuánto tarda la onda en recorrer UN ciclo. Se DERIVA de la longitud de onda y de la velocidad, en
 * vez de ser una constante propia: la magnitud que se percibe es la velocidad (dp/s), y con el
 * periodo fijo cada retoque del ancho de la onda cambiaba también el ritmo, sin que se viera venir.
 */
private val WAVE_MS_PER_CYCLE: Float
    get() = ComponentConfig.ProgressWaveLength.value /
        ComponentConfig.ProgressWaveSpeed.value * MILLIS_PER_SECOND



/**
 * Periodo con el que la UI refresca la posición de reproducción. Lo usan las DOS puntas y por eso
 * es `internal`: el bucle de `MusicPlayerScreen` que llama a `updatePosition()` y la interpolación
 * del track, que dura exactamente un tick para que cada uno llegue justo cuando el anterior terminó
 * de dibujarse y el avance se vea continuo.
 *
 * **Por qué un segundo y no menos**: es la resolución de lo que la pantalla ENSEÑA — los chips de
 * tiempo van en `mm:ss`, así que preguntar más a menudo no cambiaría ni un dígito, y la barra cubre
 * el hueco interpolando entre ticks en vez de sondeando. Menos que un segundo dejaría el contador
 * retrasado respecto al audio. Cada tick es un salto al main thread, y este bucle corre todo el rato
 * que dura la reproducción con la app delante.
 *
 * Estuvo `private` con un `delay(1000L)` literal al otro lado: el KDoc afirmaba que coincidían y
 * nada lo obligaba, así que tocar uno desincronizaba el dibujo del dato en silencio.
 */
internal const val POSITION_TICK_MS = 1000

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
        // El chip cambia de ANCHO al tocarlo ("FLAC" → "FLAC · 16 bit · 44.1 kHz") y hasta ahora
        // ese cambio era un salto seco en medio de la barra de progreso. `animateContentSize` es
        // justo para esto: mide el contenido nuevo y anima el contenedor hasta él.
        modifier = modifier
            .animateContentSize(appSpatialSpec())
            .semantics { contentDescription = desc }
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
 * así funciona sobre cualquier punto del gradiente/carátula sin que nadie tenga que pasarle el
 * color de lo que hay debajo.
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
