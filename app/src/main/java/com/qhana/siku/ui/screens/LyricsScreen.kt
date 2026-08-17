package com.qhana.siku.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qhana.siku.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qhana.siku.data.model.PlaybackState
import com.qhana.siku.data.repository.LyricsCandidate
import com.qhana.siku.ui.components.ConnectedChoiceGroup
import com.qhana.siku.ui.components.LyricsSearchSheet
import com.qhana.siku.ui.components.MaterialSymbol
import com.qhana.siku.ui.components.maxContrastOn
import com.qhana.siku.ui.state.LyricsFailure
import com.qhana.siku.ui.viewmodel.LyricLine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

import com.qhana.siku.ui.theme.appSpatialSpec
import com.qhana.siku.ui.theme.appFastSpatialSpec
import com.qhana.siku.ui.theme.appSlowSpatialSpec
import com.qhana.siku.ui.theme.appSlowEffectsSpec
import com.qhana.siku.ui.theme.EXPRESSIVE_DEFAULT_EFFECTS_MS
import com.qhana.siku.ui.theme.ExpressiveDefaultEffectsEasing
import androidx.compose.animation.core.tween

private enum class LyricsViewMode {
    SYNCED, PLAIN
}

/**
 * Alto de la zona de desvanecimiento que hay SOBRE los controles flotantes: es el recorrido
 * en el que el gradiente pasa de transparente a opaco. Generoso a propósito — con un recorrido
 * corto el fundido no se lee como tal, sino como una LÍNEA horizontal que corta el texto justo
 * encima de los botones.
 */
private val ControlsFadeHeight = 132.dp

/** Separación de los controles flotantes respecto al borde inferior de la pantalla. */
private val ControlsBottomInset = 48.dp

// Paddings de SyncedLyricsView. Se reutilizan en `contentPadding` y en el cálculo
// del scroll offset para centrar la línea activa en el área visible "limpia"
// (entre el header y los controles flotantes), no en el viewport bruto.
private val SyncedTopPadding = 32.dp

/**
 * El último verso tiene que poder quedar por ENCIMA del fundido, así que el padding inferior
 * se deriva de la geometría de los controles en vez de fijarse a ojo: si cambia el alto del
 * fade o el inset, el hueco reservado en la lista lo sigue solo.
 */
private val SyncedBottomPadding = ControlsFadeHeight + ControlsBottomInset + 24.dp

/**
 * Puntos con los que se dibuja la rampa del fundido inferior. Van repartidos por IGUAL: la curva
 * la hace el alpha de cada punto (smoothstep), no su posición, que es lo que permite subir la
 * resolución sin recolocar nada a mano.
 */
private const val FadeStopCount = 13

/** Marca que LrcLib devuelve para las pistas sin letra por ser instrumentales. */
private const val INSTRUMENTAL_SENTINEL = "[INSTRUMENTAL]"

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LyricsScreen(
    lyrics: String?,
    lyricLines: List<LyricLine>,
    isLyricsLoading: Boolean,
    /** Causa de que no haya letra; gobierna qué acciones ofrece el estado vacío. */
    lyricsFailure: LyricsFailure?,
    /** Detalle del fallo del proveedor (solo con [LyricsFailure.PROVIDER_ERROR]). */
    lyricsError: String?,
    songId: String,
    currentPositionFlow: StateFlow<Long>,
    playbackState: PlaybackState,
    lyricsCandidates: List<LyricsCandidate>?,
    isSearchingCandidates: Boolean,
    lyricsSearchError: String?,
    onSeek: (Long) -> Unit,
    onClose: () -> Unit,
    onFetchLyrics: () -> Unit,
    /** Abre el guardado de la letra (diálogo o guardado directo, según la preferencia). */
    onSaveLyrics: () -> Unit,
    /** Hay un guardado en curso: el botón se apaga para no dispararlo dos veces. */
    isSavingLyrics: Boolean,
    onGoogleSearch: () -> Unit,
    onSearchManually: () -> Unit,
    onSelectCandidate: (LyricsCandidate) -> Unit,
    onDismissSearch: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    backgroundColor: Color,
    contentColor: Color,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    val currentPosition by currentPositionFlow.collectAsStateWithLifecycle()

    // Auto-detect mode capability
    val hasSyncedData = remember(lyricLines) { lyricLines.any { it.startTime > 0 } }

    // Sin letra que mostrar: manda el estado vacío, que YA ofrece las acciones pertinentes a la
    // causa. Los iconos del header (lupa + refresco) se ocultan ahí: serían redundantes, y en el
    // caso sin red directamente engañosos.
    val showEmptyState = !isLyricsLoading && lyrics == null && lyricLines.isEmpty()

    // Acento "seguro": el del álbum si contrasta con el fondo, si no contentColor.
    // Se usa para los iconos del header, el pill del toggle y la línea activa.
    val safeAccent = remember(accentColor, backgroundColor, contentColor) {
        safeAccentColor(accentColor, backgroundColor, contentColor)
    }

    // State initialization
    var viewMode by remember { mutableStateOf(if (hasSyncedData) LyricsViewMode.SYNCED else LyricsViewMode.PLAIN) }

    // Reset viewMode cuando cambia la canción O las letras (ej.: selección manual de candidato).
    // Sin la dependencia en `lyrics`, el viewMode podía quedar pegado en PLAIN si la canción
    // arrancó sin letras y luego el usuario eligió una versión sincronizada.
    LaunchedEffect(songId, lyrics) {
        viewMode = if (hasSyncedData) LyricsViewMode.SYNCED else LyricsViewMode.PLAIN
    }

    // Gradient Background Wrapper
    Box(
        modifier = modifier
            .fillMaxSize()
            // Fondo SÓLIDO (surfaceContainer, provisto por el caller): superficie plana estable,
            // igual que el ajuste "fondo sólido" del NowPlaying.
            .background(backgroundColor)
            // Interceptar toques para evitar click-through
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { /* No-op to block touches */ }
            .statusBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                ExpressiveActionIcon(
                    onClick = onClose,
                    icon = "expand_more",
                    description = stringResource(R.string.lyrics_close_desc),
                    contentColor = safeAccent,
                    iconSize = 32.sp
                )

                // Mode Toggle - pill deslizante custom sólido (sin bordes)
                if (hasSyncedData && !lyrics.isNullOrBlank() && !isLyricsLoading) {
                    LyricsModeToggle(
                        viewMode = viewMode,
                        pillColor = safeAccent,
                        contentColor = contentColor,
                        onModeChange = { viewMode = it }
                    )
                } else {
                    Spacer(modifier = Modifier.width(48.dp))
                }

                // Búsqueda manual + guardar. Solo con letra en pantalla: en el estado vacío las
                // acciones las da EmptyStateView, adaptadas a la causa.
                //
                // Ya no hay botón de "volver a buscar": con la búsqueda manual al lado —que además
                // deja ELEGIR la versión en vez de repetir la misma consulta— era el mismo gesto
                // dos veces. Su hueco lo ocupa guardar. `onFetchLyrics` sigue vivo para el estado
                // vacío, donde sí significa algo distinto: "busca, que aquí no hay nada".
                if (!showEmptyState) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ExpressiveActionIcon(
                            onClick = onSearchManually,
                            icon = "manage_search",
                            description = stringResource(R.string.lyrics_search_manual_desc),
                            contentColor = safeAccent.copy(alpha = if (isLyricsLoading) 0.3f else 1f),
                            enabled = !isLyricsLoading
                        )
                        // Una canción marcada como instrumental no tiene nada que guardar.
                        val canSave = !isLyricsLoading && !isSavingLyrics && lyrics != INSTRUMENTAL_SENTINEL
                        ExpressiveActionIcon(
                            onClick = onSaveLyrics,
                            icon = "save",
                            description = stringResource(R.string.lyrics_save_desc),
                            contentColor = safeAccent.copy(alpha = if (canSave) 1f else 0.3f),
                            enabled = canSave
                        )
                    }
                } else {
                    // Mantiene el balance del Row (el cerrar queda a la izquierda, no centrado).
                    Spacer(modifier = Modifier.width(48.dp))
                }
            }

            // Content Area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                if (isLyricsLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = 120.dp), // Subir visualmente
                        contentAlignment = Alignment.Center
                    ) {
                        // LoadingIndicator expressive: morfea entre MaterialShapes.
                        LoadingIndicator(color = accentColor, modifier = Modifier.size(56.dp))
                    }
                } else if (lyrics == INSTRUMENTAL_SENTINEL) {
                    InstrumentalView(contentColor)
                } else if (showEmptyState) {
                    EmptyStateView(
                        failure = lyricsFailure,
                        errorDetail = lyricsError,
                        contentColor = contentColor,
                        accentColor = safeAccent,
                        onFetchLyrics = onFetchLyrics,
                        onSearchManually = onSearchManually,
                        onGoogleSearch = onGoogleSearch
                    )
                } else {
                    // Spec explícito: el default de `Crossfade` no mira el tema. Y va con los
                    // tokens de TRANSICIÓN (fade through) y no con un spring: lo que se cruza es el
                    // cuerpo entero de la pantalla, no un componente.
                    Crossfade(
                        targetState = viewMode,
                        // Fade through = puro EFFECTS: no se mueve nada, solo cruzan opacidades.
                        animationSpec = tween(
                            EXPRESSIVE_DEFAULT_EFFECTS_MS,
                            easing = ExpressiveDefaultEffectsEasing
                        ),
                        label = "LyricsMode"
                    ) { mode ->
                        when (mode) {
                            LyricsViewMode.SYNCED -> SyncedLyricsView(
                                lines = lyricLines,
                                currentPosition = currentPosition,
                                contentColor = contentColor,
                                accentColor = accentColor,
                                backgroundColor = backgroundColor,
                                onSeek = onSeek
                            )
                            LyricsViewMode.PLAIN -> PlainLyricsView(
                                text = lyrics ?: lyricLines.joinToString("\n") { it.text },
                                contentColor = contentColor
                            )
                        }
                    }
                }
            }
        }

        // Controles flotantes al fondo, sobre un fundido que tapa el texto que pasa por detrás.
        //
        // La rampa es de UN SOLO color —el fondo— con alpha creciente y perfil smoothstep. Antes
        // mezclaba a mitad de camino un "glow" con un 25% del acento: eso hace que la luminancia
        // suba y vuelva a bajar, y una rampa no monótona se lee como una FRANJA horizontal justo
        // encima de los controles (se veía en los dos temas; en claro además el acento es tono 40,
        // así que el "glow" ni siquiera aclaraba: ensuciaba). Smoothstep entra y sale con pendiente
        // cero, así que tampoco quedan bandas de Mach en los empalmes.
        val fadeBrush = remember(backgroundColor) {
            Brush.verticalGradient(
                List(FadeStopCount) { i ->
                    val t = i / (FadeStopCount - 1f)
                    backgroundColor.copy(alpha = t * t * (3f - 2f * t))
                }
            )
        }

        // El fundido es su PROPIO bloque y los controles van sobre color SÓLIDO, en vez de un
        // único degradado estirado sobre el conjunto: así el punto en que la rampa llega a opaco
        // coincide EXACTAMENTE con el borde superior de los botones, sin depender del alto que
        // acabe midiendo la fila (que cambia al pausar: los laterales pasan de 48 a 64dp).
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
        ) {
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ControlsFadeHeight)
                    .background(fadeBrush)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(backgroundColor)
                    .padding(bottom = ControlsBottomInset),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val haptic = LocalHapticFeedback.current
                // Réplica COMPACTA del transporte del NowPlaying: reproduciendo = play como
                // COOKIE de 9 lados girando (64×64) y laterales círculos tenues; pausa = play a
                // píldora ensanchada (108×64) y laterales a cápsula vertical. El play comparte
                // pieza por pieza con el NowPlaying: [PlayButtonMorphShape] para la forma,
                // [rememberPlayButtonSpin] para el morph + el giro, y [cookieSpinDegrees] para
                // desenroscar el ángulo al pausar. Los laterales siguen con forma fija.
                val isPlaying = playbackState == PlaybackState.PLAYING || playbackState == PlaybackState.BUFFERING
                // Transporte alineado con el NowPlaying: play resaltado (primary), laterales
                // tonales (secondaryContainer). Roles del scheme sembrado con el álbum.
                val playContainer = MaterialTheme.colorScheme.primary
                val playContent = MaterialTheme.colorScheme.onPrimary
                val spin = rememberPlayButtonSpin(isPlaying)
                val playMorphProgress by spin.morphProgress
                val cookieAngle = spin.angle
                val playStateDesc = when (playbackState) {
                    PlaybackState.BUFFERING -> stringResource(R.string.np_loading)
                    PlaybackState.PLAYING -> stringResource(R.string.common_pause)
                    else -> stringResource(R.string.common_play)
                }
                val sideContainer = MaterialTheme.colorScheme.secondaryContainer
                val sideContent = MaterialTheme.colorScheme.onSecondaryContainer
                // Mismo token que el transporte del NowPlaying: este bloque es el MISMO control en
                // otra pantalla, y hasta ahora coincidía por tener los números copiados.
                val transportSpec = appSpatialSpec<Dp>()
                val playWidth by animateDpAsState(
                    targetValue = if (isPlaying) 64.dp else 108.dp,
                    animationSpec = transportSpec,
                    label = "lyricsPlayWidth"
                )
                val sideWidth by animateDpAsState(
                    targetValue = if (isPlaying) 48.dp else 44.dp,
                    animationSpec = transportSpec,
                    label = "lyricsSideWidth"
                )
                val sideHeight by animateDpAsState(
                    targetValue = if (isPlaying) 48.dp else 64.dp,
                    animationSpec = transportSpec,
                    label = "lyricsSideHeight"
                )
                // Izados: `transitionSpec` no es composable (mismo patrón que en PlaybackControls).
                val glyphEnterScale = appSpatialSpec<Float>()
                val glyphExitScale = appFastSpatialSpec<Float>()

                FilledIconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onPrevious()
                    },
                    shapes = IconButtonDefaults.shapes(),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = sideContainer,
                        contentColor = sideContent
                    ),
                    modifier = Modifier.width(sideWidth).height(sideHeight)
                ) {
                    MaterialSymbol("skip_previous", size = 26.sp, color = sideContent, fill = true)
                }

                Surface(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onPlayPause()
                    },
                    shape = PlayButtonMorphShape(playMorphProgress),
                    color = playContainer,
                    modifier = Modifier
                        .height(64.dp)
                        .width(playWidth)
                        // Lambda: solo invalida el draw, cero recomposición por frame del giro.
                        .graphicsLayer {
                            rotationZ = cookieSpinDegrees(cookieAngle.value, playMorphProgress)
                        }
                        .semantics { contentDescription = playStateDesc }
                ) {
                    // Contra-rotación: el layer gira la superficie entera (forma incluida);
                    // el contenido se gira a la inversa para que el icono quede derecho.
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                rotationZ = -cookieSpinDegrees(cookieAngle.value, playMorphProgress)
                            }
                    ) {
                        AnimatedContent(
                            targetState = playbackState,
                            transitionSpec = {
                                scaleIn(glyphEnterScale) togetherWith scaleOut(glyphExitScale)
                            },
                            label = "PlayPauseAnimation"
                        ) { state ->
                            when (state) {
                                PlaybackState.BUFFERING ->
                                    // LoadingIndicator expressive (morfea entre MaterialShapes),
                                    // igual que el NowPlaying: era un CircularProgressIndicator.
                                    LoadingIndicator(color = playContent, modifier = Modifier.size(32.dp))
                                PlaybackState.PLAYING ->
                                    MaterialSymbol("pause", size = 28.sp, color = playContent, fill = true)
                                else ->
                                    MaterialSymbol("play_arrow", size = 28.sp, color = playContent, fill = true)
                            }
                        }
                    }
                }

                FilledIconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onNext()
                    },
                    shapes = IconButtonDefaults.shapes(),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = sideContainer,
                        contentColor = sideContent
                    ),
                    modifier = Modifier.width(sideWidth).height(sideHeight)
                ) {
                    MaterialSymbol("skip_next", size = 26.sp, color = sideContent, fill = true)
                }
            }
        }

        // Sheet de búsqueda manual (solo se muestra cuando hay actividad)
        if (isSearchingCandidates || lyricsCandidates != null || lyricsSearchError != null) {
            LyricsSearchSheet(
                isLoading = isSearchingCandidates,
                candidates = lyricsCandidates,
                errorMessage = lyricsSearchError,
                onCandidateSelected = onSelectCandidate,
                // Reintentar relanza la misma búsqueda manual de candidatos (LrcLib).
                onRetry = onSearchManually,
                onDismiss = onDismissSearch
            )
        }
    }
}

/**
 * Acento "seguro": devuelve [accent] si contrasta bien con [background]; si no,
 * cae a [fallback] (blanco/negro) para garantizar legibilidad.
 * Umbral 3.0 ≈ mínimo WCAG para texto grande.
 */
private fun safeAccentColor(accent: Color, background: Color, fallback: Color): Color {
    val contrast = androidx.core.graphics.ColorUtils.calculateContrast(
        accent.toArgb(),
        // calculateContrast exige fondo opaco; albumPrimary debería serlo, pero forzamos.
        androidx.core.graphics.ColorUtils.setAlphaComponent(background.toArgb(), 255)
    )
    return if (contrast >= 3.0) accent else fallback
}

/**
 * Toggle Karaoke/Texto con el connected button group de M3 Expressive (el segmentado que usaba
 * antes dejó de estar recomendado). El botón activo se tiñe con [pillColor] y morfea de forma;
 * los springs los pone el `MotionScheme` del tema.
 */
@Composable
private fun LyricsModeToggle(
    viewMode: LyricsViewMode,
    pillColor: Color,
    contentColor: Color,
    onModeChange: (LyricsViewMode) -> Unit
) {
    // Contenido del segmento activo: blanco/negro por CONTRASTE real (`maxContrastOn`), NO por el
    // umbral de luminancia 0.5. Sobre un acento medio (0.18 < L < 0.5) aquel umbral elegía blanco
    // cuando el negro contrasta el doble, así que "Karaoke" salía claro sobre la píldora clara del
    // acento mientras el play, del mismo color, iba con glifo oscuro — la inconsistencia reportada.
    val activeContentColor = remember(pillColor) { maxContrastOn(pillColor) }
    // Connected button group (el segmentado dejó de recomendarse en M3 Expressive). El fondo de
    // esta pantalla es `surfaceContainer` (rol del scheme), así que el segmento INACTIVO usa roles
    // del scheme y NO un alpha sobre el contenido: contenedor tenue `surfaceContainerHighest` (un
    // escalón por encima del fondo, para leerse como botón sin competir con la píldora activa) y
    // contenido `onSurfaceVariant`. La píldora ACTIVA sí es el acento dinámico del álbum (`pillColor`),
    // con su contenido decidido por contraste unas líneas más arriba.
    val colors = ToggleButtonDefaults.toggleButtonColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        checkedContainerColor = pillColor,
        checkedContentColor = activeContentColor
    )

    ConnectedChoiceGroup(
        options = LYRICS_VIEW_MODES,
        selected = viewMode,
        onSelect = onModeChange,
        labelFor = {
            when (it) {
                LyricsViewMode.SYNCED -> stringResource(R.string.lyrics_view_karaoke)
                LyricsViewMode.PLAIN -> stringResource(R.string.lyrics_plain)
            }
        },
        fillWidth = false,
        colors = colors
    )
}

/** Modos de visualización de la letra, en el orden del conmutador. */
private val LYRICS_VIEW_MODES = listOf(LyricsViewMode.SYNCED, LyricsViewMode.PLAIN)

@Composable
private fun SyncedLyricsView(
    lines: List<LyricLine>,
    currentPosition: Long,
    contentColor: Color,
    accentColor: Color,
    backgroundColor: Color,
    onSeek: (Long) -> Unit
) {
    // Color de la línea activa: acento del álbum con fallback de legibilidad.
    val activeLineColor = remember(accentColor, backgroundColor, contentColor) {
        safeAccentColor(accentColor, backgroundColor, contentColor)
    }

    val listState = rememberLazyListState()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val topPaddingPx = with(density) { SyncedTopPadding.toPx().toInt() }
    val bottomPaddingPx = with(density) { SyncedBottomPadding.toPx().toInt() }

    // Find active line
    val activeIndex by remember(lines, currentPosition) {
        derivedStateOf {
            val index = lines.indexOfLast { it.startTime <= currentPosition }
            if (index < 0) 0 else index
        }
    }

    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0) {
            try {
                // Centrar la línea activa en el área "limpia" (entre header arriba y
                // controles flotantes abajo), NO en el viewport bruto. El bottom padding
                // es mucho mayor que el top, así que centrar respecto al viewport completo
                // dejaba la línea activa más abajo del centro visual percibido.
                //
                // `animateScrollToItem(index, scrollOffset)`: con scrollOffset = 0 el top
                // del item queda en el inicio del padding superior. Negativo lo mueve
                // HACIA ABAJO. Para centrarlo en el área limpia:
                //   offset = -(cleanArea - itemHeight) / 2
                // donde cleanArea = viewport - topPadding - bottomPadding.
                val viewportHeight = listState.layoutInfo.viewportSize.height
                val itemInfo = listState.layoutInfo.visibleItemsInfo.find { it.index == activeIndex }
                val cleanArea = viewportHeight - topPaddingPx - bottomPaddingPx
                if (cleanArea > 0 && itemInfo != null) {
                    val centerOffset = -(cleanArea - itemInfo.size) / 2
                    listState.animateScrollToItem(activeIndex, scrollOffset = centerOffset)
                } else {
                    // Item fuera del viewport (ej.: primera carga, salto grande): scroll directo.
                    // El próximo cambio de activeIndex centrará exacto, pero al menos lo trae a la vista.
                    listState.animateScrollToItem(activeIndex)
                }
            } catch (e: Exception) {
                android.util.Log.w("LyricsScreen", "Error scrolling to active line", e)
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        // Bottom incluye altura de los controles + zona de fade colorido + margen
        contentPadding = PaddingValues(top = SyncedTopPadding, bottom = SyncedBottomPadding),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        itemsIndexed(
            items = lines,
            key = { index, line -> "${line.startTime}_$index" }
        ) { index, line ->
            val isActive = index == activeIndex
            val distance = abs(index - activeIndex)

            // Profundidad tipo "foco": la línea activa va a plena opacidad y las vecinas
            // se atenúan progresivamente según su distancia. Da sensación de enfoque en la
            // línea que suena (estilo Apple Music) en vez de un alpha plano para todas.
            // `contentColor` ya es blanco en dark / negro en light (lo arma NowPlayingScreen),
            // así que controlamos la visibilidad sólo con el alpha del graphicsLayer.
            val targetAlpha = when {
                isActive -> 1f
                distance == 1 -> 0.45f
                distance == 2 -> 0.30f
                else -> 0.20f
            }
            // La activa crece levemente. Usamos SÓLO scale (graphicsLayer), nunca el fontSize,
            // para que no haya reflow vertical y el centrado del scroll quede estable.
            val targetScale = if (isActive) 1.12f else 1f

            // Tokens *slow* (los más blandos del scheme): el foco del karaoke tiene que sentirse
            // como un desplazamiento de atención, no como un cambio de estado. Alpha y color van
            // por `slowEffects` —y por eso cierran juntos, que es lo que evita el destello descrito
            // abajo— y la escala por `slowSpatial`, que es el que corresponde a una transformación.
            val focusFade = appSlowEffectsSpec<Float>()
            val animatedAlpha by animateFloatAsState(
                targetValue = targetAlpha,
                animationSpec = focusFade,
                label = "lyricAlpha"
            )
            val animatedScale by animateFloatAsState(
                targetValue = targetScale,
                animationSpec = appSlowSpatialSpec(),
                label = "lyricScale"
            )
            // Animamos también el COLOR: sin esto, al perder el foco la línea saltaba de
            // golpe de `activeLineColor` (acento) a `contentColor` (blanco/negro) mientras
            // el alpha aún estaba alto -> destello blanco. Con la transición de color el
            // cambio es gradual y el flash desaparece.
            val animatedColor by animateColorAsState(
                targetValue = if (isActive) activeLineColor else contentColor,
                animationSpec = appSlowEffectsSpec(),
                label = "lyricColor"
            )

            val interactionSource = remember { MutableInteractionSource() }

            Text(
                text = line.text,
                color = animatedColor,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    lineHeight = 34.sp,
                    fontSize = 22.sp
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null
                    ) {
                        if (line.startTime >= 0) onSeek(line.startTime)
                    }
                    .graphicsLayer {
                        alpha = animatedAlpha
                        scaleX = animatedScale
                        scaleY = animatedScale
                    }
                    .padding(vertical = 12.dp, horizontal = 32.dp)
            )
        }
    }
}

@Composable
private fun PlainLyricsView(
    text: String,
    contentColor: Color
) {
    val scrollState = rememberScrollState()
    
    // Clean timestamps [00:00.00] for readability
    val cleanText = remember(text) {
        text.replace(Regex("\\[\\d{2}:\\d{2}(\\.\\d{2,3})?\\] ?"), "")
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(top = 16.dp, bottom = SyncedBottomPadding, start = 24.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = cleanText,
            color = contentColor,
            style = MaterialTheme.typography.bodyLarge.copy(
                lineHeight = 32.sp,
                textAlign = TextAlign.Center,
                fontSize = 18.sp
            )
        )
    }
}

@Composable
private fun InstrumentalView(contentColor: Color) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 120.dp), // Subir visualmente para no chocar con controles
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // El glifo escala con el parámetro `size` (fontSize), NO con Modifier.size —
        // ese solo agrandaba la caja dejando el icono en 24sp (default).
        // Icono decorativo de estado vacío → rol `outline`, NO un alpha sobre `onSurface`
        // (jerarquía por rol, no por opacidad).
        MaterialSymbol("music_note", size = 120.sp, color = MaterialTheme.colorScheme.outline)
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.lyrics_badge_instrumental),
            style = MaterialTheme.typography.headlineMedium,
            color = contentColor
        )
    }
}

/**
 * Estado vacío de las letras, con acciones PROPIAS de cada causa ([LyricsFailure]). Antes era un
 * único `SplitButton` con chevron que ofrecía siempre lo mismo — incluido "buscar en Google" sin
 * conexión, o "reintentar" cuando LrcLib ya había dicho que no la tiene.
 *
 * - [LyricsFailure.NOT_FOUND]: reintentar no sirve → buscar alternativas (candidatos de LrcLib)
 *   y buscar en Google.
 * - [LyricsFailure.NO_NETWORK]: ninguna acción de red sirve → solo reintentar (cuando vuelva).
 * - [LyricsFailure.PROVIDER_ERROR]: el fallo puede ser transitorio → reintentar, y Google como
 *   salida. La búsqueda manual también pega contra LrcLib, así que no se ofrece.
 */
@Composable
private fun EmptyStateView(
    failure: LyricsFailure?,
    errorDetail: String?,
    contentColor: Color,
    accentColor: Color,
    onFetchLyrics: () -> Unit,
    onSearchManually: () -> Unit,
    onGoogleSearch: () -> Unit
) {
    val icon = when (failure) {
        LyricsFailure.NO_NETWORK -> "wifi_off"
        LyricsFailure.PROVIDER_ERROR -> "cloud_off"
        else -> "lyrics"
    }
    val title = when (failure) {
        LyricsFailure.NO_NETWORK -> stringResource(R.string.lyrics_error_offline_title)
        LyricsFailure.PROVIDER_ERROR -> stringResource(R.string.lyrics_error_provider_title)
        else -> stringResource(R.string.lyrics_none_available)
    }
    val subtitle = when (failure) {
        LyricsFailure.NO_NETWORK -> stringResource(R.string.lyrics_error_offline_subtitle)
        // El detalle crudo del proveedor si lo hay; si no, el genérico.
        LyricsFailure.PROVIDER_ERROR -> errorDetail ?: stringResource(R.string.lyrics_error_provider_subtitle)
        else -> null
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Jerarquía por ROL, no por alpha: icono decorativo → `outline`; título (mensaje
        // principal) → `contentColor` pleno; subtítulo → `onSurfaceVariant`.
        MaterialSymbol(icon, size = 120.sp, color = MaterialTheme.colorScheme.outline)
        Spacer(modifier = Modifier.height(32.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = contentColor,
            textAlign = TextAlign.Center
        )
        if (subtitle != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        Spacer(modifier = Modifier.height(32.dp))

        // Botones APILADOS (uno encima del otro): con etiquetas largas en español ("Buscar
        // alternativas" + "Buscar en Google") una fila los apretaba/truncaba. En columna van a
        // ancho completo y respiran.
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (failure) {
                LyricsFailure.NO_NETWORK -> {
                    LyricsActionButton("refresh", stringResource(R.string.common_retry), accentColor, primary = true, onClick = onFetchLyrics, modifier = Modifier.fillMaxWidth())
                }
                LyricsFailure.PROVIDER_ERROR -> {
                    LyricsActionButton("refresh", stringResource(R.string.common_retry), accentColor, primary = true, onClick = onFetchLyrics, modifier = Modifier.fillMaxWidth())
                    LyricsActionButton("travel_explore", stringResource(R.string.lyrics_search_google), accentColor, primary = false, onClick = onGoogleSearch, modifier = Modifier.fillMaxWidth())
                }
                else -> {
                    LyricsActionButton("manage_search", stringResource(R.string.lyrics_search_alternatives), accentColor, primary = true, onClick = onSearchManually, modifier = Modifier.fillMaxWidth())
                    LyricsActionButton("travel_explore", stringResource(R.string.lyrics_search_google), accentColor, primary = false, onClick = onGoogleSearch, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/**
 * Botón del estado vacío. Tonal para la acción principal, con contorno para la secundaria. Los
 * colores salen del acento del álbum, no del ColorScheme: el fondo de la pantalla de letras es el
 * de la carátula, y un `primaryContainer` del tema desentonaría.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LyricsActionButton(
    icon: String,
    label: String,
    accentColor: Color,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Button/OutlinedButton REALES (M3 Expressive: shape-morph al presionar) con los colores
    // del acento del álbum en vez de Surface artesanal.
    val contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
    if (primary) {
        // Contenido sobre el acento: `maxContrastOn` (contraste WCAG real), no el umbral de
        // luminancia 0.5, por el mismo motivo que el toggle Karaoke y el icono del chip.
        val content = maxContrastOn(accentColor)
        Button(
            onClick = onClick,
            modifier = modifier,
            shapes = ButtonDefaults.shapes(),
            colors = ButtonDefaults.buttonColors(
                containerColor = accentColor,
                contentColor = content
            ),
            contentPadding = contentPadding
        ) {
            MaterialSymbol(icon, size = 18.sp, color = content)
            Spacer(modifier = Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = content)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            shapes = ButtonDefaults.shapes(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = accentColor),
            border = BorderStroke(1.dp, accentColor.copy(alpha = 0.5f)),
            contentPadding = contentPadding
        ) {
            MaterialSymbol(icon, size = 18.sp, color = accentColor)
            Spacer(modifier = Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = accentColor)
        }
    }
}
