package com.qhana.siku.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.MenuGroupShapes
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * El color de contenido que le toca a [color], resuelto contra [AppColors].
 *
 * ## Por qué existe este archivo entero
 *
 * Un componente de M3 al que no se le pasan colores **no los recibe: los va a buscar él mismo**.
 *
 * ```kotlin
 * // material3 1.5.0-alpha24, Button.kt:1376 y Switch.kt:341
 * @Composable fun textButtonColors() = MaterialTheme.colorScheme.defaultTextButtonColors
 * @Composable fun colors() = MaterialTheme.colorScheme.defaultSwitchColors
 * ```
 *
 * Mientras el tema se regeneró desde la carátula eso funcionaba solo: `primary` ya venía teñido y
 * la etiqueta salía con el color del álbum sin que nadie se lo pidiera. Desde que el tema dejó de
 * moverse —ver el KDoc de [AppColorScheme] para el porqué— esos componentes se quedarían en un
 * color fijo mientras las superficies propias siguen a la carátula. **El problema no sería un botón
 * apagado: sería la MEZCLA**, media app viva y media no, que es peor que cualquiera de las dos.
 *
 * Cada helper de aquí toca **solo los roles que ese componente usa de verdad**, sacados de los
 * tokens de M3 y no a ojo (`FilledButtonTokens`, `SwitchTokens`, `SliderTokens`, `ListTokens`…), y
 * hereda del propio M3 todo lo demás — en particular los estados DESHABILITADOS, que son
 * `onSurface` con alfas tabuladas: replicarlos aquí sería meter media docena de números mágicos
 * para pintar de gris algo que ya está gris.
 *
 * **La regla: un componente de M3 que deba llevar el color del álbum se escribe con su helper de
 * aquí.** Si falta uno, se añade con el mapeo leído de los tokens, nunca inventado.
 *
 * ## Y en particular esta función
 *
 * Es el equivalente de `contentColorFor` de M3 (`ColorScheme.kt:1089`), con el mismo mapeo rol por
 * rol. Existe porque el de M3 busca el color **por VALOR dentro de `MaterialTheme.colorScheme`**
 * (`ColorScheme.kt:1137`) y cae a `LocalContentColor` si no lo encuentra. Con el tema quieto y las
 * superficies viniendo de [AppColors] esa búsqueda no acierta nunca: el texto de encima heredaría
 * el color de quien esté más arriba, **sin fallar en compilación y sin que se note hasta verlo en
 * pantalla**. Por eso [AppSurface] lo aplica por defecto.
 *
 * Devuelve `Color.Unspecified` si el color no es ninguno de los roles, igual que M3: eso significa
 * "no sé, heredá", que es la respuesta correcta para un color arbitrario.
 */
@Composable
@ReadOnlyComposable
fun appContentColorFor(color: Color): Color {
    val c = LocalAppColors.current
    return when (color) {
        c.primary -> c.onPrimary
        c.secondary -> c.onSecondary
        c.tertiary -> c.onTertiary
        c.background -> c.onBackground
        c.error -> c.onError
        c.primaryContainer -> c.onPrimaryContainer
        c.secondaryContainer -> c.onSecondaryContainer
        c.tertiaryContainer -> c.onTertiaryContainer
        c.errorContainer -> c.onErrorContainer
        c.inverseSurface -> c.inverseOnSurface
        c.surface -> c.onSurface
        c.surfaceVariant -> c.onSurfaceVariant
        c.surfaceBright -> c.onSurface
        c.surfaceContainer -> c.onSurface
        c.surfaceContainerHigh -> c.onSurface
        c.surfaceContainerHighest -> c.onSurface
        c.surfaceContainerLow -> c.onSurface
        c.surfaceContainerLowest -> c.onSurface
        c.surfaceDim -> c.onSurface
        else -> Color.Unspecified
    }
}

/**
 * `Surface` con el color de contenido derivado de [AppColors] en vez de del tema.
 *
 * Es la única diferencia con el de M3, y es la que evita el fallo silencioso que describe
 * [appContentColorFor]. Lo usan los sitios que pasan `color` sin `contentColor`.
 */
@Composable
fun AppSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    color: Color = AppColors.surface,
    contentColor: Color = appContentColorFor(color),
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 0.dp,
    border: BorderStroke? = null,
    content: @Composable () -> Unit
) = Surface(
    modifier = modifier,
    shape = shape,
    color = color,
    contentColor = contentColor,
    tonalElevation = tonalElevation,
    shadowElevation = shadowElevation,
    border = border,
    content = content
)

/**
 * Variante clicable, con la misma corrección del color de contenido.
 *
 * `interactionSource` se expone porque hay callers que lo COMPARTEN: el botón de play del
 * reproductor anima su ancho con el mismo `MutableInteractionSource` con el que se pinta, así que
 * sin este parámetro no hay forma de que el efecto de pulsación y la animación hablen del mismo
 * gesto. Nullable como en M3, que lo sustituye por uno recordado cuando llega `null`.
 */
@Composable
fun AppSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RectangleShape,
    color: Color = AppColors.surface,
    contentColor: Color = appContentColorFor(color),
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 0.dp,
    border: BorderStroke? = null,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit
) = Surface(
    onClick = onClick,
    modifier = modifier,
    enabled = enabled,
    shape = shape,
    color = color,
    contentColor = contentColor,
    tonalElevation = tonalElevation,
    shadowElevation = shadowElevation,
    border = border,
    interactionSource = interactionSource,
    content = content
)

// --- Botones -------------------------------------------------------------------------------

/** Relleno: contenedor `primary`, etiqueta `onPrimary` (`FilledButtonTokens`). */
@Composable
fun appButtonColors() = ButtonDefaults.buttonColors(
    containerColor = AppColors.primary,
    contentColor = AppColors.onPrimary
)

/** De texto: sin contenedor, etiqueta `primary` (`TextButtonTokens`). */
@Composable
fun appTextButtonColors() = ButtonDefaults.textButtonColors(contentColor = AppColors.primary)

/** Con borde: etiqueta `onSurfaceVariant` (`OutlinedButtonTokens`); el borde sale de `outline`. */
@Composable
fun appOutlinedButtonColors() =
    ButtonDefaults.outlinedButtonColors(contentColor = AppColors.onSurfaceVariant)

/** Tonal: `secondaryContainer` / `onSecondaryContainer` (`FilledTonalButtonTokens`). */
@Composable
fun appFilledTonalButtonColors() = ButtonDefaults.filledTonalButtonColors(
    containerColor = AppColors.secondaryContainer,
    contentColor = AppColors.onSecondaryContainer
)

// --- Botones de icono ----------------------------------------------------------------------

// NO hay helper para el `IconButton` sin contenedor, y es a propósito. Su color por defecto NO sale
// del token sino de la HERENCIA:
//
// ```kotlin
// // material3 1.5.0-alpha24, IconButtonDefaults.kt:51
// fun iconButtonColors(): IconButtonColors {
//     val contentColor = LocalContentColor.current
//     ...
// ```
//
// O sea que ya se tiñe solo, porque `LocalContentColor` lo pone la superficie que lo contiene y esas
// superficies sí leen [AppColors]. Se llegó a añadir el helper y a aplicarlo en 25 sitios: eso
// FORZABA `onSurfaceVariant` y rompía la herencia justo donde importa —un icono dentro de un
// contenedor de acento dejaba de usar el color de ese contenedor—. Revertido. **Si alguien vuelve a
// echarlo en falta: el que hereda no necesita que le entreguen nada.**

/** Relleno: `primary` / `onPrimary` (`FilledIconButtonTokens`). */
@Composable
fun appFilledIconButtonColors() = IconButtonDefaults.filledIconButtonColors(
    containerColor = AppColors.primary,
    contentColor = AppColors.onPrimary
)

/** Tonal: `secondaryContainer` / `onSecondaryContainer` (`FilledTonalIconButtonTokens`). */
@Composable
fun appFilledTonalIconButtonColors() = IconButtonDefaults.filledTonalIconButtonColors(
    containerColor = AppColors.secondaryContainer,
    contentColor = AppColors.onSecondaryContainer
)

/**
 * Toggle relleno: apagado `surfaceContainer`/`onSurfaceVariant`, encendido `primary`/`onPrimary`.
 *
 * Es el de los toggles con shape-morph del toolbar del reproductor, así que el estado encendido es
 * justo donde se ve el acento del álbum.
 */
@Composable
fun appFilledIconToggleButtonColors() = IconButtonDefaults.filledIconToggleButtonColors(
    containerColor = AppColors.surfaceContainer,
    contentColor = AppColors.onSurfaceVariant,
    checkedContainerColor = AppColors.primary,
    checkedContentColor = AppColors.onPrimary
)

// --- Controles de ajuste -------------------------------------------------------------------

/** Switch: encendido sobre `primary`, apagado sobre la escala neutra (`SwitchTokens`). */
@Composable
fun appSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = AppColors.onPrimary,
    checkedTrackColor = AppColors.primary,
    checkedBorderColor = AppColors.primary,
    checkedIconColor = AppColors.onPrimaryContainer,
    uncheckedThumbColor = AppColors.outline,
    uncheckedTrackColor = AppColors.surfaceContainerHighest,
    uncheckedBorderColor = AppColors.outline,
    uncheckedIconColor = AppColors.surfaceContainerHighest
)

/**
 * Slider. Ojo con los ticks, que en M3 van CRUZADOS a propósito (`Slider.kt:1668`): el tick activo
 * lleva el color de la pista INACTIVA y viceversa, para que cada uno se vea sobre su propio tramo.
 */
@Composable
fun appSliderColors() = SliderDefaults.colors(
    thumbColor = AppColors.primary,
    activeTrackColor = AppColors.primary,
    activeTickColor = AppColors.secondaryContainer,
    inactiveTrackColor = AppColors.secondaryContainer,
    inactiveTickColor = AppColors.primary
)

/** Checkbox: marcado `primary` con el tilde en `onPrimary` (`CheckboxTokens`). */
@Composable
fun appCheckboxColors() = CheckboxDefaults.colors(
    checkedColor = AppColors.primary,
    uncheckedColor = AppColors.onSurfaceVariant,
    checkmarkColor = AppColors.onPrimary
)

/** Radio: seleccionado `primary` (`RadioButtonTokens`). */
@Composable
fun appRadioButtonColors() = RadioButtonDefaults.colors(
    selectedColor = AppColors.primary,
    unselectedColor = AppColors.onSurfaceVariant
)

// --- Chips, listas, barras y menús ---------------------------------------------------------

/** Filter chip: seleccionado sobre `secondaryContainer` (`FilterChipTokens`). */
@Composable
fun appFilterChipColors() = FilterChipDefaults.filterChipColors(
    labelColor = AppColors.onSurfaceVariant,
    iconColor = AppColors.onSurfaceVariant,
    selectedContainerColor = AppColors.secondaryContainer,
    selectedLabelColor = AppColors.onSecondaryContainer,
    selectedLeadingIconColor = AppColors.onSecondaryContainer
)

/** Card rellena: `surfaceContainerHighest` (`FilledCardTokens.ContainerColor`). */
@Composable
fun appCardColors() = CardDefaults.cardColors(
    containerColor = AppColors.surfaceContainerHighest,
    contentColor = AppColors.onSurface
)

/** Fila de lista de M3 (`ListTokens`): contenedor `surface`, apoyos `onSurfaceVariant`. */
@Composable
fun appListItemColors() = ListItemDefaults.colors(
    containerColor = AppColors.surface,
    headlineColor = AppColors.onSurface,
    leadingIconColor = AppColors.onSurfaceVariant,
    trailingIconColor = AppColors.onSurfaceVariant,
    overlineColor = AppColors.onSurfaceVariant,
    supportingColor = AppColors.onSurfaceVariant
)

/** Top app bar (`AppBarTokens`), incluido el color al que vira con el scroll. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun appTopAppBarColors() = TopAppBarDefaults.topAppBarColors(
    containerColor = AppColors.surface,
    scrolledContainerColor = AppColors.surfaceContainer,
    navigationIconContentColor = AppColors.onSurface,
    titleContentColor = AppColors.onSurface,
    actionIconContentColor = AppColors.onSurfaceVariant
)

/**
 * Campo de texto relleno (`FilledTextFieldTokens`).
 *
 * El acento está en tres sitios y sólo en tres: el cursor, la línea inferior CUANDO tiene el foco y
 * la etiqueta flotante con el foco. El resto es escala neutra, foco o no.
 */
@Composable
fun appTextFieldColors() = TextFieldDefaults.colors(
    focusedTextColor = AppColors.onSurface,
    unfocusedTextColor = AppColors.onSurface,
    focusedContainerColor = AppColors.surfaceContainerHighest,
    unfocusedContainerColor = AppColors.surfaceContainerHighest,
    cursorColor = AppColors.primary,
    focusedIndicatorColor = AppColors.primary,
    unfocusedIndicatorColor = AppColors.onSurfaceVariant,
    focusedLabelColor = AppColors.primary,
    unfocusedLabelColor = AppColors.onSurfaceVariant,
    focusedPlaceholderColor = AppColors.onSurfaceVariant,
    unfocusedPlaceholderColor = AppColors.onSurfaceVariant,
    focusedLeadingIconColor = AppColors.onSurfaceVariant,
    unfocusedLeadingIconColor = AppColors.onSurfaceVariant,
    focusedTrailingIconColor = AppColors.onSurfaceVariant,
    unfocusedTrailingIconColor = AppColors.onSurfaceVariant
)

/**
 * Entrada de menú de ACCIÓN (sin estado): texto `onSurface`, iconos `onSurfaceVariant`
 * (`ListTokens`).
 *
 * No lleva contenedor, y eso no es un olvido: `MenuDefaults.itemColors` ni siquiera lo acepta
 * (MenuDefaults.kt:411, seis campos). El item simple no pinta superficie — la pinta el grupo, que
 * por eso es [AppMenuGroup] y no el `DropdownMenuGroup` crudo.
 */
@Composable
fun appMenuItemColors() = MenuDefaults.itemColors(
    textColor = AppColors.onSurface,
    leadingIconColor = AppColors.onSurfaceVariant,
    trailingIconColor = AppColors.onSurfaceVariant
)

/**
 * Entrada de menú con ESTADO (`selected` / `checked`), que es otra API y otro juego de colores:
 * `MenuDefaults.selectableItemColors` (MenuDefaults.kt:452).
 *
 * Ésta **sí pinta su propio contenedor** —es lo que le permite morfear la forma leading/middle/
 * trailing dentro del grupo— y trae además los cuatro `selected*`. Confundirla con la de acción fue
 * el bug del menú gris del 20 ago: con los campos del contenedor sin entregar, el item seguía
 * sacándolos del tema, que ya no acompaña a la carátula.
 *
 * El estado seleccionado va sobre `tertiaryContainer` (`StandardMenuTokens.ItemSelectedContainerColor`);
 * no es un despiste, es el rol con el que M3 marca la opción activa de un menú.
 */
@Composable
fun appSelectableMenuItemColors() = MenuDefaults.selectableItemColors(
    textColor = AppColors.onSurface,
    containerColor = AppColors.surfaceContainerHighest,
    leadingIconColor = AppColors.onSurfaceVariant,
    trailingIconColor = AppColors.onSurfaceVariant,
    selectedContainerColor = AppColors.tertiaryContainer,
    selectedTextColor = AppColors.onTertiaryContainer,
    selectedLeadingIconColor = AppColors.onTertiaryContainer,
    selectedTrailingIconColor = AppColors.onTertiaryContainer
)

/**
 * Grupo de un menú desplegable, con el contenedor en el **TECHO de la escala neutra**.
 *
 * Ese techo es la decisión de diseño que describe [com.qhana.siku.ui.components.AppMenuPopup] y
 * hasta el 20 ago 2026 se conseguía REMAPEANDO el rol `surfaceContainerLow` en un `MaterialTheme`
 * anidado. Eso funcionaba mientras el tema seguía a la carátula; cuando dejó de seguirla, el menú se
 * quedó gris —visible en device— porque su color dependía de una cadena de defaults en vez de
 * recibirlo. Ahora se entrega, igual que en el resto de la app, y el remapeo desapareció.
 */
@Composable
fun AppMenuGroup(
    shapes: MenuGroupShapes,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) = DropdownMenuGroup(
    shapes = shapes,
    modifier = modifier,
    containerColor = AppColors.surfaceContainerHighest,
    content = content
)

/**
 * Snackbar con el par inverso de [AppColors].
 *
 * Se pasa como `snackbar = { AppSnackbar(it) }` en los `SnackbarHost`: el host no expone colores,
 * los pone el `Snackbar` de dentro, y sus defaults (`inverseSurface` / `inverseOnSurface` /
 * `inversePrimary`) salen del tema, que ya no acompaña a la carátula.
 */
@Composable
fun AppSnackbar(data: SnackbarData) = Snackbar(
    snackbarData = data,
    containerColor = AppColors.inverseSurface,
    contentColor = AppColors.inverseOnSurface,
    actionColor = AppColors.inversePrimary
)

/**
 * Floating toolbar VIBRANT: contenedor `primaryContainer` (`FloatingToolbarTokens`).
 *
 * Es el del toolbar del NowPlaying y el de la hoja de la cola, y fue **el primer sitio donde se
 * notó** que congelar el tema dejaba componentes atrás: su contenido ya salía de [AppColors] —está
 * escrito a mano en el caller— pero el CONTENEDOR lo resolvía M3 por dentro, así que la barra se
 * quedó azul (el `primaryContainer` del esquema base) con los iconos teñidos encima.
 *
 * El FAB adyacente va en `tertiaryContainer`, como en M3; aquí no se usa, pero se pasa igual para
 * que el helper no mienta si alguien lo estrena.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun appVibrantFloatingToolbarColors() = FloatingToolbarDefaults.vibrantFloatingToolbarColors(
    toolbarContainerColor = AppColors.primaryContainer,
    toolbarContentColor = AppColors.onPrimaryContainer,
    fabContainerColor = AppColors.tertiaryContainer,
    fabContentColor = AppColors.onTertiaryContainer
)

/**
 * Toggle button tonal (`TonalButtonTokens`): apagado `secondaryContainer`, encendido `secondary`.
 *
 * Ojo con el salto: el estado marcado NO es el contenedor tonal más oscuro sino el rol `secondary`
 * a secas, que es bastante más saturado. Así lo tabula M3 y así se queda.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun appTonalToggleButtonColors() = ToggleButtonDefaults.tonalToggleButtonColors(
    containerColor = AppColors.secondaryContainer,
    contentColor = AppColors.onSecondaryContainer,
    checkedContainerColor = AppColors.secondary,
    checkedContentColor = AppColors.onSecondary
)
