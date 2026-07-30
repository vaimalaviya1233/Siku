package com.qhana.siku.ui

import android.content.Intent
import android.media.audiofx.AudioEffect
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qhana.siku.R
import com.qhana.siku.data.model.PlaybackState
import com.qhana.siku.data.util.SnackbarManager
import com.qhana.siku.ui.components.AddSongsToPlaylistSheet
import com.qhana.siku.ui.components.ComponentConfig
import com.qhana.siku.ui.components.EqualizerSheet
import com.qhana.siku.ui.components.MiniPlayer
import com.qhana.siku.ui.components.miniPlayerExpandDrag
import com.qhana.siku.ui.components.SaveLyricsDialog
import com.qhana.siku.ui.navigation.Screen
import com.qhana.siku.ui.screens.AmbientPlayerActivity
import com.qhana.siku.ui.screens.NavigationActions
import com.qhana.siku.ui.screens.NowPlayingScreen
import com.qhana.siku.ui.screens.PlayerActions
import com.qhana.siku.ui.theme.SCREEN_TRANSFORM_MS
import com.qhana.siku.ui.theme.ScreenSlideEasing
import com.qhana.siku.ui.theme.appSheetEnter
import com.qhana.siku.ui.theme.appSheetExit
import com.qhana.siku.ui.viewmodel.LibraryViewModel
import com.qhana.siku.ui.viewmodel.PlaybackViewModel

/**
 * Capa de reproductor ÚNICA sobre el NavHost (pill ↔ player), más el FAB contextual y las
 * hojas/diálogos que dispara. Una sola instancia compartida entre home y detalles: al navegar
 * no se recrea (marquee, progreso y animaciones continúan). Expandida, es el NowPlaying a
 * pantalla completa: son DOS CAPAS hermanas —píldora abajo, player encima— y el player entra
 * deslizando mientras la carátula morfa entre ambas como shared element; el NavHost de abajo
 * nunca se entera.
 *
 * Receptor [BoxScope]: se monta en el Box raíz, alineada abajo.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun BoxScope.PlayerOverlay(
    appState: MusicAppState,
    playbackViewModel: PlaybackViewModel,
    libraryViewModel: LibraryViewModel,
    snackbarManager: SnackbarManager,
    sharedTransitionScope: SharedTransitionScope
) {
    val context = LocalContext.current

    val currentSong by playbackViewModel.currentSong.collectAsStateWithLifecycle()
    val playbackState by playbackViewModel.playbackState.collectAsStateWithLifecycle()
    val nowPlayingUiState by playbackViewModel.nowPlayingUiState.collectAsStateWithLifecycle()
    val keepScreenOn by playbackViewModel.keepScreenOn.collectAsStateWithLifecycle()
    val nowPlayingSolidBackground by playbackViewModel.nowPlayingSolidBackground.collectAsStateWithLifecycle()
    val nowPlayingWavyProgress by playbackViewModel.nowPlayingWavyProgress.collectAsStateWithLifecycle()
    val nowPlayingDetailedFormat by playbackViewModel.nowPlayingDetailedFormat.collectAsStateWithLifecycle()
    val playerGestures by playbackViewModel.playerGestures.collectAsStateWithLifecycle()
    val lyricsSaveState by playbackViewModel.lyricsSaveState.collectAsStateWithLifecycle()
    val libraryUiState by libraryViewModel.uiState.collectAsStateWithLifecycle()
    val favorites = libraryUiState.favorites

    val navBackStackEntry = appState.currentBackStackEntry
    val currentRoute = navBackStackEntry?.destination?.route
    val onPlaylistDetailRoute = currentRoute == Screen.PlaylistDetail.route
    val onFavoritesRoute = currentRoute == Screen.Favorites.route
    // Rutas de lista donde aplica la hoja de "añadir canciones" (botón en el detalle).
    val onAddSongsRoute = onPlaylistDetailRoute || onFavoritesRoute
    val miniPlayerVisible = when (currentRoute) {
        // Rutas con capa flotante habilitada; la píldora se cae sola si no hay canción
        // (el viejo FAB de "añadir canciones" ya no existe: es un botón del detalle).
        Screen.Library.route, Screen.PlaylistDetail.route, Screen.Favorites.route -> true
        Screen.ArtistDetail.route, Screen.AlbumDetail.route, Screen.GenreDetail.route -> currentSong != null
        else -> false
    }

    // Si la ruta deja de ser un detalle de lista (back, navegación), la hoja muere con ella.
    LaunchedEffect(onAddSongsRoute) {
        if (!onAddSongsRoute) appState.showAddSongsSheet = false
    }
    // Atrás cierra el player (se compone DESPUÉS del NavHost para tener prioridad).
    BackHandler(enabled = appState.playerExpanded) { appState.collapsePlayer() }
    // Si la ruta actual no muestra reproductor (settings, onboarding…), colapsar. La guarda
    // `currentRoute != null` es CLAVE: al rotar, currentBackStackEntryAsState emite null un
    // instante mientras el NavController se restaura → miniPlayerVisible caía a false (rama
    // else) y este efecto cerraba el NowPlaying restaurado, tirándote al home. Con ruta nula
    // (transición) no se toca nada; se decide solo cuando hay una ruta real sin reproductor.
    LaunchedEffect(miniPlayerVisible, currentRoute) {
        if (currentRoute != null && !miniPlayerVisible) appState.collapsePlayer()
    }

    // --- Guardar la letra en el archivo -------------------------------------------------------
    //
    // Los tres pasos posibles viven aquí, fuera de la capa del player: si colgaran del
    // NowPlaying expandido, cerrar el reproductor a mitad del permiso mataría el diálogo y el
    // guardado quedaría a medias sin que nadie lo cancelara.

    // MediaStore en API 30+: solo la Activity puede lanzar el IntentSender del sistema.
    val systemWriteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) playbackViewModel.retryPendingSave()
        else playbackViewModel.cancelPendingSave()
    }
    LaunchedEffect(lyricsSaveState.pendingPermission) {
        lyricsSaveState.pendingPermission?.let { sender ->
            systemWriteLauncher.launch(IntentSenderRequest.Builder(sender).build())
            playbackViewModel.consumePendingPermission()
        }
    }

    lyricsSaveState.options?.let { options ->
        SaveLyricsDialog(
            options = options,
            onDismiss = { playbackViewModel.dismissSaveLyricsDialog() },
            onConfirm = { mode, remember -> playbackViewModel.confirmSaveLyrics(mode, remember) }
        )
    }

    // El consentimiento de escritura sobre OneDrive se explica ANTES de lanzar la pantalla de
    // Microsoft: es un permiso sobre todo el drive y aparecer de la nada asusta con razón.
    if (lyricsSaveState.needsCloudConsent) {
        val activity = LocalActivity.current
        AlertDialog(
            onDismissRequest = { playbackViewModel.cancelPendingSave() },
            title = { Text(stringResource(R.string.lyrics_save_consent_title)) },
            text = { Text(stringResource(R.string.lyrics_save_consent_message)) },
            confirmButton = {
                TextButton(
                    onClick = { activity?.let { playbackViewModel.grantCloudWriteConsent(it) } },
                    enabled = activity != null
                ) { Text(stringResource(R.string.lyrics_save_consent_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { playbackViewModel.cancelPendingSave() }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    // Hoja del ECUALIZADOR PROPIO (5/10 bandas + float): el botón de la barra del NowPlaying
    // la abre — salvo que en Ajustes se prefiera el EQ del sistema, en cuyo caso el botón
    // lanza el panel del sistema directamente.
    var showEqualizerSheet by remember { mutableStateOf(false) }
    val useSystemEq by playbackViewModel.useSystemEq.collectAsStateWithLifecycle()

    // Ecualizador del sistema (MIUI primero, panel estándar como fallback). El feedback de
    // fallo va por TOAST, no por el SnackbarManager: se invoca también desde el botón dentro
    // de EqualizerSheet (ModalBottomSheet = ventana propia encima de la de MainActivity) y
    // el snackbar del host central quedaría tapado por la hoja; el Toast flota sobre todo.
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

    // Player y píldora son DOS CAPAS HERMANAS del Box raíz, no dos ramas de un `AnimatedContent`.
    // Ese es el punto de todo el bloque: el orden de dibujo lo fija la ESTRUCTURA (la píldora se
    // declara primero, el player después, así que el player está encima por construcción) y no
    // una negociación de la transición. Con `AnimatedContent` el z-order salía del
    // `targetContentZIndex` del contenido que ENTRA, de modo que al cerrar la píldora se pintaba
    // por delante del player que todavía bajaba y se materializaba SOBRE él —el bug— y cualquier
    // arreglo por ahí seguía siendo negociado: bastaba interrumpir una apertura para que los
    // índices empataran y volviera a ganar el entrante.
    //
    // La píldora no se anima a sí misma en esta transición: el player la TAPA al subir y la
    // DESTAPA al bajar. Lo único que hace su `AnimatedVisibility` interno es decidir cuándo está
    // MONTADA, y eso importa por dos motivos que no son estéticos: montada bajo el player seguiría
    // recibiendo toques en las zonas donde el reproductor no consume ninguno, y seguiría existiendo
    // para TalkBack detrás de una pantalla completa.
    val playerVisible = appState.playerExpanded && currentSong != null

    // --- Capa 1: la PÍLDORA -------------------------------------------------------------------
    //
    // Dos `AnimatedVisibility` anidados porque son dos preguntas distintas: el de fuera es
    // "¿esta ruta tiene reproductor?" (entra y sale deslizando al navegar) y el de dentro es
    // "¿está el player encima?".
    AnimatedVisibility(
        visible = miniPlayerVisible,
        // Specs del MotionScheme y no los defaults de `AnimatedVisibility`: esos son springs de
        // compose-animation (`StiffnessMediumLow`), ajenos al tema.
        enter = appSheetEnter(),
        exit = appSheetExit(),
        modifier = Modifier.align(Alignment.BottomCenter)
    ) {
        AnimatedVisibility(
            // Aparece ENTERA y de inmediato: al cerrar ya está en su sitio detrás del player,
            // que la va descubriendo conforme baja.
            enter = EnterTransition.None,
            // Al abrir se queda quieta y opaca hasta que el slide terminó de taparla, y recién
            // ahí se desmonta (`snap` DIFERIDO, no un fade). Desmontarla antes dejaría su franja
            // vacía los frames que el player tarda en llegar hasta ella.
            exit = fadeOut(snap(delayMillis = PLAYER_SLIDE_MS)),
            visible = !playerVisible
        ) {
            // Scope de ESTA capa: es el que empareja la carátula del mini con la del player como
            // shared element. Antes ambos lados colgaban del scope único del `AnimatedContent`;
            // ahora cada capa aporta el suyo, que es el patrón normal entre destinos de un
            // NavHost. Se nombra porque hay dos `AnimatedVisibility` anidados y `this@…` sería
            // ambiguo de leer.
            val miniScope = this

            // Capa flotante del bottom: solo el MiniPlayer a TODO EL ANCHO (ya no hay FAB
            // encima). Sin padding lateral en el Column: el MiniPlayer recibe su margen
            // explícito, alineando al borde sin offsets.
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(top = 10.dp, bottom = ComponentConfig.FloatingBarBottomMargin)
            ) {
                // Ya NO hay FAB flotante: "crear lista" es un botón sobre Favoritos
                // (PlaylistList) y "añadir canciones" es un botón redondo junto al aleatorio
                // en el detalle (DetailPlayButtons). La capa flotante es solo el MiniPlayer.

                // MiniPlayer a todo el ancho.
                //
                // La VISIBILIDAD la decide `currentSong`: solo es null sin sesión (arranque
                // antes del restore) o tras stop(), y en ambos casos la píldora NO debe
                // mostrarse (retenerla dejaba una píldora fantasma tras logout).
                //
                // Los DATOS salen de `nowPlayingUiState.song`, que es la fila de Room y no el
                // objeto que el reproductor cargó al empezar a sonar. `currentSong` se queda
                // congelado en lo que había entonces, así que todo lo que la BD escriba después
                // sobre el tema en curso —carátula reparada, colores, letras, la descarga que
                // termina— lo veía el NowPlaying y no el mini. Se compara el id para no pintar
                // los datos del tema anterior en el instante en que cambia la canción.
                val song = currentSong?.let { current ->
                    nowPlayingUiState.song?.takeIf { it.id == current.id } ?: current
                }
                if (song != null) {
                    MiniPlayer(
                        song = song,
                        isPlaying = playbackState == PlaybackState.PLAYING,
                        isBuffering = playbackState == PlaybackState.BUFFERING,
                        onPlayPause = { playbackViewModel.playPause() },
                        onNextClick = { playbackViewModel.next() },
                        // `fromPill`: es la ÚNICA apertura en la que la carátula ya está en
                        // pantalla y puede viajar de aquí al reproductor (ver playerOpenedFromPill).
                        onClick = { appState.openPlayer(fromPill = true) },
                        // Como FLOWS, no como valor: el tick de posición repinta el relleno de
                        // progreso sin recomponer el mini (ver el kdoc del parámetro).
                        currentPositionFlow = playbackViewModel.currentPosition,
                        durationFlow = playbackViewModel.duration,
                        // Gateado igual que la otra punta del par (ver la llamada a
                        // NowPlayingScreen): las dos tienen que declarar el shared element o
                        // ninguna. Una punta suelta no encuentra pareja y, durante el desmontaje
                        // de la píldora, se pintaría en el overlay sin motivo.
                        sharedTransitionScope = sharedTransitionScope.takeIf {
                            appState.playerOpenedFromPill
                        },
                        animatedVisibilityScope = miniScope,
                        // Margen lateral del spec (el Column ya no lo aplica).
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = ComponentConfig.FloatingBarSideMargin)
                            // Deslizar hacia arriba abre el reproductor: es el gesto inverso
                            // al de cerrarlo, y sin él la píldora solo respondía al tap.
                            .miniPlayerExpandDrag(playerGestures) {
                                appState.openPlayer(fromPill = true)
                            }
                    )
                }
            }
        }
    }

    // --- Capa 2: el PLAYER --------------------------------------------------------------------
    //
    // Declarado DESPUÉS de la píldora = dibujado encima, siempre. Ocupa la pantalla entera y
    // sube/baja deslizando; la carátula morfa como shared element en paralelo.
    AnimatedVisibility(
        visible = playerVisible,
        enter = slideInVertically(tween(PLAYER_SLIDE_MS, easing = ScreenSlideEasing)) { it },
        exit = slideOutVertically(tween(PLAYER_SLIDE_MS, easing = ScreenSlideEasing)) { it },
        modifier = Modifier.fillMaxSize()
    ) {
        val playerScope = this
        val isDarkTheme = isSystemInDarkTheme()
        // Memoizamos las acciones: si se construyen inline, cada recomposición
        // (posición cada 1s, lyrics, descargas…) crea lambdas nuevas →
        // NowPlayingScreen se recompone entero.
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
                // El diálogo de crear lista del NowPlaying solo se abre desde la hoja
                // "agregar a lista" de la canción en curso: la lista nueva nace con ella
                // (antes se creaba vacía y la canción se perdía).
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
                onBackClick = { appState.collapsePlayer() },
                onLaunchAmbientMode = { timeout ->
                    context.startActivity(Intent(context, AmbientPlayerActivity::class.java).apply {
                        putExtra(AmbientPlayerActivity.EXTRA_TIMEOUT_MINUTES, timeout)
                    })
                },
                onShowDebugInfo = { playbackViewModel.showDebugInfo(isDarkTheme) },
                onClearDebugInfo = { playbackViewModel.clearDebugInfo() },
                onSelectColor = { color -> playbackViewModel.overrideSongColor(color, isDarkTheme) },
                onArtistClick = { name ->
                    appState.collapsePlayer()
                    appState.navigateToArtist(name)
                },
                onAlbumClick = { name ->
                    appState.collapsePlayer()
                    appState.navigateToAlbum(name)
                }
            )
        }
        NowPlayingScreen(
            // El shared element de la carátula SOLO cuando se abrió desde la píldora: es la única
            // apertura en la que la portada ya está en pantalla y tiene de dónde viajar. Abriendo
            // desde una lista se pasa null y la carátula sube CON el resto del contenido, como una
            // pieza más del reproductor — que es lo correcto, porque la fila que se tocó sigue ahí
            // detrás y de ella no sale nada. Ver `MusicAppState.playerOpenedFromPill`.
            //
            // OJO: se anula el `sharedTransitionScope`, NO el `animatedVisibilityScope`. Ese
            // segundo alimenta además los gates de "player ya asentado" (reveal de cambio de
            // canción y blur del vidrio); pasarlo null los daría por asentados en pleno slide.
            sharedTransitionScope = sharedTransitionScope.takeIf { appState.playerOpenedFromPill },
            animatedVisibilityScope = playerScope,
            uiState = nowPlayingUiState,
            playbackState = playbackState,
            currentPositionFlow = playbackViewModel.currentPosition,
            durationFlow = playbackViewModel.duration,
            bufferedPositionFlow = playbackViewModel.bufferedPosition,
            isShuffleEnabled = playbackViewModel.isShuffleEnabled.collectAsStateWithLifecycle().value,
            repeatMode = playbackViewModel.repeatMode.collectAsStateWithLifecycle().value,
            playlist = playbackViewModel.playlist.collectAsStateWithLifecycle().value,
            currentIndex = playbackViewModel.currentIndex.collectAsStateWithLifecycle().value,
            isFavorite = currentSong?.let { it.id in favorites } ?: false,
            keepScreenOn = keepScreenOn,
            solidBackground = nowPlayingSolidBackground,
            wavyProgress = nowPlayingWavyProgress,
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

    // Ecualizador: overlay FULL-SCREEN (ya no es ModalBottomSheet) que slide desde abajo, mismo
    // patrón que lyrics/cola. Vive FUERA de la capa del player para sobrevivir a su colapso.
    // BackHandler para el back del sistema (el header también tiene su flecha).
    androidx.activity.compose.BackHandler(enabled = showEqualizerSheet) { showEqualizerSheet = false }
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
            suggestedPreampDb = playbackViewModel.eqSuggestedPreampDb.collectAsStateWithLifecycle().value,
            limiterEnabled = playbackViewModel.eqLimiterEnabled.collectAsStateWithLifecycle().value,
            // El EFECTIVO (en automático lo calcula el ViewModel a partir del pico de la curva),
            // no el manual: es el que el limitador está usando de verdad.
            limiterThresholdDb = playbackViewModel.eqLimiterThresholdDb.collectAsStateWithLifecycle().value,
            limiterThresholdAuto = playbackViewModel.eqLimiterThresholdAuto.collectAsStateWithLifecycle().value,
            // Flow FRÍO muestreado: solo corre mientras esta hoja está compuesta, que es
            // justamente cuando el medidor se ve.
            gainReductionDb = playbackViewModel.eqGainReductionDb
                .collectAsStateWithLifecycle(initialValue = 0f).value,
            audioRoute = playbackViewModel.audioRoute.collectAsStateWithLifecycle().value,
            customPresets = playbackViewModel.customEqPresets.collectAsStateWithLifecycle().value,
            conflictWarningSuppressed = playbackViewModel.eqConflictWarningSuppressed.collectAsStateWithLifecycle().value,
            onSuppressConflictWarning = { playbackViewModel.suppressEqConflictWarning() },
            onEnabledChange = { playbackViewModel.setEqEnabled(it) },
            onBandCountChange = { playbackViewModel.setEqBandCount(it) },
            onApplyPreset = { playbackViewModel.setEqGains(it) },
            onApplyCustomPreset = { playbackViewModel.applyCustomEqPreset(it) },
            onSaveCurrentAsPreset = { playbackViewModel.saveCurrentAsEqPreset(it) },
            onDeleteCustomPreset = { playbackViewModel.deleteEqPreset(it) },
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
            onReset = { playbackViewModel.resetEq() },
            onOpenSystemEq = openSystemEqualizer,
            onDismiss = { showEqualizerSheet = false }
        )
    }

    // --- Hojas/diálogos disparados por el FAB (estado en MusicAppState) ---

    // (El "crear lista" ya no vive en el FAB: es un botón en PlaylistList con su propio
    // CreatePlaylistDialog vía LibraryScreen.showCreatePlaylistDialog.)

    // Selector "añadir canciones": en una playlist el id/nombre salen de la ruta activa;
    // en Favoritos el destino es la lista fija.
    val sheetPlaylistId = if (onPlaylistDetailRoute) navBackStackEntry?.arguments
        ?.getString(Screen.PlaylistDetail.ARG_PLAYLIST_ID)?.toLongOrNull() else null
    if (appState.showAddSongsSheet && (sheetPlaylistId != null || onFavoritesRoute)) {
        val sheetPlaylistName = if (sheetPlaylistId != null) {
            Screen.PlaylistDetail.decodeName(
                navBackStackEntry?.arguments?.getString(Screen.PlaylistDetail.ARG_PLAYLIST_NAME)
            )
        } else stringResource(R.string.common_favorites)
        val existingIds: Set<String> = if (sheetPlaylistId != null) {
            val sheetPlaylistSongs by libraryViewModel.getPlaylistSongs(sheetPlaylistId)
                .collectAsStateWithLifecycle(emptyList())
            remember(sheetPlaylistSongs) { sheetPlaylistSongs.map { it.id }.toSet() }
        } else favorites
        val pickerQuery by libraryViewModel.songPickerQuery.collectAsStateWithLifecycle()
        val pickerResults by libraryViewModel.songPickerResults.collectAsStateWithLifecycle()
        val candidates = remember(pickerResults, existingIds) {
            pickerResults.filterNot { it.id in existingIds }
        }

        AddSongsToPlaylistSheet(
            playlistName = sheetPlaylistName,
            candidates = candidates,
            query = pickerQuery,
            // Con búsqueda activa y cero resultados el mensaje debe ser "ningún
            // resultado", no "no hay canciones que añadir".
            hasSongsAvailable = pickerQuery.isNotBlank() || candidates.isNotEmpty(),
            onQueryChange = { libraryViewModel.setSongPickerQuery(it) },
            onConfirm = { ids ->
                if (sheetPlaylistId != null) {
                    libraryViewModel.addSongsToPlaylist(sheetPlaylistId, ids)
                } else {
                    libraryViewModel.addSongsToFavorites(ids)
                }
                libraryViewModel.setSongPickerQuery("")
                appState.showAddSongsSheet = false
            },
            onDismiss = {
                libraryViewModel.setSongPickerQuery("")
                appState.showAddSongsSheet = false
            }
        )
    }
}

// --- Coreografía píldora ↔ player (ver las dos capas de PlayerOverlay) ---

/**
 * Recorrido del player al abrir y al cerrar. Va con [SCREEN_TRANSFORM_MS] y [ScreenSlideEasing],
 * o sea el token *default spatial* del motion Expressive (500 ms nominales).
 *
 * **El acople con la carátula es obligatorio y está medido**: la portada viaja como shared element
 * mientras esta capa se desplaza, y si asienta antes que el slide se queda quieta en su posición
 * final mientras el resto del reproductor sigue subiendo por debajo — que es literalmente el
 * "la carátula no sube con el resto del contenido, simplemente aparece" que reportó el usuario.
 * Los dos extremos usan por tanto la misma curva y duración (ver `AppBoundsTransform`).
 *
 * Que 500 ms nominales NO se sientan lentos es cosa de la curva, y es el motivo de que este
 * archivo pasara por 450/`FastOutSlowIn` y por 500/`Emphasized` antes de llegar aquí: el token
 * spatial sobrepasa y asienta, así que despacha el 99,6 % del recorrido en 175 ms. `Emphasized`
 * —la curva del M3 anterior a Expressive— gastaba la mitad del tiempo en el último 13 %, y ESO
 * era lo que se leía como pereza.
 *
 * Sigue siendo un tween y no un spring del `MotionScheme` por la razón mecánica de siempre: la
 * píldora espera exactamente este tiempo antes de desmontarse (`snap(delayMillis = …)`) y un
 * spring no tiene duración nominal que ofrecerle.
 */
private const val PLAYER_SLIDE_MS = SCREEN_TRANSFORM_MS
