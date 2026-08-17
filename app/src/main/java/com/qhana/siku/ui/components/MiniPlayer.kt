package com.qhana.siku.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.roundToInt
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.qhana.siku.R
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import com.materialkolor.contrast.Contrast
import com.materialkolor.hct.Hct
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qhana.siku.data.model.Song

import com.qhana.siku.ui.theme.AppContainerBoundsTransform
import com.qhana.siku.ui.theme.appContainerContentEnter
import com.qhana.siku.ui.theme.appContainerContentExitFast
import com.qhana.siku.ui.theme.appSpatialSpec
import com.qhana.siku.ui.theme.appFastSpatialSpec
import com.qhana.siku.ui.theme.appEffectsSpec
import com.qhana.siku.ui.theme.EXPRESSIVE_SLOW_EFFECTS_MS
import com.qhana.siku.ui.theme.ExpressiveSlowEffectsEasing
import com.qhana.siku.ui.theme.appFastEffectsSpec

// ============== MINI PLAYER ==============

// PROGRESO: el mini lo muestra como ANILLO ondulado alrededor de la carátula (ver
// [MiniPlayerContent], donde vive el `CircularWavyProgressIndicator`).
//
// Historia, porque es el cuarto intento y los tres anteriores se descartaron: (1) una barra de 3dp
// en el borde inferior que la forma de píldora obligaba a recortar 34dp por lado (a esa altura el
// contorno ya se ha metido hacia adentro); (2) un tinte del contenedor entero; (3) un RELLENO de
// altura completa cuyo borde vertical se leía como posición y que la píldora recortaba sola. El
// relleno resolvió la geometría, pero tenía una pega documentada: pasaba por DEBAJO del título, así
// que el texto tenía que medir su contraste contra el color del relleno (toda la maquinaria de
// `rememberMiniPlayerColors`/`rememberMiniPlayerSurfaces`).
//
// El anillo saca el progreso de debajo del texto: el título vuelve a apoyarse en el contenedor
// LIMPIO y la medida de contraste se simplifica. Cabe en los 8dp de aire que la carátula (56dp)
// deja dentro de los 72 de alto, va como HERMANO de la portada —no dentro de sus bounds— para no
// viajar con el shared element hacia el NowPlaying, y el tick de posición solo repinta (se lee
// dentro de la lambda de dibujo), nunca recompone.

/**
 * Paso de la escala tonal (HCT) propia de la barra: 8 puntos, el mismo salto que M3 usa entre
 * peldaños de superficie y que `SongListItem.ROW_ACTION_TONE_DELTA` para la píldora de acciones.
 * Visible como otra superficie sin partir la barra en dos.
 *
 * Es un DESPLAZAMIENTO DE TONO y no una opacidad de un rol sobre otro, que es lo que había hasta el
 * 31 jul. Con `TonalSpot` un 16 % de `primary` sobre `primaryContainer` daba justo estos ~8 puntos y
 * parecía bien elegido, pero la distancia entre dos roles la decide el ESTILO DE PALETA: en "fiel a
 * la carátula" (`Fidelity`) ambos se pegan al color fuente, así que la mezcla movía el tono un par de
 * puntos y el progreso desaparecía. Es lo que ya documenta `rememberRowActionColors` —un rol fijo no
 * garantiza separación—, colado por la puerta del alpha.
 */
private const val MiniPlayerToneStep = 8.0

/**
 * Contraste mínimo del contenido de la barra sobre su fondo: 4.5:1, el AA de WCAG para texto pequeño
 * (`bodySmall` lo es, y el glifo de "siguiente" son trazos de ~2dp, que a efectos de legibilidad se
 * comportan igual).
 *
 * De él sale el color del subtítulo, no al revés: se toma el tono más CERCANO al fondo que todavía
 * cumple el umbral, así que queda lo más apagado que la accesibilidad permite y la jerarquía contra
 * el título sale medida en vez de inventada. Sustituye a un alpha a ojo, que es lo que este archivo
 * ya evitaba con `onSurface`/`onSurfaceVariant` — pares que aquí no valen: son el contenido de la
 * escala NEUTRA y el contenedor de la barra ya no está en ella.
 */
private const val MiniPlayerMinContrast = 4.5

/**
 * Umbral que DECIDE si el acento del play colisiona con el contenedor: 3:1 (WCAG gráfico). Por encima,
 * `primary` ya destaca solo y se deja intacto; por debajo, colisiona y el play se re-tonaliza con
 * [boostedAccent]. Es además el PISO que ese realce no puede dejar de cumplir.
 *
 * Colisionan tanto la carátula monocromática (no hay acento con que resaltar) como cualquier tema
 * `Fidelity`, donde `primary` y `primaryContainer` se pegan los dos al color fuente por diseño del
 * estilo — o sea que la rama es el caso NORMAL con esa paleta, no una excepción rara.
 */
private const val MiniPlayerPlayMinContrast = 3f

/**
 * Contraste al que se ASPIRA a llevar el play cuando colisiona con el contenedor: 6:1, muy por
 * encima del piso de 3:1, para que domine como acción principal — un 3:1 lo dejaba en un tono medio
 * confundible con el botón "siguiente".
 *
 * Es una ASPIRACIÓN y no una garantía, y esa distinción es el arreglo del 10 ago: cuando el tono que
 * haría falta se sale de [MiniPlayerAccentToneCeiling] se cede contraste para conservar el acento
 * (ver [boostedAccent]). Perseguirlo a toda costa fue lo que produjo un play BLANCO PURO — medido en
 * device con paleta "fiel a la carátula": contenedor `#65559F` (tono 41), play `#FFFFFF`.
 */
private const val MiniPlayerPlayBoldContrast = 6.0

/**
 * Extremos de tono HCT donde un color todavía ES un color: 10 y 90, el primer y último peldaño
 * CROMÁTICO de la escala tonal de M3 (los roles `*Container` viven ahí; 0 y 100 son negro y blanco
 * puros por definición, no tonos de una paleta).
 *
 * Existen porque el gamut sRGB estrecha el croma según se acerca a los extremos: pedir "el tono que
 * dé 6:1" contra un contenedor de tono medio devuelve tonos de 97-98, donde ningún matiz sostiene
 * croma y HCT entrega blanco. Acotar aquí es lo que mantiene el play siendo el ACENTO DEL ÁLBUM en
 * vez de una pieza neutra, que es su trabajo en esta barra.
 */
private const val MiniPlayerAccentToneFloor = 10.0

/**
 * Lo que tarda la onda del anillo en avanzar UNA longitud de onda: el default del componente M3 es
 * `waveSpeed = wavelength` (una longitud de onda por segundo), y aquí se pasa explícito para que de él
 * salga también la duración del episodio (ver `waveEpisodeMs`).
 */
private const val MILLIS_PER_WAVELENGTH = 1_000L
private const val MiniPlayerAccentToneCeiling = 90.0

/**
 * Superficies de la barra derivadas del contenedor, desplazando el TONO hacia el centro de la escala.
 *
 * Que la dirección la decida el tono del fondo (una vez) es lo load-bearing: apunta siempre al CENTRO
 * de la escala, así que un contenedor claro se oscurece y uno oscuro se aclara, y ningún paso se sale
 * de rango. Vale igual en tema claro y oscuro sin una rama por tema.
 *
 * **Nota histórica:** la pista del anillo y el botón "siguiente" estaban a UNO y DOS pasos porque el
 * progreso era un RELLENO que le pasaba por DEBAJO al botón, y con un contenedor de tono medio una
 * derivación por capa invertía el sentido y devolvía al botón al tono del fondo (fondo 46 → relleno
 * 54 → botón 46, botón invisible sobre la mitad no reproducida — device, 31 jul). Desde que el
 * progreso es un ANILLO alrededor de la carátula, la pista y el botón ya NO se solapan: son dos
 * superficies independientes. Se conservan los mismos pasos porque dan la mejor separación —el botón
 * a dos pasos se lee claro contra la barra—, no porque haya nada que despejar.
 */
@Immutable
private data class MiniPlayerSurfaces(
    /** Pista tenue del anillo de progreso (tono derivado del fondo, visible sin competir con el arco). */
    val progress: Color,
    /** Contenedor del botón "siguiente": un tono claramente separado de la barra. */
    val buttonContainer: Color,
    /** Glifo del botón, con el contraste garantizado contra [buttonContainer]. */
    val buttonContent: Color,
    /** Contenedor del play Y color del arco del anillo: el ACENTO, pero garantizado contra el fondo. */
    val playContainer: Color,
    /** Glifo del play, con contraste garantizado contra [playContainer]. */
    val playContent: Color
)

/**
 * Despega el acento del álbum de la barra CONSERVÁNDOLO como acento: mismo matiz y mismo croma que
 * [primary], con el TONO llevado hasta donde el contraste lo pida.
 *
 * Sustituye a un `ensureContrast(primary, container, 6f)` que empujaba la luminosidad **HSL** hasta
 * alcanzar el ratio. Ese camino tiene dos fugas, las dos medidas en device el 10 ago con paleta
 * "fiel a la carátula" (contenedor `#65559F`, luminancia 0.118):
 *
 * 1. En HSL, `L → 1` es BLANCO sea cual sea la saturación. Contra ese contenedor el techo por el
 *    lado claro es 6.26:1, así que el único color que cumplía 6:1 era el blanco puro — y eso salía
 *    por pantalla: play y anillo en `#FFFFFF`, las dos piezas de mayor contraste de la pantalla y
 *    sin una gota del color del disco.
 * 2. Con un contenedor de luminancia entre 0.125 y 0.25 el objetivo es INALCANZABLE por los dos
 *    lados (ni blanco ni negro llegan a 6:1). El barrido salía igualmente por su tope devolviendo
 *    blanco, o sea incumpliendo en silencio el ratio que la firma prometía.
 *
 * Aquí el amo es el acento y el contraste va acotado: se pide el tono de
 * [MiniPlayerPlayBoldContrast], se recorta a la banda cromática ([MiniPlayerAccentToneCeiling] y
 * compañía) y solo si ni así se alcanza el piso de [MiniPlayerPlayMinContrast] —un fondo que no
 * admite 3:1 contra ningún tono cromático— se cede el croma y se va al extremo, que es cuando la
 * legibilidad pesa más que la identidad. Con el caso medido el play pasa de `#FFFFFF` a tono 90 con
 * el croma del acento, 4.85:1 contra la barra.
 *
 * La dirección la decide [MINI_MID_TONE], igual que el resto de derivaciones del archivo, así que no
 * hay una rama por tema.
 */
private fun boostedAccent(primary: Color, container: Hct): Color {
    val accent = Hct.fromInt(primary.toArgb())
    fun withTone(tone: Double) = Color(Hct.from(accent.hue, accent.chroma, tone).toInt())
    // `*Unsafe` devuelve un tono fuera de [0,100] cuando el ratio es inalcanzable por ese lado; ese
    // valor sirve igual, porque lo único que se hace con él es acotarlo.
    val ideal =
        if (container.tone > MINI_MID_TONE) Contrast.darkerUnsafe(container.tone, MiniPlayerPlayBoldContrast)
        else Contrast.lighterUnsafe(container.tone, MiniPlayerPlayBoldContrast)
    val chromatic = withTone(ideal.coerceIn(MiniPlayerAccentToneFloor, MiniPlayerAccentToneCeiling))
    val bar = Color(container.toInt())
    return if (contrastRatio(chromatic, bar) >= MiniPlayerPlayMinContrast) chromatic
    else withTone(ideal.coerceIn(HCT_TONE_MIN, HCT_TONE_MAX))
}

@Composable
private fun rememberMiniPlayerSurfaces(
    container: Color,
    onContainer: Color,
    /** Acento del álbum (`primary`): de aquí sale el play, forzado a contrastar con el contenedor. */
    primary: Color
): MiniPlayerSurfaces =
    remember(container, onContainer, primary) {
        val base = Hct.fromInt(container.toArgb())
        // Hacia el centro de la escala: es el único sentido con sitio para DOS pasos.
        val direction = if (base.tone > MINI_MID_TONE) -1.0 else 1.0
        fun step(times: Double) = Color(
            Hct.from(
                base.hue,
                base.chroma,
                (base.tone + direction * MiniPlayerToneStep * times).coerceIn(HCT_TONE_MIN, HCT_TONE_MAX)
            ).toInt()
        )
        val button = step(2.0)
        // Play (y arco del anillo) = el ACENTO del álbum, pero GARANTIZANDO que se despegue del
        // contenedor. Cuando `primary` y `primaryContainer` caen en la misma banda, el play se leía
        // como un hueco sobre el fondo (y el arco del anillo desaparecía).
        //
        // OJO con la intuición de que esa colisión es cosa de carátulas monocromáticas: la distancia
        // entre DOS ROLES la decide el ESTILO DE PALETA (lo mismo que ya documenta
        // [MiniPlayerToneStep]), y en "fiel a la carátula" (`Fidelity`) ambos se pegan al color
        // fuente, así que la rama entra a diario con temas de color vivo. Por eso el realce no puede
        // limitarse a "empujar hasta el ratio": tiene que seguir devolviendo un ACENTO, que es lo
        // que hace [boostedAccent]. Con un tema de color `primary` ya supera el piso de 3:1 contra
        // el fondo y esto es un no-op.
        val playBg =
            if (contrastRatio(primary, container) >= MiniPlayerPlayMinContrast) primary
            else boostedAccent(primary, base)
        MiniPlayerSurfaces(
            progress = step(1.0),
            buttonContainer = button,
            // `ensureContrast` mide en Float; el umbral es el mismo AA que usa el subtítulo.
            buttonContent = ensureContrast(onContainer, button, MiniPlayerMinContrast.toFloat()),
            playContainer = playBg,
            // Glifo del play por CONTRASTE real contra el botón (`maxContrastOn`), NO `onPrimary`: en
            // temas Fidelity `onPrimary` es un par de bajo contraste (medido: gris azulado 5.6:1 sobre
            // un primary casi negro) y el triángulo salía apagado. maxContrastOn da blanco nítido sobre
            // el botón oscuro (y negro sobre el claro del caso boost). Mismo criterio que el chip y el
            // toggle de letras: contenido sobre un acento = maxContrastOn.
            playContent = maxContrastOn(playBg)
        )
    }

/**
 * Elevación de la sombra del MiniPlayer: **nivel 4** del spec de M3 (sube del nivel 3 del FAB porque
 * la barra es la superficie más alta). Se dibuja a mano con [pillShadow], NO con la elevación
 * nativa del Card — ver ahí el porqué. En tema claro la sombra es la mitad del trabajo de despegar la
 * barra flotante; en oscuro M3 separa por tono y aporta poco.
 */
private val MiniPlayerShadowElevation = 8.dp

/** Opacidad del negro de la sombra pintada. Calibrada para acercarse a la elevación nativa de M3. */
private const val MINI_PLAYER_SHADOW_ALPHA = 0.28f

/** [pillShadow] sin transición que la module (mini sin scope compartido): sombra entera, constante. */
private val FullShadow: State<Float> = mutableStateOf(1f)

/**
 * Sombra de la píldora, dibujada como PÍXELES (`Paint.setShadowLayer`) en un `drawBehind` — la misma
 * técnica que `MaterialContainerTransform` de Material Components (que también pinta la sombra a mano,
 * porque la elevación nativa no se puede llevar a una transición).
 *
 * **Por qué a mano y no la elevación del Card.** La elevación nativa se dibuja mediante el RenderNode
 * del layer, y el overlay del `SharedTransitionScope` NO lo propaga: durante el vuelo la sombra
 * desaparecía y reaparecía DE GOLPE al asentar. Verificado que ni `Card(elevation)` ni
 * `Modifier.shadow` viajan (ambos usan `shadowElevation`). Pintada como píxeles, y en un Box HERMANO del
 * Card que no es shared element, se dibuja en su sitio desde el primer frame y solo cambia de opacidad.
 *
 * `setShadowLayer` solo funciona con aceleración hardware en **API 28+** (igual que Material
 * Components, que por eso deshabilita la sombra por debajo); en API 26–27 la barra queda sin sombra
 * —degradación elegante, se separa igual por color—.
 *
 * **[alphaFactor] es el PROGRESO del morph** (`shadowFactor`), no un fade elegido a mano: 1 en la
 * píldora, 0 en el player. Es lo que hace `MaterialContainerTransform` al INTERPOLAR la elevación —la
 * sombra solo es prominente cuando el contenedor es chico—.
 *
 * **La sombra NO viaja con el contenedor, y lo hizo (16 ago → 17 ago).** Estuvo montada como un shared
 * element aparte ([PLAYER_CONTAINER_SHARED_KEY] tenía un gemelo "solo-sombra") con `RemeasureToBounds`,
 * para que este Box se re-midiera al tamaño interpolado y pintara la sombra a ESE tamaño. Lo que se
 * pasó por alto es el COSTE DE DIBUJO: `setShadowLayer` es un filtro de desenfoque que HWUI resuelve
 * en un buffer fuera de pantalla del tamaño de la forma, y al arrancar el CIERRE la forma es la
 * PANTALLA ENTERA (el rect parte del player) — un desenfoque gaussiano de 1280×2772 en cada uno de los
 * primeros frames, que son justo los del recorrido visible del morph (~130 ms). Resultado: los frames
 * largos caían al principio, el spring (que va por tiempo) los saltaba y el cierre a la píldora se
 * leía como un parpadeo con bajón de fps — mientras el cierre a una FILA, que no lleva sombra, iba
 * fino. Al ABRIR el orden se invertía (barata al principio, cara al final, ya tapada por el player).
 * Y a tamaños grandes la sombra no aporta NADA que se vea: su halo queda fuera de la pantalla o bajo
 * el player opaco, y el `shadowFactor` la tiene casi a cero. Así que se dibuja siempre al tamaño de la
 * píldora, en su sitio: coste constante y chico, sin re-medir la capa por frame, y a la vista solo
 * cuando el contenedor ES la píldora — que es cuando se dibujaba antes también.
 */
private fun Modifier.pillShadow(shape: Shape, elevation: Dp, alphaFactor: State<Float>): Modifier = drawBehind {
    val blur = elevation.toPx()
    val a = MINI_PLAYER_SHADOW_ALPHA * alphaFactor.value
    if (blur <= 0f || a <= 0f || android.os.Build.VERSION.SDK_INT < 28) return@drawBehind
    val paint = android.graphics.Paint().apply {
        isAntiAlias = true
        color = android.graphics.Color.TRANSPARENT
        setShadowLayer(
            blur,                                   // radio de desenfoque
            0f,                                     // dx
            blur * 0.5f,                            // dy: la sombra cae hacia abajo
            Color.Black.copy(alpha = a).toArgb()
        )
    }
    drawIntoCanvas { canvas ->
        val nc = canvas.nativeCanvas
        when (val outline = shape.createOutline(size, layoutDirection, this)) {
            is Outline.Rounded -> {
                val rr = outline.roundRect
                nc.drawRoundRect(
                    rr.left, rr.top, rr.right, rr.bottom,
                    rr.topLeftCornerRadius.x, rr.topLeftCornerRadius.y, paint
                )
            }
            is Outline.Rectangle ->
                nc.drawRect(outline.rect.left, outline.rect.top, outline.rect.right, outline.rect.bottom, paint)
            is Outline.Generic -> nc.drawPath(outline.path.asAndroidPath(), paint)
        }
    }
}

/**
 * Default de los flows de progreso: sin posición ni duración el anillo no se dibuja.
 *
 * Es una instancia COMPARTIDA y no un `MutableStateFlow(0L)` en la firma: un default con
 * constructor crea un objeto nuevo en cada composición del mini que no los pase, y aquí solo hace
 * falta un cero constante. Los parámetros son no-nullables a propósito — con `StateFlow?` el
 * `collectAsStateWithLifecycle` quedaría detrás de un `?.`, o sea una llamada composable dentro de
 * una rama condicional, que es justo lo que conviene no tener en el camino de una recomposición
 * por segundo.
 */
private val ZeroProgressFlow: StateFlow<Long> = MutableStateFlow(0L)

/**
 * Título y subtítulo del mini sobre el contenedor teñido de la barra.
 *
 * El título es el rol que le corresponde al contenedor (`onPrimaryContainer`) y el subtítulo se
 * DERIVA de él (ver [rememberMiniPlayerColors]). No son `onSurface`/`onSurfaceVariant`: ese par es
 * el contenido de la escala neutra, y la barra ya no vive en ella.
 *
 * Sustituyen a un blanco puro y un `#1A1A1A` FIJOS con alpha 0.7/0.6, elegidos por
 * `isSystemInDarkTheme()`. Eran lo único de esta pantalla que no se teñía con la carátula, y
 * consultaban el tema del SISTEMA, que no tiene por qué coincidir con el de la app (ver
 * `AmbientPlayerActivity`, que lo fuerza oscuro).
 */
@Immutable
private data class MiniPlayerColors(
    val titleColor: Color,
    val contentColor: Color
)

/**
 * Par título/subtítulo sobre [container], con la jerarquía MEDIDA en vez de un alpha a ojo.
 *
 * El título usa el rol tal cual. El subtítulo conserva su matiz y su croma y se le fija el tono más
 * cercano al fondo que aún cumple [MiniPlayerMinContrast] — el mismo mecanismo con el que la
 * app resuelve el resto de sus pares derivados (`rememberRowActionColors`, el glifo activo del
 * toolbar). La dirección la decide el TONO del fondo, no una rama por tema, así que funciona igual
 * en claro y en oscuro y con cualquier paleta.
 */
@Composable
private fun rememberMiniPlayerColors(container: Color, onContainer: Color): MiniPlayerColors =
    remember(container, onContainer) {
        val containerTone = Hct.fromInt(container.toArgb()).tone
        val onHct = Hct.fromInt(onContainer.toArgb())
        // `*Unsafe` devuelve un tono fuera de [0,100] cuando el ratio es inalcanzable por ese lado
        // (un fondo de tono medio no admite 4.5:1 hacia ninguno de los dos extremos). Acotar lo
        // resuelve solo: el tono se va a 0 o 100, o sea al máximo contraste que ese lado puede dar,
        // que es exactamente lo que se quería pedir.
        val subtitleTone = (
            if (containerTone > MINI_MID_TONE) Contrast.darkerUnsafe(containerTone, MiniPlayerMinContrast)
            else Contrast.lighterUnsafe(containerTone, MiniPlayerMinContrast)
        ).coerceIn(HCT_TONE_MIN, HCT_TONE_MAX)
        MiniPlayerColors(
            titleColor = onContainer,
            contentColor = Color(Hct.from(onHct.hue, onHct.chroma, subtitleTone).toInt())
        )
    }

/**
 * Centro de la escala de tono HCT: decide hacia qué lado se alejan del fondo las cosas que se
 * derivan de él (la pista del anillo, el botón "siguiente", el subtítulo). Es lo que hace que la
 * barra funcione igual en tema claro y oscuro sin una sola rama por tema.
 */
private const val MINI_MID_TONE = 50.0

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun MiniPlayer(
    song: Song?,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onNextClick: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isBuffering: Boolean = false,
    /** Ajustes → Apariencia: rectángulo redondeado en vez de la píldora del diseño original. */
    roundedRect: Boolean = false,
    /**
     * Posición y duración como FLOWS, igual que el NowPlaying: la posición cambia cada segundo y
     * pasarla como valor recompondría el mini —y con él la carátula y el marquee— en cada tick.
     * Colectados aquí y leídos SOLO dentro de la lambda de dibujo, el tick invalida el dibujo del
     * anillo y nada más.
     */
    currentPositionFlow: StateFlow<Long> = ZeroProgressFlow,
    durationFlow: StateFlow<Long> = ZeroProgressFlow,
    /**
     * ¿La portada de esta barra VIAJA al reproductor ([PLAYER_ART_SHARED_KEY])? Solo cuando el player
     * se abre desde acá; desde una fila o un chip el player se engancha a otra punta (o a ninguna).
     *
     * Es un parámetro y no algo que el mini deduzca porque decide QUÉ SE DIBUJA, no solo qué se anima:
     * la punta se declara SIEMPRE —una que nace en el frame del gesto no tiene bounds y no hay match—,
     * pero un `sharedElementWithCallerManagedVisibility` con `visible = false` y nadie enfrente
     * simplemente NO SE PINTA. Sin este flag, abrir el reproductor desde una fila o un chip hacía
     * desaparecer de golpe la portada del mini en vez de desvanecerla con la barra.
     */
    artTravelsToPlayer: Boolean = false,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    if (song == null) return

    // Background SÓLIDO teñido con el álbum: `primaryContainer`, o sea que la barra se separa del
    // contenido por CROMA y no por tono.
    //
    // Por qué no un peldaño de la escala neutra, que es lo natural para una superficie flotante: en
    // esta app la escala está AGOTADA. El mini flota siempre sobre bloques de lista
    // `surfaceContainerHigh` (tono 92 en claro), y ninguno de los cinco peldaños deja hueco —
    // `surfaceContainer` queda a 2 puntos por arriba, `surfaceContainerHighest` a 2 por abajo, y
    // `surface` a 6 pero convirtiendo la barra flotante en la superficie MÁS BAJA de la pantalla.
    // Se probaron los dos primeros (31 jul, con captura en device) y el mini se perdía igual.
    //
    // Antes de eso hubo un `BorderStroke` de 1dp con `outlineVariant`, también descartado: un filo
    // de tono 80 cruzando por delante de filas y carátulas de colores no se lee como el borde de una
    // superficie sino como una línea ajena. Los dos intentos fallaron por lo mismo — pedirle a la
    // escala neutra una distancia que ya no tiene. `primaryContainer` no compite con nada de la
    // pantalla porque ninguna lista usa ese rol de fondo, y de paso da al reproductor identidad
    // propia, que es lo que hace cualquier mini-player conocido.
    val backgroundColor = MaterialTheme.colorScheme.primaryContainer

    // Las superficies derivadas de la barra —pista del anillo, botón "siguiente" y el par del play—
    // salen del contenedor desplazando el tono, ver [MiniPlayerSurfaces]. Opacas y por tono, no por
    // tintes translúcidos: así la separación no depende de qué haya debajo ni de la distancia que la
    // paleta activa deje entre dos roles.
    val onContainerColor = MaterialTheme.colorScheme.onPrimaryContainer
    val surfaces = rememberMiniPlayerSurfaces(
        container = backgroundColor,
        onContainer = onContainerColor,
        primary = MaterialTheme.colorScheme.primary
    )

    // El texto se mide contra el CONTENEDOR limpio: desde que el progreso es un anillo alrededor de
    // la carátula, nada le pasa por debajo al título (antes se medía contra el relleno, que era el
    // peor caso). Es el fondo real sobre el que se apoya el texto.
    val textColors = rememberMiniPlayerColors(
        container = backgroundColor,
        onContainer = onContainerColor
    )

    // Forma ELEGIBLE en Ajustes → Apariencia. Por defecto píldora completa: el MiniPlayer FLOTA
    // sobre la navbar del home (ya no va edge-to-edge con fondo plano hasta abajo).
    //
    // La alternativa es el token `extraLarge` de M3 (28dp), no un radio inventado. A 72dp de alto
    // la píldora tiene 36dp de radio, así que 28 se lee CLARAMENTE como rectángulo redondeado sin
    // caer en la esquina dura que desentonaría con el resto de contenedores de la app.
    //
    // No se anima entre las dos: es una preferencia que se cambia en otra pantalla, así que nadie
    // ve la transición — animarla solo añadiría un spec que mantener.
    val shape = if (roundedRect) MaterialTheme.shapes.extraLarge else RoundedCornerShape(50)

    // contentDescription se resuelve aquí (contexto @Composable) porque el lambda de semantics {} no lo es.
    val miniPlayerDesc = stringResource(R.string.mini_player_desc, song.title)

    // CONTAINER TRANSFORM: esta superficie y la del reproductor son LA MISMA cambiando de tamaño, no
    // dos pantallas que se relevan (ver [PLAYER_CONTAINER_SHARED_KEY] para las dos reglas del patrón).
    //
    // `scaleToBounds` (no `RemeasureToBounds`) en las DOS puntas: escala el contenido en vez de
    // re-medirlo, que es lo que un container transform necesita — re-medir haría que cada layout se
    // recompusiera a tamaños intermedios absurdos en pleno vuelo. Lo que sí sale de este contenedor por
    // su cuenta es la PORTADA, que viaja como shared element propio ([PLAYER_ART_SHARED_KEY]) y por
    // tanto ya no la escala este Card.
    //
    // **NO se pasa `clipInOverlayDuringTransition`** (queda el default `ParentClip`): el Card (Surface)
    // ya recorta su propio contenido a `shape`, así que sin el clip nada se derrama. El player SÍ lo
    // pasa porque es un Box a pantalla completa sin auto-recorte. La sombra NO va dentro de este
    // contenedor (se escalaría en bloque y se volvía una mancha): es un Box HERMANO, ver [pillShadow].
    // Estado del shared element de contenedor (el Card): morfea con `scaleToBounds` hasta el player.
    val containerState =
        if (sharedTransitionScope != null && animatedVisibilityScope != null) {
            with(sharedTransitionScope) { rememberSharedContentState(key = PLAYER_CONTAINER_SHARED_KEY) }
        } else null
    SideEffect {
        com.qhana.siku.data.util.JankProbe.note {
            "píldora: match=${containerState?.isMatchFound} viajaPortada=$artTravelsToPlayer " +
                "trans=${animatedVisibilityScope?.transition?.currentState}→${animatedVisibilityScope?.transition?.targetState}"
        }
    }

    // Prominencia de la sombra = PROGRESO del morph (1 = píldora, 0 = player), leído de la PROPIA
    // transición del container transform. Es el `setProgress` de MaterialContainerTransform: la sombra
    // acompaña a la contracción y se va con la expansión. En reposo la transición está en `Visible` →
    // factor 1 → sombra normal. Es un `State` que se lee DIFERIDO dentro del `drawBehind` de
    // [pillShadow]: cambia en cada frame del morph y leerlo en composición recompondría el mini por frame.
    //
    // **Dura lo que el fundido del contenido del morph ([EXPRESSIVE_SLOW_EFFECTS_MS], 300 — el mismo
    // token que `appContainerContentExit`), NO `SCREEN_TRANSFORM_MS` (500), y el motivo se midió con
    // la sonda el 17 ago**: esta animación vive en la transición de la CAPA (`AnimatedContent`), y una
    // `Transition` corre hasta que su animación más larga termina. Con 500 ms era, de largo, la más
    // larga —el bounds asienta a ~330— así que sostenía TODA la capa 200 ms de más con la sombra ya
    // invisible (al abrir la tapa el player desde los primeros frames): (1) al abrir, la paleta del
    // NavHost retenida "hasta que asiente" aterrizaba a los ~550 ms, y un cierre rápido la pillaba
    // pendiente y la aplicaba en el PRIMER frame del cierre (25-33 ms de recomposición de la
    // biblioteca justo al arrancar el morph); (2) al cerrar, `KeepUntilTransitionsFinished` mantenía el
    // árbol del NowPlaying hasta los ~530 ms y su desmontaje (~25 ms) caía cuando el usuario ya
    // scrolleaba. Con 300 la capa asienta con el bounds: la paleta aterriza bajo el player y el
    // desmontaje coincide con el final del morph. Sigue siendo un token de EFFECTS (es opacidad).
    val shadowFactor: State<Float> = if (animatedVisibilityScope != null) {
        animatedVisibilityScope.transition.animateFloat(
            transitionSpec = { tween(EXPRESSIVE_SLOW_EFFECTS_MS, easing = ExpressiveSlowEffectsEasing) },
            label = "miniShadowFactor"
        ) { if (it == EnterExitState.Visible) 1f else 0f }
    } else FullShadow

    val containerSharedModifier =
        if (containerState != null && sharedTransitionScope != null && animatedVisibilityScope != null) {
            with(sharedTransitionScope) {
                // El que SALE se dibuja ENCIMA (zIndex 1) y se disuelve sobre el que ENTRA, sólido
                // debajo: así al ABRIR se ve el contenedor CRECER (la píldora encima disolviéndose,
                // el player sólido debajo creciendo), no un revelado tipo slide.
                val exiting = animatedVisibilityScope.transition.targetState != EnterExitState.Visible
                Modifier.sharedBounds(
                    sharedContentState = containerState,
                    animatedVisibilityScope = animatedVisibilityScope,
                    boundsTransform = AppContainerBoundsTransform,
                    enter = appContainerContentEnter(),
                    // La píldora sale RÁPIDO (ver [appContainerContentExitFast]): es la superficie
                    // CHICA, así que al salir (abrir) se escala hacia ARRIBA y se pixela — desvanecerla
                    // en el primer tramo la quita de vista antes de que el mosaico se note. El player,
                    // que es la superficie grande, usa el exit LENTO en su punta.
                    exit = appContainerContentExitFast(),
                    // MISMOS parámetros que la otra punta, y tienen que serlo: las dos escalan el
                    // mismo par de rects, cada una desde su lado.
                    //
                    // `Fit` y NO `Crop`. `Crop` toma el MAYOR de los dos ratios, y como las dos
                    // superficies comparten ancho (ratio 1) mientras el de alto es ~34, agrandaba el
                    // contenido de esta barra TREINTA Y CUATRO veces: el "14 Occasions" gigante que se
                    // veía cruzando la pantalla durante el morph. `Fit` toma el MENOR (1), así que el
                    // mini se queda a su tamaño natural y solo se funde, que es lo que se espera de un
                    // contenido que se INTERCAMBIA. (`FillWidth`, el default, da lo mismo que `Fit`
                    // acá; lo que nunca hay que usar es el que amplifica la dimensión que más cambia.)
                    resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(
                        ContentScale.Fit,
                        Alignment.Center
                    ),
                    // Ver la escala en [CONTAINER_SHADOW_OVERLAY_Z].
                    zIndexInOverlay =
                        if (exiting) CONTAINER_SURFACE_OVERLAY_Z_EXITING
                        else CONTAINER_SURFACE_OVERLAY_Z_ENTERING
                )
            }
        } else Modifier

    // El MiniPlayer son DOS superficies superpuestas en un Box: DETRÁS, la SOMBRA (un Box vacío del
    // tamaño de la barra, en su sitio, que NO es shared element — ver [pillShadow]); DELANTE, el Card
    // con el container transform (`scaleToBounds`). El `modifier` externo (padding, arrastre) va en el
    // Box para que las dos compartan footprint exacto y la sombra quede pegada al borde de la barra.
    // Durante el morph el Card se dibuja en el overlay (encima de todo) y la sombra sigue aquí debajo,
    // fundiéndose con [shadowFactor]: al abrir el player la tapa en los primeros frames; al cerrar
    // aparece bajo la superficie que llega. Es lo que se ve; lo que no se ve ya no se dibuja.
    Box(modifier = modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(ComponentConfig.MiniPlayerHeight)
                .pillShadow(shape, MiniPlayerShadowElevation, shadowFactor)
        )

        // Card: contenedor OFICIAL de M3 para "contenido de un solo tema que se toca para abrir".
        // M3 no tiene componente de mini-player; un toolbar no aplica (no es una fila de acciones y
        // necesita tap en toda la superficie). Card da ese onClick nativo (expandir al NowPlaying).
        Card(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                // Alto fijo al spec del floating toolbar M3 Expressive (64.dp).
                .height(ComponentConfig.MiniPlayerHeight)
                // DESPUÉS del tamaño: el container transform parte de los bounds ya medidos de la barra.
                .then(containerSharedModifier)
                .semantics { contentDescription = miniPlayerDesc },
            shape = shape,
            // Contenedor TRANSPARENTE: el tinte animado del álbum se dibuja en el Box interno para que
            // NO reciba el overlay tonal del Card (que teñiría el color según la elevación).
            colors = CardDefaults.cardColors(containerColor = Color.Transparent),
            // Elevación 0: la sombra la pinta [travelingShadow] en el Box de atrás. La elevación NATIVA
            // del Card no vale aquí porque el overlay del shared element no la propaga.
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            val position = currentPositionFlow.collectAsStateWithLifecycle()
            val duration = durationFlow.collectAsStateWithLifecycle()

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(backgroundColor)
            ) {
                // Contenido. El progreso ya no rellena este contenedor: lo dibuja el anillo alrededor
                // de la carátula, así que el fondo queda LIMPIO bajo el texto.
                MiniPlayerContent(
                    song,
                    isPlaying,
                    onPlayPause,
                    onNextClick,
                    textColors,
                    surfaces,
                    isBuffering,
                    position,
                    duration,
                    artTravelsToPlayer,
                    sharedTransitionScope,
                    animatedVisibilityScope
                )
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MiniPlayerContent(
    song: Song,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onNextClick: () -> Unit,
    textColors: MiniPlayerColors,
    /** Superficies derivadas de la barra: colores del botón "siguiente" y pista del anillo. */
    surfaces: MiniPlayerSurfaces,
    isBuffering: Boolean,
    /** Posición/duración para el anillo de progreso; se leen dentro del dibujo (repaint, no recompose). */
    position: State<Long>,
    duration: State<Long>,
    /** Ver el KDoc del parámetro homónimo en [MiniPlayer]. */
    artTravelsToPlayer: Boolean,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    // contentDescription hoisteados (los lambdas de semantics {} no son @Composable).
    val playPauseDesc = if (isPlaying) stringResource(R.string.common_pause) else stringResource(R.string.common_play)
    val nextDesc = stringResource(R.string.common_next)
    // El icono muestra "pausa" también durante el buffering (transición de canción,
    // re-buffer): es el mismo criterio que usa NowPlaying (PLAYING || BUFFERING) y evita
    // el parpadeo del flapping de isPlaying sin retrasar el estado con un reloj.
    val showAsPlaying = isPlaying || isBuffering

    // Transporte: play resaltado (el ACENTO del álbum) y "siguiente" tonal.
    //
    // El play ya NO es `primary` crudo: cuando `primary` cae en la misma banda tonal que
    // `primaryContainer` (el fondo) el play se leía como un hueco sobre él. Ahora sale de
    // [MiniPlayerSurfaces], que en ese caso lo re-tonaliza conservando matiz y croma
    // ([boostedAccent]) y recalcula el glifo — no-op mientras el par ya se separe solo. Ese mismo
    // color va al arco del anillo, para que play y progreso sean el mismo acento.
    //
    // El siguiente tampoco es `secondaryContainer` crudo: ese rol cae en la MISMA banda tonal que el
    // fondo del mini desde que la barra pasó a `primaryContainer`, o sea el botón desaparecía. Y
    // tampoco es un derivado con hue/croma de OTRO rol — probado el 31 jul con
    // `rememberRowActionColors`: el croma lo pone el rol, y con el de `secondary` salía un botón gris
    // sobre una barra teñida, la única pieza sin color de toda la barra. Es el propio contenedor a
    // DOS pasos de tono (ver [MiniPlayerSurfaces]): mismo matiz, mismo croma, y separado del fondo.
    val sideContainer = surfaces.buttonContainer
    val sideContent = surfaces.buttonContent
    val playContainer = surfaces.playContainer
    val playContent = surfaces.playContent

    // Izados: los `transitionSpec` de los dos `AnimatedContent` del botón de play no son lambdas
    // composables. Mismos tokens que el glifo del transporte del NowPlaying — es el mismo control.
    val bufferFadeIn = appEffectsSpec<Float>()
    val bufferFadeOut = appFastEffectsSpec<Float>()
    val glyphEnterScale = appSpatialSpec<Float>()
    val glyphExitScale = appFastSpatialSpec<Float>()

    Row(
        // fillMaxHeight + CenterVertically: el contenido se centra en los 64.dp fijos del Surface
        // sin depender de un padding vertical calculado a mano. Padding interno = spec del
        // floating toolbar (8dp).
        modifier = Modifier.fillMaxHeight().padding(horizontal = ComponentConfig.FloatingBarInnerPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Punta ORIGEN de la portada compartida ([PLAYER_ART_SHARED_KEY]). El resto del contenido del
        // mini SÍ se intercambia con un cruce de opacidad, como manda el container transform; la
        // portada es la excepción porque existe en las dos puntas.
        //
        // `sharedElementWithCallerManagedVisibility` y no el atado al scope, igual que en el otro
        // extremo (ver el comentario en `AlbumArtSection`): del scope se lee solo la INTENCIÓN
        // (`targetState`), nunca su duración — atar la punta al scope mataba el morph cuando esa
        // transición terminaba y la portada aparecía quieta en su destino. Exactamente una de las dos
        // puntas está `visible` en cada sentido, que es lo que decide cuál es origen y cuál destino.
        //
        // Se declara SIEMPRE, no solo al abrir: una punta que nace en el frame del gesto no tiene
        // bounds que ofrecer y no hay match (la regla del 30 jul). Mientras nadie la empareja es un
        // shared element solitario, inofensivo.
        val artSharedModifier =
            if (sharedTransitionScope != null && animatedVisibilityScope != null) {
                with(sharedTransitionScope) {
                    // `|| !artTravelsToPlayer`: si nadie va a recogerla al otro lado, esta punta se
                    // queda VISIBLE y se desvanece con la barra, en vez de desaparecer de golpe.
                    val artVisible =
                        animatedVisibilityScope.transition.targetState == EnterExitState.Visible ||
                            !artTravelsToPlayer
                    Modifier.sharedElementWithCallerManagedVisibility(
                        sharedContentState = rememberSharedContentState(key = PLAYER_ART_SHARED_KEY),
                        visible = artVisible,
                        boundsTransform = AppContainerBoundsTransform,   // el spring del contenedor: aterriza con él (ver NowPlayingArt)
                        zIndexInOverlay = CONTAINER_ART_OVERLAY_Z
                    )
                }
            } else Modifier

        // Carátula + anillo de progreso ONDULADO (Expressive). Es un `CircularWavyProgressIndicator`
        // REAL de M3 que llena el Box CONTENEDOR, un poco mayor que la portada (deja sitio al trazo +
        // la amplitud de la onda en el aire que la barra reserva). Es un HERMANO de la carátula y NO
        // entra en los bounds del shared element: la portada viaja sola al NowPlaying y el anillo se
        // queda, desvaneciéndose con el MiniPlayer. `progress` es una lambda que lee la posición → el
        // tick la repinta, no recompone (mismo patrón que el indicador de descarga del toolbar).
        // Mismo acento que el play (garantizado a contrastar con el fondo): así el progreso no
        // desaparece con carátula monocromática, donde `primary` crudo se fundía con el contenedor.
        val arcColor = surfaces.playContainer
        val trackColor = surfaces.progress
        val density = LocalDensity.current
        val ringStroke = remember(density) {
            Stroke(width = with(density) { ComponentConfig.MiniPlayerRingStroke.toPx() }, cap = StrokeCap.Round)
        }
        // ONDA EPISÓDICA: la onda del anillo se mueve cuando algo pasa —aparece la píldora, cambia la
        // canción, se reanuda la reproducción— y da EXACTAMENTE una vuelta completa al anillo antes
        // de pararse. La duración no es un número elegido: es el PERIODO de la propia animación del
        // componente. M3 reparte la onda en `round(2πr / λ)` crestas (r = radio del anillo menos medio
        // trazo, ver `CircularShapes.update`) y la desplaza a una longitud de onda por segundo, así que
        // el patrón vuelve a su posición inicial tras `crestas` segundos — parar justo ahí es lo que
        // evita el salto de fase que daría un corte a mitad (`waveSpeed = 0` reencuadra la fase a 0).
        // Por qué a ratos y no siempre: ver el comentario de `waveSpeed` abajo.
        val waveEpisodeMs = remember(density) {
            with(density) {
                val radiusPx = ComponentConfig.MiniPlayerRingSize.toPx() / 2f -
                    ComponentConfig.MiniPlayerRingStroke.toPx() / 2f
                val crests = (2.0 * Math.PI * radiusPx / WavyProgressIndicatorDefaults.CircularWavelength.toPx())
                    .roundToInt()
                crests * MILLIS_PER_WAVELENGTH
            }
        }
        var waveMoving by remember { mutableStateOf(false) }
        LaunchedEffect(song.id, showAsPlaying, waveEpisodeMs) {
            if (!showAsPlaying) {
                waveMoving = false
                return@LaunchedEffect
            }
            waveMoving = true
            delay(waveEpisodeMs)
            waveMoving = false
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(ComponentConfig.MiniPlayerRingSize)
        ) {
            CircularWavyProgressIndicator(
                progress = {
                    val total = duration.value
                    if (total <= 0L) 0f else (position.value.toFloat() / total).coerceIn(0f, 1f)
                },
                // Tamaño EXPLÍCITO (no fillMaxSize): el componente aplica su propio `.size(48dp)`
                // interno, y con fillMaxSize el anillo se estiraba hasta el borde del bar y asomaba
                // por el pill. Con la medida propia queda del tamaño del box (padding fijo de
                // MiniPlayerRingContainerPadding al pill) y concéntrico con la carátula.
                modifier = Modifier.size(ComponentConfig.MiniPlayerRingSize),
                color = arcColor,
                trackColor = trackColor,
                stroke = ringStroke,
                trackStroke = ringStroke,
                // Onda al reproducir, ANILLO PLANO en pausa —igual que la barra del NowPlaying, que
                // aplana la onda con `if (isPlaying) 1f else 0f`—. El indicador anima el aplanado él
                // solo (Increasing/DecreasingAmplitudeAnimationSpec internos). `1f` fijo, no el
                // default: ese aplana la onda cerca del 0 % y del 95 %, y el usuario ya lo descartó
                // para el NowPlaying (ondula hasta el final).
                //
                // El criterio es `showAsPlaying` y no `isPlaying` a secas por lo mismo que el icono
                // del play: en el cambio de canción con streaming `isPlaying` cae un instante
                // mientras bufferiza, y con el crudo la onda se aplanaba y volvía a levantarse justo
                // en ese frame — un parpadeo del anillo con el reproductor sin pausar.
                amplitude = { if (showAsPlaying) 1f else 0f },
                // La onda se mueve A RATOS, no en continuo (ver `waveMoving` arriba): con el default
                // la fase avanza en cada vsync y la píldora —persistente en toda la biblioteca—
                // obligaba a la app a renderizar a 120 fps SIN PARAR mientras sonara algo. Dos costes
                // medidos el 17 ago con Perfetto: batería (60 frames por cada 500 ms de sesión de
                // escucha) y, peor, **buffer stuffing**: cualquier frame atrasado (el morph de
                // abrir/cerrar) deja a la app un buffer por delante de SurfaceFlinger, y mientras no
                // deje de producir la cola no drena — cada frame se presenta un vsync tarde y SF tira
                // uno de cada tres en los scrolls siguientes ("Buffer Stuffing" 469 / "Dropped Frame"
                // 167 en 30 s, con la app terminando A TIEMPO). El componente no expone la fase, así
                // que aquí no vale la regla de "publicar solo cuando mueve un píxel" (la de la cookie
                // del play y la onda del NowPlaying); lo que sí vale es que el movimiento sea un
                // EPISODIO acotado y que en reposo la velocidad sea cero. La velocidad, cuando se
                // mueve, es el default del componente hecho explícito (una longitud de onda por
                // segundo): de ella sale la duración del episodio.
                waveSpeed = if (waveMoving) WavyProgressIndicatorDefaults.CircularWavelength else 0.dp
            )
            // En el mini la carátula es SIEMPRE un círculo, reproduzca o no (decisión de diseño:
            // a este tamaño el morph a squircle no se leía y solo agregaba ruido). Se usa
            // AlbumArtMorphShape(1f) —el mismo path de círculo que el NowPlaying en pausa— para que la
            // portada se lea igual en las dos puntas del container transform.
            // El tamaño se DERIVA del anillo (que a su vez sale del alto y el padding al pill), no
            // de una constante suelta: si cambia el alto o el padding, la carátula lo sigue.
            AlbumArt(
                albumArtUri = song.albumArtUriString,
                size = ComponentConfig.MiniPlayerArtSize,
                shape = AlbumArtMorphShape(progress = 1f),
                modifier = artSharedModifier
            )
        }

        Spacer(modifier = Modifier.width(ComponentConfig.MiniPlayerTextGap))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.titleSmallEmphasized,
                color = textColors.titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.basicMarquee(
                    // Marquee finito: el MiniPlayer es persistente, un scroll infinito
                    // mantendría una animación viva recomponiéndose siempre. 3 pasadas bastan.
                    iterations = 3,
                    velocity = 30.dp
                )
            )
            Spacer(modifier = Modifier.height(ComponentConfig.MiniPlayerTextLineGap))
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodySmall,
                color = textColors.contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // MISMO gap que hay entre la carátula y el texto: el bloque de título/artista queda con el
        // mismo aire a ambos lados. Con el gap de los botones (4dp) el texto llegaba pegado al
        // play. Todo el ancho que sobra se lo sigue quedando la Column de arriba.
        Spacer(modifier = Modifier.width(ComponentConfig.MiniPlayerTextGap))

        // Solo play + siguiente. El "anterior" salió del mini a propósito: con tres controles
        // el texto se quedaba en ~155dp (marquee permanente) y los botones no llegaban al
        // mínimo táctil de 48dp. Sigue disponible en el NowPlaying, la notificación, Android
        // Auto y Wear — que es donde lo ponen YT Music, Spotify y Apple Music.
        //
        // Botón Play/Pause — círculo resaltado (primary).
        MiniRoundButton(
            onClick = onPlayPause,
            containerColor = playContainer,
            contentColor = playContent,
            desc = playPauseDesc
        ) {
            AnimatedContent(
                targetState = isBuffering,
                transitionSpec = { fadeIn(bufferFadeIn) togetherWith fadeOut(bufferFadeOut) },
                label = "bufferingAnimation"
            ) { buffering: Boolean ->
                if (buffering) {
                    SideEffect { com.qhana.siku.data.util.JankProbe.mark { "LoadingIndicator del MINI compuesto" } }
                    // LoadingIndicator expressive (morfea entre MaterialShapes), igual que
                    // el buffering del NowPlaying/Lyrics.
                    LoadingIndicator(
                        modifier = Modifier.size(24.dp),
                        color = playContent
                    )
                } else {
                    AnimatedContent(
                        targetState = showAsPlaying,
                        transitionSpec = {
                            scaleIn(glyphEnterScale) togetherWith scaleOut(glyphExitScale)
                        },
                        label = "playPauseAnimation"
                    ) { playing: Boolean ->
                        if (playing)
                            MaterialSymbol("pause", color = playContent, size = 24.sp, fill = true)
                        else
                            MaterialSymbol("play_arrow", color = playContent, size = 24.sp, fill = true)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.width(ComponentConfig.FloatingBarItemGap))

        // Botón Siguiente — mismo círculo, en tonal: el par se lee como transporte y el color
        // (`primary` contra un tonal derivado del fondo) marca cuál es la acción principal.
        MiniRoundButton(
            onClick = onNextClick,
            containerColor = sideContainer,
            contentColor = sideContent,
            desc = nextDesc
        ) {
            // Icono skip RELLENO (variante fill del símbolo, no el outline).
            MaterialSymbol("skip_next", color = sideContent, size = 24.sp, fill = true)
        }
    }
}

/**
 * Botón REDONDO del MiniPlayer (círculo de [ComponentConfig.MiniPlayerButtonSize]):
 * `FilledIconButton` REAL de M3 Expressive. El color del contenedor puede venir animado (play/pause
 * con el acento del álbum). Trae ripple/state-layer, touch target y semántica del componente.
 *
 * **La forma PRESIONADA es la del componente, no la de reposo.** Hasta el 30 jul se pasaba
 * `pressedShape = CircleShape`, o sea la MISMA que en reposo: con las dos formas iguales no hay nada
 * que interpolar y el shape-morph de M3 Expressive quedaba anulado en silencio — el botón se
 * limitaba al ripple. Se notaba al lado del transporte del NowPlaying, cuyos botones sí se deforman
 * bajo el dedo, que es exactamente lo que reportó el usuario. Dejando que el default decida, el
 * círculo se achata al presionar y vuelve al soltar, igual que el resto de la app.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MiniRoundButton(
    onClick: () -> Unit,
    containerColor: Color,
    contentColor: Color,
    desc: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    FilledIconButton(
        onClick = onClick,
        shapes = IconButtonDefaults.shapes(shape = CircleShape),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        modifier = modifier
            .size(ComponentConfig.MiniPlayerButtonSize)
            .semantics { contentDescription = desc }
    ) {
        content()
    }
}
