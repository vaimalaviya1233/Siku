package com.qhana.siku.ui

import android.view.View
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
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
/**
 * De dónde sale la carátula al abrir el reproductor. Son tres coreografías distintas y cada una es
 * correcta en su caso; lo que no vale es aplicar la de otro, porque un shared element **sin pareja
 * se pinta en el overlay del `SharedTransitionScope`** —que cuelga de la raíz y NO recibe el
 * `graphicsLayer` del slide— o sea quieto en su posición final mientras el reproductor sube.
 */
enum class PlayerArtOrigin {
    /**
     * Sin origen: la carátula sube CON el resto del contenido, como una pieza más de la pantalla.
     * Es lo correcto cuando lo que disparó la reproducción no enseña la portada (chips del inicio,
     * deep link de la notificación).
     */
    NONE,

    /**
     * Desde la PÍLDORA (el MiniPlayer): la portada ya está en la barra y viaja de ahí al centro.
     * *Container transform* clásico — "esta barra se convierte en el reproductor".
     */
    PILL,

    /**
     * Desde la FILA de la canción en una lista. La fila origen es siempre la de la canción ACTIVA
     * (`MusicController.announceSelection` la fija en el frame del tap), así que no hace falta
     * pasar ningún id: cada `SongItem` sabe si es el origen preguntando si es la fila que suena.
     *
     * La fila **oculta su carátula** mientras dura el viaje, y eso no es un efecto: es lo que la
     * convierte en ORIGEN. Compose exige que de las dos puntas de una key solo UNA sea destino, y
     * una fila que se queda visible siempre lo es — con ella visible habría dos destinos y ninguna
     * animación. Ocultarla es además lo que hace el *container transform* de Material.
     */
    ROW
}

/** Estado vacío por defecto: una instancia estable, para que el local nunca cambie de objeto. */
private val NoArtOrigin: State<String?> = mutableStateOf(null)

/**
 * Id de la canción cuya FILA debe comportarse como origen de la carátula ahora mismo (null = ninguna).
 * Lo publica [com.qhana.siku.ui.MusicPlayerScreen] y lo consume `SongItem`, que está a ocho pantallas
 * de distancia — pasarlo por parámetro habría obligado a tocar todas las firmas intermedias para un
 * detalle que solo le importa a la carátula.
 *
 * **Es un `State` dentro de un local ESTÁTICO, y no un `Boolean` en uno normal, por rendimiento
 * medido en el sitio exacto donde dolía.** Con un Boolean, cada fila leía el valor durante su
 * composición, así que al abrir y al cerrar el reproductor **recomponían TODAS las filas visibles a
 * la vez** — unas diez, justo en el frame que arranca la transición y en el que la termina, que es
 * donde el usuario notaba el tirón. Publicando el id en un `State` estable, la fila lo lee dentro de
 * un `derivedStateOf` y solo recompone aquella cuyo veredicto cambia de verdad: una.
 */
val LocalArtOriginSongId = staticCompositionLocalOf { NoArtOrigin }

/**
 * El [SharedTransitionScope] de la app, publicado por la misma razón que [LocalArtOriginSongId]:
 * las filas de lista lo necesitan para declarar el shared element de su carátula y están demasiado
 * abajo como para recibirlo por parámetro.
 *
 * **No se lee directamente: se lee con [appSharedTransitionScope]**, que lo anula fuera de la
 * ventana en la que nació.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalAppSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }

/**
 * La `View` raíz de la ventana donde se montó el [LocalAppSharedTransitionScope]. La publica el
 * mismo sitio que el scope y solo la consume [appSharedTransitionScope].
 */
private val LocalSharedTransitionOwnerView = staticCompositionLocalOf<View?> { null }

/**
 * El scope compartido, o `null` si quien pregunta vive en OTRA ventana.
 *
 * **Un `SharedTransitionScope` no cruza ventanas.** Su overlay y sus medidas cuelgan del árbol de
 * layout de la ventana donde se declaró el `SharedTransitionLayout`; un `Dialog`, un `Popup` o un
 * `ModalBottomSheet` crean su PROPIO `AndroidComposeView`, así que un shared element declarado ahí
 * dentro le entrega coordenadas de otro árbol y Compose lanza
 * `IllegalArgumentException: layouts are not part of the same hierarchy` — un FC, no una animación
 * fea. Pasó con el overlay de búsqueda (`ExpandedFullScreenContainedSearchBar` es un diálogo
 * edge-to-edge y sus resultados son las mismas filas `SongItem` de la biblioteca).
 *
 * El gate va AQUÍ, en la lectura, y no en cada ventana que se abra: el local viaja a todas por
 * construcción, así que confiar en que cada `Dialog`/sheet nuevo se acuerde de anularlo es dejar la
 * misma trampa armada para la próxima. `LocalView` cambia de valor en cada ventana, que es
 * exactamente la pregunta que hay que hacer.
 *
 * **Ojo, esto NO cubre el otro motivo para anular el scope**: dos listas de la MISMA ventana que
 * repitan la key por-canción (la hoja de la cola sobre la lista de detrás). Ese caso sigue
 * necesitando su `CompositionLocalProvider(LocalAppSharedTransitionScope provides null)` explícito
 * — ver `PlayerOverlay`. Son problemas distintos con soluciones distintas.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun appSharedTransitionScope(): SharedTransitionScope? {
    val scope = LocalAppSharedTransitionScope.current ?: return null
    return scope.takeIf { LocalSharedTransitionOwnerView.current === LocalView.current }
}

/**
 * Publica el scope compartido junto con la ventana a la que pertenece. Único punto de entrada:
 * proveer el local a mano dejaría el gate de [appSharedTransitionScope] sin la referencia con la
 * que comparar y anularía el shared element en TODAS partes (fallo silencioso: las animaciones
 * simplemente dejarían de ocurrir).
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun ProvideAppSharedTransitionScope(
    scope: SharedTransitionScope,
    artOriginSongId: State<String?>,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(
        LocalAppSharedTransitionScope provides scope,
        LocalSharedTransitionOwnerView provides LocalView.current,
        LocalArtOriginSongId provides artOriginSongId,
        content = content
    )
}

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
     * De dónde nace la carátula del reproductor en la última apertura. Ver [PlayerArtOrigin].
     */
    var playerArtOrigin by mutableStateOf(PlayerArtOrigin.NONE)
        private set

    /** Atajo de lectura: la carátula viaja desde la píldora. */
    val playerOpenedFromPill: Boolean get() = playerArtOrigin == PlayerArtOrigin.PILL

    /**
     * Abre el reproductor declarando de DÓNDE sale la carátula (ver [PlayerArtOrigin]). El default
     * es [PlayerArtOrigin.NONE] a propósito: quien no sepa ofrecer un origen real no debe inventarlo
     * — un shared element sin pareja se pinta en el overlay y se queda quieto.
     */
    fun openPlayer(origin: PlayerArtOrigin = PlayerArtOrigin.NONE) {
        playerArtOrigin = origin
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
