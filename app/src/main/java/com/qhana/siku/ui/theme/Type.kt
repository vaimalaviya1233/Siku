package com.qhana.siku.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight

/**
 * Tipografía de la app: **la escala de M3 tal cual, dibujada con Google Sans Flex**.
 *
 * No se declara ni un solo `TextStyle`. Hasta el 9 ago 2026 este archivo repetía los quince roles
 * a mano, y el balance de esa copia era: los tamaños y alturas de línea coincidían exactamente con
 * el spec (o sea, noventa líneas para no cambiar nada), y donde SÍ se desviaba era por descuido y
 * no por decisión — siete roles con más peso del que les toca (los tres `display`, los tres
 * `headline` y `titleLarge`, en Medium/SemiBold donde el spec pone Regular) y tres con el tracking
 * de una versión anterior del spec (`displayLarge`, `titleMedium`, `bodyMedium`). Transcribir una
 * tabla ajena solo crea ocasiones de que se desincronice.
 *
 * `Typography(fontFamily = …)` hace exactamente lo que hacía falta: cada rol sale de su token —los
 * quince clásicos **y los quince `*Emphasized` de Expressive**— con esta familia en lugar de la
 * fuente del sistema. Eso arregla de paso un bug real: `titleSmallEmphasized` (el título de la
 * canción en el MiniPlayer) y `labelLargeEmphasized` se dibujaban en Roboto, porque los roles no
 * declarados caían a `TypefaceTokens.Plain`. Y cubre los roles que M3 añada más adelante, cosa que
 * una lista escrita a mano nunca haría.
 *
 * **Si algún día hay que apartarse del spec, se declara ESE rol y solo ese**, con el motivo escrito
 * al lado. Un peso distinto porque sí no es identidad de marca: la identidad la pone la fuente, que
 * es lo que sí se elige aquí.
 */
@Composable
fun rememberAppTypography(): Typography {
    val context = LocalContext.current
    val assets = context.assets

    /*
     * Fuente VARIABLE, con una instancia por peso que el spec usa (400/500) más los dos que piden
     * los roles `*Emphasized` (600/700). Declararlas explícitamente evita que el sistema sintetice
     * la negrita deformando los trazos.
     *
     * `ROND 100` es el eje de redondez de Google Sans Flex, y eso SÍ es una decisión de marca: es
     * la variante redondeada la que acompaña al lenguaje Expressive del resto de la app.
     */
    val googleSansFlex = remember(assets) {
        FontFamily(
            Font(
                path = "fonts/GoogleSansFlex.ttf",
                assetManager = assets,
                variationSettings = FontVariation.Settings(
                    FontVariation.weight(400),
                    FontVariation.Setting("ROND", 100f)
                )
            ),
            Font(
                path = "fonts/GoogleSansFlex.ttf",
                assetManager = assets,
                weight = FontWeight.Medium,
                variationSettings = FontVariation.Settings(
                    FontVariation.weight(500),
                    FontVariation.Setting("ROND", 100f)
                )
            ),
            Font(
                path = "fonts/GoogleSansFlex.ttf",
                assetManager = assets,
                weight = FontWeight.SemiBold,
                variationSettings = FontVariation.Settings(
                    FontVariation.weight(600),
                    FontVariation.Setting("ROND", 100f)
                )
            ),
            Font(
                path = "fonts/GoogleSansFlex.ttf",
                assetManager = assets,
                weight = FontWeight.Bold,
                variationSettings = FontVariation.Settings(
                    FontVariation.weight(700),
                    FontVariation.Setting("ROND", 100f)
                )
            )
        )
    }

    return remember(googleSansFlex) { Typography(fontFamily = googleSansFlex) }
}
