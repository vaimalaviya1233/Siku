package com.qhana.siku.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.qhana.siku.data.repository.ArtworkRepository
import com.qhana.siku.data.repository.IMusicRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@HiltWorker
class ArtworkWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val musicRepository: IMusicRepository,
    private val artworkRepository: ArtworkRepository
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "ArtworkWorker"
        private const val PREFS_NAME = "artwork_worker_prefs"
        private const val KEY_LAST_PROCESSED_ID = "last_processed_song_id"
        private const val BATCH_SIZE = 10

        /**
         * Tope de reintentos. Sin él, una excepción DETERMINISTA (BD corrupta, disco lleno) se
         * reintentaba para siempre con backoff: el trabajo nunca se completa y nunca se rinde.
         * Es un backfill cosmético —los colores se re-extraen solos en la próxima corrida—, así
         * que rendirse es preferible a insistir eternamente.
         */
        private const val MAX_RUN_ATTEMPTS = 5

        const val PROGRESS_CURRENT = "progress_current"
        const val PROGRESS_TOTAL = "progress_total"
    }

    private val prefs by lazy { applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val allSongs = musicRepository.getSongsWithoutColors()
            if (allSongs.isEmpty()) { prefs.edit().clear().apply(); return@withContext Result.success() }
            val total = allSongs.size
            val lastId = prefs.getString(KEY_LAST_PROCESSED_ID, null)
            val cursorIdx = if (!lastId.isNullOrBlank()) allSongs.indexOfFirst { it.id == lastId } else -1
            val songs = if (cursorIdx >= 0 && cursorIdx < allSongs.size - 1) {
                allSongs.subList(cursorIdx + 1, allSongs.size)
            } else if (cursorIdx >= 0) {
                emptyList()
            } else allSongs
            // El progreso se DERIVA del cursor en vez de persistir un contador aparte: como
            // `allSongs` encoge en cada corrida (las que ya tienen color salen de la consulta),
            // un contador acumulado entre corridas podía superar al total —tras un retry, el
            // cursor ya no está en la lista, se reprocesa desde el principio y el acumulado
            // seguía sumando—. Con esto, `count <= total` se cumple por construcción.
            var count = if (cursorIdx >= 0) cursorIdx + 1 else 0
            if (songs.isEmpty()) { prefs.edit().clear().apply(); return@withContext Result.success() }

            songs.chunked(BATCH_SIZE).forEach { chunk ->
                if (isStopped) return@withContext Result.success()
                val batch = mutableListOf<Triple<String, Int, Int>>()
                var lastIdInBatch: String? = null
                chunk.forEach { song ->
                    try {
                        // Solo se persisten extracciones REALES. Sin artwork o con fallo de
                        // extracción, las columnas quedan NULL y la canción se reintenta en la
                        // próxima corrida (cuando el artwork exista). Persistir un color
                        // fallback aquí lo volvía indistinguible de un resultado legítimo.
                        val uri = song.albumArtUriString
                        val colors = if (uri != null) artworkRepository.extractColorsOptimized(song.id, uri) else null
                        if (colors != null) {
                            batch.add(Triple(song.id, colors.primary, colors.secondary))
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error extracting colors for song ${song.id}", e)
                    }
                    lastIdInBatch = song.id
                    count++
                }
                if (batch.isNotEmpty()) musicRepository.saveColorsBatch(batch)
                lastIdInBatch?.let { lb ->
                    prefs.edit().putString(KEY_LAST_PROCESSED_ID, lb).apply()
                }
                setProgress(workDataOf(PROGRESS_CURRENT to count.coerceAtMost(total), PROGRESS_TOTAL to total))
            }
            prefs.edit().clear().apply()
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount >= MAX_RUN_ATTEMPTS) {
                Log.w(TAG, "Backfill de colores abandonado tras $runAttemptCount intentos", e)
                Result.failure()
            } else {
                Result.retry()
            }
        }
    }
}