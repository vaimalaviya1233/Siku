package com.qhana.siku.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.TransformResult
import androidx.graphics.shapes.rectangle
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.qhana.siku.R
import com.qhana.siku.data.model.PlaybackState
import com.qhana.siku.data.model.PlayerToolbarAction
import com.qhana.siku.data.model.RepeatMode
import com.qhana.siku.data.model.ToolbarActionState
import com.qhana.siku.ui.components.*

/*
 * TRANSPORTE y BARRA DE ACCIONES del NowPlaying: el grupo prev/play/next con sus formas
 * animadas, el reveal de acento que comparten con el toolbar, y la barra flotante configurable.
 *
 * La carátula y la identidad de la canción viven en [NowPlayingArt]; la barra de progreso y el
 * chip de formato, en [NowPlayingProgress].
 */

/**
 * Shape del botón de play: morph continuo píldora ↔ [MaterialShapes.Cookie9Sided].
 * A progress 0 delega en la píldora exacta (percent 50, idéntica al estado en pausa
 * original); con progress > 0 interpola con [Morph] entre una píldora real construida
 * al aspect actual del botón y la cookie escalada a los bounds. Se reconstruye por
 * frame porque el tamaño anima a la vez que la forma — el costo del matching de
 * features de Morph es despreciable para un botón.
 */
internal class PlayButtonMorphShape(private val progress: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        // El spring rebota fuera de [0,1]; Morph solo acepta ese rango.
        val p = progress.coerceIn(0f, 1f)
        if (p == 0f) {
            return RoundedCornerShape(percent = 50).createOutline(size, layoutDirection, density)
        }
        // Píldora en coordenadas reales del botón (rectángulo con radio = lado menor / 2).
        val pill = RoundedPolygon.rectangle(
            width = size.width,
            height = size.height,
            rounding = CornerRounding(size.minDimension / 2f),
            centerX = size.width / 2f,
            centerY = size.height / 2f
        )
        // Cookie normalizada (unit square) escalada a los bounds del botón.
        val cookie = MaterialShapes.Cookie9Sided.transformed { x, y ->
            TransformResult(x * size.width, y * size.height)
        }
        return Outline.Generic(Morph(pill, cookie).toPath(p))
    }
}

/**
 * Rotación efectiva del giro de la cookie del play. Con el morph completo (progress 1)
 * gira con el ángulo pleno; durante el morph se reduce al resto módulo 40° (la cookie
 * de 9 lados es idéntica cada 360/9 = 40°) escalado por el progreso, así el
 * "desenrosque" al volver a píldora nunca supera 40° y el recorte del ángulo es
 * invisible por simetría.
 */
internal fun cookieSpinDegrees(angle: Float, progress: Float): Float {
    val p = progress.coerceIn(0f, 1f)
    return if (p >= 1f) angle else (angle % 40f) * p
}

/**
 * SHAPE REVEAL de acento COMPARTIDO para controles + action bar: al cambiar de canción se
 * re-renderiza TODO el bloque con el acento nuevo dentro de UNA ventana cookie que crece
 * desde el centro (misma coreografía que el reveal de la carátula) — un solo efecto para
 * ambos componentes. [accent]/[accentContent] deben ser los colores TARGET sin animar:
 * la ventana ES la transición (un tween de color por debajo la desluciría).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AccentRevealGroup(
    songId: String,
    accent: Color,
    accentContent: Color,
    modifier: Modifier = Modifier,
    content: @Composable (accent: Color, accentContent: Color) -> Unit
) {
    val currentAccent by rememberUpdatedState(accent)
    val currentAccentContent by rememberUpdatedState(accentContent)
    var displayed by remember { mutableStateOf(Triple(songId, accent, accentContent)) }
    var revealing by remember { mutableStateOf(false) }
    val reveal = remember { Animatable(0f) }

    // Acento que cambia SIN cambiar de canción (histograma que llega tarde u override
    // manual del color): actualizar el snapshot directo, sin reveal.
    LaunchedEffect(accent, accentContent) {
        if (!revealing && displayed.first == songId) {
            displayed = Triple(songId, accent, accentContent)
        }
    }

    LaunchedEffect(songId) {
        if (displayed.first == songId) return@LaunchedEffect
        revealing = true
        reveal.snapTo(0f)
        reveal.animateTo(
            1f,
            spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
        )
        displayed = Triple(songId, currentAccent, currentAccentContent)
        revealing = false
    }

    val revealShape = MaterialShapes.Cookie12Sided.toShape()
    Box(modifier = modifier) {
        // Capa base: el bloque con el acento CONGELADO de la canción anterior.
        content(displayed.second, displayed.third)
        if (revealing) {
            // Ventana cookie creciente + escala inversa (el contenido queda estático,
            // solo crece la ventana). 1.7 de rango: el bloque es apaisado y la cookie
            // estirada necesita algo más que en el cuadrado de la carátula para cubrir
            // las esquinas.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        val s = 0.08f + reveal.value * 1.7f
                        scaleX = s
                        scaleY = s
                        clip = true
                        shape = revealShape
                    }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val s = 0.08f + reveal.value * 1.7f
                            scaleX = 1f / s
                            scaleY = 1f / s
                        }
                ) {
                    content(currentAccent, currentAccentContent)
                }
            }
        }
    }
}

/**
 * Estado del botón de play (progreso del morph píldora↔cookie + ángulo del giro continuo),
 * HOISTED fuera de [AccentRevealGroup]: el reveal compone [PlaybackControls] DOS veces
 * (capa congelada + capa entrante) y cada copia creaba su propio rememberInfiniteTransition,
 * así que la cookie entrante arrancaba en 0° mientras la congelada llevaba su giro
 * acumulado — se veían DOS botones de play superpuestos con ángulos distintos al cambiar
 * de canción. Compartiendo el mismo estado ambas capas dibujan el botón idéntico y el
 * solape es invisible.
 */
internal class PlayButtonSpinState(
    val morphProgress: State<Float>,
    val angle: State<Float>
)

@Composable
internal fun rememberPlayButtonSpin(isPlayingOrBuffering: Boolean): PlayButtonSpinState {
    // Morph continuo píldora (pausa) ↔ Cookie9Sided (reproduciendo); mismo spring que
    // las dimensiones del botón en PlaybackControls.
    val morphProgress = animateFloatAsState(
        targetValue = if (isPlayingOrBuffering) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "playShapeMorph"
    )
    // La transición infinita solo existe mientras el botón no es píldora pura en reposo.
    // derivedStateOf: el caller (layout) no recompone por frame durante el morph, solo
    // cuando el booleano realmente cambia.
    val playing = rememberUpdatedState(isPlayingOrBuffering)
    val spinning by remember {
        derivedStateOf { playing.value || morphProgress.value > 0f }
    }
    val angle: State<Float> = if (spinning) {
        rememberInfiniteTransition(label = "cookieSpin").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(18000, easing = LinearEasing)),
            label = "cookieAngle"
        )
    } else {
        remember { mutableFloatStateOf(0f) }
    }
    return PlayButtonSpinState(morphProgress, angle)
}

/**
 * Reveal del propio glifo: descubre el contenido (icono RELLENO activo) mediante un clip circular
 * que crece desde el centro según [fraction] (0→1). Va SOBRE una copia del icono outlined inactivo,
 * así el glifo se "rellena desde el centro" (misma coreografía que el reveal de cambio de canción,
 * pero acotada AL ICONO, no al botón). Radio = círculo inscrito del icono. [revealPath] se reutiliza
 * (remember) para no allocar un Path por frame.
 */
private fun Modifier.iconFillReveal(fraction: Float, revealPath: Path): Modifier =
    this.drawWithContent {
        // Radio hasta la ESQUINA (media diagonal), no el círculo inscrito: así el glifo queda
        // TOTALMENTE descubierto en fraction=1 (con minDimension/2 las esquinas no se revelaban).
        val hw = size.width / 2f
        val hh = size.height / 2f
        val r = kotlin.math.sqrt((hw * hw + hh * hh).toDouble()).toFloat() * fraction
        if (r <= 0f) return@drawWithContent
        revealPath.reset()
        revealPath.addOval(Rect(center.x - r, center.y - r, center.x + r, center.y + r))
        clipPath(revealPath) { this@drawWithContent.drawContent() }
    }

/**
 * Toggle del floating toolbar: CÍRCULO fijo (sin morph, spec). La animación de estado es UN reveal
 * que ABRE DESDE EL CENTRO: el contenedor circular ([checkedBg]) crece y, en sync (mismo fraction),
 * el glifo relleno/activo se descubre sobre el outlined inactivo ([iconFillReveal]). Ambos terminan
 * a la vez. Contenedores del botón transparentes: el relleno lo pinta esta función.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ToolbarToggle(
    checked: Boolean,
    onToggle: () -> Unit,
    icon: String,
    description: String,
    checkedBg: Color,
    inactiveContent: Color,
    // Color del glifo activo (el par de contenido de [checkedBg]). Se pasa desde la barra para
    // que salga de la MISMA paleta (inversa del toolbar), no de un rol global fijo.
    activeContent: Color,
    isLoading: Boolean = false
) {
    val haptic = LocalHapticFeedback.current
    val fraction by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "toolbarToggleReveal"
    )
    val revealPath = remember { Path() }
    FilledIconToggleButton(
        checked = checked,
        onCheckedChange = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onToggle()
        },
        shapes = IconButtonDefaults.toggleableShapes(
            shape = CircleShape,
            pressedShape = CircleShape,
            checkedShape = CircleShape
        ),
        // El botón no pinta relleno propio (haría snap): el contenedor lo dibuja el drawBehind
        // (fade con [fraction]) y el reveal vive en el icono.
        colors = IconButtonDefaults.filledIconToggleButtonColors(
            containerColor = Color.Transparent,
            contentColor = inactiveContent,
            checkedContainerColor = Color.Transparent,
            checkedContentColor = activeContent
        ),
        // 48dp = mismo diámetro que los botones de acción del toolbar (ExpressiveActionIcon).
        modifier = Modifier
            .size(48.dp)
            .drawBehind {
                // Contenedor circular y reveal del icono ABREN JUNTOS desde el centro (mismo
                // fraction): el círculo CRECE en vez de solo aparecer, así termina a la vez que el
                // icono. Antes se dibujaba a tamaño full con alpha → "acababa" antes que el icono.
                if (fraction > 0f) {
                    drawCircle(color = checkedBg, radius = size.minDimension / 2f * fraction, center = center)
                }
            }
            .semantics { contentDescription = description }
    ) {
        if (isLoading) {
            LoadingIndicator(modifier = Modifier.size(22.dp), color = if (checked) activeContent else inactiveContent)
        } else {
            Box(contentAlignment = Alignment.Center) {
                // Base: glifo OUTLINED inactivo (siempre por debajo).
                MaterialSymbol(icon, fill = false, color = inactiveContent)
                // Reveal: glifo activo RELLENO descubierto desde el centro del icono (outlined→filled,
                // igual que todos los toggles del toolbar).
                MaterialSymbol(
                    icon,
                    fill = true,
                    color = activeContent,
                    modifier = Modifier.iconFillReveal(fraction, revealPath)
                )
            }
        }
    }
}

/**
 * Toggle icon button Material 3 — variante STANDARD del spec (fila D del matrix): SIN contenedor ni
 * borde, es SOLO el icono. Más liviano que Filled/Tonal/Outlined, así el play (Filled/primary) y
 * prev/next (Tonal/secondaryContainer) PESAN más en la jerarquía (el inverseSurface del Outlined
 * competía con el play).
 *  - Inactivo (D2): icono `onSurfaceVariant` ([inactiveColor]), sin fondo.
 *  - Activo (D3): icono en el acento del álbum ([accentColor] = playButtonColor), sin fondo.
 * Se usa el acento CRUDO (no `MaterialTheme.primary`): con seeds grises, Fidelity lleva `primary` a
 * un neutro claro casi idéntico a `onSurfaceVariant` y el toggle "no cambiaba de color". El acento
 * crudo difiere en luminancia. El cambio activo↔inactivo se anima con spring. Feedback háptico.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ExpressiveToggleIcon(
    checked: Boolean,
    onCheckedChange: () -> Unit,
    icon: String,
    description: String,
    accentColor: Color,
    inactiveColor: Color,
    modifier: Modifier = Modifier,
    fillWhenChecked: Boolean = true,
    iconSize: TextUnit = 24.sp
) {
    val haptic = LocalHapticFeedback.current
    // Activo = acento del álbum, SIN contenedor (Standard D3), pero pasado por ensureContrast contra
    // el `surface` para que SIEMPRE sea PROMINENTE (≥3:1) — si no, un acento claro (p.ej. el lila de
    // Orion) en tema claro pesa MENOS que el `onSurfaceVariant` oscuro del inactivo y se lee al
    // revés. Conserva el matiz: en claro lo oscurece, en oscuro lo deja claro.
    val activeColor = ensureContrast(accentColor, MaterialTheme.colorScheme.surface, minRatio = 3f)
    val iconColor by animateColorAsState(
        targetValue = if (checked) activeColor else inactiveColor,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "toggleIconColor"
    )
    IconToggleButton(
        checked = checked,
        onCheckedChange = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onCheckedChange()
        },
        colors = IconButtonDefaults.iconToggleButtonColors(
            contentColor = inactiveColor,
            checkedContentColor = activeColor
        ),
        modifier = modifier
            .size(48.dp)
            .semantics { contentDescription = description }
    ) {
        // Punto indicador de "activo" (estilo nav/Spotify): deja claro cuál está activo sin un
        // contenedor pesado — el color solo era muy sutil (sobre todo en grises). Aparece con un
        // pequeño rebote; su espacio (4dp) se reserva siempre para no desplazar el icono.
        val dotScale by animateFloatAsState(
            targetValue = if (checked) 1f else 0f,
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
            label = "toggleDot"
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            MaterialSymbol(icon, size = iconSize, fill = checked && fillWhenChecked, color = iconColor)
            Spacer(Modifier.height(2.dp))
            Box(
                Modifier
                    .size(4.dp)
                    .graphicsLayer { scaleX = dotScale; scaleY = dotScale }
                    .background(activeColor, CircleShape)
            )
        }
    }
}

/**
 * Botón de ACCIÓN (no-toggle) gemelo de [ExpressiveToggleIcon]: mismo tamaño (48.dp) y feedback
 * háptico, pero sin estado checked ni relleno. Lo usan las acciones que abren overlays (cola,
 * ecualizador) para que se vean idénticas a los toggles en estado inactivo.
 *
 * [morph]: si true (default), morfea de forma al presionar ([IconButtonDefaults.shapes]). El
 * floating toolbar del NowPlaying lo pasa en false → CÍRCULO fijo sin morph (decisión del usuario
 * para ESA barra; la barra superior conserva el morph con el default).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ExpressiveActionIcon(
    onClick: () -> Unit,
    icon: String,
    description: String,
    contentColor: Color,
    modifier: Modifier = Modifier,
    iconSize: TextUnit = 24.sp,
    enabled: Boolean = true,
    morph: Boolean = true
) {
    val haptic = LocalHapticFeedback.current
    FilledIconButton(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        },
        enabled = enabled,
        shapes = if (morph) IconButtonDefaults.shapes()
                 else IconButtonDefaults.shapes(shape = CircleShape, pressedShape = CircleShape),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = Color.Transparent,
            contentColor = contentColor
        ),
        modifier = modifier
            .size(48.dp)
            .semantics { contentDescription = description }
    ) {
        MaterialSymbol(icon, size = iconSize)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PlaybackControls(
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    contentColor: Color,
    playButtonColor: Color,
    playButtonContentColor: Color,
    isPlayingOrBuffering: Boolean,
    playbackState: PlaybackState,
    spin: PlayButtonSpinState
) {
    val haptic = LocalHapticFeedback.current
    // Contenedor de prev/next: FILLED TONAL de M3 (spec para este tipo de botón secundario) =
    // secondaryContainer + onSecondaryContainer. Contraste del icono garantizado por el par M3.
    // Jerarquía: play = Filled/primary (acento pleno) > prev/next = Tonal/secondary.
    val sideButtonContainer = MaterialTheme.colorScheme.secondaryContainer
    val sideButtonContent = MaterialTheme.colorScheme.onSecondaryContainer
    // Transporte = SOLO prev / play / next, centrado. Shuffle se movió a la cola y repeat al
    // toolbar (eran ajustes de la cola, no del transporte; y competían con el play en la jerarquía).
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Grupo central prev/play/next como CONNECTED BUTTON GROUP M3 Expressive según la
        // referencia visual del spec: separación visible (GroupSpacing), extremos con lado
        // exterior en semicírculo e interiores generosos que se encogen al presionar, y la
        // física del grupo: el botón presionado se ensancha un 15% (ExpandedRatio) comprimiendo
        // a sus vecinos vía animateWidth.
        //
        // El overflow NUNCA se dispara por diseño: los 3 ítems llevan weight proporcional a su
        // tamaño objetivo (56 / ancho del play / 56) y el ancho del grupo se capa a esa suma con
        // widthIn(max) — el measure policy solo manda ítems al menú cuando los NO ponderados no
        // caben; los ponderados se reparten el espacio disponible, así que en pantallas normales
        // miden exacto y en ultra estrechas se comprimen proporcionalmente (un control de
        // transporte jamás debe esconderse). Los menuContent quedan como red de seguridad
        // funcional por si un cambio futuro rompe esa invariante.

        // REPRODUCIENDO: el play es una COOKIE de 9 lados (88×88, morph desde la píldora
        // vía PlayButtonMorphShape) y los laterales círculos de 64.dp. EN PAUSA: los
        // laterales se estiran a cápsulas verticales (56×80) y el play vuelve a píldora
        // (132×80, solo icono). Forma y dimensiones animan con el mismo spring.
        val playWidth by animateDpAsState(
            targetValue = if (isPlayingOrBuffering) 88.dp else 132.dp,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessMediumLow
            ),
            label = "playButtonWidth"
        )
        val playHeight by animateDpAsState(
            targetValue = if (isPlayingOrBuffering) 88.dp else 80.dp,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessMediumLow
            ),
            label = "playButtonHeight"
        )
        // Giro continuo de la cookie mientras suena. La rotación se aplica en un
        // graphicsLayer con lambda (solo invalida el draw, cero recomposición por
        // frame) y se escala por el progreso del morph vía cookieSpinDegrees, así al
        // pausar se desenrosca suavemente junto con el morph a píldora. El estado
        // (morph + ángulo) viene HOISTED de [rememberPlayButtonSpin] para que las dos
        // capas del AccentRevealGroup dibujen el botón con el MISMO giro.
        val playMorphProgress by spin.morphProgress
        val cookieAngle = spin.angle
        val sideWidth by animateDpAsState(
            targetValue = if (isPlayingOrBuffering) 64.dp else 56.dp,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessMediumLow
            ),
            label = "sideButtonWidth"
        )
        val sideHeight by animateDpAsState(
            targetValue = if (isPlayingOrBuffering) 64.dp else 80.dp,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessMediumLow
            ),
            label = "sideButtonHeight"
        )
        val sideShapes = IconButtonShapes(
            shape = RoundedCornerShape(percent = 50),
            pressedShape = RoundedCornerShape(NowPlayingConfig.GroupInnerCorner)
        )
        val prevShapes = sideShapes
        val nextShapes = sideShapes
        // El grupo central va dentro de un Box PONDERADO con padding propio: sin él, la Row
        // mide el grupo ANTES que el toggle de repeat, y con el play ensanchado (pausa) el
        // grupo se llevaba todo el ancho disponible dejando a los toggles pegados/aplastados.
        // Con weight(1f) el grupo solo puede ocupar lo que sobra tras los dos toggles menos
        // TransportSideGap por lado, y sus weights internos lo comprimen proporcionalmente en
        // pantallas estrechas en vez de invadir a los vecinos.
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = NowPlayingConfig.TransportSideGap),
            contentAlignment = Alignment.Center
        ) {
        ButtonGroup(
            overflowIndicator = { menuState ->
                val moreControlsDesc = stringResource(R.string.np_more_controls)
                FilledIconButton(
                    onClick = { menuState.show() },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = sideButtonContainer,
                        contentColor = sideButtonContent
                    ),
                    modifier = Modifier
                        .size(56.dp)
                        .semantics { contentDescription = moreControlsDesc }
                ) {
                    MaterialSymbol("more_horiz", size = 30.sp)
                }
            },
            horizontalArrangement = Arrangement.spacedBy(NowPlayingConfig.GroupSpacing),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.widthIn(
                // Cap exacto = suma animada de los tres botones + gaps: así los weights
                // (proporcionales) producen exactamente los dp objetivo.
                max = sideWidth * 2 + playWidth + NowPlayingConfig.GroupSpacing * 2
            )
        ) {
            // Anterior: shape conectada "leading" de spec.
            customItem(
                buttonGroupContent = {
                    val prevInteraction = remember { MutableInteractionSource() }
                    val prevDesc = stringResource(R.string.np_previous)
                    FilledIconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onPrevious()
                        },
                        shapes = prevShapes,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = sideButtonContainer,
                            contentColor = sideButtonContent
                        ),
                        interactionSource = prevInteraction,
                        modifier = Modifier
                            .weight(sideWidth.value)
                            .animateWidth(prevInteraction)
                            .height(sideHeight)
                            .semantics { contentDescription = prevDesc }
                    ) {
                        MaterialSymbol("skip_previous", size = 32.sp, fill = true)
                    }
                },
                menuContent = { state ->
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.np_previous)) },
                        leadingIcon = { MaterialSymbol("skip_previous", fill = true) },
                        onClick = {
                            onPrevious()
                            state.dismiss()
                        }
                    )
                }
            )

            // Play/pausa: círculo reproduciendo, píldora ensanchada con texto en pausa.
            customItem(
                buttonGroupContent = {
                    val playInteraction = remember { MutableInteractionSource() }
                    val playStateDesc = when (playbackState) {
                        PlaybackState.BUFFERING -> stringResource(R.string.np_loading)
                        PlaybackState.PLAYING -> stringResource(R.string.common_pause)
                        else -> stringResource(R.string.common_play)
                    }
                    // Morph continuo píldora (pausa) ↔ Cookie9Sided (reproduciendo),
                    // conducido por el mismo spring que las dimensiones.
                    val playShape = PlayButtonMorphShape(playMorphProgress)
                    Surface(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onPlayPause()
                        },
                        shape = playShape,
                        color = playButtonColor,
                        interactionSource = playInteraction,
                        modifier = Modifier
                            .weight(playWidth.value)
                            .animateWidth(playInteraction)
                            .height(playHeight)
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
                                    scaleIn(
                                        animationSpec = tween(200, easing = FastOutSlowInEasing)
                                    ) togetherWith scaleOut(
                                        animationSpec = tween(150, easing = FastOutLinearInEasing)
                                    )
                                },
                                label = "playPause"
                            ) { state ->
                                when (state) {
                                    PlaybackState.BUFFERING ->
                                        // LoadingIndicator expressive: morfea entre MaterialShapes.
                                        LoadingIndicator(color = playButtonContentColor, modifier = Modifier.size(44.dp))
                                    PlaybackState.PLAYING ->
                                        MaterialSymbol("pause", size = 32.sp, color = playButtonContentColor, fill = true)
                                    else ->
                                        MaterialSymbol("play_arrow", size = 32.sp, color = playButtonContentColor, fill = true)
                                }
                            }
                        }
                    }
                },
                menuContent = { state ->
                    DropdownMenuItem(
                        text = { Text(if (isPlayingOrBuffering) stringResource(R.string.common_pause) else stringResource(R.string.common_play)) },
                        leadingIcon = { MaterialSymbol(if (isPlayingOrBuffering) "pause" else "play_arrow") },
                        onClick = {
                            onPlayPause()
                            state.dismiss()
                        }
                    )
                }
            )

            // Siguiente: shape conectada "trailing" de spec.
            customItem(
                buttonGroupContent = {
                    val nextInteraction = remember { MutableInteractionSource() }
                    val nextDesc = stringResource(R.string.np_next)
                    FilledIconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onNext()
                        },
                        shapes = nextShapes,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = sideButtonContainer,
                            contentColor = sideButtonContent
                        ),
                        interactionSource = nextInteraction,
                        modifier = Modifier
                            .weight(sideWidth.value)
                            .animateWidth(nextInteraction)
                            .height(sideHeight)
                            .semantics { contentDescription = nextDesc }
                    ) {
                        MaterialSymbol("skip_next", size = 32.sp, fill = true)
                    }
                },
                menuContent = { state ->
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.np_next)) },
                        leadingIcon = { MaterialSymbol("skip_next", fill = true) },
                        onClick = {
                            onNext()
                            state.dismiss()
                        }
                    )
                }
            )
        } // fin grupo central prev/play/next
        } // fin Box ponderado del transporte
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun BottomActionBar(
    showLyrics: Boolean,
    isLyricsLoading: Boolean,
    keepScreenOn: Boolean,
    playButtonColor: Color,
    songRemoteId: String?,
    isDownloaded: Boolean,
    isDownloading: Boolean,
    downloadProgress: Float?,
    onLyricsToggle: () -> Unit,
    onShowQueue: () -> Unit,
    onToggleKeepScreenOn: () -> Unit,
    onOpenEqualizer: () -> Unit,
    /** Estado REAL del EQ propio: el botón del toolbar rellena su fondo cuando está encendido. */
    eqEnabled: Boolean,
    onRedownload: () -> Unit,
    onAddToPlaylistClick: () -> Unit,
    sleepTimerActive: Boolean,
    onSleepTimerClick: () -> Unit,
    repeatMode: RepeatMode,
    onRepeatToggle: () -> Unit,
    onShareSong: () -> Unit,
    config: List<ToolbarActionState>,
    modifier: Modifier = Modifier
) {
    // FLOATING TOOLBAR M3 Expressive (m3.material.io/components/toolbars), variante VIBRANT:
    // container = `primaryContainer`, content = `onPrimaryContainer` (defaults de
    // vibrantFloatingToolbarColors). Se eligió vibrant porque la standard (surfaceContainer) se
    // perdía contra el fondo del NowPlaying.
    val toolbarContentColor = MaterialTheme.colorScheme.onPrimaryContainer
    // Activo = el PAR PROPIO de la barra INVERTIDO: contenedor `onPrimaryContainer`, icono
    // `primaryContainer`. Antes se usaba `inverseSurface`/`inverseOnSurface`, pero con carátulas
    // monocromas (esquema del álbum casi sin croma) inverseSurface cae del MISMO lado tonal que
    // el primaryContainer de la barra → el toggle activo quedaba gris sobre gris, casi invisible
    // en ambos temas. El par container/onContainer tiene contraste garantizado POR CONSTRUCCIÓN
    // (M3 los genera a ≥4.5:1) sea cual sea el seed, así el relleno activo siempre se ve.
    val checkedBg = MaterialTheme.colorScheme.onPrimaryContainer
    val activeContent = MaterialTheme.colorScheme.primaryContainer
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        HorizontalFloatingToolbar(
            expanded = true,
            colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
            // Spec floating toolbar: container de 64dp. La alpha18 NO lo respeta sola (medido
            // 72.5dp en dispositivo: acolcha 12dp alrededor de los touch targets de 48 aunque
            // se le pase contentPadding de 8) → altura FORZADA al token. Los visuales de 40dp
            // de los icon buttons caben exactos y quedan centrados.
            contentPadding = PaddingValues(ComponentConfig.FloatingBarInnerPadding),
            modifier = Modifier.height(ComponentConfig.FloatingToolbarHeight)
        ) {
            // Barra DINÁMICA: el usuario elige qué acciones van aquí y en qué orden (Ajustes →
            // Reproducción); el resto van al overflow (⋮). DOWNLOAD solo aplica a canciones de la
            // nube (songRemoteId != null): en locales se filtra de ambos lados. En la barra se
            // filtra también DURANTE la descarga (el anillo de progreso de abajo ya la comunica);
            // filtrarla acá y no dentro del `when` evita dejar un Spacer huérfano (hueco doble).
            val barActions = config.filter { it.inBar }.map { it.action }
                .filter { it != PlayerToolbarAction.DOWNLOAD || (songRemoteId != null && !isDownloading) }
            val overflowActions = config.filterNot { it.inBar }.map { it.action }
                .filter { it != PlayerToolbarAction.DOWNLOAD || songRemoteId != null }

            barActions.forEachIndexed { index, action ->
                // Gap explícito del spec entre acciones (el toolbar no espacia su Row solo).
                if (index > 0) Spacer(modifier = Modifier.width(ComponentConfig.FloatingBarItemGap))
                when (action) {
                    // Repeat: toggle de 3 estados (OFF→ALL→ONE). "Activo" = repeatMode != OFF; el
                    // icono cambia a repeat_one en modo ONE. Mismo tratamiento (reveal) que el resto.
                    PlayerToolbarAction.REPEAT -> ToolbarToggle(
                        checked = repeatMode != RepeatMode.OFF,
                        onToggle = onRepeatToggle,
                        icon = if (repeatMode == RepeatMode.ONE) "repeat_one" else "repeat",
                        description = when (repeatMode) {
                            RepeatMode.OFF -> stringResource(R.string.np_repeat_off)
                            RepeatMode.ONE -> stringResource(R.string.np_repeat_one)
                            RepeatMode.ALL -> stringResource(R.string.np_repeat_all)
                        },
                        checkedBg = checkedBg,
                        activeContent = activeContent,
                        inactiveContent = toolbarContentColor
                    )
                    PlayerToolbarAction.LYRICS -> ToolbarToggle(
                        checked = showLyrics,
                        onToggle = onLyricsToggle,
                        icon = "lyrics",
                        description = if (showLyrics) stringResource(R.string.np_hide_lyrics) else stringResource(R.string.np_show_lyrics),
                        checkedBg = checkedBg,
                        activeContent = activeContent,
                        inactiveContent = toolbarContentColor,
                        isLoading = isLyricsLoading
                    )
                    PlayerToolbarAction.QUEUE -> ExpressiveActionIcon(
                        onClick = onShowQueue,
                        icon = "queue_music",
                        description = stringResource(R.string.np_view_queue),
                        contentColor = toolbarContentColor,
                        morph = false
                    )
                    PlayerToolbarAction.SHARE -> ExpressiveActionIcon(
                        onClick = onShareSong,
                        icon = "share",
                        description = stringResource(R.string.np_share),
                        contentColor = toolbarContentColor,
                        morph = false
                    )
                    PlayerToolbarAction.KEEP_SCREEN_ON -> ToolbarToggle(
                        checked = keepScreenOn,
                        onToggle = onToggleKeepScreenOn,
                        // `wb_sunny` (no `visibility`): el ojo relleno quedaba como un blob sólido
                        // pesado; el sol hace un morph outlined→filled limpio y comunica "pantalla
                        // encendida/brillando".
                        icon = "wb_sunny",
                        description = if (keepScreenOn) stringResource(R.string.np_screen_off) else stringResource(R.string.np_screen_on),
                        checkedBg = checkedBg,
                        activeContent = activeContent,
                        inactiveContent = toolbarContentColor
                    )
                    // Con look de toggle (fondo relleno) cuando el EQ está ENCENDIDO — era el único
                    // botón con estado persistente que no lo mostraba. El tap sigue abriendo la
                    // hoja/panel (el on/off vive dentro).
                    PlayerToolbarAction.EQUALIZER -> ToolbarToggle(
                        checked = eqEnabled,
                        onToggle = onOpenEqualizer,
                        icon = "graphic_eq",
                        description = stringResource(R.string.np_open_eq),
                        checkedBg = checkedBg,
                        activeContent = activeContent,
                        inactiveContent = toolbarContentColor
                    )
                    PlayerToolbarAction.SLEEP_TIMER -> ToolbarToggle(
                        checked = sleepTimerActive,
                        onToggle = onSleepTimerClick,
                        icon = "bedtime",
                        description = stringResource(R.string.sleep_timer_title),
                        checkedBg = checkedBg,
                        activeContent = activeContent,
                        inactiveContent = toolbarContentColor
                    )
                    PlayerToolbarAction.ADD_TO_PLAYLIST -> ExpressiveActionIcon(
                        onClick = onAddToPlaylistClick,
                        icon = "playlist_add",
                        description = stringResource(R.string.common_add_to_playlist),
                        contentColor = toolbarContentColor,
                        morph = false
                    )
                    // Local o descargando ya se filtró al armar barActions.
                    PlayerToolbarAction.DOWNLOAD ->
                        ExpressiveActionIcon(
                            onClick = onRedownload,
                            icon = "download",
                            description = if (isDownloaded) stringResource(R.string.common_redownload) else stringResource(R.string.np_download),
                            contentColor = toolbarContentColor,
                            morph = false
                        )
                }
            }

            // Descarga EN CURSO: anillo determinado (o indeterminado mientras se resuelve la URL).
            // Independiente de la config: es ESTADO, no una acción reordenable.
            AnimatedVisibility(visible = isDownloading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(modifier = Modifier.width(ComponentConfig.FloatingBarItemGap))
                    val downloadingDesc = downloadProgress?.let {
                        stringResource(R.string.status_downloading, (it * 100).toInt())
                    } ?: stringResource(R.string.download_status_preparing)
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(48.dp)
                            .semantics { contentDescription = downloadingDesc }
                    ) {
                        // Onda circular expressive en sus dos variantes: determinada con progreso,
                        // indeterminada al preparar. Es el MISMO lenguaje que los indicadores
                        // lineales de sync y del gestor de descargas (antes acá había un
                        // LoadingIndicator, que morfea formas en vez de ondular).
                        val density = LocalDensity.current
                        val waveStroke = remember(density) {
                            Stroke(
                                width = with(density) { ToolbarDownloadStrokeWidth.toPx() },
                                cap = StrokeCap.Round
                            )
                        }
                        if (downloadProgress != null && downloadProgress > 0f) {
                            CircularWavyProgressIndicator(
                                progress = { downloadProgress },
                                modifier = Modifier.size(ToolbarDownloadIndicatorSize),
                                color = playButtonColor,
                                trackColor = playButtonColor.copy(alpha = TOOLBAR_DOWNLOAD_TRACK_ALPHA),
                                stroke = waveStroke,
                                trackStroke = waveStroke
                            )
                        } else {
                            CircularWavyProgressIndicator(
                                modifier = Modifier.size(ToolbarDownloadIndicatorSize),
                                color = playButtonColor,
                                trackColor = playButtonColor.copy(alpha = TOOLBAR_DOWNLOAD_TRACK_ALPHA),
                                stroke = waveStroke,
                                trackStroke = waveStroke
                            )
                        }
                        MaterialSymbol("download", size = 14.sp, color = toolbarContentColor)
                    }
                }
            }

            // Overflow (⋮): las acciones que el usuario dejó fuera de la barra, en su orden. Se
            // oculta por completo si no queda ninguna.
            if (overflowActions.isNotEmpty()) {
                Spacer(modifier = Modifier.width(ComponentConfig.FloatingBarItemGap))
                Box {
                    var showMenu by remember { mutableStateOf(false) }
                    ExpressiveActionIcon(
                        onClick = { showMenu = true },
                        icon = "more_vert",
                        description = stringResource(R.string.np_more_options),
                        contentColor = toolbarContentColor,
                        morph = false
                    )
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        overflowActions.forEach { action ->
                            when (action) {
                                PlayerToolbarAction.REPEAT -> DropdownMenuItem(
                                    text = {
                                        Text(when (repeatMode) {
                                            RepeatMode.OFF -> stringResource(R.string.np_repeat_off)
                                            RepeatMode.ONE -> stringResource(R.string.np_repeat_one)
                                            RepeatMode.ALL -> stringResource(R.string.np_repeat_all)
                                        })
                                    },
                                    leadingIcon = {
                                        MaterialSymbol(
                                            if (repeatMode == RepeatMode.ONE) "repeat_one" else "repeat",
                                            fill = repeatMode != RepeatMode.OFF
                                        )
                                    },
                                    onClick = { showMenu = false; onRepeatToggle() }
                                )
                                PlayerToolbarAction.LYRICS -> DropdownMenuItem(
                                    text = { Text(if (showLyrics) stringResource(R.string.np_hide_lyrics) else stringResource(R.string.np_show_lyrics)) },
                                    leadingIcon = { MaterialSymbol("lyrics", fill = showLyrics) },
                                    onClick = { showMenu = false; onLyricsToggle() }
                                )
                                PlayerToolbarAction.QUEUE -> DropdownMenuItem(
                                    text = { Text(stringResource(R.string.np_view_queue)) },
                                    leadingIcon = { MaterialSymbol("queue_music") },
                                    onClick = { showMenu = false; onShowQueue() }
                                )
                                PlayerToolbarAction.SHARE -> DropdownMenuItem(
                                    text = { Text(stringResource(R.string.np_share)) },
                                    leadingIcon = { MaterialSymbol("share") },
                                    onClick = { showMenu = false; onShareSong() }
                                )
                                PlayerToolbarAction.KEEP_SCREEN_ON -> DropdownMenuItem(
                                    text = { Text(if (keepScreenOn) stringResource(R.string.np_screen_off) else stringResource(R.string.np_screen_on)) },
                                    leadingIcon = { MaterialSymbol("wb_sunny", fill = keepScreenOn) },
                                    onClick = { showMenu = false; onToggleKeepScreenOn() }
                                )
                                PlayerToolbarAction.EQUALIZER -> DropdownMenuItem(
                                    text = { Text(stringResource(R.string.np_open_eq)) },
                                    leadingIcon = { MaterialSymbol("graphic_eq") },
                                    onClick = { showMenu = false; onOpenEqualizer() }
                                )
                                PlayerToolbarAction.ADD_TO_PLAYLIST -> DropdownMenuItem(
                                    text = { Text(stringResource(R.string.common_add_to_playlist)) },
                                    leadingIcon = { MaterialSymbol("playlist_add") },
                                    onClick = { showMenu = false; onAddToPlaylistClick() }
                                )
                                PlayerToolbarAction.SLEEP_TIMER -> DropdownMenuItem(
                                    text = { Text(stringResource(R.string.sleep_timer_title)) },
                                    leadingIcon = {
                                        MaterialSymbol(
                                            "bedtime",
                                            fill = sleepTimerActive,
                                            color = if (sleepTimerActive) playButtonColor else LocalContentColor.current
                                        )
                                    },
                                    onClick = { showMenu = false; onSleepTimerClick() }
                                )
                                PlayerToolbarAction.DOWNLOAD -> DropdownMenuItem(
                                    text = { Text(if (isDownloaded) stringResource(R.string.common_redownload) else stringResource(R.string.np_download)) },
                                    leadingIcon = { MaterialSymbol(if (isDownloading) "hourglass_top" else "download") },
                                    enabled = !isDownloading,
                                    onClick = { showMenu = false; onRedownload() }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
/**
 * Diámetro del indicador de descarga de la barra del NowPlaying. Va dentro del hueco de 48dp
 * de una acción y por debajo rodea al glifo `download` centrado, sin tocarlo.
 *
 * Son 36dp y no los 32 que llevaba con el `LoadingIndicator`: la onda gasta radio en la
 * amplitud, así que con el diámetro viejo los crestones rozaban el glifo y, sobre todo, a menos
 * de ~32dp la ondulación deja de leerse como onda (el spec la dimensiona a 48). Sigue holgado
 * dentro del hueco de 48dp.
 */
private val ToolbarDownloadIndicatorSize = 36.dp


/**
 * Opacidad del track del anillo de descarga. Se deriva del MISMO color del indicador (en vez de
 * un rol del esquema) porque la barra flotante es vibrant: cualquier `surface*` cae encima del
 * `primaryContainer` del contenedor y el track desaparece. Atenuado lo justo para que el recorrido
 * pendiente se lea sin competir con la onda activa ni con el glifo.
 */
private const val TOOLBAR_DOWNLOAD_TRACK_ALPHA = 0.3f

/**
 * Grosor del trazo de la onda de descarga (indicador y track). Por debajo del default del
 * componente (4dp de `activeThickness`), que a este diámetro se veía macizo y comía el hueco
 * interior donde va el glifo. No bajar mucho más: con un trazo demasiado fino la onda se
 * desdibuja sobre el contenedor de la barra.
 */
private val ToolbarDownloadStrokeWidth = 2.5.dp



