package com.qhana.siku.data.util.tags

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Construye FLACs sintéticos byte a byte y verifica que [FlacTagReader] los diseca igual que la
 * medición hecha sobre la biblioteca real (VORBIS_COMMENT little-endian, PICTURE big-endian,
 * STREAMINFO a nivel de bit).
 */
class FlacTagReaderTest {

    private val reader = FlacTagReader()

    @Test
    fun `lee artista album titulo y genero`() {
        val flac = buildFlac(
            streamInfo = streamInfo(sampleRate = 44100, totalSamples = 44100L * 180), // 3 min
            comments = listOf("ARTIST=Ado", "ALBUM=Kyogen", "TITLE=Odo", "GENRE=J-Pop")
        )
        val tags = reader.read(flac)!!
        assertEquals("Ado", tags.artist)
        assertEquals("Kyogen", tags.album)
        assertEquals("Odo", tags.title)
        assertEquals("J-Pop", tags.genre)
        assertEquals(180_000L, tags.durationMs)
    }

    @Test
    fun `claves case-insensitive y primera gana`() {
        val flac = buildFlac(
            streamInfo = streamInfo(44100, 0),
            comments = listOf("artist=Primero", "ARTIST=Segundo")
        )
        assertEquals("Primero", reader.read(flac)!!.artist)
    }

    @Test
    fun `portada dentro del fragmento se extrae`() {
        val image = ByteArray(500) { (it % 256).toByte() }
        val flac = buildFlac(
            streamInfo = streamInfo(44100, 0),
            comments = listOf("ARTIST=X"),
            picture = image
        )
        val tags = reader.read(flac)!!
        assertTrue(image.contentEquals(tags.pictureData))
        assertNull(tags.pictureRange)
    }

    @Test
    fun `portada fuera del fragmento devuelve rango exacto`() {
        val image = ByteArray(4096) { 1 }
        val full = buildFlac(streamInfo(44100, 0), listOf("ARTIST=X"), picture = image)
        // Cortamos ANTES de que empiecen los bytes de la imagen: el lector debe reportar el rango.
        val truncated = full.copyOfRange(0, full.size - image.size - 100)
        val tags = reader.read(truncated)!!
        assertNull(tags.pictureData)
        assertTrue(tags.pictureRange != null)
    }

    @Test
    fun `no es flac devuelve null`() {
        assertNull(reader.read("ID3".toByteArray()))
        assertNull(reader.read(ByteArray(2)))
    }

    // ---- Constructores de FLAC sintético ----

    private fun buildFlac(streamInfo: ByteArray, comments: List<String>, picture: ByteArray? = null): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray())
        writeBlock(out, type = 0, body = streamInfo, isLast = false)
        writeBlock(out, type = 4, body = vorbisComment(comments), isLast = picture == null)
        if (picture != null) writeBlock(out, type = 6, body = pictureBlock(picture), isLast = true)
        return out.toByteArray()
    }

    private fun writeBlock(out: ByteArrayOutputStream, type: Int, body: ByteArray, isLast: Boolean) {
        val flags = (if (isLast) 0x80 else 0) or type
        out.write(flags)
        out.write((body.size shr 16) and 0xFF)
        out.write((body.size shr 8) and 0xFF)
        out.write(body.size and 0xFF)
        out.write(body)
    }

    private fun streamInfo(sampleRate: Int, totalSamples: Long): ByteArray {
        val b = ByteArray(34)
        // Reproduce el empaquetado a bit del lector: 20 bits de sampleRate repartidos en
        // b[10], b[11] y los 4 bits altos de b[12]; los 4 bits bajos de b[12] son los canales/bps
        // (irrelevantes aquí). b[13] lleva en sus 4 bits BAJOS los 4 bits altos de totalSamples,
        // y b[14..17] los 32 bits restantes.
        b[10] = ((sampleRate shr 12) and 0xFF).toByte()
        b[11] = ((sampleRate shr 4) and 0xFF).toByte()
        b[12] = ((sampleRate and 0x0F) shl 4).toByte()
        b[13] = (((totalSamples shr 32) and 0x0F).toInt()).toByte()
        b[14] = ((totalSamples shr 24) and 0xFF).toByte()
        b[15] = ((totalSamples shr 16) and 0xFF).toByte()
        b[16] = ((totalSamples shr 8) and 0xFF).toByte()
        b[17] = (totalSamples and 0xFF).toByte()
        return b
    }

    private fun vorbisComment(comments: List<String>): ByteArray {
        val out = ByteArrayOutputStream()
        writeUInt32LE(out, 0) // vendor length 0
        writeUInt32LE(out, comments.size)
        for (c in comments) {
            val bytes = c.toByteArray(Charsets.UTF_8)
            writeUInt32LE(out, bytes.size)
            out.write(bytes)
        }
        return out.toByteArray()
    }

    private fun pictureBlock(image: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        writeUInt32BE(out, 3) // picture type = cover front
        val mime = "image/jpeg".toByteArray()
        writeUInt32BE(out, mime.size); out.write(mime)
        writeUInt32BE(out, 0) // descripción vacía
        writeUInt32BE(out, 0); writeUInt32BE(out, 0); writeUInt32BE(out, 0); writeUInt32BE(out, 0) // w,h,depth,colors
        writeUInt32BE(out, image.size); out.write(image)
        return out.toByteArray()
    }

    private fun writeUInt32LE(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF); out.write((v shr 8) and 0xFF); out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF)
    }

    private fun writeUInt32BE(out: ByteArrayOutputStream, v: Int) {
        out.write((v shr 24) and 0xFF); out.write((v shr 16) and 0xFF); out.write((v shr 8) and 0xFF); out.write(v and 0xFF)
    }
}
