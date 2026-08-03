package com.qhana.siku.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qhana.siku.R

/**
 * Botonera de las pantallas de detalle (artista/álbum/playlist), estilo M3 Expressive:
 * REPRODUCIR = píldora compacta de acento (icono + etiqueta, ancho de contenido);
 * ALEATORIO = círculo tonal. Contraste de formas; no se estiran al ancho del padre.
 * Componentes REALES de material3 (Button / FilledTonalIconButton) con sus `shapes()`
 * Expressive: shape-morph al presionar, en vez de Surface+Box artesanal.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DetailPlayButtons(
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    // Solo en listas editables (playlist): botón redondo "añadir canciones" junto al aleatorio.
    onAddSongs: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val playAllDesc = stringResource(R.string.detail_play_all)
    val shuffleDesc = stringResource(R.string.detail_shuffle)
    val addSongsDesc = stringResource(R.string.playlist_add_songs)
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onPlayAll()
            },
            shapes = ButtonDefaults.shapes(),
            contentPadding = PaddingValues(horizontal = 24.dp),
            modifier = Modifier
                .height(64.dp)
                .semantics { contentDescription = playAllDesc }
        ) {
            MaterialSymbol("play_arrow", size = 28.sp, color = colorScheme.onPrimary, fill = true)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.common_play),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = colorScheme.onPrimary,
                maxLines = 1
            )
        }
        FilledTonalIconButton(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onShuffle()
            },
            shapes = IconButtonDefaults.shapes(),
            modifier = Modifier
                .size(64.dp)
                .semantics { contentDescription = shuffleDesc }
        ) {
            MaterialSymbol("shuffle", size = 24.sp, color = colorScheme.onSecondaryContainer)
        }
        // Añadir canciones (solo playlists editables). Círculo tonal como el aleatorio.
        if (onAddSongs != null) {
            FilledTonalIconButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onAddSongs()
                },
                shapes = IconButtonDefaults.shapes(),
                modifier = Modifier
                    .size(64.dp)
                    .semantics { contentDescription = addSongsDesc }
            ) {
                MaterialSymbol("playlist_add", size = 24.sp, color = colorScheme.onSecondaryContainer)
            }
        }
    }
}

/**
 * Botón de overflow por CANCIÓN (píldora vertical tonal con ⋮) con menú: favoritos y
 * añadir a lista de reproducción.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SongOverflowButton(
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onAddToPlaylist: () -> Unit,
    // Fondo REAL de la fila (tinte del ítem activo ya compuesto, vía `songRowBackground`): de él se
    // derivan los colores de la píldora, ver `rememberRowActionColors`.
    rowBackground: Color,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }
    val optionsDesc = stringResource(R.string.common_song_options)
    val colors = rememberRowActionColors(rowBackground)
    Box(modifier = modifier) {
        // Píldora VERTICAL (M3 Expressive) como FilledIconButton real: shape-morph al presionar.
        FilledIconButton(
            onClick = { showMenu = true },
            shapes = IconButtonDefaults.shapes(),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = colors.container,
                contentColor = colors.content
            ),
            modifier = Modifier
                .width(28.dp)
                .height(44.dp)
                .semantics { contentDescription = optionsDesc }
        ) {
            MaterialSymbol("more_vert", size = 18.sp, color = colors.content)
        }
        // Menú SEGMENTADO (popup + grupo), no el `DropdownMenu` clásico: ver la nota en SortChip.
        DropdownMenuPopup(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            DropdownMenuGroup(shapes = MenuDefaults.groupShapes()) {
                // Favorito es un TOGGLE, no una acción: va con la sobrecarga `checked`
                // (contenedor marcado, morph de forma y `Role.Checkbox`). El texto sigue diciendo
                // qué pasa al tocarlo — el estado lo cuenta el item, no solo el relleno del
                // corazón, que un lector de pantalla no anuncia.
                DropdownMenuItem(
                    checked = isFavorite,
                    onCheckedChange = {
                        showMenu = false
                        onToggleFavorite()
                    },
                    text = { Text(if (isFavorite) stringResource(R.string.common_remove_from_favorites) else stringResource(R.string.common_add_to_favorites)) },
                    // Menú de items fijos: la posición se dice con `leading`/`trailing` en vez de
                    // `itemShape(index, count)`, que obligaría a mantener a mano un total.
                    shapes = MenuDefaults.itemShapes(shape = MenuDefaults.leadingItemShape),
                    leadingIcon = { MenuItemIcon("favorite", fill = isFavorite) }
                )
                DropdownMenuItem(
                    onClick = {
                        showMenu = false
                        onAddToPlaylist()
                    },
                    text = { Text(stringResource(R.string.common_add_to_playlist)) },
                    shape = MenuDefaults.trailingItemShape,
                    leadingIcon = { MenuItemIcon("playlist_add") }
                )
            }
        }
    }
}
