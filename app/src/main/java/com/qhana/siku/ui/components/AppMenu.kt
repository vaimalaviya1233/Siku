package com.qhana.siku.ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.DropdownMenuPopupPositionProvider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorPosition
import androidx.compose.material3.MenuDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.PopupProperties

/**
 * El `DropdownMenuPopup` de la app: el de material3 con **el contenedor del menú un peldaño más
 * alto en la escala neutra**. Es el punto de entrada ÚNICO para cualquier menú desplegable —
 * `DropdownMenuPopup` crudo no debe aparecer en el repo, igual que `ModalBottomSheet` crudo
 * tampoco (ver [AppModalSheet]).
 *
 * **El problema**: M3 pinta el menú con `surfaceContainerLow`, que está a UN peldaño de `surface`
 * —el fondo de casi todas las pantallas— y a dos del `surfaceContainer` de las barras. Con un tema
 * dinámico, cuya escala neutra apenas tiene croma, esa distancia no se ve: el menú se fundía con lo
 * que tapaba y solo lo delataba su sombra. Es exactamente el agotamiento de la escala neutra que ya
 * obligó a separar el MiniPlayer por croma; aquí la salida es la contraria —quedarse en la escala,
 * pero en su TECHO— porque un menú de varias entradas teñido de acento pesa demasiado para algo que
 * se abre y se cierra en un segundo. (M3 tabula esa otra opción: `groupVibrantContainerColor`, sobre
 * `tertiaryContainer`. Su propio doc pide usarla "sparingly", y aquí serían los nueve menús.)
 *
 * **Por qué el TECHO y no un peldaño intermedio**: las superficies sobre las que un menú puede caer
 * en esta app van de `surface` a `surfaceContainerHigh` — la biblioteca y los detalles reparten en
 * HORQUILLA (contenido `surface` 98, página `surfaceContainer` 94, barras `surfaceContainerHigh`
 * 92; ver `headerColor` en LibraryScreen). Cualquier valor por debajo del techo empataría con alguna
 * de ellas en alguna pantalla; `surfaceContainerHighest` es el único que no puede, y por eso es una
 * decisión y no un gusto calibrado a ojo.
 *
 * Ojo al tocar ese reparto: el 20 ago 2026 la cabecera de la biblioteca estuvo unas horas en este
 * mismo `surfaceContainerHighest` y el menú del ⋮ de la búsqueda nacía del color de la barra que lo
 * abría. Se revirtió por otro motivo, pero deja la lección: **este techo solo es un techo mientras
 * nadie más lo ocupe.**
 *
 * **Por qué se hace REMAPEANDO el rol y no pasando `containerColor`**: el color del menú no está en
 * un sitio, está en dos. `DropdownMenuGroup` pinta su superficie, pero **cada `DropdownMenuItem`
 * pinta ADEMÁS la suya** (por eso puede morfear su forma leading/middle/trailing), las dos con el
 * mismo token; el grupo solo asoma por su `contentPadding`. Pasar `containerColor` obligaría a
 * tocar el grupo Y los 37 items repartidos por la app, y a acordarse en cada item nuevo — el fallo
 * sería silencioso y visual. Redefiniendo el ROL dentro del subárbol del menú, ambos lo heredan, y
 * un menú que se añada mañana también.
 */
@Composable
fun AppMenuPopup(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    popupPositionProvider: DropdownMenuPopupPositionProvider =
        MenuDefaults.rememberDropdownMenuPopupPositionProvider(MenuAnchorPosition.Below),
    properties: PopupProperties = MenuDefaults.DefaultMenuProperties,
    content: @Composable ColumnScope.() -> Unit
) {
    DropdownMenuPopup(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        popupPositionProvider = popupPositionProvider,
        properties = properties
    ) {
        // El `ColumnScope` del popup se captura para devolvérselo al contenido: `MaterialTheme`
        // toma una lambda SIN receiver, así que sin esto el llamador perdería el scope (y con él
        // `weight`, `align` y compañía) solo por haber envuelto el tema.
        val columnScope = this
        val scheme = MaterialTheme.colorScheme
        // `remember`ado a propósito: `MaterialTheme` es un local ESTÁTICO y compara el esquema por
        // IDENTIDAD, así que un `copy()` nuevo por recomposición invalidaría el menú entero en cada
        // frame (es la misma razón por la que `rememberUnderlayColorScheme` existe).
        val menuScheme = remember(scheme) {
            scheme.copy(surfaceContainerLow = scheme.surfaceContainerHighest)
        }
        // Solo el esquema: `MaterialTheme` hereda tipografía, formas y motion de fuera, así que el
        // menú no se despega del tema en nada más.
        MaterialTheme(colorScheme = menuScheme) {
            with(columnScope) { content() }
        }
    }
}
