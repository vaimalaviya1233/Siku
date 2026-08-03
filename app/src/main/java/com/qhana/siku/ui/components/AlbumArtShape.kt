package com.qhana.siku.ui.components

import androidx.compose.material3.*
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.TransformResult
import androidx.graphics.shapes.rectangle
import kotlin.math.min

/**
 * Radio de las esquinas del squircle: token `extraExtraLarge` de M3, la esquina más grande que
 * define el sistema. Se probó 32dp (`extraLargeIncreased`) y se descartó por escucha visual — a
 * ese radio la carátula se lee como tarjeta sobria y pierde el carácter Expressive, que es
 * justamente lo que aporta el token grande.
 *
 * Es un valor ABSOLUTO en dp y NO una fracción del lado, a propósito: con una fracción el radio
 * depende del tamaño de la carátula, así que la MISMA forma se leía distinta en portrait (~363dp
 * de lado) que en landscape, donde la carátula es bastante menor. Material especifica esquinas en
 * dp por esa razón. El valor anterior (28% del lado) daba ~102dp en portrait, más del doble de
 * este token, y se veía excesivamente redondo.
 */
private val SQUIRCLE_CORNER_RADIUS = 48.dp

/**
 * Key del shared element de la carátula entre la píldora y el reproductor.
 *
 * **Es CONSTANTE, y no puede llevar el id de la canción.** Lo llevó hasta el 30 jul
 * (`"album_art_${song.id}"`) y ese era el bug de "la carátula ya está ahí desde que el NowPlaying
 * aparece desde abajo": al abrir una canción DISTINTA desde una lista, la píldora todavía mostraba
 * la anterior, así que las dos puntas del par pedían keys DIFERENTES y no había match posible. Sin
 * match no hay animación de bounds, y como un shared element se pinta en el overlay del
 * `SharedTransitionScope` —que cuelga de la raíz y NO recibe el `graphicsLayer` del slide— la
 * portada aparecía clavada en su posición final mientras el resto del reproductor subía por debajo.
 * El síntoma sobrevivió a acoplar las duraciones porque nunca fue un problema de tiempo: no había
 * nada que animar.
 *
 * Lo que el shared element representa es **la superficie de la carátula**, un elemento persistente
 * de la UI que existe en los dos sitios, no la portada de un tema concreto. Que la imagen de dentro
 * cambie es asunto del contenido (y de `announceSelection`, que pone la nueva en la píldora en el
 * mismo frame del tap para que lo que viaja sea ya la portada correcta).
 *
 * Con la key fija, además, la punta de origen SIEMPRE está compuesta y medida antes de que el
 * player se expanda — un elemento creado y marcado como saliente en el mismo frame no tiene bounds
 * previos que ofrecer como origen.
 */
const val ALBUM_ART_SHARED_KEY = "album_art"

/**
 * Key del shared element de la carátula de UNA FILA de lista. Lleva el id de la canción, al revés
 * que [ALBUM_ART_SHARED_KEY], y las dos razones son la misma moneda:
 *
 * Una punta de shared element solo sirve de ORIGEN si ya estaba compuesta y MEDIDA antes del gesto
 * — declararla en el mismo frame en que se la necesita la deja sin bounds y no hay match (bug del
 * 30 jul: la portada aparecía quieta en su destino mientras el reproductor subía). O sea que las
 * filas tienen que declararla SIEMPRE, no solo cuando les toca ser origen.
 *
 * Y si todas las filas visibles declararan la MISMA key habría diez destinos peleándose por ella,
 * que es el otro error que Compose no perdona. Con el id dentro, cada fila es su propio shared
 * element solitario —inofensivo mientras nadie lo empareja— y el reproductor elige a cuál se
 * engancha pidiendo la key de la canción que va a sonar.
 *
 * La píldora puede permitirse la key constante porque es ÚNICA: no compite con nadie.
 */
fun rowArtSharedKey(songId: String): String = "album_art_row_$songId"

/**
 * Forma de la carátula, común al MiniPlayer y al NowPlaying: morph continuo entre un SQUIRCLE
 * (reproduciendo, progress 0) y un círculo (en pausa, progress 1; el mini lo usa fijo).
 *
 * El squircle es un cuadrado propio con [SQUIRCLE_CORNER_RADIUS] en vez de [MaterialShapes.Square]:
 * la forma del sistema apenas redondea las esquinas y en una carátula grande se leía como un
 * cuadro plano. Bajar el radio alarga el camino hasta el círculo, así que el morph al pausar se
 * nota más — lo absorbe el spring suave de `AlbumArtSection`, pero es el trade-off a vigilar si
 * se sigue bajando.
 *
 * Vive en `ui.components` (no en la pantalla) porque los DOS extremos del shared element de la
 * carátula la necesitan con el MISMO valor: si el mini usara un círculo fijo y el NowPlaying un
 * squircle, la transición saltaría de forma. Compartiéndola, ambos extremos coinciden para cada
 * estado de reproducción y el shared element solo interpola bounds.
 *
 * ## Rendimiento: esta forma vive en el camino más caliente de la app
 *
 * `createOutline` corre en cada frame de la apertura del reproductor (el shared element cambia
 * los bounds de la carátula frame a frame, en el mini Y en el player a la vez), encima del slide,
 * de la primera composición del NowPlaying y de la animación de los 31 colores del tema. La
 * versión inicial construía DOS `RoundedPolygon` + un [Morph] (matching de curvas, caro) en cada
 * llamada y devolvía siempre `Outline.Generic` (clip por Path, sin la vía rápida de hardware) —
 * medible como parte del tartamudeo al abrir una canción desde una lista. De ahí las tres reglas
 * de esta clase:
 *
 *  1. **En los extremos no hay Morph**: `p == 0` es un rectángulo redondeado y `p == 1` un
 *     círculo, y ambos se devuelven como [Outline.Rounded] — geometría idéntica a la del polígono
 *     (las esquinas de `RoundedPolygon` sin smoothing son arcos circulares) y clip acelerado por
 *     hardware. Son los DOS estados de reposo: el mini vive siempre en 1 y el player en 0 o 1, así
 *     que el camino caro queda solo para el tramo intermedio del morph al pausar/reanudar.
 *  2. **El Morph intermedio se cachea por tamaño** ([MorphCache]): mientras la forma morfea, el
 *     tamaño de la carátula no cambia (la "respiración" escala por graphicsLayer, no por layout),
 *     así que por frame solo se paga `toPath(p)`, no el matching.
 *  3. **Igualdad por valor** ([equals]/[hashCode]): el modifier de clip compara la forma nueva con
 *     la anterior, y una clase sin equals hacía que CADA recomposición (p. ej. los 31 colores del
 *     tema animándose al cambiar de canción) invalidara el outline aunque ni el progreso ni el
 *     tamaño hubieran cambiado.
 */
// El @OptIn cubre la extensión `Morph.toPath` de material3 (experimental, igual que MaterialShapes).
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
class AlbumArtMorphShape(private val progress: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        // El spring rebota fuera de [0,1]; Morph solo acepta ese rango.
        val p = progress.coerceIn(0f, 1f)
        // `minDimension` puede ser 0 en la primera medición: sin la guarda, las divisiones de
        // abajo darían NaN y el path saldría vacío.
        val side = size.minDimension
        if (side <= 0f) return Outline.Rectangle(size.toRect())
        // El radio en dp no puede superar la mitad del lado (= fracción 0.5 del espacio
        // normalizado del polígono, el tope que ya imponía la versión anterior).
        val radiusPx = min(with(density) { SQUIRCLE_CORNER_RADIUS.toPx() }, side / 2f)
        if (p == 0f) {
            return Outline.Rounded(RoundRect(size.toRect(), CornerRadius(radiusPx)))
        }
        if (p == 1f) {
            // Radios = semiejes: círculo en la carátula cuadrada, elipse si algún día no lo fuera
            // (que es lo mismo que dibuja el polígono círculo escalado por ancho/alto).
            return Outline.Rounded(
                RoundRect(size.toRect(), CornerRadius(size.width / 2f, size.height / 2f))
            )
        }
        return Outline.Generic(MorphCache.get(size, radiusPx / side).toPath(p))
    }

    override fun equals(other: Any?): Boolean =
        other is AlbumArtMorphShape && other.progress == progress

    override fun hashCode(): Int = progress.hashCode()
}

/**
 * Último [Morph] construido, con su clave. Un solo hueco basta: el único consumidor que llega al
 * tramo intermedio es la carátula del NowPlaying (el mini vive en el fast path de `p == 1`), y
 * durante su morph el tamaño es constante. Sin sincronización a propósito — `createOutline` corre
 * solo en el hilo de UI.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private object MorphCache {
    private var lastSize: Size = Size.Unspecified
    private var lastRounding: Float = -1f
    private var lastMorph: Morph? = null

    fun get(size: Size, rounding: Float): Morph {
        lastMorph?.let { if (size == lastSize && rounding == lastRounding) return it }
        // Espacio normalizado 0..1 (igual que las MaterialShapes) y luego a píxeles.
        val squircle = RoundedPolygon.rectangle(
            width = 1f,
            height = 1f,
            centerX = 0.5f,
            centerY = 0.5f,
            rounding = CornerRounding(rounding)
        ).transformed { x, y ->
            TransformResult(x * size.width, y * size.height)
        }
        val circle = MaterialShapes.Circle.transformed { x, y ->
            TransformResult(x * size.width, y * size.height)
        }
        return Morph(squircle, circle).also {
            lastSize = size
            lastRounding = rounding
            lastMorph = it
        }
    }
}
