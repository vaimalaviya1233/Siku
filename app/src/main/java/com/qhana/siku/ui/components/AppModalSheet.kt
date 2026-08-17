package com.qhana.siku.ui.components

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import com.qhana.siku.ui.theme.AppMotionScheme
import kotlinx.coroutines.launch

/**
 * `ModalBottomSheet` de M3 que entra y sale **sin rebote**, como pide el patrón *enter and exit*.
 *
 * ## Por qué existe
 *
 * Una hoja modal es una superficie que se pone ENCIMA de la UI principal, no un nivel del grafo de
 * navegación, y para eso el spec reserva el patrón *enter and exit*: easing + duración, nunca la
 * física de un componente. El `ModalBottomSheet` de material3 no expone `animationSpec` — su motion
 * lo resuelve por dentro leyendo el `MotionScheme` del tema, que en esta app es `expressive()`, o
 * sea springs SUBAMORTIGUADOS. Resultado: la hoja del temporizador (y todas las demás) llegaban
 * dando un rebote que el patrón no contempla.
 *
 * La alternativa era reimplementar cada hoja como overlay propio con `AnimatedVisibility` —lo que se
 * hizo con el ecualizador— y eso obliga a rehacer a mano el scrim, el cierre al tocar fuera, el
 * arrastre, los insets y la semántica. Aquí se ataca el mismo punto por donde el componente lo lee:
 * **el tema**. Sin reimplementar nada y sin poder desincronizarse de material3 el día que cambie su
 * animación por dentro.
 *
 * ## Los dos temas anidados NO son un truco
 *
 * [SheetMotionScheme] —springs sin rebote y más blandos, ver su kdoc— se aplica SOLO al contenedor.
 *
 * El CONTENIDO recupera [AppMotionScheme] enseguida, y eso es deliberado: lo que no debe rebotar es
 * la hoja al llegar, no los botones, chips y toggles de dentro — esos son componentes y su física
 * expressive es justamente lo que la app usa en todas partes. Sin el segundo tema, abrir una hoja
 * apagaría en silencio el motion de todo lo que contiene.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppModalSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    // `skipPartiallyExpanded = true` por DEFAULT, y es lo que arregla el "hay que dar atrás dos
    // veces para cerrar". El `settleToDismiss` de `ModalBottomSheet` (lo que corre al hacer back)
    // COLAPSA a media altura en vez de cerrar cuando la hoja está `Expanded` y tiene estado parcial
    // (`if (currentValue == Expanded && hasPartiallyExpandedState) partialExpand() else hide()`); con
    // contenido alto —una lista de candidatos, p.ej.— el primer back solo baja el detent y hace falta
    // un segundo para cerrar. Sin estado parcial, back siempre cierra en un gesto. Todas las hojas de
    // la app son listas/opciones que abren enteras: ninguna quiere el medio-detent. Dos hojas ya lo
    // forzaban a mano (AddSongsToPlaylist, OneDriveFolderPicker) — ahora es el default para todas.
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    content: @Composable ColumnScope.() -> Unit
) {
    val scope = rememberCoroutineScope()
    // Cierre ANIMADO para los botones de dentro (ver [LocalSheetCloser]).
    val closer: SheetCloser = remember(sheetState, scope) {
        { action ->
            scope.launch { sheetState.hide() }.invokeOnCompletion {
                // La guarda importa: si el usuario vuelve a abrir la hoja mientras se ocultaba, o
                // el `hide()` se cancela, la acción NO debe correr — cerraría algo que sigue ahí.
                if (!sheetState.isVisible) action()
            }
        }
    }
    MaterialTheme(motionScheme = SheetMotionScheme) {
        ModalBottomSheet(
            onDismissRequest = onDismissRequest,
            modifier = modifier,
            sheetState = sheetState
        ) {
            // `this` es el ColumnScope de la hoja; se captura porque el MaterialTheme de dentro lo
            // dejaría fuera de alcance y el contenido lo necesita (weights, aligns).
            val sheetScope = this
            MaterialTheme(motionScheme = AppMotionScheme) {
                CompositionLocalProvider(LocalSheetCloser provides closer) {
                    with(sheetScope) { content() }
                }
            }
        }
    }
}

/**
 * Rigideces del [SheetMotionScheme]. Son springs porque una hoja se ARRASTRA: al soltarla con
 * impulso, un spring continúa desde la velocidad que traía el dedo y un tween la ignoraría, llevando
 * la hoja al destino en tiempo fijo — el flick dejaría de significar nada.
 *
 * Los valores son los tres peldaños de `Spring` de Compose, elegidos un escalón POR DEBAJO de lo
 * habitual en un componente: una hoja recorre el alto de la pantalla, y a esa distancia la rigidez
 * que se siente bien en un botón se lee como un corte. `StiffnessLow` (200) para el caso normal
 * asienta en algo más de medio segundo, que es lo que el usuario echaba en falta al cerrarlas.
 */
private const val SHEET_STIFFNESS_FAST = Spring.StiffnessMediumLow
private const val SHEET_STIFFNESS_DEFAULT = Spring.StiffnessLow
private const val SHEET_STIFFNESS_SLOW = Spring.StiffnessVeryLow

/**
 * `MotionScheme` propio para las hojas modales: **springs SIN REBOTE y más blandos** que los del
 * tema de la app.
 *
 * Sustituye al `MotionScheme.standard()` que había aquí, y por dos motivos que se descubrieron por
 * separado: `standard()` **sigue rebotando** un poco (sus springs espaciales no están críticamente
 * amortiguados, solo menos que los de `expressive()`), y su velocidad no se puede tocar — es la que
 * es. Con un scheme propio las dos cosas quedan bajo control sin reimplementar el componente, que
 * era la única alternativa: `ModalBottomSheet` no expone `animationSpec`, resuelve su motion
 * leyendo el scheme del tema, y por ahí es por donde se le habla.
 *
 * Los **effects** se delegan tal cual a [AppMotionScheme]: esos ya son críticamente amortiguados en
 * el scheme expressive, así que no hay nada que corregir y conviene que un fundido dentro de una
 * hoja dure lo mismo que en el resto de la app.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private object SheetMotionScheme : MotionScheme {
    private fun <T> flat(stiffness: Float): FiniteAnimationSpec<T> =
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = stiffness)

    override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = flat(SHEET_STIFFNESS_DEFAULT)
    override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = flat(SHEET_STIFFNESS_FAST)
    override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> = flat(SHEET_STIFFNESS_SLOW)

    override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = AppMotionScheme.defaultEffectsSpec()
    override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = AppMotionScheme.fastEffectsSpec()
    override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> = AppMotionScheme.slowEffectsSpec()
}

/** Ver [LocalSheetCloser]. Ejecuta [action] cuando la hoja ha terminado de ocultarse. */
typealias SheetCloser = (action: () -> Unit) -> Unit

/**
 * Cómo cierra una hoja **desde dentro** (un botón de "Añadir", elegir una opción de una lista…).
 *
 * ## Por qué hace falta
 *
 * `ModalBottomSheet` anima su salida cuando el gesto lo pide —arrastrar hacia abajo, tocar fuera,
 * atrás—, pero un botón del contenido normalmente apaga el flag que MONTA la hoja, y eso la quita
 * del árbol de composición en el acto: desaparece de golpe, sin animación ninguna. Era el caso de
 * "añadir canciones", que el usuario describió como que se va "muy rápido" — y no era ni una
 * duración corta ni un número mágico, era que no había animación que durase.
 *
 * El patrón correcto de M3 es `sheetState.hide()` (que SUSPENDE hasta terminar) y solo después
 * apagar el flag. Esto lo envuelve para que ninguna hoja tenga que acordarse:
 *
 * ```
 * val close = LocalSheetCloser.current
 * Button(onClick = { close { onConfirm(seleccion) } })
 * ```
 *
 * El default ejecuta la acción directamente, así que un contenido usado fuera de una hoja sigue
 * funcionando (sin animación, que es lo correcto ahí).
 */
val LocalSheetCloser = staticCompositionLocalOf<SheetCloser> { { action -> action() } }
