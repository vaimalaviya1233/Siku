package com.qhana.siku.ui.components

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import com.qhana.siku.data.util.JankProbe
import com.qhana.siku.ui.theme.ScreenEnterEasing
import com.qhana.siku.ui.theme.ScreenExitEasing
import com.qhana.siku.ui.theme.SCREEN_ENTER_MS
import com.qhana.siku.ui.theme.SCREEN_EXIT_MS

/** Fases de [SheetOverlay]. Ver su KDoc: [Preparing] es la que arregla el jank de apertura. */
private enum class SheetStage {
    /** No compuesta. */
    Gone,

    /** Compuesta y medida, colocada FUERA de la pantalla. La animación todavía no arrancó. */
    Preparing,

    /** Arriba (o subiendo). */
    Open
}

/**
 * Capa a pantalla completa que sube desde abajo: las tres hojas propias del reproductor (letras,
 * cola, ecualizador). Sustituye al `AnimatedVisibility(enter = appSheetEnter(), exit = appSheetExit())`
 * que las montaba hasta el 19 ago 2026, y las dos diferencias son las que quitan el jank al abrirlas.
 *
 * ## 1. Un FRAME DE PREPARACIÓN: componer y animar dejan de caer en el mismo frame
 *
 * `AnimatedVisibility` compone su contenido en el frame en que `visible` pasa a `true` — que es
 * también el frame en que arranca la animación de entrada. Y esa animación es un `tween`, o sea que
 * **avanza por TIEMPO**: si montar el árbol cuesta 40-80 ms (el ecualizador son 2260 líneas dentro de
 * un `verticalScroll`, así que se compone y mide ENTERO, lo que se ve y lo que no; la cola arranca el
 * mapeo de la lista y su scroll al índice activo; las letras montan su `LazyColumn`), la hoja no
 * aparece abajo del todo: aparece ya con un tercio del recorrido consumido y saltando. Es exactamente
 * el fallo que el reproductor tuvo hasta que se volvió persistente ("25-58 ms en el frame del tap, el
 * que arranca el morph, o sea el peor"), y por eso se ve igual en las TRES hojas: no es de ninguna,
 * es del patrón.
 *
 * Aquí no vale la solución del reproductor —dejarlas compuestas para siempre—: el ecualizador
 * mantendría vivas dos docenas de suscripciones y el detector true-peak que alimenta su medidor, y la
 * cola, la lista entera mapeada. Lo que se hace es SEPARAR las dos cosas en dos frames: en el primero
 * la hoja se compone, se mide y se dibuja **en su posición inicial, fuera de la pantalla** (fase
 * [SheetStage.Preparing]); en el siguiente arranca el deslizamiento, ya con todo caliente. El coste no
 * desaparece —se paga igual, en el frame del tap— pero deja de comerse el principio de la animación,
 * que es lo que se veía.
 *
 * Se prepara COLOCADA y no simplemente medida (como hace el reproductor guardado, que mide y no
 * coloca): así el primer dibujado —el que crea los `RenderNode`, mide los textos y sube las texturas—
 * también cae en el frame de preparación. Fuera de la pantalla no se ve, que es lo único que importa.
 *
 * ## 2. SIN fundido: la superficie es opaca desde el primer frame
 *
 * `appSheetEnter/Exit` sumaban un `fadeIn`/`fadeOut` al deslizamiento. Un alpha < 1 sobre una
 * superficie a pantalla completa obliga a HWUI a un `saveLayer` offscreen de la pantalla entera en
 * CADA frame ("alpha caused saveLayer 1080x2400", medido con atrace el 17 ago) — el mismo coste por el
 * que la rama saliente del reproductor dejó de llevar `fadeOut`, aquí pagado en la entrada Y en la
 * salida de las tres hojas. Y a diferencia del container transform, donde el fundido explica que una
 * superficie se convierte en otra, aquí no explica nada: la hoja no comparte nada con lo que tapa, y
 * el patrón *enter and exit* del spec funde el SCRIM, no el material que llega. Deslizar basta.
 *
 * Las duraciones y easings NO cambian: son los mismos [SCREEN_ENTER_MS]/[SCREEN_EXIT_MS] con
 * [ScreenEnterEasing]/[ScreenExitEasing] que ya usaba `appSheetEnter/Exit`. Lo que cambia es que el
 * deslizamiento va por `graphicsLayer` (una traslación, sin tocar el layout) en vez de por
 * `slideInVertically`.
 *
 * @param label nombre de la hoja para la sonda de frames ([com.qhana.siku.data.util.JankProbe]):
 *   abrir una hoja arma una ventana, igual que abrir el reproductor.
 * @param visible si la hoja debe estar abierta.
 * @param onCoveringChange se llama con `true` cuando la hoja ha terminado de subir y TAPA lo de abajo,
 *   y con `false` en cuanto empieza a bajar. Es lo que deja apagar el reproductor mientras no se ve
 *   (ver `NowPlayingScreen`): durante la animación vale `false`, porque entonces sí se ve.
 */
@Composable
fun SheetOverlay(
    visible: Boolean,
    label: String,
    modifier: Modifier = Modifier,
    onCoveringChange: (Boolean) -> Unit = {},
    content: @Composable () -> Unit
) {
    var stage by remember { mutableStateOf(if (visible) SheetStage.Open else SheetStage.Gone) }

    val transition = updateTransition(stage == SheetStage.Open, label = "sheetOverlay")

    LaunchedEffect(visible) {
        if (!visible) {
            stage = SheetStage.Gone
            return@LaunchedEffect
        }
        // `currentState` sigue valiendo `true` mientras la hoja no haya terminado de bajar, y en ese
        // caso su árbol sigue compuesto: reabrirla no necesita preparación y esperar un frame solo
        // añadiría un tirón al cambio de sentido.
        if (stage == SheetStage.Gone && !transition.currentState) {
            JankProbe.arm { "abrir hoja: $label" }
            stage = SheetStage.Preparing
            // Un frame: el de la preparación. El callback se resuelve al principio del SIGUIENTE, con
            // el árbol ya compuesto, medido y dibujado una vez.
            withFrameNanos { }
            JankProbe.mark { "hoja $label preparada; arranca el slide" }
        }
        stage = SheetStage.Open
    }

    // Compuesta mientras haga falta: preparándose, arriba, o todavía bajando (`currentState` no cae a
    // `false` hasta que la animación de salida termina).
    val composed = stage != SheetStage.Gone || transition.currentState || transition.isRunning

    // TAPA lo de abajo solo con la animación ASENTADA en abierto. `currentState` no adopta el destino
    // hasta que la transición termina, así que esto es `false` durante toda la subida —cuando la hoja
    // todavía deja ver el reproductor— y vuelve a `false` en el primer frame de la bajada, que es
    // justo cuando lo de abajo tiene que estar otra vez colocado y vivo.
    val covering = composed && transition.currentState && transition.targetState
    val coveringCallback = rememberUpdatedState(onCoveringChange)
    DisposableEffect(covering) {
        coveringCallback.value(covering)
        onDispose { if (covering) coveringCallback.value(false) }
    }

    // Se declara SIEMPRE, también con la hoja descompuesta (convención 15: una función @Composable
    // con estado propio nunca dentro de una condición). En reposo no cuesta nada: una animación
    // registrada en una transición parada no produce frames.
    val slide: State<Float> = transition.animateFloat(
        transitionSpec = {
            if (targetState) tween(SCREEN_ENTER_MS, easing = ScreenEnterEasing)
            else tween(SCREEN_EXIT_MS, easing = ScreenExitEasing)
        },
        label = "sheetSlide"
    ) { if (it) 1f else 0f }

    // **Mientras NO está asentada, la hoja no existe para la accesibilidad**, y esto no es un detalle
    // de a11y sino la mitad del coste de la animación (medido con Perfetto el 19 ago, release, sobre
    // 8 aperturas seguidas de la hoja de LETRAS; el mecanismo es del patrón, así que vale para las tres).
    //
    // `getAllUncoveredSemanticsNodesToIntObjectMap` recorre el árbol semántico y cada nodo importante
    // RESTA su área del espacio libre, así que lo que queda tapado sale del mapa. Con la hoja subiendo,
    // su rectángulo crece un poco en cada frame → tapa unos nodos más del reproductor → **el conjunto
    // de nodos cambia en CADA frame** → Compose recalcula el árbol entero y emite eventos de cambio de
    // estructura. Medido: 31 pasadas de `checkForSemanticsChanges` en los 430 ms de una apertura,
    // 56 ms de hilo principal, o sea **1,8 ms de cada frame de 8,33** (22 % del presupuesto a 120 Hz),
    // contra CERO en reposo con el reproductor abierto. Es lo que dejaba el frame en ~7 ms y hacía caer
    // uno de cada dos.
    //
    // Por eso el morph del reproductor no sufre esto y esta animación sí: aquél escala con
    // `scaleToBounds`, que mueve píxeles y NO el layout, así que sus bounds semánticos están en su
    // sitio final desde el primer frame. Un `graphicsLayer { translationY }` sí entra en
    // `touchBoundsInRoot`. Esa es la diferencia entre "va fluido" y "no", y no la duración ni el easing.
    //
    // Los DOS modifiers hacen falta y cada uno corta una fuente: `clearAndSetSemantics` deja fuera del
    // recorrido a los hijos —cada hoja aporta un árbol grande y sus nodos van ENTRANDO en la pantalla
    // uno a uno mientras sube: una línea de letra clickable por verso, veinte sliders con su
    // `progressBarRangeInfo` en el ecualizador— y
    // `hideFromAccessibility` hace `isImportantForAccessibility()` falso, que es la condición exacta con
    // la que un nodo resta espacio — sin él, la hoja seguiría destapando y tapando el reproductor de
    // debajo aunque ella no aportase nada.
    //
    // Y es lo correcto además por lo suyo: una superficie en movimiento no se explora. Hoy TalkBack
    // recibe 31 avisos de "cambió la estructura" en 350 ms, que es peor que no recibir ninguno hasta
    // que la hoja llega. Al asentar se quita el modifier y la estructura se anuncia UNA vez — en el
    // mismo frame en que `NowPlayingScreen` deja de colocar el reproductor, así que las dos entran en
    // el mismo recálculo.
    val animatingSemantics = remember {
        Modifier.clearAndSetSemantics { hideFromAccessibility() }
    }

    if (composed) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .then(if (covering) Modifier else animatingSemantics)
                // Lectura DIFERIDA (dentro del bloque): cambia en cada frame de la animación, y leerla
                // en composición recompondría la hoja entera por frame. En [SheetStage.Preparing] vale
                // 0, o sea la hoja entera colocada justo por debajo del borde inferior: compuesta,
                // medida y dibujada, pero sin verse y sin recibir toques (un `graphicsLayer` sí
                // transforma el hit testing). Sin alpha a propósito — ver el KDoc.
                .graphicsLayer { translationY = (1f - slide.value) * size.height }
        ) {
            content()
        }
    }
}
