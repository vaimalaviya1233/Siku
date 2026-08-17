package com.qhana.siku.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.qhana.siku.data.model.PlaybackOrigin
import com.qhana.siku.data.model.PlaybackState
import com.qhana.siku.data.model.RepeatMode
import com.qhana.siku.data.model.Song
import com.qhana.siku.data.model.ToolbarActionState
import com.qhana.siku.ui.components.*
import com.qhana.siku.ui.state.NowPlayingUiState
import kotlinx.coroutines.flow.StateFlow

    // --- Orientation Layouts ---

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun NowPlayingPortrait(
    song: Song,
    uiState: NowPlayingUiState,
    variantColor: Color,
    contentColor: Color,
    playButtonColor: Color,
    playButtonContentColor: Color,
    // Acento TARGET sin animar: lo consume AccentRevealGroup (la ventana es la transición).
    revealAccent: Color,
    isFavorite: Boolean,
    repeatMode: RepeatMode,
    keepScreenOn: Boolean,
    showLyrics: Boolean,
    isPlayingOrBuffering: Boolean,
    playbackState: PlaybackState,
    currentPositionFlow: StateFlow<Long>,
    durationFlow: StateFlow<Long>,
    /** Búfer cargado: tercer nivel de la barra. */
    bufferedPositionFlow: StateFlow<Long>,
    /** La canción se está streameando: fuera de ahí el búfer no informa de nada. */
    showBuffer: Boolean,
    /** Gestos del reproductor (Ajustes → Reproducción). */
    gesturesEnabled: Boolean,
    /** Arrastre de cierre compartido: la carátula también lo alimenta. */
    dismiss: PlayerDismissState?,
    format: AudioFormatInfo,
    /** Chip de formato con ficha técnica (bitrate/bits + frecuencia) en vez de solo el contenedor. */
    detailedFormat: Boolean,
    onToggleDetailedFormat: () -> Unit,
    /** Ajustes → Reproducción: barra de progreso ondulada (Expressive). */
    wavyProgress: Boolean,
    /** Grosor de la barra de progreso (Ajustes -> Apariencia); vale para los dos modos. */
    progressThickness: Dp,
    playerActions: PlayerActions,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    onLyricsToggle: () -> Unit,
    onShowQueue: () -> Unit,
    onAddToPlaylistClick: () -> Unit,
    eqEnabled: Boolean,
    sleepTimerActive: Boolean,
    onSleepTimerClick: () -> Unit,
    onShareSong: () -> Unit,
    toolbarConfig: List<ToolbarActionState>,
    sharedTransitionScope: SharedTransitionScope?,
    artSharedKey: Any?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    onAlbumArtLongPress: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AlbumArtSection(
            song = song,
            variantColor = variantColor,
            sharedTransitionScope = sharedTransitionScope,
            artSharedKey = artSharedKey,
            animatedVisibilityScope = animatedVisibilityScope,
            isPlaying = isPlayingOrBuffering,
            onTap = onAlbumArtLongPress,
            gesturesEnabled = gesturesEnabled,
            onNext = playerActions.onNext,
            onPrevious = playerActions.onPrevious,
            onSeekBy = playerActions.onSeekBy,
            dismiss = dismiss,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                // La carátula es cuadrada dentro de este hueco, así que normalmente el ALTO es
                // el que manda: cada dp de padding vertical la encoge por los cuatro lados.
                // 16/8 es el punto medio calibrado a ojo (24/8 la dejaba chica, 8/0 la llevaba
                // a tocar los márgenes de la pantalla).
                .padding(vertical = 16.dp, horizontal = 8.dp)
        )

        SongInfoSection(
            song = song,
            contentColor = contentColor,
            variantColor = variantColor,
            isFavorite = isFavorite,
            playButtonColor = playButtonColor,
            onToggleFavorite = playerActions.onToggleFavorite,
            onArtistClick = onArtistClick,
            onAlbumClick = onAlbumClick
        )

        Spacer(modifier = Modifier.height(24.dp))

        ProgressSlider(
            currentPositionFlow = currentPositionFlow,
            durationFlow = durationFlow,
            bufferedPositionFlow = bufferedPositionFlow,
            showBuffer = showBuffer,
            onSeek = playerActions.onSeek,
            trackColor = playButtonColor,
            inactiveTrackColor = playButtonColor,
            textColor = variantColor,
            format = format,
            detailedFormat = detailedFormat,
            onToggleDetailedFormat = onToggleDetailedFormat,
            wavy = wavyProgress,
            trackHeight = progressThickness,
            isPlaying = isPlayingOrBuffering
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Controles + action bar comparten UN solo shape reveal de acento al cambiar de
        // canción (misma coreografía cookie que la carátula). El estado del giro del play
        // va HOISTED aquí: las dos capas del reveal deben compartirlo (ver PlayButtonSpinState).
        val playSpin = rememberPlayButtonSpin(isPlayingOrBuffering)
        AccentRevealGroup(
            songId = song.id,
            accent = revealAccent,
            accentContent = playButtonContentColor,
            // Sin reveal mientras el player sube desde la píldora: la transición ya está moviendo
            // toda la pantalla y encadenar encima el barrido de acento se lee como un tirón.
            revealEnabled = animatedVisibilityScope?.transition?.let {
                it.currentState == it.targetState
            } ?: true,
            modifier = Modifier.fillMaxWidth()
        ) { accent, accentContent ->
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                PlaybackControls(
                    onPrevious = playerActions.onPrevious,
                    onPlayPause = playerActions.onPlayPause,
                    onNext = playerActions.onNext,
                    contentColor = contentColor,
                    playButtonColor = accent,
                    playButtonContentColor = accentContent,
                    isPlayingOrBuffering = isPlayingOrBuffering,
                    playbackState = playbackState,
                    spin = playSpin
                )

                Spacer(modifier = Modifier.height(32.dp))

                BottomActionBar(
                    showLyrics = showLyrics,
                    isLyricsLoading = uiState.isLyricsLoading,
                    keepScreenOn = keepScreenOn,
                    playButtonColor = accent,
                    songRemoteId = song.remoteId,
                    isDownloaded = uiState.isDownloaded,
                    isDownloading = uiState.isDownloading,
                    downloadProgress = uiState.downloadProgress,
                    onLyricsToggle = onLyricsToggle,
                    onShowQueue = onShowQueue,
                    onToggleKeepScreenOn = playerActions.onToggleKeepScreenOn,
                    onOpenEqualizer = playerActions.onOpenEqualizer,
                    onRedownload = playerActions.onToggleDownload,
                    onAddToPlaylistClick = onAddToPlaylistClick,
                    eqEnabled = eqEnabled,
                    sleepTimerActive = sleepTimerActive,
                    onSleepTimerClick = onSleepTimerClick,
                    repeatMode = repeatMode,
                    onRepeatToggle = playerActions.onRepeatToggle,
                    onShareSong = onShareSong,
                    config = toolbarConfig,
                    // Mismo margen sobre la navbar que la capa flotante del home (16dp). El Scaffold
                    // ya mete el inset de la navbar en innerPadding, así que esto queda 16dp por
                    // encima de ella (antes 32).
                    modifier = Modifier.padding(bottom = ComponentConfig.FloatingBarBottomMargin)
                )
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun NowPlayingLandscape(
    song: Song,
    uiState: NowPlayingUiState,
    variantColor: Color,
    contentColor: Color,
    playButtonColor: Color,
    playButtonContentColor: Color,
    isFavorite: Boolean,
    repeatMode: RepeatMode,
    keepScreenOn: Boolean,
    showLyrics: Boolean,
    isPlayingOrBuffering: Boolean,
    playbackState: PlaybackState,
    currentPositionFlow: StateFlow<Long>,
    durationFlow: StateFlow<Long>,
    /** Búfer cargado: tercer nivel de la barra. */
    bufferedPositionFlow: StateFlow<Long>,
    /** La canción se está streameando: fuera de ahí el búfer no informa de nada. */
    showBuffer: Boolean,
    /** Gestos del reproductor (Ajustes → Reproducción). */
    gesturesEnabled: Boolean,
    /** Arrastre de cierre compartido: la carátula también lo alimenta. */
    dismiss: PlayerDismissState?,
    // Acento TARGET sin animar: lo consume AccentRevealGroup (la ventana es la transición).
    revealAccent: Color,
    origin: PlaybackOrigin,
    /** Color real bajo la barra: de él deriva el chip de origen su relleno para no fundirse. */
    backgroundColor: Color,
    format: AudioFormatInfo,
    /** Chip de formato con ficha técnica (bitrate/bits + frecuencia) en vez de solo el contenedor. */
    detailedFormat: Boolean,
    onToggleDetailedFormat: () -> Unit,
    /** Ajustes → Reproducción: barra de progreso ondulada (Expressive). */
    wavyProgress: Boolean,
    /** Grosor de la barra de progreso (Ajustes -> Apariencia); vale para los dos modos. */
    progressThickness: Dp,
    playerActions: PlayerActions,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    onBackClick: () -> Unit,
    onAmbientMode: () -> Unit,
    onLyricsToggle: () -> Unit,
    onShowQueue: () -> Unit,
    onAddToPlaylistClick: () -> Unit,
    eqEnabled: Boolean,
    sleepTimerActive: Boolean,
    onSleepTimerClick: () -> Unit,
    onShareSong: () -> Unit,
    toolbarConfig: List<ToolbarActionState>,
    sharedTransitionScope: SharedTransitionScope?,
    artSharedKey: Any?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    onAlbumArtLongPress: () -> Unit
) {
    // Sin statusBarsPadding/navigationBarsPadding: el Scaffold de NowPlayingScreen ya mete los
    // insets de las barras del sistema en innerPadding (aplicado por el Box padre). Aplicarlos de
    // nuevo acá duplicaba el espacio superior (la franja vacía bajo el status bar).
    //
    // El recorte de pantalla SÍ hay que pedirlo aparte: en horizontal la perforación cae en un
    // COSTADO, que es justo donde el Scaffold no aporta inset, y el padding lateral fijo no
    // alcanza para esquivarla.
    Row(
        modifier = Modifier
            .fillMaxSize()
            .displayCutoutPadding()
            .padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // LEFT: barra superior + carátula + toolbar de acciones.
        // El toolbar vive ACÁ, no en la columna de controles: en landscape esa columna no tenía
        // altura para todo y el BottomActionBar (último hijo) se medía a ≈0 y se aplastaba.
        // Repartido así, cada columna cabe sin scroll.
        // Sin verticalArrangement=Center: la barra se ancla arriba y el toolbar abajo; la
        // carátula se centra en el Box(weight) intermedio. Antes, weight(1f, fill=false) en la
        // carátula + Center dejaba TODO el aire sobrante como padding superior.
        Column(
            modifier = Modifier
                .weight(0.52f)
                .fillMaxHeight()
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // La MISMA barra que en vertical (minimizar | chip | ambient). Antes se reimplementaba
            // aquí con `IconButton` crudos y las dos copias ya habían divergido: en horizontal los
            // botones se habían quedado sin el morph de forma al presionar ni la háptica que
            // `ExpressiveActionIcon` da en vertical.
            NowPlayingTopBar(
                onBackClick = onBackClick,
                contentColor = contentColor,
                accentColor = playButtonColor,
                origin = origin,
                backgroundColor = backgroundColor,
                onAmbientMode = onAmbientMode,
                // El chip pierde su etiqueta: en esta columna el ancho es la mitad y con texto
                // empujaba a los botones contra los bordes.
                compactChip = true,
                // Los insets ya vienen del Scaffold; repetir el del status bar aquí dejaría una
                // franja vacía sobre la barra.
                applyStatusBarPadding = false
            )

            // Carátula: ocupa el alto sobrante entre info bar y toolbar, cuadrada y centrada.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                AlbumArtSection(
                    song = song,
                    variantColor = variantColor,
                    sharedTransitionScope = sharedTransitionScope,
                    artSharedKey = artSharedKey,
                    animatedVisibilityScope = animatedVisibilityScope,
                    isPlaying = isPlayingOrBuffering,
                    onTap = onAlbumArtLongPress,
                    gesturesEnabled = gesturesEnabled,
                    onNext = playerActions.onNext,
                    onPrevious = playerActions.onPrevious,
                    onSeekBy = playerActions.onSeekBy,
                    dismiss = dismiss,
                    modifier = Modifier
                        .fillMaxHeight()
                        .aspectRatio(1f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Toolbar de acciones. Usa playButtonColor directo (sin el reveal cookie, que queda
            // con los controles de la derecha): igual sigue el acento del álbum vía el theme.
            BottomActionBar(
                showLyrics = showLyrics,
                isLyricsLoading = uiState.isLyricsLoading,
                keepScreenOn = keepScreenOn,
                playButtonColor = playButtonColor,
                songRemoteId = song.remoteId,
                isDownloaded = uiState.isDownloaded,
                isDownloading = uiState.isDownloading,
                downloadProgress = uiState.downloadProgress,
                onLyricsToggle = onLyricsToggle,
                onShowQueue = onShowQueue,
                onToggleKeepScreenOn = playerActions.onToggleKeepScreenOn,
                onOpenEqualizer = playerActions.onOpenEqualizer,
                onRedownload = playerActions.onToggleDownload,
                onAddToPlaylistClick = onAddToPlaylistClick,
                eqEnabled = eqEnabled,
                sleepTimerActive = sleepTimerActive,
                onSleepTimerClick = onSleepTimerClick,
                repeatMode = repeatMode,
                onRepeatToggle = playerActions.onRepeatToggle,
                onShareSong = onShareSong,
                config = toolbarConfig
            )
        }

        // RIGHT: info de la canción, slider y transporte (sin toolbar, ahora en la izquierda).
        Column(
            modifier = Modifier
                .weight(0.48f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.Center
        ) {
            SongInfoSection(
                song = song,
                contentColor = contentColor,
                variantColor = variantColor,
                isFavorite = isFavorite,
                playButtonColor = playButtonColor,
                onToggleFavorite = playerActions.onToggleFavorite,
                onArtistClick = onArtistClick,
                onAlbumClick = onAlbumClick
            )

            Spacer(modifier = Modifier.height(16.dp))

            ProgressSlider(
                currentPositionFlow = currentPositionFlow,
                durationFlow = durationFlow,
                bufferedPositionFlow = bufferedPositionFlow,
                showBuffer = showBuffer,
                onSeek = playerActions.onSeek,
                trackColor = playButtonColor,
                inactiveTrackColor = playButtonColor,
                textColor = variantColor,
                format = format,
                detailedFormat = detailedFormat,
                onToggleDetailedFormat = onToggleDetailedFormat,
                wavy = wavyProgress,
                trackHeight = progressThickness,
                isPlaying = isPlayingOrBuffering
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Transporte con su shape reveal de acento al cambiar de canción. El estado del giro
            // del play va HOISTED (ver PlayButtonSpinState).
            val playSpin = rememberPlayButtonSpin(isPlayingOrBuffering)
            AccentRevealGroup(
                songId = song.id,
                accent = revealAccent,
                accentContent = playButtonContentColor,
                // Sin reveal mientras el player sube desde la píldora: la transición ya está moviendo
            // toda la pantalla y encadenar encima el barrido de acento se lee como un tirón.
                revealEnabled = animatedVisibilityScope?.transition?.let {
                    it.currentState == it.targetState
                } ?: true,
                modifier = Modifier.fillMaxWidth()
            ) { accent, accentContent ->
                PlaybackControls(
                    onPrevious = playerActions.onPrevious,
                    onPlayPause = playerActions.onPlayPause,
                    onNext = playerActions.onNext,
                    contentColor = contentColor,
                    playButtonColor = accent,
                    playButtonContentColor = accentContent,
                    isPlayingOrBuffering = isPlayingOrBuffering,
                    playbackState = playbackState,
                    spin = playSpin
                )
            }
        }
    }
}
