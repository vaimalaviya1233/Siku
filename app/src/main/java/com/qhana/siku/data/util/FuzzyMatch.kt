package com.qhana.siku.data.util

import java.text.Normalizer

/**
 * Coincidencia de texto TOLERANTE A ERRATAS para las búsquedas que se resuelven en memoria
 * (artistas y álbumes).
 *
 * Existe porque escribir `deram` no encontraba `Dream Theater`: la comparación era `contains`, que
 * es todo o nada, y una letra cambiada de sitio dejaba la búsqueda en blanco sin ninguna pista de
 * que había un resultado a un carácter de distancia.
 *
 * **No se aplica a las canciones**, que se buscan con `LIKE` dentro de una consulta paginada: ahí el
 * filtrado ocurre en SQLite, que no tiene distancia de edición, y traer la tabla a memoria para
 * compararla rompería el paginado. Ese caso necesita otra solución (índice de trigramas) y es un
 * trabajo aparte.
 */
object FuzzyMatch {

    /**
     * Erratas toleradas según lo escrito: una cada cuatro caracteres.
     *
     * La proporción es lo que hace que el criterio funcione en todo el rango sin una tabla de casos:
     * con `dua` (3) no se tolera ninguna —a esa longitud casi cualquier palabra queda a un carácter
     * y la lista se llenaría de ruido—, con `deram` (5) se tolera una, y a partir de nueve dos. Un
     * número fijo de erratas sería laxo en las cortas y estricto en las largas, que es justo al
     * revés de lo que se necesita.
     */
    private const val CHARS_PER_ALLOWED_TYPO = 4

    /**
     * ¿Coincide [candidate] con lo que se escribió en [query]?
     *
     * Primero se prueba la coincidencia normal por subcadena, que es lo que acierta casi siempre y
     * no cuesta nada; la comparación aproximada solo entra cuando aquella falla.
     */
    fun matches(candidate: String, query: String): Boolean = score(candidate, query) != NO_MATCH

    /**
     * Cuánto de bien coincide: **menor es mejor**, y [NO_MATCH] significa que no coincide.
     *
     * Ordenar por esto es lo que mantiene arriba lo que el usuario escribió literalmente, con las
     * aproximaciones detrás. Sin ese orden, una corrección de dos erratas podría colarse por delante
     * de una coincidencia exacta solo por ir antes en el abecedario.
     */
    fun score(candidate: String, query: String): Int {
        val needle = normalize(query)
        if (needle.isEmpty()) return 0
        val haystack = normalize(candidate)
        if (haystack.isEmpty()) return NO_MATCH

        // Coincidencia literal: la mejor. Se puntúa por POSICIÓN para que "beatles" ponga antes a
        // "Beatles" que a "The Beatles" — quien escribe el principio de un nombre suele buscar ese.
        val index = haystack.indexOf(needle)
        if (index >= 0) return index

        val tolerance = needle.length / CHARS_PER_ALLOWED_TYPO
        if (tolerance == 0) return NO_MATCH

        // Palabra a palabra, no contra el nombre entero: "deram" contra "dream theater" da una
        // distancia enorme si se compara con todo el nombre, y 1 si se compara con "dream". Es lo
        // que permite encontrar un artista escribiendo mal una sola de sus palabras.
        var best = NO_MATCH
        for (word in haystack.split(' ')) {
            if (word.isEmpty()) continue
            val distance = editDistance(word, needle, tolerance)
            if (distance <= tolerance && distance < best) best = distance
        }
        // Las aproximadas van SIEMPRE detrás de las literales, sea cual sea la posición en la que
        // aquellas casaron: el desempate entre exactas es un detalle, pero una exacta nunca debe
        // quedar por debajo de una corregida.
        return if (best == NO_MATCH) NO_MATCH else APPROXIMATE_BASE + best
    }

    /** Devuelto por [score] cuando no hay coincidencia. */
    const val NO_MATCH = Int.MAX_VALUE

    /**
     * Suelo de puntuación de las coincidencias aproximadas. Cualquier valor mayor que la posición
     * más larga que pueda devolver una coincidencia literal sirve; se usa un número grande y
     * redondo para que al leer un score se vea de un vistazo de qué tipo es.
     */
    private const val APPROXIMATE_BASE = 1_000_000

    /**
     * Minúsculas y sin diacríticos, para que `cancion` encuentre `Canción` y `dvorak` a `Dvořák`.
     * Escribir los acentos en un teclado de móvil es justo lo que la gente se salta al buscar.
     */
    private fun normalize(value: String): String =
        Normalizer.normalize(value.trim().lowercase(), Normalizer.Form.NFD)
            .replace(DIACRITICS, "")

    private val DIACRITICS = Regex("\\p{Mn}+")

    /**
     * Distancia de edición **con transposiciones** (Damerau-Levenshtein en su variante OSA) y con
     * CORTE en [limit]: en cuanto toda una fila lo supera se abandona, porque quien llama solo
     * necesita saber si cabe dentro de la tolerancia, no cuánto vale exactamente. Sobre una
     * biblioteca de miles de artistas eso ahorra casi todo el trabajo, ya que la inmensa mayoría de
     * los nombres no se parecen en nada a lo buscado.
     *
     * **Contar la transposición como UN error es lo que hace útil todo esto.** Intercambiar dos
     * letras al teclear rápido es la errata más común, y para Levenshtein a secas `dream` → `deram`
     * son DOS operaciones (borrar y volver a insertar), de modo que con la tolerancia de una palabra
     * corta quedaba fuera — precisamente el caso que motivó escribir esta clase. Con la
     * transposición cuesta 1 y se encuentra.
     *
     * Se guardan tres filas de la matriz en vez de la matriz entera; la tercera es la que permite
     * mirar dos posiciones atrás, que es lo que la transposición necesita.
     */
    private fun editDistance(a: String, b: String, limit: Int): Int {
        // Si la diferencia de longitudes ya excede la tolerancia, sobra el cálculo: hacen falta al
        // menos esas inserciones.
        if (kotlin.math.abs(a.length - b.length) > limit) return NO_MATCH
        if (a == b) return 0

        var beforePrevious = IntArray(b.length + 1)
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)

        for (i in 1..a.length) {
            current[0] = i
            var rowBest = current[0]
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                var best = minOf(current[j - 1] + 1, previous[j] + 1, substitution)
                // Transposición de dos caracteres adyacentes: "…re…" contra "…er…".
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    best = minOf(best, beforePrevious[j - 2] + 1)
                }
                current[j] = best
                if (best < rowBest) rowBest = best
            }
            if (rowBest > limit) return NO_MATCH
            val recycled = beforePrevious
            beforePrevious = previous
            previous = current
            current = recycled
        }
        return previous[b.length]
    }
}
