package com.qhana.siku.data.model

/**
 * Política del usuario para canciones DUPLICADAS entre fuentes (mismo archivo visible por
 * la nube y por la carpeta local — caso típico: la carpeta local es un espejo sincronizado
 * de la nube). Se decide UNA vez (diálogo al detectar) y se persiste; el pipeline genérico
 * del sync la re-aplica idempotentemente en cada scan.
 *
 * null (sin persistir) = aún no se preguntó: el sync detecta y dispara el diálogo.
 */
enum class DuplicatePolicy {
    /** Conservar ambas copias (también se persiste: no volver a preguntar). */
    KEEP_BOTH,

    /** Conservar la copia de NUBE; las filas locales duplicadas se retiran. */
    PREFER_CLOUD,

    /** Conservar la copia LOCAL; las filas de nube duplicadas se retiran. */
    PREFER_LOCAL
}

/**
 * Normalización canónica de una ruta relativa para COMPARAR entre fuentes: separadores a
 * `/`, sin separadores repetidos, sin bordes, case-insensitive (OneDrive y FAT/exFAT lo son). La
 * columna `songs.relativePath` se persiste YA normalizada — el JOIN de duplicados compara igualdad
 * directa.
 *
 * Las barras repetidas se COLAPSAN, y eso es load-bearing: `MediaStore.RELATIVE_PATH` ya termina en
 * `/`, así que el modo dispositivo componía `…/final destination//12 8am.flac` mientras SAF y
 * OneDrive producen la misma ruta con una sola barra. Sin colapsar, el MISMO archivo tenía un id
 * distinto según el modo de escaneo —justo lo que el esquema de ids por volumen existe para
 * evitar— y su `relativePath` no casaba con el de la nube, de modo que el dedup entre fuentes no
 * veía el duplicado. Se detectó leyendo un id real en el log: `local:primary/music/coldrain/final
 * destination//12 8am (album ver.).flac`.
 */
fun normalizeRelativePath(raw: String): String =
    raw.replace('\\', '/').replace(REPEATED_SEPARATORS, "/").trim('/').lowercase()

private val REPEATED_SEPARATORS = Regex("/{2,}")
