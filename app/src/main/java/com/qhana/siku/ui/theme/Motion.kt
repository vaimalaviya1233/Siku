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
 * [ExpressiveDefaultSpatialEasing] **recortado a 1**, para lo que se desplaza a PANTALLA COMPLETA:
 * el slide del reproductor y las cuatro transiciones del `NavHost`.
 *
 * El sobrepaso de los tokens spatial es ~1,4 % del recorrido. En un botón eso son 2 px y ES el
 * efecto; en un slide de pantalla completa son ~34 px de la pantalla saliéndose por su borde, o sea
 * una franja de lo que hay detrás asomando por el lado contrario (y en el reproductor, justo la
 * píldora que el slide acaba de tapar). Recortar conserva el TIMING —que es lo que hace que se
 * sienta rápido— y elimina ese efecto secundario.
 *
 * **Aquí el recorte vale y en [ScreenEnterEasing] no valía**, y la diferencia está en un solo
 * número: el *default* spatial termina en (0.22, **1.00**), así que se pasa y baja hasta el destino
 * SIN cruzarlo — saturar a 1 solo le quita el exceso. El *fast* termina en (0.21, **0.90**): cruza
 * por debajo y vuelve, y eso un `coerceAtMost` no lo puede tocar. Por eso las hojas usan una curva
 * de effects y esto no. Antes de recortar un easing, mirar el segundo punto de control.
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
 * Lo que solo SALE. Sigue siendo más corto que la entrada (350) a propósito: lo que se va no se hace
 * esperar.
 *
 * **300 ms y no 200**, que es donde estuvo y se leía como un corte: lo que recorre esta duración es
 * el ALTO COMPLETO de la pantalla, y a esa distancia el ojo necesita ver salir la hoja o parece que
 * simplemente ha dejado de estar. La escala del token es la misma para un chip de 32 dp que para una
 * hoja de 2400 px, así que en los recorridos largos hay que subir un peldaño. Las OPACIDADES se
 * quedan en el token *fast* (150): una hoja debe volverse transparente antes de terminar de bajar, o
 * se la ve arrastrarse.
 */
const val SCREEN_EXIT_MS = EXPRESSIVE_SLOW_EFFECTS_MS

/**
 * Easing de lo que ENTRA y sale del todo: hojas modales a pantalla completa, cambio de paso del
 * onboarding, cambio de pestaña del gestor.
 *
 * **Es una curva de EFFECTS, no de spatial, y no es un descuido.** Los tres tokens spatial
 * SOBREPASAN, y el *fast spatial* además **retrocede**: sus puntos de control son
 * (0.42, 1.67, 0.21, **0.90**) — el segundo está por DEBAJO de 1, así que la curva se pasa del
 * destino y vuelve desde el otro lado. Eso en un botón es el efecto buscado; en una hoja que ocupa
 * la pantalla es un rebote, y el usuario lo reportó en las tres del reproductor (letras, cola,
 * ecualizador). Los tokens de effects son la única familia del spec con todos los puntos de control
 * ≤ 1: llegan al destino y se quedan.
 *
 * **Recortar el spatial NO servía**, y estuvo así medio día: un `coerceAtMost(1f)` satura el
 * sobrepaso —congelando la animación mientras la curva va por encima de 1— pero no puede hacer nada
 * con el retroceso posterior, que es justo la parte que se ve como rebote. Salía peor que el
 * original: mismo rebote, con una parada artificial delante.
 */
val ScreenEnterEasing = ExpressiveDefaultEffectsEasing

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

/*
 * Los cuatro helpers de abajo animan un TAMAÑO, y por eso todos usan specs de **effects** —los
 * críticos, sin rebote— aunque lo que cambie sea geometría. No es una excepción caprichosa a la
 * regla spatial/effects: es la consecuencia de CÓMO se anima un tamaño en Compose.
 *
 * `expandVertically`/`expandHorizontally` no mueven un `graphicsLayer`, cambian la MEDIDA del nodo:
 * cada frame es una pasada de layout que reacomoda a todos los vecinos. Un spring subamortiguado
 * oscila alrededor del tamaño final antes de asentarse, así que **el rebote multiplica el número de
 * pasadas caras** — y encima se ve como un temblor en el contenido de al lado, que no es lo mismo
 * que el "pop" simpático de un botón. Con el banner de sincronización el coste se nota entero: su
 * alto alimenta el `contentPadding` de la lista, así que cada frame de rebote reacomodaba la
 * biblioteca. Medido como pérdida de fps en las pestañas de la biblioteca (30 jul), mismo defecto.
 *
 * **Regla: un spec que rebota sobre algo que EMPUJA LAYOUT sale caro; sobre un `graphicsLayer`
 * (posición, escala, alpha) es gratis.**
 */

/**
 * Aparición de un bloque que EMPUJA al contenido de abajo (banner de sincronización, ficha técnica
 * del formato, secciones que se despliegan): crece en alto y entra por alpha.
 *
 * Existe como función y no como dos parámetros sueltos en cada llamada porque son dos specs con
 * duraciones distintas y es fácil equivocarse: el alto entra con el token *default* y el alpha
 * también, para que el bloque no se lea antes de haber terminado de abrirse.
 */
fun appExpandFadeIn(): EnterTransition =
    expandVertically(AppMotionScheme.defaultEffectsSpec()) +
        fadeIn(AppMotionScheme.defaultEffectsSpec())

/** Contrario de [appExpandFadeIn]. Sale con los tokens *fast*: lo que se va no se hace esperar. */
fun appShrinkFadeOut(): ExitTransition =
    shrinkVertically(AppMotionScheme.fastEffectsSpec()) +
        fadeOut(AppMotionScheme.fastEffectsSpec())

/**
 * Versión horizontal de [appExpandFadeIn], para lo que aparece DENTRO de una fila y desplaza a sus
 * vecinos (el anillo de descarga del toolbar).
 *
 * Existe como par aparte y no como parámetro porque elegir mal el eje no es un detalle: un bloque
 * que crece en el eje equivocado empuja el layout hacia donde no debe y se lee como un salto.
 */
fun appExpandWidthFadeIn(): EnterTransition =
    expandHorizontally(AppMotionScheme.defaultEffectsSpec()) +
        fadeIn(AppMotionScheme.defaultEffectsSpec())

/** Contrario de [appExpandWidthFadeIn]. */
fun appShrinkWidthFadeOut(): ExitTransition =
    shrinkHorizontally(AppMotionScheme.fastEffectsSpec()) +
        fadeOut(AppMotionScheme.fastEffectsSpec())

// ============================== HOJAS A PANTALLA COMPLETA ==============================

/**
 * Entrada de las hojas propias que ocupan la pantalla y suben desde abajo (ecualizador, letras,
 * cola). No son `ModalBottomSheet` —esos van por `AppModalSheet`, que les da el mismo patrón sin
 * rebote a través del tema— sino capas montadas con
 * `AnimatedVisibility` por encima del player, y por eso necesitan su spec explícito.
 *
 * Es el patrón **enter and exit** del spec: introducir un COMPONENTE sobre la UI principal (modal o
 * no), que es distinto de navegar entre pantallas — y por eso el spec advierte de no usarlo para
 * jerarquía, donde deslizar el alto completo sobra y deja la relación entre pantallas sin explicar.
 * Aquí sí aplica: una hoja no es un nivel del grafo, es una superficie que se pone encima.
 *
 * Transición, no componente: easing + duración, **nunca springs**. Y del par *enter/exit* y no del
 * *transform*, porque la hoja no deja nada suyo en pantalla al cerrarse — a diferencia del
 * reproductor, cuya carátula persiste en la píldora.
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
 * Divisor del recorrido horizontal de AMBAS pantallas: cada una se desplaza `ancho / 3`.
 *
 * **Ninguna recorre el ancho completo, y eso es del spec, no una economía**: en el patrón *forward
 * and backward* de Material, Android acompaña el deslizamiento con un FADE precisamente para no
 * tener que mover las pantallas de un borde al otro. El desplazamiento comunica la dirección
 * (adelante / atrás) y la opacidad hace el trabajo de sustituir una por otra; con el ancho completo
 * el gesto se lee como dos hojas independientes cruzándose y se pierde la jerarquía.
 *
 * Que las dos recorran LO MISMO es lo que las convierte en un *shared axis* de verdad: se mueven
 * como una sola pieza en un eje, en vez de una tapando a la otra. (Hasta el 30 jul la entrante venía
 * desde `it` —el ancho entero— y solo la saliente cedía un tercio, o sea un paralaje; el kdoc de
 * entonces daba por bueno el ancho completo para la que llega, que es justo lo que el spec descarta.)
 *
 * Esto es además lo que hace que el **predictive back** tenga algo que enseñar: durante el gesto el
 * sistema recorre la transición hacia atrás, y sin `exitTransition`/`popEnterTransition` declaradas
 * el recorrido de la pantalla saliente es cero — el gesto arrastra una capa sobre un fondo quieto.
 */
const val SCREEN_SLIDE_DIVISOR = 3

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

/** Adelante: la que llega entra desde la derecha, un tercio (ver [SCREEN_SLIDE_DIVISOR]). */
val appNavForwardEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    slideInHorizontally(tween(SCREEN_TRANSFORM_MS, easing = ScreenSlideEasing)) {
        it / SCREEN_SLIDE_DIVISOR
    } + fadeIn(tween(EXPRESSIVE_DEFAULT_EFFECTS_MS, easing = ExpressiveDefaultEffectsEasing))
}

/** Adelante: la anterior cede hacia la izquierda el MISMO tercio. */
val appNavForwardExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    slideOutHorizontally(tween(SCREEN_TRANSFORM_MS, easing = ScreenSlideEasing)) {
        -it / SCREEN_SLIDE_DIVISOR
    } + fadeOut(tween(EXPRESSIVE_FAST_EFFECTS_MS, easing = ExpressiveFastEffectsEasing))
}

/** Atrás: la anterior regresa desde la izquierda. */
val appNavBackEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    slideInHorizontally(tween(SCREEN_TRANSFORM_MS, easing = ScreenSlideEasing)) {
        -it / SCREEN_SLIDE_DIVISOR
    } + fadeIn(tween(EXPRESSIVE_DEFAULT_EFFECTS_MS, easing = ExpressiveDefaultEffectsEasing))
}

/** Atrás: el detalle se va hacia la derecha, por donde vino. */
val appNavBackExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    slideOutHorizontally(tween(SCREEN_TRANSFORM_MS, easing = ScreenSlideEasing)) {
        it / SCREEN_SLIDE_DIVISOR
    } + fadeOut(tween(EXPRESSIVE_FAST_EFFECTS_MS, easing = ExpressiveFastEffectsEasing))
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
