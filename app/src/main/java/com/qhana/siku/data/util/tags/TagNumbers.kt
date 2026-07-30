package com.qhana.siku.data.util.tags

/**
 * Conversión a entero de los dos tags numéricos que casi nunca vienen como un número limpio.
 *
 * Viven aquí, fuera de los lectores, porque los TRES caminos de metadata los necesitan con el
 * mismo criterio: el análisis completo del archivo ([com.qhana.siku.data.util.AudioFileAnalyzer],
 * vía `MediaMetadataRetriever`), la lectura parcial por HTTP `Range` ([PartialTagReader]) y el
 * índice del sistema (`MediaStore`). Si cada uno parsease a su manera, la misma canción tendría
 * distinto número de pista según por dónde entró a la biblioteca.
 *
 * **0 = ausente o ilegible**, que es el mismo valor con el que `Song.trackNumber`/`Song.year`
 * expresan "aún no leído". No se distingue "no lo tiene" de "no lo pude leer" a propósito: a
 * diferencia de la carátula, releer un tag numérico es barato y no hay nada que sellar.
 */

/**
 * Número de pista. El tag puede venir como `4`, `04`, `4/12` (pista/total, la forma canónica de
 * ID3v2 `TRCK`) o `4 / 12`. Se queda con lo que hay antes de la barra.
 */
fun parseTrackNumber(raw: String?): Int {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return 0
    return text.substringBefore('/').trim().toIntOrNull()?.takeIf { it > 0 } ?: 0
}

/**
 * Año de publicación. El tag es un campo de FECHA, no de año: Vorbis `DATE` e ID3v2.4 `TDRC`
 * admiten ISO-8601 completo (`1992-05-03`, `1992-05`), mientras que ID3v2.3 `TYER` son cuatro
 * dígitos pelados. Se toma la primera secuencia de cuatro dígitos, que cubre las tres formas y
 * también las fechas escritas al revés (`03/05/1992`).
 *
 * El rango descarta basura sin inventar reglas: [MIN_PLAUSIBLE_YEAR] es anterior a la primera
 * grabación sonora conocida (1860), así que nada legítimo cae por debajo, y el tope deja pasar
 * cualquier fecha futura razonable en vez de atarse al año en curso —que obligaría a leer el
 * reloj y volvería el parseo dependiente de la fecha del dispositivo—.
 */
fun parseYear(raw: String?): Int {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return 0
    val digits = YEAR_PATTERN.find(text)?.value ?: return 0
    return digits.toIntOrNull()?.takeIf { it in MIN_PLAUSIBLE_YEAR..MAX_PLAUSIBLE_YEAR } ?: 0
}

/** Cuatro dígitos consecutivos: el año dentro de una fecha en cualquiera de sus formas. */
private val YEAR_PATTERN = Regex("""\d{4}""")

/** Anterior a cualquier grabación existente: por debajo de esto el tag es basura. */
private const val MIN_PLAUSIBLE_YEAR = 1800

/** Tope holgado: no depende del reloj del dispositivo (ver KDoc de [parseYear]). */
private const val MAX_PLAUSIBLE_YEAR = 2999
