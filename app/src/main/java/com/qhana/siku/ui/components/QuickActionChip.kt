package com.qhana.siku.ui.components

import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.qhana.siku.ui.theme.AppColors

/**
 * Tope de caracteres del label de un chip que fija Material. Las etiquetas FIJAS lo cumplen
 * escritas; el que no puede garantizarlo es el chip de GÉNERO, cuyo texto es el tag crudo del
 * archivo ("Progressive Metal/Fusion") y no lo elige nadie de este lado.
 */
private const val CHIP_LABEL_MAX_CHARS = 20

/**
 * Ancho medio de un glifo en fracción del tamaño de fuente. Aproximación (la Google Sans Flex no
 * es monoespaciada), suficiente porque lo que se busca es el punto donde CORTAR, no medir el texto.
 */
private const val CHIP_LABEL_AVG_CHAR_EM = 0.5f

/** Tamaño del glifo del chip (`AssistChipDefaults` lo tabula en 18dp). */
private val CHIP_ICON_SIZE = 18.sp

/**
 * Chip de una acción rápida: `AssistChip` real de M3 con relleno tonal y sin borde.
 *
 * Vive en `components` y no en `HomeScreen` desde el 22 ago 2026, cuando la pestaña **Todas**
 * estrenó los suyos: son la MISMA acción con el mismo aspecto en dos superficies, y escritos dos
 * veces nada obligaría a que el relleno, el tamaño del glifo o el recorte del label coincidieran.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickActionChip(
    icon: String,
    label: String,
    enabled: Boolean = true,
    fill: Boolean = false,
    onClick: () -> Unit
) {
    // El tope se calcula en ANCHO y a partir del tamaño de fuente VIVO, no como un dp fijo: así
    // sube con la escala tipográfica del sistema y el chip sigue mostrando los mismos ~20
    // caracteres en vez de recortar antes. Recortar el String sería peor —el corte debe caer donde
    // la fuente diga, y la elipsis es cosa del layout.
    val maxLabelWidth = with(LocalDensity.current) {
        (MaterialTheme.typography.labelLarge.fontSize * CHIP_LABEL_MAX_CHARS * CHIP_LABEL_AVG_CHAR_EM).toDp()
    }
    AssistChip(
        onClick = onClick,
        enabled = enabled,
        label = {
            Text(
                label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = maxLabelWidth)
            )
        },
        // Sin color explícito: el icono hereda el leadingIconContentColor del chip (y se
        // atenúa solo cuando está deshabilitado).
        leadingIcon = { MaterialSymbol(icon, size = CHIP_ICON_SIZE, fill = fill) },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = AppColors.secondaryContainer,
            labelColor = AppColors.onSecondaryContainer,
            leadingIconContentColor = AppColors.onSecondaryContainer
        ),
        border = null
    )
}
