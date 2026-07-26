package com.qhana.siku.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.graphics.shapes.Morph
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.qhana.siku.R
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.qhana.siku.data.model.PlaybackOrigin
import com.qhana.siku.data.model.Song
import com.qhana.siku.ui.components.*
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * Carátula, cabecera e identidad de la canción en el NowPlaying: la barra superior con el chip
 * de origen, la carátula (con su reveal al cambiar de tema y los gestos), el bloque de
 * título/artista/álbum y el botón de favorito.
 *
 * Separado de [NowPlayingControls] —transporte y barra de acciones— y de [NowPlayingProgress]
 * —barra de progreso y ficha del formato— porque los tres crecieron hasta no caber juntos.
 */

@Composable
internal fun NowPlayingTopBar(
    onBackClick: () -> Unit,
    contentColor: Color,
    accentColor: Color,
    hazeState: HazeState,
    glassTint: Color,
    origin: PlaybackOrigin,
    /** Fondo sólido vs degradado: el chip de origen necesita saberlo para no fundirse con él. */
    solidBackground: Boolean,
    onAmbientMode: () -> Unit,
    /** Chip de origen sin etiqueta: en horizontal la barra vive en media pantalla. */
    compactChip: Boolean = false,
    /**
     * En vertical la barra es el `topBar` del Scaffold y pone ella el inset del status bar; en
     * horizontal cuelga dentro del contenido, que ya lo recibió por `innerPadding`.
     */
    applyStatusBarPadding: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (applyStatusBarPadding) Modifier.statusBarsPadding() else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Icon buttons expressive: morph de forma al presionar (IconButtonDefaults.shapes)
        // + háptica, vía ExpressiveActionIcon.
        ExpressiveActionIcon(
            onClick = onBackClick,
            icon = "keyboard_arrow_down",
            description = stringResource(R.string.np_close_desc),
            contentColor = contentColor,
            iconSize = 32.sp
        )

        PlaybackSourceChip(
            origin = origin,
            contentColor = contentColor,
            accentColor = accentColor,
            hazeState = hazeState,
            glassTint = glassTint,
            solidBackground = solidBackground,
            compact = compactChip
        )

        ExpressiveActionIcon(
            onClick = onAmbientMode,
            icon = "expand_content",
            description = stringResource(R.string.np_ambient_mode_desc),
            contentColor = contentColor,
            iconSize = 28.sp
        )
    }
}

/**
 * Chip de ORIGEN del NowPlaying (compartido portrait/landscape): de dónde sale el audio que
 * suena. Pastilla de VIDRIO ESMERILADO ([GlassSurface]) — informativa, no accionable — con
 * sello M3 Expressive: el icono va sentado en una forma orgánica de [MaterialShapes]
 * (cookie) del color del contenido, que además MORFA de forma al cambiar el origen.
 *
 * El FORMATO del archivo ya no vive acá: es otro dato (qué suena, no de dónde) y tiene su
 * propio chip centrado entre los tiempos del [ProgressSlider].
 *
 * Los tres orígenes de [PlaybackOrigin] tienen icono, forma y texto propios: una canción de la
 * nube ya descargada suena offline igual que una local, pero no es lo mismo, y antes ambas se
 * mostraban como "LOCAL".
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PlaybackSourceChip(
    origin: PlaybackOrigin,
    contentColor: Color,
    accentColor: Color,
    hazeState: HazeState,
    glassTint: Color,
    /**
     * Fondo del reproductor en modo SÓLIDO. El relleno del chip NO depende de esto (es el mismo
     * en ambos); lo que decide es el relieve —borde y sombra— con el que se despega del fondo.
     * Ver más abajo: es un problema real de contraste, no una preferencia estética.
     */
    solidBackground: Boolean,
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    // El relleno es el MISMO en los dos fondos: `secondaryContainer`, teñido por el álbum. El
    // neutro `surfaceContainerHighest` que llevaba con degradado resolvía el contraste pero
    // dejaba un chip GRIS al lado de un reproductor entero a color, y esa era la queja.
    //
    // El problema que aquel neutro atacaba sigue siendo real: el degradado ARRANCA en
    // `secondaryContainer` —es el color del tope, justo donde vive este chip—, así que relleno y
    // fondo coinciden. Lo que separa el chip ahí no es el color sino el RELIEVE: borde `outline`
    // (en vez del `outlineVariant` tenue) y sombra, que es exactamente el recurso que M3 usa
    // para superficies del mismo tono. Con fondo sólido no hace falta ninguno de los dos: el
    // contraste ya lo da el color, y la sombra solo ensuciaría.
    val chipContainerColor = MaterialTheme.colorScheme.secondaryContainer
    val chipContentColor = MaterialTheme.colorScheme.onSecondaryContainer
    val chipBorderColor = if (solidBackground) {
        MaterialTheme.colorScheme.outlineVariant
    } else {
        MaterialTheme.colorScheme.outline
    }
    val chipShadow = if (solidBackground) 0.dp else ChipGradientShadowElevation

    // Forma expressive del asiento del icono, una por origen: cookie de 9 lados en local (disco
    // "dentado"), soft burst en descargado, sunny en stream (rayos). El cambio
    // de forma es el acento expressive del chip. toShape() ya es @Composable y memoiza internamente.
    val seatShape = when (origin) {
        PlaybackOrigin.LOCAL -> MaterialShapes.Cookie7Sided
        PlaybackOrigin.DOWNLOADED -> MaterialShapes.Cookie9Sided
        PlaybackOrigin.STREAMING -> MaterialShapes.Sunny
    }.toShape()
    val seatSize = if (compact) 24.dp else 28.dp

    val originIcon = when (origin) {
        PlaybackOrigin.LOCAL -> "sd_card"
        PlaybackOrigin.DOWNLOADED -> "cloud_done"
        PlaybackOrigin.STREAMING -> "stream"
    }
    val originLabel = when (origin) {
        PlaybackOrigin.LOCAL -> stringResource(R.string.np_chip_local)
        PlaybackOrigin.DOWNLOADED -> stringResource(R.string.np_chip_downloaded)
        PlaybackOrigin.STREAMING -> stringResource(R.string.np_chip_stream)
    }

    Surface(
        shape = RoundedCornerShape(50),
        color = chipContainerColor,
        border = BorderStroke(1.dp, chipBorderColor),
        shadowElevation = chipShadow,
        modifier = modifier.height(if (compact) 36.dp else 40.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .padding(start = 6.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(seatSize)
                    .clip(seatShape)
                    // Asiento con el ACENTO del álbum (mismo color que el botón de play):
                    // ata el chip al tema de la carátula en vez del blanco/negro neutro.
                    .background(accentColor)
            ) {
                MaterialSymbol(
                    originIcon,
                    color = onContainerColor(accentColor),
                    size = if (compact) 13.sp else 15.sp,
                    fill = true
                )
            }
            Spacer(modifier = Modifier.width(if (compact) 8.dp else 10.dp))
            Text(
                text = originLabel,
                style = (if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium)
                    .copy(fontWeight = FontWeight.Medium),
                color = chipContentColor
            )
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun AlbumArtSection(
    song: Song,
    variantColor: Color,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    isPlaying: Boolean,
    onTap: () -> Unit,
    /** Ajustes → Reproducción: deslizar para cambiar/cerrar y doble toque para saltar. */
    gesturesEnabled: Boolean,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    /** Salto YA firmado del doble toque (ver [PlayerGestureConfig.SeekStepMs]). */
    onSeekBy: (Long) -> Unit,
    /** Arrastre de cierre compartido con el resto del reproductor; null si no aplica. */
    dismiss: PlayerDismissState?,
    modifier: Modifier = Modifier
) {
    // "Respiración" al pausar: la carátula se encoge sutilmente y su forma morfea de
    // MaterialShapes.Square a MaterialShapes.Circle (estado de reposo); al reproducir recupera
    // plena presencia y vuelve a cuadrado. Springs suaves.
    val artMorphProgress by animateFloatAsState(
        targetValue = if (isPlaying) 0f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "artMorph"
    )
    val artScale by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0.93f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "artScale"
    )

    // Arrastre horizontal del gesto de cambiar canción: la carátula sigue al dedo y vuelve al
    // centro al soltar (el cambio lo cuenta el reveal de abajo, no una salida por el borde).
    val gestureScope = rememberCoroutineScope()
    val dragOffsetX = remember { Animatable(0f) }
    val maxDragPx = with(LocalDensity.current) { PlayerGestureConfig.SwipeSongMaxDrag.toPx() }

    // Destello del doble toque. El disparo es un CONTADOR y no el lado tocado: dos saltos
    // seguidos hacia el mismo lado no cambiarían un estado booleano y el segundo se quedaría
    // sin destello.
    var flashTick by remember { mutableIntStateOf(0) }
    var flashForward by remember { mutableStateOf(true) }
    var flashVisible by remember { mutableStateOf(false) }
    LaunchedEffect(flashTick) {
        if (flashTick == 0) return@LaunchedEffect
        flashVisible = true
        delay(PlayerGestureConfig.SeekFlashMs.toLong())
        flashVisible = false
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        val sharedElementModifier =
            if (sharedTransitionScope != null && animatedVisibilityScope != null) {
                with(sharedTransitionScope) {
                    Modifier.sharedElement(
                        sharedContentState = rememberSharedContentState(key = "album_art_${song.id}"),
                        animatedVisibilityScope = animatedVisibilityScope
                    )
                }
            } else Modifier

        // Animación del CAMBIO DE CANCIÓN — SHAPE REVEAL (M3 Expressive, elegida tras
        // probar variantes): la carátula nueva se revela desde el centro con una VENTANA
        // MaterialShapes (cookie) que crece hasta cubrir el cuadrado; la imagen queda
        // estática (escala inversa) — solo crece la ventana. La carátula MOSTRADA va por
        // detrás del estado real para poder coreografiar el intercambio.
        var displayedArt by remember { mutableStateOf(song.id to song.albumArtUriString) }
        var incomingArt by remember { mutableStateOf<Pair<String, String?>?>(null) }
        val reveal = remember { Animatable(0f) }

        LaunchedEffect(song.id, song.albumArtUriString) {
            val target = song.id to song.albumArtUriString
            if (displayedArt == target) return@LaunchedEffect
            incomingArt = target
            reveal.snapTo(0f)
            reveal.animateTo(
                1f,
                spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
            )
            displayedArt = target
            incomingArt = null
        }

        val revealShape = MaterialShapes.Cookie12Sided.toShape()
        val albumArtOptionsLabel = stringResource(R.string.np_album_art_options)
        Surface(
            modifier = Modifier
                .aspectRatio(1f)
                .then(sharedElementModifier)
                .graphicsLayer {
                    scaleX = artScale
                    scaleY = artScale
                    translationX = dragOffsetX.value
                }
                .albumArtSwipe(
                    enabled = gesturesEnabled,
                    offsetX = dragOffsetX,
                    dismiss = dismiss,
                    scope = gestureScope,
                    maxDragPx = maxDragPx,
                    onNext = onNext,
                    onPrevious = onPrevious
                )
                .albumArtTaps(
                    gesturesEnabled = gesturesEnabled,
                    onSeek = onSeekBy,
                    onFlash = { forward ->
                        flashForward = forward
                        flashTick++
                    },
                    onLongPress = onTap
                )
                // El long-press abre el selector de color y hasta ahora era invisible para un
                // lector de pantalla: sin acción semántica declarada, el gesto no existe.
                .semantics {
                    onLongClick(label = albumArtOptionsLabel) {
                        onTap()
                        true
                    }
                },
            shape = AlbumArtMorphShape(artMorphProgress),
            color = MaterialTheme.colorScheme.surfaceContainerHighest
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                // Carátula base (la mostrada).
                NowPlayingArtImage(
                    artId = displayedArt.first,
                    artUri = displayedArt.second,
                    albumName = song.album,
                    variantColor = variantColor
                )

                // Capa entrante del reveal: ventana cookie creciente + escala
                // inversa en la imagen para que solo se mueva la ventana.
                val inc = incomingArt
                if (inc != null) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .graphicsLayer {
                                val s = 0.08f + reveal.value * 1.55f
                                scaleX = s
                                scaleY = s
                                clip = true
                                shape = revealShape
                            }
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    val s = 0.08f + reveal.value * 1.55f
                                    scaleX = 1f / s
                                    scaleY = 1f / s
                                }
                        ) {
                            NowPlayingArtImage(
                                artId = inc.first,
                                artUri = inc.second,
                                albumName = song.album,
                                variantColor = variantColor
                            )
                        }
                    }
                }

                // Destello del salto por doble toque, del lado que se tocó. Va DENTRO del
                // recorte de la carátula para que el círculo no se salga de la forma cuando
                // ésta morfea a redonda (en pausa).
                androidx.compose.animation.AnimatedVisibility(
                    visible = flashVisible,
                    enter = fadeIn(animationSpec = tween(SEEK_FLASH_FADE_IN_MS)),
                    exit = fadeOut(animationSpec = tween(SEEK_FLASH_FADE_OUT_MS)),
                    modifier = Modifier
                        .align(if (flashForward) Alignment.CenterEnd else Alignment.CenterStart)
                        .padding(horizontal = SeekFlashSidePadding)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.scrim.copy(alpha = SEEK_FLASH_SCRIM_ALPHA),
                        modifier = Modifier.size(SeekFlashCircleSize)
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            MaterialSymbol(
                                if (flashForward) "forward_10" else "replay_10",
                                size = SeekFlashIconSize,
                                color = Color.White,
                                fill = true
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NowPlayingArtImage(
    artId: String,
    artUri: String?,
    albumName: String,
    variantColor: Color
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (artUri != null) {
            val context = LocalContext.current
            // memoryCacheKey/diskCacheKey por song.id: reusa bitmap decodificado entre
            // navegaciones Library ↔ NowPlaying y con el MiniPlayer (misma canción).
            val request = remember(artId, artUri) {
                ImageRequest.Builder(context)
                    .data(artUri)
                    .crossfade(false)
                    .size(800)
                    .memoryCacheKey("song_art_$artId")
                    .diskCacheKey("song_art_$artId")
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = stringResource(R.string.album_art_desc, albumName),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            MaterialSymbol("music_note", size = 80.sp, color = variantColor)
        }
    }
}

@Composable
internal fun SongInfoSection(
    song: Song,
    contentColor: Color,
    variantColor: Color,
    isFavorite: Boolean,
    playButtonColor: Color,
    onToggleFavorite: () -> Unit,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // 3 líneas (título / artista / álbum): artista y álbum son CLICKEABLES por separado
        // y navegan a sus pantallas de detalle.
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Medium),
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = song.artist.ifBlank { stringResource(R.string.common_unknown_artist) },
                style = MaterialTheme.typography.titleMedium,
                color = variantColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onArtistClick(song.artist) }
                    .padding(vertical = 2.dp)
            )
            if (song.album.isNotBlank()) {
                Text(
                    text = song.album,
                    style = MaterialTheme.typography.titleSmall,
                    color = variantColor.copy(alpha = variantColor.alpha * 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onAlbumClick(song.album) }
                        .padding(vertical = 2.dp)
                )
            }
        }

        // Favorito: PÍLDORA VERTICAL (misma familia que los overflow de las listas) con
        // rebote del corazón al marcar/desmarcar.
        FavoriteHeartPill(
            isFavorite = isFavorite,
            onToggle = onToggleFavorite
        )
    }
}

/**
 * Botón de favorito en PÍLDORA VERTICAL: contenedor tonal translúcido inactivo que se
 * rellena con el acento del álbum al activarse, y el corazón da un pequeño REBOTE
 * (snap 0.7 → spring con overshoot) en cada cambio de estado.
 *
 * Tamaño de spec LARGE-narrow: 64×96 con icono de 32.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FavoriteHeartPill(
    isFavorite: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val heartScale = remember { Animatable(1f) }
    // TONAL toggle de M3 (tokens): activo = secondary/onSecondary; inactivo = secondaryContainer/
    // onSecondaryContainer. El activo NO usa primary — ese es el color del PLAY, por eso antes el
    // corazón activo se veía idéntico al play.
    val activeContainer = MaterialTheme.colorScheme.secondary
    val onActive = MaterialTheme.colorScheme.onSecondary
    val tonalContainer = MaterialTheme.colorScheme.secondaryContainer
    val onTonalContainer = MaterialTheme.colorScheme.onSecondaryContainer
    val container by animateColorAsState(
        targetValue = if (isFavorite) activeContainer else tonalContainer,
        animationSpec = tween(durationMillis = 250),
        label = "heartContainer"
    )
    val heartColor by animateColorAsState(
        targetValue = if (isFavorite) onActive else onTonalContainer,
        animationSpec = tween(durationMillis = 250),
        label = "heartContent"
    )
    val favoriteDesc = if (isFavorite) stringResource(R.string.common_remove_from_favorites) else stringResource(R.string.common_add_to_favorites)
    // FilledIconToggleButton REAL (M3 Expressive: shape-morph presionado/checked, como los
    // toggles de la floating toolbar) en vez de Surface artesanal. Los colores animados del
    // acento se pasan idénticos para ambos estados: la transición de color sigue siendo
    // nuestra (tween 250), el componente aporta ripple/formas/semántica de toggle.
    // Morph INVERTIDO a petición del usuario: squircle en reposo → redondo (píldora) activo.
    FilledIconToggleButton(
        checked = isFavorite,
        onCheckedChange = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onToggle()
            scope.launch {
                heartScale.snapTo(0.7f)
                heartScale.animateTo(
                    1f,
                    spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium
                    )
                )
            }
        },
        shapes = IconButtonDefaults.toggleableShapes(
            shape = RoundedCornerShape(percent = 30),
            pressedShape = RoundedCornerShape(percent = 20),
            checkedShape = RoundedCornerShape(percent = 50)
        ),
        colors = IconButtonDefaults.filledIconToggleButtonColors(
            containerColor = container,
            contentColor = heartColor,
            checkedContainerColor = container,
            checkedContentColor = heartColor
        ),
        modifier = modifier
            .width(64.dp)
            .height(96.dp)
            .semantics {
                contentDescription = favoriteDesc
            }
    ) {
        Box(
            modifier = Modifier.graphicsLayer {
                scaleX = heartScale.value
                scaleY = heartScale.value
            }
        ) {
            MaterialSymbol("favorite", fill = isFavorite, color = heartColor, size = 32.sp)
        }
    }
}

// --- Destello del doble toque de salto (ver [AlbumArtSection]) ---

/**
 * Círculo del destello. Grande a propósito: no es un control, es una confirmación que hay que
 * leer de reojo mientras el dedo sigue encima de la carátula.
 */
private val SeekFlashCircleSize = 88.dp
private val SeekFlashIconSize = 36.sp

/** Separación del borde de la carátula, para que el círculo no toque el canto redondeado. */
private val SeekFlashSidePadding = 12.dp

/**
 * Velo del destello sobre la carátula. `scrim` del esquema —el mismo rol que M3 usa para
 * oscurecer contenido bajo una capa— a media opacidad: tapa lo justo para que el glifo blanco
 * se lea sobre CUALQUIER carátula, incluida una blanca.
 */
private const val SEEK_FLASH_SCRIM_ALPHA = 0.45f

/**
 * Entrada instantánea y salida lenta: el destello tiene que estar ya visible cuando el usuario
 * levanta el dedo, y desvanecerse sin pedir atención.
 */
private const val SEEK_FLASH_FADE_IN_MS = 90
private const val SEEK_FLASH_FADE_OUT_MS = 260
/**
 * Sombra del chip de origen cuando el reproductor va en DEGRADADO (ver [PlaybackSourceChip]).
 * Es lo que despega el chip de un fondo de su mismo color, ya que el relleno no cambia entre
 * los dos modos. 3dp: suficiente para que el borde se lea como canto y no como línea pintada,
 * y por debajo del umbral en el que la sombra se nota como tal sobre un fondo oscuro.
 */
private val ChipGradientShadowElevation = 3.dp
