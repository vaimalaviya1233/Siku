package com.qhana.siku.ui.components

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
import androidx.compose.ui.text.font.FontVariation
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
    weight: Int = 400,
    grade: Int = 0,
    opticalSize: Int = 24
) {
    val context = LocalContext.current
    val fillValue = if (fill) 1f else 0f

    // Cache del font con una key estable (context no cambia durante la sesión)
    val font = remember(fillValue, weight, grade, opticalSize) {
        createMaterialSymbolFontFamily(context.assets, fillValue, weight, grade, opticalSize)
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

private fun createMaterialSymbolFontFamily(
    assetManager: AssetManager,
    fill: Float,
    weight: Int,
    grade: Int,
    opticalSize: Int
): FontFamily {
    return FontFamily(
        Font(
            path = "fonts/material_symbols_rounded.ttf",
            assetManager = assetManager,
            variationSettings = FontVariation.Settings(
                FontVariation.Setting("FILL", fill),
                FontVariation.Setting("wght", weight.toFloat()),
                FontVariation.Setting("GRAD", grade.toFloat()),
                FontVariation.Setting("opsz", opticalSize.toFloat())
            )
        )
    )
}
