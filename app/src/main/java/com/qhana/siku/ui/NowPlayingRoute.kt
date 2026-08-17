package com.qhana.siku.ui

import android.content.Intent
import android.media.audiofx.AudioEffect
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qhana.siku.R
import com.qhana.siku.data.util.JankProbe
import com.qhana.siku.data.util.SnackbarManager
import com.qhana.siku.ui.components.EqualizerSheet
import com.qhana.siku.ui.components.PLAYER_ART_SHARED_KEY
import com.qhana.siku.ui.components.PLAYER_CONTAINER_SHARED_KEY
import com.qhana.siku.ui.components.rowArtSharedKey
import com.qhana.siku.ui.components.rowContainerSharedKey
import com.qhana.siku.ui.screens.AmbientPlayerActivity
import com.qhana.siku.ui.screens.NavigationActions
import com.qhana.siku.ui.screens.NowPlayingScreen
import com.qhana.siku.ui.screens.PlayerActions
import com.qhana.siku.ui.theme.appSheetEnter
import com.qhana.siku.ui.theme.appSheetExit
import com.qhana.siku.ui.viewmodel.LibraryViewModel
import com.qhana.siku.ui.viewmodel.PlaybackViewModel

/**
 * El NowPlaying a pantalla completa: la rama **Expanded** del `AnimatedContent` de [PlayerOverlay].
 *
 * El player NO es una ruta del NavHost: es una capa que hace *container transform* con la píldora
 * (ver [MusicAppState.playerExpanded]). Las dos puntas del morph comparten así el MISMO
 * `AnimatedContentScope` —el que recibe esta función en `animatedVisibilityScope`—, que es lo que
 * hace nativo el cross-fade píldora↔player. El `sharedTransitionScope` sigue eligiendo la key según
 * el origen CONGELADO del morph ([PlayerMorphOrigin]: píldora / fila = container transform con su carátula anidada; ninguna = fundido).
 *
 * "Atrás" (botón o gesto) llama a [MusicAppState.collapsePlayer]; navegar a un artista/álbum lo
 * colapsa recordando volver ([MusicAppState.navigateToArtist] → `navigateFromPlayer`).
 *
 * La hoja del ECUALIZADOR vive aquí (se abre desde el toolbar del player). Los diálogos de guardar
 * letra y su `ActivityResultLauncher` NO: siguen en [PlayerOverlay], un nivel estable — un launcher
 * dentro de una capa que se desmonta a mitad del permiso del sistema se destruiría.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun NowPlayingLayer(
    appState: MusicAppState,
    /**
     * De qué superficie nace (o a cuál vuelve) esta capa, CONGELADO mientras su transición corre.
     * De aquí, y de nada más, salen las keys de sus shared elements: leerlas de `currentSong` o de
     * `appState.playerArtOrigin` en vivo las cambiaba a media transición cuando `AnimatedContent`
     * reutilizaba esta misma instancia (retocar una fila antes de que el cierre asentara) y dejaba la
     * fila anterior varada en el overlay — ver [rememberPlayerMorphOrigin].
     */
    morphOrigin: PlayerMorphOrigin,
    playbackViewModel: PlaybackViewModel,
    libraryViewModel: LibraryViewModel,
    snackbarManager: SnackbarManager,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isDarkTheme = isSystemInDarkTheme()

    // Sonda (solo debug): primera composición de la capa y cada recomposición de este scope.
    val firstComposition = remember { booleanArrayOf(true) }
    SideEffect {
        JankProbe.mark { if (firstComposition[0]) "NowPlayingLayer COMPUESTA (1ª vez)" else "NowPlayingLayer recompuesta" }
        firstComposition[0] = false
    }

    val currentSong by playbackViewModel.currentSong.collectAsStateWithLifecycle()
    val playbackState by playbackViewModel.playbackState.collectAsStateWithLifecycle()
    val nowPlayingUiState by playbackViewModel.nowPlayingUiState.collectAsStateWithLifecycle()
    val keepScreenOn by playbackViewModel.keepScreenOn.collectAsStateWithLifecycle()
    val nowPlayingSolidBackground by playbackViewModel.nowPlayingSolidBackground.collectAsStateWithLifecycle()
    val nowPlayingWavyProgress by playbackViewModel.nowPlayingWavyProgress.collectAsStateWithLifecycle()
    val nowPlayingProgressThickness by playbackViewModel.nowPlayingProgressThickness.collectAsStateWithLifecycle()
    val nowPlayingDetailedFormat by playbackViewModel.nowPlayingDetailedFormat.collectAsStateWithLifecycle()
    val playerGestures by playbackViewModel.playerGestures.collectAsStateWithLifecycle()
    val lyricsSaveState by playbackViewModel.lyricsSaveState.collectAsStateWithLifecycle()
    val libraryUiState by libraryViewModel.uiState.collectAsStateWithLifecycle()
    val favorites = libraryUiState.favorites
    val useSystemEq by playbackViewModel.useSystemEq.collectAsStateWithLifecycle()

    var showEqualizerSheet by remember { mutableStateOf(false) }

    // Sonda (solo con la propiedad de sistema): QUÉ cambió cada vez que esta capa recompone — para
    // separar las fuentes de las recomposiciones del NowPlaying en la ventana del morph.
    LaunchedEffect(nowPlayingUiState) {
        JankProbe.mark { "uiState cambió: song=${nowPlayingUiState.song?.title?.take(12)} down=${nowPlayingUiState.isDownloaded} colors=${nowPlayingUiState.albumColors != null} lyrics=${nowPlayingUiState.lyrics != null}" }
    }
    LaunchedEffect(playbackState) { JankProbe.mark { "playbackState(UI)=$playbackState" } }
    LaunchedEffect(currentSong) { JankProbe.mark { "currentSong instancia nueva" } }

    // Ecualizador del sistema (MIUI primero, panel estándar como fallback). Feedback de fallo por
    // TOAST y no por el SnackbarManager: se invoca también desde el botón dentro de EqualizerSheet
    // (ModalBottomSheet = ventana propia) y el snackbar del host central quedaría tapado.
    val openSystemEqualizer: () -> Unit = remember {
        {
            try {
                val xiaomiIntent = Intent().apply {
                    setClassName("com.miui.misound", "com.miui.misound.HeadsetSettingsActivity")
                }
                if (xiaomiIntent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(xiaomiIntent)
                } else {
                    val intent = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).apply {
                        putExtra(AudioEffect.EXTRA_AUDIO_SESSION, playbackViewModel.getAudioSessionId())
                        putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                        putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
                    }
                    if (intent.resolveActivity(context.packageManager) != null) {
                        context.startActivity(intent)
                    } else {
                        Toast.makeText(context, R.string.eq_none_available, Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(context, R.string.eq_open_error, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // A qué punta se engancha el reproductor. La píldora y las filas usan familias de key
    // DISTINTAS a propósito (constante vs. id), porque las dos tienen que estar declaradas de ANTES
    // para servir de origen sin pisarse; elegir la key aquí decide cuál recibe la superficie.
    //
    // Sale de `morphOrigin` —el origen CONGELADO durante la transición— y NUNCA de `currentSong` en
    // vivo: la key de una punta no puede cambiar mientras su transición corre (deja a la otra punta
    // varada en el overlay). Cuando el usuario cambia de canción con el player abierto y asentado la
    // key NO lo sigue; se actualiza al arrancar el cierre, que es cuando importa (la superficie
    // aterriza en la fila del tema que suena entonces). Ver [rememberPlayerMorphOrigin].
    //
    // DOS PATRONES DISTINTOS, según de dónde nazca el reproductor:
    //
    //  · PÍLDORA → **container transform** ([PLAYER_CONTAINER_SHARED_KEY]) MÁS la portada compartida
    //    ([PLAYER_ART_SHARED_KEY]): la superficie de la barra crece hasta ser el fondo de esta pantalla
    //    y la portada viaja por encima. El resto del contenido sí se INTERCAMBIA con un cruce de
    //    opacidad, como manda el patrón.
    //  · FILA → **el MISMO container transform** con otra punta de origen ([rowContainerSharedKey] +
    //    [rowArtSharedKey]): la superficie de la fila crece hasta ser esta pantalla, con su portada
    //    viajando anidada. Es literalmente la misma coreografía —lo único que cambia es de dónde sale—,
    //    por eso comparte configuración y no tiene una propia.
    //  · CHIP/NOTIFICACIÓN → nada que compartir; la pantalla entra por su cuenta (fundido).
    //
    // Las dos claves son de FAMILIAS distintas, y la de fila lleva además el id: la píldora es única,
    // pero las filas visibles declaran su punta SIEMPRE —una que nace en el frame del tap no tiene
    // bounds y no hay match— así que sin el id habría diez peleándose por la misma.
    val containerSharedKey: Any? = when (morphOrigin.kind) {
        PlayerArtOrigin.PILL -> PLAYER_CONTAINER_SHARED_KEY
        PlayerArtOrigin.ROW -> morphOrigin.songId?.let { rowContainerSharedKey(it) }
        PlayerArtOrigin.NONE -> null
    }
    val artSharedKey: Any? = when (morphOrigin.kind) {
        PlayerArtOrigin.PILL -> PLAYER_ART_SHARED_KEY
        PlayerArtOrigin.ROW -> morphOrigin.songId?.let { rowArtSharedKey(it) }
        PlayerArtOrigin.NONE -> null
    }


    // Memoizadas: construidas inline, cada recomposición (posición 1/s, letras, descargas) crearía
    // lambdas nuevas y recompondría NowPlayingScreen entero.
    val playerActions = remember(playbackViewModel, libraryViewModel, context, currentSong?.id, useSystemEq) {
        PlayerActions(
            onPlayPause = { playbackViewModel.playPause() },
            onNext = { playbackViewModel.next() },
            onPrevious = { playbackViewModel.previous() },
            onSeek = { playbackViewModel.seekTo(it) },
            onSeekBy = { playbackViewModel.seekBy(it) },
            onShuffleToggle = { playbackViewModel.toggleShuffle() },
            onRepeatToggle = { playbackViewModel.toggleRepeatMode() },
            onSkipToIndex = { playbackViewModel.skipToIndex(it) },
            onReorder = { from, to -> playbackViewModel.reorderQueue(from, to) },
            onRemoveFromQueue = { playbackViewModel.removeFromQueue(it) },
            onSaveQueueAsPlaylist = { playbackViewModel.saveQueueAsPlaylist(it) },
            onClearQueue = { playbackViewModel.clearQueue() },
            onToggleFavorite = { currentSong?.let { libraryViewModel.toggleFavorite(it.id) } },
            onToggleDownload = { playbackViewModel.toggleDownload() },
            onToggleKeepScreenOn = { playbackViewModel.toggleKeepScreenOn() },
            onOpenEqualizer = {
                if (useSystemEq) openSystemEqualizer() else showEqualizerSheet = true
            },
            onFetchLyrics = { force -> playbackViewModel.fetchLyrics(force) },
            onSearchLyricsManually = { playbackViewModel.searchLyricsCandidates() },
            onSaveLyrics = { playbackViewModel.requestSaveLyrics() },
            onSelectLyricsCandidate = { candidate ->
                playbackViewModel.selectLyricsFromCandidate(candidate)
                snackbarManager.show(context.getString(R.string.lyrics_refresh_updated))
            },
            onDismissLyricsSearch = { playbackViewModel.dismissLyricsSearch() },
            onUpdatePosition = { playbackViewModel.updatePosition() },
            onAddToPlaylist = { playlistId, songId -> libraryViewModel.addSongToPlaylist(playlistId, songId) },
            onCreatePlaylist = { name ->
                libraryViewModel.createPlaylist(name) { id ->
                    currentSong?.id?.let { songId -> libraryViewModel.addSongToPlaylist(id, songId) }
                }
            },
            onStartSleepTimer = { minutes, finishSong -> playbackViewModel.startSleepTimer(minutes, finishSong) },
            onCancelSleepTimer = { playbackViewModel.cancelSleepTimer() }
        )
    }
    val navigationActions = remember(playbackViewModel, appState, context, isDarkTheme) {
        NavigationActions(
            // Back del player = colapsar la capa (el back del sistema y el gesto hacen lo mismo).
            onBackClick = { appState.collapsePlayer() },
            onLaunchAmbientMode = { timeout ->
                context.startActivity(Intent(context, AmbientPlayerActivity::class.java).apply {
                    putExtra(AmbientPlayerActivity.EXTRA_TIMEOUT_MINUTES, timeout)
                })
            },
            onShowDebugInfo = { playbackViewModel.showDebugInfo(isDarkTheme) },
            onClearDebugInfo = { playbackViewModel.clearDebugInfo() },
            onSelectColor = { color -> playbackViewModel.overrideSongColor(color, isDarkTheme) },
            // `navigateToArtist/Album` detectan que el player está expandido y pasan por
            // `navigateFromPlayer`: colapsan el player recordando volver a él, así que "atrás" del
            // detalle devuelve al reproductor (no a la biblioteca).
            onArtistClick = { name -> appState.navigateToArtist(name) },
            onAlbumClick = { name -> appState.navigateToAlbum(name) }
        )
    }

    // Sin canción actual la capa no tiene nada que mostrar (vaciar la cola llama a
    // `MusicController.stop()`, que anula la canción). Antes esto dejaba el player atascado en el
    // esqueleto shimmer, sin salida salvo varios "atrás". Ahora se colapsa sola: sin canción que
    // sonar, no hay player que enseñar.
    val hasSong = nowPlayingUiState.song != null
    LaunchedEffect(hasSong) {
        if (!hasSong) appState.closePlayerIfEmpty()
    }

    // ATRÁS colapsa la capa del player. Va PRIMERO en la composición a propósito: los `BackHandler`
    // se atienden en LIFO, así que los de las hojas de esta pantalla (letras, cola, ecualizador) se
    // registran después y ganan; éste es el último recurso.
    //
    // El player ya no es una ruta del NavHost, así que el back del sistema no lo cierra solo: este
    // `BackHandler` lo intercepta y llama a `collapsePlayer`, que dispara el container transform de
    // vuelta a la píldora (las dos puntas coexisten en el `AnimatedContent` de [PlayerOverlay]).
    BackHandler { appState.collapsePlayer() }

    Box(modifier = modifier.fillMaxSize()) {
        // Mientras la ruta se cierra (canción ya nula, pop en curso) se pinta una superficie lisa que
        // se desliza hacia abajo, NO el esqueleto shimmer: ese parpadeo es justo lo que el usuario no
        // debe volver a ver al vaciar la cola.
        if (!hasSong) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface)
            )
            return@Box
        }
        // Las filas de la hoja de la cola (dentro del player) no deben declarar el shared element de
        // su carátula: repetirían la key por-canción de las filas de la lista de atrás y de la propia
        // carátula del player. Anular el scope aquí las desactiva; NowPlayingScreen recibe el suyo
        // por PARÁMETRO, no por este local.
        CompositionLocalProvider(LocalAppSharedTransitionScope provides null) {
            NowPlayingScreen(
                // La carátula se engancha a la punta que corresponda ELIGIENDO SU KEY; sin origen no
                // declara shared element y sube con el contenido. Se anula el `sharedTransitionScope`,
                // NO el `animatedVisibilityScope` (ese alimenta el gate de "player asentado").
                sharedTransitionScope = sharedTransitionScope
                    .takeIf { artSharedKey != null || containerSharedKey != null },
                artSharedKey = artSharedKey,
                containerSharedKey = containerSharedKey,
                animatedVisibilityScope = animatedVisibilityScope,
                uiState = nowPlayingUiState,
                playbackState = playbackState,
                currentPositionFlow = playbackViewModel.currentPosition,
                durationFlow = playbackViewModel.duration,
                bufferedPositionFlow = playbackViewModel.bufferedPosition,
                isShuffleEnabled = playbackViewModel.isShuffleEnabled.collectAsStateWithLifecycle().value,
                repeatMode = playbackViewModel.repeatMode.collectAsStateWithLifecycle().value,
                playlistFlow = playbackViewModel.playlist,
                currentIndexFlow = playbackViewModel.currentIndex,
                isFavorite = currentSong?.let { it.id in favorites } ?: false,
                keepScreenOn = keepScreenOn,
                solidBackground = nowPlayingSolidBackground,
                wavyProgress = nowPlayingWavyProgress,
                progressThickness = nowPlayingProgressThickness.dp,
                detailedFormat = nowPlayingDetailedFormat,
                onToggleDetailedFormat = playbackViewModel::toggleDetailedFormat,
                gesturesEnabled = playerGestures,
                playlists = libraryUiState.playlists,
                sleepTimer = playbackViewModel.sleepTimer.collectAsStateWithLifecycle().value,
                eqEnabled = playbackViewModel.eqEnabled.collectAsStateWithLifecycle().value,
                isSavingLyrics = lyricsSaveState.isSaving,
                playerActions = playerActions,
                navigationActions = navigationActions,
                toolbarConfig = playbackViewModel.toolbarConfig.collectAsStateWithLifecycle().value,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Ecualizador: overlay FULL-SCREEN que slide desde abajo, POR ENCIMA del player dentro de la
        // ruta. BackHandler para que el back del sistema cierre PRIMERO la hoja (y solo un segundo
        // back cierre el player).
        BackHandler(enabled = showEqualizerSheet) { showEqualizerSheet = false }
        AnimatedVisibility(
            visible = showEqualizerSheet,
            enter = appSheetEnter(),
            exit = appSheetExit()
        ) {
            EqualizerSheet(
                enabled = playbackViewModel.eqEnabled.collectAsStateWithLifecycle().value,
                bandCount = playbackViewModel.eqBandCount.collectAsStateWithLifecycle().value,
                gains = playbackViewModel.eqGains.collectAsStateWithLifecycle().value,
                bassBoost = playbackViewModel.eqBassBoost.collectAsStateWithLifecycle().value,
                trebleBoost = playbackViewModel.eqTrebleBoost.collectAsStateWithLifecycle().value,
                bassFreq = playbackViewModel.eqBassFreq.collectAsStateWithLifecycle().value,
                trebleFreq = playbackViewModel.eqTrebleFreq.collectAsStateWithLifecycle().value,
                headroomDb = playbackViewModel.eqHeadroomDb.collectAsStateWithLifecycle().value,
                preamp = playbackViewModel.eqPreamp.collectAsStateWithLifecycle().value,
                limiterEnabled = playbackViewModel.eqLimiterEnabled.collectAsStateWithLifecycle().value,
                limiterThresholdDb = playbackViewModel.eqLimiterThresholdDb.collectAsStateWithLifecycle().value,
                limiterThresholdAuto = playbackViewModel.eqLimiterThresholdAuto.collectAsStateWithLifecycle().value,
                gainReductionDb = playbackViewModel.eqGainReductionDb
                    .collectAsStateWithLifecycle(initialValue = 0f).value,
                audioRoute = playbackViewModel.audioRoute.collectAsStateWithLifecycle().value,
                profiles = playbackViewModel.eqPresets.profiles.collectAsStateWithLifecycle().value,
                hiddenPresets = playbackViewModel.eqPresets.hidden.collectAsStateWithLifecycle().value,
                clarityEnabled = playbackViewModel.clarityEnabled.collectAsStateWithLifecycle().value,
                clarityGain = playbackViewModel.clarityGain.collectAsStateWithLifecycle().value,
                conflictWarningSuppressed = playbackViewModel.eqConflictWarningSuppressed.collectAsStateWithLifecycle().value,
                onSuppressConflictWarning = { playbackViewModel.suppressEqConflictWarning() },
                onEnabledChange = { playbackViewModel.setEqEnabled(it) },
                onBandCountChange = { playbackViewModel.setEqBandCount(it) },
                onApplyPreset = { playbackViewModel.setEqGains(it) },
                onApplyProfile = { playbackViewModel.applyEqProfile(it) },
                onSaveCurrentAsProfile = { playbackViewModel.saveCurrentAsEqProfile(it) },
                onDeleteProfile = { playbackViewModel.eqPresets.deleteProfile(it) },
                onBandChange = { band, db -> playbackViewModel.setEqBand(band, db) },
                onBandChangeFinished = { playbackViewModel.commitEqGains() },
                onBassBoostChange = { playbackViewModel.setEqBassBoost(it) },
                onTrebleBoostChange = { playbackViewModel.setEqTrebleBoost(it) },
                onBassFreqChange = { playbackViewModel.setEqBassFreq(it) },
                onTrebleFreqChange = { playbackViewModel.setEqTrebleFreq(it) },
                onBoostChangeFinished = { playbackViewModel.commitEqBoosts() },
                onPreampChange = { playbackViewModel.setEqPreamp(it) },
                onPreampChangeFinished = { playbackViewModel.commitEqPreamp() },
                onLimiterEnabledChange = { playbackViewModel.setEqLimiterEnabled(it) },
                onLimiterThresholdChange = { playbackViewModel.setEqLimiterThreshold(it) },
                onLimiterThresholdChangeFinished = { playbackViewModel.commitEqLimiterThreshold() },
                onLimiterThresholdAutoChange = { playbackViewModel.setEqLimiterThresholdAuto(it) },
                onClarityEnabledChange = { playbackViewModel.setClarityEnabled(it) },
                onClarityGainChange = { playbackViewModel.setClarityGain(it) },
                onClarityGainChangeFinished = { playbackViewModel.commitClarityGain() },
                onReset = { playbackViewModel.resetEq() },
                onOpenSystemEq = openSystemEqualizer,
                onDismiss = { showEqualizerSheet = false }
            )
        }
    }
}
