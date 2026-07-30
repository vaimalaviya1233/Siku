package com.qhana.siku.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.qhana.siku.R
import com.qhana.siku.data.local.AlbumSummary
import com.qhana.siku.ui.theme.AppBoundsTransform

/**
 * Tarjeta de álbum: `Card` tonal con la carátula arriba (esquinas superiores redondeadas por el
 * recorte del card) llevando un badge con el nº de canciones, y debajo el contenido —nombre +
 * artista a la izquierda y botón de play a la derecha—.
 *
 * Es la ÚNICA tarjeta de álbum de la app: la usan tanto la cuadrícula de la pestaña Álbumes como
 * el carrusel del detalle de artista, que antes tenía su propio formato (carátula suelta con el
 * play flotando encima y el texto colgando debajo, sin contenedor). Dos diseños para la misma
 * entidad se leían como dos cosas distintas.
 *
 * La carátula es shared element (key `album_image_<nombre>`) hacia el header del detalle de
 * álbum cuando se le pasan los dos scopes.
 */
@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AlbumTileCard(
    album: AlbumSummary,
    onClick: () -> Unit,
    onPlayClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** En el detalle de artista sobra: todos los álbumes son de ese mismo artista. */
    showArtist: Boolean = true,
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
                    sharedContentState = rememberSharedContentState(key = "album_image_${album.name}"),
                    animatedVisibilityScope = animatedVisibilityScope,
                    // Spring del tema en vez del default de la API (ver AppBoundsTransform).
                    boundsTransform = AppBoundsTransform
                )
            }
        } else Modifier

        // Carátula cuadrada + badge de nº de canciones.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .then(sharedModifier)
                .background(colorScheme.surfaceContainerHighest)
        ) {
            if (album.albumArtUri != null) {
                AsyncImage(
                    model = album.albumArtUri,
                    contentDescription = stringResource(R.string.album_art_desc, album.name),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    MaterialSymbol("music_note", size = 40.sp, color = colorScheme.onSurfaceVariant)
                }
            }
            // Badge tonal sólido (sin glassmorphism) con el conteo de canciones.
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
                    text = "${album.songCount}",
                    style = MaterialTheme.typography.labelMedium,
                    color = colorScheme.onSecondaryContainer
                )
            }
        }

        // Contenido: nombre + artista (izquierda) y botón de play (derecha).
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = album.name.ifBlank { stringResource(R.string.common_unknown_album) },
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (showArtist) {
                    Text(
                        text = album.artist.ifBlank { stringResource(R.string.common_unknown_artist) },
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            // SIN fondo; el glifo `play_circle` (disco relleno) se lee como botón. En primary.
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
