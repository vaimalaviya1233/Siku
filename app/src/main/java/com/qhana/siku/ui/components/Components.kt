package com.qhana.siku.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// ============== CONFIGURACIÓN ==============

internal object ComponentConfig {
    val StatusIconSize = 18.dp
    val StatusIconTouchTarget = 22.dp // Min 48dp recommended, but 22dp + 4dp padding is better than 18dp
    val SongItemCornerRadius = 8.dp
    val SongItemIconSize = 56.dp
    val SongItemIconCorner = 28.dp
    val ThumbnailSize = 156
    // --- Capa flotante del bottom (solo el MiniPlayer), specs floating toolbar M3 Expressive ---
    // ContainerHeight del floating toolbar del spec (FloatingToolbarTokens). Lo usa la barra de
    // acciones del NowPlaying, que DEBE forzarlo (la alpha18 no lo respeta sola). Vivía pegado a
    // MiniPlayerHeight por coincidencia de valor: son cosas distintas y ya divergen.
    val FloatingToolbarHeight = 64.dp
    // 72dp, un escalón por encima del ContainerHeight del floating toolbar (64dp): con la
    // carátula, dos líneas de texto y los controles, el container de 64 obligaba a botones de
    // 34-44dp, por debajo del mínimo táctil de 48. Con 72 la carátula sube a 56, los botones
    // llegan a 48 reales y sigue leyéndose como barra, no como panel.
    val MiniPlayerHeight = 72.dp
    // Carátula: deja 8dp de aire arriba/abajo dentro del container, el mismo respiro que los
    // botones de la floating toolbar del NowPlaying.
    val MiniPlayerArtSize = MiniPlayerHeight - 16.dp
    // Diámetro/lado de los controles de transporte. 48dp = mínimo táctil recomendado.
    val MiniPlayerButtonSize = 48.dp
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
    // El alto lo comparten los DOS modos (píldora plana y onda): si divergen, alternar el
    // ajuste de Apariencia movería el layout de todo el bloque de controles.
    val ProgressTrackHeight = 12.dp
    // Grosor del trazo de la onda: el mismo que el diámetro del stop indicator (radio = mitad),
    // así la punta y el indicador final se leen del mismo peso.
    val ProgressWaveStroke = 4.dp
    // Desviación máxima de la onda respecto al centro. Acotada a (alto - grosor) / 2 para que
    // las crestas no se recorten contra el borde del contenedor.
    val ProgressWaveAmplitude = 3.dp
    // Distancia entre crestas. Más corta = onda más "nerviosa"; más larga = casi recta.
    val ProgressWaveLength = 22.dp
    // Diámetro del stop indicator del final del riel (y del punto de contraste en la punta del
    // fill, que es su espejo). Mismo valor que el grosor de la onda: se leen del mismo peso.
    val ProgressStopIndicatorSize = 4.dp
    // Diámetro del thumb REDONDO que remata la onda (solo modo wavy). Igual al alto del track,
    // que es lo que hace que se lea como el mismo objeto y no como un adorno pegado encima.
    // Viaja con la cresta (±ProgressWaveAmplitude), así que puede asomar unos dp por fuera del
    // track: los absorbe la caja de 24dp en la que vive el Slider, no hay recorte.
    val ProgressWaveThumbSize = ProgressTrackHeight
}

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
 */
@OptIn(ExperimentalSharedTransitionApi::class)
fun overSharedElementsModifier(
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?
): Modifier = if (sharedTransitionScope != null && animatedVisibilityScope != null) {
    with(sharedTransitionScope) {
        Modifier
            .renderInSharedTransitionScopeOverlay(
                renderInOverlay = {
                    val transition = animatedVisibilityScope.transition
                    transition.currentState != transition.targetState
                },
                zIndexInOverlay = 1f
            )
            .then(with(animatedVisibilityScope) {
                Modifier.animateEnterExit(enter = fadeIn(), exit = fadeOut())
            })
    }
} else Modifier

fun formatTime(millis: Long): String {
    if (millis <= 0) return "0:00"
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
