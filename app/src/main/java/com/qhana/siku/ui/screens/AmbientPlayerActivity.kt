package com.qhana.siku.ui.screens

import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import androidx.compose.ui.res.stringResource
import com.qhana.siku.R
import com.qhana.siku.data.model.PlaybackState
import com.qhana.siku.data.model.Song
import com.qhana.siku.data.repository.IMusicRepository
import com.qhana.siku.player.MusicController
import com.qhana.siku.ui.components.UnifiedProgressBar
import com.qhana.siku.ui.theme.AppSurface
import com.qhana.siku.ui.theme.MusicPlayerTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import androidx.compose.foundation.basicMarquee
import com.qhana.siku.ui.components.MaterialSymbol

@AndroidEntryPoint
class AmbientPlayerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_TIMEOUT_MINUTES = "timeout_minutes"
        const val NO_TIMEOUT = -1
    }

    @Inject
    lateinit var musicController: MusicController

    @Inject
    lateinit var repository: IMusicRepository

    private var countDownTimer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val windowInsetsController = WindowInsetsControllerCompat(window, window.decorView)
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = 0.01f }
        val timeoutMinutes = intent.getIntExtra(EXTRA_TIMEOUT_MINUTES, NO_TIMEOUT)
        if (timeoutMinutes > 0) startTimeout(timeoutMinutes)
        setContent {
            MusicPlayerTheme(darkTheme = true) {
                AmbientPlayerScreen(musicController = musicController, repository = repository, onDoubleTap = { finish() })
            }
        }
    }

    private fun startTimeout(minutes: Int) {
        // El intervalo de tick ES la duración total: `onTick` está vacío —aquí solo interesa el
        // final— y con el segundo literal que había antes el timer despertaba la CPU una vez por
        // segundo durante todo el modo ambiente para no hacer nada. De paso desaparece el
        // `* 60 * 1000` a mano: la conversión la nombra `TimeUnit`.
        val totalMs = TimeUnit.MINUTES.toMillis(minutes.toLong())
        countDownTimer = object : CountDownTimer(totalMs, totalMs) {
            override fun onTick(millisUntilFinished: Long) {}
            override fun onFinish() { finish() }
        }.start()
    }

    override fun onDestroy() {
        countDownTimer?.cancel()
        super.onDestroy()
    }
}

@Composable
fun AmbientPlayerScreen(
    musicController: MusicController,
    repository: IMusicRepository,
    onDoubleTap: () -> Unit
) {
    val controllerSong by musicController.currentSong.collectAsStateWithLifecycle()
    val controllerSongId = controllerSong?.id
    val currentSong by remember(controllerSongId) {
        if (controllerSongId != null) repository.getSongByIdFlow(controllerSongId) else flowOf(null)
    }.collectAsStateWithLifecycle(initialValue = controllerSong)
    val playbackState by musicController.playbackState.collectAsStateWithLifecycle()
    val currentPosition by musicController.currentPosition.collectAsStateWithLifecycle()
    val duration by musicController.duration.collectAsStateWithLifecycle()
    // Cache del Flow: repository.getFavoritesIds() crea un nuevo Flow en cada recomposición
    // (por el .map interno). Sin remember, collectAsStateWithLifecycle resuscribe en cada frame
    // y el valor cae a emptySet entre emisiones, por eso el ícono nunca se veía relleno.
    val favoritesFlow = remember(repository) { repository.getFavoritesIds() }
    val favorites by favoritesFlow.collectAsStateWithLifecycle(initialValue = emptySet())
    val isFavorite = currentSong?.let { it.id in favorites } ?: false
    val isPlaying = playbackState == PlaybackState.PLAYING
    val isBuffering = playbackState == PlaybackState.BUFFERING
    val scope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) {
        detectTapGestures(onDoubleTap = { onDoubleTap() })
    }) {
        Row(modifier = Modifier.fillMaxSize().padding(48.dp), horizontalArrangement = Arrangement.spacedBy(48.dp), verticalAlignment = Alignment.CenterVertically) {
            AmbientAlbumArt(song = currentSong, modifier = Modifier.weight(0.45f).aspectRatio(1f))
            Column(modifier = Modifier.weight(0.55f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                // Roles del spec, no tamaños sueltos: `headlineMediumEmphasized` son exactamente
                // los 28sp/Medium que estaban escritos a mano, y `bodyLarge` los 16sp del artista.
                Text(text = currentSong?.title ?: stringResource(R.string.ambient_no_playback), color = Color.White, style = MaterialTheme.typography.headlineMediumEmphasized, maxLines = 1, overflow = TextOverflow.Visible, modifier = Modifier.basicMarquee(repeatDelayMillis = 10000, initialDelayMillis = 1000))
                Text(text = currentSong?.artist ?: "", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(modifier = Modifier.height(32.dp))
                UnifiedProgressBar(currentPosition = currentPosition, duration = duration, onSeek = { musicController.seekTo(it) }, trackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.2f), textColor = Color.White.copy(alpha = 0.5f), showThumb = true, trackHeight = 4.dp, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(32.dp))
                AmbientControls(
                    isPlaying = isPlaying,
                    isBuffering = isBuffering,
                    isFavorite = isFavorite,
                    onPrevious = { musicController.previous() },
                    onPlayPause = { musicController.playPause() },
                    onNext = { musicController.next() },
                    onToggleFavorite = {
                        currentSong?.let { song -> scope.launch { repository.toggleFavorite(song.id) } }
                    },
                    onExit = onDoubleTap
                )
                Spacer(modifier = Modifier.height(48.dp))
            }
        }
    }
    // Mismo reloj que el bucle de posición de `MusicPlayerScreen` (ver [POSITION_TICK_MS]): esta
    // pantalla es otra vista del MISMO estado, así que su cadencia no es una decisión aparte.
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            musicController.updatePosition()
            delay(POSITION_TICK_MS.toLong())
        }
    }
}

@Composable
private fun AmbientAlbumArt(song: Song?, modifier: Modifier = Modifier) {
    var isImageLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(song) { isImageLoaded = false }
    AppSurface(modifier = modifier, shape = RoundedCornerShape(24.dp), shadowElevation = 16.dp, color = if (isImageLoaded) Color.Transparent else Color(0xFF2A2A2A)) {
        Box(contentAlignment = Alignment.Center) {
            if (!isImageLoaded) MaterialSymbol("music_note", color = Color.White.copy(alpha = 0.2f), modifier = Modifier.fillMaxSize(0.5f))
            if (song?.albumArtUri != null) AsyncImage(model = ImageRequest.Builder(LocalContext.current).data(song.albumArtUri).crossfade(true).build(), contentDescription = stringResource(R.string.common_album_art), contentScale = ContentScale.Crop, onSuccess = { isImageLoaded = true }, onError = { isImageLoaded = false }, modifier = Modifier.fillMaxSize().scale(1.02f))
        }
    }
}

/**
 * Botonera del modo ambiente. Los cinco botones tienen tamaño de REFERENCIA, no fijo: la fila
 * mide lo que le den (una columna al 55% del ancho, y este modo vive en horizontal, donde ese
 * 55% puede ser bastante menos de lo que suman los botones) y si no cabe se escala TODO en
 * bloque —diámetros, glifos, separación y borde— con un mismo factor.
 *
 * Se escala en vez de envolver a dos filas o recortar botones porque el transporte tiene que
 * leerse de un vistazo desde lejos, que es el sentido de la pantalla: cinco controles en una
 * línea, siempre en el mismo sitio. El factor nunca pasa de 1, así que en pantallas anchas
 * quedan exactamente en su tamaño de diseño.
 */
@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun AmbientControls(isPlaying: Boolean, isBuffering: Boolean, isFavorite: Boolean, onPrevious: () -> Unit, onPlayPause: () -> Unit, onNext: () -> Unit, onToggleFavorite: () -> Unit, onExit: () -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val naturalWidth = AmbientPlayButtonSize +
            AmbientSideButtonSize * AMBIENT_SIDE_BUTTON_COUNT +
            AmbientControlsGap * AMBIENT_CONTROLS_GAP_COUNT
        val scale = if (naturalWidth > maxWidth) maxWidth / naturalWidth else 1f
        // Los glifos van en sp (MaterialSymbol es tipografía), pero su tamaño de referencia se
        // declara en dp junto al del botón que los contiene: son la misma medida física.
        fun glyph(base: Dp) = (base.value * scale).sp

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AmbientControlsGap * scale, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val sideSize = AmbientSideButtonSize * scale
            IconButton(onClick = onExit, modifier = Modifier.size(sideSize)) { MaterialSymbol(icon = "exit_to_app", color = Color.White, modifier = Modifier.size(AmbientExitIconSize * scale), size = glyph(AmbientExitIconSize)) }
            IconButton(onClick = onPrevious, modifier = Modifier.size(sideSize)) { MaterialSymbol(icon = "skip_previous", color = Color.White, modifier = Modifier.size(AmbientSkipIconSize * scale), size = glyph(AmbientSkipIconSize)) }
            // OutlinedIconButton real (M3 Expressive: shape-morph al presionar) con el borde blanco.
            OutlinedIconButton(
                onClick = onPlayPause,
                shapes = IconButtonDefaults.shapes(),
                colors = IconButtonDefaults.outlinedIconButtonColors(contentColor = Color.White),
                border = androidx.compose.foundation.BorderStroke(AmbientPlayBorderWidth * scale, Color.White),
                modifier = Modifier.size(AmbientPlayButtonSize * scale)
            ) {
                if (isBuffering) LoadingIndicator(modifier = Modifier.size(AmbientBufferingIndicatorSize * scale), color = Color.White)
                else MaterialSymbol(icon = if (isPlaying) "pause" else "play_arrow", color = Color.White, size = glyph(AmbientPlayIconSize), fill = true)
            }
            IconButton(onClick = onNext, modifier = Modifier.size(sideSize)) { MaterialSymbol(icon = "skip_next", color = Color.White, modifier = Modifier.size(AmbientSkipIconSize * scale), size = glyph(AmbientSkipIconSize)) }
            IconButton(onClick = onToggleFavorite, modifier = Modifier.size(sideSize)) { MaterialSymbol(icon = "favorite", fill = isFavorite, color = Color.White, modifier = Modifier.size(AmbientFavoriteIconSize * scale), size = glyph(AmbientFavoriteIconSize)) }
        }
    }
}

// --- Geometría de referencia de [AmbientControls] (se escala en bloque si no cabe) ---
/** Diámetro del play: el botón mayor de la fila. */
private val AmbientPlayButtonSize = 80.dp
/** Diámetro de los cuatro secundarios: salir, anterior, siguiente y favorito. */
private val AmbientSideButtonSize = 64.dp
/** Cuántos secundarios hay; entra en el cálculo del ancho natural de la fila. */
private const val AMBIENT_SIDE_BUTTON_COUNT = 4
/** Separación entre botones. */
private val AmbientControlsGap = 20.dp
/** Huecos entre los cinco botones (uno menos que botones). */
private const val AMBIENT_CONTROLS_GAP_COUNT = 4
/** Grosor del aro del play. */
private val AmbientPlayBorderWidth = 5.dp
/** Diámetro del indicador de carga que sustituye al glifo del play mientras bufferea. */
private val AmbientBufferingIndicatorSize = 36.dp
// Glifos: manda el del play, los saltos van un peldaño por debajo, y salir/favorito cierran.
private val AmbientPlayIconSize = 48.dp
private val AmbientSkipIconSize = 40.dp
private val AmbientExitIconSize = 32.dp
private val AmbientFavoriteIconSize = 28.dp