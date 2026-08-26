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
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.qhana.siku.ui.theme.LocalAppColors
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
import com.qhana.siku.ui.theme.appCardColors
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
// [MiniPlayerContent], donde vive el [WavyProgressRing]).
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
 * Separación mínima del botón "siguiente" respecto a la barra: **1.8:1**.
 *
 * Es un CONTRASTE y no un número de pasos de tono, y ésa es la corrección del 20 ago. Un paso fijo
 * produce separaciones distintas según dónde caiga la barra —medido en device el mismo día: **1.35:1**
 * con carátula monocroma (barra en tono 30) y **1.27:1** en tema claro (barra en tono 73)—, o sea que
 * el botón se distinguía más o menos de su fondo según el disco que sonara. Pidiendo el contraste, el
 * desplazamiento sale solo y es el mismo en cualquier tema.
 *
 * El valor sale de dos medidas que coinciden: es lo que M3 da a ESE MISMO botón sobre su superficie en
 * el reproductor (`secondaryContainer` sobre `surface`, **1.90** medido con carátula monocroma) y lo
 * que el mini tenía antes de esta jornada (**1.82**). No es un mínimo de accesibilidad —no existe
 * ninguno para "un contenedor tonal sobre otro"; el del contenido va aparte, en
 * [MiniPlayerMinContrast]— sino el punto en el que dos superficies del mismo color se leen como dos
 * piezas.
 */
private const val MiniPlayerButtonMinContrast = 1.8

/**
 * Contraste mínimo del contenido de la barra sobre su fondo: 4.5:1, el AA de WCAG para texto pequeño
 * (`bodySmall` lo es, y el glifo de "siguiente" son trazos de ~2dp que a efectos de legibilidad se
 * comportan igual).
 *
 * **El glifo estuvo un rato en 3:1** —el mínimo de WCAG 1.4.11 para objetos gráficos, que es el que
 * formalmente le toca a un icono— porque con 4.5 salía BLANQUECINO. Volvió aquí el 20 ago por decisión
 * del usuario, y la razón es buena: en la píldora el contenedor del botón tiene que separarse ADEMÁS
 * de la barra, y cada punto que gana contra ella lo pierde contra su propio glifo (medido con carátula
 * monocroma: el botón pasó de tono 38 a 46 y el glifo cayó de 5.16:1 a 3.97:1 sin cambiar de color).
 * Con los fondos de las dos pantallas siendo distintos por construcción, **manda el contraste**: un
 * icono claro se lee, uno del color "correcto" a 3:1 no siempre. El precio es que sobre un contenedor
 * de tono medio el glifo acaba cerca del extremo claro de la escala.
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
 * superficies independientes, y el único trabajo que le queda al desplazamiento del botón es separarlo
 * de la BARRA. El 20 ago ese desplazamiento dejó de contarse en pasos y pasó a pedirse por CONTRASTE
 * — el porqué, en [MiniPlayerButtonMinContrast].
 *
 * Que hoy pista y botón queden en el MISMO peldaño (uno cada uno) no los confunde: llevan cromas
 * distintos —la pista el de la barra, el botón el de `secondaryContainer`— y viven en extremos
 * opuestos de la píldora, un trazo de 4dp alrededor de la carátula contra un círculo de 48. La regla
 * de que "un paso lo colapsaría sobre la pista" valía cuando los dos salían del mismo croma.
 *
 * **DESCARTES del 20 ago, ninguno de los cuales hay que reintentar** (la jornada entera salió de que
 * el icono del "siguiente" se veía blanco en la píldora y beige en el reproductor):
 *
 * 1. **El par CRUDO aquí, como en el reproductor**: `secondaryContainer` cae en el mismo peldaño que
 *    la barra (30.8 contra 29.8) y el botón daba 1.0:1. No es calibración —
 *    **`secondaryContainer` sobre `primaryContainer` no es un patrón de M3**, que garantiza el
 *    contraste de un rol contra `surface`, no entre dos contenedores de acento.
 * 2. **Perseguir 3:1 entre botón y barra**: imposible. `secondaryContainer` tiene un TECHO de
 *    **2.31:1 contra negro puro**, así que ningún fondo oscuro llega a 3 — y el propio reproductor
 *    entrega 1.80. El 3:1 de M3 es para el ICONO (4.59 en las dos pantallas), nunca para el contenedor
 *    contra su superficie.
 * 3. **Mover la BARRA para que el botón pudiera ser el rol crudo**: bajarla la hunde bajo los chips de
 *    la home; subirla se implementó y se revirtió tras verla en device — ver [miniPlayerContainer],
 *    que es donde vive la lección (la igualdad de valor no da igualdad percibida).
 * 4. **Contar la separación en PASOS DE TONO** en vez de pedir un contraste: un paso fijo daba 1.35:1
 *    con carátula monocroma y 1.27:1 en tema claro, o sea el botón se distinguía más o menos de su
 *    fondo según el disco. Ver [MiniPlayerButtonMinContrast].
 * 5. **Sacar el CROMA del botón del par `secondaryContainer`** (para que coincidiera con el del
 *    reproductor): lo dejaba con el 48 % del croma de la barra en tema claro y el 33 % con carátula
 *    monocroma — el mismo "única pieza sin color de la barra" del 31 jul, por otra puerta.
 */
@Immutable
private data class MiniPlayerSurfaces(
    /** Pista tenue del anillo de progreso (tono derivado del fondo, visible sin competir con el arco). */
    val progress: Color,
    /**
     * Contenedor del botón "siguiente": el matiz y el croma de la BARRA con el tono llevado hasta
     * [MiniPlayerButtonMinContrast] — mismo material, otra altura.
     */
    val buttonContainer: Color,
    /**
     * Glifo del botón "siguiente": el propio [buttonContainer] alejado por tono hasta cumplir
     * [MiniPlayerMinContrast] — mismo croma que su fondo, la claridad que haga falta para leerse.
     */
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
 * "fiel a la carátula" (contenedor `#65559F`, luminancia 0.118). (Nota: `ensureContrast` YA NO va en
 * HSL desde el 17 ago — ver su KDoc, que corrige una tercera fuga del mismo espacio de color: el
 * matiz fantasma de los colores casi blancos. Lo de aquí abajo describe cómo era entonces, y esta
 * función sigue haciendo falta porque su objetivo es OTRO: acotar a la banda cromática para que el
 * play siga siendo el acento del álbum, no solo cumplir un ratio.)
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

/**
 * Tonos canónicos de `primaryContainer` en la escala de M3: **30 en tema oscuro y 90 en claro**. Son
 * el destino al que [miniPlayerContainer] lleva el fondo de la barra cuando el estilo de paleta lo
 * deja del lado equivocado — no un valor elegido a ojo, sino el peldaño que ese rol ocupa en
 * cualquier tema de Material.
 */
private const val MiniPlayerContainerToneDark = 30.0

private const val MiniPlayerContainerToneLight = 90.0

/**
 * Fondo de la barra: `primaryContainer` con el TONO enderezado al lado del tema (oscuro en tema
 * oscuro, claro en tema claro), conservando matiz y croma — o sea la misma identidad de color, otra
 * polaridad.
 *
 * **PROBADO Y DESCARTADO el 20 ago: derivar la barra DEL BOTÓN** (colocarla un paso por encima de
 * `secondaryContainer`, para que el botón pudiera ser el rol CRUDO y coincidir al píxel con el del
 * reproductor). Se implementó, se vio en device y se revirtió: **la igualdad de valor no da igualdad
 * percibida**. Verificado sobre la captura —botón `#57462A` en las dos pantallas, idénticos bit a
 * bit— y aun así el del mini se veía más oscuro, porque es **contraste simultáneo**: allá el fondo
 * está 20 puntos de tono POR DEBAJO del botón y aquí la barra quedaba 7 por ENCIMA, o sea el mismo
 * color con la polaridad de su entorno invertida. Igualar el valor y igualar la apariencia son
 * objetivos INCOMPATIBLES mientras los fondos difieran, y de los dos manda la apariencia. Con la barra
 * en su sitio el botón vuelve a ser una pieza ELEVADA sobre ella, como su gemelo lo es sobre
 * `surface`, y el precio son 7 puntos de tono de más en el contenedor.
 *
 * **Por qué el tono no puede ser el del rol:** lo decide el ESTILO DE PALETA, no el tema. Con el spec
 * 2025 y estilos como `Fidelity` o `Vibrant`, `primary` y `primaryContainer` se pegan los dos al
 * color de la carátula, así que en tema OSCURO la barra salía CLARA. Y como el play es el acento
 * (`primary`, también claro), no contrastaba contra ella y [boostedAccent] lo empujaba al extremo
 * contrario: play OSCURO sobre barra CLARA en la píldora, contra play CLARO sobre fondo oscuro en el
 * reproductor — el mismo botón con la polaridad invertida en las dos superficies, y encima
 * cambiándola a mitad del container transform que lleva una a la otra.
 *
 * Con el fondo del lado del tema, `primary` recupera su contraste natural contra él y el realce se
 * queda en no-op: el play de la píldora y el del reproductor vuelven a ser el MISMO par de colores.
 *
 * **Solo actúa cuando el tono está del lado contrario al del tema.** Un `primaryContainer` que ya
 * cae donde debe (TonalSpot y compañía) se devuelve intacto, así que los temas que estaban bien no
 * cambian ni un píxel. El lado del tema se lee de `surface` y no de un flag: es el fondo real sobre
 * el que flota la barra, y ya está resuelto por el esquema.
 */
private fun miniPlayerContainer(primaryContainer: Color, surface: Color): Color {
    val base = Hct.fromInt(primaryContainer.toArgb())
    val darkTheme = Hct.fromInt(surface.toArgb()).tone <= MINI_MID_TONE
    val wrongSide = if (darkTheme) base.tone > MINI_MID_TONE else base.tone < MINI_MID_TONE
    if (!wrongSide) return primaryContainer
    val tone = if (darkTheme) MiniPlayerContainerToneDark else MiniPlayerContainerToneLight
    return Color(Hct.from(base.hue, base.chroma, tone).toInt())
}

@Composable
private fun rememberMiniPlayerSurfaces(
    container: Color,
    /** Acento del álbum (`primary`): de aquí sale el play, forzado a contrastar con el contenedor. */
    primary: Color
): MiniPlayerSurfaces =
    remember(container, primary) {
        val base = Hct.fromInt(container.toArgb())
        // Hacia el centro de la escala: un contenedor claro se oscurece y uno oscuro se aclara, así que
        // ningún paso se sale de rango y no hace falta una rama por tema.
        val direction = if (base.tone > MINI_MID_TONE) -1.0 else 1.0
        fun step(times: Double) = Color(
            Hct.from(
                base.hue,
                base.chroma,
                (base.tone + direction * MiniPlayerToneStep * times).coerceIn(HCT_TONE_MIN, HCT_TONE_MAX)
            ).toInt()
        )
        // Botón "siguiente": **el propio contenedor de la barra** —su matiz y su croma— con el TONO
        // llevado hasta [MiniPlayerButtonMinContrast]. Los dos ejes salen del FONDO, no de un rol:
        //
        //   · el CROMA y el MATIZ, porque el botón tiene que ser del mismo material que la barra. Del
        //     20 ago hasta esta línea salieron del par `secondaryContainer` —para que el control
        //     coincidiera con el del reproductor— y eso reintrodujo el fallo que el repo ya tenía
        //     documentado del 31 jul: el botón conservaba **el 48 % del croma de la barra en tema
        //     claro y el 33 % con carátula monocroma** (medido en device), o sea una pieza beige
        //     lavada dentro de una píldora dorada. La distancia entre dos roles la decide el ESTILO
        //     DE PALETA, así que ningún rol fijo puede prometer que su croma se parezca al del fondo;
        //     el fondo sí.
        //   · el TONO, porque de la barra es de quien el botón tiene que despegarse, y
        //     `secondaryContainer` cae en su MISMO peldaño (30.8 contra 29.8 — el botón invisible).
        //
        // Lo que sí se conserva del par es el GLIFO (ver `buttonContent`): ése es contenido, no
        // superficie, y su color no tiene por qué cambiar entre las dos puntas del morph.
        val ideal =
            if (base.tone > MINI_MID_TONE) Contrast.darkerUnsafe(base.tone, MiniPlayerButtonMinContrast)
            else Contrast.lighterUnsafe(base.tone, MiniPlayerButtonMinContrast)
        val button = Color(
            Hct.from(
                base.hue,
                base.chroma,
                // `*Unsafe` se sale de [0,100] cuando el ratio es inalcanzable por ese lado; acotarlo
                // deja el botón en el extremo, que es lo más separado que la escala permite.
                ideal.coerceIn(HCT_TONE_MIN, HCT_TONE_MAX)
            ).toInt()
        )
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
            // Glifo del "siguiente": **el propio botón**, alejado por tono hasta contrastar con él. Es
            // el mismo principio que arma el contenedor un nivel más afuera —derivar del fondo y
            // garantizar contraste— aplicado al contenido, así que el icono se queda con TODO el croma
            // que el tema tenga (el del disco en una paleta de color, casi nada en una monocroma) sin
            // depender de que un rol se lo traiga.
            //
            // Estuvo saliendo de `onSecondaryContainer` (el contenido que el NowPlaying da a este mismo
            // botón) para que el icono fuera idéntico en las dos puntas del morph, y eso se abandonó el
            // 20 ago: un rol trae un croma que NO tiene por qué parecerse al del fondo sobre el que
            // aquí se pinta —el mismo motivo por el que el contenedor tampoco sale de un rol— y, sobre
            // todo, deja el contraste a merced de dónde caiga el contenedor. Medido con carátula
            // monocroma: al separar el botón de la barra, el glifo se quedó quieto en su color y su
            // contraste cayó de 5.16:1 a 3.97:1. **Los fondos de las dos pantallas son distintos por
            // construcción, así que entre igualar el color y garantizar la lectura, manda la lectura.**
            //
            // Antes de aquello la semilla era `onPrimaryContainer`, el par de la BARRA, y ése es el bug
            // que abrió toda la jornada: es casi ACROMÁTICO (medido: `#FCEFDE`, croma 10) y como ya
            // cumplía el ratio, `ensureContrast` lo dejaba intacto — icono blanco en la píldora y beige
            // en el reproductor, con los dos contrastes idénticos (4.57 y 4.59).
            //
            // Pasar el botón como CONTENIDO y como CONTENEDOR no es un descuido: `ensureContrast`
            // conserva el matiz y el croma de lo primero y le busca tono contra lo segundo, que es
            // exactamente "el mismo color, apartado hasta que se lea".
            buttonContent = ensureContrast(button, button, MiniPlayerMinContrast.toFloat()),
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
    /** Ajustes → Apariencia: botón de play redondo en vez de squircle. */
    roundPlayButton: Boolean = false,
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
    // esta app la escala está AGOTADA, y desde el 20 ago 2026 más todavía. El mini flota sobre
    // bloques de lista, y el reparto de esas pantallas es una HORQUILLA que ocupa los dos lados
    // —filas y tarjetas `surface` (98 en claro), página `surfaceContainer` (94), cabecera
    // `surfaceContainerHigh` (92)—, así que lo único libre es `surfaceContainerLow` (96), metido
    // JUSTO entre el contenido y el fondo sobre los que el mini tiene que destacar, o el techo (90),
    // donde sería la superficie más oscura de la pantalla con diferencia. Cuando se probó (31 jul, con captura en device) las filas estaban en
    // `surfaceContainerHigh` y el resultado fue el mismo por el mismo motivo — el mini se perdía.
    // El reparto cambió; la conclusión no, y hoy hay MENOS sitio que entonces.
    //
    // Antes de eso hubo un `BorderStroke` de 1dp con `outlineVariant`, también descartado: un filo
    // de tono 80 cruzando por delante de filas y carátulas de colores no se lee como el borde de una
    // superficie sino como una línea ajena. Los dos intentos fallaron por lo mismo — pedirle a la
    // escala neutra una distancia que ya no tiene. `primaryContainer` no compite con nada de la
    // pantalla porque ninguna lista usa ese rol de fondo, y de paso da al reproductor identidad
    // propia, que es lo que hace cualquier mini-player conocido.
    //
    // Con el TONO enderezado al lado del tema, ver [miniPlayerContainer]: es lo que mantiene la
    // barra siendo una superficie del tema y no su negativo.
    val scheme = LocalAppColors.current
    val backgroundColor = remember(scheme.primaryContainer, scheme.surface) {
        miniPlayerContainer(scheme.primaryContainer, scheme.surface)
    }

    // Las superficies derivadas de la barra —pista del anillo, tono del botón "siguiente" y el par
    // del play— salen del contenedor desplazando el tono, ver [MiniPlayerSurfaces]. Opacas y por
    // tono, no por tintes translúcidos: así la separación no depende de qué haya debajo ni de la
    // distancia que la paleta activa deje entre dos roles.
    //
    // El contenido se MIDE contra el fondo ya enderezado en vez de tomar `onPrimaryContainer` a
    // ciegas: cuando [miniPlayerContainer] mueve el tono, el par del rol deja de valer (era el
    // contenido del OTRO lado de la escala). No-op cuando el fondo se deja tal cual, porque el par
    // de M3 ya cumple ese ratio de sobra.
    val onContainerColor = remember(scheme.onPrimaryContainer, backgroundColor) {
        ensureContrast(scheme.onPrimaryContainer, backgroundColor, MiniPlayerMinContrast.toFloat())
    }
    val surfaces = rememberMiniPlayerSurfaces(
        container = backgroundColor,
        primary = scheme.primary
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
    // token que `appContainerSurfaceExitSpec`), NO `SCREEN_TRANSFORM_MS` (500), y el motivo se midió con
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
                    // `Fit` y NO `Crop`. `Crop` toma el MAYOR de los dos ratios, y como las dos
                    // superficies comparten ancho (ratio 1) mientras el de alto es ~34, agrandaba el
                    // contenido de esta barra TREINTA Y CUATRO veces: el "14 Occasions" gigante que se
                    // veía cruzando la pantalla durante el morph. `Fit` toma el MENOR (1), así que el
                    // mini se queda a su tamaño natural y solo se funde, que es lo que se espera de un
                    // contenido que se INTERCAMBIA.
                    //
                    // **La otra punta usa `FillWidth`, y esa asimetría es la regla, no un descuido**
                    // (corregido el 17 ago 2026): lo que las dos comparten es el OBJETIVO —contenido
                    // a tamaño natural durante todo el morph, revelado por el recorte— y el
                    // `ContentScale` que lo consigue depende de si el contenido es más chico o más
                    // grande que el rect. Ver el bloque largo en `NowPlayingScreen`: con `Fit` allá,
                    // el reproductor se dibujaba al 3 % y hacía zoom desde el fondo.
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
                    roundPlayButton,
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
    roundPlayButton: Boolean,
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
    // fondo del mini desde que la barra pasó a `primaryContainer`, o sea el botón desaparecía (y
    // mover la BARRA para hacerle sitio se probó el 20 ago y se revirtió, ver [miniPlayerContainer]).
    // Tampoco sale de NINGÚN rol: la distancia entre dos roles la decide el ESTILO DE PALETA, así que
    // ninguno puede prometer ni separación ni parecido con el fondo. Probado por los dos lados y con
    // el mismo síntoma —"la única pieza sin color de toda la barra"—: con `secondary` el 31 jul
    // (`rememberRowActionColors`) y con `secondaryContainer` el 20 ago, que en tema claro dejaba el
    // botón con la mitad del croma de la píldora.
    //
    // Es **el propio contenedor de la barra**, con el tono llevado hasta el contraste que lo separa
    // (ver [MiniPlayerSurfaces]): mismo material, otra altura. Del par `secondaryContainer` solo se
    // conserva el GLIFO, que es contenido y no superficie.
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

        // Carátula + anillo de progreso ONDULADO (Expressive). Llena el Box CONTENEDOR, un poco
        // mayor que la portada (deja sitio al trazo + la amplitud de la onda en el aire que la barra
        // reserva). Es un HERMANO de la carátula y NO
        // entra en los bounds del shared element: la portada viaja sola al NowPlaying y el anillo se
        // queda, desvaneciéndose con el MiniPlayer. `progress` es una lambda que lee la posición → el
        // tick la repinta, no recompone (mismo patrón que el indicador de descarga del toolbar).
        // Mismo acento que el play (garantizado a contrastar con el fondo): así el progreso no
        // desaparece con carátula monocromática, donde `primary` crudo se fundía con el contenedor.
        val arcColor = surfaces.playContainer
        val trackColor = surfaces.progress
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(ComponentConfig.MiniPlayerRingSize)
        ) {
            // Anillo PROPIO y no `CircularWavyProgressIndicator`: el oficial no expone su fase, así
            // que ondula a la tasa de la pantalla mientras haya música y esa producción continua es
            // lo que impedía que la cola de SurfaceFlinger drenara (36 % de los frames con buffer
            // stuffing sonando, contra 10 % en pausa — medido con Perfetto el 19 ago 2026). El
            // nuestro publica el avance solo cuando la onda se movió un píxel, que es la regla que
            // ya usan la cookie del play y la onda del NowPlaying. Ver [WavyProgressRing].
            WavyProgressRing(
                progress = {
                    val total = duration.value
                    if (total <= 0L) 0f else (position.value.toFloat() / total).coerceIn(0f, 1f)
                },
                // Onda al reproducir, anillo PLANO en pausa (y el reloj parado). `showAsPlaying` y no
                // `isPlaying` a secas por lo mismo que el icono del play: en el cambio de canción con
                // streaming `isPlaying` cae un instante mientras bufferiza, y con el crudo la onda se
                // aplanaba y volvía a levantarse en ese frame — un parpadeo del anillo sin pausar.
                playing = showAsPlaying,
                color = arcColor,
                trackColor = trackColor,
                strokeWidth = ComponentConfig.MiniPlayerRingStroke,
                // Tamaño EXPLÍCITO: el Box contenedor ya lo fija, y así el anillo queda concéntrico
                // con la carátula y dentro del aire que la barra reserva.
                modifier = Modifier.size(ComponentConfig.MiniPlayerRingSize)
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
        // Botón Play/Pause — SQUIRCLE o CÍRCULO según [roundPlayButton] (Ajustes → Apariencia). Las
        // dos son formas TABULADAS del catálogo de icon buttons (`ContainerShapeSquare` /
        // `ContainerShapeRound`), así que el ajuste elige entre dos valores del spec y no entre dos
        // geometrías inventadas. Y las dos son `CornerBasedShape`, que es lo que hace que el morph
        // al pulsar lo interpole M3 solo (`IconButtonShapes.isCornerBasedShape`) — de ahí que aquí
        // no haya ni `Shape` propio ni animación a mano, al revés que con la píldora inclinada de
        // `MaterialShapes.Pill` que ocupó este sitio hasta el 24 ago 2026.
        //
        // **El squircle es el de la talla MEDIUM (`CornerLarge`, 16dp) y no el de la Small
        // (`CornerMedium`, 12dp)**, y ese valor está CERRADO en device (24 ago 2026): se probaron
        // los tres escalones del spec por arriba y por abajo y 12 se veía demasiado cuadrado
        // mientras que 20 (`LargeIncreased`) se veía demasiado redondo. NO volver a moverlo sin
        // mirarlo en device. El techo de este botón es geométrico y está a un paso: mide 48 y
        // `RoundedCornerShape` recorta cada esquina a la mitad del lado, así que **un radio de 24
        // YA ES el círculo** y las dos opciones del ajuste quedarían iguales.
        // Pedirle la forma a la talla de al lado es la MISMA desviación que este botón ya tiene en
        // su TAMAÑO —48 tampoco es talla tabulada, es su mínimo táctil—, así que sigue siendo un
        // valor del spec y no un radio a ojo.
        MiniTransportButton(
            onClick = onPlayPause,
            containerColor = playContainer,
            contentColor = playContent,
            desc = playPauseDesc,
            size = ComponentConfig.MiniPlayerPlayButtonSize,
            shape = if (roundPlayButton) IconButtonDefaults.smallRoundShape
            else IconButtonDefaults.mediumSquareShape,
            // La misma forma pulsada en los dos casos, y es la de la talla del BOTÓN (Small,
            // `CornerSmall`): sigue siendo distinta de la de reposo en los dos modos —que es la
            // condición para que el morph exista, pasar la de reposo lo anula en silencio— y con el
            // squircle a 16 el recorrido 16→8 se lee aún mejor que el 12→8 del primer intento.
            pressedShape = IconButtonDefaults.smallPressedShape
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
                        // Del tamaño del GLIFO al que sustituye, no del contenedor.
                        modifier = Modifier.size(ComponentConfig.MiniPlayerPlayIndicatorSize),
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
                            MaterialSymbol(
                                "pause",
                                color = playContent,
                                size = ComponentConfig.MiniPlayerButtonIconSize,
                                fill = true
                            )
                        else
                            MaterialSymbol(
                                "play_arrow",
                                color = playContent,
                                size = ComponentConfig.MiniPlayerButtonIconSize,
                                fill = true
                            )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.width(ComponentConfig.FloatingBarItemGap))

        // Botón Siguiente — un escalón POR DEBAJO del play (XSmall contra Small): el par se lee
        // como transporte y la TALLA dice cuál es la acción principal, no solo el color. Con los dos
        // en 48 la jerarquía la sostenía el color él solo, y son acciones de rango distinto.
        MiniTransportButton(
            onClick = onNextClick,
            containerColor = sideContainer,
            contentColor = sideContent,
            desc = nextDesc,
            size = ComponentConfig.MiniPlayerNextButtonSize,
            // Redondo: es la acción secundaria, y la forma es el segundo eje que la separa del play
            // (el primero es la talla). Su par también sale de la talla Small, que es la SUYA.
            shape = IconButtonDefaults.smallRoundShape,
            pressedShape = IconButtonDefaults.smallPressedShape
        ) {
            // Icono skip RELLENO (variante fill del símbolo, no el outline).
            MaterialSymbol(
                "skip_next",
                color = sideContent,
                size = ComponentConfig.MiniPlayerButtonIconSize,
                fill = true
            )
        }
    }
}

/**
 * Botón de transporte del MiniPlayer: `FilledIconButton` REAL de M3 Expressive, con el diámetro y la
 * forma que le pase el caller ([ComponentConfig.MiniPlayerPlayButtonSize] o
 * [ComponentConfig.MiniPlayerNextButtonSize]). El siguiente es SIEMPRE círculo y el play es
 * squircle o círculo según el ajuste de Apariencia: son las dos formas que M3 Expressive tabula
 * para un botón de icono (`ContainerShapeSquare` / `ContainerShapeRound`), así que ninguna de
 * las dos opciones inventa geometría — el squircle toma el radio de la talla Medium por el motivo
 * que explica el caller. Con el play en squircle la acción principal se distingue de
 * la secundaria por FORMA además de por talla y color; con los dos redondos esa jerarquía queda en
 * la talla y el color, que es lo que el ajuste deja elegir. Una forma geométrica estática no
 * contradice el descarte de la COOKIE (ver el KDoc de [ComponentConfig.MiniPlayerPlayButtonSize]):
 * aquélla se descartó por MOTION —una forma orgánica quieta al lado de la del NowPlaying, que gira,
 * prometía un movimiento que la píldora persistente no puede permitirse—, y ni el squircle ni el
 * círculo prometen nada. El color del contenedor puede venir animado (play/pausa con el acento
 * del álbum). Trae ripple/state-layer y semántica del componente.
 *
 * **El touch target lo repone ESTE composable, no el componente.** `FilledIconButton` entrega su
 * modifier al `Surface` sin pasar por `minimumInteractiveComponentSize` (ver `SurfaceIconButton` en
 * las fuentes de material3 1.5.0-alpha24), así que con contenedores por debajo de 48 el objetivo del
 * dedo encogería con el dibujo. `minimumInteractiveComponentSize()` va ANTES del `size`: expande el
 * área de entrada sin tocar lo que se pinta.
 *
 * **La forma PRESIONADA nunca puede ser la de reposo.** Hasta el 30 jul se pasaba
 * `pressedShape = CircleShape`, o sea la MISMA que en reposo: con las dos formas iguales no hay nada
 * que interpolar y el shape-morph de M3 Expressive quedaba anulado en silencio — el botón se
 * limitaba al ripple. Se notaba al lado del transporte del NowPlaying, cuyos botones sí se deforman
 * bajo el dedo, que es exactamente lo que reportó el usuario.
 *
 * Desde el 21 ago la pasa el CALLER en vez de heredarse del default. Hoy los dos piden
 * `smallPressedShape` (`CornerSmall`) y el default daría lo mismo
 * (`IconButtonDefaults.shapes()` sale de `SmallIconButtonTokens`), pero
 * heredarla solo funciona mientras los dos botones estén en esa talla: el spec tabula
 * `CornerMedium` para un Medium, y en la pasada en que el play midió 56 el default lo achataba como
 * si fuera de 40. Explícita, el tamaño y su forma al pulsar se mueven juntos.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MiniTransportButton(
    onClick: () -> Unit,
    containerColor: Color,
    contentColor: Color,
    desc: String,
    size: Dp,
    shape: Shape,
    pressedShape: Shape,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    FilledIconButton(
        onClick = onClick,
        shapes = IconButtonDefaults.shapes(shape = shape, pressedShape = pressedShape),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        modifier = modifier
            .minimumInteractiveComponentSize()
            .size(size)
            .semantics { contentDescription = desc }
    ) {
        content()
    }
}
