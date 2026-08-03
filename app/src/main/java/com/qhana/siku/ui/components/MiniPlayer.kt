package com.qhana.siku.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.materialkolor.contrast.Contrast
import com.materialkolor.hct.Hct
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qhana.siku.data.model.Song

import com.qhana.siku.ui.theme.AppBoundsTransform
import com.qhana.siku.ui.theme.appSpatialSpec
import com.qhana.siku.ui.theme.appFastSpatialSpec
import com.qhana.siku.ui.theme.appEffectsSpec
import com.qhana.siku.ui.theme.appFastEffectsSpec

// ============== MINI PLAYER ==============

// PROGRESO: el mini lo muestra como RELLENO del contenedor (ver [MiniPlayerProgressToneDelta]).
//
// Historia, porque es el tercer intento y los dos anteriores se descartaron: hubo una barra de 3dp
// en el borde inferior que la forma de píldora obligaba a recortar 34dp por lado (a esa altura el
// contorno ya se ha metido hacia adentro), y un tinte del contenedor entero. La conclusión que
// quedó escrita fue que, antes que el estilo del indicador, había que resolver la GEOMETRÍA de la
// píldora.
//
// Esto es lo que la resuelve: un relleno de altura COMPLETA no tiene borde propio que recortar —
// las esquinas de la píldora lo recortan solas, sin cálculos—, y a diferencia del tinte uniforme
// tiene un borde vertical nítido que se puede leer como posición. No cuesta ni un píxel de alto,
// que era la otra objeción de fondo: el mini mide 72dp y no había dónde meter un indicador.

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
 * Las tres superficies apiladas de la barra, derivadas del contenedor con UNA sola dirección.
 *
 * Que la dirección se decida una vez es la parte load-bearing. Cada capa se derivaba antes por su
 * cuenta con la regla "aléjate del extremo de la escala", y con un contenedor de tono MEDIO —que es
 * justo lo que da "fiel a la carátula"— la segunda derivación invertía el sentido y aterrizaba en el
 * tono del contenedor: fondo 46 → relleno 54 → botón 46, o sea un botón "siguiente" invisible sobre
 * la mitad no reproducida (visto en device, 31 jul). Apilando en el mismo sentido, el botón queda a
 * un paso del relleno y a dos del fondo, y como el sentido siempre apunta al CENTRO de la escala, dos
 * pasos nunca se salen de rango.
 */
@Immutable
private data class MiniPlayerSurfaces(
    /** Relleno de progreso, y fondo efectivo de la mitad ya reproducida. */
    val progress: Color,
    /** Contenedor del botón "siguiente": tiene que verse sobre el fondo Y sobre el relleno. */
    val buttonContainer: Color,
    /** Glifo del botón, con el contraste garantizado contra [buttonContainer]. */
    val buttonContent: Color
)

@Composable
private fun rememberMiniPlayerSurfaces(container: Color, onContainer: Color): MiniPlayerSurfaces =
    remember(container, onContainer) {
        val base = Hct.fromInt(container.toArgb())
        // Hacia el centro de la escala: es el único sentido con sitio para DOS pasos.
        val direction = if (base.tone > MINI_MID_TONE) -1.0 else 1.0
        fun step(times: Double) = Color(
            Hct.from(
                base.hue,
                base.chroma,
                (base.tone + direction * MiniPlayerToneStep * times).coerceIn(0.0, 100.0)
            ).toInt()
        )
        val button = step(2.0)
        MiniPlayerSurfaces(
            progress = step(1.0),
            buttonContainer = button,
            // `ensureContrast` mide en Float; el umbral es el mismo AA que usa el subtítulo.
            buttonContent = ensureContrast(onContainer, button, MiniPlayerMinContrast.toFloat())
        )
    }

/**
 * Elevación del MiniPlayer: **nivel 4** del spec de M3.
 *
 * Sube del nivel 3 (6dp, el del FAB) porque la barra es la superficie MÁS ALTA de la pantalla —
 * flota por encima de la lista y de la navbar— y en tema claro la sombra es la mitad del trabajo de
 * despegarla. En oscuro no aporta casi nada (M3 separa por tono, no por sombra): ahí trabaja el
 * contenedor, ver el porqué de `surfaceContainerHighest` en el cuerpo del componente.
 */
private val MiniPlayerElevation = 8.dp

/**
 * Default de los flows de progreso: sin posición ni duración el relleno no se dibuja.
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
        ).coerceIn(0.0, 100.0)
        MiniPlayerColors(
            titleColor = onContainer,
            contentColor = Color(Hct.from(onHct.hue, onHct.chroma, subtitleTone).toInt())
        )
    }

/**
 * Centro de la escala de tono HCT: decide hacia qué lado se alejan del fondo las cosas que se
 * derivan de él (el relleno de progreso, el subtítulo). Es lo que hace que la barra funcione igual
 * en tema claro y oscuro sin una sola rama por tema.
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
     * relleno y nada más.
     */
    currentPositionFlow: StateFlow<Long> = ZeroProgressFlow,
    durationFlow: StateFlow<Long> = ZeroProgressFlow,
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

    // Las otras dos superficies de la barra —relleno de progreso y botón "siguiente"— salen del
    // contenedor apilando pasos de tono en UNA sola dirección, ver [MiniPlayerSurfaces]. Opacas y
    // por tono, no por tintes translúcidos: así la separación no depende de qué haya debajo ni de la
    // distancia que la paleta activa deje entre dos roles.
    val onContainerColor = MaterialTheme.colorScheme.onPrimaryContainer
    val surfaces = rememberMiniPlayerSurfaces(backgroundColor, onContainerColor)

    // El texto se mide contra el RELLENO y no contra el contenedor limpio: el relleno acaba pasando
    // por debajo del título, y como siempre va hacia el centro de la escala es el peor caso para
    // cualquier contenido. Mismo criterio que `songRowBackground` con el tinte del ítem activo.
    val textColors = rememberMiniPlayerColors(
        container = surfaces.progress,
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

    // Card: contenedor OFICIAL de M3 para "contenido de un solo tema que se toca para abrir".
    // M3 no tiene componente de mini-player; un toolbar no aplica (no es una fila de acciones y
    // necesita tap en toda la superficie). Card da ese onClick nativo (expandir al NowPlaying) y
    // la elevación por su propia API, sin fingir que es un toolbar. Es `Surface` + defaults de card.
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            // Alto fijo al spec del floating toolbar M3 Expressive (64.dp).
            .height(ComponentConfig.MiniPlayerHeight)
            .semantics { contentDescription = miniPlayerDesc },
        shape = shape,
        // Contenedor TRANSPARENTE: el tinte animado del álbum se dibuja en el Box interno para que
        // NO reciba el overlay tonal del Card (que teñiría el color según la elevación).
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        // Elevación por la API del Card (ver [MiniPlayerElevation]): un bar flotante debe LEERSE
        // flotante, y el default de Card (1dp) quedaría casi plano.
        elevation = CardDefaults.cardElevation(defaultElevation = MiniPlayerElevation)
    ) {
        val position = currentPositionFlow.collectAsStateWithLifecycle()
        val duration = durationFlow.collectAsStateWithLifecycle()
        val fillColor = surfaces.progress

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundColor)
                // `drawBehind` y no un Box con `fillMaxWidth(fraction)`: la fracción se lee dentro
                // de la lambda de dibujo, así que el tick de posición NO recompone nada, solo
                // repinta. (Además `fillMaxWidth(0f)` es ilegal, y al empezar una canción la
                // fracción es exactamente 0.)
                .drawBehind {
                    val total = duration.value
                    if (total <= 0L) return@drawBehind
                    val fraction = (position.value.toFloat() / total).coerceIn(0f, 1f)
                    if (fraction <= 0f) return@drawBehind
                    // Borde derecho RECTO: es lo que se lee como "hasta aquí vamos". Las esquinas
                    // las recorta el Card, que ya clipea a su shape — sea píldora o rectángulo.
                    drawRect(color = fillColor, size = Size(size.width * fraction, size.height))
                }
        ) {
            // Contenido
            MiniPlayerContent(
                song,
                isPlaying,
                onPlayPause,
                onNextClick,
                textColors,
                surfaces,
                isBuffering,
                sharedTransitionScope,
                animatedVisibilityScope
            )
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
    /** Superficies derivadas de la barra: de aquí salen los colores del botón "siguiente". */
    surfaces: MiniPlayerSurfaces,
    isBuffering: Boolean,
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

    // Transporte: play resaltado (`primary`, el mismo rol que en el NowPlaying — sobre un fondo
    // `primaryContainer` es su propio matiz varios tonos más oscuro, así que sigue siendo el control
    // que salta a la vista) y "siguiente" tonal.
    //
    // El siguiente ya NO es `secondaryContainer` crudo: ese rol cae en la MISMA banda tonal que el
    // fondo del mini desde que la barra pasó a `primaryContainer`, o sea el botón desaparecía. Y
    // tampoco es un derivado con hue/croma de OTRO rol — probado el 31 jul con
    // `rememberRowActionColors`: el croma lo pone el rol, y con el de `secondary` salía un botón gris
    // sobre una barra teñida, la única pieza sin color de toda la barra.
    //
    // Es el propio contenedor a DOS pasos de tono (ver [MiniPlayerSurfaces]): mismo matiz, mismo
    // croma, y separado tanto del fondo como del relleno de progreso que le pasa por debajo.
    val sideContainer = surfaces.buttonContainer
    val sideContent = surfaces.buttonContent
    val playContainer = MaterialTheme.colorScheme.primary
    val playContent = MaterialTheme.colorScheme.onPrimary

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
        val sharedElementModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null) {
            with(sharedTransitionScope) {
                Modifier.sharedElement(
                    // Key CONSTANTE, no `..._${song.id}`: ver [ALBUM_ART_SHARED_KEY]. Con el id
                    // dentro, abrir una canción distinta desde una lista pedía keys diferentes en
                    // cada punta y el morph no ocurría.
                    sharedContentState = rememberSharedContentState(key = ALBUM_ART_SHARED_KEY),
                    animatedVisibilityScope = animatedVisibilityScope,
                    // MISMA curva y duración que el slide del player (ver AppBoundsTransform y
                    // PLAYER_SLIDE_MS). Es obligatorio: el slide desplaza el reproductor con un
                    // graphicsLayer, que NO mueve el layout, así que este shared element viaja
                    // hacia la posición FINAL de la carátula. Si asienta antes que el slide
                    // —como hacía con el spring default de la API, ~300 ms contra 500— se queda
                    // quieta arriba mientras el resto del player todavía sube por debajo: el
                    // "flash" reportado el 30 jul.
                    boundsTransform = AppBoundsTransform
                )
            }
        } else Modifier

        // En el mini la carátula es SIEMPRE un círculo, reproduzca o no (decisión de diseño:
        // a este tamaño el morph a squircle no se leía y solo agregaba ruido). Se usa
        // AlbumArtMorphShape(1f) —el mismo path de círculo que el NowPlaying en pausa— en vez de
        // CircleShape para que ese extremo del shared element coincida exacto al pausar.
        // El tamaño sale del alto del container (deja 8dp de aire arriba/abajo), no de una
        // constante suelta: si la barra cambia de alto, la carátula lo sigue.
        AlbumArt(
            albumArtUri = song.albumArtUriString,
            size = ComponentConfig.MiniPlayerArtSize,
            shape = AlbumArtMorphShape(progress = 1f),
            modifier = sharedElementModifier,
            cacheKey = song.id
        )

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
