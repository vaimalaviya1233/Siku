package com.qhana.siku.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/**
 * Chip tonal Material 3 Expressive.
 * Píldora de contenedor SÓLIDO plano (sin sombra ni translucidez), mismo tratamiento
 * que la barra de acciones. El contenido debe usar [onContainerColor] para garantizar
 * contraste sobre el color de contenedor.
 */
@Composable
fun TonalChip(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    content: @Composable RowScope.() -> Unit
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = containerColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            content = content
        )
    }
}

/**
 * Color de contenido (texto/iconos) con contraste garantizado sobre un
 * contenedor sólido [container]: negro o blanco según su luminancia.
 */
fun onContainerColor(container: Color): Color =
    if (androidx.core.graphics.ColorUtils.calculateLuminance(container.toArgb()) > 0.5) Color.Black else Color.White

/**
 * Tono del MISMO matiz de [base] con la luminosidad fijada a [lightness] (HSL: conserva
 * H y S). ES la manera de derivar variantes oscuras/claras de un acento — un lerp hacia
 * negro mata la saturación junto con la luz y el resultado se LEE negro (verificado en
 * device: triángulo del play "negro" sobre cyan con lerp 0.80).
 */
fun accentTone(base: Color, lightness: Float): Color {
    val hsl = FloatArray(3)
    androidx.core.graphics.ColorUtils.colorToHSL(base.toArgb(), hsl)
    hsl[2] = lightness
    return Color(androidx.core.graphics.ColorUtils.HSLToColor(hsl))
}

/**
 * Contenido sobre un contenedor de ACENTO conservando su matiz: en vez del negro/blanco
 * puros de [onContainerColor], el tono oscuro (o claro) del PROPIO color — el triángulo
 * del play sobre el acento del álbum se ve "del álbum", no negro genérico.
 */
fun onAccentContentColor(container: Color): Color {
    // Elige el tono (oscuro 0.15 o claro 0.92, MISMO matiz) que MÁS contrasta con el fondo. NO por
    // umbral de luminancia 0.5 (heurística vieja que fallaba en acentos medios): el cruce real de
    // contraste está en ~0.18, así que para casi todo acento con algo de brillo (L > ~0.18) gana el
    // tono OSCURO. Por eso en tema oscuro el icono va OSCURO sobre el botón claro, como el spec del
    // Filled (onPrimary es oscuro en dark).
    val dark = accentTone(container, 0.15f)
    val light = accentTone(container, 0.92f)
    return if (contrastRatio(dark, container) >= contrastRatio(light, container)) dark else light
}

/**
 * Ratio de contraste WCAG entre dos colores: (L_claro + 0.05) / (L_oscuro + 0.05), en [1, 21].
 */
fun contrastRatio(a: Color, b: Color): Float {
    val la = androidx.core.graphics.ColorUtils.calculateLuminance(a.toArgb())
    val lb = androidx.core.graphics.ColorUtils.calculateLuminance(b.toArgb())
    val hi = maxOf(la, lb)
    val lo = minOf(la, lb)
    return ((hi + 0.05) / (lo + 0.05)).toFloat()
}

/**
 * Garantiza contraste WCAG >= [minRatio] de [content] sobre [container] CONSERVANDO el matiz del
 * acento: si el par ya cumple, se devuelve tal cual; si no, se empuja la luminosidad HSL de [content]
 * hacia el extremo (oscuro si el fondo es claro, claro si es oscuro) hasta alcanzar el ratio.
 *
 * Por qué no basta [accentTone] con L fija (0.15/0.92): la luminosidad HSL NO es la luminancia
 * perceptual, así que un mismo L da ratios distintos por matiz/saturación — no garantiza 3:1. Aquí
 * se MIDE el ratio real y se ajusta. Negro/blanco (L→0/1) dan >=4.5:1 contra cualquier fondo, así que
 * el barrido siempre termina cumpliendo. 3:1 = mínimo WCAG para iconos/componentes y texto grande.
 */
fun ensureContrast(content: Color, container: Color, minRatio: Float = 3f): Color {
    if (contrastRatio(content, container) >= minRatio) return content
    // Dirección por CONTRASTE real, no por luminancia 0.5: se oscurece si el negro contrasta más que
    // el blanco contra el fondo (cruce en ~0.18), se aclara si al revés. Así un fondo medio recibe
    // icono oscuro (la dirección que sí llega a 3:1), no claro.
    val goingDark = contrastRatio(Color.Black, container) >= contrastRatio(Color.White, container)
    val hsl = FloatArray(3)
    androidx.core.graphics.ColorUtils.colorToHSL(content.toArgb(), hsl)
    var l = hsl[2]
    val step = 0.04f
    while (true) {
        l = (if (goingDark) l - step else l + step).coerceIn(0f, 1f)
        hsl[2] = l
        val candidate = Color(androidx.core.graphics.ColorUtils.HSLToColor(hsl))
        if (contrastRatio(candidate, container) >= minRatio || l <= 0f || l >= 1f) return candidate
    }
}

/**
 * Versión VIVA de un acento para botones SOBRE carátulas (quick-play de álbum, badge del
 * home): el `primary` seedeado con PaletteStyle.Fidelity puede salir apagado u oscuro y
 * perderse contra el arte — aquí se fuerza saturación y brillo a una banda vibrante.
 * GUARDA: un acento casi gris (carátula monocroma) NO se satura — colorToHSV le da hue 0
 * y "avivarlo" lo teñiría de rojo inventado; en ese caso solo se ajusta el brillo.
 * El contenido encima debe usar [onContainerColor].
 */
fun vividAccentColor(base: Color): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(base.toArgb(), hsv)
    if (hsv[1] >= 0.15f) hsv[1] = hsv[1].coerceAtLeast(0.65f)
    hsv[2] = hsv[2].coerceIn(0.75f, 0.95f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/**
 * Acento del álbum para el tema actual (secondary en oscuro, primary en claro).
 *
 * El color de la carátula es UNA lectura y se aplica TAL CUAL — sin mezclas hacia blanco/negro
 * ni coerción de luminancia. Una carátula blanco y negro da su par NEGRO/BLANCO real
 * (`processBitmapColors`) y así debe verse; distorsionarlo sería inventar un color que no está.
 *
 * El ÚNICO caso con fallback neutro es "no hay carátula" — que ahora llega como [albumColors]
 * `null` (ver `ArtworkRepository.getAlbumColors`), un estado distinto de "carátula oscura/clara".
 * No hay gris centinela que confundir, así que no hace falta ninguna guarda por luminancia.
 *
 * @param fallback color a usar cuando no hay [albumColors] (por defecto blanco/negro según tema).
 */
fun albumAccent(
    albumColors: com.qhana.siku.data.model.AlbumColors?,
    isDarkTheme: Boolean,
    fallback: Color = if (isDarkTheme) Color.White else Color.Black
): Color {
    albumColors ?: return fallback
    return Color(if (isDarkTheme) albumColors.secondary else albumColors.primary)
}

/**
 * Contenedor de VIDRIO ESMERILADO (frosted glass) vía Haze: desenfoca el fondo que tiene
 * detrás ([hazeState] marcado con `hazeSource` en la capa de fondo) y le aplica un velo
 * tenue [tint]. Como toma el color real del fondo pixel a pixel, NUNCA desentona con el
 * gradiente/carátula — a diferencia de un contenedor de color sólido.
 *
 * En Android 12+ (API 31) el blur es real; en 8–11 Haze degrada a solo el velo translúcido.
 */
/**
 * Permite DESACTIVAR temporalmente el blur de [GlassSurface] (que es caro por frame) mientras
 * corre una animación pesada —p. ej. el slide de apertura de NowPlaying—, cayendo a un panel
 * tonal sólido. Se reactiva al asentarse la transición. Por defecto activo.
 */
val LocalGlassBlurEnabled = compositionLocalOf { true }

@Composable
fun GlassSurface(
    hazeState: HazeState,
    shape: Shape,
    tint: Color,
    modifier: Modifier = Modifier,
    blurRadius: Dp = 28.dp,
    content: @Composable () -> Unit
) {
    val blurEnabled = LocalGlassBlurEnabled.current
    // Fallback sin blur: el velo tinte compuesto sobre un tonal opaco (surfaceContainerHighest),
    // así el panel se lee como sólido durante la transición sin el coste del RenderEffect.
    val fallbackColor = tint.compositeOver(MaterialTheme.colorScheme.surfaceContainerHighest)
    Box(
        modifier = modifier
            .clip(shape)
            .then(
                if (blurEnabled) Modifier.hazeEffect(state = hazeState) {
                    this.blurRadius = blurRadius
                    this.tints = listOf(HazeTint(tint))
                    this.noiseFactor = 0f
                } else Modifier.background(fallbackColor)
            )
    ) {
        content()
    }
}


/**
 * Barra de progreso Expressive estilo Apple Music.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnifiedProgressBar(
    currentPosition: Long,
    duration: Long,
    onSeek: ((Long) -> Unit)? = null,
    trackColor: Color = MaterialTheme.colorScheme.primary,
    inactiveTrackColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    textColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    showThumb: Boolean = true,
    trackHeight: Dp = 6.dp,
    modifier: Modifier = Modifier
) {
    var sliderPosition by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    // Cálculo DIRECTO, sin remember{derivedStateOf}: currentPosition/duration son parámetros
    // planos (no State) y el derivedStateOf cacheaba la lambda de la PRIMERA composición —
    // la barra quedaba congelada en la posición inicial y al soltar un drag "rebotaba" ahí
    // aunque el seek de audio sí se ejecutara. La recomposición por cambio de parámetro ya
    // recalcula esto solo.
    val displayPosition = if (isDragging) sliderPosition
    else if (duration > 0) (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    else 0f

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val thumbSize by animateDpAsState(
        targetValue = if (showThumb && (isDragging || isPressed)) 18.dp else 0.dp,
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "ThumbSize"
    )

    val formattedDuration = remember(duration) { formatTime(duration) }

    Column(modifier = modifier.fillMaxWidth()) {
        if (onSeek != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Slider(
                    value = displayPosition,
                    onValueChange = {
                        isDragging = true
                        sliderPosition = it
                    },
                    onValueChangeFinished = {
                        isDragging = false
                        onSeek((sliderPosition * duration).toLong())
                    },
                    interactionSource = interactionSource,
                    modifier = Modifier.fillMaxWidth(),
                    thumb = {
                        Box(
                            modifier = Modifier
                                .size(thumbSize)
                                .shadow(if (thumbSize.value > 0f) 4.dp else 0.dp, CircleShape)
                                .background(Color.White, CircleShape)
                        )
                    },
                    track = { sliderState ->
                        val fraction = sliderState.value
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(trackHeight)
                                .clip(CircleShape)
                                .background(inactiveTrackColor.copy(alpha = 0.2f))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(fraction)
                                    .fillMaxHeight()
                                    .background(trackColor)
                            )
                        }
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = trackColor,
                        inactiveTrackColor = Color.Transparent
                    )
                )
            }
        } else {
            LinearProgressIndicator(
                progress = { if (duration > 0) currentPosition.toFloat() / duration else 0f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(trackHeight)
                    .clip(CircleShape),
                color = trackColor,
                trackColor = inactiveTrackColor.copy(alpha = 0.2f)
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatTime(if (isDragging) (sliderPosition * duration).toLong() else currentPosition),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                color = textColor
            )
            Text(
                text = formattedDuration,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                color = textColor
            )
        }
    }
}
