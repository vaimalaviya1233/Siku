package com.qhana.siku.ui.components

import android.content.Context
import android.content.res.AssetManager
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Componente para renderizar iconos de Material Symbols (Variable Font).
 * Permite personalizar peso, relleno, grado y tamaño óptico.
 *
 * @param icon El nombre del icono (ligadura) ej. "search", "play_arrow", "home".
 * @param fill Si el icono debe estar relleno (true) o delineado (false).
 * @param weight El peso de la fuente (100 a 700). Default 400.
 * @param grade El grado (-25 a 200). Ajuste fino del grosor. Default 0.
 * @param opticalSize El tamaño óptico (20 a 48). Debe coincidir aprox con el tamaño visual. Default 24.
 */
@Composable
fun MaterialSymbol(
    icon: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 24.sp,
    color: Color = LocalContentColor.current,
    fill: Boolean = false,
    weight: Int = DEFAULT_WEIGHT,
    grade: Int = DEFAULT_GRADE,
    opticalSize: Int = DEFAULT_OPTICAL_SIZE
) {
    val context = LocalContext.current
    val fillValue = if (fill) 1f else 0f

    // Cache del font con una key estable (context no cambia durante la sesión)
    val font = remember(fillValue, weight, grade, opticalSize) {
        MaterialSymbolFont.family(context.assets, fillValue, weight, grade, opticalSize)
    }

    // Cache del TextStyle base para evitar recrearlo
    val textStyle = remember(size) {
        TextStyle(
            platformStyle = PlatformTextStyle(includeFontPadding = false),
            lineHeight = size,
            lineHeightStyle = LineHeightStyle(
                alignment = LineHeightStyle.Alignment.Center,
                // `Trim.Both` y no `None`: esto es un GLIFO, no un párrafo. Con `None` la caja
                // conserva el espacio que la fuente reserva sobre el ascender y bajo el descender,
                // y ese espacio NO es simétrico — así que la caja que mide Compose no tiene el
                // dibujo en su centro. Se nota en cuanto el símbolo convive con texto y los dos se
                // centran por caja (el play de la botonera de detalles salía más bajo que su
                // etiqueta), y también, más sutil, dentro de cualquier botón de icono.
                //
                // Recortándolo, la caja pasa a medir exactamente `lineHeight` —o sea `size`, el
                // tamaño nominal del icono— con el glifo centrado dentro, que es justo lo que
                // asume quien lo coloca. Requiere `includeFontPadding = false`, que ya está
                // arriba; sin eso `Trim` no hace nada.
                trim = LineHeightStyle.Trim.Both
            )
        )
    }

    Text(
        text = icon,
        modifier = modifier,
        color = color,
        fontSize = size,
        fontFamily = font,
        textAlign = TextAlign.Center,
        style = textStyle
    )
}

/**
 * Icono para el slot `leadingIcon`/`selectedLeadingIcon` de un `DropdownMenuItem`.
 *
 * Existe para que el tamaño salga del TOKEN del menú (`MenuDefaults.LeadingIconSize`, 20dp) y no
 * del default de [MaterialSymbol], que son 24sp. La diferencia no es cosmética: el componente
 * reserva el hueco del icono midiendo lo que le pasan, así que un glifo más grande que el token
 * empuja el texto del item y los items dejan de alinear entre sí.
 *
 * El token viene en dp y [MaterialSymbol] dimensiona en sp —es una fuente variable, no un
 * vectorial—, así que se convierte con la densidad en vez de escribir "20.sp" a ojo: con
 * `fontScale` distinto de 1 esas dos cosas dejan de medir lo mismo. `opticalSize` acompaña al
 * tamaño visual, que es justo para lo que está (ver el kdoc de [MaterialSymbol]).
 */
@Composable
fun MenuItemIcon(
    icon: String,
    modifier: Modifier = Modifier,
    color: Color = LocalContentColor.current,
    fill: Boolean = false
) {
    val tokenSize = MenuDefaults.LeadingIconSize
    MaterialSymbol(
        icon = icon,
        modifier = modifier,
        size = with(LocalDensity.current) { tokenSize.toSp() },
        color = color,
        fill = fill,
        opticalSize = MenuIconOpticalSize
    )
}

/**
 * Tamaño óptico de los iconos de menú. Acompaña a los 20dp del token: la fuente de Material
 * Symbols trae ejes por tamaño y usar el de 24 en un glifo dibujado a 20 lo engorda.
 */
private const val MenuIconOpticalSize = 20

/**
 * La fuente de Material Symbols como FAMILIA por variante, y su precalentamiento.
 *
 * Cada combinación distinta de ejes (FILL/wght/GRAD/opsz) es un `Typeface` distinto que Android
 * construye con `Typeface.Builder(assets, path).setFontVariationSettings(...)`, o sea **parseando el
 * archivo entero otra vez**, y ese coste es proporcional al TAMAÑO del archivo. Compose resuelve una
 * fuente de assets de forma BLOQUEANTE dentro del `measure` del primer texto que la pide, así que en
 * un arranque en frío ese parseo caía en el hilo principal, en el segundo frame de la app: medido en
 * Perfetto el 20 ago 2026, dos `TextStringSimpleNode::measure` de **89,8 y 41,4 ms** (una variante
 * cada uno, `fill` 0 y 1) dentro de un frame de 160 ms, con 180 frames de buffer stuffing detrás. El
 * precalentamiento de Google Sans Flex (`rememberAppTypography`) no lo cubría: es otra familia.
 *
 * [preload] construye las variantes que usa la primera pantalla en un hilo de fondo, desde
 * `Application.onCreate`, a través de `createFontFamilyResolver(context)` — que comparte
 * `GlobalTypefaceRequestCache` con el `LocalFontFamilyResolver` de cualquier `ComposeView`
 * (`FontFamilyResolver.android.kt`), y la clave de esa caché compara `AndroidAssetFont` por ruta y
 * ejes, no por instancia — así que lo resuelto aquí es exactamente lo que el primer frame encuentra
 * hecho. Va en la Application y NO en un `LaunchedEffect`: el efecto arranca después de la primera
 * composición, y la primera composición ya pide estos glifos (las pestañas de la biblioteca).
 *
 * **La fuente empaquetada es un SUBSET desde la 1.2.0**: 117 glifos en vez de 4174, 0,24 MB en vez
 * de 14,9 (ver `tools/subset_icon_font.py` y la tarea `verifyIconFontSubset`). Eso reduce el parseo
 * en dos órdenes de magnitud y deja este precalentamiento casi gratis — pero NO lo hace inútil: la
 * resolución sigue siendo bloqueante dentro del `measure`, y sigue siendo mejor pagarla en un hilo
 * de fondo que en el segundo frame. Lo que sí deja de ser cierto es el tamaño de la factura, así
 * que las cifras de arriba son de ANTES del subset y solo valen como historia de por qué existe
 * esto.
 *
 * **Si se añade una variante nueva al arranque (otro `weight`, `grade` u `opticalSize` en pantalla
 * desde el primer frame), añadirla a [STARTUP_VARIANTS]**; una variante que solo aparece más tarde
 * se paga una vez, en caliente y sin frame de arranque de por medio, y no hace falta listarla.
 */
object MaterialSymbolFont {

    private const val PATH = "fonts/material_symbols_rounded.ttf"

    /** Una variante = un `Typeface` = un parseo del archivo. */
    private data class Variant(val fill: Float, val weight: Int, val grade: Int, val opticalSize: Int)

    /**
     * Las variantes visibles en el primer frame: el default de [MaterialSymbol] delineado y relleno
     * (la biblioteca mezcla los dos desde el arranque) y, detrás, las de [MenuItemIcon]
     * (`opticalSize` 20), que no son del primer frame pero sí del primer menú que se abra. El orden
     * es el de necesidad: se construyen en serie y el primer frame espera a las dos primeras.
     */
    private val STARTUP_VARIANTS = listOf(
        Variant(fill = 0f, weight = DEFAULT_WEIGHT, grade = DEFAULT_GRADE, opticalSize = DEFAULT_OPTICAL_SIZE),
        Variant(fill = 1f, weight = DEFAULT_WEIGHT, grade = DEFAULT_GRADE, opticalSize = DEFAULT_OPTICAL_SIZE),
        Variant(fill = 0f, weight = DEFAULT_WEIGHT, grade = DEFAULT_GRADE, opticalSize = MenuIconOpticalSize),
        Variant(fill = 1f, weight = DEFAULT_WEIGHT, grade = DEFAULT_GRADE, opticalSize = MenuIconOpticalSize)
    )

    fun family(
        assetManager: AssetManager,
        fill: Float,
        weight: Int,
        grade: Int,
        opticalSize: Int
    ): FontFamily = FontFamily(
        Font(
            path = PATH,
            assetManager = assetManager,
            variationSettings = FontVariation.Settings(
                FontVariation.Setting("FILL", fill),
                FontVariation.Setting("wght", weight.toFloat()),
                FontVariation.Setting("GRAD", grade.toFloat()),
                FontVariation.Setting("opsz", opticalSize.toFloat())
            )
        )
    )

    /**
     * Puebla la caché global de typefaces con [STARTUP_VARIANTS]. Llamar desde un hilo de fondo
     * (`Dispatchers.Default`: es CPU pura) lo antes posible en la vida del proceso.
     *
     * Pide cada familia con los MISMOS parámetros con los que la pedirá el `Text` de
     * [MaterialSymbol] —peso `Normal`, estilo `Normal`, síntesis por defecto— porque la clave de la
     * caché los incluye: con otro peso sería otra entrada y el primer frame no la encontraría.
     * `FontFamily.Resolver.preload` no sirve para esto (solo precarga fuentes ASYNC, y una de assets
     * es `Blocking`; ver `rememberAppTypography`).
     */
    fun preload(context: Context) {
        val resolver = createFontFamilyResolver(context.applicationContext)
        val assets = context.applicationContext.assets
        STARTUP_VARIANTS.forEach { v ->
            // Un fallo aquí no puede tumbar la app: sin caché, la fuente se resolverá luego por la vía
            // normal y lo único que se pierde es el precalentado.
            runCatching {
                resolver.resolve(
                    fontFamily = family(assets, v.fill, v.weight, v.grade, v.opticalSize),
                    fontWeight = FontWeight.Normal,
                    fontStyle = FontStyle.Normal
                )
            }
        }
    }
}

/** Defaults de [MaterialSymbol]; declarados aparte para que [MaterialSymbolFont] los comparta. */
private const val DEFAULT_WEIGHT = 400
private const val DEFAULT_GRADE = 0
private const val DEFAULT_OPTICAL_SIZE = 24
