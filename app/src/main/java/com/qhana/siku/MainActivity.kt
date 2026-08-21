package com.qhana.siku

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qhana.siku.data.repository.ArtworkRepository
import com.qhana.siku.data.util.AppLogger
import com.qhana.siku.data.util.JankProbe
import com.qhana.siku.data.util.SnackbarManager
import com.qhana.siku.service.MusicPlaybackService
import com.qhana.siku.ui.MusicPlayerScreen
import com.qhana.siku.ui.theme.AppColors
import com.qhana.siku.ui.theme.AppSurface
import com.qhana.siku.ui.theme.MusicPlayerTheme
import com.qhana.siku.ui.theme.paletteStyleFromName
import com.qhana.siku.ui.viewmodel.AuthViewModel
import com.qhana.siku.ui.viewmodel.PlaybackViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Única Activity de la app. Se limita a lo que SOLO una Activity puede hacer: splash,
 * edge-to-edge, window flags (keep-screen-on) y deep link del NowPlaying desde la
 * notificación. Toda la composición vive en [MusicPlayerScreen] (ui/MusicPlayerScreen.kt)
 * — el NavHost en ui/navigation/AppNavHost.kt y la capa flotante del reproductor en
 * ui/PlayerOverlay.kt, coordinadas por MusicAppState.
 *
 * **No hay puerta de permisos al arrancar.** La app entra directa al onboarding y cada permiso
 * se pide donde se necesita: el de audio, solo si el usuario elige escanear todo el dispositivo
 * (`rememberDeviceScanActivator`); el de notificaciones, al terminar el onboarding y sin bloquear.
 * Cuando existía la puerta, denegar CUALQUIERA de los dos —incluido el de notificaciones, que no
 * da acceso a nada— dejaba al usuario encerrado en la pantalla de permisos sin salida.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var appLogger: AppLogger

    @Inject
    lateinit var snackbarManager: SnackbarManager

    // Misma instancia que resuelve hiltViewModel() dentro de MusicPlayerScreen
    // (ambos usan el ViewModelStore de la activity). Se necesita aquí para la
    // condición de retención del splash.
    private val authViewModel: AuthViewModel by viewModels()

    // Solo para decidir si el splash tiene que esperar a MSAL (ver onCreate). Sus dos consultas
    // leen la caché en memoria de MusicPreferences, así que responden sin tocar disco.
    @Inject
    lateinit var localMusicSource: com.qhana.siku.data.source.LocalMusicSource

    private var pendingNowPlayingNavigation by mutableStateOf(false)
    private var userWantsScreenOn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        // Sonda de frames largos (apagada salvo `adb shell setprop log.tag.JankProbe DEBUG`; ver JankProbe).
        JankProbe.start()
        // Retiene el splash del sistema hasta que MSAL resuelva la sesión (isLoggedIn deja de ser
        // null); el NavHost compone entonces directo en el destino correcto sin flash del Login.
        // tryRestoreSession tiene su propio timeout, no cuelga.
        //
        // Pero SOLO si esa respuesta puede cambiar el destino. Con una fuente local ya configurada
        // el arranque es la biblioteca haya sesión o no (`hasAnySource = loggedIn || hasLocalSource`
        // en MusicPlayerScreen), así que esperar a que una librería de autenticación lea sus
        // credenciales de disco es tiempo de splash regalado — y quien nunca conectó OneDrive lo
        // pagaba en cada arranque en frío sin recibir nada a cambio.
        val hasLocalSource =
            localMusicSource.scansWholeDevice() || localMusicSource.folderUris().isNotEmpty()
        splashScreen.setKeepOnScreenCondition {
            !hasLocalSource && authViewModel.isLoggedIn.value == null
        }
        appLogger.lifecycle("onCreate() - savedInstanceState=${savedInstanceState != null}")

        enableEdgeToEdge(
            navigationBarStyle = SystemBarStyle.auto(
                AndroidColor.TRANSPARENT,
                AndroidColor.TRANSPARENT
            )
        )

        pendingNowPlayingNavigation = savedInstanceState == null &&
            intent?.action == MusicPlaybackService.ACTION_SHOW_NOW_PLAYING

        setContent {
            // Acento del álbum en reproducción como seed del ColorScheme global, para
            // homogeneizar el acento en toda la app (fallback al sistema sin reproducción).
            // Igual que el NowPlaying: secondary en modo oscuro, primary en claro (respeta
            // el override manual de color, que es granular por modo).
            val themePlaybackViewModel: PlaybackViewModel = hiltViewModel()
            val themeNowPlaying by themePlaybackViewModel.nowPlayingUiState.collectAsStateWithLifecycle()
            val isThemeDark = isSystemInDarkTheme()
            val chosenArgb = themeNowPlaying.albumColors?.let { if (isThemeDark) it.secondary else it.primary }
            // Carátula ACROMÁTICA (sin matiz: negro/blanco/gris, p. ej. Stream of Consciousness):
            // NO hereda el acento anterior ni deja que el PaletteStyle le invente un matiz (rosa)
            // — usa un esquema NEUTRO en grises (monochrome) que solo sigue el claro/oscuro.
            //
            // El veredicto lo da el CROMA HCT (ArtworkRepository.isAchromatic), no la saturación
            // HSV: esta última depende del tono, así que el mismo acento se declaraba cromático en
            // tema claro y gris en oscuro (ver el KDoc — era el bug de Train of Thought).
            //
            // NO aplica a un color elegido A MANO: ahí el usuario ya decidió, y muchos colores
            // legítimos de una carátula caen bajo cualquier umbral. Filtrarlos hacía que elegirlos
            // en el selector no cambiara NADA del tema, que es como se detectó esto.
            // Paréntesis obligatorios: `?:` liga MENOS que `&&`, así que sin ellos el compilador
            // lee `(!manual && Boolean?) ?: false` y no tipa.
            val isAchromatic = !themeNowPlaying.hasManualColor &&
                (chosenArgb?.let { ArtworkRepository.isAchromatic(it) } ?: false)
            // Seed CROMÁTICO: color del álbum solo si tiene croma. Se mantiene el ÚLTIMO cromático
            // mientras se extraen los colores de la nueva canción — al cambiar de canción el UiState
            // nace con albumColors=null un instante y, sin esto, el tema saltaba al dynamic del
            // sistema (color ajeno, p. ej. celeste del wallpaper) antes del color real.
            val rawSeed = if (chosenArgb != null && !isAchromatic) Color(chosenArgb) else null
            var lastSeed by remember { mutableStateOf<Color?>(null) }
            LaunchedEffect(rawSeed) { if (rawSeed != null) lastSeed = rawSeed }
            // El puente `lastSeed` SOLO es válido mientras la extracción está en curso, y eso solo
            // ocurre si la canción TIENE carátula. Una canción SIN carátula (albumArtUri null) nunca
            // producirá color —`ArtworkRepository.getAlbumColors` corta en null de forma PERMANENTE,
            // no transitoria—, así que heredar `lastSeed` dejaba CLAVADO el acento de la canción
            // anterior. Una canción sin carátula usa un NEUTRO FIJO (gris puro croma 0 → esquema
            // Monochrome, la misma ruta que una carátula acromática), no dynamic/baseline: así el
            // tema es estable y no depende del wallpaper. Sin esto se conservaba el color previo.
            val currentSong = themeNowPlaying.song
            val currentSongHasArt = currentSong?.albumArtUri != null
            val noArtNeutral = currentSong != null && !currentSongHasArt && rawSeed == null && !isAchromatic
            // Acromática o sin carátula → gris neutro con estilo Monochrome (no hereda nada).
            // Cromática → el color (o el último mientras extrae, SOLO con carátula).
            // Cold start sin canción → dynamic/baseline.
            val seedColor = when {
                isAchromatic -> chosenArgb?.let { Color(it) }
                rawSeed != null -> rawSeed
                currentSongHasArt -> lastSeed
                noArtNeutral -> Color(ArtworkRepository.NEUTRAL_SEED_ARGB)
                else -> null
            }
            val useMonochrome = isAchromatic || noArtNeutral
            // Estilo elegido en Ajustes → Apariencia. Se observa del DataStore: cambiarlo
            // repinta el tema en vivo, sin recrear la Activity ni recompilar para probar otro.
            val paletteStyleName by themePlaybackViewModel.themePaletteStyle.collectAsStateWithLifecycle()
            // Izados AQUÍ y no dentro de MusicPlayerScreen, su dueño natural. El motivo original
            // era que el TEMA los leía, para congelar el fundido del esquema con el reproductor
            // abierto; eso se fue el 20 ago 2026 junto con su causa (ver `MusicPlayerTheme`). Se
            // quedan izados porque `MusicPlayerScreen` los recibe por parámetro y el estado del
            // reproductor tiene que sobrevivir a la recreación de la Activity.
            val playerExpandedState = rememberSaveable { mutableStateOf(false) }
            // La fila que se está PREPARANDO para abrir el player (ver `MusicAppState.openPlayer`):
            // el frame ANTERIOR a expandir. Transitorio, no `rememberSaveable`: nunca hay que
            // restaurar una preparación a medias.
            val pendingRowOriginState = remember { mutableStateOf<String?>(null) }
            MusicPlayerTheme(
                seedColor = seedColor,
                monochrome = useMonochrome,
                paletteStyle = paletteStyleFromName(paletteStyleName)
            ) {
                AppSurface(
                    modifier = Modifier.fillMaxSize(),
                    color = AppColors.background
                ) {
                    MusicPlayerScreen(
                        snackbarManager = snackbarManager,
                        pendingNowPlayingNavigation = pendingNowPlayingNavigation,
                        onNavigationHandled = { pendingNowPlayingNavigation = false },
                        onKeepScreenOnChanged = { enabled ->
                            userWantsScreenOn = enabled
                            updateKeepScreenOn(enabled)
                        },
                        playerExpandedState = playerExpandedState,
                        pendingRowOriginState = pendingRowOriginState
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == MusicPlaybackService.ACTION_SHOW_NOW_PLAYING) {
            pendingNowPlayingNavigation = true
        }
    }

    override fun onStart() {
        super.onStart()
        if (userWantsScreenOn) updateKeepScreenOn(true)
        restoreSystemBars()
    }

    private fun restoreSystemBars() {
        val windowInsetsController = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
    }

    override fun onStop() {
        super.onStop()
        updateKeepScreenOn(false)
    }

    private fun updateKeepScreenOn(enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

}
