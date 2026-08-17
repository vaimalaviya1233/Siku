package com.qhana.siku.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Fila de una LISTA AGRUPADA de M3, canónica y COMPARTIDA por las listas de navegación de la app.
 *
 * Junta las dos mitades que antes cada pantalla copiaba a mano: el envoltorio de tarjeta (margen
 * lateral de 16 + 2dp de gap entre filas + recorte con la [shape] del grupo + fondo tonal) y el
 * `ListItem` REAL de M3 por dentro, que aporta la ANATOMÍA y el PADDING del spec sin números a mano.
 * Es la misma base que usa `SongItem` para Todas/Cola, así que las cuatro listas miden idéntico.
 *
 * El motivo de existir: Artistas y Listas eran `Row` hechos a mano que replicaban este look cada uno
 * por su cuenta, y como nada obligaba a que las copias coincidieran, sus métricas (padding, gap,
 * trailing) se fueron desincronizando. Con una sola fuente, tocar el lenguaje visual es un cambio
 * en un sitio.
 *
 * Las acciones (play, overflow) van en [trailingContent], el slot trailing del `ListItem`, no como
 * hermanos sueltos del texto — así heredan el margen al borde del spec.
 */
@Composable
fun GroupedListRow(
    shape: Shape,
    onClick: () -> Unit,
    leadingContent: @Composable () -> Unit,
    headlineContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    supportingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            // Margen lateral + 2dp de gap entre filas (1dp por lado), como el envoltorio de
            // SongItem en Todas/Cola. El recorte va con la forma del grupo; el fondo, tonal.
            .padding(horizontal = 16.dp, vertical = 1.dp)
            .clip(shape)
            .background(containerColor)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ListItem(
            leadingContent = leadingContent,
            headlineContent = headlineContent,
            supportingContent = supportingContent,
            trailingContent = trailingContent,
            // Contenedor transparente: el fondo lo pinta el Row de arriba (para poder recortarlo con
            // la forma del grupo). El padding y la altura los pone el ListItem según el spec.
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.weight(1f)
        )
    }
}
