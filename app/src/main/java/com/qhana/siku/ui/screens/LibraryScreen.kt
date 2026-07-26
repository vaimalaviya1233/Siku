package com.qhana.siku.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.*
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.qhana.siku.R
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qhana.siku.data.local.AlbumSummary
import com.qhana.siku.data.local.ArtistSummary
import com.qhana.siku.data.model.LibraryTabId
import com.qhana.siku.data.model.LibraryTabsConfig
import com.qhana.siku.data.model.PlaybackContext
import com.qhana.siku.data.model.Song
import com.qhana.siku.data.model.SongFilter
import com.qhana.siku.ui.components.*
import com.qhana.siku.ui.viewmodel.BrowseViewModel
import com.qhana.siku.ui.viewmodel.LibraryBannerState
import com.qhana.siku.ui.viewmodel.LibraryViewModel
import com.qhana.siku.ui.viewmodel.PlaybackViewModel
import com.qhana.siku.ui.viewmodel.SyncViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Immutable
private data class TabInfo(
    val tab: LibraryTabId,
    val titleRes: Int,
    val iconName: String
)

/**
 * Movimiento del search view expandido, SIN rebote. Los defaults de
 * [rememberContainedSearchBarState] son `MotionSchemeKeyTokens.FastSpatial`, que con
 * `MotionScheme.expressive()` resuelve a `dampingRatio = 0.6f` — muy subamortiguado, y la píldora
 * oscilaba al abrir y al cerrar. Se conserva la rigidez del token (800) para no alterar la
 * velocidad percibida; solo se lleva el damping a crítico.
 */
private val SearchViewMotionSpec = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = 800f
)

/**
 * Escala de partida/llegada del cruce lupa↔flecha del leading icon. El glifo no nace en 0:
 * se encoge lo justo para que el cambio se lea como un relevo y no como un icono que aparece
 * de la nada. Simétrico entrada/salida a propósito.
 */
private const val IconCrossfadeScale = 0.7f

/**
 * Presentación (etiqueta + glifo) de cada pestaña. El ORDEN y la VISIBILIDAD ya no viven aquí:
 * los decide el usuario en Ajustes → Apariencia → Pestañas y llegan como
 * [com.qhana.siku.data.model.LibraryTabState]. Este mapa solo traduce id → cómo se dibuja.
 */
private val tabInfo: Map<LibraryTabId, TabInfo> = listOf(
    TabInfo(LibraryTabId.HOME, R.string.tab_home, "home"),
    TabInfo(LibraryTabId.SONGS, R.string.tab_all, "library_music"),
    TabInfo(LibraryTabId.ARTISTS, R.string.common_artists, "artist"),
    TabInfo(LibraryTabId.ALBUMS, R.string.common_albums, "album"),
    TabInfo(LibraryTabId.GENRES, R.string.common_genres, "genres"),
    TabInfo(LibraryTabId.PLAYLISTS, R.string.tab_playlists, "playlist_play")
).associateBy { it.tab }

// ExperimentalMaterial3ExpressiveApi: la variante contained del search view expandido.
@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalSharedTransitionApi::class
)
@Composable
fun LibraryScreen(
    isLoggedIn: Boolean,
    onLogoutClick: () -> Unit,
    onDownloadManagerClick: () -> Unit,
    onPlaylistClick: (Long, String) -> Unit,
    onFavoritesClick: () -> Unit,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    onGenreClick: (String) -> Unit,
    onNavigateToNowPlaying: () -> Unit,
    onNavigateToSettings: () -> Unit,
    // Scopes para los shared elements foto/carátula → header del detalle (artista/álbum).
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    modifier: Modifier = Modifier,
    // Inyección de ViewModels.
    //
    // `libraryViewModel` SIN default a propósito: tiene que llegar el de la Activity. Con
    // `hiltViewModel()` aquí, la resolución cae en el ViewModelStore del `NavBackStackEntry` de
    // esta ruta y nace una SEGUNDA instancia, distinta de la que `AppNavHost` pasa a los detalles
    // de lista/artista/álbum. Eran dos objetos con sus propios colectores de `syncManager.state` y
    // sus propios flujos de paging sobre la misma base de datos, y cualquier estado que no viva en
    // DataStore o en un singleton (favoritos en memoria, banner, query) se veía distinto según la
    // pantalla. Quitar el default es lo que impide que se cuele otra vez sin darse cuenta.
    libraryViewModel: LibraryViewModel,
    // El banner de progreso de sync lo maneja LibraryViewModel; syncViewModel se usa para el
    // banner PERSISTENTE de descargas pausadas/detenidas (lee flows de singletons).
    syncViewModel: SyncViewModel = hiltViewModel(),
    playbackViewModel: PlaybackViewModel = hiltViewModel(),
    browseViewModel: BrowseViewModel = hiltViewModel()
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Data Collection from ViewModels
    val uiState by libraryViewModel.uiState.collectAsStateWithLifecycle()
    val songCount by libraryViewModel.songCount.collectAsStateWithLifecycle()
    val artists by browseViewModel.artists.collectAsStateWithLifecycle()
    val albums by browseViewModel.albums.collectAsStateWithLifecycle()
    // Visibilidad de los chips de origen (compartida por Todas/Artistas/Álbumes): Local solo
    // si hay AMBAS familias; Descargadas/Nube con que haya nube. Misma regla que SongsScreen.
    val hasLocalSongs by libraryViewModel.hasLocalSongs.collectAsStateWithLifecycle()
    val hasCloudSongs by libraryViewModel.hasCloudSongs.collectAsStateWithLifecycle()
    val showLocalSourceChip = hasLocalSongs && hasCloudSongs
    val showCloudSourceChips = hasCloudSongs

    // Secciones de la pestaña Inicio (reactivas al historial).
    val homeMostPlayed by libraryViewModel.homeMostPlayed.collectAsStateWithLifecycle()
    val homeRecentlyAdded by libraryViewModel.homeRecentlyAdded.collectAsStateWithLifecycle()
    val topAlbums by browseViewModel.topAlbums.collectAsStateWithLifecycle()
    val recentContexts by libraryViewModel.recentContexts.collectAsStateWithLifecycle()
    val homeStats by libraryViewModel.homeStats.collectAsStateWithLifecycle()
    val homeArtistPick by libraryViewModel.homeArtistPick.collectAsStateWithLifecycle()
    val homeRediscover by libraryViewModel.homeRediscover.collectAsStateWithLifecycle()
    val homeTopGenres by libraryViewModel.homeTopGenres.collectAsStateWithLifecycle()

    // Resultados de búsqueda SECCIONADOS: artistas y álbumes que matchean la query se filtran
    // aquí en memoria (las listas ya viven cargadas para las tabs); las canciones las sigue
    // filtrando el paging LIKE. Solo con búsqueda activa en la pestaña Todas.
    val searchQuery = uiState.searchQuery
    val isSearchActive = uiState.showSearch && searchQuery.isNotBlank()
    val searchArtists = remember(artists, searchQuery, isSearchActive) {
        if (!isSearchActive) emptyList()
        else artists.filter { it.name.contains(searchQuery.trim(), ignoreCase = true) }.take(12)
    }
    val searchAlbums = remember(albums, searchQuery, isSearchActive) {
        if (!isSearchActive) emptyList()
        else albums.filter { it.name.contains(searchQuery.trim(), ignoreCase = true) }.take(12)
    }
    
    // El MiniPlayer vive ahora en MainActivity; acá solo se necesita el estado para el
    // acento del álbum en reproducción.
    val nowPlayingUiState by playbackViewModel.nowPlayingUiState.collectAsStateWithLifecycle()

    // Acento del álbum en reproducción, calculado una sola vez aquí (LibraryScreen está
    // siempre vivo) para que no parpadee al cambiar de tab. null = fallback al sistema.
    val isDarkThemeLib = isSystemInDarkTheme()
    val playingAccent = remember(nowPlayingUiState.albumColors, isDarkThemeLib) {
        nowPlayingUiState.albumColors?.let { c -> Color(if (isDarkThemeLib) c.secondary else c.primary) }
    }

    // Dialog State
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var songIdForPlaylist by remember { mutableStateOf<String?>(null) }
    // Canción retenida cuando el diálogo de crear lista se abre DESDE la hoja "agregar a
    // lista": la lista nueva nace con ella dentro. null = diálogo abierto desde la pestaña
    // Listas → al crear se navega al detalle (ahí vive "añadir canciones").
    var pendingSongForNewPlaylist by remember { mutableStateOf<String?>(null) }
    var showLogoutDialog by remember { mutableStateOf(false) }

    // --- PAGER (antes de la búsqueda: el trailing de la píldora depende de currentTab) ---
    // Las pestañas visibles, en el orden del usuario (Ajustes → Apariencia → Pestañas). El
    // fallback al DEFAULT no es defensa de más: `tabInfo` podría no cubrir un id de una config
    // guardada por una versión futura, y esta lista NUNCA puede quedar vacía (el pager exige
    // pageCount ≥ 1 y sin pestañas no habría forma de volver).
    val tabsConfig by libraryViewModel.libraryTabs.collectAsStateWithLifecycle()
    val tabs = remember(tabsConfig) {
        tabsConfig.filter { it.visible }.mapNotNull { tabInfo[it.tab] }
            .ifEmpty { LibraryTabsConfig.DEFAULT.mapNotNull { tabInfo[it.tab] } }
    }
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val currentTab = tabs.getOrNull(pagerState.currentPage) ?: tabs[0]

    // Ocultar la pestaña activa (o reordenar) deja al pager apuntando a una página que ya no
    // existe o que ahora es otra: se lleva el foco a un índice válido en cuanto cambia la config.
    LaunchedEffect(tabs.size) {
        if (pagerState.currentPage >= tabs.size) pagerState.scrollToPage(tabs.lastIndex)
    }

    // --- SCROLL & APPBAR ---
    // Va ANTES de la búsqueda: el color de la píldora depende del estado de scroll (ver
    // headerItemColor).
    // TopBar SIEMPRE visible (pinned): se quitó el enterAlways que la ocultaba al deslizar
    // en "Todas" (y con él la lógica de auto-mostrar al parar el scroll).
    val topAppBarState = rememberTopAppBarState()
    val currentScrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(state = topAppBarState)

    // Separación header/contenido: on-scroll color change del spec (surface at-rest →
    // surfaceContainer con contenido debajo), HOISTED porque el bloque tintado es
    // TopBar + fila de tabs y deben virar en sincronía exacta — la misma lógica interna de
    // SingleRowTopAppBar (umbral binario sobre overlappedFraction + spring DefaultEffects)
    // aplicada al color compartido. surface NO es negro puro: los tres schemes producen
    // tone 6 / tone 98, así que at-rest el header se funde con el lienzo (intencional).
    // CLAVE: el margen bajo las tabs vive DENTRO del área teñida (padding inferior de
    // LibraryTabsRow), para que la píldora activa respire; sin ese aire quedaba pegada al
    // corte del bloque. El divider se probó y NO.
    // contentOffset directo, NO overlappedFraction: sin un TopAppBar componiéndose nadie
    // fija heightOffsetLimit y la fracción no se computa. El pinned connection acumula
    // contentOffset igual (y lo resetea al volver al tope), que es la única señal que
    // necesita el umbral binario.
    val headerScrolled by remember {
        derivedStateOf { topAppBarState.contentOffset < -1f }
    }
    // Reposo = surface (spec: el header se funde con el fondo hasta que hay contenido
    // debajo), scrolled = surfaceContainer. El "hueco raro" que se veía en reposo NO era
    // el color: era el canal de 12dp entre el bloque de tabs y el contenido (hoy 4dp) —
    // se probó surfaceContainerLow como paliativo y con el gap corregido sobraba.
    val headerColor by animateColorAsState(
        targetValue = if (headerScrolled) colorScheme.surfaceContainer else colorScheme.surface,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "headerColor"
    )
    // Color del contenedor de la píldora de búsqueda, que va SOBRE el bloque. Sube un peldaño con
    // el bloque para conservar la MISMA distancia tonal en los dos estados. Fijo en
    // surfaceContainerHigh (el default del componente) se leía bien en reposo —contra surface hay
    // ~11 puntos de tono— pero al scrollear el bloque pasa a surfaceContainer y la distancia cae a
    // ~5: la píldora se fundía con el fondo. Las tabs ya no lo usan (no tienen contenedor propio).
    val headerItemColor by animateColorAsState(
        targetValue = if (headerScrolled) colorScheme.surfaceContainerHighest
                      else colorScheme.surfaceContainerHigh,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "headerItemColor"
    )
    // contentOffset es un acumulador global del nested scroll: al cambiar de página no se
    // resetea solo, y una pestaña abierta arriba del todo heredaría el tinte del scroll de
    // la anterior. Trade-off aceptado: una pestaña que quedó scrolleada nace des-tintada
    // hasta el primer scroll (el fix perfecto exigiría izar los scroll states de las 5 tabs).
    LaunchedEffect(pagerState.currentPage) { topAppBarState.contentOffset = 0f }

    // --- BÚSQUEDA (search as secondary action / focused search) ---
    // Componente REAL de M3, variante CONTAINED: la lupa de la TopBar es el ancla colapsada y los
    // resultados viven en ExpandedFullScreenContainedSearchBar (diálogo edge-to-edge, con
    // predictive back nativo). El TextField manual que había en el slot topBar se borró
    // (ui/components/SearchBar.kt).
    //
    // Contained, NO la variante con divisor (`ExpandedFullScreenSearchBar`): aquella pega el input
    // al borde y dibuja un `HorizontalDivider` bajo él, y su superficie crece geométricamente. La
    // contained deja el input como píldora con márgenes, la superficie entra por alpha
    // (`layerBlock { alpha = state.progress }`) y el contenido tiene su propio fade
    // (`contentProgress`). Es el estilo expressive — el "bouncy" del search view contained.
    val searchBarState = rememberContainedSearchBarState(
        animationSpecForExpand = SearchViewMotionSpec,
        animationSpecForCollapse = SearchViewMotionSpec
    )
    // El contenedor del input sale del color animado del header (mismo criterio que los botones
    // inactivos de las tabs), NO del token fijo `SearchBarTokens.ContainerColor`
    // (surfaceContainerHigh): con la lista scrolleada ese token queda a un peldaño del bloque.
    // El resto de la paleta (superficie del diálogo expandido incluida) se conserva.
    val searchBarColors = SearchBarDefaults.containedColors(searchBarState).let { base ->
        base.copy(
            inputFieldColors = base.inputFieldColors.copy(
                focusedContainerColor = headerItemColor,
                unfocusedContainerColor = headerItemColor
            )
        )
    }
    val searchTextFieldState = rememberTextFieldState()
    val keyboardController = LocalSoftwareKeyboardController.current

    // La query sigue siendo dueña del LibraryViewModel (la consume el paging LIKE): el
    // TextFieldState del componente es solo el input, así que se replica hacia el VM.
    LaunchedEffect(Unit) {
        snapshotFlow { searchTextFieldState.text.toString() }
            .collect { libraryViewModel.onSearchQueryChanged(it) }
    }
    // targetValue (no currentValue): al colapsar limpiamos apenas arranca la animación,
    // no al terminarla, para que la lista de atrás ya esté sin filtrar cuando se destapa.
    LaunchedEffect(searchBarState.targetValue) {
        val expanded = searchBarState.targetValue == SearchBarValue.Expanded
        libraryViewModel.onShowSearchChanged(expanded)
        if (!expanded) searchTextFieldState.clearText()
    }

    // El mismo inputField se pasa al contenedor expandido; el componente lo mueve durante el
    // morph. El foco inicial lo pide el propio diálogo (ya no hace falta un FocusRequester).
    val backDesc = stringResource(R.string.common_back)
    val clearDesc = stringResource(R.string.common_clear_search)
    // Mitad de la expansión: antes de eso el leading sigue siendo la lupa (continuidad con el
    // icono del que nace el diálogo), después es "volver".
    val showBackIcon by remember { derivedStateOf { searchBarState.progress > 0.5f } }
    val searchInputField: @Composable () -> Unit = {
        SearchBarDefaults.InputField(
            textFieldState = searchTextFieldState,
            searchBarState = searchBarState,
            // Píldora tonal sobre la superficie del diálogo (el input NO comparte color con el
            // contenedor en la variante contained).
            colors = searchBarColors.inputFieldColors,
            // Los resultados se filtran en vivo: "buscar" en el IME solo baja el teclado.
            onSearch = { keyboardController?.hide() },
            placeholder = {
                // Una línea con elipsis: el texto es largo y en pantallas angostas, sin esto,
                // el decorator lo recorta a mitad de letra en vez de puntearlo.
                Text(
                    text = stringResource(R.string.common_search_songs),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            leadingIcon = {
                // El icono arranca siendo la MISMA lupa del ancla y se cruza a "volver" pasada
                // la mitad de la expansión. Con `currentValue` no sirve: ese flag salta a
                // Expanded en cuanto progress > 0.02, o sea que la lupa se volvía flecha antes
                // de que la barra creciera — el corte que se veía. derivedStateOf recorta la
                // recomposición a un único cambio, no a un frame por tick del spring.
                IconButton(
                    onClick = { scope.launch { searchBarState.animateToCollapsed() } },
                    modifier = Modifier.semantics { contentDescription = backDesc }
                ) {
                    // Izados: `transitionSpec` NO es un lambda composable, así que los tokens
                    // del MotionScheme (que sí son lectura composable) se resuelven aquí.
                    val iconEnterFade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
                    val iconEnterScale = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
                    val iconExitFade = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
                    val iconExitScale = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
                    AnimatedContent(
                        targetState = showBackIcon,
                        // Mismos tokens de motion que el resto de la cabecera: el que entra con
                        // el ritmo "default", el que sale con el "fast".
                        transitionSpec = {
                            (fadeIn(iconEnterFade) +
                                scaleIn(iconEnterScale, initialScale = IconCrossfadeScale))
                                .togetherWith(
                                    fadeOut(iconExitFade) +
                                        scaleOut(iconExitScale, targetScale = IconCrossfadeScale)
                                )
                        },
                        label = "searchLeadingIcon"
                    ) { back ->
                        MaterialSymbol(if (back) "arrow_back" else "search")
                    }
                }
            },
            trailingIcon = {
                when {
                    searchTextFieldState.text.isNotEmpty() -> {
                        IconButton(
                            onClick = { searchTextFieldState.clearText() },
                            modifier = Modifier.semantics { contentDescription = clearDesc }
                        ) { MaterialSymbol("close") }
                    }
                    // Docked (y hasta la mitad del morph, espejo del leading): el overflow vive
                    // DENTRO de la píldora como trailing icon del spec de search bar. El ORDEN
                    // ya no vive aquí: pasó a un chip en la fila sobre la lista (consistente con
                    // Artistas/Álbumes).
                    !showBackIcon -> Row(verticalAlignment = Alignment.CenterVertically) {
                        LibraryOverflowButton(
                            isLoggedIn = isLoggedIn,
                            onSettingsClick = onNavigateToSettings,
                            onLogoutClick = { showLogoutDialog = true }
                        )
                    }
                }
            }
        )
    }

    // Host ÚNICO de la app (MainActivity). Esta pantalla ya no crea el suyo: todo el feedback
    // sale del bus SnackbarManager, que alimenta ese host.
    val snackbarHostState = LocalSnackbarHostState.current

    // --- BANNER LOGIC ---
    // Banner PERSISTENTE de descargas pausadas/detenidas (mismo diseño que el Download Manager).
    // Solo se muestra cuando NO hay un banner de sync en curso (idle), en el mismo slot.
    val downloadBanner by syncViewModel.downloadBanner.collectAsStateWithLifecycle()
    val showBanner = uiState.bannerState !is LibraryBannerState.Hidden || downloadBanner != null
    // El indicador del pull-to-refresh es estado del ViewModel: se enciende solo si el scan
    // realmente va a correr (red/batería verificadas) y se apaga cuando el sync publica
    // estado — sin "safety timeout" arbitrario.
    val isManualRefreshing by libraryViewModel.isManualRefreshing.collectAsStateWithLifecycle()

    // El back de la búsqueda lo maneja el diálogo de ExpandedFullScreenSearchBar (predictive
    // back incluido): no hace falta BackHandler propio.

    // Decisión de duplicados entre fuentes: el sync la detecta (StateFlow del SyncManager,
    // sobrevive a navegación) y aquí se pregunta — el home es a donde se aterriza tras
    // conectar una fuente en onboarding o Ajustes.
    val duplicateCount by syncViewModel.duplicateDecisionNeeded.collectAsStateWithLifecycle()
    duplicateCount?.let { count ->
        DuplicatePolicyDialog(
            count = count,
            onResolve = syncViewModel::resolveDuplicates,
            onDismiss = syncViewModel::dismissDuplicates
        )
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(currentScrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0,0,0,0),
        containerColor = colorScheme.surface,
        // Sin snackbarHost: el host único vive en MainActivity, ya posicionado sobre el
        // MiniPlayer flotante. Tener otro acá duplicaba el componente y solo mostraba los
        // snackbars pedidos a mano desde esta pantalla, no los del bus.
        topBar = {
            // Search bar DOCKED como cabecera (sin título: la píldora activa de las tabs ya
            // dice dónde estás — el título era redundante). El componente registra solo sus
            // collapsedCoords como ancla del morph; adiós al hack del Box de 56dp sobre la lupa.
            // Único header posible: al no haber selección múltiple, el AnimatedContent que
            // alternaba con la barra de selección quedó sin segundo estado.
            LibrarySearchHeader(
                searchBarState = searchBarState,
                searchInputField = searchInputField,
                searchBarColors = searchBarColors,
                containerColor = headerColor
            )

            // Overlay de búsqueda. No ocupa alto en el slot topBar: internamente es un Dialog
            // que sólo se compone con el estado expandido.
            ExpandedFullScreenContainedSearchBar(
                state = searchBarState,
                inputField = searchInputField,
                colors = searchBarColors
                // collapsedShape por defecto (píldora): el ancla ya ES la píldora docked,
                // el morph nace de su forma real (antes era CircleShape por la lupa).
            ) {
                // Cierre UNIFORME: el back del sistema debe animar igual que el icono arrow_back
                // (morph directo a la lupa), no con el encogimiento del predictive back del diálogo.
                // Este BackHandler se compone DENTRO del content, después del PredictiveBackStateHandler
                // de BasicEdgeToEdgeDialog, así que gana por prioridad LIFO del OnBackPressedDispatcher:
                // consume el back y llama al mismo animateToCollapsed(), sin que el gesto muestre preview.
                // Es el patrón que el propio componente usa en su variante docked.
                BackHandler { scope.launch { searchBarState.animateToCollapsed() } }
                Box(modifier = Modifier.fillMaxSize()) {
                    SearchResults(
                        searchQuery = searchQuery,
                        searchArtists = searchArtists,
                        searchAlbums = searchAlbums,
                        libraryViewModel = libraryViewModel,
                        playbackViewModel = playbackViewModel,
                        playingAccent = playingAccent,
                        onAddToPlaylistRequest = { songIdForPlaylist = it },
                        onCollapseAnd = { action -> scope.launch { searchBarState.animateToCollapsed(); action() } },
                        onNavigateToNowPlaying = onNavigateToNowPlaying,
                        onArtistClick = onArtistClick,
                        onAlbumClick = onAlbumClick
                    )
                    // Segundo host sobre el MISMO estado que el del root: el diálogo es otra
                    // ventana y tapa aquél. Acá no hay MiniPlayer flotante, así que el snackbar
                    // solo esquiva la navbar.
                    SnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(bottom = 16.dp)
                    )
                }
            }
        },
    ) { paddingValues ->
        // El MiniPlayer vive en MainActivity y FLOTA sobre el final de la
        // lista; arriba, espejo: el CONTENIDO PASA POR DEBAJO del header (TopBar + tabs,
        // opacos con tinte on-scroll). El pager ocupa TODA la altura y cada lista reserva
        // el alto del header como contentPadding superior — así el borde del bloque tonal
        // es exactamente donde el contenido desaparece y el header no se lee como un
        // "cuadro" apilado sobre la lista.
        val topBarInset = paddingValues.calculateTopPadding()
        var bannerHeightPx by remember { mutableIntStateOf(0) }
        val bannerHeight = with(LocalDensity.current) { bannerHeightPx.toDp() }
        val listInsets = PaddingValues(
            // 4dp tras el bloque de tabs: el aire principal ya vive DENTRO del área teñida
            // (bottom interno de LibraryTabsRow); 12dp externos dejaban un canal vacío
            // entre el corte del bloque y el contenido.
            top = topBarInset + TabsRowHeight + bannerHeight + 4.dp,
            bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + ComponentConfig.FloatingBarListInset
        )
        Box(modifier = Modifier.fillMaxSize()) {
            val pullState = rememberPullToRefreshState()
            PullToRefreshBox(
                isRefreshing = isManualRefreshing,
                onRefresh = { libraryViewModel.onRefresh() },
                state = pullState,
                modifier = Modifier.fillMaxSize(),
                indicator = {
                    // El contenido arranca en y=0 (detrás del header): bajar el spinner
                    // para que asome bajo las tabs y no quede oculto tras la TopBar. Se suma
                    // el alto del banner para que, cuando hay banner (p.ej. "Descargando"),
                    // el spinner aparezca DEBAJO de él y no tapado por la tarjeta.
                    PullToRefreshDefaults.Indicator(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = topBarInset + TabsRowHeight + bannerHeight),
                        isRefreshing = isManualRefreshing,
                        state = pullState
                    )
                }
            ) {
                HorizontalPager(state = pagerState, key = { it }, beyondViewportPageCount = 1) { page ->
                    val tab = tabs[page]
                    when (tab.tab) {
                        LibraryTabId.HOME -> {
                            HomeScreen(
                                mostPlayed = homeMostPlayed,
                                recentlyAdded = homeRecentlyAdded,
                                topAlbums = topAlbums,
                                recentContexts = recentContexts,
                                stats = homeStats,
                                artistPick = homeArtistPick,
                                rediscover = homeRediscover,
                                currentSongId = nowPlayingUiState.song?.id,
                                contentPadding = listInsets,
                                onPlaySongs = { songs, index ->
                                    playbackViewModel.playSongs(songs, index)
                                    onNavigateToNowPlaying()
                                },
                                onAlbumClick = onAlbumClick,
                                onResumeContext = { ctx -> resumeContext(
                                    ctx, scope, browseViewModel, libraryViewModel, playbackViewModel,
                                    uiState.favoriteSongs, onNavigateToNowPlaying
                                ) },
                                canPlayAll = songCount > 0,
                                onShuffleAll = {
                                    playbackViewModel.shuffleAllFromLibrary()
                                    onNavigateToNowPlaying()
                                },
                                onPlayAll = {
                                    playbackViewModel.playAllFromLibrary()
                                    onNavigateToNowPlaying()
                                },
                                hasFavorites = uiState.favoriteSongs.isNotEmpty(),
                                onShuffleFavorites = {
                                    playbackViewModel.shufflePlay(uiState.favoriteSongs)
                                    onNavigateToNowPlaying()
                                },
                                genres = homeTopGenres,
                                onShuffleGenre = { genre ->
                                    scope.launch {
                                        val songs = libraryViewModel.getSongsByGenre(genre)
                                        if (songs.isNotEmpty()) {
                                            playbackViewModel.shufflePlay(songs)
                                            onNavigateToNowPlaying()
                                        }
                                    }
                                },
                                sharedTransitionScope = sharedTransitionScope,
                                animatedVisibilityScope = animatedVisibilityScope
                            )
                        }
                        LibraryTabId.SONGS -> {
                            // Lista normal: la búsqueda ya no vive acá, sino en el overlay
                            // (ExpandedFullScreenSearchBar), que tapa la pantalla entera.
                            SongsScreen(
                                currentFilter = SongFilter.ALL,
                                contentPadding = listInsets,
                                onNavigateToNowPlaying = onNavigateToNowPlaying,
                                onAddToPlaylistRequest = { songIdForPlaylist = it },
                                viewModel = libraryViewModel,
                                playbackViewModel = playbackViewModel,
                                playingAccent = playingAccent,
                                songCount = songCount,
                                sortOrder = uiState.sortOrderAll,
                                onSortOrderChange = { libraryViewModel.onSortOrderChanged(it, SongFilter.ALL) },
                                onToggleSourceFilter = libraryViewModel::toggleSourceFilter
                            )
                        }
                        LibraryTabId.ARTISTS -> {
                            val artistPhotosPaused by browseViewModel.artistPhotosPausedOnMobile
                                .collectAsStateWithLifecycle()
                            val artistSortOrder by browseViewModel.artistSortOrder
                                .collectAsStateWithLifecycle()
                            val artistSourceFilters by browseViewModel.artistSourceFilters
                                .collectAsStateWithLifecycle()
                            ArtistsScreen(
                                artists = artists,
                                onArtistClick = onArtistClick,
                                sortOrder = artistSortOrder,
                                onSortOrderChange = browseViewModel::setArtistSortOrder,
                                sourceFilters = artistSourceFilters,
                                onToggleSourceFilter = browseViewModel::toggleArtistSourceFilter,
                                showLocalChip = showLocalSourceChip,
                                showCloudChips = showCloudSourceChips,
                                meteredBannerVisible = artistPhotosPaused,
                                onDownloadOnMobile = browseViewModel::downloadArtistPhotosOnMobile,
                                onDismissMeteredBanner = browseViewModel::dismissArtistPhotosBanner,
                                // Quick-play: reproduce todo el artista sin navegar (el
                                // MiniPlayer aparece como feedback), como en Álbumes.
                                onPlayArtist = { name ->
                                    scope.launch {
                                        val artistSongs = browseViewModel.getArtistSongs(name).first()
                                        if (artistSongs.isNotEmpty()) {
                                            libraryViewModel.recordContext(
                                                PlaybackContext.Artist(name, artistSongs.firstOrNull()?.albumArtUri?.toString())
                                            )
                                            playbackViewModel.playSongs(artistSongs, 0)
                                        }
                                    }
                                },
                                contentPadding = listInsets,
                                sharedTransitionScope = sharedTransitionScope,
                                animatedVisibilityScope = animatedVisibilityScope
                            )
                        }
                        LibraryTabId.ALBUMS -> {
                            val albumSortOrder by browseViewModel.albumSortOrder
                                .collectAsStateWithLifecycle()
                            val albumSourceFilters by browseViewModel.albumSourceFilters
                                .collectAsStateWithLifecycle()
                            AlbumsScreen(
                                albums = albums,
                                onAlbumClick = onAlbumClick,
                                sortOrder = albumSortOrder,
                                onSortOrderChange = browseViewModel::setAlbumSortOrder,
                                sourceFilters = albumSourceFilters,
                                onToggleSourceFilter = browseViewModel::toggleAlbumSourceFilter,
                                showLocalChip = showLocalSourceChip,
                                showCloudChips = showCloudSourceChips,
                                // Quick-play: reproduce el álbum sin navegar (el MiniPlayer
                                // aparece como feedback).
                                onPlayAlbum = { name ->
                                    scope.launch {
                                        val albumSongs = browseViewModel.getAlbumSongs(name).first()
                                        if (albumSongs.isNotEmpty()) {
                                            libraryViewModel.recordContext(
                                                PlaybackContext.Album(name, albumSongs.firstOrNull()?.albumArtUri?.toString())
                                            )
                                            playbackViewModel.playSongs(albumSongs, 0)
                                        }
                                    }
                                },
                                contentPadding = listInsets,
                                sharedTransitionScope = sharedTransitionScope,
                                animatedVisibilityScope = animatedVisibilityScope
                            )
                        }
                        LibraryTabId.GENRES -> {
                            val genres by browseViewModel.genres.collectAsStateWithLifecycle()
                            val partialMatch by browseViewModel.genrePartialMatch
                                .collectAsStateWithLifecycle()
                            GenresScreen(
                                genres = genres,
                                onGenreClick = onGenreClick,
                                partialMatch = partialMatch,
                                onPartialMatchChange = browseViewModel::setGenrePartialMatch,
                                // Quick-play: reproduce el género sin navegar (el MiniPlayer
                                // aparece como feedback), igual que Artistas/Álbumes.
                                onPlayGenre = { name ->
                                    scope.launch {
                                        val genreSongs = browseViewModel.getGenreSongs(name).first()
                                        if (genreSongs.isNotEmpty()) {
                                            libraryViewModel.recordContext(
                                                PlaybackContext.Genre(name, genreSongs.firstOrNull()?.albumArtUri?.toString())
                                            )
                                            playbackViewModel.playSongs(genreSongs, 0)
                                        }
                                    }
                                },
                                contentPadding = listInsets,
                                sharedTransitionScope = sharedTransitionScope,
                                animatedVisibilityScope = animatedVisibilityScope
                            )
                        }
                        LibraryTabId.PLAYLISTS -> {
                            val coverMeta by libraryViewModel.playlistsCoverMeta.collectAsStateWithLifecycle()
                            PlaylistList(
                                playlists = uiState.playlists,
                                favoritesCount = uiState.favorites.size,
                                coverMeta = coverMeta,
                                onPlaylistClick = { id ->
                                    val pl = uiState.playlists.find { it.id == id }
                                    if (pl != null) onPlaylistClick(id, pl.name)
                                },
                                onPlayPlaylist = { libraryViewModel.playPlaylist(it) },
                                onFavoritesClick = onFavoritesClick,
                                onCreatePlaylist = { showCreatePlaylistDialog = true },
                                onDeletePlaylist = { libraryViewModel.deletePlaylist(it) },
                                onRenamePlaylist = { id, name -> libraryViewModel.renamePlaylist(id, name) },
                                contentPadding = listInsets
                            )
                        }
                    }
                }
            }

            // HEADER como capa SOBRE el contenido: tabs (+ banner) con fondo opaco. La
            // TopBar la dibuja el Scaffold, también por encima del body y del mismo color.
            Column(modifier = Modifier.padding(top = topBarInset)) {
                LibraryTabsRow(
                    tabs = tabs,
                    selectedIndex = pagerState.currentPage,
                    onTabSelected = { index -> scope.launch { pagerState.animateScrollToPage(index) } },
                    containerColor = headerColor
                )
                // El banner FLOTA sobre la lista (tarjeta suelta, sin fondo de bloque); su
                // alto medido se suma al contentPadding para que el primer ítem nazca debajo.
                AnimatedVisibility(
                    visible = showBanner,
                    modifier = Modifier.onSizeChanged { bannerHeightPx = it.height }
                ) {
                    when (val state = uiState.bannerState) {
                        is LibraryBannerState.Error -> {
                            ErrorBanner(state.message) { libraryViewModel.onRefresh() }
                        }
                        is LibraryBannerState.Scanning -> {
                            ScanProgressBanner(state.progress, state.message)
                        }
                        is LibraryBannerState.Downloading -> {
                            DownloadSummaryBanner(
                                active = 1,
                                completed = state.current,
                                total = state.total,
                                failed = state.failed,
                                onClick = onDownloadManagerClick
                            )
                        }
                        is LibraryBannerState.Complete -> {
                            SyncCompleteBanner(state.newSongs, state.downloaded, state.failed, state.deleted)
                        }
                        is LibraryBannerState.Paused -> {
                            SyncPausedBanner(state.message)
                        }
                        // Idle: si hay descargas pausadas/detenidas con pendientes, mostramos el
                        // banner persistente (con opción de reanudar / cancelar).
                        else -> downloadBanner?.let { banner ->
                            DownloadStateBanner(
                                control = banner.control,
                                pending = banner.pending,
                                onResume = { syncViewModel.resumeDownloads() },
                                onDismiss = { syncViewModel.dismissDownloadBanner() },
                                onMute = { syncViewModel.muteDownloadBanner() }
                            )
                        }
                    }
                }
            }
        }

        // DIALOGS
        if (showCreatePlaylistDialog) {
            CreatePlaylistDialog(
                onDismiss = {
                    showCreatePlaylistDialog = false
                    pendingSongForNewPlaylist = null
                },
                onConfirm = { name ->
                    val pendingSong = pendingSongForNewPlaylist
                    val trimmed = name.trim()
                    libraryViewModel.createPlaylist(trimmed) { id ->
                        if (pendingSong != null) {
                            // Venimos de "agregar a lista": la lista nueva nace con la canción.
                            libraryViewModel.addSongToPlaylist(id, pendingSong)
                        } else {
                            // Venimos de la pestaña Listas: directo al detalle recién creado,
                            // que tiene el botón "añadir canciones" (antes quedaba una lista
                            // vacía sin ningún camino evidente para llenarla).
                            onPlaylistClick(id, trimmed)
                        }
                    }
                    pendingSongForNewPlaylist = null
                    showCreatePlaylistDialog = false
                }
            )
        }

        if (songIdForPlaylist != null) {
            AddToPlaylistBottomSheet(
                playlists = uiState.playlists,
                onPlaylistSelected = { playlistId ->
                    libraryViewModel.addSongToPlaylist(playlistId, songIdForPlaylist!!)
                    songIdForPlaylist = null
                },
                onCreateNewPlaylist = {
                    // Retener la canción: la lista nueva debe nacer con ella (antes se
                    // descartaba y "crear lista" desde esta hoja creaba una lista vacía).
                    pendingSongForNewPlaylist = songIdForPlaylist
                    songIdForPlaylist = null
                    showCreatePlaylistDialog = true
                },
                onDismiss = { songIdForPlaylist = null }
            )
        }

        if (showLogoutDialog) {
            AlertDialog(
                onDismissRequest = { showLogoutDialog = false },
                title = { Text(stringResource(R.string.common_logout)) },
                text = { Text(stringResource(R.string.logout_confirm)) },
                confirmButton = {
                    TextButton(onClick = {
                        showLogoutDialog = false
                        onLogoutClick()
                    }) {
                        Text(stringResource(R.string.common_logout), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showLogoutDialog = false }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                }
            )
        }
    }
}

/**
 * Reanuda un contexto de "Seguir escuchando": obtiene sus canciones y reproduce como tal
 * (álbum/artista/lista/favoritos en cola; biblioteca en orden o aleatoria), refrescando su
 * posición en el historial. Abre el NowPlaying, como un "retomar".
 */
private fun resumeContext(
    ctx: PlaybackContext,
    scope: kotlinx.coroutines.CoroutineScope,
    browseViewModel: BrowseViewModel,
    libraryViewModel: LibraryViewModel,
    playbackViewModel: PlaybackViewModel,
    favoriteSongs: List<Song>,
    onNavigateToNowPlaying: () -> Unit
) {
    when (ctx) {
        is PlaybackContext.Album -> scope.launch {
            val songs = browseViewModel.getAlbumSongs(ctx.name).first()
            if (songs.isNotEmpty()) {
                libraryViewModel.recordContext(ctx.copy(coverUri = songs.firstOrNull()?.albumArtUri?.toString()))
                playbackViewModel.playSongs(songs, 0)
                onNavigateToNowPlaying()
            }
        }
        is PlaybackContext.Artist -> scope.launch {
            val songs = browseViewModel.getArtistSongs(ctx.name).first()
            if (songs.isNotEmpty()) {
                libraryViewModel.recordContext(ctx.copy(coverUri = songs.firstOrNull()?.albumArtUri?.toString()))
                playbackViewModel.playSongs(songs, 0)
                onNavigateToNowPlaying()
            }
        }
        is PlaybackContext.Genre -> scope.launch {
            val songs = browseViewModel.getGenreSongs(ctx.name).first()
            if (songs.isNotEmpty()) {
                libraryViewModel.recordContext(ctx.copy(coverUri = songs.firstOrNull()?.albumArtUri?.toString()))
                playbackViewModel.playSongs(songs, 0)
                onNavigateToNowPlaying()
            }
        }
        is PlaybackContext.Playlist -> {
            libraryViewModel.playPlaylist(ctx.id)
            onNavigateToNowPlaying()
        }
        PlaybackContext.Favorites -> {
            if (favoriteSongs.isNotEmpty()) {
                libraryViewModel.recordContext(PlaybackContext.Favorites)
                playbackViewModel.playSongs(favoriteSongs, 0)
                onNavigateToNowPlaying()
            }
        }
        PlaybackContext.LibraryShuffle -> {
            playbackViewModel.shuffleAllFromLibrary()
            onNavigateToNowPlaying()
        }
        PlaybackContext.LibraryAll -> {
            playbackViewModel.playAllFromLibrary()
            onNavigateToNowPlaying()
        }
    }
}

/**
 * Alto total de [LibraryTabsRow]: 48dp de la fila de tabs + 16dp de aire teñido inferior.
 * Las listas del pager lo reservan como contentPadding (el contenido pasa por debajo).
 */
private val TabsRowHeight = 64.dp

/** Glifo de cada pestaña, y su separación de la etiqueta cuando la pestaña está activa. */
private val TabIconSize = 20.sp
private val TabIconGap = 8.dp

/**
 * Padding horizontal DENTRO de la píldora, alrededor del glifo (y de la etiqueta en la activa).
 * No confundir con [TabPillGap], que es la separación ENTRE píldoras, ni con el `edgePadding` de
 * la fila, que es el margen contra los bordes de la pantalla.
 *
 * **12dp es el techo con seis pestañas, no un valor elegido a ojo.** Con los 16dp del
 * `HorizontalTextPadding` de Material, en 393dp salían 5×60 + 94 de la activa + 24 de márgenes =
 * 418dp y la última pestaña quedaba cortada por el borde (se vio en el dispositivo, en español;
 * en inglés colaba porque "Home"/"Songs" son más cortas que "Inicio"/"Canciones"). Con 12dp la
 * inactiva mide exactamente los 48dp del mínimo táctil y el peor caso —"Canciones" activa— cae en
 * ~376dp. Subirlo obliga a quitar una pestaña.
 */
private val TabContentPadding = 12.dp

/**
 * Aire a los lados de la píldora de cada pestaña: la mitad de la separación real entre dos
 * píldoras contiguas, porque cada una pone la suya (2 + 2 = 4dp de canal). Ojo si se toca — este
 * padding recorta el área táctil de la pestaña (va antes del `selectable`), así que en vertical no
 * se pone ninguno, y en horizontal cada dp de más son 6dp de fila con las seis pestañas.
 */
private val TabPillGap = 2.dp

/** Dónde nace la primera píldora (y dónde acaba la última) respecto al borde de la pantalla. */
private val TabRowEdgePadding = 12.dp

/**
 * Ancho mínimo de una pestaña, gaps incluidos: 48dp de píldora + el aire a cada lado. El 48 NO es
 * estético — es el objetivo táctil mínimo de Material, y con [TabContentPadding] a 12dp una
 * pestaña inactiva mide justo eso (12 + glifo 20 + 12), así que este mínimo es exactamente el que
 * la sostiene. Si alguien baja el padding, la píldora deja de encogerse y sigue siendo pulsable.
 */
private val TabMinWidth = 48.dp + TabPillGap * 2

/**
 * Navegación de la biblioteca bajo la cabecera de búsqueda: [PrimaryScrollableTabRow] de Material 3
 * con el look de píldora que tenía la botonera anterior — inactiva = solo glifo, activa = glifo +
 * etiqueta dentro de un contenedor tonal.
 *
 * Sustituyó al connected button group (`ButtonGroup` + `ToggleButton`), que era un componente de
 * SELECCIÓN entre opciones puesto a hacer de navegación. Lo que se gana con el componente real:
 * semántica de pestaña, scroll automático hasta la activa, y que el ancho de cada una lo decida su
 * contenido en vez de un reparto por `weight` que obligaba a recortar el padding interno a 4dp
 * para que el glifo cupiera en 360dp.
 *
 * **Siempre la variante scrollable**, sin caso especial para pocas pestañas: la fija reparte el
 * ancho a partes iguales, así que con las inactivas a solo icono las dejaría como píldoras enormes
 * con un glifo perdido en el centro. Con la scrollable cada pestaña mide lo suyo; si caben todas,
 * simplemente no hay scroll.
 *
 * El slot `indicator` queda VACÍO a propósito y la píldora se pinta en cada pestaña — el porqué
 * está en [LibraryTabs].
 */
// El `indicator` de esta fila recibe un `TabIndicatorScope`, que sigue tras la puerta experimental
// en esta versión de Material 3, aunque aquí se le pase un lambda vacío.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryTabsRow(
    /** Pestañas visibles, ya en el orden del usuario. Nunca vacía (ver el caller). */
    tabs: List<TabInfo>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    containerColor: Color,
    modifier: Modifier = Modifier
) {
    // getOrNull + coerceIn: al ocultar la pestaña activa desde Ajustes, el pager recompone con el
    // índice viejo un frame antes de que el LaunchedEffect lo corrija — y `PrimaryTabRow` indexa
    // su lista de posiciones con él, así que un índice fuera de rango revienta.
    val safeIndex = selectedIndex.coerceIn(0, tabs.lastIndex)

    Box(
        modifier = modifier
            .fillMaxWidth()
            // Tinte tonal compartido con la cabecera (elevación M3 al scrollear). El padding
            // INFERIOR va dentro del fondo teñido: es el aire que separa la fila del corte
            // del bloque al scrollear.
            .background(containerColor)
            .padding(bottom = 16.dp)
    ) {
        // `containerColor = Color.Transparent`: el tinte ya lo pinta el Box de arriba, que es
        // quien comparte el color animado con la TopBar. Si además lo pintara la fila, el
        // degradado de elevación se aplicaría dos veces.
        PrimaryScrollableTabRow(
            selectedTabIndex = safeIndex,
            containerColor = Color.Transparent,
            // Margen contra los bordes de la pantalla. Se le resta el aire propio de la píldora
            // para que lo que quede alineado sea el borde del CONTENEDOR, no el del glifo. Son
            // 12dp y no los 16dp de la barra de búsqueda: con seis pestañas, esos 4dp por lado son
            // 8dp que deciden si la última entra o queda cortada contra el borde.
            edgePadding = TabRowEdgePadding - TabPillGap,
            // OBLIGATORIO bajarlo: el default de la fila scrollable es
            // `TabRowDefaults.ScrollableTabRowMinTabWidth` = 90dp, y con las inactivas a solo
            // icono eso las dejaría como píldoras de 90dp con un glifo perdido en el centro y
            // la fila enorme. Bajándolo, cada pestaña mide lo que mide su contenido y solo la
            // activa se ensancha; el valor sigue ahí como red del mínimo táctil.
            minTabWidth = TabMinWidth,
            indicator = {},
            divider = {}
        ) {
            LibraryTabs(tabs, safeIndex, onTabSelected)
        }
    }
}

/**
 * Las pestañas en sí. Recuperan el look de la botonera que había antes —**inactiva = solo glifo,
 * activa = glifo + etiqueta dentro de una píldora**— pero montado sobre `Tab`, así que conserva el
 * comportamiento de pestaña (selección, semántica, scroll hasta la activa) y ya no depende de un
 * componente de selección de opciones.
 *
 * **Todo el contenido va en el slot `icon`, con `text = null`**, y no repartido entre los dos
 * slots. `Tab` coloca text+icon con `TabBaselineLayout`, que alinea el texto por su BASELINE y
 * reserva su sitio; con la etiqueta apareciendo y desapareciendo eso da saltos verticales. Con
 * solo el slot de icono, el layout centra el contenido y la fila del glifo + etiqueta se gobierna
 * aquí, que es justo lo que hace falta para que la etiqueta crezca en horizontal sin mover nada.
 *
 * **La píldora se pinta en el modifier de cada pestaña, no en el slot `indicator` de la fila**
 * (que por eso queda vacío). No es una preferencia: `TabRow` coloca el indicador DESPUÉS de las
 * pestañas en su `SubcomposeLayout`, o sea que se dibuja ENCIMA — vale para una barrita al borde
 * inferior, pero un contenedor relleno taparía el contenido. Como fondo de la pestaña queda detrás
 * por construcción, y de paso el `clip` que va antes es lo que hace que el **ripple del pressed
 * salga con forma de píldora** también en las inactivas.
 *
 * Consecuencia asumida: la píldora aparece en su sitio en vez de deslizarse, así que el indicador
 * ya no sigue el arrastre del pager (solo salta al soltar).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LibraryTabs(
    tabs: List<TabInfo>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    tabs.forEachIndexed { index, tab ->
        val selected = index == selectedIndex
        // OJO con el destino de la animación: NO es `Color.Transparent`, que es negro transparente
        // (0x00000000). Interpolar desde `secondaryContainer` hasta él arrastra el RGB hacia el
        // negro mientras baja el alpha, y eso es el DESTELLO oscuro que se veía al cambiar de
        // pestaña. Con el mismo color a alpha 0 la interpolación se queda en su propio tono y solo
        // se desvanece.
        val pillColor by animateColorAsState(
            targetValue = colorScheme.secondaryContainer.copy(alpha = if (selected) 1f else 0f),
            animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
            label = "tabPill"
        )
        Tab(
            selected = selected,
            onClick = {
                if (!selected) {
                    // Tick de segmento: el háptico del spec para moverse dentro de un grupo
                    // (LongPress sería un golpe de más).
                    haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onTabSelected(index)
                }
            },
            // EXPLÍCITOS, no los defaults: `Tab` define `unselectedContentColor` como "lo mismo
            // que el seleccionado", y el contentColor que hereda de `PrimaryTabRow` es `primary`
            // — o sea que sin esto las seis pestañas se pintarían del color de la activa y ninguna
            // se leería como inactiva. La activa va sobre la píldora, así que su color es el `on-`
            // del contenedor, no el acento suelto.
            selectedContentColor = colorScheme.onSecondaryContainer,
            unselectedContentColor = colorScheme.onSurfaceVariant,
            text = null,
            icon = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = TabContentPadding)
                ) {
                    MaterialSymbol(tab.iconName, size = TabIconSize, fill = selected)
                    // La etiqueta solo en la activa. `expandHorizontally` + `fadeIn` con el MISMO
                    // spring espacial que usaba la botonera: si el texto se abriera con otra curva
                    // se vería llegar tarde respecto al ensanchado de la píldora.
                    AnimatedVisibility(
                        visible = selected,
                        enter = expandHorizontally(
                            animationSpec = MaterialTheme.motionScheme.fastSpatialSpec()
                        ) + fadeIn(
                            animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec()
                        ),
                        exit = shrinkHorizontally(
                            animationSpec = MaterialTheme.motionScheme.fastSpatialSpec()
                        ) + fadeOut(
                            animationSpec = MaterialTheme.motionScheme.fastEffectsSpec()
                        )
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Spacer(modifier = Modifier.width(TabIconGap))
                            Text(
                                text = stringResource(tab.titleRes),
                                style = MaterialTheme.typography.labelLargeEmphasized,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            },
            modifier = Modifier
                // Solo separación HORIZONTAL. Un inset vertical se vería mejor, pero este padding
                // va ANTES del `selectable` que añade `Tab`, así que recorta también el área
                // táctil: con 4dp arriba y abajo la pestaña quedaría en 40dp de alto, por debajo
                // del mínimo de 48dp. La píldora ocupa el alto completo de la fila y el aire se lo
                // dan la cabecera de búsqueda (arriba) y el padding del bloque (abajo).
                .padding(horizontal = TabPillGap)
                .clip(CircleShape)
                .background(pillColor)
        )
    }
}

// --- Helpers (Banner, TopBars) ---

/**
 * Contenedor común de los banners de sync, M3 Expressive: tarjeta tonal SÓLIDA del scheme
 * (nada de colores hardcodeados — así siguen el tema seedeado del álbum), esquinas grandes
 * y padding generoso. El icono va en un círculo del acento para dar jerarquía.
 */
@Composable
private fun BannerCard(
    icon: String,
    iconContainer: Color,
    containerColor: Color,
    contentColor: Color,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(24.dp)
    val baseModifier = Modifier
        .fillMaxWidth()
        // Asimétrico a propósito: 12dp arriba (aire respecto al bloque del header) y 0 abajo
        // — el hueco inferior lo pone el `+ 12.dp` del listInsets, así el banner queda a
        // 12/12 de header y lista (antes 4 arriba / 16 abajo, se veía descolgado).
        .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 0.dp)
    val row: @Composable () -> Unit = {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(shape = CircleShape, color = iconContainer, modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    MaterialSymbol(icon, color = onContainerColor(iconContainer), size = 20.sp, fill = true)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f), content = content)
        }
    }
    if (onClick != null) {
        Surface(onClick = onClick, color = containerColor, contentColor = contentColor, shape = shape, modifier = baseModifier) { row() }
    } else {
        Surface(color = containerColor, contentColor = contentColor, shape = shape, modifier = baseModifier) { row() }
    }
}

// Paletas SEMÁNTICAS fijas de los banners (jerarquía natural: azul = descargando,
// verde = ok, rojo = error). A propósito NO usan el scheme seedeado del álbum: el color
// del banner comunica estado, no estética.
@Immutable
private data class BannerPalette(val container: Color, val accent: Color)

@Composable
private fun bannerBlue() = if (isSystemInDarkTheme())
    BannerPalette(Color(0xFF1E1E1E), Color(0xFF64B5F6)) else BannerPalette(Color(0xFFE3F2FD), Color(0xFF1976D2))

@Composable
private fun bannerGreen() = if (isSystemInDarkTheme())
    BannerPalette(Color(0xFF1E1E1E), Color(0xFF81C784)) else BannerPalette(Color(0xFFE8F5E9), Color(0xFF2E7D32))

@Composable
private fun bannerRed() = if (isSystemInDarkTheme())
    BannerPalette(Color(0xFF3E1E1E), Color(0xFFE57373)) else BannerPalette(Color(0xFFFFEBEE), Color(0xFFC62828))

// Ámbar y no rojo: quedarse sin WiFi no es un fallo de la app ni pide nada al usuario. Con la
// paleta de error, un banner que solo dice "esto sigue solo cuando vuelvas a WiFi" se lee como
// algo que hay que ir a arreglar.
@Composable
private fun bannerAmber() = if (isSystemInDarkTheme())
    BannerPalette(Color(0xFF2A2114), Color(0xFFFFB74D)) else BannerPalette(Color(0xFFFFF3E0), Color(0xFFE65100))

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DownloadSummaryBanner(active: Int, completed: Int, total: Int, failed: Int, onClick: () -> Unit) {
    val palette = bannerBlue()
    BannerCard(
        icon = "download",
        iconContainer = palette.accent,
        containerColor = palette.container,
        contentColor = palette.accent,
        onClick = onClick
    ) {
        val failedText = if (failed > 0) stringResource(R.string.sync_with_errors_suffix, failed) else ""
        Text(
            stringResource(R.string.sync_downloading),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
        )
        Text(
            stringResource(R.string.sync_progress, completed, total, failedText),
            style = MaterialTheme.typography.bodySmall,
            color = palette.accent.copy(alpha = 0.8f)
        )
        Spacer(Modifier.height(10.dp))
        // Onda expressive determinada: el progreso "vivo" de la descarga.
        LinearWavyProgressIndicator(
            progress = { if (total > 0) completed.toFloat() / total else 0f },
            color = palette.accent,
            trackColor = palette.accent.copy(alpha = 0.25f),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(2.dp))
    }
}

@Composable
private fun ErrorBanner(message: String, onRetry: () -> Unit) {
    val palette = bannerRed()
    BannerCard(
        icon = "error",
        iconContainer = palette.accent,
        containerColor = palette.container,
        contentColor = palette.accent,
        onClick = onRetry
    ) {
        Text(
            stringResource(R.string.sync_error),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
        )
        Text(
            stringResource(R.string.sync_error_retry, message),
            style = MaterialTheme.typography.bodySmall,
            color = palette.accent.copy(alpha = 0.8f),
            maxLines = 2
        )
    }
}

@Composable
private fun SyncCompleteBanner(newSongs: Int, downloaded: Int, failed: Int, deleted: Int = 0) {
    val newText = stringResource(R.string.sync_new, newSongs)
    val downloadedText = stringResource(R.string.sync_downloaded, downloaded)
    val deletedText = stringResource(R.string.sync_deleted, deleted)
    val noChangesText = stringResource(R.string.sync_no_changes)
    val failedSuffix = stringResource(R.string.sync_failed_suffix, failed)
    val text = buildString {
        val parts = mutableListOf<String>()
        if (newSongs > 0) parts.add(newText)
        if (downloaded > 0) parts.add(downloadedText)
        if (deleted > 0) parts.add(deletedText)
        if (parts.isNotEmpty()) append(parts.joinToString(" · "))
        else append(noChangesText)
        if (failed > 0) append(failedSuffix)
    }
    val hasIssues = failed > 0
    val palette = if (hasIssues) bannerRed() else bannerGreen()
    BannerCard(
        icon = if (hasIssues) "warning" else "check_circle",
        iconContainer = palette.accent,
        containerColor = palette.container,
        contentColor = palette.accent
    ) {
        Text(
            stringResource(R.string.sync_up_to_date),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
        )
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = palette.accent.copy(alpha = 0.8f),
            maxLines = 1
        )
    }
}

/**
 * Cola de descargas detenida por el entorno. SIN acción y sin barra de progreso: no hay nada
 * que reintentar (se reanuda sola en cuanto vuelva la condición) y no hay avance que mostrar.
 */
@Composable
private fun SyncPausedBanner(message: String) {
    val palette = bannerAmber()
    BannerCard(
        icon = "pause_circle",
        iconContainer = palette.accent,
        containerColor = palette.container,
        contentColor = palette.accent
    ) {
        Text(
            stringResource(R.string.sync_paused),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
        )
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = palette.accent.copy(alpha = 0.8f),
            maxLines = 2
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ScanProgressBanner(count: Int, message: String) {
    val palette = bannerGreen()
    BannerCard(
        icon = "search",
        iconContainer = palette.accent,
        containerColor = palette.container,
        contentColor = palette.accent
    ) {
        Text(
            stringResource(R.string.sync_scanning),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
        )
        Text(
            if (count > 0) stringResource(R.string.sync_found, count, message) else message,
            style = MaterialTheme.typography.bodySmall,
            color = palette.accent.copy(alpha = 0.8f),
            maxLines = 1
        )
        Spacer(Modifier.height(10.dp))
        // Onda expressive indeterminada mientras se recorre el delta.
        LinearWavyProgressIndicator(
            color = palette.accent,
            trackColor = palette.accent.copy(alpha = 0.25f),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(2.dp))
    }
}

/**
 * Cabecera de la biblioteca: search bar DOCKED real de M3 (la píldora ES el ancla del
 * search view expandido — `SearchBar(state, inputField)` registra sus collapsedCoords y
 * el tap del input dispara la expansión, sin `onSearchClick` manual). Sin título ni
 * TopAppBar: la píldora activa de las tabs ya dice dónde estás. Visible en TODAS las
 * pestañas, incluida Listas. Ordenar (solo Todas) y el overflow van DENTRO de la píldora
 * como trailing icons del spec — el trailing lo arma `searchInputField` en el caller.
 *
 * El tinte on-scroll lo gobierna el caller (mismo color animado que la fila de tabs).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LibrarySearchHeader(
    searchBarState: SearchBarState,
    searchInputField: @Composable () -> Unit,
    searchBarColors: SearchBarColors,
    containerColor: Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            // background ANTES del statusBarsPadding: el tinte del header pinta también
            // detrás de la barra de estado (antes lo hacía el TopAppBar con sus insets).
            .background(containerColor)
            .statusBarsPadding()
            // bottom generoso: la píldora quedaba pegada a la píldora activa de las tabs.
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 12.dp)
    ) {
        SearchBar(
            state = searchBarState,
            inputField = searchInputField,
            colors = searchBarColors,
            modifier = Modifier.weight(1f)
        )
    }
}

/** Overflow del header (Ajustes / cerrar sesión): trailing icon de la píldora de búsqueda. */
@Composable
private fun LibraryOverflowButton(
    isLoggedIn: Boolean,
    onSettingsClick: () -> Unit,
    onLogoutClick: () -> Unit
) {
    var showOverflowMenu by remember { mutableStateOf(false) }
    // El botón de nueva lista vive en el FAB contextual (MainActivity), que muta a
    // "playlist_add" en la pestaña Listas — no duplicarlo aquí.
    Box {
        IconButton(onClick = { showOverflowMenu = true }) { MaterialSymbol("more_vert") }
        DropdownMenu(
            expanded = showOverflowMenu,
            onDismissRequest = { showOverflowMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.settings_title)) },
                onClick = { showOverflowMenu = false; onSettingsClick() },
                leadingIcon = { MaterialSymbol("settings") }
            )
            if (isLoggedIn) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.common_logout)) },
                    onClick = { showOverflowMenu = false; onLogoutClick() },
                    leadingIcon = { MaterialSymbol("logout") }
                )
            }
        }
    }
}

/**
 * Contenido del contenedor de búsqueda expandido. Reusa [SongsScreen] en su modo búsqueda
 * (carruseles de artistas/álbumes que matchean + canciones del paging LIKE), que antes se
 * renderizaba dentro de la pestaña "Todas".
 *
 * Con la query vacía no dibuja nada: el diálogo se abre mostrando solo el input, no la
 * biblioteca entera (el paging con query en blanco devuelve TODAS las canciones).
 *
 * Toda navegación colapsa primero ([onCollapseAnd]): el destino y las hojas modales viven en
 * la ventana de abajo y quedarían tapados por el diálogo.
 */
@Composable
private fun SearchResults(
    searchQuery: String,
    searchArtists: List<ArtistSummary>,
    searchAlbums: List<AlbumSummary>,
    libraryViewModel: LibraryViewModel,
    playbackViewModel: PlaybackViewModel,
    playingAccent: Color?,
    onAddToPlaylistRequest: (String) -> Unit,
    onCollapseAnd: (() -> Unit) -> Unit,
    onNavigateToNowPlaying: () -> Unit,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit
) {
    if (searchQuery.isBlank()) return

    SongsScreen(
        currentFilter = SongFilter.ALL,
        contentPadding = PaddingValues(
            top = 8.dp,
            bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp
        ),
        onNavigateToNowPlaying = { onCollapseAnd(onNavigateToNowPlaying) },
        onAddToPlaylistRequest = { songId -> onCollapseAnd { onAddToPlaylistRequest(songId) } },
        viewModel = libraryViewModel,
        playbackViewModel = playbackViewModel,
        playingAccent = playingAccent,
        isSearchActive = true,
        searchQuery = searchQuery,
        searchArtists = searchArtists,
        searchAlbums = searchAlbums,
        onSearchArtistClick = { name -> onCollapseAnd { onArtistClick(name) } },
        onSearchAlbumClick = { name -> onCollapseAnd { onAlbumClick(name) } }
    )
}

