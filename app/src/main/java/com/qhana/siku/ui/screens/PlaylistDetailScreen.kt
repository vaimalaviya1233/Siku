package com.qhana.siku.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.qhana.siku.R
import com.qhana.siku.data.model.Song
import com.qhana.siku.ui.components.AdaptiveCollage
import com.qhana.siku.ui.components.ComponentConfig
import com.qhana.siku.ui.components.DetailPlayButtons
import com.qhana.siku.ui.components.MaterialSymbol
import com.qhana.siku.ui.components.SongItem
import com.qhana.siku.ui.components.SongRowContainer
import com.qhana.siku.ui.components.SongQueueOverflowButton
import com.qhana.siku.ui.components.TonalChip
import com.qhana.siku.ui.components.rememberActiveRowContentColor
import com.qhana.siku.ui.components.rememberListItemShape
import com.qhana.siku.ui.components.songRowBackground
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

import com.qhana.siku.ui.theme.appEffectsSpec

/**
 * Detalle de lista de reproducción / favoritos, con el MISMO lenguaje inmersivo que álbum y
 * artista: cabecera edge-to-edge (collage de carátulas) con scrim, título viajero que escala
 * hasta la topbar mínima al scrollear, botonera compacta y canciones en tarjetas segmentadas.
 *
 * Antes tenía TopAppBar clásica + collage centrado en tarjeta: era la única pantalla de detalle
 * fuera del patrón.
 */
@Composable
fun PlaylistDetailScreen(
    playlistName: String,
    songs: List<Song>,
    currentSong: Song?,
    isFavoritesList: Boolean = false,
    onBackClick: () -> Unit,
    onPlayAll: (List<Song>, Int) -> Unit,
    onShufflePlay: (List<Song>) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onAddToQueue: (Song) -> Unit,
    /** Encolar TODAS las canciones de la lista, desde la botonera de la cabecera. */
    onAddAllToQueue: (List<Song>) -> Unit,
    onReorderSongs: ((List<String>) -> Unit)? = null,
    onRemoveSong: ((String) -> Unit)? = null,
    onAddSongs: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    // Copia local para que el arrastre se vea fluido; el orden se persiste al soltar.
    var localSongs by remember(songs) { mutableStateOf(songs) }

    // Los dos primeros ítems del LazyColumn son la cabecera y la botonera: los índices que
    // reporta la librería de reordenado son de la LISTA LAZY, no de las canciones.
    val headerItems = 2
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val fromIndex = from.index - headerItems
        val toIndex = to.index - headerItems
        if (fromIndex in localSongs.indices && toIndex in localSongs.indices) {
            localSongs = localSongs.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
        }
    }

    // Título viajero: interpola posición y escala entre el nombre del header y el hueco de la
    // topbar según el scroll (mismo mecanismo que AlbumDetailScreen).
    val titleFadePx = with(LocalDensity.current) { ComponentConfig.DetailTitleFadeRange.toPx() }
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { overlayOrigin = it.positionInRoot() }
        ) {
            if (songs.isEmpty()) {
                EmptyPlaylistState(
                    isFavoritesList = isFavoritesList,
                    onAddSongs = onAddSongs,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                )
            } else {
                // El MiniPlayer global FLOTA sobre esta pantalla: se reserva su alto para que
                // el último ítem pueda scrollear por encima.
                val listBottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                    ComponentConfig.FloatingBarListInset

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = listBottomInset)
                ) {
                    item(key = "header") {
                        PlaylistImmersiveHeader(
                            playlistName = playlistName,
                            isFavoritesList = isFavoritesList,
                            songs = localSongs,
                            onTitlePositioned = { pos, height ->
                                headerTitleAnchor = pos
                                headerTitleHeight = height
                            }
                        )
                    }

                    item(key = "actions") {
                        // "Añadir canciones" = botón redondo junto al aleatorio (onAddSongs);
                        // reemplazó al FAB flotante. Solo en listas editables (no Favoritos).
                        DetailPlayButtons(
                            onPlayAll = { onPlayAll(localSongs, 0) },
                            onShuffle = { onShufflePlay(localSongs) },
                            onAddToQueue = { onAddAllToQueue(localSongs) },
                            onAddSongs = onAddSongs,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
                        )
                    }

                    itemsIndexed(
                        items = localSongs,
                        key = { _, song -> song.id }
                    ) { index, song ->
                        // La canción actual, esté sonando o en PAUSA: mismo criterio que la cola y
                        // la lista de canciones (el resaltado marca "cargada", no "sonando ahora").
                        val isPlaying = currentSong?.id == song.id
                        // isActive: el ítem en reproducción usa la forma redondeada (16 dp), igual
                        // que en la cola y la lista de canciones, en vez de la esquina agrupada.
                        val shape = rememberListItemShape(index, localSongs.size, isActive = isPlaying)
                        val canReorder = onReorderSongs != null && !isFavoritesList

                        if (canReorder) {
                            ReorderableItem(reorderState, key = song.id) { isDragging ->
                                val elevation = if (isDragging) 8.dp else 0.dp
                                // El resaltado del ítem en reproducción lo pinta la Surface de TODA
                                // la fila —igual que en la cola, que tiene este mismo layout— y no
                                // el `ListItem` de SongItem: aquí SongItem es un `weight(1f)` entre
                                // el grip y los botones, así que su tinte cubría solo el trozo
                                // central, con esquinas rectas, y la fila activa se leía distinta
                                // que en el resto de listas de la app.
                                val rowBackground = songRowBackground(colorScheme.surfaceContainer, isPlaying)
                                val activeContent = rememberActiveRowContentColor(rowBackground, isPlaying)
                                val rowVariantColor =
                                    if (isPlaying) activeContent else colorScheme.onSurfaceVariant
                                // Punta ORIGEN del container transform hacia el reproductor (ver
                                // [SongRowContainer]). Va DENTRO del `ReorderableItem`: lo que se
                                // arrastra es la fila, y lo que morfa es esa misma superficie.
                                SongRowContainer(
                                    songId = song.id,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 1.dp)
                                ) {
                                    Surface(
                                        color = rowBackground,
                                        contentColor = if (isPlaying) activeContent else colorScheme.onSurface,
                                        shape = shape,
                                        tonalElevation = elevation,
                                        shadowElevation = elevation,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        // Mismo layout que la cola: grip de reordenado LEADING (Box
                                        // de 32dp con draggableHandle) + SongItem + quitar TRAILING.
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .draggableHandle(
                                                        onDragStopped = {
                                                            onReorderSongs?.invoke(localSongs.map { it.id })
                                                        }
                                                    )
                                                    .width(32.dp)
                                                    .fillMaxHeight(),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                MaterialSymbol("drag_indicator", color = rowVariantColor, size = 20.sp)
                                            }
                                            SongItem(
                                                song = song,
                                                isPlaying = isPlaying,
                                                showStatusIcon = false,
                                                // La Surface de arriba ya rellena la fila entera: aquí
                                                // solo se tiñe el texto con el `on-` de ese relleno.
                                                showActiveBackground = false,
                                                activeContentColor = activeContent,
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clickable { onPlayAll(localSongs, index) }
                                            )
                                            SongQueueOverflowButton(
                                                onAddToQueue = { onAddToQueue(song) },
                                                rowBackground = rowBackground
                                            )
                                            onRemoveSong?.let { remove ->
                                                val removeDesc = stringResource(R.string.playlist_remove_song)
                                                IconButton(
                                                    onClick = { remove(song.id) },
                                                    modifier = Modifier
                                                        .padding(end = 4.dp)
                                                        .semantics { contentDescription = removeDesc }
                                                ) {
                                                    MaterialSymbol("close", color = rowVariantColor, size = 20.sp)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            // Punta ORIGEN del container transform hacia el reproductor; ver
                            // [SongRowContainer].
                            SongRowContainer(
                                songId = song.id,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 1.dp)
                            ) {
                                Surface(
                                    color = colorScheme.surfaceContainer,
                                    shape = shape,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    SongItem(
                                        song = song,
                                        isPlaying = isPlaying,
                                        showStatusIcon = false,
                                        modifier = Modifier.clickable { onPlayAll(localSongs, index) },
                                        trailingContent = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                SongQueueOverflowButton(
                                                    onAddToQueue = { onAddToQueue(song) },
                                                    rowBackground = songRowBackground(colorScheme.surfaceContainer, isPlaying)
                                                )
                                                if (isFavoritesList) {
                                                    IconButton(onClick = { onToggleFavorite(song.id) }) {
                                                        MaterialSymbol("favorite", fill = true, color = colorScheme.primary)
                                                    }
                                                }
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // TopBar mínima: el back siempre visible (flota sobre el collage); fondo y título
            // se funden a la vista cuando el nombre grande sale de pantalla.
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(colorScheme.surface.copy(alpha = if (songs.isEmpty()) 1f else topBarAlpha))
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
                    // Con canciones: caja de alto cero cuya posición es el DESTINO del título
                    // viajero. Sin canciones no hay cabecera de la que viajar, así que el
                    // nombre se escribe aquí directamente.
                    if (songs.isEmpty()) {
                        Text(
                            text = playlistName,
                            style = MaterialTheme.typography.titleMedium,
                            color = colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp)
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp)
                                .onGloballyPositioned { barTitleAnchor = it.positionInRoot() }
                        )
                    }
                }
            }

            if (songs.isNotEmpty()) {
                Text(
                    text = playlistName,
                    style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                    color = colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 40.dp)
                        .graphicsLayer {
                            val f = FastOutSlowInEasing.transform(topBarAlpha)
                            // titleMedium (16sp) / headlineLarge (32sp)
                            val endScale = 16f / 32f
                            val scale = lerp(1f, endScale, f)
                            val startX = headerTitleAnchor.x - overlayOrigin.x
                            val startY = headerTitleAnchor.y - overlayOrigin.y
                            val endX = barTitleAnchor.x - overlayOrigin.x
                            val endY = (barTitleAnchor.y - overlayOrigin.y) - (headerTitleHeight * endScale) / 2f
                            translationX = lerp(startX, endX, f)
                            translationY = lerp(startY, endY, f)
                            scaleX = scale
                            scaleY = scale
                            transformOrigin = TransformOrigin(0f, 0f)
                        }
                )
            }
        }
    }
}

@Composable
private fun EmptyPlaylistState(
    isFavoritesList: Boolean,
    onAddSongs: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            MaterialSymbol(
                if (isFavoritesList) "favorite" else "queue_music",
                fill = true,
                color = colorScheme.outline,
                size = 64.sp
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = if (isFavoritesList) stringResource(R.string.favorites_empty)
                else stringResource(R.string.playlist_empty),
                style = MaterialTheme.typography.titleMedium,
                color = colorScheme.onSurface
            )
            if (!isFavoritesList) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.playlist_empty_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onSurfaceVariant
                )
            }
            // Botón de alta AQUÍ: con la lista vacía no se dibuja la cabecera con
            // DetailPlayButtons (donde vive el "añadir canciones" del caso con canciones),
            // así que sin esto una lista recién creada no tenía NINGUNA forma de llenarse
            // desde su propio detalle. Vale también para Favoritos: la hoja soporta
            // addSongsToFavorites y sin botón quedaba inalcanzable con la lista vacía.
            if (onAddSongs != null) {
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = onAddSongs) {
                    MaterialSymbol("playlist_add", size = 18.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.playlist_add_songs))
                }
            }
        }
    }
}

/**
 * Cabecera inmersiva: [AdaptiveCollage] de hasta 4 carátulas distintas, con su propio placeholder
 * cuando la lista no tiene ninguna.
 */
@Composable
private fun PlaylistImmersiveHeader(
    playlistName: String,
    isFavoritesList: Boolean,
    songs: List<Song>,
    onTitlePositioned: (Offset, Int) -> Unit
) {
    val arts = remember(songs) { songs.mapNotNull { it.albumArtUriString }.distinct().take(4) }
    val totalDurationMin = remember(songs) { songs.sumOf { it.duration } / 60_000 }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(ComponentConfig.DetailHeaderHeight)
    ) {
        when (arts.size) {
            0 -> Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center
            ) {
                MaterialSymbol(
                    if (isFavoritesList) "favorite" else "queue_music",
                    fill = true,
                    size = 96.sp,
                    color = colorScheme.onSurfaceVariant
                )
            }
            else -> AdaptiveCollage(arts, Modifier.matchParentSize())
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
                text = playlistName,
                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                color = colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // PLACEHOLDER invisible: solo aporta layout y su ancla; el texto visible es el
                // título viajero del overlay.
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
                        text = pluralStringResource(R.plurals.song_count, songs.size, songs.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = colorScheme.onSecondaryContainer
                    )
                }
                if (totalDurationMin > 0) {
                    TonalChip {
                        MaterialSymbol("schedule", size = 14.sp, color = colorScheme.onSecondaryContainer)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "$totalDurationMin min",
                            style = MaterialTheme.typography.labelMedium,
                            color = colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
        }
    }
}
