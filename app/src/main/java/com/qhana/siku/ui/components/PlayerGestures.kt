package com.qhana.siku.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Gestos del reproductor, compartidos por el NowPlaying y el MiniPlayer. Todos cuelgan del MISMO
 * ajuste (Ajustes → Reproducción, `MusicPreferences.playerGesturesFlow`): quien los apaga los
 * apaga enteros, así que cada API de aquí recibe `enabled` y se convierte en un no-op cuando es
 * `false` — sin registrar `pointerInput`, para no competir por eventos que no va a usar.
 */
object PlayerGestureConfig {

    /** Salto del doble toque en los laterales de la carátula. */
    const val SeekStepSeconds = 10
    val SeekStepMs = SeekStepSeconds * 1000L

    /**
     * Recorrido horizontal (fracción del ancho de la carátula) a partir del cual soltar cambia de
     * canción. Fracción y no dp: en una tablet la carátula es enorme y un umbral fijo se cruzaría
     * con un gesto mínimo.
     */
    const val SwipeSongFraction = 0.22f

    /** Tope del arrastre horizontal: pasado el umbral el dedo deja de mover la carátula. */
    val SwipeSongMaxDrag = 110.dp

    /** Arrastre vertical que cierra el reproductor. */
    val DismissThreshold = 140.dp

    /** Arrastre vertical hacia arriba que abre el reproductor desde el MiniPlayer. */
    val ExpandThreshold = 40.dp

    /** Opacidad del reproductor al llegar al umbral de cierre. */
    const val DismissMinAlpha = 0.6f

    /** Cuánto se ve el destello del doble toque de salto. */
    const val SeekFlashMs = 500

    /** Retorno de un arrastre que no llegó al umbral: firme y sin rebote. */
    internal fun <T> settleSpring() = spring<T>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow
    )
}

/**
 * Arrastre hacia abajo para CERRAR el reproductor. El estado vive fuera del gesto porque lo
 * consumen dos sitios a la vez: el `pointerInput` que lo alimenta (fondo del reproductor y
 * carátula) y el `graphicsLayer` que traslada y atenúa la pantalla mientras el dedo baja.
 *
 * Al superar el umbral el offset NO se resetea: el slide de salida del `AnimatedContent` del
 * `PlayerOverlay` arranca desde donde quedó el dedo, sin el salto de un snap a cero. Reabrir el
 * reproductor lo recompone desde cero, así que el `Animatable` nace en su sitio.
 */
@Stable
class PlayerDismissState internal constructor(
    private val scope: CoroutineScope,
    private val thresholdPx: Float,
    private val onDismiss: () -> Unit
) {
    private val offset = Animatable(0f)

    /** Ya se soltó pasando el umbral: el reproductor se está yendo y el gesto deja de responder. */
    private var dismissing = false

    /** Desplazamiento actual en px, siempre ≥ 0 (hacia arriba no hay nada que hacer). */
    val offsetY: Float get() = offset.value

    /** 0 en reposo, 1 en el umbral de cierre. Lo consume la opacidad del reproductor. */
    val progress: Float get() = if (thresholdPx <= 0f) 0f else (offset.value / thresholdPx).coerceIn(0f, 1f)

    internal fun onDrag(deltaY: Float) {
        if (dismissing) return
        scope.launch { offset.snapTo((offset.value + deltaY).coerceAtLeast(0f)) }
    }

    internal fun onRelease() {
        if (dismissing) return
        if (offset.value >= thresholdPx && thresholdPx > 0f) {
            dismissing = true
            onDismiss()
        } else {
            scope.launch { offset.animateTo(0f, PlayerGestureConfig.settleSpring()) }
        }
    }
}

@Composable
fun rememberPlayerDismissState(
    threshold: Dp = PlayerGestureConfig.DismissThreshold,
    onDismiss: () -> Unit
): PlayerDismissState {
    val scope = rememberCoroutineScope()
    val thresholdPx = with(LocalDensity.current) { threshold.toPx() }
    // rememberUpdatedState: el estado sobrevive a recomposiciones y no debe quedarse con una
    // lambda vieja (onDismiss se reconstruye con cada cambio de canción en PlayerOverlay).
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    return remember(scope, thresholdPx) {
        PlayerDismissState(scope, thresholdPx) { currentOnDismiss() }
    }
}

/**
 * Alimenta [state] con los arrastres verticales que empiecen sobre este nodo. Va en el FONDO del
 * reproductor: los hijos que consumen gestos propios (el slider, los botones) reciben antes y
 * este modifier solo ve lo que sobra.
 */
fun Modifier.playerDismissDrag(state: PlayerDismissState?, enabled: Boolean): Modifier =
    if (!enabled || state == null) this else pointerInput(state) {
        detectVerticalDragGestures(
            onDragEnd = { state.onRelease() },
            onDragCancel = { state.onRelease() },
            onVerticalDrag = { change, dragAmount ->
                state.onDrag(dragAmount)
                // Solo se consume si el gesto está moviendo algo de verdad: un arrastre hacia
                // arriba en reposo no debe robarle el evento a nadie.
                if (state.offsetY > 0f) change.consume()
            }
        )
    }

/** Eje al que se ata un arrastre de la carátula en cuanto se sabe hacia dónde va. */
private enum class DragAxis { Undecided, Horizontal, Vertical }

/**
 * Arrastre de la CARÁTULA: horizontal cambia de canción, vertical hacia abajo cierra (delegando
 * en [dismiss], para que el gesto sea el mismo empiece donde empiece).
 *
 * El eje se BLOQUEA con el primer movimiento y no se vuelve a decidir. Sin ese bloqueo, un gesto
 * en diagonal —que es como arrastra la mayoría— iría alternando de eje a mitad de camino y
 * acabaría sin cruzar ningún umbral, con el reproductor temblando y sin pasar nada.
 *
 * [offsetX] se hoistea porque quien dibuja la carátula necesita leerlo para trasladarla.
 */
fun Modifier.albumArtSwipe(
    enabled: Boolean,
    offsetX: Animatable<Float, AnimationVector1D>,
    dismiss: PlayerDismissState?,
    scope: CoroutineScope,
    maxDragPx: Float,
    onNext: () -> Unit,
    onPrevious: () -> Unit
): Modifier = if (!enabled) this else pointerInput(enabled, onNext, onPrevious) {
    var axis = DragAxis.Undecided
    val songThresholdPx = size.width * PlayerGestureConfig.SwipeSongFraction

    fun settle() {
        scope.launch { offsetX.animateTo(0f, PlayerGestureConfig.settleSpring()) }
    }

    detectDragGestures(
        onDragStart = { axis = DragAxis.Undecided },
        onDragCancel = {
            axis = DragAxis.Undecided
            dismiss?.onRelease()
            settle()
        },
        onDragEnd = {
            when {
                axis == DragAxis.Vertical -> dismiss?.onRelease()
                // Arrastrar a la IZQUIERDA trae lo que está a la derecha: la siguiente.
                offsetX.value <= -songThresholdPx -> onNext()
                offsetX.value >= songThresholdPx -> onPrevious()
            }
            axis = DragAxis.Undecided
            // Siempre vuelve al centro: el cambio de canción lo cuenta el reveal de la carátula
            // (ver AlbumArtSection), no una salida por el borde que competiría con él.
            settle()
        },
        onDrag = { change, delta ->
            if (axis == DragAxis.Undecided && delta != Offset.Zero) {
                axis = if (abs(delta.x) >= abs(delta.y)) DragAxis.Horizontal else DragAxis.Vertical
            }
            when (axis) {
                DragAxis.Horizontal -> {
                    change.consume()
                    scope.launch {
                        offsetX.snapTo((offsetX.value + delta.x).coerceIn(-maxDragPx, maxDragPx))
                    }
                }
                DragAxis.Vertical -> {
                    dismiss?.let {
                        it.onDrag(delta.y)
                        if (it.offsetY > 0f) change.consume()
                    }
                }
                DragAxis.Undecided -> Unit
            }
        }
    )
}

/**
 * Doble toque en los laterales de la carátula para saltar hacia atrás/adelante, más el long-press
 * que ya existía (selector de color). Van juntos en el MISMO detector porque son el mismo tipo de
 * gesto: dos `detectTapGestures` encadenados competirían por el evento y solo respondería uno.
 *
 * [onSeek] recibe el salto YA firmado y [onFlash] `true` si fue hacia adelante, para el destello.
 */
fun Modifier.albumArtTaps(
    gesturesEnabled: Boolean,
    onSeek: (Long) -> Unit,
    onFlash: (forward: Boolean) -> Unit,
    onLongPress: () -> Unit
): Modifier = pointerInput(gesturesEnabled, onSeek, onLongPress) {
    // El doble toque se declara aparte y tipado: en línea, `if (…) null else { offset -> … }`
    // hace que Kotlin lea el `else` como un BLOQUE y no como la lambda que espera el parámetro.
    val onDoubleTap: ((Offset) -> Unit)? = if (gesturesEnabled) {
        { offset ->
            val forward = offset.x > size.width / 2f
            onSeek(if (forward) PlayerGestureConfig.SeekStepMs else -PlayerGestureConfig.SeekStepMs)
            onFlash(forward)
        }
    } else {
        null
    }
    detectTapGestures(
        onLongPress = { onLongPress() },
        onDoubleTap = onDoubleTap
    )
}

/**
 * Arrastre hacia ARRIBA en el MiniPlayer para abrir el reproductor. Dispara al cruzar el umbral
 * sin esperar a que el dedo se levante: la píldora no se mueve durante el gesto (es una barra de
 * 72dp, no hay recorrido que enseñar), así que esperar al release dejaría el gesto sin respuesta
 * hasta el final y se sentiría roto.
 */
@Composable
fun Modifier.miniPlayerExpandDrag(enabled: Boolean, onExpand: () -> Unit): Modifier {
    if (!enabled) return this
    val thresholdPx = with(LocalDensity.current) { PlayerGestureConfig.ExpandThreshold.toPx() }
    val currentOnExpand by rememberUpdatedState(onExpand)
    return pointerInput(thresholdPx) {
        var travel = 0f
        var fired = false
        detectVerticalDragGestures(
            onDragStart = {
                travel = 0f
                fired = false
            },
            onVerticalDrag = { change, dragAmount ->
                travel += dragAmount
                if (!fired && travel <= -thresholdPx) {
                    fired = true
                    change.consume()
                    currentOnExpand()
                }
            }
        )
    }
}
