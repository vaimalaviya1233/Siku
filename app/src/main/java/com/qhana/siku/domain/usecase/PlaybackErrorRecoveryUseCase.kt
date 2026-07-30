package com.qhana.siku.domain.usecase

import android.content.Context
import com.qhana.siku.R
import com.qhana.siku.data.config.AppConfig
import com.qhana.siku.data.model.PlaybackErrorInfo
import com.qhana.siku.data.model.PlaybackErrorType
import com.qhana.siku.data.model.Song
import com.qhana.siku.data.model.SourceType
import com.qhana.siku.data.repository.IMusicRepository
import com.qhana.siku.player.PlaybackCoordinator
import com.qhana.siku.worker.DownloadScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

class PlaybackErrorRecoveryUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: IMusicRepository,
    private val playbackCoordinator: PlaybackCoordinator,
    private val downloadScheduler: DownloadScheduler
) {

    sealed class Result {
        data class Retry(val song: Song) : Result()
        data class Skip(val reason: String) : Result()
        data class Healing(val message: String) : Result()
        object Ignore : Result()
    }

    /**
     * Decide cómo recuperarse de un error de reproducción basado en el código de Media3,
     * no en strings del mensaje (que son frágiles entre versiones).
     */
    suspend operator fun invoke(
        song: Song,
        error: PlaybackErrorInfo,
        currentRetryCount: Int,
        maxRetries: Int
    ): Result {
        // Validar que la canción tenga remoteId para intentar healing.
        val targetSong = if (song.remoteId == null) {
            val freshSong = repository.getSongById(song.id).getOrNull()
            when {
                freshSong?.remoteId != null -> freshSong
                // LOCAL no tiene copia en la nube: no hay healing posible, pero el mensaje
                // debe hablar del ARCHIVO (movido/borrado de la carpeta, permiso SAF caído),
                // no de "sincronización" — eso es vocabulario de la nube.
                (freshSong ?: song).sourceType == SourceType.LOCAL ->
                    return Result.Skip(context.getString(R.string.error_local_file_unavailable))
                else -> return Result.Skip(context.getString(R.string.error_song_not_synced))
            }
        } else song

        val type = error.type

        // La corrupción la declara el DECODER, y nadie más. Antes esta condición mezclaba dos
        // cosas distintas —"el decoder no puede con esto" y "se me acabaron los reintentos"— y
        // ambas terminaban marcando la canción y BORRANDO su audio: un simple corte de red
        // bastaba para condenar una canción sana, con daño que sobrevive en la BD. Es el mismo
        // principio que ya se aplicó al timeout de recuperación (que solo salta, nunca marca).
        // FILE_NOT_FOUND tampoco es corrupción: el archivo no está, y eso se arregla
        // re-descargando — el camino de healing de más abajo.
        if (type == PlaybackErrorType.DECODER) {
            // Archivo local íntegro que el decoder rechaza: el problema es el formato o el
            // hardware, no los bytes. Se salta sin destruir nada.
            if (localFileIsComplete(targetSong)) {
                return Result.Skip(context.getString(R.string.error_hardware_skipping))
            }
            markAsCorrupted(targetSong)
            return Result.Skip(context.getString(R.string.error_incompatible_skipping))
        }

        // Reintentos agotados: se salta, sin diagnóstico permanente. Que un tema no arranque
        // ahora no dice nada sobre el archivo.
        if (currentRetryCount > maxRetries) {
            return Result.Skip(context.getString(R.string.error_playback_skipping))
        }

        // El resto (red, desconocidos y archivo ausente) se intenta sanar.
        if (type != PlaybackErrorType.NETWORK &&
            type != PlaybackErrorType.UNKNOWN &&
            type != PlaybackErrorType.FILE_NOT_FOUND
        ) {
            return Result.Ignore
        }

        // Si la canción ya está descargada localmente y el archivo se ve íntegro,
        // el error NO es de red/URL — es un bug de orquestación (cola vacía, race, etc.).
        // No tiene sentido borrarla y re-descargarla; simplemente ignoramos y dejamos
        // que el siguiente playAt() la recargue normalmente. El chequeo era `length() > 0`,
        // que da por bueno un archivo a medio bajar (un EOF prematuro deja bytes válidos):
        // esos SÍ deben caer al healing de abajo, y por eso se compara contra el tamaño real.
        if (targetSong.path.startsWith("file://") && localFileIsComplete(targetSong)) {
            return Result.Ignore
        }

        // Intento de healing: limpiar archivo local incompleto + refrescar URL
        cleanupLocalFile(targetSong)

        // Descarga prioritaria en background (fire & forget). Sin customWorkName: el unique
        // name por defecto es repairTag(songId), único POR CANCIÓN. El viejo nombre global
        // compartido (AUTO_PRIORITY_DOWNLOAD_NAME + REPLACE) hacía que dos errores seguidos
        // en canciones distintas cancelaran el healing de la primera.
        downloadScheduler.scheduleDownload(
            targetSong.id,
            forceRedownload = true
        )

        return try {
            val refreshedSong = playbackCoordinator.refreshSongUrl(targetSong)
            if (refreshedSong != null) Result.Retry(refreshedSong)
            else Result.Skip(context.getString(R.string.error_connection_skipping))
        } catch (e: Exception) {
            Result.Skip(context.getString(R.string.error_url_skipping))
        }
    }

    /**
     * true si el audio de [song] está en disco con todos sus bytes, según el ÚNICO criterio de
     * integridad de la app ([AppConfig.looksTruncated]) — el mismo que usa el downloader para
     * descartar restos truncados. Aquí vivía una copia del umbral (`abs(len - size) < 1024`)
     * que además exigía coincidencia casi exacta, así que un archivo legítimamente más grande
     * (tags de ReplayGain/carátula reescritos tras descargar) contaba como incompleto y se
     * borraba para re-descargarlo.
     *
     * Con tamaño esperado desconocido no se puede afirmar que falten bytes, y ante la duda se
     * considera completo: la alternativa es destruir el archivo por una sospecha sin evidencia.
     */
    private fun localFileIsComplete(song: Song): Boolean {
        val file = File(song.path.removePrefix("file://"))
        if (!file.exists() || file.length() <= 0L) return false
        return !AppConfig.looksTruncated(file.length(), song.size)
    }

    private suspend fun markAsCorrupted(song: Song) {
        repository.markSongAsCorrupted(song.id)
        repository.deleteAudioFileById(song.id)
        playbackCoordinator.clearCacheForSong(song)
    }

    private suspend fun cleanupLocalFile(song: Song) {
        repository.deleteAudioFileById(song.id)
        playbackCoordinator.clearCacheForSong(song)
    }
}
