package com.qhana.siku.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.util.lerp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.qhana.siku.ui.theme.LocalAppColors
import com.qhana.siku.player.audio.EqCurve
import kotlin.math.ceil
import kotlin.math.ln

import com.qhana.siku.ui.theme.appEffectsSpec
import com.qhana.siku.ui.theme.appFastEffectsSpec
import com.qhana.siku.ui.theme.appSlowEffectsSpec

/**
 * Gráfico de la respuesta en frecuencia del ecualizador: lo que la curva le hace de verdad al
 * sonido, bandas y refuerzos JUNTOS.
 *
 * Es la pieza que faltaba en la hoja. Los refuerzos son aditivos sobre la curva de bandas y sus
 * controles viven en otra sección, así que su efecto combinado no se podía deducir mirando nada —
 * y esa falta de visibilidad, no la forma de los filtros, fue una de las causas de que los boosts
 * sonaran a ruido. Aquí se ve el pico saliéndose antes de escucharlo.
 *
 * La matemática NO se duplica: la rejilla de [EqCurve.response] es la misma que ya alimentaba el
 * indicador de headroom, que se calculaba entera y se reducía a su máximo. Dibujarla no añade
 * coste de cálculo.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun EqResponseGraph(
    /** Magnitudes en dB sobre la rejilla logarítmica de [EqCurve]. Es la curva TOTAL. */
    response: FloatArray,
    /**
     * Aportación SOLO de los refuerzos (misma rejilla), o null si no hay ninguno activo.
     *
     * Se dibuja aparte porque la suma esconde de dónde viene cada dB, y eso no es un detalle
     * estético: la causa raíz de julio fue justamente que los refuerzos se acumulaban sobre el
     * preset hasta +12..14 dB sin que nada lo mostrara. El indicador de headroom da ese dato como
     * número; esta curva lo da como forma, y además enseña SOBRE QUÉ FRECUENCIAS actúa.
     */
    boostResponse: FloatArray? = null,
    /**
     * Hay un dedo sobre un control de refuerzo: la punteada pasa a primer plano y la total se
     * atenúa.
     *
     * Mover un refuerzo desplaza las DOS curvas —la total tiene que moverse, es lo que suena— y dos
     * trazos cambiando a la vez no dicen cuál mirar. Mientras dura el gesto, la pregunta que el
     * usuario se está haciendo es "cuánto de esto lo pongo yo con este control", y esa la responde la
     * punteada. Al soltar vuelven las dos a su peso normal, que es la lectura correcta en reposo.
     */
    boostFocused: Boolean = false,
    /** Pico de la curva; decide el color, igual que el indicador de headroom. */
    headroomDb: Float,
    /** Umbral desde el que la curva se pinta como "atención". */
    cautionDb: Float,
    /** Umbral desde el que se pinta como riesgo. */
    riskDb: Float,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    height: Dp = GraphHeight
) {
    val colors = LocalAppColors.current
    val measurer = rememberTextMeasurer()

    // Color por estado, con la MISMA regla de umbrales que el indicador de headroom: si el gráfico
    // y el aviso discreparan, el usuario tendría dos lecturas del mismo dato.
    val target = when {
        !enabled -> colors.onSurfaceVariant
        headroomDb >= riskDb -> colors.error
        headroomDb >= cautionDb -> colors.tertiary
        else -> colors.primary
    }
    // El color cambia al CRUZAR un umbral mientras el dedo arrastra, así que se interpola: un
    // salto seco se lee como un parpadeo de todo el gráfico.
    //
    // El spec va EXPLÍCITO. Antes se omitía con el comentario de que "el spec del tema ya define la
    // duración vía MotionScheme": eso era falso — `animateColorAsState` sin `animationSpec` usa el
    // default de compose-animation y el tema no entra en juego.
    val curveColor by animateColorAsState(
        targetValue = target,
        animationSpec = appEffectsSpec(),
        label = "eqCurveColor"
    )

    val gridColor = colors.outlineVariant
    val frameColor = colors.outline.copy(alpha = FrameAlpha)
    val labelColor = colors.onSurfaceVariant
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = labelColor)

    // Las etiquetas del eje X se miden una vez por composición, no por frame: el layout de texto
    // es caro y el Canvas se redibuja con cada movimiento del dedo.
    val axisLabels = remember(measurer, labelStyle) {
        AXIS_FREQUENCIES.map { hz ->
            val text = if (hz >= 1000) "${hz / 1000}k" else "$hz"
            AxisLabel(fraction = logFraction(hz.toDouble()), layout = measurer.measure(text, labelStyle))
        }
    }

    // Excursión máxima de la curva, en valor absoluto: es lo que la escala tiene que caber.
    val peakDb = remember(response, boostResponse) {
        var peak = 0f
        for (db in response) {
            val magnitude = if (db < 0f) -db else db
            if (magnitude > peak) peak = magnitude
        }
        // La del refuerzo también manda: con bandas en negativo puede superar a la total.
        boostResponse?.forEach { db ->
            val magnitude = if (db < 0f) -db else db
            if (magnitude > peak) peak = magnitude
        }
        peak
    }

    // Escala vertical ADAPTATIVA por ESCALONES de [GRID_STEP_DB], sin techo. Hace falta porque la
    // curva se sale de la escala base por los dos lados: el preamp la desplaza entera hacia abajo
    // (hasta −12 dB) y los refuerzos son ADITIVOS sobre las bandas hacia arriba (preset a +12 más
    // un refuerzo a +12 pasa holgadamente de +20).
    //
    // Discreta y no continua a propósito: una escala que se reajusta en cada frame de arrastre
    // hace que la curva parezca moverse sola mientras el dedo va en la otra dirección.
    //
    // Pero el escalón NO puede cambiar de golpe, y ese era el bug: la escala saltaba de 12 a 24 dB
    // en un frame, o sea que la curva pasaba de tocar el borde superior a ocupar la mitad de la
    // altura de una vez. Justo al cruzar los 12 dB —el momento en que el usuario está subiendo
    // ganancia— el gráfico se encogía a su tamaño de reposo y se leía como si se hubiera RESETEADO.
    // Dos cambios lo arreglan: pasos de 6 dB (no un salto ×2) e interpolación del zoom, que es lo
    // que convierte el reset aparente en un alejamiento continuo de la cámara.
    val rangeTracker = remember { RangeTracker() }
    val targetRangeDb = remember(peakDb) {
        rangeTracker.rangeDb = nextRange(rangeTracker.rangeDb, peakDb)
        rangeTracker.rangeDb
    }
    // Los dos sentidos NO duran lo mismo. Ensanchar es URGENTE: hasta que la escala termina de
    // abrirse la curva no cabe, y `drawCurve` la recorta contra el borde, así que una animación
    // larga dejaría el pico aplastado justo mientras el dedo lo está subiendo. Estrechar no
    // corre ninguna prisa —lo que sobra es aire— y ahí conviene lento, que además refuerza la
    // histéresis. Ninguno de los dos con rebote: un spring espacial haría que la curva se pasara
    // de largo y volviera, que es el latido que se está intentando quitar.
    val openSpec = appFastEffectsSpec<Float>()
    val closeSpec = appSlowEffectsSpec<Float>()
    val rangeAnim = remember { Animatable(targetRangeDb) }
    LaunchedEffect(targetRangeDb) {
        rangeAnim.animateTo(
            targetValue = targetRangeDb,
            animationSpec = if (targetRangeDb > rangeAnim.value) openSpec else closeSpec
        )
    }
    val rangeDb = rangeAnim.value

    // Foco como FRACCIÓN animada y no como Boolean crudo: el resalte entra y sale mientras el dedo
    // ya está moviendo la curva, y un salto seco de opacidad ahí se lee como un parpadeo. Spec de
    // EFFECTS —se animan opacidad y grosor, y sobre todo el valor está acotado a [0,1]: un token
    // spatial rebota y se saldría del rango por los dos lados.
    val focus by animateFloatAsState(
        targetValue = if (boostFocused && boostResponse != null) 1f else 0f,
        animationSpec = appEffectsSpec(),
        label = "eqBoostFocus"
    )

    Box(modifier = modifier.fillMaxWidth().height(height)) {
        Canvas(modifier = Modifier.fillMaxWidth().height(height).padding(bottom = LabelGutter)) {
            drawGrid(gridColor, axisLabels, rangeDb)
            // Debajo de la total y sin relleno: es un componente de la curva, no otra lectura.
            boostResponse?.let { drawBoostCurve(it, curveColor, enabled, rangeDb, focus) }
            drawCurve(response, curveColor, enabled, rangeDb, focus)
            drawFrame(frameColor)
        }
        Canvas(modifier = Modifier.fillMaxWidth().height(height)) {
            val y = size.height - LabelGutter.toPx() + LabelOffset.toPx()
            axisLabels.forEach { label ->
                val x = (label.fraction * size.width - label.layout.size.width / 2f)
                    .coerceIn(0f, size.width - label.layout.size.width)
                drawText(label.layout, topLeft = Offset(x, y))
            }
        }
    }
}

/** Líneas de referencia: 0 dB continua y múltiplos de [GRID_STEP_DB] punteados, más las del eje X. */
private fun DrawScope.drawGrid(color: Color, labels: List<AxisLabel>, rangeDb: Float) {
    val zeroY = size.height / 2f
    val dashed = PathEffect.dashPathEffect(floatArrayOf(DashOn, DashOff))

    // Horizontales de ganancia. La de 0 dB va entera porque es la referencia (curva plana =
    // bit-perfect); las de ±6 dB, punteadas, solo dan escala.
    drawLine(
        color = color,
        start = Offset(0f, zeroY),
        end = Offset(size.width, zeroY),
        strokeWidth = ZeroLineWidth.toPx()
    )
    // Se dibujan TODOS los múltiplos que caben, no dos fijos: al ensancharse la escala aparecen
    // solas las guías de ±18 y la lectura en dB no se pierde.
    var guideDb = GRID_STEP_DB
    while (guideDb < rangeDb) {
        val offset = guideDb / rangeDb * (size.height / 2f)
        listOf(zeroY - offset, zeroY + offset).forEach { y ->
            drawLine(
                color = color.copy(alpha = GridAlpha),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = GridLineWidth.toPx(),
                pathEffect = dashed
            )
        }
        guideDb += GRID_STEP_DB
    }

    // Verticales en las décadas etiquetadas: dan sentido al eje logarítmico.
    labels.forEach { label ->
        val x = label.fraction * size.width
        drawLine(
            color = color.copy(alpha = GridAlpha),
            start = Offset(x, 0f),
            end = Offset(x, size.height),
            strokeWidth = GridLineWidth.toPx(),
            pathEffect = dashed
        )
    }
}

/**
 * La curva y su relleno hasta la línea de 0 dB. El relleno es lo que da la lectura de un vistazo:
 * el área por encima del cero es energía añadida, que es exactamente lo que consume headroom.
 */
private fun DrawScope.drawCurve(
    response: FloatArray,
    color: Color,
    enabled: Boolean,
    rangeDb: Float,
    /** 0 = reposo, 1 = el dedo está en un refuerzo y esta curva cede el primer plano. */
    focus: Float = 0f
) {
    if (response.isEmpty()) return
    val zeroY = size.height / 2f
    // Se atenúa, NO se esconde: sigue siendo lo que suena, y el sentido del resalte es justamente
    // poder comparar cuánto de ella pone el refuerzo. Con la total desaparecida no habría nada
    // contra qué comparar.
    val alpha = (if (enabled) 1f else DISABLED_CONTENT_ALPHA) * lerp(1f, FocusedDimAlpha, focus)

    fun yFor(db: Float): Float =
        zeroY - (db / rangeDb).coerceIn(-1f, 1f) * (size.height / 2f)

    val line = Path()
    response.forEachIndexed { index, db ->
        val x = index / (response.size - 1f) * size.width
        val y = yFor(db)
        if (index == 0) line.moveTo(x, y) else line.lineTo(x, y)
    }

    // El relleno es la misma curva cerrada contra el cero: se clona el path para no destruir el
    // de la línea, que se dibuja encima con su propio grosor.
    val fill = Path().apply {
        addPath(line)
        lineTo(size.width, zeroY)
        lineTo(0f, zeroY)
        close()
    }
    drawPath(
        path = fill,
        brush = Brush.verticalGradient(
            0f to color.copy(alpha = FillAlphaTop * alpha),
            1f to color.copy(alpha = FillAlphaBottom * alpha)
        )
    )
    drawPath(
        path = line,
        color = color.copy(alpha = alpha),
        style = Stroke(width = CurveWidth.toPx())
    )
}

/**
 * Aportación de los refuerzos: línea PUNTEADA, sin relleno y atenuada.
 *
 * Las tres cosas son deliberadas. Sin relleno y por debajo, porque no es una segunda lectura del
 * mismo dato sino un COMPONENTE de la curva total — si tuviera el mismo peso visual competirían y
 * no se sabría cuál manda. Punteada, porque va a solaparse con la total en cuanto las bandas estén
 * planas, y ahí dos líneas continuas del mismo color serían una sola.
 *
 * Comparte el color de la curva (atenuado) en vez de estrenar uno propio: el acento sale de la
 * carátula y ya cambia de canción a canción, así que un segundo color fijo chocaría con la mitad
 * de los temas.
 */
private fun DrawScope.drawBoostCurve(
    response: FloatArray,
    color: Color,
    enabled: Boolean,
    rangeDb: Float,
    /** 0 = reposo, 1 = el dedo está en un refuerzo y esta curva pasa a primer plano. */
    focus: Float = 0f
) {
    if (response.isEmpty()) return
    val zeroY = size.height / 2f
    // Con foco sube a opacidad plena y engorda hasta el grosor de la curva total: sigue siendo
    // punteada —es lo que la distingue cuando se solapan— pero deja de ser la línea secundaria.
    val alpha = (if (enabled) 1f else DISABLED_CONTENT_ALPHA) * lerp(BoostCurveAlpha, 1f, focus)
    val width = lerp(BoostCurveWidth.toPx(), CurveWidth.toPx(), focus)

    val path = Path()
    response.forEachIndexed { index, db ->
        val x = index / (response.size - 1f) * size.width
        val y = zeroY - (db / rangeDb).coerceIn(-1f, 1f) * (size.height / 2f)
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(
        path = path,
        color = color.copy(alpha = alpha),
        style = Stroke(
            width = width,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(DashOn, DashOff))
        )
    )
}

/**
 * Marco en U: laterales y base, con las esquinas inferiores redondeadas. ARRIBA queda abierto a
 * propósito — cerrarlo convertiría el gráfico en una caja y la curva puede tocar el borde superior
 * cuando la ganancia llega al máximo; abierto se lee como un eje, que es lo que es. Sin él el
 * gráfico flotaba sobre el fondo sin nada que lo contuviera.
 */
private fun DrawScope.drawFrame(color: Color) {
    val inset = FrameWidth.toPx() / 2f
    val radius = FrameCornerRadius.toPx()
    val left = inset
    val right = size.width - inset
    val bottom = size.height - inset

    val path = Path().apply {
        moveTo(left, 0f)
        lineTo(left, bottom - radius)
        quadraticTo(left, bottom, left + radius, bottom)
        lineTo(right - radius, bottom)
        quadraticTo(right, bottom, right, bottom - radius)
        lineTo(right, 0f)
    }
    drawPath(path = path, color = color, style = Stroke(width = FrameWidth.toPx()))
}

/**
 * Escalón de escala que contiene [peakDb]: el menor múltiplo de [GRID_STEP_DB] que no baja de
 * [BASE_RANGE_DB]. Que los escalones coincidan con la separación de las guías punteadas no es
 * casual — así el borde superior del gráfico SIEMPRE cae sobre una guía y la escala se sigue
 * leyendo en dB después de ensancharse.
 */
private fun rangeStepFor(peakDb: Float): Float {
    if (peakDb <= BASE_RANGE_DB) return BASE_RANGE_DB
    return ceil(peakDb / GRID_STEP_DB) * GRID_STEP_DB
}

/**
 * Siguiente escala a partir de la actual. Subir es inmediato (la curva no puede salirse); bajar
 * exige que el pico quepa con [RANGE_HYSTERESIS_DB] de margen en el escalón menor.
 *
 * Esa asimetría es la histéresis, y es necesaria porque el pico se mueve con el dedo: parado justo
 * en el filo de un escalón, un temblor de décimas alternaría dos escalas y —ahora que la
 * transición está animada— la curva latiría sin parar en vez de parpadear una vez.
 */
private fun nextRange(currentDb: Float, peakDb: Float): Float {
    val target = rangeStepFor(peakDb)
    if (target >= currentDb) return target
    return if (peakDb <= target - RANGE_HYSTERESIS_DB) target else currentDb
}

/**
 * Escala vigente entre recomposiciones. Es un objeto plano y no `mutableFloatStateOf` a propósito:
 * la escala se DERIVA del pico (que ya provoca la recomposición) y escribir estado de snapshot
 * durante la composición invalidaría el propio scope que lo acaba de escribir.
 */
private class RangeTracker(var rangeDb: Float = BASE_RANGE_DB)

/** Posición 0..1 de una frecuencia en el eje logarítmico de la rejilla. */
private fun logFraction(hz: Double): Float =
    (ln(hz / EqCurve.GRID_LOW_HZ) / ln(EqCurve.GRID_HIGH_HZ / EqCurve.GRID_LOW_HZ)).toFloat()

private class AxisLabel(val fraction: Float, val layout: androidx.compose.ui.text.TextLayoutResult)

/**
 * Alto del gráfico. Suficiente para que ±12 dB se lean como forma y no como una línea temblorosa,
 * sin comerse el espacio de los controles en un teléfono.
 */
private val GraphHeight = 148.dp

/** Franja inferior reservada a las etiquetas de frecuencia. */
private val LabelGutter = 16.dp
private val LabelOffset = 2.dp

private val CurveWidth = 2.5.dp
private val ZeroLineWidth = 1.dp
private val GridLineWidth = 1.dp
private val FrameWidth = 1.dp

/** Redondeo de las dos esquinas inferiores del marco, en la familia de las formas de la app. */
private val FrameCornerRadius = 12.dp

/**
 * Escala vertical mínima: el rango de una banda, para que un ±12 llegue justo al borde. Es un
 * SUELO y no un default — nunca se baja de aquí aunque la curva sea plana, porque si no una curva
 * de ±1 dB se dibujaría igual de aparatosa que una de ±12 y el gráfico dejaría de comunicar
 * magnitud.
 */
private const val BASE_RANGE_DB = 12f

/**
 * Margen que tiene que sobrar para BAJAR de escalón. Un pelo menos de la mitad del escalón: lo
 * bastante para que el filo no oscile, lo bastante poco para que la escala no se quede grande
 * mucho después de que la curva haya bajado.
 */
private const val RANGE_HYSTERESIS_DB = 2f

/** Peso visual de la curva de refuerzo: presente pero claramente secundaria. */
private const val BoostCurveAlpha = 0.55f

/**
 * Opacidad de la curva TOTAL mientras el dedo está en un refuerzo. No baja más porque el resalte
 * sirve para COMPARAR las dos curvas: por debajo de ~0.35 la total deja de leerse y la punteada se
 * queda sola, que es tan poco informativo como el problema que se está arreglando.
 */
private const val FocusedDimAlpha = 0.35f

private val BoostCurveWidth = 1.5.dp

/** Separación de las guías punteadas de ganancia. */
private const val GRID_STEP_DB = 6f

private const val GridAlpha = 0.5f

/**
 * El marco va MUY tenue: su trabajo es dar suelo al gráfico para que no flote sobre el fondo, no
 * dibujar un recuadro. Sobre `outline` —un color pensado para bordes con presencia— a esta
 * opacidad queda insinuado, que es justo lo que se busca: se nota si falta, no si está.
 */
private const val FrameAlpha = 0.12f
private const val FillAlphaTop = 0.28f
private const val FillAlphaBottom = 0.04f

private const val DashOn = 4f
private const val DashOff = 6f

/** Décadas etiquetadas en el eje. Las mismas que usa cualquier analizador: potencias de 10. */
private val AXIS_FREQUENCIES = listOf(50, 100, 500, 1000, 5000, 10000)
