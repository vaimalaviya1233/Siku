package com.qhana.siku.ui

import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Transition
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qhana.siku.R
import com.qhana.siku.data.model.PlaybackState
import com.qhana.siku.data.util.JankProbe
import com.qhana.siku.data.util.SnackbarManager
import com.qhana.siku.ui.components.AddSongsToPlaylistSheet
import com.qhana.siku.ui.components.ComponentConfig
import com.qhana.siku.ui.components.MiniPlayer
import com.qhana.siku.ui.components.SaveLyricsDialog
import com.qhana.siku.ui.components.miniPlayerExpandDrag
import com.qhana.siku.ui.navigation.Screen
import com.qhana.siku.ui.theme.appFadeEnter
import com.qhana.siku.ui.theme.appFadeExit
import com.qhana.siku.ui.viewmodel.LibraryViewModel
import com.qhana.siku.ui.viewmodel.PlaybackViewModel

/**
 * Capa flotante sobre el NavHost: el **`AnimatedContent` píldora ↔ reproductor** más las
 * hojas/diálogos GLOBALES que sobreviven a la navegación.
 *
 * **El reproductor a pantalla completa VIVE aquí** (rama Expanded del `AnimatedContent`, contenido en
 * [NowPlayingLayer]). El motivo es el *container transform*: la barra crece hasta ser el player. Ese
 * morph exige que sus DOS puntas (píldora y player) vivan en el MISMO `AnimatedContentScope`; cuando
 * el player era una ruta del NavHost cada punta estaba en un dueño de transición distinto y el
 * `sharedBounds` no cruzaba (se leía como slide). El estado lo gobierna [MusicAppState.playerExpanded]
 * (booleano), no la ruta.
 *
 * El `AnimatedContent` tiene TRES estados ([PlayerLayerState]): **Expanded** (player a pantalla
 * completa), **Collapsed** (píldora) y **Hidden** (nada, en rutas sin reproducción o sin canción).
 * Collapsed↔Expanded es el container transform (lo pinta el `sharedBounds` `PLAYER_CONTAINER_SHARED_KEY`
 * que declaran el Card de la píldora y la raíz del player); Hidden↔Collapsed es un fundido de la
 * píldora al cambiar de ruta.
 *
 * También aquí, en un nivel estable:
 *  - Los **diálogos de guardar letra** + su `ActivityResultLauncher` (el permiso del sistema no puede
 *    colgar de una capa que se desmonta a mitad) y el consentimiento de escritura en nube.
 *  - La hoja **"añadir canciones"** del detalle de playlist/Favoritos.
 *
 * Receptor [BoxScope]: se monta en el Box raíz, sobre el NavHost.
 */
/**
 * z de la rama que ENTRA en el container transform de la capa, por encima de la que sale (que queda a
 * 0, el default). Lo pide la propia API: `KeepUntilTransitionsFinished` retiene la rama saliente y, si
 * el sentido se invierte a mitad (abrir y cerrar seguido, la rama reutilizada), sin z explícita la
 * saliente puede pintarse ENCIMA de la entrante. Ordinal, no medida: solo importa que sea mayor que 0.
 */
private const val CONTAINER_TARGET_BRANCH_Z = 1f

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun BoxScope.PlayerOverlay(
    appState: MusicAppState,
    /**
     * Transición de la capa (Hidden/Collapsed/Expanded), creada por `MusicPlayerScreen` con
     * `updateTransition`. Se recibe hecha porque de ella se deriva también el origen congelado del
     * morph ([morphOrigin]), que consumen las filas del NavHost — hermanas de esta capa, no hijas.
     */
    layerTransition: Transition<PlayerLayerState>,
    /**
     * De dónde sale / a dónde vuelve el reproductor, CONGELADO mientras [layerTransition] corre. Es la
     * ÚNICA fuente para las keys de los shared elements de la capa y para los gates que dependen del
     * origen; `appState.playerArtOrigin` es la petición y no se lee aquí — ver [rememberPlayerMorphOrigin].
     */
    morphOrigin: PlayerMorphOrigin,
    /**
     * Paleta de lo que queda DEBAJO del reproductor, retenida mientras [layerTransition] corre (ver
     * [rememberUnderlayColorScheme]). La PÍLDORA se pinta con ella: al abrir desde una fila con otra
     * carátula, la barra que se está desvaneciendo no debe cambiar de color a mitad del fundido —
     * pertenece al mismo mundo que la biblioteca de detrás, no al player que crece.
     */
    underlayScheme: ColorScheme,
    playbackViewModel: PlaybackViewModel,
    libraryViewModel: LibraryViewModel,
    snackbarManager: SnackbarManager,
    sharedTransitionScope: SharedTransitionScope
) {
    val currentSong by playbackViewModel.currentSong.collectAsStateWithLifecycle()
    val playbackState by playbackViewModel.playbackState.collectAsStateWithLifecycle()
    val nowPlayingUiState by playbackViewModel.nowPlayingUiState.collectAsStateWithLifecycle()
    val playerGestures by playbackViewModel.playerGestures.collectAsStateWithLifecycle()
    val miniPlayerRoundedRect by playbackViewModel.miniPlayerRoundedRect.collectAsStateWithLifecycle()
    val lyricsSaveState by playbackViewModel.lyricsSaveState.collectAsStateWithLifecycle()
    val libraryUiState by libraryViewModel.uiState.collectAsStateWithLifecycle()
    val favorites = libraryUiState.favorites

    val navBackStackEntry = appState.currentBackStackEntry
    val currentRoute = navBackStackEntry?.destination?.route
    val onPlaylistDetailRoute = currentRoute == Screen.PlaylistDetail.route
    val onFavoritesRoute = currentRoute == Screen.Favorites.route
    // Rutas de lista donde aplica la hoja de "añadir canciones" (botón en el detalle).
    val onAddSongsRoute = onPlaylistDetailRoute || onFavoritesRoute


    // Si la ruta deja de ser un detalle de lista (back, navegación), la hoja muere con ella.
    LaunchedEffect(onAddSongsRoute) {
        if (!onAddSongsRoute) appState.showAddSongsSheet = false
    }

    // --- Guardar la letra en el archivo -------------------------------------------------------
    //
    // Vive aquí, en un nivel estable, y NO dentro de [NowPlayingLayer]: si colgara de la rama Expanded
    // del `AnimatedContent`, colapsar el reproductor a mitad del permiso del sistema desmontaría el
    // launcher y el guardado quedaría a medias sin que nadie lo cancelara.

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

    // --- La CAPA del reproductor: AnimatedContent píldora ↔ player -----------------------------
    //
    // Un SOLO `AnimatedContent` con las dos puntas del container transform: eso es lo que las hace
    // COEXISTIR en el mismo `AnimatedContentScope` y hace NATIVO el morph (ver el KDoc de la función).
    // Collapsed↔Expanded lo pinta el `sharedBounds` de contenedor (Card de la píldora + raíz del
    // player); la rama saliente vive lo que el bounds tarda en asentar (`KeepUntilTransitionsFinished`,
    // ver el `transitionSpec`: sin ella la rama moriría en el acto y el shared element no tendría
    // punta; con un fade a pantalla completa costaba un `saveLayer` por frame), y la píldora se funde
    // en Hidden↔Collapsed con `appFadeEnter/Exit`. Las áreas VACÍAS de la capa no llevan `pointerInput`
    // ni fondo, así que los toques pasan al NavHost de abajo (solo la píldora y el player expandido
    // interceptan).
    //
    // La `Transition` la POSEE `MusicPlayerScreen` (`updateTransition` sobre el estado de la capa) y
    // aquí solo se recorre: es la misma de la que se deriva el origen CONGELADO del morph, así que
    // "qué fila se oculta", "con qué key declara el player" y "qué rama entra/sale" salen de un único
    // estado, en el mismo frame.
    layerTransition.AnimatedContent(
        transitionSpec = {
            // Collapsed↔Expanded = container transform: el que ENTRA es SÓLIDO (la superficie del
            // player nunca se ve transparente, solo crece), el que SALE se disuelve encima y vive
            // hasta que el bounds asienta. Hidden↔Collapsed = fundido normal de la píldora al cambiar
            // de ruta.
            //
            // **Con la condición del ORIGEN**: el `EnterTransition.None` solo es correcto cuando hay una
            // superficie de la que crecer (píldora o fila). Abriendo desde un chip del inicio o desde
            // la notificación no hay ninguna, así que el player aparecía DE GOLPE — sin morph y sin
            // fundido, que es el único caso en que "sólido" no significa nada.
            val hasOriginSurface = morphOrigin.kind != PlayerArtOrigin.NONE
            val containerMorph = hasOriginSurface && (
                (initialState == PlayerLayerState.Collapsed && targetState == PlayerLayerState.Expanded) ||
                (initialState == PlayerLayerState.Expanded && targetState == PlayerLayerState.Collapsed)
            )
            // La rama saliente NO se funde: `KeepUntilTransitionsFinished` la mantiene viva hasta que
            // TODAS las transiciones del AnimatedContent terminan —el bounds del `sharedBounds`
            // incluido—, o sea exactamente lo que dura el morph, por construcción y sin ninguna
            // duración elegida a mano. Antes era un `fadeOut` de la rama, y eso costaba caro donde no
            // se veía: la rama es una Box a pantalla completa, y un alfa sobre ella obliga a HWUI a un
            // `saveLayer` offscreen de la pantalla ENTERA en cada frame del morph (medido con atrace:
            // "alpha caused saveLayer 1080x2400" en cada frame del cierre) — el RenderThread pasaba de
            // 2-3 a 5-8 ms/frame y el scroll que arrancaba en esa cola perdía frames. Lo que SÍ se funde
            // es el CONTENIDO de la superficie que morfa (`appContainerContentExit*`), que es lo que se
            // ve. Ver el KDoc de la función sobre `targetContentZIndex`.
            JankProbe.note { "AnimatedContent spec: $initialState→$targetState morph=$containerMorph origen=${morphOrigin.kind}" }
            if (containerMorph) {
                (EnterTransition.None togetherWith ExitTransition.KeepUntilTransitionsFinished)
                    .apply { targetContentZIndex = CONTAINER_TARGET_BRANCH_Z }
            } else appFadeEnter() togetherWith appFadeExit()
        },
        modifier = Modifier.fillMaxSize()
    ) { state ->
        // El scope de ESTE AnimatedContent es el `animatedVisibilityScope` de las dos puntas del
        // shared element; se captura antes de entrar en Box/Column (que lo sombrearían con su receptor).
        val layerScope = this
        // Sonda: vida de cada rama del AnimatedContent (compuesta/descompuesta) y su transición.
        DisposableEffect(state) {
            JankProbe.note { "rama $state COMPUESTA" }
            onDispose { JankProbe.note { "rama $state DESCOMPUESTA" } }
        }
        SideEffect {
            JankProbe.note {
                "rama $state: ${layerScope.transition.currentState}→${layerScope.transition.targetState}"
            }
        }
        when (state) {
            PlayerLayerState.Expanded -> Box(Modifier.fillMaxSize()) {
                // (Aquí hubo, del 16 al 17 ago, la punta player de un shared element solo-sombra con
                // `RemeasureToBounds`. La sombra de la píldora ya no viaja — ver `pillShadow` en
                // MiniPlayer.kt: al arrancar el cierre pintaba un blur de pantalla entera por frame.)
                NowPlayingLayer(
                    appState = appState,
                    morphOrigin = morphOrigin,
                    playbackViewModel = playbackViewModel,
                    libraryViewModel = libraryViewModel,
                    snackbarManager = snackbarManager,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = layerScope,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // La píldora va bajo la paleta RETENIDA, igual que el NavHost: pertenece al mundo de
            // debajo del reproductor (ver el KDoc de `underlayScheme`).
            PlayerLayerState.Collapsed -> MaterialTheme(colorScheme = underlayScheme) { Box(Modifier.fillMaxSize()) {
                // La VISIBILIDAD la decide `currentSong` (solo null sin sesión o tras stop()). Los DATOS
                // salen de `nowPlayingUiState.song` (fila de Room, con carátula/colores/letras/descarga
                // al día), cayendo a `currentSong` mientras el id no coincide (instante del cambio).
                val song = currentSong?.let { current ->
                    nowPlayingUiState.song?.takeIf { it.id == current.id } ?: current
                }
                if (song != null) {
                    Column(
                        horizontalAlignment = Alignment.End,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(top = 10.dp, bottom = ComponentConfig.FloatingBarBottomMargin)
                    ) {
                        MiniPlayer(
                            song = song,
                            isPlaying = playbackState == PlaybackState.PLAYING,
                            isBuffering = playbackState == PlaybackState.BUFFERING,
                            roundedRect = miniPlayerRoundedRect,
                            onPlayPause = { playbackViewModel.playPause() },
                            onNextClick = { playbackViewModel.next() },
                            // La carátula ya está en pantalla y viaja al player (container transform).
                            onClick = { appState.openPlayer(PlayerArtOrigin.PILL) },
                            // Como FLOWS: el tick de posición repinta el progreso sin recomponer el mini.
                            currentPositionFlow = playbackViewModel.currentPosition,
                            durationFlow = playbackViewModel.duration,
                            // Solo con origen PÍLDORA hay alguien esperando la portada al otro lado; ver
                            // el KDoc del parámetro. Desde una fila o un chip el mini se limita a
                            // desvanecerse con la suya puesta.
                            artTravelsToPlayer = morphOrigin.kind == PlayerArtOrigin.PILL,
                            sharedTransitionScope = sharedTransitionScope,
                            animatedVisibilityScope = layerScope,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = ComponentConfig.FloatingBarSideMargin)
                                // Deslizar hacia arriba abre el reproductor (gesto inverso al de cerrar).
                                .miniPlayerExpandDrag(playerGestures) {
                                    appState.openPlayer(PlayerArtOrigin.PILL)
                                }
                        )
                    }
                }
            } }

            // Sin píldora ni player: un Box vacío sin fondo → deja pasar los toques al NavHost.
            PlayerLayerState.Hidden -> Box(Modifier.fillMaxSize())
        }
    }

    // --- Hoja "añadir canciones" (estado en MusicAppState) ------------------------------------
    // Vive aquí, y no en la pantalla que la abre, porque su estado tiene que sobrevivir a la
    // navegación: la dispara un botón del detalle, pero se pinta sobre toda la app.
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

        // Mismo motivo que en la hoja de la cola: sus filas repetirían la key por-canción de las
        // filas de la lista que hay detrás.
        CompositionLocalProvider(LocalAppSharedTransitionScope provides null) {
            AddSongsToPlaylistSheet(
                playlistName = sheetPlaylistName,
                candidates = candidates,
                query = pickerQuery,
                // Con búsqueda activa y cero resultados el mensaje debe ser "ningún resultado".
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
}
