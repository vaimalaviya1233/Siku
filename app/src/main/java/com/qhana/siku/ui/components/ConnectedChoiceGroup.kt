package com.qhana.siku.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonColors
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Selección exclusiva de una opción entre varias: **connected button group**.
 *
 * Sustituye a `SegmentedButton` / `SingleChoiceSegmentedButtonRow` en toda la app. El spec de M3
 * Expressive los retiró de las recomendaciones y señala este grupo como su reemplazo — misma
 * función, diseño actualizado: el seleccionado morfea de píldora a rectángulo redondeado
 * (`checkedShape`), los extremos redondean hacia afuera y los vecinos se comprimen al presionar
 * (`animateWidth`), cosas que el segmentado no hace.
 *
 * Existe como componente y no copiado en cada pantalla porque el boilerplate del `ButtonGroup` en
 * alpha24 no es trivial —`overflowIndicator` obligatorio, `content` que es un `ButtonGroupScope` y
 * no un `@Composable`, y las shapes distintas para primero / medio / último— y estaba a punto de
 * repetirse en los cuatro sitios que usaban el segmentado.
 *
 * @param labelFor etiqueta de cada opción; se usa igual en el botón y en el menú de overflow.
 * @param iconFor símbolo opcional. Sin icono el grupo queda más compacto, que es lo que conviene
 *        cuando es un ajuste secundario dentro de una pantalla que ya tiene otro grupo.
 * @param fillWidth true reparte el ancho del padre entre las opciones **a partes IGUALES**; false
 *        las ciñe a su contenido. Es la palanca principal para que DOS grupos en la misma pantalla
 *        no se lean como el mismo control (ver el EQ: el conmutador de sección va a ancho completo
 *        y el selector de bandas, ceñido y bajo).
 *
 *        **Con etiquetas de longitud DESIGUAL hay que ponerlo en false**: el reparto igual le da a
 *        la más larga el mismo hueco que a la más corta, y la larga se recorta. Pasó con el
 *        selector de ReplayGain ("Desactivado" contra "Por pista" y "Por álbum"): en 360dp cada
 *        botón se quedaba en ~76dp de texto útil y la primera etiqueta necesitaba ~82. Con `false`
 *        cada botón mide lo suyo y, si aun así no cupieran todos, entra el `overflowIndicator` que
 *        el `ButtonGroup` ya trae — que es justo el mecanismo que el `weight` anula.
 * @param buttonHeight alto de cada botón; null = el del componente.
 * @param colors override para superficies que NO son del scheme. Lo necesita la pantalla de letras,
 *        que se pinta sobre el color del álbum y calcula el contenido por luminancia; en el resto
 *        de la app el default tonal es el correcto y no hay que tocarlo.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun <T> ConnectedChoiceGroup(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    labelFor: @Composable (T) -> String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    iconFor: ((T) -> String)? = null,
    fillWidth: Boolean = true,
    buttonHeight: Dp? = null,
    contentPadding: PaddingValues? = null,
    colors: ToggleButtonColors? = null
) {
    ButtonGroup(
        // Con `fillWidth` las opciones reparten el ancho por weight y no debería desbordar nunca;
        // ceñidas, el overflow sí puede entrar en juego con etiquetas largas o fontScale alto. En
        // cualquier caso el API lo exige.
        overflowIndicator = { menuState ->
            FilledTonalIconButton(onClick = { menuState.show() }) {
                MaterialSymbol(icon = "more_horiz", size = OverflowIconSize)
            }
        },
        modifier = if (fillWidth) modifier.fillMaxWidth() else modifier,
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)
    ) {
        options.forEachIndexed { index, option ->
            // `iconFor` se puede resolver aquí porque NO es composable; [labelFor] sí lo es y se
            // resuelve DENTRO de cada lambda. El `content` de `ButtonGroup` es un
            // `ButtonGroupScope.() -> Unit` normal —no un `@Composable`—, así que este bloque no
            // es contexto composable; `buttonGroupContent` y `menuContent` sí lo son.
            val icon = iconFor?.invoke(option)
            customItem(
                buttonGroupContent = {
                    val label = labelFor(option)
                    val source = remember { MutableInteractionSource() }
                    ToggleButton(
                        checked = selected == option,
                        onCheckedChange = { onSelect(option) },
                        enabled = enabled,
                        interactionSource = source,
                        colors = colors ?: ToggleButtonDefaults.tonalToggleButtonColors(),
                        shapes = when (index) {
                            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                            options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                        },
                        contentPadding = contentPadding ?: ToggleButtonDefaults.ContentPadding,
                        modifier = Modifier
                            .then(if (fillWidth) Modifier.weight(1f) else Modifier)
                            .then(if (buttonHeight != null) Modifier.height(buttonHeight) else Modifier)
                            .animateWidth(source)
                    ) {
                        if (icon != null) {
                            MaterialSymbol(icon = icon, size = IconSize)
                            Spacer(Modifier.width(IconGap))
                        }
                        // `Ellipsis` como RED DE SEGURIDAD, no como solución: con `maxLines = 1` a
                        // secas el texto se cortaba a hachazo —"Desactivado" salía "Desactivad"—,
                        // que se lee como una errata y no como un truncamiento. Si una etiqueta
                        // llega aquí recortada es que el grupo está mal dimensionado (ver
                        // [fillWidth]); esto solo evita que el fallo parezca otra cosa.
                        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                menuContent = { state ->
                    val label = labelFor(option)
                    // Items CLÁSICOS (sin `shapes`) a propósito: el contenedor del overflow lo pone
                    // `ButtonGroup` con un `DropdownMenu` normal, y los items que él mismo genera
                    // tampoco llevan forma.
                    DropdownMenuItem(
                        text = { Text(label) },
                        leadingIcon = icon?.let { { MaterialSymbol(icon = it) } },
                        enabled = enabled,
                        onClick = {
                            onSelect(option)
                            state.dismiss()
                        }
                    )
                }
            )
        }
    }
}

private val IconSize = 18.sp
private val OverflowIconSize = 18.sp
private val IconGap = 8.dp
