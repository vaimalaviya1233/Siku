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

// PROGRESO: el mini lo muestra como RELLENO del contenedor (ver [MiniPlayerProgressFillAlpha]).
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
 * Opacidad del relleno de progreso sobre `surfaceContainer`.
 *
 * Es BAJA a propósito y el color es `primary` (no `primaryContainer` ni `secondaryContainer`), por
 * dos restricciones que se cruzan encima de esta misma superficie:
 *  - el botón "siguiente" ES `secondaryContainer`, así que un relleno de esa familia lo borraría
 *    justo en la mitad de la canción en la que el relleno lo alcanza;
 *  - el texto va en blanco (oscuro) o casi negro (claro) según el tema, y no se re-calcula por
 *    encima del relleno: subir la opacidad mueve la luminancia del fondo bajo el título y se come
 *    el contraste de una de las dos mitades.
 *
 * Con `primary` a esta opacidad el tinte hereda el acento del álbum —así que el relleno cambia con
 * la portada, como el resto del reproductor— sin acercarse a ninguno de los dos límites. Subirlo es
 * lo primero que se nota si el efecto queda flojo; el techo lo marca el contraste del título, no el
 * gusto.
 */
private const val MiniPlayerProgressFillAlpha = 0.16f

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
 * Título y subtítulo del mini. Son los ROLES del esquema, `onSurface`/`onSurfaceVariant`, que es el
 * par de contenido del fondo que hay debajo (`surfaceContainer`).
 *
 * Sustituyen a un blanco puro y un `#1A1A1A` FIJOS con alpha 0.7/0.6, elegidos por
 * `isSystemInDarkTheme()`. Eran lo único de esta pantalla que no se teñía con la carátula: el fondo
 * del mini sí es un rol del tema, así que el texto quedaba genérico sobre una superficie de color —
 * el mismo defecto que acabábamos de corregir en los iconos, con otra ropa. Y de paso arregla dos
 * cosas más: la jerarquía título/subtítulo la hace el ROL y no un alpha inventado (M3 garantiza el
 * contraste de los dos, que un 0.6 de alpha sobre un fondo tonal no garantiza), y desaparece la
 * consulta al tema del SISTEMA, que no tiene por qué coincidir con el tema de la app (ver
 * `AmbientPlayerActivity`, que lo fuerza oscuro).
 */
@Immutable
private data class MiniPlayerColors(
    val titleColor: Color,
    val contentColor: Color
)

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

    // Colores del texto (título/artista): roles del esquema, ver [MiniPlayerColors].
    val textColors = MiniPlayerColors(
        titleColor = MaterialTheme.colorScheme.onSurface,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    )

    // Background SÓLIDO surfaceContainer (el mismo del ajuste "fondo sólido" del NowPlaying):
    // superficie plana estable, sin tinte de acento. Es un rol del scheme sembrado con el álbum,
    // así que el tema lo tiñe al cambiar de canción sin animarlo a mano.
    val backgroundColor = MaterialTheme.colorScheme.surfaceContainer

    // Píldora completa: el MiniPlayer FLOTA sobre la navbar del home (ya no va edge-to-edge
    // con fondo plano hasta abajo).
    val shape = RoundedCornerShape(50)

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
        // Elevación por la API del Card. 6dp = nivel 3 del spec (el mismo del FAB): un bar flotante
        // debe LEERSE flotante; el default de Card (1dp) quedaría casi plano y desharía la sombra.
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        val position = currentPositionFlow.collectAsStateWithLifecycle()
        val duration = durationFlow.collectAsStateWithLifecycle()
        val fillColor = MaterialTheme.colorScheme.primary.copy(alpha = MiniPlayerProgressFillAlpha)

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
                    // de la píldora las recorta el Card, que ya clipea a su shape.
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

    // Transporte alineado con el NowPlaying: laterales tonales (secondaryContainer) y play
    // resaltado (primary). Son roles del scheme sembrado con el álbum, así que ya vienen
    // teñidos por la canción sin animar colores a mano.
    //
    // Los pares `on*` van CRUDOS, igual que en el NowPlaying: el mini y el reproductor comparten el
    // shared element de la carátula, así que se ven uno al lado del otro durante la transición y
    // cualquier diferencia de tratamiento se leería como un salto de color. Ver el porqué de no
    // reencuadrarlos junto a `ensureContrast` en PlayerWidgets.kt.
    val sideContainer = MaterialTheme.colorScheme.secondaryContainer
    val sideContent = MaterialTheme.colorScheme.onSecondaryContainer
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
        // (primary vs secondaryContainer) es el que marca cuál es la acción principal.
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
 * `FilledIconButton` REAL de M3 Expressive con shape circular FIJA (sin morph). El color del
 * contenedor puede venir animado (play/pause con el acento del álbum). Trae ripple/state-layer,
 * touch target y semántica del componente.
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
        shapes = IconButtonDefaults.shapes(shape = CircleShape, pressedShape = CircleShape),
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
