package com.qhana.siku.data.util.tags

import javax.inject.Inject

/**
 * MP3 (y cualquier archivo con ID3v2 delante): `ID3` + versión + flags + tamaño, y luego frames
 * `TIT2` (título), `TPE1` (artista), `TALB` (álbum), `TPE2` (artista del álbum), `TCON` (género),
 * `USLT` (letra) y `APIC` (carátula).
 *
 * Dos trampas propias del formato, ambas contempladas aquí:
 * - **Syncsafe**: el tamaño del tag usa 7 bits por byte (el bit alto siempre 0, para que no se
 *   confunda con una cabecera de frame MPEG). Leerlo como un uint32 normal da un tamaño enorme.
 * - **ID3v2.2**: frames de 3 letras (`TT2`, `TP1`, `PIC`) y cabecera de 6 bytes en vez de 10.
 *   Es viejo pero sigue apareciendo en bibliotecas rippeadas hace 20 años.
 *
 * ID3v1 (128 bytes al FINAL del archivo) no se soporta: exigiría una segunda petición al final
 * para 30 caracteres truncados y sin carátula. Esos archivos caen al camino universal.
 */
class Id3v2TagReader @Inject constructor() : PartialTagReader {

    override fun supports(extension: String): Boolean = extension in SUPPORTED

    override fun read(fragment: ByteArray): TagFragment? {
        if (fragment.size < HEADER_BYTES) return null
        if (fragment[0] != 'I'.code.toByte() || fragment[1] != 'D'.code.toByte() ||
            fragment[2] != '3'.code.toByte()
        ) return null

        val majorVersion = fragment[3].toInt() and 0xFF
        val tagSize = readSyncsafe(fragment, 6)
        if (tagSize <= 0) return null

        val isV22 = majorVersion == 2
        val frameHeaderBytes = if (isV22) V22_FRAME_HEADER_BYTES else FRAME_HEADER_BYTES
        val idLength = if (isV22) 3 else 4
        // El tag puede exceder el fragmento (carátula grande): se lee hasta donde haya bytes.
        val tagEnd = minOf(HEADER_BYTES + tagSize, fragment.size)

        var pos = HEADER_BYTES
        val text = HashMap<String, String>()
        var pictureData: ByteArray? = null

        while (pos + frameHeaderBytes <= tagEnd) {
            val id = String(fragment, pos, idLength, Charsets.ISO_8859_1)
            if (id[0] == '\u0000') break // zona de padding: se acabaron los frames
            val size = if (isV22) {
                readUInt24BE(fragment, pos + 3)
            } else if (majorVersion >= 4) {
                // v2.4 también usa syncsafe para el tamaño de cada frame; v2.3 no.
                readSyncsafe(fragment, pos + 4)
            } else {
                readUInt32BE(fragment, pos + 4)
            }
            val bodyStart = pos + frameHeaderBytes
            if (size <= 0 || bodyStart + size > tagEnd) break

            when (id) {
                "TIT2", "TT2" -> text["TITLE"] = decodeText(fragment, bodyStart, size)
                "TPE1", "TP1" -> text["ARTIST"] = decodeText(fragment, bodyStart, size)
                "TALB", "TAL" -> text["ALBUM"] = decodeText(fragment, bodyStart, size)
                "TPE2", "TP2" -> text["ALBUMARTIST"] = decodeText(fragment, bodyStart, size)
                "TCON", "TCO" -> text["GENRE"] = normalizeGenre(decodeText(fragment, bodyStart, size))
                // Un archivo puede traer un USLT por idioma; nos quedamos con el primero.
                "USLT", "ULT" -> if ("LYRICS" !in text) {
                    extractLyrics(fragment, bodyStart, size)?.let { text["LYRICS"] = it }
                }
                "APIC", "PIC" -> if (pictureData == null) {
                    pictureData = extractPicture(fragment, bodyStart, size, isV22)
                }
            }
            pos = bodyStart + size
        }

        return TagFragment(
            title = text["TITLE"],
            artist = text["ARTIST"],
            album = text["ALBUM"],
            albumArtist = text["ALBUMARTIST"],
            genre = text["GENRE"],
            lyrics = pickBestLyrics(text["LYRICS"]),
            pictureData = pictureData,
            // El offset del APIC dentro del tag no es estable si el frame quedó cortado a medias:
            // no se ofrece rango exacto. Sin carátula en el fragmento, el álbum la conseguirá de
            // otra de sus canciones o al descargarse.
            pictureRange = null
        )
    }

    /**
     * Primer byte = codificación: 0 ISO-8859-1, 1 UTF-16 con BOM, 2 UTF-16BE, 3 UTF-8.
     * Se recorta en el primer NUL: muchos taggers rellenan el resto del frame.
     */
    private fun decodeText(buf: ByteArray, start: Int, size: Int): String {
        if (size <= 1) return ""
        val charset = charsetFor(buf[start].toInt())
        return String(buf, start + 1, size - 1, charset).substringBefore('\u0000').trim()
    }

    private fun charsetFor(encoding: Int) = when (encoding) {
        ENCODING_UTF16_BOM -> Charsets.UTF_16
        ENCODING_UTF16_BE -> Charsets.UTF_16BE
        ENCODING_UTF8 -> Charsets.UTF_8
        else -> Charsets.ISO_8859_1
    }

    /**
     * `USLT`: codificación, idioma (3 bytes), un descriptor terminado en NUL y, desde ahí, la
     * letra hasta el final del frame.
     *
     * A diferencia de los frames de texto normales, aquí NO se puede cortar en el primer NUL: la
     * letra es multilínea y ese corte la dejaría en el primer renglón. Solo se limpian los bordes.
     */
    private fun extractLyrics(buf: ByteArray, start: Int, size: Int): String? {
        val end = start + size
        var p = start
        if (p >= end) return null
        val encoding = buf[p].toInt(); p++
        p += LANGUAGE_BYTES
        if (p >= end) return null
        val wide = encoding == ENCODING_UTF16_BOM || encoding == ENCODING_UTF16_BE
        p = skipPastNul(buf, p, end, wide)
        if (p >= end) return null
        return String(buf, p, end - p, charsetFor(encoding)).trim().takeIf { it.isNotBlank() }
    }

    /** `(17)` o `(17)Rock` son códigos ID3v1 heredados; nos quedamos con el texto si lo hay. */
    private fun normalizeGenre(raw: String): String {
        if (!raw.startsWith("(")) return raw
        val afterCode = raw.substringAfter(')', "").trim()
        return afterCode.ifBlank { raw }
    }

    /**
     * APIC: codificación, mime (terminado en NUL; en v2.2 son 3 bytes fijos), tipo de imagen,
     * descripción (terminada en NUL con el ancho de la codificación) y los bytes de la imagen.
     */
    private fun extractPicture(buf: ByteArray, start: Int, size: Int, isV22: Boolean): ByteArray? {
        val end = start + size
        var p = start
        val encoding = buf[p].toInt(); p++
        p = if (isV22) p + 3 else skipPastNul(buf, p, end, wide = false)
        if (p >= end) return null
        p++ // tipo de imagen (portada, contraportada, …)
        p = skipPastNul(buf, p, end, wide = encoding == ENCODING_UTF16_BOM || encoding == ENCODING_UTF16_BE)
        if (p >= end) return null
        return buf.copyOfRange(p, end)
    }

    private fun skipPastNul(buf: ByteArray, from: Int, end: Int, wide: Boolean): Int {
        var p = from
        if (wide) {
            while (p + 1 < end && !(buf[p] == 0.toByte() && buf[p + 1] == 0.toByte())) p += 2
            return p + 2
        }
        while (p < end && buf[p] != 0.toByte()) p++
        return p + 1
    }

    /** 7 bits por byte: el bit alto de cada uno es siempre 0 (ver KDoc de la clase). */
    private fun readSyncsafe(buf: ByteArray, at: Int): Int =
        ((buf[at].toInt() and 0x7F) shl 21) or
            ((buf[at + 1].toInt() and 0x7F) shl 14) or
            ((buf[at + 2].toInt() and 0x7F) shl 7) or
            (buf[at + 3].toInt() and 0x7F)

    private fun readUInt24BE(buf: ByteArray, at: Int): Int =
        ((buf[at].toInt() and 0xFF) shl 16) or
            ((buf[at + 1].toInt() and 0xFF) shl 8) or
            (buf[at + 2].toInt() and 0xFF)

    private fun readUInt32BE(buf: ByteArray, at: Int): Int =
        ((buf[at].toInt() and 0xFF) shl 24) or
            ((buf[at + 1].toInt() and 0xFF) shl 16) or
            ((buf[at + 2].toInt() and 0xFF) shl 8) or
            (buf[at + 3].toInt() and 0xFF)

    private companion object {
        val SUPPORTED = setOf("mp3", "aac")
        const val HEADER_BYTES = 10
        const val FRAME_HEADER_BYTES = 10
        const val V22_FRAME_HEADER_BYTES = 6
        /** Códigos de codificación de texto del primer byte de un frame (spec ID3v2). */
        const val ENCODING_UTF16_BOM = 1
        const val ENCODING_UTF16_BE = 2
        const val ENCODING_UTF8 = 3
        /** `USLT`/`SYLT` llevan el idioma ISO-639-2 en 3 bytes, entre codificación y descriptor. */
        const val LANGUAGE_BYTES = 3
    }
}
