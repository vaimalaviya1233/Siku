package com.qhana.siku.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qhana.siku.ui.theme.appSpatialSpec

/**
 * Ancla tonal para un menú desplegable, en el lenguaje de M3 Expressive.
 *
 * Sustituye al patrón `ExposedDropdownMenuBox` + `TextField` de solo lectura, que es la forma
 * antigua de "campo que abre una lista": un text field comunica *escritura*, arrastra label
 * flotante e indicador inferior, y sobre una superficie de reproductor se lee como un formulario.
 * Esto es lo que el spec Expressive usa en su lugar — un contenedor tonal con forma de píldora.
 *
 * Los dos gestos Expressive que lo distinguen de un botón cualquiera:
 *  - **shape morph**: al abrirse la píldora se aprieta a una esquina grande, señalando que el
 *    control pasó a ser el contenedor de algo desplegado;
 *  - **el chevron rota** 180°, así que el estado abierto/cerrado se lee sin mirar el menú.
 * Ambos van por tokens del `MotionScheme` del tema (ver `ui/theme/Motion.kt`), no por duraciones a
 * mano — y con el spec PASADO explícitamente, que es la única forma de que eso sea cierto.
 *
 * @param value texto del estado actual: es el contenido principal, no un placeholder. NO hay
 *        parámetro de etiqueta a propósito — el rótulo encima del valor es resto del text field
 *        del que viene este control, y gasta dos líneas para un dato de una.
 * @param matchAnchorWidth el menú toma el ancho del ancla. Para el selector grande queda alineado;
 *        para uno compacto se deja `false` o el menú saldría más estrecho que sus propios items.
 * @param fillWidth el ancla ocupa todo el ancho que le den, y el chevron se va al borde derecho.
 *        En `false` el control se ciñe a su contenido y el chevron queda pegado al texto — que es
 *        lo correcto para un chip, y lo que NO se quiere en un selector ancho.
 * @param menuContent items del menú; recibe el `dismiss` para cerrarlo al elegir.
 */
@Composable
fun TonalDropdownButton(
    value: String,
    modifier: Modifier = Modifier,
    leadingIcon: String? = null,
    enabled: Boolean = true,
    matchAnchorWidth: Boolean = false,
    fillWidth: Boolean = false,
    menuContent: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var anchorWidth by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current

    // Píldora en reposo → esquina grande al desplegarse. El radio se anima en dp (no se conmuta
    // la forma) para que el cambio sea continuo y no un salto entre dos shapes.
    //
    // Los DOS specs son explícitos, y eso corrige una afirmación falsa que estaba en el kdoc de
    // arriba: `animateDpAsState`/`animateFloatAsState` SIN `animationSpec` no caen en el
    // `MotionScheme` del tema, caen en el default de compose-animation. Omitirlo no era "usar el
    // motion global", era ignorarlo.
    val cornerRadius by animateDpAsState(
        targetValue = if (expanded) ExpandedCorner else CollapsedCorner,
        animationSpec = appSpatialSpec(),
        label = "tonalDropdownCorner"
    )
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = appSpatialSpec(),
        label = "tonalDropdownChevron"
    )

    Box(modifier = modifier) {
        Surface(
            onClick = { expanded = true },
            enabled = enabled,
            shape = RoundedCornerShape(cornerRadius),
            // `surfaceContainerHigh` y no `secondaryContainer`: estos dos controles conviven con
            // el conmutador de sección y la botonera, que SÍ van tonales de acento. Con el mismo
            // container que ellos, tres cosas con jerarquías distintas competían por la atención
            // en una pantalla cuyo protagonista es el gráfico. Un escalón de superficie basta para
            // que se lean como tocables sin gritar.
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .defaultMinSize(minHeight = DefaultMinHeight)
                .onSizeChanged { anchorWidth = with(density) { it.width.toDp() } }
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                // Padding ASIMÉTRICO, como cualquier botón M3 con icono de arrastre: el lado del
                // chevron lleva menos que el del texto. Con 20dp a ambos lados el chevron quedaba
                // tan separado del borde como el texto del suyo, y en el chip —donde el ancho es
                // el del contenido— eso lo dejaba leyéndose como centrado en vez de como trailing.
                modifier = Modifier.padding(
                    start = StartPadding,
                    end = EndPadding,
                    top = VerticalPadding,
                    bottom = VerticalPadding
                )
            ) {
                if (leadingIcon != null) {
                    MaterialSymbol(icon = leadingIcon, size = IconSize)
                    Spacer(Modifier.width(IconGap))
                }
                // Con `fillWidth`, el texto se COME el espacio sobrante y empuja el chevron al
                // borde derecho; sin él no lleva weight y todo queda ceñido. Un `weight` con
                // `fill = false` daba el peor de los dos mundos: el contenedor se estiraba pero el
                // contenido se agrupaba a la izquierda, dejando el chevron flotando a media fila.
                Box(modifier = if (fillWidth) Modifier.weight(1f) else Modifier) {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                // Separación mayor que la del icono leading: es lo que despega el chevron del
                // texto y lo manda visualmente al borde en el chip, donde no hay espacio sobrante
                // que lo empuje.
                Spacer(Modifier.width(TrailingGap))
                // El chevron va dentro de una caja CUADRADA centrada, y no suelto en el Row, por
                // dos motivos que se notan los dos:
                //  - `MaterialSymbol` dibuja el glifo como TEXTO de una fuente variable, así que
                //    su caja de línea incluye el descendente y el centro de esa caja no coincide
                //    con el centro óptico del icono: alineado por el Row quedaba montado alto;
                //  - `rotate` gira sobre el centro del elemento, y con una caja más alta que el
                //    glifo ese centro tampoco es el del dibujo, de modo que al abrir el menú el
                //    chevron se desplazaba además de girar.
                val chevronBox = with(LocalDensity.current) { IconSize.toDp() }
                Box(
                    modifier = Modifier.size(chevronBox),
                    contentAlignment = Alignment.Center
                ) {
                    MaterialSymbol(
                        icon = "expand_more",
                        size = IconSize,
                        modifier = Modifier.rotate(chevronRotation)
                    )
                }
            }
        }

        // Contenedor del menú con los tokens del grupo (los mismos que ya usaba el selector con
        // menú expuesto), para que los items con forma de dentro tengan un grupo que los contenga.
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = MenuDefaults.groupStandardContainerColor,
            shape = MenuDefaults.standaloneGroupShape,
            modifier = if (matchAnchorWidth && anchorWidth > 0.dp) {
                Modifier.width(anchorWidth)
            } else {
                Modifier
            }
        ) {
            menuContent { expanded = false }
        }
    }
}

/**
 * Alto por defecto: una línea de texto con aire, holgadamente por encima del mínimo táctil.
 *
 * Es el MISMO para el selector ancho y para el chip que va a su lado — lo que los diferencia es el
 * ancho, no la altura. Con alturas distintas, dos controles de la misma familia en una misma fila
 * se leen como descuidados por mucho que estén centrados entre sí.
 */
private val DefaultMinHeight = 48.dp

private val CollapsedCorner = 28.dp
private val ExpandedCorner = 12.dp
/** Lado del texto: holgado, es donde empieza a leerse el control. */
private val StartPadding = 20.dp

/** Lado del chevron: ajustado, para que el icono quede claramente pegado al borde. */
private val EndPadding = 12.dp

private val VerticalPadding = 8.dp
private val IconSize = 18.sp

/** Aire tras el icono leading, cuando lo hay. */
private val IconGap = 8.dp

/** Aire ANTES del chevron. Mayor que [IconGap] a propósito (ver el comentario en el Row). */
private val TrailingGap = 16.dp
