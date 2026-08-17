package com.qhana.siku.ui

import android.view.View
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Transition
import androidx.compose.material3.ColorScheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.qhana.siku.data.model.Song
import com.qhana.siku.data.util.JankProbe
import com.qhana.siku.ui.navigation.Screen
import kotlinx.coroutines.flow.StateFlow

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
     * Desde la FILA de la canción en una lista: *container transform* igual que el de la píldora, con
     * otra punta de origen — la superficie de la fila CRECE hasta ser el reproductor y su portada
     * viaja anidada dentro. La fila origen es siempre la de la canción ACTIVA
     * (`MusicController.announceSelection` la fija en el frame del tap), así que no hace falta pasar
     * ningún id: cada fila sabe si es el origen preguntando si es la que suena.
     *
     * La fila **se oculta conservando su hueco** mientras dura el viaje, y eso no es un efecto: es lo
     * que la convierte en ORIGEN. Compose exige que de las dos puntas de una key solo UNA sea destino,
     * y una fila que se queda visible siempre lo es — con ella visible habría dos destinos y ninguna
     * animación. Es además lo que hace `MaterialContainerTransform`, que esconde la vista de origen
     * (con `INVISIBLE`, de ahí lo del hueco). Ver `ContainerTransformOrigin`.
     */
    ROW
}

/**
 * Papel de una superficie de lista (una fila) en el container transform del reproductor. `null` —el
 * caso de todas las filas casi todo el tiempo— es "ninguno": la fila no declara shared element y cuesta
 * lo que un `Box`.
 *
 * ## Por qué hay un papel VISIBLE además del oculto
 *
 * Una punta de shared element solo sirve de ORIGEN si ya estaba compuesta y **colocada** antes de que
 * aparezca su pareja: los bounds iniciales del morph salen de la última colocación de la punta que era
 * destino hasta ese momento (`obtainBoundsFromLastTarget` en `compose-animation`), y una punta que
 * nace en el mismo frame que el reproductor no tiene ninguna. Hasta el 16 ago eso se resolvía
 * declarando la punta de TODAS las filas SIEMPRE, y el precio se pagaba en cada frame de scroll (ver
 * `ContainerTransformOrigin`). Ahora se paga UN frame, y solo al abrir: al tocar una fila,
 * `MusicAppState.openPlayer` no expande el reproductor sino que le da a esa fila el papel [VISIBLE]
 * —declara su punta como destino, sola, y queda colocada en ese frame—; cuando la fila avisa de que
 * está colocada (`onRowPlaced`), el reproductor se expande y la fila pasa a [HIDDEN] sin pasar por
 * `null` en medio, que es lo que conserva la entrada ya medida. Es exactamente la mecánica de la
 * píldora —que está declarada y medida desde mucho antes—, comprimida a un frame para una punta que
 * no puede estar declarada de antemano sin costar por fila.
 *
 * El mismo papel [VISIBLE] vale para el cierre: la fila a la que vuelve el reproductor declara su punta
 * visible mientras la capa se contrae (un DESTINO no necesita bounds previos: los toma de su
 * colocación en ese mismo frame) y la suelta al asentar.
 */
enum class ContainerOriginRole {
    /** Declara su punta y se ve: preparándose para ser origen, o recibiendo el cierre. */
    VISIBLE,

    /** Declara su punta y está oculta: el morph nació de ella y el reproductor la tapa. */
    HIDDEN
}

/**
 * La fila que participa en el morph del reproductor AHORA MISMO: qué canción y si está oculta. Lo
 * calcula [com.qhana.siku.ui.MusicPlayerScreen] a partir del origen congelado del morph, la
 * transición de la capa y la fila en preparación (`MusicAppState.pendingRowOrigin`).
 */
data class RowOrigin(val songId: String, val hidden: Boolean)

/**
 * Lo que una fila de lista necesita para poder ser punta del morph del reproductor: preguntar qué
 * papel le toca ([roleOf]) y avisar de que existe y de que quedó colocada. Lo consumen las dos puntas
 * anidadas de la fila —el CONTENEDOR (`SongRowContainer`) y su CARÁTULA (`SongItem`)— que están a
 * ocho pantallas del sitio donde se decide, de ahí que viaje por [LocalRowOrigin].
 *
 * **El origen va en un `State` y las filas lo leen dentro de un `derivedStateOf`, por rendimiento
 * medido en el sitio exacto donde dolía**: con un valor suelto, al abrir y al cerrar el reproductor
 * recomponían TODAS las filas visibles a la vez —unas diez, justo en el frame que arranca la
 * transición y en el que la termina—. Así solo recompone aquella cuyo papel cambia de verdad.
 *
 * Los avisos son plumbing hacia [MusicAppState] (null en el host por defecto, fuera de la app).
 */
@Stable
class RowOriginHost internal constructor(
    private val current: State<RowOrigin?>,
    private val appState: MusicAppState?
) {
    fun roleOf(songId: String): ContainerOriginRole? {
        val origin = current.value ?: return null
        if (origin.songId != songId) return null
        return if (origin.hidden) ContainerOriginRole.HIDDEN else ContainerOriginRole.VISIBLE
    }

    fun onRowPlaced(songId: String) { appState?.onRowPlaced(songId) }
    fun onRowDisposed(songId: String) { appState?.onRowDisposed(songId) }
}

/** Host vacío por defecto: una instancia estable, para que el local nunca cambie de objeto. */
private val NoRowOrigin = RowOriginHost(mutableStateOf(null), appState = null)

/** Ver [RowOriginHost]. Estático: su valor es un objeto estable que no cambia; lo que cambia va dentro en `State`. */
val LocalRowOrigin = staticCompositionLocalOf { NoRowOrigin }

/**
 * El [SharedTransitionScope] de la app, publicado por la misma razón que [LocalRowOrigin]:
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
    rowOrigin: RowOriginHost,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(
        LocalAppSharedTransitionScope provides scope,
        LocalSharedTransitionOwnerView provides LocalView.current,
        LocalRowOrigin provides rowOrigin,
        content = content
    )
}

/**
 * El [RowOriginHost] de la app: el origen de fila calculado por `MusicPlayerScreen` en un `State`, con
 * los avisos cableados a [appState]. Único constructor público — así el host y el estado que publica
 * nacen juntos.
 */
@Composable
fun rememberRowOriginHost(rowOrigin: State<RowOrigin?>, appState: MusicAppState): RowOriginHost =
    remember(rowOrigin, appState) { RowOriginHost(rowOrigin, appState) }

/**
 * State holder de la app (patrón `rememberAppState` de Now in Android): agrupa el NavController,
 * el estado de la capa flotante (píldora ↔ reproductor, hojas globales) y los flags derivados de
 * la ruta actual.
 *
 * Este estado vive AQUÍ y no en las rutas del NavHost a propósito: la capa del reproductor se
 * pinta SOBRE el NavHost (una sola instancia compartida entre pantallas), y un `hiltViewModel()`
 * dentro de una ruta resolvería al scope del NavBackStackEntry — una instancia nueva por pantalla
 * que no puede ser dueña de estado global.
 */
@Stable
class MusicAppState(
    val navController: NavHostController,
    /**
     * Estado de "player abierto", IZADO desde `MainActivity` porque el TEMA —que envuelve a toda la
     * app— también lo lee: con el player abierto congela la animación del `ColorScheme`
     * (`MusicPlayerTheme(animateColors = …)`).
     *
     * Se recibe el `MutableState` en vez de exponer una copia y espejarla, y eso es load-bearing, no
     * estilo: el espejo se sincronizaba con un `LaunchedEffect`, que corre DESPUÉS de la composición,
     * así que el tema se enteraba **un frame tarde**. En ese frame el seed de la canción nueva ya había
     * cambiado con la animación del esquema todavía encendida, y cada frame de ese fundido recompone
     * TODO lo que hay bajo el `MaterialTheme` sin skipping (ver `animatedScheme` en Theme.kt) — justo
     * encima del container transform que acaba de arrancar. Es exactamente la lección del 15 ago: no
     * gatear una animación con un estado que se actualiza en otro frame que el que la dispara.
     */
    private val playerExpandedState: MutableState<Boolean>,
    /**
     * La fila en PREPARACIÓN (ver [pendingRowOrigin]), izada a `MainActivity` por la misma razón que
     * [playerExpandedState]: el tema se congela también en ese frame. El seed de la canción tocada
     * cambia en el frame de preparación, y si la animación del esquema siguiera encendida ahí, el
     * esquema saldría a medias (roles animados viejos, fijos nuevos) y el árbol entero se
     * recompondría dos veces — en ese frame y en el siguiente con el definitivo.
     */
    private val pendingRowOriginState: MutableState<String?>,
    /**
     * La canción en curso, para anotar de QUÉ FILA nace el reproductor en [openPlayer]. Se lee
     * `.value` en el instante del tap: `MusicController.announceSelection` la fija de forma síncrona
     * en el mismo handler, ANTES de que el caller llame a [openPlayer], así que aquí ya es la tocada.
     * Leerla de un colector de Compose la dejaría a merced de en qué frame llega la emisión.
     */
    private val currentSong: StateFlow<Song?>
) {
    /**
     * Reproductor expandido: la capa del player cubre la pantalla. Es **estado PROPIO**, no la ruta.
     *
     * El player ya NO es una ruta del `NavHost`: es una capa en [com.qhana.siku.ui.PlayerOverlay] que
     * hace `AnimatedContent` con la píldora. Las dos puntas del morph comparten así un **único**
     * `AnimatedContentScope`, que es lo que hace NATIVO el *container transform* (la barra crece hasta
     * ser el player). Como ruta, cada punta vivía en un dueño de transición distinto —el player en el
     * NavHost, la píldora en el overlay— y el `sharedBounds` no cruzaba: se leía como un slide.
     *
     * Se abre con [openPlayer] y se cierra con [collapsePlayer] (o el back del sistema / el gesto de
     * arrastre, que llaman a `onBackClick`). Navegar a un detalle DESDE el player lo colapsa
     * recordando a dónde volver (ver [navigateFromPlayer]).
     *
     * Vive en [playerExpandedState] (izado a `MainActivity`) y no en un `mutableStateOf` propio: hay
     * UN solo escritor y una sola verdad, que es lo que garantiza que el tema y la capa del player
     * cambien EN EL MISMO frame.
     */
    var playerExpanded: Boolean
        get() = playerExpandedState.value
        private set(value) { playerExpandedState.value = value }

    /**
     * De dónde nace la carátula del reproductor en la última apertura. Ver [PlayerArtOrigin].
     * La capa del player lo lee para elegir con qué key declara su shared element.
     */
    var playerArtOrigin by mutableStateOf(PlayerArtOrigin.NONE)
        private set

    /**
     * Id de la FILA de la que nació el reproductor la última vez que se abrió con
     * [PlayerArtOrigin.ROW] (null con cualquier otro origen). Es la canción que estaba anunciada en
     * el instante del tap — ver el KDoc de [currentSong]—, anotada AQUÍ y no leída después de un
     * colector: la fila que se oculta y la key que declara el player tienen que ser la misma desde el
     * primer frame, sin depender de cuándo llegue una emisión.
     *
     * Es la PETICIÓN. Lo que las puntas del morph usan de verdad es [PlayerMorphOrigin], que congela
     * esta petición mientras la transición de la capa está en vuelo — ver [rememberPlayerMorphOrigin].
     */
    var playerOriginSongId: String? by mutableStateOf(null)
        private set

    /**
     * Fila que se está PREPARANDO para ser origen del morph: declara su punta visible y sola durante un
     * frame, y en cuanto avisa de que quedó colocada ([onRowPlaced]) el reproductor se expande.
     * `null` = ninguna. Ver [ContainerOriginRole] para el porqué de este frame.
     */
    var pendingRowOrigin: String?
        get() = pendingRowOriginState.value
        private set(value) {
            pendingRowOriginState.value = value
            pendingRowOriginPlain = value
        }

    /**
     * Copia PLANA de [pendingRowOrigin] para leerla desde el LAYOUT ([onRowPlaced] corre dentro de un
     * `onPlaced`, en cada colocación de cada fila). Leer ahí el `MutableState` registraría el layout de
     * cada fila colocada como dependiente de él, y cada tap (que lo escribe dos veces: al preparar y
     * al expandir) relayoutaría la lista entera — justo en el frame que compone el reproductor.
     */
    private var pendingRowOriginPlain: String? = null

    /**
     * Filas de canción COLOCADAS ahora mismo en la ventana de la app, por id. Lo mantienen las propias
     * filas (`SongRowContainer`: alta en cada `onPlaced`, baja al descomponerse) y lo consulta
     * [openPlayer] para decidir, en el instante del tap y sin esperar a nada, si hay una fila que pueda
     * ser origen. Colocadas y no compuestas: el prefetch de `LazyColumn` compone filas que no se ven, y
     * una fila que no se coloca nunca daría la señal de [onRowPlaced] con la que se abre. Conjunto plano
     * y no estado de snapshot: nadie reacciona a él, se consulta.
     */
    private val placedRows = HashSet<String>()

    /**
     * Entrada del back stack a la que volver para RE-ABRIR el player, cuando se navegó a un detalle
     * ESTANDO el player abierto. `null` = no hay reapertura pendiente. Ver [navigateFromPlayer].
     */
    private var reopenPlayerAtEntryId: String? = null

    /**
     * Abre el reproductor: expande la capa del player, declarando de DÓNDE sale la carátula (ver
     * [PlayerArtOrigin]) para que la capa elija su shared element. El default es [PlayerArtOrigin.NONE]:
     * quien no sabe ofrecer un origen real (chip, notificación) no lo inventa y la carátula sube con el
     * player sin morph.
     *
     * **Con [PlayerArtOrigin.ROW] no expande en el acto: primero PREPARA la fila.** Una punta de shared
     * element solo sirve de origen si ya estaba colocada antes de que aparezca su pareja, y las filas ya
     * no declaran su punta de antemano (costaba en cada frame de scroll — ver [ContainerOriginRole]).
     * Así que se anota la fila en [pendingRowOrigin] —lo que hace que declare su punta, visible y sola—
     * y se espera su aviso de "colocada" ([onRowPlaced]) para expandir: un frame después, por SEÑAL y
     * no por un retardo elegido. Si la fila de la canción no está en pantalla (el play de una cabecera
     * arranca por la primera canción, que puede estar fuera de la vista), no hay a quién esperar y se
     * abre sin origen en el acto.
     *
     * Ya no navega: el player es una capa sobre el NavHost, no una ruta. "Atrás" lo colapsa
     * ([collapsePlayer]); navegar a un artista/álbum DESDE él pasa por [navigateFromPlayer].
     */
    fun openPlayer(origin: PlayerArtOrigin = PlayerArtOrigin.NONE) {
        JankProbe.arm { "openPlayer($origin) song=${currentSong.value?.title}" }
        if (origin == PlayerArtOrigin.ROW) {
            // La fila de origen es la canción ANUNCIADA en este mismo handler (announceSelection corre
            // antes que este método en todos los call sites de ROW). `.value` y no un colector: es lo
            // que garantiza que sea la tocada y no la anterior. Ver [playerOriginSongId].
            val rowId = currentSong.value?.id
            if (rowId != null && rowId in placedRows) {
                playerArtOrigin = PlayerArtOrigin.ROW
                playerOriginSongId = rowId
                pendingRowOrigin = rowId
                return
            }
        }
        pendingRowOrigin = null
        playerArtOrigin = if (origin == PlayerArtOrigin.ROW) PlayerArtOrigin.NONE else origin
        playerOriginSongId = null
        playerExpanded = true
    }

    /**
     * Una fila acaba de quedar COLOCADA (llega desde el layout, en cada colocación: también en cada
     * frame de scroll, así que aquí no se hace más que una inserción en un conjunto). Dos usos:
     * mantener [placedRows], y —si es la fila en preparación— dar por lista su punta: ya tiene bounds
     * que ofrecer y el reproductor puede expandirse. Esas dos escrituras van juntas para que la fila
     * pase de VISIBLE a HIDDEN en la misma composición, sin un frame en `null` que la desdeclare.
     */
    fun onRowPlaced(songId: String) {
        placedRows.add(songId)
        // La copia plana, no el State: esto corre en el layout de cada fila, en cada colocación.
        if (pendingRowOriginPlain != songId) return
        JankProbe.mark { "fila origen colocada → expandir" }
        pendingRowOrigin = null
        playerExpanded = true
    }

    /** Ver [placedRows]: ¿hay una fila de esta canción colocada en pantalla ahora mismo? */
    fun isRowPlaced(songId: String): Boolean = songId in placedRows

    /**
     * Ver [placedRows]. Si la fila que se estaba preparando desaparece antes de avisar (la lista se
     * recompuso justo en ese frame), no hay a quién esperar: se abre sin origen en vez de quedarse
     * esperando a una punta que ya no existe.
     */
    fun onRowDisposed(songId: String) {
        placedRows.remove(songId)
        if (pendingRowOrigin == songId) {
            pendingRowOrigin = null
            playerArtOrigin = PlayerArtOrigin.NONE
            playerOriginSongId = null
            playerExpanded = true
        }
    }

    /** Colapsa el player. Cierre MANUAL: descarta cualquier reapertura pendiente. */
    fun collapsePlayer() {
        JankProbe.arm { "collapsePlayer song=${currentSong.value?.title}" }
        reopenPlayerAtEntryId = null
        playerExpanded = false
    }

    /**
     * Hoja "añadir canciones" del detalle de playlist/Favoritos. La dispara un botón de la propia
     * pantalla —la cabecera si hay canciones, el empty state si no—, NO un FAB: el FAB flotante ya
     * no existe.
     */
    var showAddSongsSheet by mutableStateOf(false)

    /**
     * **OJO: getters `@Composable` con ESTADO en el sitio de llamada.** `currentBackStackEntryAsState()`
     * es un `produceState(initialValue = null)` que se recuerda donde se llama: si la llamada está en
     * una rama condicional (`if`, `when`, `&&`), sale de la composición cuando la rama no corre y al
     * volver NACE DE NUEVO con `null` durante una composición, hasta que el flow emite en el frame
     * siguiente. Leerlos SIEMPRE una vez, incondicionalmente, al principio de la pantalla, y usar la
     * variable — así se cayó la capa del reproductor a `Hidden` un frame en cada cierre (17 ago, ver
     * `MusicPlayerScreen`).
     */
    val currentBackStackEntry: NavBackStackEntry?
        @Composable get() = navController.currentBackStackEntryAsState().value

    /** Ver la advertencia de [currentBackStackEntry]: nunca dentro de una condición. */
    val currentRoute: String?
        @Composable get() = currentBackStackEntry?.destination?.route

    // --- Navegación (helpers no composables, seguros desde callbacks) ---

    /**
     * Cierra el player si estaba expandido. Lo llama la capa del player cuando la canción actual pasa
     * a `null` (vaciar la cola, logout, corrupción): sin canción no hay nada que mostrar, así que en
     * vez de quedarse en el esqueleto —sin salida salvo varios "atrás"— se cierra sola.
     */
    fun closePlayerIfEmpty() {
        if (playerExpanded) collapsePlayer()
    }

    // Navegar a un detalle: si el player está expandido se colapsa recordando volver a él
    // ([navigateFromPlayer]); si no, es una navegación normal. El gate por [playerExpanded] evita
    // tocar los call sites —el player es el ÚNICO que puede llamar esto estando expandido, porque
    // tapa todo lo demás—.
    fun navigateToArtist(name: String) = navigateMaybeFromPlayer(Screen.ArtistDetail.createRoute(name))
    fun navigateToAlbum(name: String) = navigateMaybeFromPlayer(Screen.AlbumDetail.createRoute(name))
    fun navigateToGenre(name: String) = navigateMaybeFromPlayer(Screen.GenreDetail.createRoute(name))

    private fun navigateMaybeFromPlayer(route: String) {
        if (playerExpanded) navigateFromPlayer(route)
        else navController.navigate(route) { launchSingleTop = true }
    }

    /**
     * Navegar a un detalle DESDE el player: colapsa el player SIN container transform (`origin = NONE`,
     * o sea solo se funde) para no competir con la transición del NavHost por el overlay del
     * `SharedTransitionScope` —el bug de shared elements huérfanos que motivó el refactor del 11 ago—,
     * y anota a qué entrada del back stack volver para RE-ABRIR el player. Al regresar (back),
     * [reopenPlayerIfReturningFrom] lo expande de nuevo: "atrás" del detalle devuelve al reproductor,
     * no a la biblioteca (era el bug del 7 ago).
     */
    private fun navigateFromPlayer(route: String) {
        reopenPlayerAtEntryId = navController.currentBackStackEntry?.id
        playerArtOrigin = PlayerArtOrigin.NONE
        playerExpanded = false
        navController.navigate(route) { launchSingleTop = true }
    }

    /**
     * Reabre el player si [entry] es la entrada desde la que se navegó a un detalle estando el player
     * abierto. Lo llama el observador de ruta de [com.qhana.siku.ui.MusicPlayerScreen] en cada cambio
     * de destino. Se compara por **id** de entrada (no por ruta): un detalle de artista puede navegar
     * a otro detalle de artista —misma ruta— y el id sobrevive a la rotación.
     */
    fun reopenPlayerIfReturningFrom(entry: NavBackStackEntry?) {
        val target = reopenPlayerAtEntryId ?: return
        if (entry?.id == target) {
            reopenPlayerAtEntryId = null
            openPlayer(PlayerArtOrigin.PILL)   // reabre desde la píldora → container transform
        }
    }

    fun navigate(route: String) {
        navController.navigate(route) { launchSingleTop = true }
    }
}

@Composable
fun rememberMusicAppState(
    playerExpandedState: MutableState<Boolean>,
    pendingRowOriginState: MutableState<String?>,
    currentSong: StateFlow<Song?>,
    navController: NavHostController = rememberNavController()
): MusicAppState {
    // "Player abierto" es estado PROPIO de MusicAppState ([MusicAppState.playerExpanded]), no la ruta:
    // el player es una capa que hace container transform con la píldora, no una ruta del NavHost.
    // Los dos `MutableState` los pone MainActivity porque el TEMA también los lee; ver los KDoc de los
    // parámetros en [MusicAppState]. Antes había un espejo sincronizado por `LaunchedEffect` y llegaba
    // tarde.
    return remember(navController, playerExpandedState, pendingRowOriginState, currentSong) {
        MusicAppState(navController, playerExpandedState, pendingRowOriginState, currentSong)
    }
}

// ============================== LA CAPA DEL REPRODUCTOR Y SU MORPH ==============================

/**
 * Estados de la capa del reproductor (`PlayerOverlay`). Son las tres ramas de su `AnimatedContent`:
 * - [Expanded]: el player a pantalla completa.
 * - [Collapsed]: la píldora (MiniPlayer) flotando abajo.
 * - [Hidden]: nada (rutas sin reproducción, o sin canción).
 *
 * Collapsed↔Expanded es el *container transform*; Hidden↔Collapsed es un fundido de la píldora.
 *
 * Vive aquí, y no como `private` de `PlayerOverlay`, porque la `Transition` que los recorre se crea en
 * `MusicPlayerScreen` (que también decide qué fila es el origen del morph) y la capa solo la CONSUME.
 */
enum class PlayerLayerState { Hidden, Collapsed, Expanded }

/** Rutas en las que la píldora puede aparecer. Fuera de ellas —onboarding, ajustes— la capa queda [PlayerLayerState.Hidden]. */
fun isPillRoute(route: String?): Boolean = when (route) {
    Screen.Library.route, Screen.PlaylistDetail.route, Screen.Favorites.route,
    Screen.ArtistDetail.route, Screen.AlbumDetail.route, Screen.GenreDetail.route -> true
    else -> false
}

/**
 * De qué superficie sale (o a cuál vuelve) el reproductor en el morph EN VUELO — o en el último, si
 * está asentado. Es lo que consumen las DOS puntas: las filas de lista (para saber cuál se oculta y
 * cede sus bounds) y la capa del player (para elegir con qué keys declara sus shared elements).
 *
 * @param kind PÍLDORA / FILA / ninguna. Ver [PlayerArtOrigin].
 * @param songId la fila, solo con [PlayerArtOrigin.ROW]; null en los demás casos.
 */
data class PlayerMorphOrigin(val kind: PlayerArtOrigin, val songId: String?) {
    companion object {
        val None = PlayerMorphOrigin(PlayerArtOrigin.NONE, null)
    }
}

/**
 * El origen del morph, **congelado mientras la transición de la capa corre**.
 *
 * ## Por qué congelarlo — la regla que sale de las fuentes de `compose-animation`
 *
 * Un shared element cuya key cambia mientras su transición está en vuelo deja a su contraparte
 * VARADA en el overlay: al quitar la entrada, la máquina de estados de esa key queda en
 * `ActiveMatchRemovedDuringTransition` (o directamente sin nadie que la reinicie), y
 * `shouldRenderInOverlay` la sigue pintando **por encima de todo** hasta que el `SharedTransitionScope`
 * entero se queda quieto. Es exactamente lo que se vio el 16 ago: al volver a tocar una fila antes de
 * que el cierre anterior asentara, `AnimatedContent` REUTILIZA la rama Expanded que aún estaba
 * saliendo —misma instancia de `NowPlayingLayer`, con sus keys de la canción ANTERIOR—, la canción
 * nueva le cambiaba las keys a media transición, y la fila anterior quedaba dibujada sobre el player
 * hasta que todo se detenía. Regla: **una punta de shared element no cambia de identidad mientras su
 * transición corre**; si la petición cambia en vuelo, se atiende cuando la capa asiente.
 *
 * ## Cuándo se toma la petición
 *
 * Solo con la transición PARADA (`!isRunning`), que incluye el frame en que ARRANCA (`updateTarget`
 * cambia `targetState` en la composición y `startTimeNanos` recién se fija en el frame siguiente):
 *  - **Arrancando una apertura** → la petición de `MusicAppState` (origen + fila anotada en el tap).
 *    Es lo que hace que la fila oculta y la key del player sean la misma desde el primer frame, sin
 *    depender de en qué frame llegue la emisión de un colector.
 *  - **Arrancando un cierre** → el origen pedido (que `navigateFromPlayer` puede haber cambiado a
 *    NONE para no competir con el NavHost) y, si es una fila, la canción que suena AHORA: si el usuario
 *    cambió de tema dentro del reproductor, la superficie aterriza en la fila actual, no en la que la
 *    abrió — y si esa fila NO está en pantalla, **aterriza en la PÍLDORA** (origen PILL), que en una
 *    ruta con píldora está siempre a la vista y es adonde "se va" la música al cerrar. Hasta el 17 ago
 *    ese caso degradaba a un fundido (NONE) y se leía como que el cierre "no ocurría": tocar una fila,
 *    saltar de tema dentro del reproductor y volver es de lo más común, y cada vez perdía el morph.
 *    Solo si el destino de la capa no es la píldora (`Hidden`: cerrar en una ruta sin píldora) queda
 *    el fundido, porque no hay superficie que recoja nada — sin pareja la rama saliente moriría en el
 *    acto (`KeepUntilTransitionsFinished`). Ese cambio de key ocurre con la transición parada, así que
 *    la fila que deja de ser origen (ya descompuesta: su `AnimatedVisibility` terminó de salir hace
 *    rato) no deja nada varado.
 *  - **Asentado** → conserva. Mientras el player está abierto y quieto la key NO sigue a la canción a
 *    propósito: si lo hiciera, cada "siguiente" con la fila del tema nuevo a la vista detrás dispararía
 *    un morph espurio desde esa fila hasta la pantalla completa (esa fila recibiría el papel de origen,
 *    declararía su punta y Compose la emparejaría con la del player en el acto).
 *
 * Contenedor plano y no `mutableStateOf`: se lee y se escribe en la misma composición, y ya recompone
 * por lo que lee (`isRunning`, `currentState`, `targetState` son estado de snapshot).
 */
@Composable
fun rememberPlayerMorphOrigin(
    layerTransition: Transition<PlayerLayerState>,
    appState: MusicAppState,
    currentSongId: String?
): PlayerMorphOrigin {
    val holder = remember { MorphOriginHolder() }
    if (!layerTransition.isRunning) {
        val toExpanded = layerTransition.targetState == PlayerLayerState.Expanded
        val fromExpanded = layerTransition.currentState == PlayerLayerState.Expanded
        val requestedKind = appState.playerArtOrigin
        holder.value = when {
            toExpanded && !fromExpanded ->
                PlayerMorphOrigin(requestedKind, appState.playerOriginSongId)
            fromExpanded && !toExpanded -> {
                // Cerrando hacia una FILA, la fila tiene que estar EN PANTALLA: si el usuario cambió de
                // tema dentro del reproductor y la fila del actual no está colocada, no hay punta que
                // recoja la superficie y el morph no tendría pareja — con `KeepUntilTransitionsFinished`
                // (ver PlayerOverlay) eso significa que la rama saliente moriría EN EL ACTO, sin morph
                // ni fundido. Sin fila a la vista, la superficie aterriza en la PÍLDORA (que la capa
                // compone en ese mismo frame como destino, igual que en un cierre desde la píldora);
                // solo sin píldora (destino `Hidden`) queda el fundido. Ver el KDoc.
                val rowId = if (requestedKind == PlayerArtOrigin.ROW) currentSongId ?: holder.value.songId else null
                val toPill = layerTransition.targetState == PlayerLayerState.Collapsed
                when {
                    requestedKind == PlayerArtOrigin.ROW && (rowId == null || !appState.isRowPlaced(rowId)) ->
                        if (toPill) PlayerMorphOrigin(PlayerArtOrigin.PILL, null) else PlayerMorphOrigin.None
                    else -> PlayerMorphOrigin(requestedKind, rowId)
                }
            }
            else -> holder.value
        }
        JankProbe.note {
            "morphOrigin → ${holder.value.kind}/${holder.value.songId?.takeLast(8)} " +
                "(pedido=$requestedKind, ${layerTransition.currentState}→${layerTransition.targetState})"
        }
    }
    return holder.value
}

/** Ver [rememberPlayerMorphOrigin]. */
private class MorphOriginHolder {
    var value: PlayerMorphOrigin = PlayerMorphOrigin.None
}

/**
 * La paleta de todo lo que queda DEBAJO del reproductor (el NavHost y la píldora), **retenida
 * mientras la transición de la capa corre** y puesta al día en cuanto asienta.
 *
 * ## El problema que resuelve, medido en "Todas" el 16 ago
 *
 * Tocar una canción con OTRA carátula cambia el seed del tema en el frame del tap. Material publica
 * el `ColorScheme` en un composition local ESTÁTICO, así que ese cambio **recompone sin skipping todo
 * lo que cuelga del `MaterialTheme`** — y en la biblioteca eso es la barra superior, las pestañas, el
 * pager, la lista paginada con cada `ListItem`, sus carátulas y sus menús — en el MISMO frame en que
 * arranca el container transform y se compone el NowPlaying por primera vez. Los specs de Compose
 * avanzan por tiempo, así que ese frame largo hace que el morph dé su primer paso ya avanzado: el
 * jank. Con la misma carátula no cambia el seed y no pasa; y en un DETALLE tampoco, porque
 * `DetailContentTheme` re-provee su propio esquema —independiente de la canción— y, como
 * `MaterialTheme.Values.equals` compara el esquema por identidad y `animatedScheme` devuelve la misma
 * instancia mientras nada cambie, ese subárbol queda **blindado** de la recomposición global. Era la
 * pista "solo en Todas, solo con carátula distinta".
 *
 * ## La regla
 *
 * Es la misma que ya gobierna el tema con el reproductor abierto ("la paleta no se anima; la
 * coreografía la ponen los reveals"), llevada un paso más allá: **la paleta de lo que está debajo del
 * reproductor cambia cuando el morph termina, no mientras corre** — cuando el player la tapa y nada
 * se mueve. El reproductor, que es la superficie que crece desde la fila, sí nace ya con la paleta
 * de la canción nueva (lee el tema global directamente).
 *
 * ## Se retiene SOLO mientras se ABRE; cerrando se aplica en el acto, corra o no la transición
 *
 * Si el usuario cierra antes de que la apertura asiente —tocar y bajar enseguida—, la paleta nueva
 * sigue pendiente, y "cuando asiente" pasa a ser el final del cierre: la biblioteca a la vista, quieta,
 * y el usuario probablemente ya scrolleando. Medido en "Todas" el 16 ago (dos veces): el frame de
 * 25-40 ms del repintado caía sistemáticamente ~530-550 ms después del `collapsePlayer`, dentro del
 * scroll siguiente, y el MiniPlayer —que vive bajo esta misma paleta— aparecía con el color viejo y lo
 * cambiaba medio segundo después. Por eso la regla es por DIRECCIÓN y no por "¿está corriendo?":
 * mientras el target es Expanded y no ha asentado se retiene; en cuanto el target deja de ser Expanded
 * se aplica lo pendiente en ese mismo frame, con el player todavía tapando. La primera versión de esta
 * regla retenía también con `isRunning`, y eso no cubría justo el caso que importa: cerrar con la
 * apertura aún en vuelo devuelve el target a Collapsed SIN que `currentState` haya llegado a Expanded,
 * así que ese frame contaba como "en vuelo" y lo pendiente se iba al asentar del cierre. Es el mismo
 * trato que ya tiene el frame del tap al abrir: una primera composición pesada se lee como latencia
 * de arranque, no como jank a mitad de animación. Coste asumido: si la paleta cambia MIENTRAS corre un
 * cierre (la canción avanza sola justo entonces), el repintado cae dentro del morph — raro, y medido
 * como mejor que caer en el scroll.
 *
 * Devuelve la MISMA instancia mientras la retiene, que es lo que hace gratis el `MaterialTheme`
 * anidado que la consume: no invalida nada hasta el frame en que de verdad cambia. Contenedor plano
 * porque se lee y se escribe en la misma composición y ya recompone por lo que lee.
 *
 * @param preparingOrigin hay una fila PREPARÁNDOSE para abrir el player (`MusicAppState.pendingRowOrigin`):
 *   es el frame anterior a que la capa cambie de target, el seed ya cambió y el esquema global ya es el
 *   nuevo (el tema se congela también en ese frame). Cuenta como apertura en vuelo: si no, la paleta
 *   aterrizaría en la biblioteca a la vista un frame antes del morph, y ese repintado es justo lo que
 *   se quería fuera de la vista.
 */
@Composable
fun rememberUnderlayColorScheme(
    layerTransition: Transition<PlayerLayerState>,
    globalScheme: ColorScheme,
    preparingOrigin: Boolean
): ColorScheme {
    val holder = remember { UnderlaySchemeHolder(globalScheme) }
    // Abriendo = target Expanded sin haber asentado. Cubre el frame en que arranca (el target cambia
    // en la composición y el reloj recién la pone a correr en el frame siguiente: `currentState`
    // todavía no es Expanded) y todo el vuelo (`isRunning`). Con target Collapsed/Hidden NUNCA se
    // retiene, corra lo que corra — ver el KDoc.
    val settled = layerTransition.currentState == layerTransition.targetState && !layerTransition.isRunning
    val opening = layerTransition.targetState == PlayerLayerState.Expanded && !settled
    val hold = preparingOrigin || opening
    if (!hold && holder.scheme !== globalScheme) {
        holder.scheme = globalScheme
        JankProbe.mark { "underlay: paleta nueva → recompone NavHost" }
    }
    return holder.scheme
}

/** Ver [rememberUnderlayColorScheme]. */
private class UnderlaySchemeHolder(var scheme: ColorScheme)
