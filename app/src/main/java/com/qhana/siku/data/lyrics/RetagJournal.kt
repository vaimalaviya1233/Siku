package com.qhana.siku.data.lyrics

import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Diario de reescrituras de archivos del usuario.
 *
 * Existe por un detalle de scoped storage con dientes: a un `content://` no se le puede hacer un
 * rename atómico. `openOutputStream(uri, "wt")` **trunca primero y escribe después**, así que una
 * muerte del proceso a mitad de camino deja la canción del usuario partida — y si esa canción
 * estaba solo en su teléfono, no hay de dónde recuperarla.
 *
 * La solución es anotar en disco, ANTES de tocar el destino, dónde está la copia buena y completa.
 * Si el volcado no llega a terminar, el arranque siguiente lo encuentra y lo repite: volcar es
 * idempotente, así que repetirlo siempre es seguro, tanto si el destino quedó a medias como si ya
 * estaba bien.
 *
 * Los temporales viven en `filesDir`, NO en `cacheDir`: el sistema vacía la caché cuando le hace
 * falta espacio, y aquí eso sería tirar la única copia buena de una canción.
 *
 * No confundir con los `.tmp` de [com.qhana.siku.data.manager.MusicDownloader]: aquellos son
 * descargas a medias y se BORRAN, porque el original sigue en la nube. Estos se RESTAURAN.
 */
@Singleton
class RetagJournal @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val dir: File get() = File(context.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }

    /** Copia de trabajo donde se escribe el tag antes de tocar nada del usuario. */
    fun newWorkFile(fileName: String): File {
        val extension = fileName.substringAfterLast('.', "")
        val suffix = if (extension.isEmpty()) "" else ".$extension"
        return File(dir, "$WORK_PREFIX${System.currentTimeMillis()}$suffix")
    }

    /** Deja constancia de que [work] debe acabar en [uri]. A partir de aquí el destino es recuperable. */
    fun record(uri: Uri, work: File) {
        journalOf(work).writeText(uri.toString())
    }

    fun isRecorded(work: File): Boolean = journalOf(work).exists()

    /** Vuelca la copia buena sobre el destino. Idempotente: repetirlo deja el mismo resultado. */
    fun flush(uri: Uri, work: File) {
        context.contentResolver.openOutputStream(uri, WRITE_TRUNCATE)?.use { output ->
            work.inputStream().use { it.copyTo(output) }
        } ?: error("No se pudo abrir el archivo para escritura")
    }

    /** Cierra la operación: ya no hay nada que recuperar. */
    fun clear(work: File) {
        journalOf(work).delete()
        work.delete()
    }

    /**
     * Termina las reescrituras que quedaron a medias. Se llama al arrancar, antes de que nada
     * pueda reproducir esos archivos.
     *
     * @return cuántas se completaron.
     */
    fun recoverPending(): Int {
        val journals = dir.listFiles { file -> file.name.endsWith(JOURNAL_SUFFIX) } ?: return 0
        var recovered = 0
        for (journalFile in journals) {
            val work = File(journalFile.path.removeSuffix(JOURNAL_SUFFIX))
            if (!work.exists() || work.length() == 0L) {
                // Sin copia buena no hay nada que restaurar; el destino quedó como quedó.
                Log.w(TAG, "Diario huérfano sin copia de trabajo: ${journalFile.name}")
                journalFile.delete()
                continue
            }
            val uri = runCatching { Uri.parse(journalFile.readText()) }.getOrNull()
            if (uri == null) {
                journalFile.delete()
                continue
            }
            try {
                flush(uri, work)
                clear(work)
                recovered++
                Log.i(TAG, "Reescritura completada tras un cierre inesperado: $uri")
            } catch (e: Exception) {
                // El permiso pudo revocarse o el archivo ya no existe. Se deja el diario: mientras
                // esté, la copia buena sigue a salvo y se reintentará en el próximo arranque.
                Log.w(TAG, "No se pudo completar la reescritura de $uri: ${e.message}")
            }
        }
        return recovered
    }

    private fun journalOf(work: File) = File("${work.path}$JOURNAL_SUFFIX")

    private companion object {
        const val TAG = "RetagJournal"
        const val DIR_NAME = "lyrics-retag"
        const val WORK_PREFIX = "retag-"
        const val JOURNAL_SUFFIX = ".journal"
        /** "wt" = truncar antes de escribir; sin la `t`, un archivo más corto dejaría cola basura. */
        const val WRITE_TRUNCATE = "wt"
    }
}
