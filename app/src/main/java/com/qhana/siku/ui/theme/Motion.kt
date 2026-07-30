package com.qhana.siku.ui.theme

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.navigation.NavBackStackEntry

/**
 * Motion de la app: **una sola declaración** del [MotionScheme] y los envoltorios que hacen falta
 * para consumirlo desde donde no hay composición.
 *
 * ## Por qué existe este archivo
 *
 * `MusicPlayerTheme` ya instalaba `MotionScheme.expressive()` en el `MaterialTheme`, así que todo
 * componente M3 de fábrica se movía con springs — pero el código PROPIO de la app no lo leía casi
 * nunca: había ~55 `tween(...)` con duraciones a mano y ~15 `spring(stiffness = ...)` con
 * constantes clavadas. Consecuencia práctica: cambiar el scheme del tema no cambiaba casi nada,
 * porque las pantallas no preguntaban. Cada duración suelta era además un número mágico.
 *
 * ## DOS sistemas de motion, no uno
 *
 * M3 no unificó todo en springs, y confundirlo fue el primer error de esta migración:
 *
 *  - **Componentes** (un botón que morfea, un color que cambia, una fila que entra en una lista) →
 *    **springs**, los del [MotionScheme]. Es lo que introdujo Expressive en mayo de 2025 y lo que
 *    cubre el resto de este archivo.
 *  - **Transiciones de pantalla o de destino** (enter/exit, shared axis, container transform, fade
 *    through) → **easing + duración**, y concretamente los beziers Expressive de la sección
 *    "TRANSICIONES DE PANTALLA": son los MISMOS seis tokens de arriba, publicados por el spec en la
 *    forma que hace falta cuando un spring no sirve.
 *
 * No es una formalidad, y la diferencia es de mecánica, no de gusto: una transición necesita
 * **duración nominal** por dos motivos. El **predictive back** la recorre con el dedo
 * (`SeekableTransitionState`), y hay piezas que solo pueden sincronizarse contra un número — la
 * píldora del reproductor espera exactamente lo que dura el slide antes de desmontarse
 * (`snap(delayMillis = …)`), y un shared element tiene que aterrizar a la vez que la pantalla que
 * lo lleva. Un spring no ofrece ninguna de las dos cosas.
 *
 * OJO con el sobrepaso al llevar un token spatial a pantalla completa: es ~1,4 % del recorrido, o
 * sea ~2 px en un botón (donde ES el efecto) y ~34 px en un slide de 2400 px, donde asoma una
 * franja de lo que hay detrás por el borde contrario. Para eso están [ScreenSlideEasing] y
 * [ScreenEnterEasing], que conservan el timing y recortan solo el exceso.
 *
 * ## Cómo elegir el spec de un COMPONENTE (la regla, no el gusto)
 *
 * La distinción **spatial vs. effects** no es de velocidad, es de qué se anima:
 *  - **spatial** → posición, tamaño, forma, rotación, escala. Los springs expressive están
 *    SUBAMORTIGUADOS a propósito (`dampingRatio` 0.8 el default, 0.6 el fast), o sea REBOTAN.
 *  - **effects** → color y alpha. `dampingRatio` 1.0: sin rebote, porque un color que sobrepasa su
 *    destino y vuelve se lee como un parpadeo, no como física.
 *
 * De ahí sale una consecuencia que hay que respetar: **nunca un spec spatial sobre un valor acotado
 * a [0,1]** que el consumidor no recorte (alpha, fracciones de progreso, `Morph.progress`). El
 * rebote se sale del rango. `AlbumArtMorphShape` recorta a propósito, y por eso ahí sí se puede.
 *
 * Y una segunda: la rigidez ya la decide el token, así que la elección real es *default* / *fast* /
 * *slow* según el peso del elemento, no un número de milisegundos.
 *
 * ## Dónde se lee cada cosa
 *
 *  - **Dentro de composición** → [appSpatialSpec] y sus cinco hermanos. Leen el scheme del
 *    `MaterialTheme`, así que respetan un tema anidado con otro scheme.
 *  - **Fuera de composición** (objetos `object`, `Shape`s, gestos, transiciones de `NavHost`) →
 *    [AppMotionScheme]. Es la MISMA instancia que recibe el `MaterialTheme`, no una copia de sus
 *    valores: por eso no se pueden desincronizar.
 *
 * Ojo con un caso intermedio que ya morció una vez: `transitionSpec` de `AnimatedContent` y las
 * lambdas de transición del `NavHost` **no son composables**, así que dentro no se puede leer el
 * tema. O se iza el spec a una `val` antes de entrar (lo que hacen `PlaybackControls` y
 * `DownloadManagerScreen`), o se usa [AppMotionScheme].
 *
 * Y un aviso que vale para todo lo de aquí: **omitir `animationSpec` NO es usar el motion del
 * tema.** `animateFloatAsState`, `animateDpAsState`, `animateColorAsState`, `Crossfade` y
 * `AnimatedVisibility` tienen defaults propios de `compose-animation` que no miran el
 * `MaterialTheme`. Había dos comentarios en el repo afirmando lo contrario.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
val AppMotionScheme: MotionScheme = MotionScheme.expressive()

// ============================== ACCESO DESDE COMPOSICIÓN ==============================

/*
 * Los seis tokens, envueltos para que el caller no tenga que declarar
 * `@OptIn(ExperimentalMaterial3ExpressiveApi::class)`.
 *
 * No es azúcar sintáctico: `MotionScheme` sigue siendo API experimental, y sin estos envoltorios
 * cada composable que quisiera un spec tenía que anotarse. Eso convertía "usar el motion del tema"
 * en un trámite de tres líneas y es parte de por qué el código propio seguía escribiendo `tween`.
 * Aquí la frontera con la API experimental se cruza UNA vez.
 *
 * Leen `MaterialTheme.motionScheme` y no [AppMotionScheme] directamente: si algún día se anida un
 * tema con otro scheme, esto lo respeta y la constante no.
 */

/** Posición, tamaño, forma. **Rebota** (dampingRatio 0.8) — ver el aviso del encabezado. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
@ReadOnlyComposable
fun <T> appSpatialSpec(): FiniteAnimationSpec<T> = MaterialTheme.motionScheme.defaultSpatialSpec()

/** Como [appSpatialSpec] pero más rígido y con MÁS rebote (0.6): gestos pequeños, "pops". */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
@ReadOnlyComposable
fun <T> appFastSpatialSpec(): FiniteAnimationSpec<T> = MaterialTheme.motionScheme.fastSpatialSpec()

/** Como [appSpatialSpec] pero blando: elementos grandes que no deben ir deprisa. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
@ReadOnlyComposable
fun <T> appSlowSpatialSpec(): FiniteAnimationSpec<T> = MaterialTheme.motionScheme.slowSpatialSpec()

/** Color y alpha. Sin rebote (dampingRatio 1.0), que es lo que los hace seguros en [0,1]. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
@ReadOnlyComposable
fun <T> appEffectsSpec(): FiniteAnimationSpec<T> = MaterialTheme.motionScheme.defaultEffectsSpec()

/** El más rápido del scheme. Para lo que sale de pantalla y para lo que debe estar ya visible. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
@ReadOnlyComposable
fun <T> appFastEffectsSpec(): FiniteAnimationSpec<T> = MaterialTheme.motionScheme.fastEffectsSpec()

/** El más lento sin rebote. Cambios de ambiente (el acento del álbum, el foco del karaoke). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
@ReadOnlyComposable
fun <T> appSlowEffectsSpec(): FiniteAnimationSpec<T> = MaterialTheme.motionScheme.slowEffectsSpec()

// ============================== TRANSICIONES DE PANTALLA ==============================

/*
 * Los SEIS tokens del motion Expressive expresados como **easing + duración**, que es la forma en
 * que el spec los publica para las plataformas (y los sitios) donde un spring no sirve. Son el
 * MISMO movimiento que los springs de [AppMotionScheme]: cada bezier es el equivalente publicado
 * de su spring, no una aproximación inventada aquí.
 *
 * ## Por qué un token spatial es RÁPIDO aunque su duración diga 500 ms
 *
 * Los tres *spatial* tienen el segundo coeficiente **por encima de 1** (1.67 / 1.21 / 1.29): la
 * curva SOBREPASA el destino y luego asienta, que es la traducción exacta de un spring
 * subamortiguado. La consecuencia práctica —y es la que resuelve la queja de "la apertura se
 * siente lenta"— es que el recorrido visible se despacha en la primera tercera parte del tiempo
 * nominal: `ExpressiveDefaultSpatialEasing` ya está al **99,6 % a los 175 ms** de sus 500, y el
 * resto es un asentamiento de ~1,4 % que casi no se ve.
 *
 * Eso es lo contrario de `Emphasized` (0.2, 0, 0, 1), la curva del M3 **anterior** a Expressive,
 * que estuvo aquí hasta el 30 jul: esa llega al 87 % a mitad de camino y arrastra el 13 % restante
 * durante 250 ms. Misma duración nominal, y una se siente ágil y la otra perezosa. Por eso el
 * cambio a estos valores NO es cosmético: es lo que permite mantener duraciones ACOPLADAS entre el
 * player y su carátula (ver [AppBoundsTransform]) sin que la apertura se sienta lenta.
 *
 * Los tres *effects* no sobrepasan (todos sus coeficientes ≤ 1), igual que los springs de effects:
 * son para color y alpha, donde pasarse del destino se lee como un parpadeo.
 */

/** Gestos pequeños y "pops". El que más sobrepasa. */
val ExpressiveFastSpatialEasing = CubicBezierEasing(0.42f, 1.67f, 0.21f, 0.90f)
const val EXPRESSIVE_FAST_SPATIAL_MS = 350

/** El de referencia para lo que se mueve: posición, tamaño, forma. */
val ExpressiveDefaultSpatialEasing = CubicBezierEasing(0.38f, 1.21f, 0.22f, 1.00f)
const val EXPRESSIVE_DEFAULT_SPATIAL_MS = 500

/** Elementos grandes que no deben ir deprisa. */
val ExpressiveSlowSpatialEasing = CubicBezierEasing(0.39f, 1.29f, 0.35f, 0.98f)
const val EXPRESSIVE_SLOW_SPATIAL_MS = 650

/** Lo que debe estar ya visible (o ya ido) cuando el usuario levanta el dedo. */
val ExpressiveFastEffectsEasing = CubicBezierEasing(0.31f, 0.94f, 0.34f, 1.00f)
const val EXPRESSIVE_FAST_EFFECTS_MS = 150

/** Color y alpha, caso normal. */
val ExpressiveDefaultEffectsEasing = CubicBezierEasing(0.34f, 0.80f, 0.34f, 1.00f)
const val EXPRESSIVE_DEFAULT_EFFECTS_MS = 200

/** Cambios de ambiente (el acento del álbum). */
val ExpressiveSlowEffectsEasing = CubicBezierEasing(0.34f, 0.88f, 0.34f, 1.00f)
const val EXPRESSIVE_SLOW_EFFECTS_MS = 300

/**
 * [ExpressiveDefaultSpatialEasing] **recortado a 1**, para lo que se desplaza a PANTALLA COMPLETA.
 *
 * El sobrepaso de los tokens spatial es ~1,4 % del recorrido. En un botón eso son 2 px y ES el
 * efecto; en un slide de pantalla completa son ~34 px de la pantalla saliéndose por su borde, o sea
 * una franja de lo que hay detrás asomando por el lado contrario (y en el reproductor, justo la
 * píldora que el slide acaba de tapar). Recortar conserva el TIMING —que es lo que hace que se
 * sienta rápido— y elimina el único efecto secundario que no cabe en una pantalla completa.
 *
 * No aplica a los shared elements: esos viajan en el overlay, no dejan hueco detrás, y su pequeño
 * rebote al aterrizar es justamente lo que los hace ver vivos.
 */
val ScreenSlideEasing = Easing { fraction ->
    ExpressiveDefaultSpatialEasing.transform(fraction).coerceAtMost(1f)
}

/**
 * Duración de lo que empieza Y termina en pantalla: el token *default spatial*.
 *
 * La comparten las TRES cosas que se mueven coordinadas: las transiciones del `NavHost`, el slide
 * del reproductor ([com.qhana.siku.ui.PlayerOverlay]) y [AppBoundsTransform]. Que sea una sola
 * constante es load-bearing y está MEDIDO en device: un shared element viaja MIENTRAS su pantalla
 * se desplaza, y si las dos duraciones no coinciden el elemento llega a su destino antes que el
 * fondo que lo acompaña — se queda quieto en su sitio final mientras el resto sigue subiendo, que
 * es exactamente el síntoma que el usuario describió como "la carátula no sube con el resto del
 * contenido, simplemente aparece".
 *
 * El intento de desacoplarlos (30 jul: player a 450 ms con la carátula en el spring default de la
 * API, ~300 ms) venía de que la versión acoplada con `Emphasized` se sentía lenta. La lentitud era
 * de la CURVA, no del acople — ver el encabezado de esta sección.
 */
const val SCREEN_TRANSFORM_MS = EXPRESSIVE_DEFAULT_SPATIAL_MS

/** Lo que solo ENTRA (hojas a pantalla completa, cambio de pestaña): no persiste nada suyo. */
const val SCREEN_ENTER_MS = EXPRESSIVE_FAST_SPATIAL_MS

/**
 * Lo que solo SALE. Más corto que la entrada a propósito: lo que se va no se hace esperar.
 *
 * 200 ms y no los 150 del token *fast*: aquí lo que se mueve es una hoja a pantalla COMPLETA, y a
 * 150 ms un recorrido de esa longitud se lee como un corte en vez de como algo que se va. Las
 * opacidades sí usan el token fast.
 */
const val SCREEN_EXIT_MS = EXPRESSIVE_DEFAULT_EFFECTS_MS

/** Easing de lo que entra: spatial recortado, por el mismo motivo que [ScreenSlideEasing]. */
val ScreenEnterEasing = Easing { fraction ->
    ExpressiveFastSpatialEasing.transform(fraction).coerceAtMost(1f)
}

/** Easing de lo que sale. Effects: no sobrepasa, así que no hace falta recortarlo. */
val ScreenExitEasing = ExpressiveFastEffectsEasing

// ============================== REVEALS DE CAMBIO DE CANCIÓN ==============================

/**
 * Progreso de los dos *reveals* de cambio de canción: la ventana cookie que descubre la carátula
 * nueva (`NowPlayingArt`) y la que descubre el acento nuevo en los controles (`AccentRevealGroup`).
 *
 * **Es una excepción DELIBERADA a "todo componente va por un token del scheme", y se probó al
 * revés**: el 30 jul se pasaron a `appSlowEffectsSpec()` y el reveal dejó de verse — 800 de rigidez
 * lo despachan en ~140 ms y estas dos animaciones necesitan durar para leerse como un descubrimiento
 * y no como un parpadeo. Ningún token sirve:
 *  - los de **effects** no rebotan (que es lo que hace falta: el progreso alimenta una escala y un
 *    clip, y pasarse de 1 agranda la ventana más de la cuenta), pero el más blando ya es demasiado
 *    rápido;
 *  - los **spatial** tienen la rigidez adecuada pero están subamortiguados.
 *
 * Así que el valor se queda escrito: crítico (sin rebote) y `StiffnessMediumLow`, que es lo que
 * tenían desde siempre y lo que el usuario reconoce como el efecto correcto.
 */
val AppRevealSpec: FiniteAnimationSpec<Float> =
    spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

// ============================== SHARED ELEMENTS ==============================

/**
 * Motion de los shared elements que viajan CON una transición del `NavHost` (foto de artista,
 * portada de álbum, tarjetas del home → detalle): mismo easing y duración que la pantalla que los
 * transporta, o el elemento aterriza en otro momento que su destino.
 *
 * Sin esto la API de shared transitions aplica su propio default (`spring(StiffnessMediumLow)`),
 * ajeno a la duración del `NavHost`. **Es un container transform, o sea una TRANSICIÓN**: por eso
 * lleva easing y duración y no un spring del `MotionScheme`.
 *
 * **La carátula píldora↔NowPlaying SÍ lo usa**, y es obligatorio: el slide del reproductor corre
 * con esta misma duración y curva, así que la carátula viaja acompasada con el fondo que sube. Con
 * el spring default de la API (que asienta en ~300 ms contra los 500 del slide) la carátula
 * aterrizaba primero y se quedaba flotando quieta mientras el resto del reproductor seguía
 * subiendo — el "flash" que reportó el usuario el 30 jul. Aquí NO se recorta el sobrepaso (ver
 * [ScreenSlideEasing]): un shared element viaja en el overlay y no destapa nada.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
val AppBoundsTransform = BoundsTransform { _, _ ->
    tween(SCREEN_TRANSFORM_MS, easing = ExpressiveDefaultSpatialEasing)
}

// ============================== APARICIÓN VERTICAL ==============================

/**
 * Aparición de un bloque que EMPUJA al contenido de abajo (banner de sincronización, ficha técnica
 * del formato, secciones que se despliegan): crece en alto y entra por alpha.
 *
 * El alto va por spatial y el alpha por effects, que es la razón de que esto sea una función y no
 * dos parámetros sueltos en cada llamada: son dos specs distintos y es fácil equivocarse.
 */
fun appExpandFadeIn(): EnterTransition =
    expandVertically(AppMotionScheme.defaultSpatialSpec()) +
        fadeIn(AppMotionScheme.defaultEffectsSpec())

/** Contrario de [appExpandFadeIn]. Sale con los tokens *fast*: lo que se va no se hace esperar. */
fun appShrinkFadeOut(): ExitTransition =
    shrinkVertically(AppMotionScheme.fastSpatialSpec()) +
        fadeOut(AppMotionScheme.fastEffectsSpec())

/**
 * Versión horizontal de [appExpandFadeIn], para lo que aparece DENTRO de una fila y desplaza a sus
 * vecinos (el anillo de descarga del toolbar, la etiqueta de una pestaña activa).
 *
 * Existe como par aparte y no como parámetro porque elegir mal el eje no es un detalle: un bloque
 * que crece en el eje equivocado empuja el layout hacia donde no debe y se lee como un salto.
 */
fun appExpandWidthFadeIn(): EnterTransition =
    expandHorizontally(AppMotionScheme.defaultSpatialSpec()) +
        fadeIn(AppMotionScheme.defaultEffectsSpec())

/** Contrario de [appExpandWidthFadeIn]. */
fun appShrinkWidthFadeOut(): ExitTransition =
    shrinkHorizontally(AppMotionScheme.fastSpatialSpec()) +
        fadeOut(AppMotionScheme.fastEffectsSpec())

// ============================== HOJAS A PANTALLA COMPLETA ==============================

/**
 * Entrada de las hojas propias que ocupan la pantalla y suben desde abajo (ecualizador, letras,
 * cola). No son `ModalBottomSheet` —esos ya traen su propio motion de M3— sino capas montadas con
 * `AnimatedVisibility` por encima del player, y por eso necesitan su spec explícito.
 *
 * Transición, no componente: easing + duración. Y del par *enter/exit* y no del *transform*, porque
 * la hoja no deja nada suyo en pantalla al cerrarse — a diferencia del reproductor, cuya carátula
 * persiste en la píldora.
 *
 * El movimiento va por un token **spatial** y la opacidad por uno de **effects**, cada uno con SU
 * duración: es lo que hace el spec y no un descuido. La hoja se hace opaca en 200 ms mientras
 * todavía termina de subir, así que se lee como material que llega, no como un rectángulo que se
 * desvanece.
 */
fun appSheetEnter(): EnterTransition =
    slideInVertically(tween(SCREEN_ENTER_MS, easing = ScreenEnterEasing)) { it } +
        fadeIn(tween(EXPRESSIVE_DEFAULT_EFFECTS_MS, easing = ExpressiveDefaultEffectsEasing))

/** Contrario de [appSheetEnter]. */
fun appSheetExit(): ExitTransition =
    slideOutVertically(tween(SCREEN_EXIT_MS, easing = ScreenExitEasing)) { it } +
        fadeOut(tween(EXPRESSIVE_FAST_EFFECTS_MS, easing = ExpressiveFastEffectsEasing))

// ============================== NAVEGACIÓN ==============================

/**
 * Divisor del recorrido de la pantalla que se QUEDA DETRÁS: se desplaza `ancho / 3`.
 *
 * El shared axis de Material pide que las dos pantallas se muevan en el mismo eje, no que la de
 * atrás se quede congelada mientras la nueva la tapa. Pero moverla el ancho completo (como la que
 * entra) las convierte en dos hojas independientes y se pierde la jerarquía. Un tercio es el
 * paralaje habitual: se lee que la de atrás cede, sin competir con la que llega.
 *
 * Esto es además lo que hace que el **predictive back** tenga algo que enseñar: durante el gesto el
 * sistema recorre la transición hacia atrás, y sin `exitTransition`/`popEnterTransition` declaradas
 * el recorrido de la pantalla saliente es cero — el gesto arrastra una capa sobre un fondo quieto.
 */
private const val NAV_PARALLAX_DIVISOR = 3

/*
 * Patrón **shared axis X** del spec: las dos pantallas se desplazan en el mismo eje mientras cruzan
 * su opacidad. El MOVIMIENTO va con [SCREEN_TRANSFORM_MS] (default spatial) porque las rutas de
 * detalle llevan un shared element —la foto del artista, la portada del álbum— y su
 * [AppBoundsTransform] tiene que durar exactamente lo mismo que la pantalla que lo transporta.
 *
 * La OPACIDAD va aparte y más corta (tokens de effects, 200 al entrar / 150 al salir), que es como
 * lo define el spec: la pantalla que llega se hace opaca mucho antes de terminar de moverse, así
 * que el usuario lee contenido y no un fantasma deslizándose media pantalla.
 */

/** Entrada al abrir un detalle: llega desde el borde derecho. */
val appNavForwardEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    slideInHorizontally(tween(SCREEN_TRANSFORM_MS, easing = ScreenSlideEasing)) { it } +
        fadeIn(tween(EXPRESSIVE_DEFAULT_EFFECTS_MS, easing = ExpressiveDefaultEffectsEasing))
}

/** La pantalla anterior cede hacia la izquierda (ver [NAV_PARALLAX_DIVISOR]). */
val appNavForwardExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    slideOutHorizontally(tween(SCREEN_TRANSFORM_MS, easing = ScreenSlideEasing)) {
        -it / NAV_PARALLAX_DIVISOR
    } + fadeOut(tween(EXPRESSIVE_FAST_EFFECTS_MS, easing = ExpressiveFastEffectsEasing))
}

/** Vuelta atrás: la pantalla anterior regresa desde su posición cedida. */
val appNavBackEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    slideInHorizontally(tween(SCREEN_TRANSFORM_MS, easing = ScreenSlideEasing)) {
        -it / NAV_PARALLAX_DIVISOR
    } + fadeIn(tween(EXPRESSIVE_DEFAULT_EFFECTS_MS, easing = ExpressiveDefaultEffectsEasing))
}

/** Vuelta atrás: el detalle se va por donde vino. */
val appNavBackExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    slideOutHorizontally(tween(SCREEN_TRANSFORM_MS, easing = ScreenSlideEasing)) { it } +
        fadeOut(tween(EXPRESSIVE_FAST_EFFECTS_MS, easing = ExpressiveFastEffectsEasing))
}

// NO hay par vertical para el NavHost. El único destino con sensación de hoja es el reproductor, y
// ese NO es una ruta: es una capa hermana del NavHost (ver `PlayerOverlay`). Declararlo "por si
// acaso" es lo que convirtió el viejo objeto `Transitions` en seis lambdas que no usaba nadie.

/**
 * Rutas sin dirección propia (hub ↔ hub): solo cruce de opacidad, el patrón *fade through* del spec.
 * Puro effects — no se mueve nada, así que no hay ningún token spatial que aplicar aquí.
 */
val appNavFadeEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    fadeIn(tween(EXPRESSIVE_DEFAULT_EFFECTS_MS, easing = ExpressiveDefaultEffectsEasing))
}

/** Contrario de [appNavFadeEnter]. Sale antes de que entre la otra, como pide el *fade through*. */
val appNavFadeExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    fadeOut(tween(EXPRESSIVE_FAST_EFFECTS_MS, easing = ExpressiveFastEffectsEasing))
}
