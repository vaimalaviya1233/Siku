package com.qhana.siku.ui.screens

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.runtime.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.qhana.siku.R
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.qhana.siku.data.model.Playlist
import com.qhana.siku.data.model.Song
import com.qhana.siku.ui.components.AddToPlaylistBottomSheet
import com.qhana.siku.ui.components.ComponentConfig
import com.qhana.siku.ui.components.CreatePlaylistDialog
import com.qhana.siku.ui.components.DetailPlayButtons
import com.qhana.siku.ui.components.MaterialSymbol
import com.qhana.siku.ui.components.TonalChip
import com.qhana.siku.ui.components.SongItem
import com.qhana.siku.ui.components.SongRowContainer
import com.qhana.siku.ui.components.SongOverflowButton
import com.qhana.siku.ui.components.songRowBackground
import com.qhana.siku.ui.components.overSharedElementsModifier
import com.qhana.siku.ui.components.rememberListItemShape
import com.qhana.siku.ui.viewmodel.BrowseViewModel

import com.qhana.siku.ui.theme.appEffectsSpec
import com.qhana.siku.ui.theme.AppBoundsTransform
import com.qhana.siku.ui.theme.DetailContentTheme

/**
 * Detalle de álbum, estilo INMERSIVO (misma familia visual que el detalle de artista): carátula
 * grande edge-to-edge con nombre/artista superpuestos, botonera compacta [DetailPlayButtons] que
 * scrollea CON el contenido, y canciones en tarjetas segmentadas con overflow. La topbar es mínima
 * y solo lleva el nombre, que aparece con el scroll.
 *
 * **La imagen NO se desvanece y la botonera NO se queda pegada arriba**: las dos cosas se probaron
 * y el usuario las descartó (ver la sección de detalles en CLAUDE.md, "NO reintroducir"). Este KDoc
 * las describía como si siguieran ahí, con un enlace a un composable que ya no existe.
 */
@OptIn(
    ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    ExperimentalSharedTransitionApi::class
)
@Composable
fun AlbumDetailScreen(
    albumName: String,
    currentSong: Song?,
    favorites: Set<String>,
    playlists: List<Playlist>,
    onBackClick: () -> Unit,
    onArtistClick: (String) -> Unit,
    onPlayAll: (List<Song>, Int) -> Unit,
    onShufflePlay: (List<Song>) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onAddToQueue: (Song) -> Unit,
    /** Encolar TODAS las canciones del álbum, desde la botonera de la cabecera. */
    onAddAllToQueue: (List<Song>) -> Unit,
    onAddSongToPlaylist: (Long, String) -> Unit,
    /** Crear lista nueva; [pendingSongId] = canción del flujo "agregar a lista" que debe nacer dentro. */
    onCreatePlaylist: (name: String, pendingSongId: String?) -> Unit,
    modifier: Modifier = Modifier,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    viewModel: BrowseViewModel = hiltViewModel()
) {
    val songs by remember(albumName) { viewModel.getAlbumSongs(albumName) }
        .collectAsStateWithLifecycle(emptyList())

    // La pista de la que sale la CARÁTULA del header. Se calcula una vez y se usa para las dos
    // cosas —la imagen y el seed del tema— justamente para que no puedan discrepar: el color de
    // esta pantalla tiene que ser el de la portada que se está viendo, no el de otra pista del
    // álbum que quizá tenga una portada distinta.
    val headerSong = remember(songs) { songs.firstOrNull { it.albumArtUriString != null } }

    // Seed del tema local. Sale de los colores YA persistidos en `songs` (extraídos en su día por
    // ArtworkRepository), así que no cuesta ni una consulta ni una extracción: viajan en el propio
    // modelo. `secondary` en oscuro y `primary` en claro es el mismo reparto por tema que usa el
    // resaltado de la biblioteca; son seeds CRUDOS, que es lo que MaterialKolor espera.
    val isDark = isSystemInDarkTheme()
    val albumSeed = remember(headerSong, isDark) {
        headerSong?.colors?.let { if (isDark) it.secondary else it.primary }
    }

    // Artista del header: si el álbum mezcla artists (feats), "Varios artistas" (no
    // clickeable); si es uno solo, clickeable → detalle del artista.
    val distinctArtists = remember(songs) { songs.map { it.artist }.distinct() }
    val singleArtist = distinctArtists.singleOrNull()

    var songIdForPlaylist by remember { mutableStateOf<String?>(null) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    // Canción retenida cuando el diálogo de crear lista viene de la hoja "agregar a lista".
    var pendingSongForNewPlaylist by remember { mutableStateOf<String?>(null) }

    // TopBar mínima al scrollear: fondo que se funde + título con transición tipo
    // SHARED ELEMENT (réplica del morph de carátula MiniPlayer→NowPlaying): un único
    // Text viaja y ESCALA desde el nombre grande del header hasta el hueco de la topbar,
    // interpolando entre ambas posiciones medidas, conducido por el offset de scroll.
    val listState = rememberLazyListState()
    val titleFadePx = with(LocalDensity.current) { ComponentConfig.DetailTitleFadeRange.toPx() }
    val rawTitleFraction by remember {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) 1f
            else (listState.firstVisibleItemScrollOffset / titleFadePx).coerceIn(0f, 1f)
        }
    }
    // SNAP al soltar: con pocas canciones el scroll disponible no alcanza el rango del
    // fade y la transición quedaba a medias. Mientras se arrastra sigue al dedo; al
    // soltar se completa al extremo más cercano — y si la lista llegó a su fondo, se
    // completa SIEMPRE hacia la topbar aunque el recorrido haya sido corto.
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
    // Anclas medidas de la transición del título (coordenadas en root).
    var overlayOrigin by remember { mutableStateOf(Offset.Zero) }
    var headerTitleAnchor by remember { mutableStateOf(Offset.Zero) }
    var headerTitleHeight by remember { mutableIntStateOf(0) }
    var barTitleAnchor by remember { mutableStateOf(Offset.Zero) }

    // Estado compartido de la CARÁTULA del header, HOISTADO: lo usa la cabecera para su `sharedBounds`
    // y el título/topbar para saber si deben elevarse al overlay (solo si la portada morfa de verdad,
    // o sea si venimos de un tile — ver `overSharedElementsModifier`). Uno solo, dos lectores: crear
    // un segundo con la misma key sería un target duplicado.
    val headerImageSharedState = if (sharedTransitionScope != null && animatedVisibilityScope != null) {
        with(sharedTransitionScope) { rememberSharedContentState(key = "album_image_$albumName") }
    } else null

    DetailContentTheme(albumSeed) {
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
                        "album",
                        size = 64.sp,
                        color = colorScheme.outline
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.album_empty_detail),
                        style = MaterialTheme.typography.bodyLarge,
                        color = colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            // El MiniPlayer global (MainActivity) FLOTA sobre esta pantalla: se reserva su
            // alto como contentPadding para que el final de la lista scrollee por encima.
            val listBottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + ComponentConfig.FloatingBarListInset
            Box(modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { overlayOrigin = it.positionInRoot() }
            ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = listBottomInset)
            ) {
                item {
                    AlbumImmersiveHeader(
                        albumName = albumName,
                        albumArtUri = headerSong?.albumArtUriString,
                        singleArtist = singleArtist,
                        songCount = songs.size,
                        // La primera pista que lo declare: dentro de un álbum el año es el mismo
                        // para todas, y basta con que UNA esté etiquetada.
                        year = songs.firstOrNull { it.year > 0 }?.year ?: 0,
                        onArtistClick = onArtistClick,
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                        headerImageSharedState = headerImageSharedState,
                        // El nombre del header es un PLACEHOLDER invisible que solo aporta
                        // layout y su posición: el texto real lo dibuja el título viajero.
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
                        onAddToQueue = { onAddAllToQueue(songs) },
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
                    )
                }

                itemsIndexed(
                    items = songs,
                    key = { _, song -> song.id }
                ) { index, song ->
                    // La canción actual, esté sonando o en PAUSA: mismo criterio que la cola y la
                    // lista de canciones (el resaltado marca "cargada", no "reproduciendo ahora").
                    val isPlaying = currentSong?.id == song.id
                    val rowBackground = songRowBackground(colorScheme.surfaceContainer, isPlaying)
                    // Punta ORIGEN del container transform hacia el reproductor: la fila crece hasta
                    // ser el player. Fuera del envoltorio va lo que la coloca en la lista; dentro, la
                    // superficie que morfa (ver [SongRowContainer]).
                    SongRowContainer(
                        songId = song.id,
                        modifier = Modifier
                            .animateItem()
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 1.dp)
                    ) {
                        Surface(
                            color = colorScheme.surfaceContainer,
                            // isActive: el ítem en reproducción usa la forma redondeada (16 dp), igual
                            // que en la cola y la lista de canciones, en vez de la esquina agrupada.
                            shape = rememberListItemShape(index, songs.size, isActive = isPlaying),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            SongItem(
                                song = song,
                                isPlaying = isPlaying,
                                modifier = Modifier.clickable { onPlayAll(songs, index) },
                                trailingContent = {
                                    SongOverflowButton(
                                        isFavorite = song.id in favorites,
                                        onToggleFavorite = { onToggleFavorite(song.id) },
                                        onAddToPlaylist = { songIdForPlaylist = song.id },
                                        onAddToQueue = { onAddToQueue(song) },
                                        rowBackground = rowBackground
                                    )
                                }
                            )
                        }
                    }
                }
            }

            // TopBar estilo stock, PINEADA: back + título. El back está siempre visible
            // (flota sobre la carátula en reposo); el fondo y el título se funden a la
            // vista con el scroll, cuando el nombre grande del header sale de pantalla.
            // overSharedElements: durante la transición de entrada la carátula vuela en el
            // OVERLAY y tapaba esto (aparecía de golpe al terminar) — se dibuja encima.
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .then(overSharedElementsModifier(sharedTransitionScope, animatedVisibilityScope, headerImageSharedState))
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
                    // Hueco del título: caja de alto cero centrada verticalmente en la
                    // fila — su posición marca el DESTINO del título viajero.
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp)
                            .onGloballyPositioned { barTitleAnchor = it.positionInRoot() }
                    )
                }
            }

            // TÍTULO VIAJERO (shared element manual): un único Text que interpola posición
            // y escala entre el ancla del header y el hueco de la topbar según el scroll.
            // overSharedElements: si no, la carátula voladora del overlay lo tapa durante
            // la transición de entrada y el título aparecía DE GOLPE al terminar.
            Text(
                text = albumName.ifBlank { stringResource(R.string.common_unknown_album) },
                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                color = colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 40.dp)
                    .then(overSharedElementsModifier(sharedTransitionScope, animatedVisibilityScope, headerImageSharedState))
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
                // Retener la canción: la lista nueva nace con ella (antes se descartaba).
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
    } // DetailContentTheme
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun AlbumImmersiveHeader(
    albumName: String,
    albumArtUri: String?,
    singleArtist: String?,
    songCount: Int,
    /** Año de publicación; 0 = ninguna pista del álbum lo declara y el chip no se dibuja. */
    year: Int,
    onArtistClick: (String) -> Unit,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    // HOISTADO desde el parent (mismo objeto que gatea la elevación del título): así el
    // `isMatchFound` que decide el morph y el que decide elevar son EL MISMO.
    headerImageSharedState: SharedTransitionScope.SharedContentState? = null,
    onTitlePositioned: (Offset, Int) -> Unit = { _, _ -> }
) {
    // La carátula llega volando desde la celda de la pestaña Álbumes (sharedBounds,
    // misma key que AlbumTileCard).
    val sharedModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null && headerImageSharedState != null) {
        with(sharedTransitionScope) {
            Modifier.sharedBounds(
                sharedContentState = headerImageSharedState,
                animatedVisibilityScope = animatedVisibilityScope,
                // Spring del tema en vez del default de la API (ver AppBoundsTransform).
                boundsTransform = AppBoundsTransform
            )
        }
    } else Modifier
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(ComponentConfig.DetailHeaderHeight)
            .then(sharedModifier)
    ) {
        if (albumArtUri != null) {
            AsyncImage(
                model = albumArtUri,
                contentDescription = stringResource(R.string.album_art_desc, albumName),
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize()
            )
        } else {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center
            ) {
                MaterialSymbol("album", size = 96.sp, color = colorScheme.onSurfaceVariant)
            }
        }

        // Scrim inferior REFORZADO: funde la carátula con el fondo y —clave— deja el título apoyado
        // sobre `surface` CASI SÓLIDO, no sobre la imagen. Así el `onSurface` del nombre contrasta
        // SIEMPRE, sea la carátula clara u oscura (y se adapta a tema claro/oscuro por el propio rol).
        // El scrim viejo (0.4→surface) solo llegaba a ~0.73·surface donde EMPIEZA el texto, y una
        // carátula clara tapaba la parte alta de las letras. La imagen sigue inmersiva en el 70 % de
        // arriba con su fade suave; solo el 15 % inferior (la banda del título) queda sólido.
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0.3f to Color.Transparent,
                        0.7f to colorScheme.surface.copy(alpha = 0.5f),
                        0.85f to colorScheme.surface,
                        1f to colorScheme.surface
                    )
                )
        )

        // Nombre + artista superpuestos (el back vive en la topbar pineada del caller).
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Text(
                text = albumName.ifBlank { stringResource(R.string.common_unknown_album) },
                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                color = colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // PLACEHOLDER invisible: aporta el layout (artista/chip debajo) y reporta
                // su ancla; el texto visible es el título viajero del overlay.
                modifier = Modifier
                    .graphicsLayer { alpha = 0f }
                    .onGloballyPositioned { onTitlePositioned(it.positionInRoot(), it.size.height) }
            )
            Spacer(modifier = Modifier.height(6.dp))
            if (singleArtist != null) {
                Text(
                    text = singleArtist.ifBlank { stringResource(R.string.common_unknown_artist) },
                    style = MaterialTheme.typography.titleMedium,
                    color = colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onArtistClick(singleArtist) }
                        .padding(vertical = 2.dp)
                )
            } else {
                Text(
                    text = stringResource(R.string.common_various_artists),
                    style = MaterialTheme.typography.titleMedium,
                    color = colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TonalChip {
                    MaterialSymbol("music_note", size = 14.sp, color = colorScheme.onSecondaryContainer)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (songCount == 1) "1 canción" else "$songCount canciones",
                        style = MaterialTheme.typography.labelMedium,
                        color = colorScheme.onSecondaryContainer
                    )
                }
                // El año solo aparece si alguna pista lo declara: un chip "0" o vacío sería peor
                // que no tenerlo, y hay bibliotecas enteras sin ese tag.
                if (year > 0) {
                    Spacer(modifier = Modifier.width(8.dp))
                    TonalChip {
                        Text(
                            text = year.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
        }
    }
}
