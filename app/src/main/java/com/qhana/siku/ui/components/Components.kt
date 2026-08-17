package com.qhana.siku.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.qhana.siku.ui.theme.EXPRESSIVE_DEFAULT_EFFECTS_MS
import com.qhana.siku.ui.theme.EXPRESSIVE_FAST_EFFECTS_MS
import com.qhana.siku.ui.theme.ExpressiveDefaultEffectsEasing
import com.qhana.siku.ui.theme.ExpressiveFastEffectsEasing
import java.util.concurrent.TimeUnit
import androidx.compose.ui.unit.dp

// ============== CONFIGURACIÓN ==============

internal object ComponentConfig {
    // 20dp = tamaño de icono de list item del spec de M3, el MISMO con el que la cola dibuja su
    // indicador de descarga (QueueRowActionIconSize). Estuvo en 18 ("compacto de biblioteca") y el
    // mismo glifo `offline_pin` salía más pequeño en la lista que en la cola — dos tamaños para el
    // mismo indicador en dos pantallas.
    val StatusIconSize = 20.dp

    /**
     * Alto del header inmersivo de las pantallas de detalle (artista, álbum, género, lista) y
     * recorrido de scroll en el que el título viaja de ahí a la topbar.
     *
     * Viven juntos y compartidos porque son UNA decisión de diseño replicada en cuatro pantallas —
     * el patrón documentado en CLAUDE.md—, y estaban escritos a mano en tres de ellas. La cuarta
     * (género) sí los tenía nombrados, con un KDoc que decía "igual que en artista/álbum": una
     * relación afirmada en un comentario que nada obligaba a cumplir, que es como se desincronizan.
     *
     * El fade es MENOR que el alto a propósito: el título termina de pasar a la topbar antes de que
     * el header salga del todo, así que nunca hay un instante sin título en pantalla.
     */
    val DetailHeaderHeight = 380.dp
    val DetailTitleFadeRange = 300.dp
    val SongItemCornerRadius = 8.dp
    val SongItemIconSize = 56.dp
    val SongItemIconCorner = 28.dp
    val ThumbnailSize = 156

    /**
     * Lado en píxeles al que se decodifica la carátula GRANDE del reproductor (`NowPlayingArtImage`),
     * y el MISMO al que `PlaybackViewModel.preloadVisualArt` la precalienta al cambiar de canción.
     *
     * Un solo número a propósito: la precarga y la petición del player pedían tamaños DISTINTOS
     * (900 y 800), así que en el mismo instante corrían DOS decodificaciones del mismo JPEG en
     * paralelo —una por petición, Coil no fusiona peticiones concurrentes— y ninguna servía a la
     * otra hasta terminar, justo mientras arranca el container transform. Con el mismo tamaño la
     * precarga es exactamente lo que el player pide.
     */
    val NowPlayingArtDecodePx = 800
    // --- Capa flotante del bottom (solo el MiniPlayer), specs floating toolbar M3 Expressive ---
    // ContainerHeight del floating toolbar del spec (FloatingToolbarTokens). Lo usa la barra de
    // acciones del NowPlaying, que DEBE forzarlo (la alpha18 no lo respeta sola). Vivía pegado a
    // MiniPlayerHeight por coincidencia de valor: son cosas distintas y ya divergen.
    val FloatingToolbarHeight = 64.dp
    // 80dp: subió de 72 para que el anillo de progreso ONDULADO que rodea la carátula tenga banda
    // donde respirar. Sigue leyéndose como barra y no como panel, y los botones de 48dp caben de sobra.
    val MiniPlayerHeight = 80.dp
    // --- Anillo ondulado de progreso alrededor de la carátula (ver MiniPlayer.kt) ---
    // La geometría se define de AFUERA hacia adentro para FIJAR el respiro con el contenedor:
    // padding al pill → tamaño del anillo → carátula. Así el padding superior/inferior queda EXACTO
    // sea cual sea la banda del anillo (antes se derivaba al revés —carátula → anillo— y el margen
    // al pill era lo que sobraba, distinto según el trazo/separación).
    // Respiro visible del anillo al contenedor (pill), arriba y abajo.
    val MiniPlayerRingContainerPadding = 8.dp
    // Lado del anillo (y de su Box): el alto menos el padding al contenedor a cada lado.
    val MiniPlayerRingSize = MiniPlayerHeight - MiniPlayerRingContainerPadding * 2
    // Trazo del anillo y separación anillo→carátula = la "banda" del anillo. Como el tamaño del
    // anillo y el padding al pill están FIJOS, subir el gap encoge la carátula (más aire entre
    // portada y onda a cambio de portada más chica).
    val MiniPlayerRingStroke = 4.dp
    val MiniPlayerRingGap = 10.dp
    // Carátula: el anillo menos su banda (trazo + separación) a cada lado.
    val MiniPlayerArtSize = MiniPlayerRingSize - (MiniPlayerRingStroke + MiniPlayerRingGap) * 2
    // Diámetro/lado de los controles de transporte. 48dp = mínimo táctil recomendado.
    val MiniPlayerButtonSize = 48.dp
    // Aire A AMBOS LADOS del bloque de texto (carátula→texto y texto→transporte): el gap
    // leading→texto de los ítems de lista, para que el mini se lea como una fila más de la
    // biblioteca y no como otro componente. Se usa también a la derecha para que el título no
    // llegue pegado al play — el gap de los botones (4dp) es demasiado poco ahí. El texto NO lleva
    // ancho propio: la Column va con weight(1f) y se come todo lo que sobre entre ambos gaps.
    val MiniPlayerTextGap = 12.dp
    // Separación entre las dos líneas del bloque de texto (título / artista).
    val MiniPlayerTextLineGap = 2.dp
    // Padding interno del container y gap entre sus botones de acción.
    val FloatingBarInnerPadding = 8.dp
    val FloatingBarItemGap = 4.dp
    // Márgenes de la capa respecto a la pantalla: 16dp sobre la navbar del sistema y a los lados.
    val FloatingBarBottomMargin = 16.dp
    val FloatingBarSideMargin = 16.dp
    // Espacio total que la capa reserva bajo las listas (sobre la navbar): margen + alto + colchón.
    // Se usa como contentPadding inferior para que el último ítem no quede tapado. DERIVADO del
    // alto real de la barra: si el MiniPlayer crece y esto no, la capa flotante se come el
    // último ítem de cada lista.
    val FloatingBarListInset = FloatingBarBottomMargin + MiniPlayerHeight + 16.dp

    val SearchBarHeight = 56.dp

    // --- Barra de progreso del NowPlaying (ProgressSlider) ---
    // El GROSOR es ajustable (Ajustes → Apariencia) y de él sale toda la geometría que escala con
    // él: ver [ProgressMetrics]. Aquí quedan el default y lo que NO depende del grosor.
    //
    // El alto lo comparten los DOS modos (píldora plana y onda): si divergen, alternar el
    // ajuste de Apariencia movería el layout de todo el bloque de controles.
    val ProgressTrackHeight = 12.dp
    // Extremos del ajuste de grosor. Abajo, 6dp deja el trazo de la onda en 2dp (el mínimo que se
    // sigue leyendo a densidad baja); arriba, 24dp es el alto de la zona táctil, o sea el punto en
    // que el dibujo ya ocupa todo el control y engordar más solo empujaría el layout.
    val ProgressTrackHeightMin = 6.dp
    val ProgressTrackHeightMax = 24.dp
    // Paso del ajuste. 2dp: por debajo, dos valores contiguos no se distinguen en pantalla y el
    // slider tendría paradas que no cambian nada.
    val ProgressTrackHeightStep = 2.dp
    // Distancia entre crestas. Más corta = onda más "nerviosa"; más larga = casi recta. Subirla NO
    // acelera la onda: la velocidad de desplazamiento es su propio token (ProgressWaveSpeed) y el
    // periodo se deriva de los dos. NO escala con el grosor: es una longitud HORIZONTAL, y atarla
    // al grosor cambiaría el ritmo de la onda al engordarla.
    val ProgressWaveLength = 30.dp
    // Velocidad a la que se desplaza la onda, en dp por segundo. Fijar ESTO en vez del periodo del
    // ciclo es lo que permite retocar la longitud de onda sin tocar de paso el ritmo: con el periodo
    // fijo en 1 s, ensanchar la onda le hacía recorrer más distancia en el mismo tiempo.
    val ProgressWaveSpeed = 22.dp
    // Handle (el "palito" vertical del slider Expressive), COMPARTIDO por los dos modos: aparece al
    // arrastrar. Antes la onda lo llevaba PERMANENTE; se retiró (decisión del usuario) y ahora los
    // dos modos se comportan igual: en reposo no hay palo, y al buscar sí.
    // El ancho es el del spec (4dp) y NO escala con el grosor: es el objeto de M3, no una parte del
    // riel. Su alto sí (ver [ProgressMetrics.handleHeight]).
    val ProgressHandleWidth = 4.dp
    // Hueco entre el progreso y lo que venga después (el palo al buscar, el riel apagado siempre).
    // ES lo que hace legible el handle, y por eso el palo puede ser del color del ACENTO en vez de
    // un blanco/negro ajeno a la paleta: se separa por geometría (este hueco + su altura), no por
    // contraste de color. Mismo valor en los dos modos, para que se lean idénticos.
    //
    // 6dp = `SliderTokens.ActiveHandleLeadingSpace`, el hueco que el spec da a un SLIDER. Estuvo en
    // 4 (el `TrackActiveSpace` del progress indicator, que es otro componente: sin thumb y más
    // fino) y ahí el aire se leía corto.
    val ProgressHandleGap = 6.dp
    // Radio del extremo INTERIOR de cada tramo: el borde del fill que da al hueco y el arranque del
    // riel. `SliderTokens`/`TrackInsideCornerSize` de material3. No es 0 (un corte recto) ni el
    // radio completo: con la punta redonda entera el fill deja "hombros" de riel asomando a los
    // lados del palo —6dp de radio contra 4 de palo—, y ese fue el motivo de cortarlo recto; 2dp es
    // lo que hace el Slider oficial y no produce hombros.
    val ProgressTrackInsideCorner = 2.dp
    // Cuánto se mete el stop indicator desde el borde derecho, COMO MÁXIMO. Es el
    // `StopIndicatorTrailingSpace` de material3 (6dp, el mismo valor en SliderTokens), y existe por
    // el grosor ajustable: sin tope, el inset es media altura del track —lo que hace el `Slider`,
    // cuyo track es fijo— y con la barra en 24dp el punto se hundiría 12dp adentro. Con 12dp de
    // grosor el tope no muerde y el resultado es idéntico al de antes.
    val ProgressStopIndicatorTrailingSpace = 6.dp
    // Alto MÍNIMO de la zona de gesto de la barra (el bloque que responde al arrastre y al toque).
    // Es MAYOR que el track por defecto: el objetivo táctil de un control no es su dibujo. Con un
    // grosor mayor manda el track (ver [ProgressMetrics.touchHeight]).
    val ProgressTouchHeight = 24.dp
    // Cuánto asoma el handle por fuera del track, a cada lado. Es lo que lo distingue del riel, así
    // que se suma al grosor en vez de ser un alto fijo: con la barra gruesa un palo de alto fijo
    // quedaría embebido en ella.
    val ProgressHandleOvershoot = 7.dp
    // Gota con el tiempo que flota sobre el handle al arrastrar: cuadrado rotado 45° (de ahí que
    // el lado sea menor que su diagonal aparente) y cuánto se separa del centro de la barra.
    val ProgressBubbleSize = 46.dp
    val ProgressBubbleOffset = 52.dp
}

/**
 * Geometría de la barra de progreso del NowPlaying DERIVADA del grosor elegido en Ajustes →
 * Apariencia. Existe para que el ajuste sea un solo número: todo lo que tiene que crecer con la
 * barra sale de proporciones fijas sobre él, en vez de ser media docena de tokens que el usuario
 * tendría que mantener coherentes a mano (o que quedarían clavados en su valor de 12dp y
 * desproporcionados en cuanto la barra engorda).
 *
 * Las proporciones son EXACTAMENTE las del diseño actual con 12dp, así que el default no cambia ni
 * un píxel: trazo 4, amplitud 3, indicador 4, handle 26.
 */
internal data class ProgressMetrics(
    /** Alto de la píldora plana / alto útil de la onda. Es el valor que elige el usuario. */
    val trackHeight: Dp,
    /** Grosor del trazo de la onda; el mismo que el diámetro del stop indicator. */
    val waveStroke: Dp,
    /** Desviación máxima de la onda respecto al centro. */
    val waveAmplitude: Dp,
    /** Diámetro del stop indicator del final del riel. */
    val stopIndicator: Dp,
    /** Alto del palo del handle: el track más lo que asoma por arriba y por abajo. */
    val handleHeight: Dp,
    /** Alto de la zona de gesto: nunca menor que el mínimo táctil ni que el propio dibujo. */
    val touchHeight: Dp
)

/**
 * Construye la geometría de la barra para un [trackHeight] dado, acotándolo al rango del ajuste
 * (una preferencia vieja o corrupta no debe poder dibujar una barra de 0dp ni de 200).
 *
 * Amplitud = alto/4 y trazo = alto/3, así que las crestas caben siempre dentro del contenedor:
 * el margen disponible es (alto − trazo)/2 = alto/3, mayor que la amplitud para cualquier alto.
 */
internal fun progressMetricsFor(trackHeight: Dp): ProgressMetrics {
    val height = trackHeight.coerceIn(
        ComponentConfig.ProgressTrackHeightMin,
        ComponentConfig.ProgressTrackHeightMax
    )
    val stroke = height / WAVE_STROKE_DIVISOR
    return ProgressMetrics(
        trackHeight = height,
        waveStroke = stroke,
        waveAmplitude = height / WAVE_AMPLITUDE_DIVISOR,
        stopIndicator = stroke,
        handleHeight = height + ComponentConfig.ProgressHandleOvershoot * 2f,
        touchHeight = maxOf(height, ComponentConfig.ProgressTouchHeight)
    )
}

/** Trazo de la onda = un tercio del alto (4dp sobre los 12 del diseño original). */
private const val WAVE_STROKE_DIVISOR = 3f

/** Amplitud de la onda = un cuarto del alto (3dp sobre los 12 del diseño original). */
private const val WAVE_AMPLITUDE_DIVISOR = 4f

// ============== EXTENSIONES DE COLOR ==============
// Movidas a ColorExtensions.kt para mejor separación de responsabilidades

// ============== UTILIDADES ==============

/**
 * Contenido que debe verse ENCIMA de un shared element durante la transición de navegación
 * (título viajero, topbar pineada de los detalles): un shared element vuela en el OVERLAY
 * del [SharedTransitionScope], que dibuja sobre la capa normal — sin esto, el contenido
 * queda tapado por la imagen voladora y "aparece de golpe" cuando la transición termina.
 * Lo eleva al mismo overlay (por encima, zIndex 1) y le da entrada/salida con fade.
 * No-op si no hay scopes (pantalla montada sin transición compartida).
 *
 * La elevación se ata a la transición de ESTA pantalla, no a la del scope: el
 * [SharedTransitionScope] es ÚNICO para toda la app (MainActivity), así que el default
 * `renderInOverlay = { isTransitionActive }` también se enciende cuando la carátula morfa de la
 * píldora al NowPlaying — y entonces el título del detalle se dibujaba en el overlay, es decir
 * SOBRE el player que estaba subiendo, quedándose flotando hasta que la animación terminaba.
 *
 * **Solo eleva si la cabecera realmente MORFA** ([headerState]`.isMatchFound`). Elevar reparenta el
 * contenido al overlay de la raíz, que NO recibe el slide de la pantalla entrante: queda PINCHADO en
 * su posición final. Eso es correcto cuando hay carátula voladora (venís de un tile/lista): la motion
 * focal es el morph de la portada y el título sentado en su destino acompaña. Pero **entrando al
 * detalle DESDE el player NO hay tile de origen**, así que la cabecera no morfa y no hay match — si
 * igual se elevara, el título grande flotaría semitransparente y clavado sobre la pantalla que
 * desliza, detached (el bug). Sin match no se eleva: el título se queda como hijo de la pantalla y
 * entra CON ella (el slide lo arrastra, sus anclas dan el offset relativo correcto). `?: true`
 * conserva el comportamiento viejo para cualquier caller que no pase el estado.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
fun overSharedElementsModifier(
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    headerState: SharedTransitionScope.SharedContentState? = null
): Modifier = if (sharedTransitionScope != null && animatedVisibilityScope != null) {
    with(sharedTransitionScope) {
        Modifier
            .renderInSharedTransitionScopeOverlay(
                renderInOverlay = {
                    val transition = animatedVisibilityScope.transition
                    (transition.currentState != transition.targetState) &&
                        (headerState?.isMatchFound ?: true)
                },
                zIndexInOverlay = 1f
            )
            .then(with(animatedVisibilityScope) {
                Modifier.animateEnterExit(
                    // Specs de EFFECTS del tema, NO los defaults de compose (un spring rápido): así el
                    // título se funde AL RITMO de la transición de pantalla y del morph del shared
                    // element (los mismos tweens que `appNavForwardEnter`/`Exit`), en vez de "popear"
                    // antes de tiempo. El spring por defecto llegaba a alpha alto tan rápido que
                    // destapaba el primer frame —cuando las anclas del título viajero aún valen
                    // `Offset.Zero` y salta a su sitio—; con el tween el título está casi invisible
                    // esos frames y el salto no se ve.
                    enter = fadeIn(tween(EXPRESSIVE_DEFAULT_EFFECTS_MS, easing = ExpressiveDefaultEffectsEasing)),
                    exit = fadeOut(tween(EXPRESSIVE_FAST_EFFECTS_MS, easing = ExpressiveFastEffectsEasing))
                )
            })
    }
} else Modifier

/**
 * `m:ss` para una duración en milisegundos. ÚNICA implementación: `Song.toUiModel` tenía una copia
 * literal (mismos `/ 1000` y `/ 60`, mismo formato) que no compartía nada con ésta.
 *
 * Las conversiones van por `TimeUnit` y no por divisiones a mano: así no queda ningún factor suelto
 * que haya que reconocer de memoria, y la unidad de cada paso se lee en la propia llamada.
 */
fun formatTime(millis: Long): String {
    if (millis <= 0) return "0:00"
    val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(millis)
    val minutes = TimeUnit.SECONDS.toMinutes(totalSeconds)
    val seconds = totalSeconds - TimeUnit.MINUTES.toSeconds(minutes)
    return "%d:%02d".format(minutes, seconds)
}
