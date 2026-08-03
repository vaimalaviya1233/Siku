package com.qhana.siku.ui.navigation

import android.app.Activity
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.qhana.siku.data.model.PlaybackContext
import com.qhana.siku.ui.MusicAppState
import com.qhana.siku.ui.PlayerArtOrigin
import com.qhana.siku.ui.screens.AlbumDetailScreen
import com.qhana.siku.ui.screens.ArtistDetailScreen
import com.qhana.siku.ui.screens.DownloadManagerScreen
import com.qhana.siku.ui.screens.GenreDetailScreen
import com.qhana.siku.ui.screens.LibraryScreen
import com.qhana.siku.ui.screens.OnboardingScreen
import com.qhana.siku.ui.screens.PlaylistDetailScreen
import com.qhana.siku.ui.screens.SettingsAppearanceScreen
import com.qhana.siku.ui.screens.SettingsBackupScreen
import com.qhana.siku.ui.screens.SettingsDownloadsScreen
import com.qhana.siku.ui.screens.SettingsEqPresetsScreen
import com.qhana.siku.ui.screens.SettingsGesturesScreen
import com.qhana.siku.ui.screens.SettingsPlaybackScreen
import com.qhana.siku.ui.screens.SettingsPlayerBarScreen
import com.qhana.siku.ui.screens.SettingsScreen
import com.qhana.siku.ui.screens.SettingsSourcesScreen
import com.qhana.siku.ui.screens.SettingsTabsScreen
import com.qhana.siku.ui.theme.appNavBackEnter
import com.qhana.siku.ui.theme.appNavBackExit
import com.qhana.siku.ui.theme.appNavFadeEnter
import com.qhana.siku.ui.theme.appNavFadeExit
import com.qhana.siku.ui.theme.appNavForwardEnter
import com.qhana.siku.ui.theme.appNavForwardExit
import com.qhana.siku.ui.viewmodel.LibraryViewModel
import com.qhana.siku.ui.viewmodel.PlaybackViewModel
import com.qhana.siku.ui.viewmodel.SourcesViewModel

/**
 * Grafo de navegación de la app. Las pantallas reciben TODO por parámetro: los ViewModels
 * compartidos llegan desde la raíz (instancias de la Activity), nunca vía `hiltViewModel()`
 * dentro de una ruta — eso resolvería al scope del NavBackStackEntry y crearía una segunda
 * instancia ciega al estado global (el gotcha documentado del logout invisible).
 *
 * El estado de sesión (loggedIn/authLoading/authError) y las acciones de auth llegan como
 * valores y lambdas por la misma razón.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AppNavHost(
    appState: MusicAppState,
    startDestination: String,
    loggedIn: Boolean,
    authLoading: Boolean,
    authError: String?,
    onConnectOneDrive: (Activity) -> Unit,
    onDisconnectOneDrive: () -> Unit,
    /** Encola un scan de fuentes (KEEP: no pisa uno ya encolado por elegir carpeta local). */
    onRequestSync: () -> Unit,
    playbackViewModel: PlaybackViewModel,
    libraryViewModel: LibraryViewModel,
    sourcesViewModel: SourcesViewModel,
    sharedTransitionScope: SharedTransitionScope
) {
    val navController = appState.navController

    // Estado compartido por varias rutas (cada capa lo colecta de su ViewModel; StateFlow
    // hace que ambas vean lo mismo sin acoplarse entre sí).
    val currentSong by playbackViewModel.currentSong.collectAsStateWithLifecycle()
    val playbackState by playbackViewModel.playbackState.collectAsStateWithLifecycle()
    val libraryUiState by libraryViewModel.uiState.collectAsStateWithLifecycle()

    // Transiciones del grafo, declaradas UNA vez aquí y no ruta por ruta. Antes cada `composable`
    // repetía el mismo par `slideInHorizontally(tween(300))` / `slideOutHorizontally(tween(300))`
    // —14 veces, con la duración a mano— y solo declaraba `enterTransition` + `popExitTransition`.
    // Faltaban las OTRAS dos, así que la pantalla que quedaba detrás no se movía: no había shared
    // axis (una capa entrando sobre un fondo congelado) y el predictive back no tenía recorrido que
    // enseñar durante el gesto. Los cuatro specs salen del MotionScheme (ver ui/theme/Motion.kt).
    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = appNavForwardEnter,
        exitTransition = appNavForwardExit,
        popEnterTransition = appNavBackEnter,
        popExitTransition = appNavBackExit
    ) {
        composable(
            route = Screen.Onboarding.route,
            // Única ruta que NO usa el eje horizontal: no tiene "atrás" ni jerarquía con la
            // biblioteca (se sustituyen mutuamente según haya fuentes), así que un desplazamiento
            // le inventaría una dirección que no existe.
            enterTransition = appNavFadeEnter,
            exitTransition = appNavFadeExit,
            popEnterTransition = appNavFadeEnter,
            popExitTransition = appNavFadeExit
        ) {
            OnboardingScreen(
                isLoggedIn = loggedIn,
                authLoading = authLoading,
                authError = authError,
                onConnectOneDrive = onConnectOneDrive,
                onDisconnectOneDrive = onDisconnectOneDrive,
                onFinish = {
                    onRequestSync()
                    navController.navigate(Screen.Library.route) {
                        popUpTo(Screen.Onboarding.route) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                viewModel = sourcesViewModel
            )
        }

        // Sin overrides: la biblioteca es la raíz del eje horizontal, así que CEDE con paralaje al
        // abrir un detalle y vuelve con él. Antes se desvanecía, que es lo que rompía el eje.
        composable(route = Screen.Library.route) {
            LibraryScreen(
                isLoggedIn = loggedIn,
                onLogoutClick = onDisconnectOneDrive,
                onDownloadManagerClick = { navController.navigate(Screen.DownloadManager.route) },
                onPlaylistClick = { id, name -> navController.navigate(Screen.PlaylistDetail.createRoute(id, name)) },
                onFavoritesClick = { navController.navigate(Screen.Favorites.route) },
                onArtistClick = { name -> navController.navigate(Screen.ArtistDetail.createRoute(name)) },
                onAlbumClick = { name -> navController.navigate(Screen.AlbumDetail.createRoute(name)) },
                onGenreClick = { name -> navController.navigate(Screen.GenreDetail.createRoute(name)) },
                onNavigateToNowPlaying = { origin -> appState.openPlayer(origin) },
                onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                playbackViewModel = playbackViewModel,
                // El MISMO LibraryViewModel que reciben los detalles. Antes esta ruta se quedaba
                // con el default `hiltViewModel()` de la pantalla, que resuelve al scope del
                // NavBackStackEntry: había dos instancias vivas de la nada.
                libraryViewModel = libraryViewModel,
                // Shared elements foto/carátula → headers de los detalles.
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = this@composable
            )
        }

        composable(
            route = Screen.PlaylistDetail.route
        ) { backStackEntry ->
            val playlistId = backStackEntry.arguments
                ?.getString(Screen.PlaylistDetail.ARG_PLAYLIST_ID)
                ?.toLongOrNull() ?: return@composable
            val playlistName = Screen.PlaylistDetail.decodeName(
                backStackEntry.arguments?.getString(Screen.PlaylistDetail.ARG_PLAYLIST_NAME)
            )
            val playlistSongs by libraryViewModel.getPlaylistSongs(playlistId).collectAsStateWithLifecycle(emptyList())

            PlaylistDetailScreen(
                playlistName = playlistName,
                songs = playlistSongs,
                currentSong = currentSong,
                playbackState = playbackState,
                isFavoritesList = false,
                onBackClick = { navController.popBackStack() },
                onPlayAll = { songs, index ->
                    libraryViewModel.recordContext(
                        PlaybackContext.Playlist(playlistId, playlistName, songs.firstOrNull()?.albumArtUri?.toString())
                    )
                    playbackViewModel.playSongs(songs, index)
                    appState.openPlayer(PlayerArtOrigin.ROW)
                },
                onShufflePlay = { songs ->
                    libraryViewModel.recordContext(
                        PlaybackContext.Playlist(playlistId, playlistName, songs.firstOrNull()?.albumArtUri?.toString())
                    )
                    playbackViewModel.shufflePlay(songs)
                    appState.openPlayer()
                },
                onToggleFavorite = { libraryViewModel.toggleFavorite(it) },
                onReorderSongs = { songIds -> libraryViewModel.reorderPlaylistSongs(playlistId, songIds) },
                onRemoveSong = { songId -> libraryViewModel.removeSongFromPlaylist(playlistId, songId) },
                onAddSongs = { appState.showAddSongsSheet = true }
            )
        }

        composable(
            route = Screen.ArtistDetail.route
        ) { backStackEntry ->
            val artistName = Screen.ArtistDetail.decodeName(
                backStackEntry.arguments?.getString(Screen.ArtistDetail.ARG_ARTIST_NAME)
            )
            ArtistDetailScreen(
                artistName = artistName,
                currentSong = currentSong,
                playbackState = playbackState,
                favorites = libraryUiState.favorites,
                playlists = libraryUiState.playlists,
                onBackClick = { navController.popBackStack() },
                onAlbumClick = { album -> appState.navigateToAlbum(album) },
                onPlayAll = { songs, index ->
                    libraryViewModel.recordContext(
                        PlaybackContext.Artist(artistName, songs.firstOrNull()?.albumArtUri?.toString())
                    )
                    playbackViewModel.playSongs(songs, index)
                    appState.openPlayer(PlayerArtOrigin.ROW)
                },
                onShufflePlay = { songs ->
                    libraryViewModel.recordContext(
                        PlaybackContext.Artist(artistName, songs.firstOrNull()?.albumArtUri?.toString())
                    )
                    playbackViewModel.shufflePlay(songs)
                    appState.openPlayer()
                },
                onToggleFavorite = { libraryViewModel.toggleFavorite(it) },
                onAddSongToPlaylist = { playlistId, songId -> libraryViewModel.addSongToPlaylist(playlistId, songId) },
                onCreatePlaylist = { name, pendingSongId ->
                    libraryViewModel.createPlaylist(name) { id ->
                        pendingSongId?.let { libraryViewModel.addSongToPlaylist(id, it) }
                    }
                },
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = this@composable
            )
        }

        composable(
            route = Screen.AlbumDetail.route
        ) { backStackEntry ->
            val albumName = Screen.AlbumDetail.decodeName(
                backStackEntry.arguments?.getString(Screen.AlbumDetail.ARG_ALBUM_NAME)
            )
            AlbumDetailScreen(
                albumName = albumName,
                currentSong = currentSong,
                playbackState = playbackState,
                favorites = libraryUiState.favorites,
                playlists = libraryUiState.playlists,
                onBackClick = { navController.popBackStack() },
                onArtistClick = { artist -> appState.navigateToArtist(artist) },
                onPlayAll = { songs, index ->
                    libraryViewModel.recordContext(
                        PlaybackContext.Album(albumName, songs.firstOrNull()?.albumArtUri?.toString())
                    )
                    playbackViewModel.playSongs(songs, index)
                    appState.openPlayer(PlayerArtOrigin.ROW)
                },
                onShufflePlay = { songs ->
                    libraryViewModel.recordContext(
                        PlaybackContext.Album(albumName, songs.firstOrNull()?.albumArtUri?.toString())
                    )
                    playbackViewModel.shufflePlay(songs)
                    appState.openPlayer()
                },
                onToggleFavorite = { libraryViewModel.toggleFavorite(it) },
                onAddSongToPlaylist = { playlistId, songId -> libraryViewModel.addSongToPlaylist(playlistId, songId) },
                onCreatePlaylist = { name, pendingSongId ->
                    libraryViewModel.createPlaylist(name) { id ->
                        pendingSongId?.let { libraryViewModel.addSongToPlaylist(id, it) }
                    }
                },
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = this@composable
            )
        }

        composable(
            route = Screen.GenreDetail.route
        ) { backStackEntry ->
            val genreName = Screen.GenreDetail.decodeName(
                backStackEntry.arguments?.getString(Screen.GenreDetail.ARG_GENRE_NAME)
            )
            GenreDetailScreen(
                genreName = genreName,
                currentSong = currentSong,
                playbackState = playbackState,
                favorites = libraryUiState.favorites,
                playlists = libraryUiState.playlists,
                onBackClick = { navController.popBackStack() },
                onPlayAll = { songs, index ->
                    libraryViewModel.recordContext(
                        PlaybackContext.Genre(genreName, songs.firstOrNull()?.albumArtUri?.toString())
                    )
                    playbackViewModel.playSongs(songs, index)
                    appState.openPlayer(PlayerArtOrigin.ROW)
                },
                onShufflePlay = { songs ->
                    libraryViewModel.recordContext(
                        PlaybackContext.Genre(genreName, songs.firstOrNull()?.albumArtUri?.toString())
                    )
                    playbackViewModel.shufflePlay(songs)
                    appState.openPlayer()
                },
                onToggleFavorite = { libraryViewModel.toggleFavorite(it) },
                onAddSongToPlaylist = { playlistId, songId -> libraryViewModel.addSongToPlaylist(playlistId, songId) },
                onCreatePlaylist = { name, pendingSongId ->
                    libraryViewModel.createPlaylist(name) { id ->
                        pendingSongId?.let { libraryViewModel.addSongToPlaylist(id, it) }
                    }
                },
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = this@composable
            )
        }

        composable(
            route = Screen.Favorites.route
        ) {
            PlaylistDetailScreen(
                playlistName = "Favoritos",
                songs = libraryUiState.favoriteSongs,
                currentSong = currentSong,
                playbackState = playbackState,
                isFavoritesList = true,
                onBackClick = { navController.popBackStack() },
                onPlayAll = { songs, index ->
                    libraryViewModel.recordContext(PlaybackContext.Favorites)
                    playbackViewModel.playSongs(songs, index)
                    appState.openPlayer(PlayerArtOrigin.ROW)
                },
                onShufflePlay = { songs ->
                    libraryViewModel.recordContext(PlaybackContext.Favorites)
                    playbackViewModel.shufflePlay(songs)
                    appState.openPlayer()
                },
                onToggleFavorite = { libraryViewModel.toggleFavorite(it) },
                onAddSongs = { appState.showAddSongsSheet = true }
            )
        }

        composable(
            route = Screen.Settings.route
        ) {
            // Hub de categorías: cada una navega a su propia sub-pantalla.
            SettingsScreen(
                onBackClick = { navController.popBackStack() },
                isLoggedIn = loggedIn,
                onNavigate = { route -> appState.navigate(route) },
                sourcesViewModel = sourcesViewModel
            )
        }

        composable(
            route = Screen.SettingsSources.route
        ) {
            SettingsSourcesScreen(
                onBackClick = { navController.popBackStack() },
                isLoggedIn = loggedIn,
                authLoading = authLoading,
                onConnectOneDrive = onConnectOneDrive,
                onDisconnectOneDrive = onDisconnectOneDrive,
                sourcesViewModel = sourcesViewModel
            )
        }

        composable(
            route = Screen.SettingsBackup.route
        ) {
            SettingsBackupScreen(
                onBackClick = { navController.popBackStack() },
                isLoggedIn = loggedIn
            )
        }

        composable(
            route = Screen.SettingsPlayback.route
        ) {
            SettingsPlaybackScreen(
                onBackClick = { navController.popBackStack() },
                onNavigate = { route -> appState.navigate(route) }
            )
        }

        composable(
            route = Screen.SettingsEqPresets.route
        ) {
            SettingsEqPresetsScreen(onBackClick = { navController.popBackStack() })
        }

        composable(
            route = Screen.SettingsDownloads.route
        ) {
            SettingsDownloadsScreen(
                onBackClick = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.SettingsAppearance.route
        ) {
            SettingsAppearanceScreen(
                onBackClick = { navController.popBackStack() },
                onNavigate = { route -> appState.navigate(route) }
            )
        }

        composable(
            route = Screen.SettingsGestures.route
        ) {
            SettingsGesturesScreen(onBackClick = { navController.popBackStack() })
        }

        composable(
            route = Screen.SettingsTabs.route
        ) {
            SettingsTabsScreen(onBackClick = { navController.popBackStack() })
        }

        composable(
            route = Screen.SettingsPlayerBar.route
        ) {
            SettingsPlayerBarScreen(onBackClick = { navController.popBackStack() })
        }

        composable(Screen.DownloadManager.route) {
            DownloadManagerScreen(onBackClick = { navController.popBackStack() })
        }
    }
}
