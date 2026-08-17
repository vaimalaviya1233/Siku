package com.qhana.siku.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onPlaced
import com.qhana.siku.data.util.JankProbe
import com.qhana.siku.ui.ContainerOriginRole
import com.qhana.siku.ui.LocalRowOrigin
import com.qhana.siku.ui.appSharedTransitionScope
import com.qhana.siku.ui.theme.AppContainerBoundsTransform
import com.qhana.siku.ui.theme.appContainerContentEnter
import com.qhana.siku.ui.theme.appContainerContentExitFast

/**
 * Punta **ORIGEN** de un *container transform*: la superficie de la que CRECE una pantalla y a la que
 * vuelve al cerrarse. Envuelve a [content] y no le pide nada — el contenido no sabe que participa.
 *
 * El destino (la pantalla que crece) declara el otro extremo con la misma [key]; hoy lo hace
 * `NowPlayingScreen` a través de su `containerSharedKey`. La configuración del morph vive AQUÍ y en
 * esa pantalla, y las dos tienen que coincidir: **`scaleToBounds(Fit, Center)` en las DOS puntas**
 * (un `ContentScale` distinto en cada lado fue la causa del "slide up" que costó el 16 ago entero),
 * [AppContainerBoundsTransform] como spec de bounds y la escala de z de
 * [CONTAINER_SHADOW_OVERLAY_Z].
 *
 * ## Sin papel, esto es un [Box] y nada más
 *
 * Con [role] `null` la superficie no declara NADA: ni shared element, ni `Transition`, ni nodo extra.
 * Es el camino caliente de toda lista —las diez filas visibles y las que el scroll recicla— y tiene
 * que costar lo mismo que un `Box`. Hasta el 16 ago cada fila declaraba su punta SIEMPRE (un
 * `AnimatedVisibility` + un `sharedBounds` + la punta de su carátula), porque una punta que nace en el
 * frame del tap no tiene bounds que ofrecer y no hay match; el precio era que el `SharedTransitionScope`
 * llevaba dos entradas por fila visible —cada alta y baja recorre TODAS las entradas
 * (`updateTransitionActiveness`) e invalida el dibujo de la RAÍZ (`renderers` es una lista de
 * snapshot que el overlay lee en cada `draw`)—, y eso se notaba como pérdida de fps al scrollear.
 * La punta ahora se declara **un frame antes de hacer falta**, no siempre: ver [ContainerOriginRole]
 * y `MusicAppState.openPlayer`.
 *
 * ## Con papel: la superficie declara su punta con visibilidad PROPIA, no la de un `AnimatedVisibility`
 *
 * `sharedBounds` pide un `AnimatedVisibilityScope` solo para dos cosas: saber si esta punta es la
 * visible (destino) o la oculta (origen), y hacer el fundido de su contenido. Las dos salen de una
 * `Transition<EnterExitState>` que aquí gobierna [role] directamente ([OriginVisibilityScope]), y eso
 * es lo que permite que la superficie se OCULTE sin salir de la composición:
 *
 * **1. Se oculta mientras vuela** ([ContainerOriginRole.HIDDEN]). No es un efecto: Compose exige que
 * de las dos puntas de una key solo UNA sea destino, y una superficie que se queda visible siempre lo
 * es — con dos destinos no hay animación ninguna, el contenido aparece quieto en su posición final. Es
 * además lo que hace `MaterialContainerTransform`: esconde la vista de origen durante la transición.
 *
 * **2. Pero conserva su ESPACIO.** Material oculta el origen con `View.INVISIBLE`, no con `GONE`: si
 * la fila desapareciera del layout, la lista de debajo saltaría hacia arriba al terminar el morph y
 * volvería a bajar —animada, con `animateItem`— justo mientras la pantalla se contrae y la deja ver
 * otra vez. Aquí el contenido se queda compuesto y medido y solo se FUNDE a alfa cero (el `exit` del
 * contenido, [appContainerContentExitFast]); su `placeholderSize` sigue reportando el tamaño de
 * siempre. Antes esto exigía recordar el alto medido y reponerlo cuando el hijo se descomponía.
 *
 * **3. La punta dura lo que el morph.** El bounds es una animación diferida de la misma `Transition`
 * que el fundido, y una `Transition` corre hasta que su animación más larga termina: el contenido se
 * funde rápido —la superficie chica se escala hacia arriba y se pixela, hay que quitarla de la vista
 * antes de que el mosaico se note— y el bounds sigue hasta el final. La ENTRADA del contenido es
 * [appContainerContentEnter] (`None`): al volver, la superficie tiene que estar en su sitio desde el
 * primer frame para servir de destino, y el único que mueve algo es el morph.
 *
 * Sin `SharedTransitionScope` (hojas y diálogos, donde se anula a propósito) esto es un [Box] pase lo
 * que pase.
 *
 * @param key key del elemento compartido; la misma que declara el destino.
 * @param role papel de esta superficie en el morph AHORA (null = ninguno). Ver [ContainerOriginRole].
 * @param modifier lo que posiciona la superficie en su lista (padding, `animateItem`, ancho). Va por
 *   fuera del elemento compartido a propósito: lo que morfa es la superficie, no su hueco.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun ContainerTransformOrigin(
    key: Any,
    role: ContainerOriginRole?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    // `appSharedTransitionScope()` y no el local a pelo: dentro de un diálogo o una hoja (que son
    // otra ventana) el scope entrega coordenadas de otro árbol de layout y usarlo CRASHEA.
    val sharedScope = appSharedTransitionScope()
    val originModifier = if (sharedScope == null || role == null) {
        Modifier
    } else {
        // Visible = destino (preparándose o recibiendo el cierre); PostExit = origen oculto. Es la
        // misma máquina de tres estados que usa AnimatedVisibility por dentro, gobernada por [role]
        // en vez de por un `visible` que además descompone el contenido al terminar.
        val visibility = updateTransition(
            targetState = if (role == ContainerOriginRole.HIDDEN) EnterExitState.PostExit
            else EnterExitState.Visible,
            label = "containerOrigin"
        )
        val visibilityScope = remember(visibility) { OriginVisibilityScope(visibility) }
        // El que SALE se dibuja ENCIMA y se disuelve sobre el que ENTRA, sólido debajo: es lo que
        // hace que abrir se lea como CRECER y no como un revelado. Ver la escala de z completa.
        val exiting = visibility.targetState != EnterExitState.Visible
        with(sharedScope) {
            Modifier.sharedBounds(
                sharedContentState = rememberSharedContentState(key = key),
                animatedVisibilityScope = visibilityScope,
                boundsTransform = AppContainerBoundsTransform,
                enter = appContainerContentEnter(),
                exit = appContainerContentExitFast(),
                resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(
                    ContentScale.Fit,
                    Alignment.Center
                ),
                zIndexInOverlay =
                    if (exiting) CONTAINER_SURFACE_OVERLAY_Z_EXITING
                    else CONTAINER_SURFACE_OVERLAY_Z_ENTERING
            )
        }
    }
    // Un solo Box, se tenga papel o no: cambiar de papel cambia su cadena de modifiers, NUNCA la
    // estructura. Si el papel envolviera el contenido en otro composable, prepararse recrearía la
    // fila entera —carátula incluida— en el mismo frame en que tiene que ofrecer sus bounds.
    Box(modifier = modifier.then(originModifier)) { content() }
}

/**
 * Un `AnimatedVisibilityScope` cuya única función es prestarle a `sharedBounds` la `Transition` de
 * visibilidad que gobierna [ContainerTransformOrigin]. La interfaz solo tiene abstracto `transition`;
 * `animateEnterExit` trae su implementación y aquí no lo usa nadie.
 */
private class OriginVisibilityScope(
    override val transition: Transition<EnterExitState>
) : AnimatedVisibilityScope

/**
 * [ContainerTransformOrigin] para una FILA de canción: la fila crece hasta ser el reproductor (ver
 * [rowContainerSharedKey]).
 *
 * **No hace falta pasarle ningún id de "quién abrió el player"**: la fila origen es SIEMPRE la de la
 * canción activa, porque `MusicController.announceSelection` la fija en el frame del tap. Así que
 * cada fila decide por su cuenta preguntando a [LocalRowOrigin] qué papel le toca, sin que ningún
 * callback cargue con el dato. De regalo, si el usuario cambia de canción DENTRO del reproductor, al
 * cerrar la superficie aterriza en la fila que AHORA suena, que es lo coherente.
 *
 * El `derivedStateOf` no es adorno: el origen cambia al preparar, al abrir y al cerrar, y leerlo a
 * pelo recompondría TODAS las filas visibles en ese mismo frame —el que arranca la transición y el que
 * la termina, justo donde se notaba el tirón—. Así solo recompone aquella cuyo papel cambia.
 *
 * La fila además AVISA cada vez que queda COLOCADA (`onRowPlaced`, un `onPlaced` que Compose dispara
 * en cada colocación, no solo cuando cambia la posición — `MeasurePassDelegate.onNodePlaced`) y cuando
 * se va (`onRowDisposed`). Con eso `MusicAppState` mantiene el conjunto de filas que están DE VERDAD en
 * pantalla, y `openPlayer` sabe en el instante del tap, sin esperar a nada, si hay una fila que pueda
 * ser origen — el play de una cabecera arranca por la primera canción, que puede estar fuera de
 * pantalla, y entonces el reproductor se abre sin origen en vez de esperar a una fila que no existe.
 * Se registra al COLOCARSE y no al componerse a propósito: el prefetch de `LazyColumn` compone (y
 * corre los efectos de) filas que aún no se ven, y una fila compuesta pero no colocada nunca daría la
 * señal con la que se abre. La misma señal es la que, en la fila en preparación, dispara la apertura.
 */
@Composable
fun SongRowContainer(
    songId: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    // Sin scope (hojas, diálogos, la ventana de búsqueda) la fila no puede ser origen de nada: ni
    // se registra ni pregunta por su papel.
    if (appSharedTransitionScope() == null) {
        Box(modifier) { content() }
        return
    }
    val rowOrigin = LocalRowOrigin.current
    val role by remember(songId, rowOrigin) { derivedStateOf { rowOrigin.roleOf(songId) } }
    DisposableEffect(songId, rowOrigin) {
        onDispose { rowOrigin.onRowDisposed(songId) }
    }
    // Sonda (solo con la sonda activa): cuántas filas se componen antes de un frame largo.
    SideEffect { JankProbe.mark { "fila compuesta ${songId.takeLast(12)}${role?.let { " [ORIGEN $it]" } ?: ""}" } }
    ContainerTransformOrigin(
        key = rowContainerSharedKey(songId),
        role = role,
        // Por FUERA de la punta compartida: dispara en la colocación de la fila, en el mismo pase de
        // layout en que la punta de dentro queda colocada. Lo que hace con el aviso es escribir
        // estado, y eso se consume en el frame siguiente — con la punta ya medida y puesta en este.
        modifier = modifier.onPlaced { rowOrigin.onRowPlaced(songId) },
        content = content
    )
}
