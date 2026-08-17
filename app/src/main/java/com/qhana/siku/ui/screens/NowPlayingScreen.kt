package com.qhana.siku.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import com.qhana.siku.R
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import android.content.res.Configuration
import android.media.MediaMetadataRetriever
import android.os.Build
import com.qhana.siku.data.model.PlaybackOrigin
import com.qhana.siku.data.model.PlaybackState
import com.qhana.siku.data.model.PlayerToolbarConfig
import com.qhana.siku.data.model.RepeatMode
import com.qhana.siku.data.model.Song
import com.qhana.siku.data.model.SourceType
import com.qhana.siku.data.model.ToolbarActionState
import com.qhana.siku.ui.components.*
import com.qhana.siku.ui.model.toUiModel
import com.qhana.siku.ui.util.shareSong
import com.qhana.siku.ui.state.NowPlayingUiState
import com.qhana.siku.ui.theme.AppContainerBoundsTransform
import com.qhana.siku.ui.theme.EXPRESSIVE_DEFAULT_EFFECTS_MS
import com.qhana.siku.ui.theme.ExpressiveDefaultEffectsEasing
import com.qhana.siku.ui.theme.appContainerContentEnter
import com.qhana.siku.ui.theme.appContainerContentExit
import com.qhana.siku.ui.theme.appSheetEnter
import com.qhana.siku.ui.theme.appSheetExit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

internal object NowPlayingConfig {
    val DefaultBackgroundColor = Color(0xFF252525)
    val PlayButtonElevation = 6.dp
    // Grupo de TRANSPORTE (prev/play/next), ajustado a la referencia visual del spec
    // Expressive: separación visible entre miembros y esquinas interiores generosas que se
    // encogen al presionar. La barra de acciones es un STANDARD button group (formas
    // individuales, separación 12.dp de spec) — sus corners viven en GlassActionButton.
    val GroupSpacing = 8.dp
    val GroupInnerCorner = 16.dp
    val GroupInnerCornerPressed = 8.dp
    // Hueco MÍNIMO garantizado entre el grupo de transporte y los toggles laterales
    // (aleatorio/repetir): el play se ensancha en pausa y sin este colchón se tocaban.
    val TransportSideGap = 12.dp
}

@Immutable
data class PlayerActions(
    val onPlayPause: () -> Unit,
    val onNext: () -> Unit,
    val onPrevious: () -> Unit,
    val onSeek: (Long) -> Unit,
    /** Salto RELATIVO (doble toque en la carátula); el destino lo acota el ViewModel. */
    val onSeekBy: (Long) -> Unit,
    val onShuffleToggle: () -> Unit,
    val onRepeatToggle: () -> Unit,
    val onSkipToIndex: (Int) -> Unit,
    val onReorder: (Int, Int) -> Unit,
    val onRemoveFromQueue: (Int) -> Unit,
    val onSaveQueueAsPlaylist: (String) -> Unit,
    val onClearQueue: () -> Unit,
    val onToggleFavorite: () -> Unit,
    val onToggleDownload: () -> Unit,
    val onToggleKeepScreenOn: () -> Unit,
    val onOpenEqualizer: () -> Unit,
    val onFetchLyrics: (Boolean) -> Unit,
    val onSearchLyricsManually: () -> Unit,
    /** Guardar la letra en el archivo (`.lrc` o embebida, según la preferencia). */
    val onSaveLyrics: () -> Unit,
    val onSelectLyricsCandidate: (com.qhana.siku.data.repository.LyricsCandidate) -> Unit,
    val onDismissLyricsSearch: () -> Unit,
    val onUpdatePosition: () -> Unit,
    val onAddToPlaylist: (playlistId: Long, songId: String) -> Unit,
    /** Crear lista desde la hoja "agregar a lista": la lista nace CON la canción en curso. */
    val onCreatePlaylist: (name: String) -> Unit,
    val onStartSleepTimer: (minutes: Int, finishSong: Boolean) -> Unit,
    val onCancelSleepTimer: () -> Unit
)

@Immutable
data class NavigationActions(
    val onBackClick: () -> Unit,
    val onLaunchAmbientMode: (timeoutMinutes: Int) -> Unit,
    val onShowDebugInfo: () -> Unit,
    val onClearDebugInfo: () -> Unit,
    val onSelectColor: (Int) -> Unit,
    val onArtistClick: (String) -> Unit,
    val onAlbumClick: (String) -> Unit
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun NowPlayingScreen(
    uiState: NowPlayingUiState,
    playbackState: PlaybackState,
    currentPositionFlow: StateFlow<Long>,
    durationFlow: StateFlow<Long>,
    /** Búfer cargado: tercer nivel de la barra, solo con sentido en streaming. */
    bufferedPositionFlow: StateFlow<Long>,
    isShuffleEnabled: Boolean,
    repeatMode: RepeatMode,
    /**
     * Cola e índice actual como FLOWS, no como valores: los consume SOLO la hoja de la cola, que los
     * colecta dentro de su propia rama. Como valores, cada cambio de canción (índice nuevo, y la
     * lista entera si cualquier fila cambió) recomponía este composable completo —medido con la
     * sonda (16 ago): eran 1-2 de las 3-4 recomposiciones del NowPlaying en los 30 ms siguientes al
     * `playAt`, en plena ventana del container transform— para una hoja que casi nunca está abierta.
     * Mismo patrón que `currentPositionFlow`.
     */
    playlistFlow: StateFlow<List<Song>>,
    currentIndexFlow: StateFlow<Int>,
    isFavorite: Boolean,
    keepScreenOn: Boolean,
    solidBackground: Boolean,
    /** Ajustes → Reproducción: barra de progreso ondulada (Expressive) en vez de la píldora. */
    wavyProgress: Boolean,
    /** Ajustes -> Apariencia: grosor de la barra de progreso (vale para los dos modos). */
    progressThickness: Dp,
    /** Chip de formato con ficha técnica; se conmuta desde Ajustes O tocando el propio chip. */
    detailedFormat: Boolean,
    onToggleDetailedFormat: () -> Unit,
    /** Ajustes → Reproducción: deslizar para cambiar/cerrar y doble toque para saltar. */
    gesturesEnabled: Boolean,
    playlists: List<com.qhana.siku.data.model.Playlist>,
    sleepTimer: com.qhana.siku.player.MusicController.SleepTimerState?,
    /** Estado del EQ propio para el fondo activo de su botón en el toolbar. */
    eqEnabled: Boolean,
    /** Guardado de letra en curso: apaga el botón para no dispararlo dos veces. */
    isSavingLyrics: Boolean = false,
    playerActions: PlayerActions,
    navigationActions: NavigationActions,
    toolbarConfig: List<ToolbarActionState> = PlayerToolbarConfig.DEFAULT,
    modifier: Modifier = Modifier,
    sharedTransitionScope: SharedTransitionScope? = null,
    /** Key del shared element de la carátula; null = sin morph (ver PlayerArtOrigin). */
    artSharedKey: Any? = null,
    /**
     * Key del *container transform* de la superficie de la que CRECE esta pantalla; null = entra por
     * su cuenta (fundido) en vez de crecer de ningún sitio.
     *
     * La superficie de origen la elige quien abre el player (ver `PlayerArtOrigin`): la píldora
     * ([PLAYER_CONTAINER_SHARED_KEY]) o la fila tocada
     * ([com.qhana.siku.ui.components.rowContainerSharedKey]). Aquí da igual cuál sea — la coreografía
     * es la misma y esta pantalla solo necesita saber a qué key engancharse.
     */
    containerSharedKey: Any? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    // Sonda (solo debug): cada recomposición del scope de esta pantalla (es la función grande).
    SideEffect { com.qhana.siku.data.util.JankProbe.mark { "NowPlayingScreen recompuesta" } }
    var showQueueSheet by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    var showAmbientModeDialog by remember { mutableStateOf(false) }
    var showAddToPlaylist by remember { mutableStateOf(false) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var showSleepTimerSheet by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current

    // Atrás cierra los overlays de ESTA pantalla (lyrics/cola). SOLO se habilita cuando hay uno
    // abierto: si estuviera `enabled = true` siempre, robaría el back a otros overlays del player
    // (p. ej. el ecualizador, que se abre desde acá) porque este handler se registra DESPUÉS
    // (NowPlaying se compone al expandir el player) → gana por LIFO y su `else` colapsaba el player.
    // El colapso del player lo maneja el BackHandler dedicado de PlayerOverlay.
    androidx.activity.compose.BackHandler(enabled = showLyrics || showQueueSheet) {
        if (showLyrics) showLyrics = false
        else if (showQueueSheet) showQueueSheet = false
    }

    val isPlayingOrBuffering by remember(playbackState) {
        derivedStateOf {
            playbackState == PlaybackState.PLAYING || playbackState == PlaybackState.BUFFERING
        }
    }

    // Auto-fetch lyrics (force = false) cuando se abre el overlay.
    // Claves mínimas: sólo el par (showLyrics, song.id). Los campos de estado de loading
    // se consultan dentro del efecto y NO deben ser key — causarían re-triggers en cascada.
    LaunchedEffect(showLyrics, uiState.song?.id) {
        if (showLyrics && uiState.lyrics == null && uiState.lyricLines.isEmpty() && !uiState.isLyricsLoading) {
            playerActions.onFetchLyrics(false)
        }
    }

    // Keep screen on while lyrics are visible
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(showLyrics, keepScreenOn) {
        view.keepScreenOn = showLyrics || keepScreenOn
        onDispose { view.keepScreenOn = keepScreenOn }
    }

    val song = uiState.song
    if (song == null) {
        // Sin canción no hay nada que mostrar. La capa del player (NowPlayingLayer) ya detecta este
        // estado y COLAPSA el player, así que en la práctica no se llega aquí; es solo red de seguridad. Una
        // superficie lisa, NUNCA el layout viejo del reproductor (el antiguo NowPlayingSkeleton, ya
        // eliminado, era la PRIMERA versión de esta pantalla y no reflejaba el diseño actual).
        Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
        return
    }

    // Fondo del gradiente = `secondaryContainer` → `surface` (rol del esquema, SEED-ONLY): antes era
    // el color CRUDO del álbum mezclado 50%. Sigue teñido del álbum (tema seedeado) y el tema anima
    // `secondaryContainer` al cambiar de canción (animatedScheme).
    val albumPrimary = MaterialTheme.colorScheme.secondaryContainer

    // Play button + TODOS los acentos que derivan de esto = rol PRIMARY del esquema. El color
    // elegido/extraído es SOLO el SEED (el tema está seedeado de él vía MusicPlayerTheme), NO se usa
    // 1:1 — eso causaba las inconsistencias/parches (ensureContrast, onAccentContentColor y el viejo
    // `albumAccent`, ya eliminados). El tema anima primary al cambiar de canción (animatedScheme),
    // así que no hace falta el animateColorAsState local.
    val playButtonColor = MaterialTheme.colorScheme.primary
    // Glifo del play por CONTRASTE real (`maxContrastOn`), NO `onPrimary`: en la mayoría de temas
    // coinciden, pero en Fidelity `onPrimary` puede ser un par de bajo contraste (gris azulado sobre
    // un primary casi negro) y el triángulo salía apagado. Mismo criterio que el play del MiniPlayer,
    // el chip de origen y el toggle de letras: contenido sobre un acento = maxContrastOn.
    val playButtonContentColor = maxContrastOn(playButtonColor)

    val surfaceColor = MaterialTheme.colorScheme.surface
    val solidBackgroundColor = MaterialTheme.colorScheme.surfaceContainer

    // Fondo según el ajuste del usuario: color SÓLIDO tonal M3 Expressive (surfaceContainer,
    // que ya viene teñido por el seed del álbum vía theme) o el degradado vertical clásico
    // (Color Profundo -> Fondo Neutro).
    val backgroundBrush = remember(albumPrimary, surfaceColor, solidBackgroundColor, solidBackground) {
        if (solidBackground) {
            SolidColor(solidBackgroundColor)
        } else {
            Brush.verticalGradient(
                colors = listOf(
                    albumPrimary,
                    surfaceColor
                )
            )
        }
    }

    // Color que hay REALMENTE bajo la barra superior, sea cual sea el modo de fondo: en degradado es
    // su primera parada (arriba es justo donde vive la barra). Lo consume el chip de origen, que
    // deriva de él su relleno. Se calcula AQUÍ, pegado al brush, porque son la misma decisión: si
    // algún día el degradado arranca en otro color, el chip lo sigue solo.
    val topBackgroundColor = if (solidBackground) solidBackgroundColor else albumPrimary

    val contentColor = MaterialTheme.colorScheme.onSurface
    val variantColor = MaterialTheme.colorScheme.onSurfaceVariant

    // `song` viene del player; `uiState.isDownloaded` sale de la fila de la BD y se adelanta a él
    // en cuanto termina una descarga, así que gana sobre el `path` del MediaItem en curso.
    val origin = if (uiState.isDownloaded && song.sourceType != SourceType.LOCAL) {
        PlaybackOrigin.DOWNLOADED
    } else {
        song.playbackOrigin
    }
    // El nivel de búfer solo se dibuja aquí: en local y en descargadas el player reporta la
    // canción entera bufferizada desde el primer instante, así que no informaría de nada.
    val isStreaming = origin == PlaybackOrigin.STREAMING
    val formatInfo = rememberAudioFormat(song.path, song.title)

    val onAlbumArtLongPress = { navigationActions.onShowDebugInfo() }
    // Compartir: el texto se arma acá porque los recursos solo se leen desde la composición, y
    // el artista en blanco tiene que caer en la misma etiqueta que muestra la UI.
    val shareChooserTitle = stringResource(R.string.np_share)
    val shareText = stringResource(
        R.string.np_share_text,
        song.title,
        song.artist.ifBlank { stringResource(R.string.common_unknown_artist) }
    )
    val onShareSong = { shareSong(context, song, shareText, shareChooserTitle) }
    val onLyricsToggle = { showLyrics = !showLyrics }
    val onShowQueue = { showQueueSheet = true }
    val onAmbientMode = { showAmbientModeDialog = true }
    val onAddToPlaylistClick = { showAddToPlaylist = true }
    val onSleepTimerClick = { showSleepTimerSheet = true }

    // Arrastre hacia abajo para cerrar. El estado se crea SIEMPRE (crearlo dentro de un `if`
    // ataría su `remember` a la rama y perdería el arrastre en curso si el ajuste cambiara a
    // mitad del gesto); lo que se apaga con el ajuste es quién lo alimenta.
    val dismissState = rememberPlayerDismissState { navigationActions.onBackClick() }
    val activeDismiss = dismissState.takeIf { gesturesEnabled }

    // CONTAINER TRANSFORM: la superficie de origen y esta pantalla son LA MISMA cambiando de tamaño
    // (ver [PLAYER_CONTAINER_SHARED_KEY]). Envuelve la pantalla ENTERA —fondo y contenido—, porque en
    // este patrón lo que se ve encoger y crecer es el contenedor CON lo que lleva dentro.
    //
    // El origen es la PÍLDORA o la FILA tocada según cómo se abriera el player, y esa diferencia no
    // llega hasta aquí: las dos puntas se configuran igual (misma `key` por parámetro, mismo resize,
    // mismo bounds spec) porque son la misma coreografía. (La sombra de la píldora no participa: se
    // dibuja en su sitio bajo el overlay y solo se funde — ver `pillShadow` en MiniPlayer.kt.)
    //
    // **`ContentScale.Fit` es la pieza que costó tres intentos encontrar, y el culpable de los tres
    // fallos anteriores fue siempre el CONTENT SCALE, no el `resizeMode`:**
    //
    //  - `Crop` (v1) → un factor de escala de **1**, porque `Crop` toma el MAYOR de los dos ratios y
    //    las dos superficies comparten ancho (ratio 1) mientras el de alto es ~0.03. O sea el player
    //    se dibujaba a tamaño real trasladándose una pantalla entera: el "slide up". Y al cerrar se
    //    quedaba a tamaño COMPLETO desvaneciéndose sobre el home, que es lo que se veía como restos.
    //    (El comentario que justificaba `Crop` razonaba sobre la punta de la PÍLDORA, donde sí cambia
    //    algo, y trasladaba la conclusión a ésta, donde `Crop` y `FillWidth` dan exactamente lo mismo.)
    //  - `RemeasureToBounds` (v2) → re-medir el layout cada frame, y como la carátula es el único
    //    `weight(1f)` de la columna absorbía TODA la holgura: cero durante los primeros dos tercios del
    //    recorrido y luego disparada. La "doble animación" del 16 ago.
    //  - `Fit` toma el MENOR de los ratios (~0.03), así que el contenido **se achica hasta la nada**
    //    con la superficie al cerrar y crece desde ella al abrir. Sin re-medir nada: la carátula
    //    conserva su tamaño de layout y solo se escala, que es lo que evita el fallo de la v2.
    //
    // `Alignment.Center` y no `TopCenter`: el contenido escalado se ancla al centro del rect, o sea
    // crece desde el centro de la píldora en vez de colgar de su borde superior.
    //
    // La PORTADA es la excepción y no viaja escalada con esto: tiene su propio shared element
    // ([PLAYER_ART_SHARED_KEY]) y se eleva al overlay, así que se dibuja UNA sola vez, en sus propios
    // bounds interpolados. Es lo que la deja hacer un viaje limpio de la píldora al centro.
    //
    // Va ANTES del `graphicsLayer` del gesto de cierre —o sea, más afuera—: el morph mide la pantalla
    // en su sitio, no arrastrada por el dedo.
    val containerSharedModifier =
        if (sharedTransitionScope != null && animatedVisibilityScope != null && containerSharedKey != null) {
            with(sharedTransitionScope) {
                // El que SALE se dibuja ENCIMA y se disuelve sobre el que ENTRA, sólido debajo (ver el
                // mismo patrón en MiniPlayer): así abrir y cerrar se ven IGUAL. Al cerrar, el player es
                // el saliente → va encima y se ve disolverse; al abrir es el entrante → sólido debajo.
                val exiting = animatedVisibilityScope.transition.targetState != EnterExitState.Visible
                Modifier.sharedBounds(
                    sharedContentState = rememberSharedContentState(key = containerSharedKey),
                    animatedVisibilityScope = animatedVisibilityScope,
                    boundsTransform = AppContainerBoundsTransform,
                    enter = appContainerContentEnter(),
                    exit = appContainerContentExit(),
                    resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(
                        ContentScale.Fit,
                        Alignment.Center
                    ),
                    zIndexInOverlay =
                        if (exiting) CONTAINER_SURFACE_OVERLAY_Z_EXITING
                        else CONTAINER_SURFACE_OVERLAY_Z_ENTERING,
                    // A pantalla completa la forma de la píldora sería un óvalo, así que se recorta con
                    // la esquina `extraLarge`; al encoger converge con la barra. Es el TOKEN del tema y
                    // no su valor (28dp escrito a mano): la otra forma del MiniPlayer sale de ese mismo
                    // token, así que copiar el número dejaba las dos puntas del morph libres de divergir.
                    clipInOverlayDuringTransition = OverlayClip(MaterialTheme.shapes.extraLarge)
                )
            }
        } else Modifier

    // Fade-in del CONTENIDO al ABRIR, gobernado por el PROGRESO del morph (mismo patrón que
    // `shadowFactor` en MiniPlayer): la superficie entra SÓLIDA (`enter = None`, o crecería
    // translúcida) y el contenido aparece encima mientras ella crece — el *"content is swapped"* del
    // spec. Existe con cualquier origen que tenga superficie (píldora o fila); sin origen el contenido
    // va opaco desde el primer frame.
    //
    // **Dura el token *default* de effects (200 ms), NO el del morph (500)**: es un fundido de contenido
    // —el spec lo hace en el primer tramo del container transform— y además tiene un coste que no se ve:
    // un alpha < 1 sobre el Scaffold a pantalla completa obliga a HWUI a un `saveLayer` offscreen de la
    // pantalla ENTERA en cada frame ("alpha caused saveLayer 1080x2400", medido con atrace el 17 ago),
    // así que estiraba el RenderThread durante 500 ms cuando el bounds ya había asentado a ~350. Con
    // 200 el contenido está opaco antes de que la superficie termine de crecer, y la capa cara dura
    // menos de la mitad del morph.
    //
    // **Solo la ENTRADA**: `PostExit` vale 1, no 0. Al cerrar, el contenido va DENTRO del
    // `sharedBounds` y ya se apaga con su `exit` ([appContainerContentExit], 300 ms) a la vez que
    // ENCOGE con la superficie; sumarle acá un segundo fade multiplicaría los dos alphas y lo apagaría
    // antes que a su propia superficie. Cuando el contenido estuvo FUERA del contenedor sí hubo que
    // bajarlo a mano, igualando la duración para no dejar restos del player sobre la píldora ya puesta
    // — ese apaño se fue con el contenido de vuelta adentro.
    //
    // Es un `State` y NO un valor con `by` A PROPÓSITO: se lee DIFERIDO dentro del `graphicsLayer` del
    // Scaffold — cambia en cada frame del morph y leerlo en composición recompondría la pantalla entera
    // por frame (el mismo criterio que las lecturas diferidas del progreso de reproducción).
    val contentFactor: State<Float>? =
        if (containerSharedKey != null && sharedTransitionScope != null && animatedVisibilityScope != null) {
            animatedVisibilityScope.transition.animateFloat(
                transitionSpec = { tween(EXPRESSIVE_DEFAULT_EFFECTS_MS, easing = ExpressiveDefaultEffectsEasing) },
                label = "playerContentFactor"
            ) { if (it == EnterExitState.PreEnter) 0f else 1f }
        } else null

    Box(
        modifier = modifier
            .fillMaxSize()
            .then(containerSharedModifier)
            // Se traslada y atenúa TODO el reproductor —fondo incluido— con el dedo. Mover solo
            // el contenido dejaría el degradado quieto detrás y se vería el hueco por abajo.
            .graphicsLayer {
                translationY = dismissState.offsetY
                alpha = 1f - (1f - PlayerGestureConfig.DismissMinAlpha) * dismissState.progress
            }
    ) {
        // Por la CONFIGURACIÓN de la ventana y no por los constraints medidos: `BoxWithConstraints`
        // subcompone, y decidir la maquetación por aspect ratio es lo que metería el layout landscape
        // en pleno vuelo si el contenedor volviera a re-medirse. La orientación de la ventana es
        // estable durante el morph; el tamaño no tiene por qué serlo.
        val isLandscape =
            LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

        // Capa 1 (FONDO): el gradiente en una capa DEDICADA detrás de todo, hermana del contenido.
        // Sigue separada del Scaffold —y no como su `containerColor`— porque el gesto de cierre
        // traslada y atenúa el reproductor ENTERO desde el `graphicsLayer` de arriba, fondo
        // incluido: pintarlo dentro del Scaffold lo dejaría quieto y se vería el hueco al arrastrar.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundBrush)
        )

        // Capa 2 (CONTENIDO): layout normal, encima del fondo y transparente. El alpha del morph va
        // AQUÍ —topBar incluida— y NO en la Capa 1: esa es la superficie del contenedor y tiene que
        // crecer SÓLIDA, no aparecer translúcida.
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .then(contentFactor?.let { f -> Modifier.graphicsLayer { alpha = f.value } } ?: Modifier),
            containerColor = Color.Transparent,
            topBar = {
                if (!isLandscape) {
                    NowPlayingTopBar(
                        onBackClick = navigationActions.onBackClick,
                        contentColor = contentColor,
                        accentColor = playButtonColor,
                        origin = origin,
                        backgroundColor = topBackgroundColor,
                        onAmbientMode = onAmbientMode
                    )
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    // Arrastre de cierre desde CUALQUIER punto del reproductor. Va en el
                    // contenedor, así que los hijos que gestionan su propio gesto (el slider, la
                    // carátula) reciben antes y este solo ve lo que ninguno quiso.
                    .playerDismissDrag(activeDismiss, gesturesEnabled)
            ) {
                if (isLandscape) {
                    NowPlayingLandscape(
                        song = song,
                        uiState = uiState,
                        variantColor = variantColor,
                        contentColor = contentColor,
                        playButtonColor = playButtonColor,
                        playButtonContentColor = playButtonContentColor,
                        revealAccent = playButtonColor,
                        isFavorite = isFavorite,
                        repeatMode = repeatMode,
                        keepScreenOn = keepScreenOn,
                        showLyrics = showLyrics,
                        isPlayingOrBuffering = isPlayingOrBuffering,
                        playbackState = playbackState,
                        currentPositionFlow = currentPositionFlow,
                        durationFlow = durationFlow,
                        bufferedPositionFlow = bufferedPositionFlow,
                        showBuffer = isStreaming,
                        origin = origin,
                        backgroundColor = topBackgroundColor,
                        gesturesEnabled = gesturesEnabled,
                        dismiss = activeDismiss,
                        format = formatInfo,
                        detailedFormat = detailedFormat,
                        onToggleDetailedFormat = onToggleDetailedFormat,
                        wavyProgress = wavyProgress,
                        progressThickness = progressThickness,
                        playerActions = playerActions,
                        onBackClick = navigationActions.onBackClick,
                        onArtistClick = navigationActions.onArtistClick,
                        onAlbumClick = navigationActions.onAlbumClick,
                        onAmbientMode = onAmbientMode,
                        onLyricsToggle = onLyricsToggle,
                        onShowQueue = onShowQueue,
                        onAddToPlaylistClick = onAddToPlaylistClick,
                        eqEnabled = eqEnabled,
                        sleepTimerActive = sleepTimer != null,
                        onSleepTimerClick = onSleepTimerClick,
                        onShareSong = onShareSong,
                        toolbarConfig = toolbarConfig,
                        sharedTransitionScope = sharedTransitionScope,
                        artSharedKey = artSharedKey,
                        animatedVisibilityScope = animatedVisibilityScope,
                        onAlbumArtLongPress = onAlbumArtLongPress
                    )
                } else {
                    NowPlayingPortrait(
                        song = song,
                        uiState = uiState,
                        variantColor = variantColor,
                        contentColor = contentColor,
                        playButtonColor = playButtonColor,
                        playButtonContentColor = playButtonContentColor,
                        revealAccent = playButtonColor,
                        isFavorite = isFavorite,
                        repeatMode = repeatMode,
                        keepScreenOn = keepScreenOn,
                        showLyrics = showLyrics,
                        isPlayingOrBuffering = isPlayingOrBuffering,
                        playbackState = playbackState,
                        currentPositionFlow = currentPositionFlow,
                        durationFlow = durationFlow,
                        bufferedPositionFlow = bufferedPositionFlow,
                        showBuffer = isStreaming,
                        gesturesEnabled = gesturesEnabled,
                        dismiss = activeDismiss,
                        format = formatInfo,
                        detailedFormat = detailedFormat,
                        onToggleDetailedFormat = onToggleDetailedFormat,
                        wavyProgress = wavyProgress,
                        progressThickness = progressThickness,
                        playerActions = playerActions,
                        onArtistClick = navigationActions.onArtistClick,
                        onAlbumClick = navigationActions.onAlbumClick,
                        onLyricsToggle = onLyricsToggle,
                        onShowQueue = onShowQueue,
                        onAddToPlaylistClick = onAddToPlaylistClick,
                        eqEnabled = eqEnabled,
                        sleepTimerActive = sleepTimer != null,
                        onSleepTimerClick = onSleepTimerClick,
                        onShareSong = onShareSong,
                        toolbarConfig = toolbarConfig,
                        sharedTransitionScope = sharedTransitionScope,
                        artSharedKey = artSharedKey,
                        animatedVisibilityScope = animatedVisibilityScope,
                        onAlbumArtLongPress = onAlbumArtLongPress
                    )
                }
            }
        }
    }

    // Polling removed: ProgressSlider observes currentPositionFlow directly.

    if (showAmbientModeDialog) {
        AlertDialog(
            onDismissRequest = { showAmbientModeDialog = false },
            title = { Text(stringResource(R.string.ambient_title)) },
            text = { Text(stringResource(R.string.ambient_desc)) },
            confirmButton = {
                TextButton(onClick = {
                    showAmbientModeDialog = false
                    navigationActions.onLaunchAmbientMode(-1)
                }) { Text(stringResource(R.string.common_start)) }
            },
            dismissButton = {
                TextButton(onClick = { showAmbientModeDialog = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    AnimatedVisibility(
        visible = showQueueSheet,
        // Mismos helpers que las otras hojas a pantalla completa (ecualizador, MiniPlayer): tres
        // sitios con la misma coreografía y, hasta ahora, con tres pares de duraciones distintos.
        enter = appSheetEnter(),
        exit = appSheetExit()
            ) {
            // La cola y el índice se colectan AQUÍ, dentro de la hoja: solo esta rama depende de
            // ellos, así que un cambio de canción no recompone la pantalla entera (ver el KDoc de
            // `playlistFlow`). Y los 777 modelos se calculan solo mientras la hoja está compuesta —
            // antes se calculaban al ABRIR el reproductor, en plena ventana del container transform.
            val playlist by playlistFlow.collectAsStateWithLifecycle()
            val currentIndex by currentIndexFlow.collectAsStateWithLifecycle()
            // Transformar playlist a modelos UI estables (ASYNC para evitar ANR en listas grandes).
            val uiPlaylist by produceState(
                initialValue = emptyList<com.qhana.siku.ui.model.SongUiModel>(),
                key1 = playlist
            ) {
                value = withContext(Dispatchers.Default) {
                    playlist.map { it.toUiModel(isActive = false) }
                }
            }
            QueueBottomSheet(
                playlist = uiPlaylist,
                currentIndex = currentIndex,
                onSongClick = playerActions.onSkipToIndex,
                onReorder = playerActions.onReorder,
                onDismiss = { showQueueSheet = false },
                isShuffleEnabled = isShuffleEnabled,
                onShuffleToggle = playerActions.onShuffleToggle,
                onRemoveSong = playerActions.onRemoveFromQueue,
                onSaveAsPlaylist = playerActions.onSaveQueueAsPlaylist,
                onClearQueue = playerActions.onClearQueue,
                accentColor = albumPrimary
            )
        }

        // --- Full Screen Lyrics Overlay ---
        AnimatedVisibility(
            visible = showLyrics,
            enter = appSheetEnter(),
            exit = appSheetExit()
        ) {
            // `song` ya es no-null acá (early-return arriba si uiState.song == null).
            LyricsScreen(
                                        lyrics = uiState.lyrics,
                                        lyricLines = uiState.lyricLines,
                                        isLyricsLoading = uiState.isLyricsLoading,
                                        lyricsFailure = uiState.lyricsFailure,
                                        lyricsError = uiState.lyricsError,
                                        songId = song.id,
                                        currentPositionFlow = currentPositionFlow,
                                        playbackState = playbackState,
                                        lyricsCandidates = uiState.lyricsCandidates,
                                        isSearchingCandidates = uiState.isSearchingCandidates,
                                        lyricsSearchError = uiState.lyricsSearchError,
                                        onSeek = playerActions.onSeek,
                                        onClose = { showLyrics = false },
                                        onFetchLyrics = { playerActions.onFetchLyrics(true) },
                                        onGoogleSearch = {
                                            val query = "${song.artist} ${song.title} lyrics"
                                            uriHandler.openUri("https://www.google.com/search?q=${android.net.Uri.encode(query)}")
                                        },
                                        onSaveLyrics = playerActions.onSaveLyrics,
                                        isSavingLyrics = isSavingLyrics,
                                        onSearchManually = playerActions.onSearchLyricsManually,
                                        onSelectCandidate = playerActions.onSelectLyricsCandidate,
                                        onDismissSearch = playerActions.onDismissLyricsSearch,
                                        onPlayPause = playerActions.onPlayPause,
                                        onNext = playerActions.onNext,
                                        onPrevious = playerActions.onPrevious,
                                        // Fondo SÓLIDO surfaceContainer (el mismo del ajuste "fondo
                                        // sólido"), no el degradado del álbum: el overlay de letras se
                                        // lee como una superficie neutra estable. Contraste = onSurface.
                                        backgroundColor = solidBackgroundColor,
                                        contentColor = MaterialTheme.colorScheme.onSurface,
                                        accentColor = playButtonColor
                                    )
    }

    if (uiState.debugInfo != null) {
        ColorPickerDialog(
            info = uiState.debugInfo,
            onColorSelected = { color ->
                navigationActions.onSelectColor(color)
            },
            onDismiss = navigationActions.onClearDebugInfo
        )
    }

    // Añadir la canción actual a una lista de reproducción (desde el overflow de la barra).
    if (showAddToPlaylist) {
        AddToPlaylistBottomSheet(
            playlists = playlists,
            onPlaylistSelected = { playlistId ->
                playerActions.onAddToPlaylist(playlistId, song.id)
                showAddToPlaylist = false
            },
            onCreateNewPlaylist = {
                showAddToPlaylist = false
                showCreatePlaylistDialog = true
            },
            onDismiss = { showAddToPlaylist = false }
        )
    }

    if (showSleepTimerSheet) {
        SleepTimerSheet(
            state = sleepTimer,
            onStart = playerActions.onStartSleepTimer,
            onCancel = playerActions.onCancelSleepTimer,
            onDismiss = { showSleepTimerSheet = false }
        )
    }

    if (showCreatePlaylistDialog) {
        CreatePlaylistDialog(
            onDismiss = { showCreatePlaylistDialog = false },
            onConfirm = { name ->
                playerActions.onCreatePlaylist(name)
                showCreatePlaylistDialog = false
            }
        )
    }
}

/**
 * Ficha del audio que suena: contenedor + los datos que distinguen una copia buena de una mala.
 * Los tres numéricos son OPCIONALES porque no siempre se pueden saber: en streaming no se abre el
 * archivo (costaría red), y `SAMPLERATE`/`BITS_PER_SAMPLE` de `MediaMetadataRetriever` existen
 * desde API 31 — por debajo, o si el contenedor no los declara, el chip simplemente muestra menos.
 */
@Immutable
internal data class AudioFormatInfo(
    val format: String,
    val bitrateKbps: Int? = null,
    val sampleRateHz: Int? = null,
    val bitsPerSample: Int? = null
) {
    /** ¿Hay algo que enseñar además del contenedor? Si no, el modo detallado no cambia nada. */
    val hasDetails: Boolean get() = bitrateKbps != null || sampleRateHz != null || bitsPerSample != null
}

@Composable
private fun rememberAudioFormat(path: String, title: String): AudioFormatInfo {
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val format by produceState(initialValue = AudioFormatInfo(UNKNOWN_FORMAT), key1 = path, key2 = title) {
        value = withContext(Dispatchers.IO) {
            try {
                // 0. Fuente LOCAL (SAF): el content:// no tiene extensión, hay que leer el MIME
                // con la sobrecarga Context+Uri.
                if (path.startsWith("content://")) {
                    val retriever = MediaMetadataRetriever()
                    return@withContext try {
                        retriever.setDataSource(appContext, android.net.Uri.parse(path))
                        retriever.readFormatInfo()
                    } catch (_: Exception) {
                        AudioFormatInfo(UNKNOWN_FORMAT)
                    } finally {
                        try { retriever.release() } catch (_: Exception) {}
                    }
                }
                // 1. Para archivos locales: leer MIME real del archivo
                if (path.startsWith("file://")) {
                    val filePath = path.removePrefix("file://")
                    val retriever = MediaMetadataRetriever()
                    try {
                        // FD, no setDataSource(String): esa sobrecarga hace Uri.parse sin validar
                        // y los ':' de los ids namespaced en el nombre la rompen (EINVAL).
                        java.io.FileInputStream(filePath).use { retriever.setDataSource(it.fd) }
                        retriever.readFormatInfo()
                    } catch (_: Exception) {
                        // Fallback a extensión del archivo
                        AudioFormatInfo(java.io.File(filePath).extension.uppercase().ifEmpty { UNKNOWN_FORMAT })
                    } finally {
                        try { retriever.release() } catch (_: Exception) {}
                    }
                }
                // 2. Para streaming: extensión del path, luego del título (nombre original del
                // archivo). SIN abrir el recurso: el retriever sobre http descargaría cabeceras
                // por red cada vez que cambia la canción, y el detalle no vale ese precio.
                else {
                    AudioFormatInfo(
                        detectFormatByExtension(path) ?: detectFormatByExtension(title) ?: UNKNOWN_FORMAT
                    )
                }
            } catch (_: Exception) {
                AudioFormatInfo(UNKNOWN_FORMAT)
            }
        }
    }
    return format
}

/**
 * Lee contenedor + ficha técnica de un retriever YA posicionado sobre el recurso. Las claves de
 * frecuencia y profundidad son API 31+; por debajo se devuelven nulas y el chip enseña lo que hay.
 */
private fun MediaMetadataRetriever.readFormatInfo(): AudioFormatInfo {
    val format = mimeToFormat(extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE))
    // El bitrate viene en bits por segundo; el chip habla en kbps.
    val bitrateKbps = extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
        ?.toIntOrNull()
        ?.takeIf { it > 0 }
        ?.let { (it + BPS_PER_KBPS / 2) / BPS_PER_KBPS }
    val sampleRate: Int?
    val bitsPerSample: Int?
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        sampleRate = extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)
            ?.toIntOrNull()?.takeIf { it > 0 }
        bitsPerSample = extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)
            ?.toIntOrNull()?.takeIf { it > 0 }
    } else {
        sampleRate = null
        bitsPerSample = null
    }
    return AudioFormatInfo(format, bitrateKbps, sampleRate, bitsPerSample)
}

/** Etiqueta cuando no se pudo determinar el contenedor. */
private const val UNKNOWN_FORMAT = "AUDIO"

private const val BPS_PER_KBPS = 1000

private fun mimeToFormat(mime: String?): String = when {
    mime == null -> "AUDIO"
    "flac" in mime || "x-flac" in mime -> "FLAC"
    "mpeg" in mime -> "MP3"
    "mp4" in mime || "m4a" in mime || "x-m4a" in mime -> "M4A"
    "wav" in mime || "x-wav" in mime -> "WAV"
    "ogg" in mime || "vorbis" in mime -> "OGG"
    "aac" in mime || "x-aac" in mime -> "AAC"
    "opus" in mime -> "OPUS"
    else -> "AUDIO"
}

private fun detectFormatByExtension(text: String): String? {
    val lower = text.lowercase()
    return when {
        lower.endsWith(".flac") -> "FLAC"
        lower.endsWith(".mp3") -> "MP3"
        lower.endsWith(".wav") -> "WAV"
        lower.endsWith(".m4a") -> "M4A"
        lower.endsWith(".aac") -> "AAC"
        lower.endsWith(".ogg") -> "OGG"
        else -> null
    }
}
