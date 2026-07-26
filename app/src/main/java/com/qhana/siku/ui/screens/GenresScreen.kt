package com.qhana.siku.ui.screens

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qhana.siku.R
import com.qhana.siku.data.local.GenreSummary
import com.qhana.siku.ui.components.AdaptiveCollage
import com.qhana.siku.ui.components.MaterialSymbol
import com.qhana.siku.ui.components.TonalChip

/**
 * Pestaña "Géneros": cuadrícula de 2 columnas, misma familia visual que Álbumes (tarjeta con
 * imagen arriba + contenido y play abajo). Como un género no tiene carátula propia, la imagen es
 * un COLLAGE de hasta cuatro portadas distintas de sus canciones.
 *
 * El toolbar lleva, además del conteo, el chip "géneros compuestos": los tags GENRE reales vienen
 * sucios ("Rock", "Rock/Metal", "Hard Rock") y no hay normalización que no pierda información, así
 * que el usuario decide si al abrir un género entra también lo que lo CONTIENE. Es un ajuste
 * persistido, no un filtro de sesión: cambia también lo que reproducen los chips del inicio.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GenresScreen(
    genres: List<GenreSummary>,
    onGenreClick: (String) -> Unit,
    onPlayGenre: (String) -> Unit,
    partialMatch: Boolean,
    onPartialMatchChange: (Boolean) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    // Vacío REAL: no hay ninguna canción con tag de género. No es lo mismo que "no hay
    // resultados" — aquí no hay filtro que aflojar, así que el texto explica de dónde sale el dato.
    if (genres.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 32.dp)
            ) {
                MaterialSymbol(
                    "genres",
                    size = 64.sp,
                    color = colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.genre_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.genre_empty_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            // El top que llega incluye el alto del header (el contenido pasa por debajo de
            // TopBar+tabs): NO descartarlo o la cuadrícula nace tapada por las tabs.
            top = contentPadding.calculateTopPadding() + 4.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp
        ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "genre_toolbar", span = { GridItemSpan(maxLineSpan) }, contentType = "listToolbar") {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                // Centrar cada fila: el TonalChip de conteo es más alto que los demás chips.
                itemVerticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                TonalChip {
                    Text(
                        text = pluralStringResource(R.plurals.genre_count, genres.size, genres.size),
                        style = MaterialTheme.typography.labelLarge,
                        color = colorScheme.onSecondaryContainer
                    )
                }
                FilterChip(
                    selected = partialMatch,
                    onClick = { onPartialMatchChange(!partialMatch) },
                    label = { Text(stringResource(R.string.genre_partial_match)) },
                    leadingIcon = if (partialMatch) {
                        { MaterialSymbol("check", size = 18.sp) }
                    } else null
                )
            }
        }
        items(genres, key = { it.name }) { genre ->
            GenreTileCard(
                genre = genre,
                onClick = { onGenreClick(genre.name) },
                onPlayClick = { onPlayGenre(genre.name) },
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope
            )
        }
    }
}

/**
 * Tarjeta de género: collage de portadas con badge de conteo + nombre y play. Misma anatomía que
 * la tarjeta de álbum para que la cuadrícula se lea igual al deslizar entre pestañas; el collage
 * (y no una sola portada) es lo que distingue de un vistazo un género de un álbum.
 */
@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun GenreTileCard(
    genre: GenreSummary,
    onClick: () -> Unit,
    onPlayClick: () -> Unit,
    modifier: Modifier = Modifier,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceContainerHigh),
        modifier = modifier.fillMaxWidth()
    ) {
        val sharedModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null) {
            with(sharedTransitionScope) {
                Modifier.sharedBounds(
                    sharedContentState = rememberSharedContentState(key = "genre_image_${genre.name}"),
                    animatedVisibilityScope = animatedVisibilityScope
                )
            }
        } else Modifier

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .then(sharedModifier)
                .background(colorScheme.surfaceContainerHighest)
        ) {
            val arts = genre.arts
            if (arts.isEmpty()) {
                // Sin ninguna portada: glifo de relleno. AdaptiveCollage NO rellena huecos
                // repitiendo imágenes, así que el placeholder es cosa de la pantalla.
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    MaterialSymbol("genres", size = 40.sp, color = colorScheme.onSurfaceVariant)
                }
            } else {
                AdaptiveCollage(arts = arts, modifier = Modifier.fillMaxSize())
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colorScheme.secondaryContainer)
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                MaterialSymbol("music_note", size = 13.sp, color = colorScheme.onSecondaryContainer)
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = "${genre.songCount}",
                    style = MaterialTheme.typography.labelMedium,
                    color = colorScheme.onSecondaryContainer
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
        ) {
            Text(
                text = genre.name,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = onPlayClick,
                shapes = IconButtonDefaults.shapes(),
                modifier = Modifier.size(40.dp)
            ) {
                MaterialSymbol("play_circle", size = 30.sp, color = colorScheme.primary, fill = false)
            }
        }
    }
}
