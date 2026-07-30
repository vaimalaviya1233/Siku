package com.qhana.siku.ui.screens

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qhana.siku.R
import com.qhana.siku.data.model.PlaybackState
import com.qhana.siku.data.model.Playlist
import com.qhana.siku.data.model.Song
import com.qhana.siku.ui.components.AddToPlaylistBottomSheet
import com.qhana.siku.ui.components.AdaptiveCollage
import com.qhana.siku.ui.components.ComponentConfig
import com.qhana.siku.ui.components.CreatePlaylistDialog
import com.qhana.siku.ui.components.DetailPlayButtons
import com.qhana.siku.ui.components.MaterialSymbol
import com.qhana.siku.ui.components.SongItem
import com.qhana.siku.ui.components.SongOverflowButton
import com.qhana.siku.ui.components.TonalChip
import com.qhana.siku.ui.components.overSharedElementsModifier
import com.qhana.siku.ui.components.rememberListItemShape
import com.qhana.siku.ui.viewmodel.BrowseViewModel

import com.qhana.siku.ui.theme.appEffectsSpec
import com.qhana.siku.ui.theme.AppBoundsTransform

/**
 * Detalle de un género: mismo diseño inmersivo que el detalle de álbum/artista (header
 * edge-to-edge con scrim, botonera compacta, canciones en tarjetas segmentadas y título que
 * viaja a la topbar con el scroll). La imagen del header es el COLLAGE de portadas del género,
 * que llega volando desde su tarjeta en la pestaña.
 *
 * Las canciones salen de [BrowseViewModel.getGenreSongs], que sigue en vivo el ajuste "incluir
 * géneros compuestos": activarlo desde la pestaña reordena esta lista sin salir de la pantalla.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenreDetailScreen(
    genreName: String,
    currentSong: Song?,
    playbackState: PlaybackState,
    favorites: Set<String>,
    playlists: List<Playlist>,
    onBackClick: () -> Unit,
    onPlayAll: (List<Song>, Int) -> Unit,
    onShufflePlay: (List<Song>) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onAddSongToPlaylist: (Long, String) -> Unit,
    /** Crear lista nueva; [pendingSongId] = canción del flujo "agregar a lista" que debe nacer dentro. */
    onCreatePlaylist: (name: String, pendingSongId: String?) -> Unit,
    modifier: Modifier = Modifier,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    viewModel: BrowseViewModel = hiltViewModel()
) {
    val songs by remember(genreName) { viewModel.getGenreSongs(genreName) }
        .collectAsStateWithLifecycle(emptyList())

    // Hasta ARTS_PER_GENRE portadas distintas para el collage del header, derivadas de las
    // canciones que ya están en pantalla (no hace falta volver a la BD).
    val arts = remember(songs) {
        songs.mapNotNull { it.albumArtUriString }.distinct().take(HEADER_ARTS)
    }
    val artistCount = remember(songs) { songs.map { it.artist }.distinct().size }

    var songIdForPlaylist by remember { mutableStateOf<String?>(null) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var pendingSongForNewPlaylist by remember { mutableStateOf<String?>(null) }

    // Título viajero: mismas anclas y mismo criterio de snap que el detalle de álbum.
    val listState = rememberLazyListState()
    val titleFadePx = with(LocalDensity.current) { TITLE_FADE_RANGE.toPx() }
    val rawTitleFraction by remember {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) 1f
            else (listState.firstVisibleItemScrollOffset / titleFadePx).coerceIn(0f, 1f)
        }
    }
    val topBarAlpha by animateFloatAsState(
        targetValue = when {
            listState.isScrollInProgress -> rawTitleFraction
            !listState.canScrollForward && rawTitleFraction > 0.05f -> 1f
            rawTitleFraction >= 0.5f -> 1f
            else -> 0f
        },
        // Ver el kdoc de este mismo bloque en ArtistDetailScreen: effects (sin rebote) porque la
        // fracción conduce a la vez la posición del título y un alpha.
        animationSpec = appEffectsSpec(),
        label = "topBarFraction"
    )
    var overlayOrigin by remember { mutableStateOf(Offset.Zero) }
    var headerTitleAnchor by remember { mutableStateOf(Offset.Zero) }
    var headerTitleHeight by remember { mutableIntStateOf(0) }
    var barTitleAnchor by remember { mutableStateOf(Offset.Zero) }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { paddingValues ->
        if (songs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    MaterialSymbol(
                        "genres",
                        size = 64.sp,
                        color = colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.genre_empty_detail),
                        style = MaterialTheme.typography.bodyLarge,
                        color = colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            val listBottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                ComponentConfig.FloatingBarListInset
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { overlayOrigin = it.positionInRoot() }
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = listBottomInset)
                ) {
                    item {
                        GenreImmersiveHeader(
                            genreName = genreName,
                            arts = arts,
                            songCount = songs.size,
                            artistCount = artistCount,
                            sharedTransitionScope = sharedTransitionScope,
                            animatedVisibilityScope = animatedVisibilityScope,
                            onTitlePositioned = { pos, height ->
                                headerTitleAnchor = pos
                                headerTitleHeight = height
                            }
                        )
                    }

                    item {
                        DetailPlayButtons(
                            onPlayAll = { onPlayAll(songs, 0) },
                            onShuffle = { onShufflePlay(songs) },
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
                        )
                    }

                    itemsIndexed(
                        items = songs,
                        key = { _, song -> song.id }
                    ) { index, song ->
                        Surface(
                            color = colorScheme.surfaceContainer,
                            shape = rememberListItemShape(index, songs.size),
                            modifier = Modifier
                                .animateItem()
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 1.dp)
                        ) {
                            SongItem(
                                song = song,
                                isPlaying = currentSong?.id == song.id && playbackState == PlaybackState.PLAYING,
                                modifier = Modifier.clickable { onPlayAll(songs, index) },
                                trailingContent = {
                                    SongOverflowButton(
                                        isFavorite = song.id in favorites,
                                        onToggleFavorite = { onToggleFavorite(song.id) },
                                        onAddToPlaylist = { songIdForPlaylist = song.id }
                                    )
                                }
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .then(overSharedElementsModifier(sharedTransitionScope, animatedVisibilityScope))
                        .background(colorScheme.surface.copy(alpha = topBarAlpha))
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        FilledIconButton(
                            onClick = onBackClick,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = colorScheme.surfaceContainer,
                                contentColor = colorScheme.onSurface
                            )
                        ) {
                            MaterialSymbol("arrow_back")
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp)
                                .onGloballyPositioned { barTitleAnchor = it.positionInRoot() }
                        )
                    }
                }

                Text(
                    text = genreName,
                    style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                    color = colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 40.dp)
                        .then(overSharedElementsModifier(sharedTransitionScope, animatedVisibilityScope))
                        .graphicsLayer {
                            val f = FastOutSlowInEasing.transform(topBarAlpha)
                            // titleMedium (16sp) / headlineLarge (32sp)
                            val endScale = 16f / 32f
                            val scale = androidx.compose.ui.util.lerp(1f, endScale, f)
                            val startX = headerTitleAnchor.x - overlayOrigin.x
                            val startY = headerTitleAnchor.y - overlayOrigin.y
                            val endX = barTitleAnchor.x - overlayOrigin.x
                            val endY = (barTitleAnchor.y - overlayOrigin.y) - (headerTitleHeight * endScale) / 2f
                            translationX = androidx.compose.ui.util.lerp(startX, endX, f)
                            translationY = androidx.compose.ui.util.lerp(startY, endY, f)
                            scaleX = scale
                            scaleY = scale
                            transformOrigin = TransformOrigin(0f, 0f)
                        }
                )
            }
        }
    }

    if (songIdForPlaylist != null) {
        AddToPlaylistBottomSheet(
            playlists = playlists,
            onPlaylistSelected = { playlistId ->
                onAddSongToPlaylist(playlistId, songIdForPlaylist!!)
                songIdForPlaylist = null
            },
            onCreateNewPlaylist = {
                pendingSongForNewPlaylist = songIdForPlaylist
                songIdForPlaylist = null
                showCreatePlaylistDialog = true
            },
            onDismiss = { songIdForPlaylist = null }
        )
    }

    if (showCreatePlaylistDialog) {
        CreatePlaylistDialog(
            onDismiss = {
                showCreatePlaylistDialog = false
                pendingSongForNewPlaylist = null
            },
            onConfirm = { name ->
                onCreatePlaylist(name, pendingSongForNewPlaylist)
                pendingSongForNewPlaylist = null
                showCreatePlaylistDialog = false
            }
        )
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun GenreImmersiveHeader(
    genreName: String,
    arts: List<String>,
    songCount: Int,
    artistCount: Int,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    onTitlePositioned: (Offset, Int) -> Unit = { _, _ -> }
) {
    val sharedModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null) {
        with(sharedTransitionScope) {
            Modifier.sharedBounds(
                sharedContentState = rememberSharedContentState(key = "genre_image_$genreName"),
                animatedVisibilityScope = animatedVisibilityScope,
                // Spring del tema en vez del default de la API (ver AppBoundsTransform).
                boundsTransform = AppBoundsTransform
            )
        }
    } else Modifier

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(HEADER_HEIGHT)
            .then(sharedModifier)
    ) {
        if (arts.isEmpty()) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center
            ) {
                MaterialSymbol("genres", size = 96.sp, color = colorScheme.onSurfaceVariant)
            }
        } else {
            AdaptiveCollage(arts = arts, modifier = Modifier.matchParentSize())
        }

        // Scrim inferior: funde el collage con el fondo y da contraste al texto.
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0.4f to Color.Transparent,
                        1f to colorScheme.surface
                    )
                )
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Text(
                text = genreName,
                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                color = colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // PLACEHOLDER invisible: aporta el layout y reporta su ancla; el texto visible
                // es el título viajero del overlay.
                modifier = Modifier
                    .graphicsLayer { alpha = 0f }
                    .onGloballyPositioned { onTitlePositioned(it.positionInRoot(), it.size.height) }
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TonalChip {
                    MaterialSymbol("music_note", size = 14.sp, color = colorScheme.onSecondaryContainer)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = pluralStringResource(R.plurals.song_count, songCount, songCount),
                        style = MaterialTheme.typography.labelMedium,
                        color = colorScheme.onSecondaryContainer
                    )
                }
                TonalChip {
                    MaterialSymbol("artist", size = 14.sp, color = colorScheme.onSecondaryContainer)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = pluralStringResource(R.plurals.artist_count, artistCount, artistCount),
                        style = MaterialTheme.typography.labelMedium,
                        color = colorScheme.onSecondaryContainer
                    )
                }
            }
        }
    }
}

/** Portadas del collage del header (las mismas que la tarjeta de la pestaña). */
private const val HEADER_ARTS = 4

/** Alto del header inmersivo, igual que en artista/álbum. */
private val HEADER_HEIGHT = 380.dp

/** Recorrido de scroll en el que el título viaja del header a la topbar. */
private val TITLE_FADE_RANGE = 300.dp
