package com.qhana.siku.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import com.qhana.siku.ui.theme.appFastSpatialSpec

/**
 * Radio de las esquinas INTERIORES de un grupo: `ListTokens.ItemContainerExpressiveShape`
 * (CornerExtraSmall) en la versión pineada de Material 3.
 */
private val InteriorRadius = 4.dp

/**
 * Radio de las esquinas que cierran el grupo, y también el de una fila REALZADA: coinciden en el
 * spec — `ListTokens.ContainerShape`, `ItemSelectedContainerExpressiveShape` y
 * `ReorderListTokens.ItemShape` valen los tres CornerLarge.
 */
private val ProminentRadius = 16.dp

/**
 * Forma de un ítem dentro de una LISTA AGRUPADA de M3: esquinas pronunciadas en los extremos del
 * grupo y pequeñas en los del medio, de modo que el bloque se lea como una pieza continua. Una fila
 * REALZADA ([isActive], la canción cargada) redondea entera, sin importar su posición.
 *
 * Es un `remember` puro, sin animación, y eso es deliberado: esto lo llaman las listas con scroll
 * —"Todas" la primera—, donde cada fila que entra por reciclaje pagaría lo que costase componer
 * aquí. Lo único que cambia de forma en ellas es la canción activa, una vez cada varios minutos y
 * acompañada de un cambio de color que ya tapa el salto.
 *
 * Para una lista REORDENABLE, donde sí hace falta interpolar, está
 * [rememberReorderableListItemShape].
 */
@Composable
fun rememberListItemShape(
    index: Int,
    count: Int,
    isActive: Boolean = false
): Shape = remember(index, count, isActive) {
    groupCornerRadii(index, count, highlighted = isActive).asRoundedCornerShape()
}

/**
 * Como [rememberListItemShape] pero para una lista REORDENABLE, donde la fila también se realza
 * mientras está agarrada ([isDragging]) — el `draggedShape` del spec: levantada, deja de pertenecer
 * al bloque y no debe conservar las esquinas con las que encajaba entre unos vecinos que ya no
 * tiene debajo.
 *
 * Aquí el realce **se INTERPOLA**, con el mismo token de motion que usa por dentro
 * `ListItemShapes.shapeForInteraction` en Material 3 (`fastSpatial`, o sea un spring del scheme de
 * la app): sin eso, agarrar una fila del medio le pega un tirón de 12dp a las cuatro esquinas en un
 * solo frame. Es **UNA sola animación por fila** y no una por esquina — el estado es binario y los
 * dos extremos se conocen, así que un único progreso los describe.
 *
 * Existe aparte y no como un flag de la otra porque el coste (un `Animatable` más una corrutina por
 * fila) solo se justifica en listas cortas que se arrastran, y un booleano que enciende estado
 * propio de un composable es justo lo que no conviene tener en el camino del scroll.
 */
@Composable
fun rememberReorderableListItemShape(
    index: Int,
    count: Int,
    isActive: Boolean = false,
    isDragging: Boolean = false
): Shape {
    // Forma de REPOSO: la que le toca por su sitio en el grupo. Solo depende de la posición.
    val rest = remember(index, count) { groupCornerRadii(index, count, highlighted = false) }

    // Cuánto se ha despegado del grupo: 0 = en su sitio, 1 = realzada (sonando o levantada).
    val highlight by animateFloatAsState(
        targetValue = if (isActive || isDragging) 1f else 0f,
        animationSpec = appFastSpatialSpec<Float>(),
        label = "listItemHighlight"
    )

    return remember(rest, highlight) {
        CornerRadii(
            topStart = lerp(rest.topStart, ProminentRadius, highlight),
            topEnd = lerp(rest.topEnd, ProminentRadius, highlight),
            bottomStart = lerp(rest.bottomStart, ProminentRadius, highlight),
            bottomEnd = lerp(rest.bottomEnd, ProminentRadius, highlight)
        ).asRoundedCornerShape()
    }
}

/** El reparto de esquinas del grupo, compartido por las dos funciones de arriba. */

/**
 * Los `ListItemShapes` de una fila dentro de un grupo segmentado, para los `SegmentedListItem` de
 * M3 (que resuelven la forma ellos, a diferencia de [rememberListItemShape], que la calcula para
 * superficies propias).
 *
 * **Existe por el caso de UNA sola fila.** `ListItemDefaults.segmentedShapes` devuelve ahí el
 * `defaultShapes` tal cual (`ListItem.kt`, rama `count == 1`), o sea el radio INTERIOR de 4dp: una
 * tarjeta suelta con esquinas de fila del medio, que se lee cuadrada al lado de todo lo demás. Un
 * grupo de uno no tiene vecinos con los que encajar, así que sus cuatro esquinas son exteriores —
 * es el mismo criterio que [rememberListItemShape] ya aplica en `groupCornerRadii`.
 *
 * El `pressedShape` sigue siendo el del spec: solo se sustituye la forma en reposo.
 */
@Composable
fun groupedListItemShapes(index: Int, count: Int): ListItemShapes =
    ListItemDefaults.segmentedShapes(
        index = index,
        count = count,
        defaultShapes = if (count == 1) {
            ListItemDefaults.shapes(shape = RoundedCornerShape(ProminentRadius))
        } else {
            ListItemDefaults.shapes()
        }
    )
private fun groupCornerRadii(index: Int, count: Int, highlighted: Boolean): CornerRadii = when {
    highlighted || count == 1 ->
        CornerRadii(ProminentRadius, ProminentRadius, ProminentRadius, ProminentRadius)
    index == 0 ->
        CornerRadii(ProminentRadius, ProminentRadius, InteriorRadius, InteriorRadius)
    index == count - 1 ->
        CornerRadii(InteriorRadius, InteriorRadius, ProminentRadius, ProminentRadius)
    else ->
        CornerRadii(InteriorRadius, InteriorRadius, InteriorRadius, InteriorRadius)
}

/** Los cuatro radios de una fila, para poder interpolarlos hacia la forma realzada. */
private data class CornerRadii(
    val topStart: Dp,
    val topEnd: Dp,
    val bottomStart: Dp,
    val bottomEnd: Dp
) {
    /**
     * NO se llama `toShape`: `androidx.compose.material3.toShape` es la extensión `@Composable` de
     * `RoundedPolygon`, y dos funciones con ese nombre en el mismo tema invitan a envolver ésta en
     * un `remember` (legal aquí, ilegal allí) o al revés. El nombre dice qué construye.
     */
    fun asRoundedCornerShape(): Shape = RoundedCornerShape(
        topStart = topStart,
        topEnd = topEnd,
        bottomStart = bottomStart,
        bottomEnd = bottomEnd
    )
}
