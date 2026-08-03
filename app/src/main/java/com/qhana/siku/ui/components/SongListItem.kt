package com.qhana.siku.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.qhana.siku.R
import com.qhana.siku.data.model.Song
import com.qhana.siku.ui.LocalArtOriginSongId
import com.qhana.siku.ui.appSharedTransitionScope
import com.qhana.siku.ui.model.SongUiModel
import com.qhana.siku.ui.model.toUiModel
import com.qhana.siku.ui.theme.AppBoundsTransform
import com.qhana.siku.ui.theme.EXPRESSIVE_FAST_EFFECTS_MS
import com.qhana.siku.ui.theme.ExpressiveFastEffectsEasing
import androidx.compose.ui.graphics.compositeOver
import com.materialkolor.hct.Hct

// ============== FONDO Y ACCIONES DE LA FILA ==============

/**
 * Tinte del ítem en reproducción sobre el contenedor de su lista: `secondary` al 12%, el criterio
 * de resaltado de toda la app. Vive aquí porque lo necesitan DOS consumidores —quien lo pinta
 * ([SongItem]) y quien se mide contra él ([rememberRowActionColors])— y con dos copias la píldora
 * de acciones se calcularía contra un fondo que ya no es el que se dibuja.
 */
private const val ACTIVE_ROW_TINT_ALPHA = 0.12f

/**
 * Separación tonal (HCT) de la píldora de acciones respecto del fondo de SU fila. 8 = el recorrido
 * COMPLETO de la escala de contenedores de M3 en tema claro (`surface` 98 → `surfaceContainerHighest`
 * 90): la mayor distancia que el spec sigue leyendo como "otra superficie" sin llegar al salto de un
 * botón de acción. Por debajo de ~4 (dos niveles) la píldora se funde con la fila, que es justo de
 * donde venimos: `secondaryContainer` cae en la MISMA banda que `surfaceContainerHigh` (~90 vs 92).
 */
private const val ROW_ACTION_TONE_DELTA = 8.0

/** Centro de la escala de tono HCT: decide si la píldora se aleja del fondo hacia abajo o hacia arriba. */
private const val ROW_ACTION_MID_TONE = 50.0

/**
 * Contraste mínimo del glifo sobre la píldora. 4.5:1 (AA de texto, no el 3:1 de icono) porque los
 * tres puntos son trazos de ~2 dp: a efectos de legibilidad se comportan como texto pequeño.
 */
private const val ROW_ACTION_GLYPH_MIN_CONTRAST = 4.5f

/**
 * Fondo EFECTIVO de una fila de canción: el contenedor de la lista con el tinte del ítem activo ya
 * compuesto. Es el color que hay REALMENTE debajo del contenido de la fila, y por tanto contra el
 * que se miden las superficies que se apoyan encima.
 */
@Composable
fun songRowBackground(base: Color, isPlaying: Boolean): Color =
    if (isPlaying) MaterialTheme.colorScheme.secondary.copy(alpha = ACTIVE_ROW_TINT_ALPHA).compositeOver(base)
    else base

/** Par contenedor/contenido de la píldora de acciones de una fila. */
@Immutable
data class RowActionColors(val container: Color, val content: Color)

/**
 * Colores de la píldora de overflow (⋮) de una fila, DERIVADOS del fondo real de la fila en vez de
 * un rol fijo de la paleta.
 *
 * Por qué no `secondaryContainer` a secas: ese rol vive en la misma banda tonal que los contenedores
 * de superficie sobre los que se apoyan las filas, así que la píldora se ve o no según lo que la
 * paleta activa haya hecho con esa banda — y sobre el fondo TEÑIDO del ítem en reproducción
 * desaparece. Medido en device con tres estilos de paleta sobre la MISMA canción: vibrante daba una
 * píldora saturada que competía con el botón de play, monocromo un bloque plano y "fiel" una mancha
 * fundida con la fila. Los parches previos (interpolar `primary` hacia blanco o negro con constantes
 * distintas por tema) no podían arreglarlo: un lerp hacia los extremos mata el croma junto con la luz
 * —lo mismo que ya documenta `accentTone`— y sobre todo no GARANTIZA ninguna separación, que es lo
 * único que se estaba pidiendo.
 *
 * La regla: se conservan hue y croma del rol que le toca (`secondaryContainer` — sigue siendo un
 * color de la paleta, no un gris inventado) y se le fija el TONO a [ROW_ACTION_TONE_DELTA] puntos del
 * fondo, alejándose del extremo de la escala. Es como M3 construye sus propios niveles de superficie,
 * así que la separación sale idéntica en cualquier paleta y en cualquiera de los dos temas: la
 * dirección la decide el tono MEDIDO del fondo, no una rama `isSystemInDarkTheme()`.
 *
 * **Sirve para UNA capa sobre otra, no para apilar tres.** Vale para las filas —una píldora sobre un
 * fondo— pero no para el MiniPlayer, donde el botón tiene que verse a la vez sobre el contenedor y
 * sobre el relleno de progreso: al derivar dos veces seguidas con "aléjate del extremo", un fondo de
 * tono medio hace que la segunda invierta el sentido y vuelva al color del primero (probado el 31
 * jul, ver `MiniPlayerSurfaces`, que apila en una sola dirección).
 */
@Composable
fun rememberRowActionColors(rowBackground: Color): RowActionColors {
    val role = MaterialTheme.colorScheme.secondaryContainer
    val onRole = MaterialTheme.colorScheme.onSecondaryContainer
    return remember(rowBackground, role, onRole) {
        val rowTone = Hct.fromInt(rowBackground.toArgb()).tone
        val roleHct = Hct.fromInt(role.toArgb())
        val tone = (
            if (rowTone > ROW_ACTION_MID_TONE) rowTone - ROW_ACTION_TONE_DELTA
            else rowTone + ROW_ACTION_TONE_DELTA
        ).coerceIn(0.0, 100.0)
        val container = Color(Hct.from(roleHct.hue, roleHct.chroma, tone).toInt())
        RowActionColors(
            container = container,
            content = ensureContrast(onRole, container, ROW_ACTION_GLYPH_MIN_CONTRAST)
        )
    }
}

// ============== SONG STATUS ICON ==============

/**
 * Indicador de estado de descarga de una canción.
 * Con [onClick] es un icon button ESTÁNDAR M3 Expressive (sin contenedor): ripple + shape-morph
 * al presionar vía `IconButtonDefaults.shapes()`, mismo patrón que los toggles con
 * `toggleableShapes()`. Sin [onClick] se dibuja como INDICADOR puro: un botón que se ilumina y no
 * responde se lee como un control roto, y en la mayoría de las listas el estado solo informa.
 * El área que ocupa es la misma en ambos casos (mínimo táctil de M3), así que la fila no salta.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SongStatusIcon(
    isDownloaded: Boolean,
    isDownloading: Boolean,
    downloadProgress: Float?,
    modifier: Modifier = Modifier,
    size: Dp = ComponentConfig.StatusIconSize,
    onClick: (() -> Unit)? = null,
    contrast: Boolean = false,
    contrastColor: Color = MaterialTheme.colorScheme.primary
) {
    val primaryColor = if (!contrast) MaterialTheme.colorScheme.primary else contrastColor

    // Cache del tamaño en Sp para evitar recálculos
    val density = LocalDensity.current
    val sizeSp = remember(size, density) { with(density) { size.toSp() } }

    val description = when {
        isDownloaded -> stringResource(R.string.status_downloaded)
        isDownloading -> stringResource(R.string.status_downloading, ((downloadProgress ?: 0f) * 100).toInt())
        else -> stringResource(R.string.status_download_song)
    }

    val content: @Composable () -> Unit = {
        when {
            isDownloaded -> {
                MaterialSymbol("offline_pin", color = primaryColor, size = sizeSp)
            }
            isDownloading -> {
                // LoadingIndicator expressive en AMBOS estados (morfea entre MaterialShapes):
                // la variante DETERMINADA avanza el morph con el progreso. Antes el caso con
                // progreso usaba un CircularProgressIndicator clásico, así que el mismo icono
                // cambiaba de lenguaje visual a mitad de descarga.
                if (downloadProgress != null && downloadProgress > 0f && downloadProgress < 1f) {
                    LoadingIndicator(
                        progress = { downloadProgress },
                        color = primaryColor,
                        modifier = Modifier.size(size + 6.dp)
                    )
                } else {
                    LoadingIndicator(
                        color = primaryColor,
                        modifier = Modifier.size(size + 6.dp)
                    )
                }
            }
            else -> {
                MaterialSymbol("cloud_download", color = Color.Gray.copy(alpha = 0.5f), size = sizeSp)
            }
        }
    }

    val described = modifier.semantics { contentDescription = description }
    if (onClick != null) {
        IconButton(
            onClick = onClick,
            shapes = IconButtonDefaults.shapes(),
            modifier = described,
            content = { content() }
        )
    } else {
        // `minimumInteractiveComponentSize` es el MISMO mecanismo con el que IconButton reserva su
        // espacio, así que el indicador ocupa lo mismo que el botón y las filas siguen alineadas.
        Box(
            modifier = described.minimumInteractiveComponentSize(),
            contentAlignment = Alignment.Center,
            content = { content() }
        )
    }
}

// ============== CARÁTULA DE LA FILA (y su papel de origen del reproductor) ==============

/**
 * Carátula de una fila de canción. Normalmente es un `AlbumArt` y ya está; lo que la complica es
 * que además es el **origen** de la carátula del reproductor cuando éste se abre desde esta fila.
 *
 * ## Cómo una fila puede ser origen de un shared element
 *
 * El reproductor NO es una ruta del `NavHost` sino una capa hermana, así que al abrirlo la lista
 * **sigue en pantalla detrás**. Eso choca de frente con la regla de Compose de que, de las dos
 * puntas de una key, solo UNA puede ser destino: una fila que se queda visible siempre lo es, y con
 * dos destinos no hay animación ninguna (la portada aparecería quieta en su posición final, que es
 * el bug que se persiguió media tarde del 30 jul).
 *
 * La solución es la misma que usa el *container transform* de Material: **el origen se oculta
 * mientras dura el viaje**. El `AnimatedVisibility` de aquí no está por estética — al pasar a
 * `visible = false` la fila queda "saliendo", deja de ser destino y cede sus bounds como origen. Al
 * cerrar el reproductor vuelve a entrar y recibe la portada de vuelta.
 *
 * ## Por qué NO hace falta pasar ningún id
 *
 * La fila origen es SIEMPRE la de la canción activa: `MusicController.announceSelection` fija la
 * canción en el frame del tap, antes de que el reproductor se expanda. Así que cada fila decide por
 * su cuenta comparando ids contra `LocalArtOriginSongId`, sin que ningún callback tenga que cargar
 * con el id. De regalo, si el usuario cambia de canción dentro del reproductor, al cerrar la portada
 * aterriza en la fila que AHORA suena, que es lo coherente.
 *
 * ## Lo que hay que vigilar
 *
 * El `sharedElement` se declara SIEMPRE, no solo al ser origen: una punta que nace en el frame del
 * tap no tiene bounds que ofrecer y no hay match. Lo que permite tener diez filas declarando a la
 * vez sin que se peleen es que la key lleve el id (`rowArtSharedKey`); lo que hace que una sea
 * ORIGEN es ocultarse, no declararse.
 *
 * Y el scope se pide con `appSharedTransitionScope()`, que devuelve null fuera de su ventana: estas
 * mismas filas se componen dentro de diálogos (el overlay de búsqueda) y de sheets, donde declarar
 * un shared element de la ventana de abajo es un FC.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalSharedTransitionApi::class)
@Composable
private fun SongItemArt(
    song: SongUiModel,
    downloadProgress: Float?,
    isDownloading: Boolean,
    useRingProgress: Boolean
) {
    // `appSharedTransitionScope()` y no el local a pelo: dentro de un diálogo o un sheet (que son
    // otra ventana) el scope no sirve y usarlo CRASHEA — ver su kdoc.
    val sharedScope = appSharedTransitionScope()

    // SIN scope no hay nada que hacer aquí: esta fila no puede ser origen de ningún morph, así que
    // no necesita ni ocultarse ni declarar shared element. Se dibuja la carátula a pelo y se sale.
    //
    // No es una micro-optimización: es el caso de TODAS las filas de las hojas —la cola, "añadir
    // canciones"— donde el scope va anulado a propósito, y de cualquier lista fuera del
    // `SharedTransitionLayout`. Para ellas el envoltorio de abajo era un nodo de layout MÁS una
    // `Transition` por fila, que hay que componer al abrir y DESCOMPONER al cerrar. Con "añadir
    // canciones", que lista media biblioteca, eso se pagaba de golpe en el frame del cierre y se
    // veía como un tirón al salir la hoja.
    if (sharedScope == null) {
        SongArtImage(song, downloadProgress, isDownloading, useRingProgress)
        return
    }

    // `derivedStateOf` y no una lectura directa: el id del origen cambia al abrir y al cerrar el
    // reproductor, y leerlo a pelo recompondría TODAS las filas visibles en ese mismo frame — el
    // que arranca la transición y el que la termina. Así solo recompone la fila cuyo veredicto
    // cambia. Ver [LocalArtOriginSongId].
    val originIdState = LocalArtOriginSongId.current
    val isArtOrigin by remember(song.id) {
        derivedStateOf { originIdState.value == song.id }
    }

    AnimatedVisibility(
        visible = !isArtOrigin,
        // El contenido se mantiene compuesto un instante tras ocultarse (ver `exit`), y eso es
        // deliberado: es la ventana en la que sirve de origen.
        // Entrar: la portada VUELVE a la fila al cerrar el reproductor. El shared element la trae
        // hasta aquí, así que esta capa solo tiene que dejar de estorbar — sin fade propio, o se
        // vería aparecer encima de la que está aterrizando.
        enter = EnterTransition.None,
        // Salir: se disuelve rápido mientras la copia despega. Con `ExitTransition.None` la fila se
        // descompondría en el acto y el shared element se quedaría sin punta de origen.
        exit = fadeOut(tween(EXPRESSIVE_FAST_EFFECTS_MS, easing = ExpressiveFastEffectsEasing))
    ) {
        // SIEMPRE declarado, no solo cuando la fila es el origen. Es la corrección del 30 jul
        // (noche): declararlo solo al ser origen lo registraba en el MISMO frame del tap, sin
        // bounds previos que ofrecer, y sin bounds no hay match — la portada aparecía quieta en su
        // destino. Que la key lleve el id es lo que permite tener diez filas declarando a la vez
        // sin que se peleen (ver [rowArtSharedKey]).
        SongArtImage(
            song = song,
            downloadProgress = downloadProgress,
            isDownloading = isDownloading,
            useRingProgress = useRingProgress,
            modifier = with(sharedScope) {
                Modifier.sharedElement(
                    sharedContentState = rememberSharedContentState(key = rowArtSharedKey(song.id)),
                    animatedVisibilityScope = this@AnimatedVisibility,
                    boundsTransform = AppBoundsTransform
                )
            }
        )
    }
}

/**
 * La carátula de la fila, a secas. Extraída para que [SongItemArt] pueda dibujarla por el camino
 * corto (sin envoltorio) cuando no hay shared element que declarar.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SongArtImage(
    song: SongUiModel,
    downloadProgress: Float?,
    isDownloading: Boolean,
    useRingProgress: Boolean,
    modifier: Modifier = Modifier
) {
    // Cookie de 9 lados (M3 Expressive) en vez de círculo.
    // toShape() ya es @Composable y memoiza internamente (no envolver en remember).
    AlbumArt(
        albumArtUri = song.imageUrl,
        size = ComponentConfig.SongItemIconSize,
        shape = MaterialShapes.Cookie9Sided.toShape(),
        modifier = modifier,
        cacheKey = song.id,
        downloadProgress = downloadProgress,
        isDownloading = isDownloading,
        useRingProgress = useRingProgress
    )
}

// ============== SONG ITEM ==============

@Composable
fun SongItem(
    song: Song,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    isDownloading: Boolean = false,
    downloadProgress: Float? = null,
    showDuration: Boolean = false, // Por defecto NO se muestra la duración en las listas
    showStatusIcon: Boolean = true, // New parameter
    useRingProgress: Boolean = false, // aro de progreso alrededor de la carátula (cola de descargas)
    showActiveBackground: Boolean = true,
    // null = el estado solo informa (sin ripple). Solo lo pasan las pantallas que reaccionan al tap.
    onStatusClick: (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null
) {
    // Convertir a modelo UI para reutilizar la lógica de renderizado
    val uiModel = remember(song) { song.toUiModel(isActive = isPlaying) }

    SongItem(
        song = uiModel,
        isPlaying = isPlaying,
        modifier = modifier,
        isDownloading = isDownloading,
        downloadProgress = downloadProgress,
        showDuration = showDuration,
        showStatusIcon = showStatusIcon,
        useRingProgress = useRingProgress, // Pass through
        showActiveBackground = showActiveBackground,
        onStatusClick = onStatusClick,
        trailingContent = trailingContent
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SongItem(
    song: SongUiModel,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    isDownloading: Boolean = false,
    downloadProgress: Float? = null,
    showDuration: Boolean = false, // Por defecto NO se muestra la duración en las listas
    showStatusIcon: Boolean = true, // New parameter
    useRingProgress: Boolean = false, // aro de progreso alrededor de la carátula (cola de descargas)
    // El item activo dibuja su propio tinte de contenedor. Ponerlo en false cuando un CONTENEDOR
    // externo ya pinta el resaltado de TODA la fila (p. ej. la cola), para no duplicar el énfasis.
    showActiveBackground: Boolean = true,
    // null = el estado solo informa (sin ripple). Solo lo pasan las pantallas que reaccionan al tap.
    onStatusClick: (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null
) {
    // `ListItem` REAL de M3 (anatomía + tokens por spec): headline `bodyLarge`/onSurface,
    // supporting `bodyMedium`/onSurfaceVariant, leading/trailing en sus slots. El resaltado del
    // item activo va por el `containerColor` de ListItemColors (secondary 12%, mismo criterio de
    // antes). El look de LISTA AGRUPADA (esquinas + gaps + fondo tonal) lo sigue poniendo el
    // contenedor EXTERNO de cada pantalla; aquí el container es transparente salvo el activo.
    val activeContainer = MaterialTheme.colorScheme.secondary.copy(alpha = ACTIVE_ROW_TINT_ALPHA)
    val listColors = ListItemDefaults.colors(
        containerColor = if (isPlaying && showActiveBackground) activeContainer else Color.Transparent
    )

    // El indicador de estado del archivo (descargada / se transmitirá) es un concepto de NUBE:
    // una canción del propio dispositivo está siempre ahí, así que el icono no comunicaba nada y
    // su pulsación solo repetía lo obvio. En una biblioteca solo-local desaparece de toda la app.
    val showStatus = showStatusIcon && song.isCloud

    // contentDescription/stateDescription hoisteados (los lambdas de semantics {} no son @Composable).
    val playingStateDesc = if (isPlaying) stringResource(R.string.status_playing) else ""
    val durationDesc = stringResource(R.string.common_duration, song.durationText)

    // Trailing (duración + estado de descarga + menú). null si no hay nada → ListItem no reserva slot.
    val trailing: @Composable (() -> Unit)? = if (showDuration || showStatus || trailingContent != null) {
        {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showDuration) {
                    Text(
                        text = song.durationText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.semantics { contentDescription = durationDesc }
                    )
                }
                // Estado de descarga junto al menú, como icon button estándar (sin fondo).
                if (showStatus) {
                    SongStatusIcon(
                        isDownloaded = song.isDownloaded,
                        isDownloading = isDownloading,
                        downloadProgress = null, // Hide progress here (moved to AlbumArt)
                        onClick = onStatusClick
                    )
                }
                if (trailingContent != null) {
                    Spacer(modifier = Modifier.width(4.dp))
                    trailingContent()
                }
            }
        }
    } else null

    ListItem(
        headlineContent = {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isPlaying) FontWeight.SemiBold else FontWeight.Medium, // Medium por defecto
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Text(
                text = "${song.artist} • ${song.album}",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        leadingContent = {
            SongItemArt(
                song = song,
                downloadProgress = downloadProgress,
                isDownloading = isDownloading,
                useRingProgress = useRingProgress
            )
        },
        trailingContent = trailing,
        colors = listColors,
        modifier = modifier
            // Clip para que el tinte del contenedor activo respete la esquina del item (el
            // contenedor externo agrupado recorta a su vez la forma de la lista).
            .clip(RoundedCornerShape(ComponentConfig.SongItemCornerRadius))
            .semantics { stateDescription = playingStateDesc }
    )
}
