package com.qhana.siku.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
 *
 * ## Para dar énfasis está el rol `*Emphasized`, NUNCA un peso a mano
 *
 * Ni `style = X.copy(fontWeight = …)` ni el parámetro suelto `Text(fontWeight = …)`. La limpieza de
 * este archivo (9 ago 2026) sacó los pesos inventados de la escala pero los dejó vivos en los sitios
 * de uso: quedaban **37** repartidos por la UI, y el 19 ago se migraron todos a su `*Emphasized`.
 * Lo que se aprendió al hacerlo, y que explica por qué la regla merece la pena:
 *
 *  - **Cinco no hacían NADA**: pedían `Medium` sobre roles `label*`/`title*`, que ya nacen en
 *    Medium. Documentaban una decisión de diseño inexistente.
 *  - **El peso del énfasis NO es constante: depende del tamaño del rol.** M3 sube a Bold solo de
 *    16sp hacia abajo (`titleMedium`, `titleSmall`, `label*`); de `titleLarge` hacia arriba se queda
 *    en Medium, porque a ese cuerpo la masa óptica ya la pone el tamaño. Escribir `Bold` a mano en
 *    un `headlineLarge` es pasarse, y escribir `SemiBold` en un `titleSmall` es quedarse corto — las
 *    dos cosas pasaban a la vez en este repo. **`SemiBold` ni siquiera existe en la escala de M3**:
 *    sus tokens solo usan Regular / Medium / Bold.
 *  - **El par base/Emphasized no siempre comparte tracking** (`bodyLarge` 0.5 → 0.15, `titleMedium`
 *    0.2 → 0.15), así que un `.copy(fontWeight = …)` deja el tracking del peso ligero sobre un trazo
 *    más gordo. Es el defecto que ya estaba documentado en `SongListItem`.
 *  - Y el síntoma que lo destapó: en el NowPlaying, título, artista y álbum habían acabado los tres
 *    en peso 500, o sea un bloque sin un solo salto de peso. La jerarquía se recupera bajando el
 *    apoyo a roles `body` (que nacen en Regular), no engordando el titular.
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

    // --- PRECALENTADO DE LA CACHÉ DE TYPEFACES ---
    //
    // Derivar el `Typeface` de cada peso es lo caro de esta familia: los cuatro salen del MISMO
    // archivo y se distinguen por `variationSettings`, así que cada uno hay que construirlo con
    // `Typeface.Builder(...).setFontVariationSettings(...)` la primera vez que alguien lo pide.
    //
    // Sin esto, esa factura la paga el primer frame que necesite cada peso. **Medido con Perfetto el
    // 20 ago 2026**: un frame de 172 ms al empezar a sonar la primera canción, del que 150 eran
    // `measure` y 114 cuatro `TextStringSimpleNode::measure` de 42/42/16/14 ms. Que era resolución de
    // fuente y no medida de texto lo dice el propio trace: `TextLayout:initLayout` corre 651 veces
    // en 23 ms —0,035 ms cada una— y esas cuatro medidas caras son las ÚNICAS de toda la captura.
    //
    // **`FontFamily.Resolver.preload` NO vale para esto**, aunque el nombre lo prometa: su
    // implementación filtra `loadingStrategy == Async` ("only preload styles that can be satisfied by
    // async fonts") y una fuente de assets es `AndroidAssetFont` → `AndroidPreloadedFont`, que pasa
    // `FontLoadingStrategy.Blocking`. Con cero fuentes async recorre una lista vacía y no hace nada.
    // Lo que sí puebla la caché de `TypefaceRequestCache` es pedir cada peso con `resolve`.
    val resolver = LocalFontFamilyResolver.current
    LaunchedEffect(googleSansFlex, resolver) {
        // En background: construir los cuatro typefaces es justo el trabajo que no queremos en el
        // hilo principal, y no hay nada que esperar — el resultado va a una caché compartida.
        withContext(Dispatchers.Default) {
            PRELOADED_WEIGHTS.forEach { weight ->
                // Un fallo aquí no puede tumbar la app: sin caché, la fuente se resolverá luego por
                // la vía normal y lo único que se pierde es el precalentado.
                runCatching { resolver.resolve(googleSansFlex, weight, FontStyle.Normal) }
            }
        }
    }

    return remember(googleSansFlex) { Typography(fontFamily = googleSansFlex) }
}

/**
 * Los pesos que la app llega a pedir, y por tanto los que hay que tener construidos antes de que un
 * frame los necesite: 400/500 de la escala clásica de M3 y 600/700 de los roles `*Emphasized`. Es la
 * misma lista que declara la familia — si allí se añade un peso, aquí también.
 */
private val PRELOADED_WEIGHTS = listOf(
    FontWeight.Normal,
    FontWeight.Medium,
    FontWeight.SemiBold,
    FontWeight.Bold
)
