package com.qhana.siku.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qhana.siku.R

/**
 * Alto de TODOS los botones de [DetailPlayButtons], y lado de los que son cuadrados (círculo y
 * squircle); solo la píldora vertical estrecha su ancho, hasta el mínimo táctil. Que salga de un
 * único sitio no es cosmética: la fila los alinea por su borde, así que si uno midiera distinto la
 * botonera quedaría descuadrada.
 *
 * **Es la escala MEDIUM del spec, no un número propio.** Estuvo en 64 dp —un valor entre dos
 * tamaños de M3, que no es ninguno— hasta que con las cuatro acciones la fila dejó de caber a lo
 * ancho de un teléfono en vertical. `ButtonMediumTokens.ContainerHeight` y
 * `MediumIconButtonTokens.ContainerHeight` valen los dos 56 dp, así que con este valor el `Button`
 * y los `FilledTonalIconButton` caen en la MISMA escala del spec — la misma de la que ya salían las
 * formas (`mediumSquareShape`) y los tamaños de glifo. De ahí que padding, icono y shape se puedan
 * derivar de aquí con las funciones `…For(buttonHeight)` en vez de escribirse a ojo.
 */
private val DetailActionSize = ButtonDefaults.MediumContainerHeight

/** Aire entre los botones de la botonera. */
private val DetailActionGap = 12.dp

/**
 * Botonera de las pantallas de detalle (artista/álbum/género/playlist), estilo M3 Expressive:
 * **una forma por acción**, que es lo que las distingue sin ponerles etiqueta:
 *
 * | Acción | Forma |
 * |---|---|
 * | Reproducir | píldora HORIZONTAL de acento (icono + etiqueta, ancho de contenido) |
 * | Aleatorio | círculo tonal |
 * | Encolar todo | squircle tonal |
 * | Añadir canciones (solo listas) | píldora VERTICAL tonal |
 *
 * El orden va de más a menos usado, y la última es además la única de EDICIÓN: las tres primeras
 * arrancan o extienden la reproducción, la cuarta cambia el contenido de la lista. Ninguna se
 * estira al ancho del padre. Componentes REALES de material3 (Button / FilledTonalIconButton) con
 * sus `shapes()` Expressive: shape-morph al presionar, en vez de Surface+Box artesanal.
 *
 * **La fila se DESPLAZA en horizontal** como red, no como diseño: con [DetailActionSize] en la
 * escala medium las cuatro acciones caben en un teléfono normal en vertical, pero el ancho del play
 * depende de la longitud de su etiqueta —que cambia con el idioma— y de `fontScale`, que el usuario
 * puede subir por accesibilidad. Recortar contra el borde escondería una acción sin que nada lo
 * insinúe; con el scroll, cuando cabe no pasa nada y cuando no, se alcanza arrastrando. La
 * alternativa —estirar el play a todo el ancho— ya se probó y el usuario la descartó.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DetailPlayButtons(
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    // Encolar TODO el detalle al final de la cola actual. Squircle tonal (ver abajo el porqué).
    onAddToQueue: (() -> Unit)? = null,
    // Solo en listas editables (playlist): "añadir canciones", como píldora vertical (ver abajo).
    onAddSongs: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val playAllDesc = stringResource(R.string.detail_play_all)
    val shuffleDesc = stringResource(R.string.detail_shuffle)
    val addToQueueDesc = stringResource(R.string.detail_add_all_to_queue)
    val addSongsDesc = stringResource(R.string.playlist_add_songs)

    // Glifo de los CUATRO botones, derivado del alto de la fila con la función del spec en vez de
    // escrito a ojo. El play llevaba 28 sp y los demás 24 sin que nada lo justificara; el token de
    // este tamaño son 24 para el `Button` y para el `IconButton`, así que ahora es el mismo valor y
    // sale de un sitio. Va en sp porque [MaterialSymbol] es una fuente variable, no un vectorial:
    // la conversión se hace con la densidad y no escribiendo el número en sp, o con `fontScale`
    // distinto de 1 las dos cosas dejarían de medir lo mismo (ver [MenuItemIcon]).
    val actionIconSize = with(LocalDensity.current) {
        ButtonDefaults.iconSizeFor(DetailActionSize).toSp()
    }

    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(DetailActionGap)
    ) {
        Button(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onPlayAll()
            },
            shapes = ButtonDefaults.shapesFor(DetailActionSize),
            // Padding ASIMÉTRICO (16 delante / 24 detrás), que es el que M3 define para un `Button`
            // CON icono. Un glifo no llena su caja por el lado de fuera como sí lo hace una letra,
            // así que con 24 a los dos lados el conjunto icono+etiqueta se ve corrido hacia la
            // derecha dentro de la píldora — el desalineado que se veía.
            //
            // Y NO vale `contentPaddingFor(…, hasStartIcon = true)`, que es lo que parecería: en la
            // escala medium ese flag no cambia nada, porque `IconMediumLeadingPadding` está
            // definido como el mismo valor que `MediumLeadingPadding` (24 dp los dos, leído de los
            // tokens). La compensación solo existe en `ButtonWithIconContentPadding`. Su padding
            // VERTICAL es de otra escala y aquí da igual: el alto lo fija `.height` de abajo.
            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            modifier = Modifier
                .height(DetailActionSize)
                .semantics { contentDescription = playAllDesc }
        ) {
            // Sin alineado especial: el `Row` del botón centra a sus hijos y con eso basta, porque
            // desde el `Trim.Both` de [MaterialSymbol] la caja del glifo mide su tamaño nominal y
            // lo tiene centrado dentro. Aquí se probó `alignByBaseline` en los dos —siendo ambos
            // texto, parecía lo natural— y deja el símbolo ALTO: el glifo se asienta en la línea
            // base ocupándola entera hacia arriba, mientras que la etiqueta reparte su altura entre
            // mayúsculas y descendentes. El arreglo iba en la caja del icono, no en la referencia.
            MaterialSymbol(
                "play_arrow",
                size = actionIconSize,
                color = colorScheme.onPrimary,
                fill = true
            )
            Spacer(modifier = Modifier.width(ButtonDefaults.iconSpacingFor(DetailActionSize)))
            Text(
                text = stringResource(R.string.common_play),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = colorScheme.onPrimary,
                maxLines = 1
            )
        }
        FilledTonalIconButton(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onShuffle()
            },
            shapes = IconButtonDefaults.shapes(),
            modifier = Modifier
                .size(DetailActionSize)
                .semantics { contentDescription = shuffleDesc }
        ) {
            MaterialSymbol("shuffle", size = actionIconSize, color = colorScheme.onSecondaryContainer)
        }
        // Encolar todo el detalle. Va JUNTO al aleatorio y no en un overflow porque es del mismo
        // rango que los otros dos: las tres son "qué hago con estas canciones", y las tres actúan
        // sobre el conjunto entero. El feedback lo da el snackbar — sin él la acción es invisible,
        // porque la cola no está en pantalla al pulsar.
        //
        // SQUIRCLE en vez de círculo, con las formas SQUARE del spec de icon button y no una
        // inventada. Se toma la escala **medium** (`CornerLarge` → `CornerMedium` al presionar) y
        // no la large: el radio de la large es `CornerExtraLarge`, que a [DetailActionSize] queda a
        // un paso de ser un círculo y no se leería como otra forma. El contraste de formas es como
        // esta fila distingue sus acciones, y aquí separa "encolar" de los dos verbos que arrancan
        // reproducción.
        if (onAddToQueue != null) {
            FilledTonalIconButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onAddToQueue()
                },
                shapes = IconButtonDefaults.shapes(
                    shape = IconButtonDefaults.mediumSquareShape,
                    pressedShape = IconButtonDefaults.mediumPressedShape
                ),
                modifier = Modifier
                    .size(DetailActionSize)
                    .semantics { contentDescription = addToQueueDesc }
            ) {
                MaterialSymbol("low_priority", size = actionIconSize, color = colorScheme.onSecondaryContainer)
            }
        }
        // Añadir canciones (solo playlists editables). PÍLDORA VERTICAL, la cuarta forma de la
        // fila: es la única acción de EDICIÓN entre tres verbos de reproducción, y darle su propia
        // silueta lo dice sin una etiqueta. Mismo idiom que el "guardar la cola como lista" de la
        // hoja de cola y que el overflow ⋮ de las filas, por el mismo motivo que allí: comparte la
        // familia tonal con el resto sin ser otro círculo más.
        //
        // La forma sale sola del default `shapes()` (`CornerFull`) al ser el contenedor más ALTO
        // que ancho — no hay que dibujar ninguna píldora a mano. Y el ancho es el mínimo táctil del
        // sistema, que es lo más estrecho que puede ser sin dejar de ser tocable: exactamente lo
        // que se le pide a una forma que debe leerse vertical, y de paso sigue la configuración de
        // accesibilidad en vez de un valor clavado.
        if (onAddSongs != null) {
            FilledTonalIconButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onAddSongs()
                },
                shapes = IconButtonDefaults.shapes(),
                modifier = Modifier
                    .width(LocalMinimumInteractiveComponentSize.current)
                    .height(DetailActionSize)
                    .semantics { contentDescription = addSongsDesc }
            ) {
                MaterialSymbol("playlist_add", size = actionIconSize, color = colorScheme.onSecondaryContainer)
            }
        }
    }
}

/**
 * Botón de overflow por CANCIÓN (píldora vertical tonal con ⋮) con menú: favoritos y
 * añadir a lista de reproducción.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SongOverflowButton(
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onAddToQueue: () -> Unit,
    // Fondo REAL de la fila (tinte del ítem activo ya compuesto, vía `songRowBackground`): de él se
    // derivan los colores de la píldora, ver `rememberRowActionColors`.
    rowBackground: Color,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }
    val optionsDesc = stringResource(R.string.common_song_options)
    val colors = rememberRowActionColors(rowBackground)
    Box(modifier = modifier) {
        // Píldora VERTICAL (M3 Expressive) como FilledIconButton real: shape-morph al presionar.
        FilledIconButton(
            onClick = { showMenu = true },
            shapes = IconButtonDefaults.shapes(),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = colors.container,
                contentColor = colors.content
            ),
            modifier = Modifier
                .width(28.dp)
                .height(44.dp)
                .semantics { contentDescription = optionsDesc }
        ) {
            MaterialSymbol("more_vert", size = 18.sp, color = colors.content)
        }
        // Menú SEGMENTADO (popup + grupo), no el `DropdownMenu` clásico: ver la nota en SortChip.
        DropdownMenuPopup(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            DropdownMenuGroup(shapes = MenuDefaults.groupShapes()) {
                // Favorito es un TOGGLE, no una acción: va con la sobrecarga `checked`
                // (contenedor marcado, morph de forma y `Role.Checkbox`). El texto sigue diciendo
                // qué pasa al tocarlo — el estado lo cuenta el item, no solo el relleno del
                // corazón, que un lector de pantalla no anuncia.
                DropdownMenuItem(
                    checked = isFavorite,
                    onCheckedChange = {
                        showMenu = false
                        onToggleFavorite()
                    },
                    text = { Text(if (isFavorite) stringResource(R.string.common_remove_from_favorites) else stringResource(R.string.common_add_to_favorites)) },
                    // Menú de items fijos: la posición se dice con `leading`/`trailing` en vez de
                    // `itemShape(index, count)`, que obligaría a mantener a mano un total.
                    shapes = MenuDefaults.itemShapes(shape = MenuDefaults.leadingItemShape),
                    leadingIcon = { MenuItemIcon("favorite", fill = isFavorite) }
                )
                DropdownMenuItem(
                    onClick = {
                        showMenu = false
                        onAddToPlaylist()
                    },
                    text = { Text(stringResource(R.string.common_add_to_playlist)) },
                    shape = MenuDefaults.middleItemShape,
                    leadingIcon = { MenuItemIcon("playlist_add") }
                )
                DropdownMenuItem(
                    onClick = {
                        showMenu = false
                        onAddToQueue()
                    },
                    text = { Text(stringResource(R.string.common_add_to_queue)) },
                    shape = MenuDefaults.trailingItemShape,
                    leadingIcon = { MenuItemIcon("low_priority") }
                )
            }
        }
    }
}

/**
 * Overflow (⋮) con una ÚNICA acción: "añadir a la cola". Misma píldora vertical que
 * [SongOverflowButton], para que las dos se lean como el mismo control.
 *
 * Los colores llegan HECHOS en vez de derivarse aquí de un fondo, porque sus consumidores no
 * comparten el mismo tipo de superficie: una fila de lista tiene un color plano detrás
 * ([rememberRowActionColors] sobre el contenedor de la fila), pero en la tarjeta de álbum la píldora
 * se apoya sobre la CARÁTULA, donde no hay fondo del que derivar nada — ahí se pinta un contenedor
 * tonal sólido, igual que ya hace el badge de conteo que vive en esa misma imagen.
 *
 * [menuLabel] existe porque la acción no dice lo mismo según sobre qué se aplique: en una canción es
 * "añadir a la cola" y en un artista/álbum/género son N canciones ("añadir todo a la cola").
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun QueueOverflowButton(
    onAddToQueue: () -> Unit,
    colors: RowActionColors,
    contentDescription: String,
    modifier: Modifier = Modifier,
    menuLabel: String = stringResource(R.string.common_add_to_queue)
) {
    var showMenu by remember { mutableStateOf(false) }
    // Copia local: dentro de `semantics {}` el nombre `contentDescription` es la propiedad del
    // receiver, no el parámetro.
    val optionsDesc = contentDescription
    Box(modifier = modifier) {
        FilledIconButton(
            onClick = { showMenu = true },
            shapes = IconButtonDefaults.shapes(),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = colors.container,
                contentColor = colors.content
            ),
            modifier = Modifier
                .width(28.dp)
                .height(44.dp)
                .semantics { this.contentDescription = optionsDesc }
        ) {
            MaterialSymbol("more_vert", size = 18.sp, color = colors.content)
        }
        DropdownMenuPopup(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            // Un solo item: el clip redondeado del grupo ya le da forma de píldora, así que no
            // hace falta repartir leading/middle/trailing como en los menús de varias entradas.
            DropdownMenuGroup(shapes = MenuDefaults.groupShapes()) {
                DropdownMenuItem(
                    onClick = {
                        showMenu = false
                        onAddToQueue()
                    },
                    text = { Text(menuLabel) },
                    shape = MenuDefaults.standaloneItemShape,
                    leadingIcon = { MenuItemIcon("low_priority") }
                )
            }
        }
    }
}

/**
 * [QueueOverflowButton] por CANCIÓN: para las listas cuyas filas ya tienen su propia acción rápida
 * (quitar de la lista / favorito) y solo necesitan sumar el encolado, sin las demás entradas de
 * [SongOverflowButton]. Deriva los colores del fondo REAL de la fila igual que el overflow completo.
 */
@Composable
fun SongQueueOverflowButton(
    onAddToQueue: () -> Unit,
    rowBackground: Color,
    modifier: Modifier = Modifier
) {
    QueueOverflowButton(
        onAddToQueue = onAddToQueue,
        colors = rememberRowActionColors(rowBackground),
        contentDescription = stringResource(R.string.common_song_options),
        modifier = modifier
    )
}
