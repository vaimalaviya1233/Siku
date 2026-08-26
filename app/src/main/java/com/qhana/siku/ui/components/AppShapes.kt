package com.qhana.siku.ui.components

import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Formas propias en el estilo de `MaterialShapes`: `RoundedPolygon` en el espacio 0..1 con centro en
 * (0.5, 0.5) y `normalized()` al final, listas para `toShape()` (clip/background) o para
 * [RoundedPolygonMaskTransformation] (hornear la máscara en el bitmap).
 *
 * Viven aquí y no dentro de la pantalla que las usa porque son ACTIVOS de diseño, no componentes:
 * el mismo polígono lo consumen la máscara de Coil y el fondo del placeholder, y ninguna de las dos
 * puede divergir de la otra sin que la foto y su hueco dejen de coincidir.
 */

// --- Anatomía de `MaterialShapes.Cookie6Sided`, leída de su fuente (material3 1.5.0-alpha24) ------
// M3 la construye con `customPolygon(listOf(PointNRound((0.723, 0.884), 0.394),
// PointNRound((0.500, 1.099), 0.398)), reps = 6)`: DOS puntos repetidos seis veces girando 60°, o
// sea 12 vértices que alternan valle y punta de lóbulo. Aquí se anotan por distancia al centro y no
// por coordenada, porque es la distancia lo que hay que mover para aplanarla.

/** Valle entre lóbulos: el punto (0.723, 0.884) está a 0.444 del centro, a 60° (y cada 60°). */
private const val COOKIE6_VALLEY_DISTANCE = 0.444f

/** Punta del lóbulo: el punto (0.500, 1.099) está a 0.599 del centro, a 90° (y cada 60°). */
private const val COOKIE6_LOBE_DISTANCE = 0.599f

/** Redondeo con el que M3 abomba la punta del lóbulo. Solo se ve mientras [HEXAGON_FLATTEN] < 1. */
private const val COOKIE6_LOBE_ROUNDING = 0.398f

/**
 * Cuánto se aplana la cookie: 0 la deja tal cual la dibuja M3 y **1 la convierte en un hexágono**,
 * que es como se usa.
 *
 * El truco es que los seis valles de la cookie YA son los vértices de un hexágono: al bajar la punta
 * del lóbulo hasta el segmento que une dos valles (distancia `valle · cos 30°`), el lóbulo deja de
 * sobresalir y el lado queda recto. Los 12 vértices se conservan —los seis puntos medios quedan
 * alineados y no aportan forma— para que el hexágono siga siendo la MISMA anatomía que la cookie:
 * un `Morph` entre las dos empareja curva con curva en vez de inventar el reparto.
 */
private const val HEXAGON_FLATTEN = 1f

/**
 * Fracción de cada lado que queda RECTA; el resto se lo reparten las dos esquinas redondeadas. Es el
 * parámetro COMPARTIDO por las formas de este archivo, y de él sale el radio del arco, no al revés:
 * lo que decide si algo se lee como polígono o como blob es cuánto lado plano queda a la vista, y
 * con un radio fijo esa proporción cambiaría con el número de lados.
 *
 * El redondeo original de la cookie (0.394) no sirve una vez aplanada: pide 0.228 de tangente contra
 * los 0.222 de medio lado, o sea que el arco se comería el lado ENTERO —la librería lo recortaría— y
 * el resultado sería un círculo con seis abolladuras. Con la mitad recta, la proporción queda cerca
 * de la del `Pentagon` de M3 (60 % recto).
 */
private const val STRAIGHT_EDGE_FRACTION = 0.5f

/** Los 6 lóbulos de la cookie, o los 6 lados del hexágono. */
private const val HEXAGON_SIDES = 6

/**
 * Hexágono de lados rectos y esquinas romas: la `Cookie6Sided` de M3 con los lóbulos aplanados (ver
 * [HEXAGON_FLATTEN]). Hereda de ella la orientación —**tapa plana** arriba y abajo, vértices a los
 * lados— así que es la misma silueta que ya venían teniendo las filas, sin la ondulación.
 *
 * Como toda `MaterialShapes`, termina en `normalized()`: escala UNIFORME y centra, de modo que ocupa
 * el ancho entero de su caja y 0.866 del alto. No se estira a cuadrado a propósito — una forma
 * deformada para llenar la caja se lee distinta de las de M3 puestas al lado (el propio `Pentagon`
 * queda en 0.924 de alto).
 */
val HexagonShape: RoundedPolygon by lazy {
    // Punta del lóbulo bajada hacia el segmento recto entre dos valles.
    val flatDistance = COOKIE6_VALLEY_DISTANCE * cos(radians(30f))
    val lobeDistance = lerp(COOKIE6_LOBE_DISTANCE, flatDistance, HEXAGON_FLATTEN)
    val lobeRounding = lerp(COOKIE6_LOBE_ROUNDING, 0f, HEXAGON_FLATTEN)

    // En un hexágono regular el lado mide lo mismo que el radio, así que la distancia del valle al
    // centro vale de largo de lado para calcular el redondeo.
    val valleyRounding = cornerRounding(side = COOKIE6_VALLEY_DISTANCE, sides = HEXAGON_SIDES)

    val vertices = FloatArray(HEXAGON_SIDES * 4)
    val rounding = ArrayList<CornerRounding>(HEXAGON_SIDES * 2)
    repeat(HEXAGON_SIDES) { i ->
        val step = i * 360f / HEXAGON_SIDES
        // El orden importa: el contorno va valle → punta → valle…, como en la cookie original.
        putVertex(vertices, i * 4, 60f + step, COOKIE6_VALLEY_DISTANCE)
        rounding.add(valleyRounding)
        putVertex(vertices, i * 4 + 2, 90f + step, lobeDistance)
        rounding.add(CornerRounding(lobeRounding))
    }
    RoundedPolygon(
        vertices = vertices,
        perVertexRounding = rounding,
        centerX = 0.5f,
        centerY = 0.5f,
    ).normalized()
}

/**
 * Redondeo que deja recta [STRAIGHT_EDGE_FRACTION] de un lado de largo [side] en un polígono regular
 * de [sides] lados. La tangente del arco es la mitad de lo que NO queda recto; el radio es esa
 * tangente por la tangente trigonométrica de MEDIO ángulo interior (120°/2 en el hexágono), que es
 * la relación entre ambos en una esquina.
 */
private fun cornerRounding(side: Float, sides: Int): CornerRounding {
    val tangent = side * (1f - STRAIGHT_EDGE_FRACTION) / 2f
    val halfInteriorAngle = radians((180f - 360f / sides) / 2f)
    return CornerRounding(tangent * tan(halfInteriorAngle))
}

/** Escribe en [vertices] (par x,y desde [index]) el punto a [degrees]° y [distance] del centro. */
private fun putVertex(vertices: FloatArray, index: Int, degrees: Float, distance: Float) {
    val angle = radians(degrees)
    vertices[index] = 0.5f + distance * cos(angle)
    vertices[index + 1] = 0.5f + distance * sin(angle)
}

private fun radians(degrees: Float): Float = degrees * PI.toFloat() / 180f

private fun lerp(start: Float, stop: Float, fraction: Float): Float =
    start + (stop - start) * fraction
