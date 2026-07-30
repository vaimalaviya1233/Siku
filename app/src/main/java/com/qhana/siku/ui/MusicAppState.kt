package com.qhana.siku.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.qhana.siku.ui.navigation.Screen

/**
 * State holder de la app (patrón `rememberAppState` de Now in Android): agrupa el
 * NavController, el estado de la capa flotante (pill ↔ player, FAB contextual, hojas
 * globales) y los flags derivados de la ruta actual.
 *
 * Este estado vive AQUÍ y no en las rutas del NavHost a propósito: la capa del reproductor
 * y el FAB se pintan SOBRE el NavHost (una sola instancia compartida entre pantallas), y un
 * `hiltViewModel()` dentro de una ruta resolvería al scope del NavBackStackEntry — una
 * instancia nueva por pantalla que no puede ser dueña de estado global.
 */
@Stable
class MusicAppState(
    val navController: NavHostController,
    playerExpandedState: MutableState<Boolean>
) {
    /**
     * Reproductor expandido = NowPlaying a pantalla completa sobre el NavHost.
     *
     * Para ABRIRLO se usa [openPlayer], no una asignación directa: hay que decir de dónde viene el
     * gesto (ver [playerOpenedFromPill]). Cerrarlo sí es una asignación normal — el cierre no tiene
     * variantes.
     */
    var playerExpanded by playerExpandedState
        private set

    /**
     * Si la última apertura del reproductor salió de la PÍLDORA (el MiniPlayer) o de otro sitio
     * (una canción de una lista, un chip del inicio, la notificación).
     *
     * Decide de dónde nace la carátula, y son dos coreografías legítimas y distintas:
     *  - **Desde la píldora** → *container transform*: la portada YA está en pantalla, dentro de la
     *    barra, así que viaja de ahí al centro del reproductor como shared element. Es el gesto de
     *    "esta barra se convierte en el reproductor".
     *  - **Desde cualquier otro sitio** → la carátula sube CON el resto del contenido, como una
     *    pieza más del reproductor que entra deslizando. No hay nada de dónde morfar: la fila que
     *    se tocó SIGUE en pantalla detrás del player, así que nada sale de ella (y por eso tampoco
     *    puede ser el origen de un shared element: Compose exige que de las dos puntas de una key
     *    solo UNA sea destino, y una fila que se queda visible nunca deja de serlo).
     *
     * Sin esta distinción, abrir desde una lista pintaba la carátula en el overlay del
     * `SharedTransitionScope` —que cuelga de la raíz y NO recibe el `graphicsLayer` del slide—, o
     * sea quieta en su posición final mientras el resto del reproductor subía por debajo.
     */
    var playerOpenedFromPill by mutableStateOf(false)
        private set

    /**
     * Abre el reproductor. [fromPill] SOLO lo pone el MiniPlayer (tap o arrastre hacia arriba);
     * todo lo demás —listas, chips del inicio, deep link de la notificación— abre sin origen.
     */
    fun openPlayer(fromPill: Boolean = false) {
        playerOpenedFromPill = fromPill
        playerExpanded = true
    }

    /** Cierra el reproductor (back, gesto de arrastre, navegación a artista/álbum). */
    fun collapsePlayer() {
        playerExpanded = false
    }

    /** Hoja "añadir canciones" disparada desde el FAB en detalle de playlist/Favoritos. */
    var showAddSongsSheet by mutableStateOf(false)

    val currentBackStackEntry: NavBackStackEntry?
        @Composable get() = navController.currentBackStackEntryAsState().value

    val currentRoute: String?
        @Composable get() = currentBackStackEntry?.destination?.route

    // --- Navegación (helpers no composables, seguros desde callbacks) ---

    fun navigateBack() {
        navController.popBackStack()
    }

    fun navigateToArtist(name: String) {
        navController.navigate(Screen.ArtistDetail.createRoute(name)) { launchSingleTop = true }
    }

    fun navigateToAlbum(name: String) {
        navController.navigate(Screen.AlbumDetail.createRoute(name)) { launchSingleTop = true }
    }

    fun navigateToGenre(name: String) {
        navController.navigate(Screen.GenreDetail.createRoute(name)) { launchSingleTop = true }
    }

    fun navigate(route: String) {
        navController.navigate(route) { launchSingleTop = true }
    }
}

@Composable
fun rememberMusicAppState(
    navController: NavHostController = rememberNavController(),
    // rememberSaveable: el player abierto sobrevive a rotación/recreación del proceso.
    // Izable por parámetro porque MainActivity necesita leerlo POR ENCIMA del tema:
    // `MusicPlayerTheme(animateColors = ...)` congela la animación del esquema mientras el
    // reproductor está abierto (ahí la coreografía del cambio de canción son los reveals).
    playerExpandedState: MutableState<Boolean> = rememberSaveable { mutableStateOf(false) }
): MusicAppState {
    return remember(navController) { MusicAppState(navController, playerExpandedState) }
}
