package com.qhana.siku.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qhana.siku.R
import com.qhana.siku.ui.components.*
import com.qhana.siku.ui.theme.rememberAccentPreview

/** Tamaños de las dos muestras de cada fila: el color de la carátula y el acento que generaría. */
private val SwatchSize = 36.dp
private val PreviewWidth = 48.dp
private val PreviewHeight = 24.dp
private val AppliedSwatchSize = 24.dp

/** Luminancia relativa a partir de la cual el check se pinta en negro en vez de blanco. */
private const val CHECK_LUMINANCE_CUTOFF = 0.5f

private fun hexOf(argb: Int): String = String.format("#%06X", 0xFFFFFF and argb)

/**
 * Selector de color de la carátula (long-press sobre el arte).
 *
 * Muestra DOS cosas por candidato porque no son la misma: el círculo es el color tal y como está en
 * la portada, y la barra es el acento que el tema generaría al elegirlo. La distancia entre ambos
 * puede ser enorme —el estilo de paleta impone su propio croma, y con "Equilibrado" un beige de
 * croma 11 sale como un mostaza de croma 32—, y enseñar solo el crudo hacía que el diálogo
 * pareciera no tener nada que ver con lo que aparecía en pantalla.
 *
 * La cabecera dice cuál es el color aplicado AHORA (el `primary` del tema vivo, o sea exactamente el
 * del botón play) junto al seed guardado del que sale. Antes la lista etiquetaba "(actual)" el hex
 * de un candidato, que no es el color aplicado nunca: **el aplicado es un color DERIVADO y no tiene
 * por qué estar en la lista**. Desde el 29 jul 2026 el seed guardado sí es uno de los candidatos —
 * se persiste crudo—, así que de los tres hexes que se ven aquí dos son de la lista y el tercero
 * (el aplicado) es lo que MaterialKolor hace con ellos.
 */
@Composable
internal fun ColorPickerDialog(
    info: Any,
    onColorSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val debugInfo = info as? com.qhana.siku.data.repository.ArtworkRepository.DebugColorInfo ?: return

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        },
        title = { Text(stringResource(R.string.color_picker_title)) },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .fillMaxWidth()
            ) {
                AppliedAccentHeader(savedAccent = debugInfo.winnerColor)

                Spacer(modifier = Modifier.height(16.dp))
                Text(stringResource(R.string.color_picker_prompt), style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = stringResource(R.string.color_picker_legend),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))

                debugInfo.candidates.forEach { candidate ->
                    CandidateRow(candidate = candidate, onClick = { onColorSelected(candidate.color) })
                }
            }
        }
    )
}

/**
 * El color que la app está pintando de verdad. Se lee del propio tema (`primary` = color del botón
 * play) en vez de recalcularlo: cualquier reconstrucción sería una segunda versión de la misma
 * derivación, capaz de discrepar justo aquí, que es donde se viene a comprobar.
 */
@Composable
private fun AppliedAccentHeader(savedAccent: Int) {
    val applied = MaterialTheme.colorScheme.primary
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(AppliedSwatchSize)
                .background(applied, CircleShape)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = stringResource(R.string.color_picker_applied, hexOf(applied.toArgb())),
                style = MaterialTheme.typography.bodyMediumEmphasized
            )
            Text(
                text = stringResource(R.string.color_picker_seed, hexOf(savedAccent)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CandidateRow(
    candidate: com.qhana.siku.data.repository.ArtworkRepository.ColorCandidate,
    onClick: () -> Unit
) {
    val hex = hexOf(candidate.color)
    // Lo que pasaría al tocar ESTA fila: el color se guarda como override manual y se usa de seed
    // tal cual (sin re-tonalizar), así que el seed de la previsualización es el color crudo. Es la
    // MISMA ruta que sigue el automático desde el 29 jul, así que elegir a mano el candidato que el
    // análisis ya había elegido ahora da el mismo resultado — antes daba uno más vivo, porque el
    // automático guardaba el seed proyectado a T80 y perdía el croma.
    val preview = rememberAccentPreview(candidate.color)
    val note = when {
        candidate.isExact -> stringResource(R.string.color_picker_current)
        candidate.isWinner -> stringResource(R.string.color_picker_hue_source)
        else -> null
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp)
    ) {
        // Color tal cual está en la carátula.
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(SwatchSize)
                .background(Color(candidate.color), CircleShape)
                .border(
                    width = if (candidate.isWinner) 2.dp else 1.dp,
                    color = if (candidate.isWinner) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant,
                    shape = CircleShape
                )
                .semantics { contentDescription = hex }
        ) {
            if (candidate.isWinner) {
                // El check se pinta sobre el color de la carátula, que puede ser casi blanco: el
                // blanco fijo desaparecía justo en los candidatos claros.
                val onSwatch = if (Color(candidate.color).luminance() > CHECK_LUMINANCE_CUTOFF) {
                    Color.Black
                } else {
                    Color.White
                }
                MaterialSymbol("check", size = 18.sp, color = onSwatch)
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = hex,
                style = MaterialTheme.typography.bodyMediumEmphasized
            )
            if (note != null) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Acento que generaría el tema con este color.
        Box(
            modifier = Modifier
                .width(PreviewWidth)
                .height(PreviewHeight)
                .background(preview, RoundedCornerShape(4.dp))
                .semantics { contentDescription = hexOf(preview.toArgb()) }
        )
    }
}
