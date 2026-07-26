package com.qhana.siku.data.lyrics

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Escribe la letra DENTRO del archivo de audio, en sus tags (JAudioTagger).
 *
 * Se guarda el texto tal cual, con sus marcas de tiempo si las tiene: no existe un campo estándar
 * para letra sincronizada que los reproductores lean de verdad —`SYLT` de ID3 es binario y casi
 * nadie lo implementa—, así que la convención de facto (Poweramp, MusicBee, foobar) es meter el
 * LRC crudo en el campo de letra normal. Quien no entienda los timestamps muestra el texto igual.
 *
 * ## Por qué tanto ceremonial
 *
 * Esto reescribe un archivo del usuario que puede no tener copia en ninguna parte. Hay dos rutas y
 * solo una es segura por construcción:
 *
 * - **[writeToFile]** (sandbox de la app): se trabaja sobre una copia y se cierra con un
 *   `renameTo`, que dentro del mismo filesystem es ATÓMICO. Un corte deja el archivo viejo intacto
 *   o el nuevo entero, nunca un híbrido.
 * - **[writeToDocument]** (`content://`): no hay rename atómico posible hacia el destino;
 *   `openOutputStream(uri, "wt")` TRUNCA y luego escribe. Un corte a mitad destruye la canción.
 *   Por eso se deja un diario en disco antes de tocar el destino: si el proceso muere, el arranque
 *   siguiente encuentra la copia buena y termina el volcado (ver [RetagJournal]).
 */
@Singleton
class EmbeddedLyricsWriter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val journal: RetagJournal
) {

    init {
        // JAudioTagger es muy verboso por java.util.logging (mismo trato que en ReplayGainReader).
        runCatching { Logger.getLogger(JAUDIOTAGGER_LOGGER).level = Level.SEVERE }
    }

    /** ¿Este contenedor admite letra embebida? WAV no: RIFF no tiene un campo que nadie lea. */
    fun supports(fileName: String): Boolean =
        fileName.substringAfterLast('.', "").lowercase() in SUPPORTED_EXTENSIONS

    /**
     * Reescribe [file] con la letra dentro. El archivo original solo se sustituye cuando la copia
     * nueva ya se releyó y contiene la letra: si algo falla antes, el original ni se toca.
     */
    fun writeToFile(file: File, lyrics: String) {
        val staging = File(file.parentFile, "${file.name}$STAGING_SUFFIX")
        try {
            file.copyTo(staging, overwrite = true)
            tagInPlace(staging, lyrics)
            check(staging.renameTo(file)) { "No se pudo reemplazar ${file.name}" }
        } finally {
            if (staging.exists()) staging.delete()
        }
    }

    /**
     * Reescribe el archivo detrás de un `content://` (SAF o MediaStore).
     *
     * @param fileName nombre real del documento — hace falta para que JAudioTagger sepa qué
     *        contenedor está leyendo, porque un `content://` no lleva extensión.
     */
    fun writeToDocument(uri: Uri, fileName: String, lyrics: String) {
        val work = journal.newWorkFile(fileName)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                work.outputStream().use { input.copyTo(it) }
            } ?: error("No se pudo abrir el archivo original")

            tagInPlace(work, lyrics)

            // Desde aquí el destino queda en riesgo: se anota ANTES de truncarlo nada, para que
            // una muerte del proceso a mitad del volcado sea recuperable y no una canción perdida.
            journal.record(uri, work)
            journal.flush(uri, work)
            journal.clear(work)
        } catch (e: Exception) {
            // El diario NO se borra si el fallo ocurrió durante el volcado: lo resuelve la
            // recuperación del arranque. Solo se limpia lo que aún no llegó a tocar el destino.
            if (!journal.isRecorded(work)) work.delete()
            throw e
        }
    }

    /**
     * Escribe el tag sobre [file] y verifica releyéndolo. La verificación no es paranoia: si el
     * contenedor no admite el campo, JAudioTagger puede no lanzar y dejar el archivo sin la letra,
     * y sin este control se reemplazaría el original por una copia que no aporta nada.
     */
    private fun tagInPlace(file: File, lyrics: String) {
        val audioFile = AudioFileIO.read(file)
        // `tagOrCreateAndSetDefault` ya deja el tag asignado al AudioFile: no hay que re-asignarlo.
        audioFile.tagOrCreateAndSetDefault.setField(FieldKey.LYRICS, lyrics)
        AudioFileIO.write(audioFile)

        val written = runCatching { AudioFileIO.read(file).tag?.getFirst(FieldKey.LYRICS) }.getOrNull()
        check(!written.isNullOrBlank()) { "El archivo no conservó la letra al escribirla" }
    }

    private companion object {
        const val JAUDIOTAGGER_LOGGER = "org.jaudiotagger"
        const val STAGING_SUFFIX = ".retag"

        /**
         * Contenedores con un campo de letra que los reproductores leen: Vorbis comment (FLAC,
         * OGG, Opus), `USLT` de ID3 (MP3) y el átomo `©lyr` (M4A). WAV queda fuera a propósito.
         */
        val SUPPORTED_EXTENSIONS = setOf("flac", "mp3", "m4a", "ogg", "opus")
    }
}
