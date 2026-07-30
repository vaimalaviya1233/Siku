package com.qhana.siku.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.hct.Hct
import com.materialkolor.rememberDynamicColorScheme
import com.qhana.siku.data.repository.ArtworkRepository
import com.qhana.siku.ui.components.ensureContrast

// ============== FALLBACK COLORS (NEUTRAL / BLUE ACCENT) ==============
// Solo se usan en Android < 12 donde Dynamic Color no existe.
// Usamos un azul profundo/violeta elegante como fallback, neutro pero moderno.

val PrimaryLight = Color(0xFF6750A4)
val OnPrimaryLight = Color(0xFFFFFFFF)
val PrimaryContainerLight = Color(0xFFEADDFF)
val OnPrimaryContainerLight = Color(0xFF21005D)

val PrimaryDark = Color(0xFFD0BCFF)
val OnPrimaryDark = Color(0xFF381E72)
val PrimaryContainerDark = Color(0xFF4F378B)
val OnPrimaryContainerDark = Color(0xFFEADDFF)

// Error colors
val ErrorLight = Color(0xFFB3261E)
val OnErrorLight = Color(0xFFFFFFFF)
val ErrorDark = Color(0xFFF2B8B5)
val OnErrorDark = Color(0xFF601410)

// Fallback Schemes (Material 3 Baseline)
private val DarkColorScheme = darkColorScheme(
    primary = PrimaryDark,
    onPrimary = OnPrimaryDark,
    primaryContainer = PrimaryContainerDark,
    onPrimaryContainer = OnPrimaryContainerDark,
    error = ErrorDark,
    onError = OnErrorDark
)

private val LightColorScheme = lightColorScheme(
    primary = PrimaryLight,
    onPrimary = OnPrimaryLight,
    primaryContainer = PrimaryContainerLight,
    onPrimaryContainer = OnPrimaryContainerLight,
    error = ErrorLight,
    onError = OnErrorLight
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MusicPlayerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    // Acento del álbum en reproducción: si llega, genera TODO el ColorScheme desde él
    // (homogeneíza el acento en toda la app). null = dynamic del sistema / baseline.
    seedColor: Color? = null,
    // Carátula ACROMÁTICA (blanco/negro/gris): se pide un esquema NEUTRO en escala de grises
    // (PaletteStyle.Monochrome) que solo sigue el claro/oscuro, en vez de un matiz inventado.
    monochrome: Boolean = false,
    // Estilo con el que MaterialKolor deriva el esquema del seed. Lo elige el usuario en
    // Ajustes → Apariencia. Con el color spec 2025 (ver abajo) cada estilo decide DOS cosas, no
    // solo el croma: también el TONO del rol. En teléfono:
    //  - TonalSpot (default): croma 26 en oscuro / 32 en claro. El tono se queda CLAVADO en 80 en
    //    oscuro, así que el acento sale pastel por construcción — a esa claridad el gamut sRGB no
    //    admite croma alto, y ningún ajuste lo cambia. En claro sí usa el tono de máximo croma.
    //  - Expressive: croma 36/48 y tono de máximo croma. En el spec viejo ROTABA el matiz +240°
    //    (pintaba un color que no estaba en la carátula); en el 2025 respeta el matiz del seed,
    //    que es lo que lo vuelve utilizable aquí.
    //  - Vibrant: croma 74 y tono de máximo croma. Es el más parecido a lo que System UI pinta en
    //    la notificación de media. Sigue siendo el que más puede exagerar un seed apagado.
    //  - Content/Fidelity siguen el croma del seed pero dejan los neutros casi grises.
    // `monochrome` gana siempre: sin croma no hay matiz que estilizar.
    paletteStyle: PaletteStyle = PaletteStyle.TonalSpot,
    // Acento VIVO, SOLO EN TEMA OSCURO: reencuadra `primary` al tono donde el matiz del álbum
    // alcanza su croma máximo, en vez del 80 fijo que le da el spec (ver [withVividPrimary]). Es lo
    // que acerca el acento al que System UI pinta en la notificación de media sin cambiar de estilo
    // de paleta — con Equilibrado, las superficies siguen siendo moderadas.
    vividAccent: Boolean = true,
    // Fundido del esquema al cambiar el seed (canción). `false` lo SNAPEA, y existe por
    // RENDIMIENTO, no por gusto: los colores animados pasan por `MaterialTheme`, cuyo
    // `LocalColorScheme` es un composition local ESTÁTICO — cada frame del fundido recompone la
    // app ENTERA sin skipping, durante ~350 ms. Con el reproductor cerrado eso es tolerable (no
    // compite con nada); con el reproductor ABIERTO caía justo encima del slide de apertura o del
    // reveal de cambio de canción y era la parte del tartamudeo que sobrevivió a arreglar las
    // shapes. Ahí el cambio de acento ya lo coreografían los reveals cookie del NowPlaying (que
    // congelan el acento viejo y barren el nuevo con una ventana por graphicsLayer, sin recomponer
    // por frame), así que el fundido global era redundante además de caro. MainActivity pasa
    // `!playerExpanded`.
    animateColors: Boolean = true,
    content: @Composable () -> Unit
) {
    // Post-proceso del esquema ya generado (palanca oficial de MaterialKolor). Se memoriza porque
    // `rememberDynamicColorScheme` mete esta lambda entre las claves de su `remember`: una lambda
    // nueva por recomposición tiraría el caché del esquema en cada frame.
    // SOLO en tema oscuro. Ahí el problema es real: el spec clava `primary` en tono 80 y a esa
    // claridad el gamut sRGB no admite croma alto, así que el acento sale pastel hagas lo que
    // hagas. En claro no hace falta — con el spec 2025 TonalSpot ya coloca `primary` en el tono de
    // máximo croma y luego DynamicColor le aplica su corrección de contraste, que un color escrito
    // a mano se salta. Se probó intervenir también en claro (28 jul) y todo lo que salió de ahí
    // fueron problemas nuevos: el play gris de croma 9.3, el botón oscuro con el glifo oscuro, y
    // una ventana de tonos que había que recalibrar a ojo. Revertido a propósito.
    val vividPrimary = remember(seedColor, darkTheme, monochrome, vividAccent) {
        if (seedColor == null || monochrome || !vividAccent || !darkTheme) null
        else { scheme: ColorScheme -> scheme.withVividPrimary(seedColor) }
    }

    val colorScheme = when {
        seedColor != null -> rememberDynamicColorScheme(
            seedColor = seedColor,
            isDark = darkTheme,
            isAmoled = false,
            style = if (monochrome) PaletteStyle.Monochrome else paletteStyle,
            modifyColorScheme = vividPrimary,
            // Color spec de M3 EXPRESSIVE. El default de MaterialKolor sigue siendo `SPEC_2021`
            // (`ColorSpec.SpecVersion.Default`), que es el Material You de 2021: ahí el tono de
            // cada rol es una CONSTANTE — `primary` vale 80 en oscuro y 40 en claro pase lo que
            // pase (`ColorSpec2021.primary()`), y el estilo de paleta solo decide el croma. Como a
            // tono 80 el gamut sRGB no admite croma alto, el acento salía pastel con CUALQUIER
            // estilo (ni Vibrant, que pide croma 200, llegaba al color que System UI pinta en la
            // notificación de la sesión de media).
            //
            // En el spec 2025, para las variantes Vibrant y Expressive en teléfono el tono de
            // `primary` pasa a ser `tMaxC(primaryPalette)` = el tono donde ESE matiz alcanza su
            // croma máximo. Es lo mismo que hace System UI, y por eso ahora el acento del álbum
            // puede ser tan vivo como el de la notificación. Con TonalSpot el tono sigue clavado
            // en 80 en oscuro: el spec habilita el color vivo, pero quien lo pide es el estilo.
            specVersion = ColorSpec.SpecVersion.SPEC_2025
        ).animatedScheme(
            // `snap()` y no "no llamar animatedScheme": así los 31 `animateColorAsState` conservan
            // su identidad en la composición y el conmutar `animateColors` en caliente (abrir o
            // cerrar el player) no reinicia ninguna animación en vuelo.
            spec = if (animateColors) AppMotionScheme.slowEffectsSpec() else snap()
        )
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val generation = remember(paletteStyle, darkTheme, vividAccent) {
        ThemeGeneration(paletteStyle, darkTheme, vividAccent)
    }

    CompositionLocalProvider(LocalThemeGeneration provides generation) {
        MaterialTheme(
            colorScheme = colorScheme,
            // Shapes DEFAULT de M3 (extraSmall 4 / small 8 / medium 12 / large 16 / extraLarge 28 dp).
            // Antes había un override "expressive" (8/12/16/24/32) que agrandaba todas las esquinas.
            shapes = Shapes(),
            // Motion Expressive: springs físicos en todas las transiciones M3 (button groups,
            // toggles, sheets, etc.) en lugar de los tweens lineales por defecto.
            // La instancia viene de [AppMotionScheme] y no de una llamada suelta a
            // `MotionScheme.expressive()`: es la MISMA que leen los sitios sin composición
            // (transiciones del NavHost, gestos del player, shared elements), así que no pueden
            // desincronizarse del tema. Ver ui/theme/Motion.kt.
            motionScheme = AppMotionScheme,
            typography = rememberAppTypography(),
            content = content
        )
    }
}

// --- Previsualización del acento (qué haría un seed con el tema actual) ---

/**
 * Cómo está generando el tema su esquema AHORA MISMO. Lo publica [MusicPlayerTheme] para que una
 * superficie que necesite anticipar el resultado —el selector de color de la carátula— no tenga que
 * releer preferencias por su cuenta ni reconstruir a mano la llamada a MaterialKolor: dos copias de
 * esa construcción se separan en cuanto una cambia, y la previsualización mentiría sin fallar.
 */
data class ThemeGeneration(
    val paletteStyle: PaletteStyle,
    val isDark: Boolean,
    val vividAccent: Boolean
)

val LocalThemeGeneration = staticCompositionLocalOf {
    ThemeGeneration(PaletteStyle.TonalSpot, isDark = false, vividAccent = true)
}

/**
 * El `primary` que la app pintaría si [seedArgb] fuese el acento del álbum: mismo estilo de paleta,
 * mismo color spec y mismo reencuadre vivo que [MusicPlayerTheme].
 *
 * Existe porque un color de la carátula NO se aplica tal cual: el estilo de paleta le impone su
 * propio croma y el spec le impone el tono del rol, así que un beige de croma 11 puede acabar como
 * un mostaza de croma 32. El selector enseñaba el color crudo y el usuario veía otro en pantalla.
 *
 * **Nunca usa `Monochrome`**, aunque el candidato sea acromático: elegir un color a mano marca la
 * canción como override manual y eso DESACTIVA la guardia de acromático (ver `hasManualColor`), así
 * que el gris se aplicaría como seed igualmente. Previsualizar en Monochrome sería previsualizar un
 * camino que ese toque no va a tomar.
 */
@Composable
fun rememberAccentPreview(seedArgb: Int): Color {
    val generation = LocalThemeGeneration.current
    val seed = Color(seedArgb)
    // Misma razón que en [MusicPlayerTheme]: la lambda entra en las claves del `remember` interno
    // de MaterialKolor, y una nueva por recomposición tiraría el esquema cacheado en cada frame.
    val vividPrimary = remember(seed, generation) {
        if (!generation.vividAccent || !generation.isDark) null
        else { scheme: ColorScheme -> scheme.withVividPrimary(seed) }
    }
    return rememberDynamicColorScheme(
        seedColor = seed,
        isDark = generation.isDark,
        isAmoled = false,
        style = generation.paletteStyle,
        modifyColorScheme = vividPrimary,
        specVersion = ColorSpec.SpecVersion.SPEC_2025
    ).primary
}

// --- Acento vivo (reencuadre tonal de `primary`) ---

/**
 * Croma de sondeo para preguntarle al gamut "¿cuánto color admite este matiz a este tono?".
 * Cualquier valor por encima del máximo real de sRGB sirve: [Hct.from] recorta a lo alcanzable y
 * devuelve el croma que de verdad cupo. Es el mismo 200 que el spec 2021 le pide a Vibrant.
 */
private const val CHROMA_PROBE = 200.0

/** Resolución del barrido de tonos. 2 puntos de L* son imperceptibles y halvan el coste. */
private const val TONE_STEP = 2.0

/**
 * Ventana de tonos donde puede aterrizar el acento. No es estética sino de legibilidad: el acento
 * vive sobre superficies de tono ~6-22, y salirse de estos límites lo acercaría demasiado a su
 * propio fondo. El tono de máximo croma de un matiz suele caer dentro (los naranjas ~65, los azules
 * ~45), así que en la práctica casi nunca recorta.
 */
private const val VIVID_TONE_MIN = 50.0
private const val VIVID_TONE_MAX = 90.0

/** Contraste mínimo del contenido sobre el acento reencuadrado (AA de texto). */
private const val ON_PRIMARY_MIN_CONTRAST = 4.5f

/**
 * Reencuadra `primary` (y su `onPrimary`) al tono donde el MATIZ del álbum alcanza su croma
 * máximo. **Solo se llama en tema oscuro** (ver la condición en [MusicPlayerTheme]).
 *
 * El problema que resuelve: el tono de cada rol es una CONSTANTE del color spec, no algo que salga
 * de la carátula — `primary` vale 80 en oscuro con Equilibrado, pase lo que pase. Y a tono 80 el
 * gamut sRGB no admite croma alto, así que el acento sale pastel por construcción y no hay ajuste
 * que lo cambie. Es la razón de que ningún estilo alcanzara el color que System UI pinta en la
 * notificación de media: System UI no usa un rol M3, coloca el color extraído en su tono de máximo
 * croma. Esto hace lo mismo, pero SOLO para este rol y sin cambiar de estilo de paleta: con
 * Equilibrado las superficies siguen siendo moderadas y solo el acento se aviva.
 *
 * Conserva el croma del SEED en vez de saturar al máximo (que es lo que hace Vibrant con su croma
 * 74, y el Vibrant del spec viejo con 200): así una portada apagada da un acento apagado. Ese fue
 * el motivo de abandonar Vibrant como default el 21 jul 2026 — con un seed de croma ~10 pintaba un
 * cian fluorescente que no estaba en la carátula. Aquí eso no puede pasar: el techo es la portada.
 *
 * `onPrimary` se repone con [ensureContrast] porque al construir el color a mano se pierde la
 * garantía del par `color`/`onColor` que M3 sí da.
 *
 * No toca nada si el seed es acromático: sin matiz no hay croma que maximizar y el tono de máximo
 * croma sería un punto arbitrario de la escala de grises.
 */
private fun ColorScheme.withVividPrimary(seed: Color): ColorScheme {
    // El mismo veredicto de "esto es un gris" que usa el resto de la app, no un umbral paralelo:
    // vale para el caso que `monochrome` no cubre — un color elegido A MANO que resulta acromático
    // (ahí `hasManualColor` desactiva la guardia y el tema sí se seedea con él).
    val seedHct = Hct.fromInt(seed.toArgb())
    if (ArtworkRepository.isAchromatic(seed.toArgb())) return this

    // Tono donde ESTE matiz admite más color. Se sondea con un croma imposible para leer el techo
    // real del gamut en cada tono; el color final se construye después con el croma del seed.
    var bestTone = VIVID_TONE_MIN
    var bestChroma = -1.0
    var tone = VIVID_TONE_MIN
    while (tone <= VIVID_TONE_MAX) {
        val available = Hct.from(seedHct.hue, CHROMA_PROBE, tone).chroma
        if (available > bestChroma) {
            bestChroma = available
            bestTone = tone
        }
        tone += TONE_STEP
    }

    // Croma: NUNCA por debajo del que el esquema ya le había dado a este rol. `primary` aquí es
    // todavía el original (`modifyColorScheme` corre al final), así que su croma es el de la
    // variante — 26 en TonalSpot oscuro.
    //
    // Tomar solo el del seed fue un error MEDIDO (28 jul): con una portada casi en blanco y negro
    // el acento salía a croma 9.3 mientras la barra de acciones iba a 39.8, o sea el play era el
    // elemento MENOS colorido de la pantalla.
    //
    // El techo sigue siendo el seed cuando este es MÁS cromático que la paleta (una portada vívida
    // da un acento vívido), y el suelo es la variante, así que esto no puede exagerar un seed
    // apagado más de lo que el propio estilo ya lo hace en el resto de los roles. Que es lo que
    // separa esto de Vibrant, cuyo croma fijo de 74 fue lo que arruinó Parasomnia.
    val targetChroma = maxOf(seedHct.chroma, Hct.fromInt(primary.toArgb()).chroma)
    val vivid = Color(Hct.from(seedHct.hue, targetChroma, bestTone).toInt())
    return copy(
        primary = vivid,
        onPrimary = ensureContrast(onPrimary, vivid, ON_PRIMARY_MIN_CONTRAST)
    )
}

/**
 * Anima la transición entre ColorSchemes (al cambiar de canción cambia el seed) para que
 * el cambio de acento global sea suave en toda la app, en lugar de un salto brusco.
 *
 * OJO con el precio: `LocalColorScheme` de material3 es un composition local ESTÁTICO, así que
 * cada frame de este fundido recompone TODO lo que hay bajo el `MaterialTheme`, sin skipping.
 * Por eso existe `animateColors` en [MusicPlayerTheme]: con el reproductor abierto el fundido
 * se sustituye por un `snap` (una sola recomposición) y la coreografía la ponen los reveals.
 */
// OptIn: `slowEffectsSpec()` es API experimental del MotionScheme.
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ColorScheme.animatedScheme(
    // Token *slow effects* del MotionScheme: el más lento de los que NO rebotan.
    //
    // "Effects" y no "spatial" porque esto son colores, y el rebote de los springs spatial sobre un
    // color se lee como parpadeo. "Slow" porque el acento del álbum es ambiente: es el cambio más
    // pesado de la app y el que menos conviene precipitar.
    //
    // Sustituye a un `spring(NoBouncy, StiffnessMediumLow)` escrito a mano. El motivo que había
    // documentado para ese 400f era que coincidiera con el morph del botón play; eso se abandona a
    // propósito: el morph es SPATIAL y tiene otra física por definición (rebota), así que la
    // coincidencia exacta no era sostenible sin clavar los dos números a mano. Con el token, el
    // color cierra algo antes que el morph.
    spec: AnimationSpec<Color> = AppMotionScheme.slowEffectsSpec()
): ColorScheme {
    @Composable
    fun anim(target: Color, label: String): Color =
        animateColorAsState(targetValue = target, animationSpec = spec, label = label).value
    return copy(
        primary = anim(primary, "primary"),
        onPrimary = anim(onPrimary, "onPrimary"),
        primaryContainer = anim(primaryContainer, "primaryContainer"),
        onPrimaryContainer = anim(onPrimaryContainer, "onPrimaryContainer"),
        inversePrimary = anim(inversePrimary, "inversePrimary"),
        secondary = anim(secondary, "secondary"),
        onSecondary = anim(onSecondary, "onSecondary"),
        secondaryContainer = anim(secondaryContainer, "secondaryContainer"),
        onSecondaryContainer = anim(onSecondaryContainer, "onSecondaryContainer"),
        tertiary = anim(tertiary, "tertiary"),
        onTertiary = anim(onTertiary, "onTertiary"),
        tertiaryContainer = anim(tertiaryContainer, "tertiaryContainer"),
        onTertiaryContainer = anim(onTertiaryContainer, "onTertiaryContainer"),
        background = anim(background, "background"),
        onBackground = anim(onBackground, "onBackground"),
        surface = anim(surface, "surface"),
        onSurface = anim(onSurface, "onSurface"),
        surfaceVariant = anim(surfaceVariant, "surfaceVariant"),
        onSurfaceVariant = anim(onSurfaceVariant, "onSurfaceVariant"),
        surfaceTint = anim(surfaceTint, "surfaceTint"),
        inverseSurface = anim(inverseSurface, "inverseSurface"),
        inverseOnSurface = anim(inverseOnSurface, "inverseOnSurface"),
        outline = anim(outline, "outline"),
        outlineVariant = anim(outlineVariant, "outlineVariant"),
        surfaceBright = anim(surfaceBright, "surfaceBright"),
        surfaceDim = anim(surfaceDim, "surfaceDim"),
        surfaceContainer = anim(surfaceContainer, "surfaceContainer"),
        surfaceContainerHigh = anim(surfaceContainerHigh, "surfaceContainerHigh"),
        surfaceContainerHighest = anim(surfaceContainerHighest, "surfaceContainerHighest"),
        surfaceContainerLow = anim(surfaceContainerLow, "surfaceContainerLow"),
        surfaceContainerLowest = anim(surfaceContainerLowest, "surfaceContainerLowest")
    )
}


/**
 * Traduce el nombre guardado en preferencias al [PaletteStyle] correspondiente.
 *
 * Se persiste el NOMBRE y no el ordinal a propósito: si una versión futura de MaterialKolor
 * reordena el enum, un ordinal guardado pasaría a significar otro estilo sin que nada falle.
 * Un nombre desconocido (estilo retirado de la librería) cae al default en vez de reventar.
 */
fun paletteStyleFromName(name: String): PaletteStyle =
    PaletteStyle.entries.firstOrNull { it.name == name } ?: PaletteStyle.TonalSpot
