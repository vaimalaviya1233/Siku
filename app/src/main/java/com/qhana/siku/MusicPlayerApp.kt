package com.qhana.siku

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import javax.inject.Inject
import com.qhana.siku.worker.ArtworkWorker
import com.qhana.siku.worker.WorkerTags
import androidx.work.Constraints
import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import com.qhana.siku.data.coordinator.ArtworkHealingManager
import com.qhana.siku.data.lyrics.RetagJournal
import com.qhana.siku.data.repository.IPlaylistRepository
import androidx.glance.appwidget.updateAll
import com.qhana.siku.data.util.AppLogger
import com.qhana.siku.widget.PlayerWidget
import com.qhana.siku.widget.QueueWidget
import com.qhana.siku.widget.WidgetBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application class con Coil 3 para carga de imágenes.
 * Implementa SingletonImageLoader.Factory (Coil 3) en vez del antiguo ImageLoaderFactory.
 */
@HiltAndroidApp
class MusicPlayerApp : Application(), Configuration.Provider, SingletonImageLoader.Factory {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var appLogger: AppLogger
    @Inject lateinit var imageLoader: ImageLoader
    @Inject lateinit var playlistRepository: IPlaylistRepository
    @Inject lateinit var artworkHealingManager: ArtworkHealingManager
    @Inject lateinit var widgetBridge: WidgetBridge
    @Inject lateinit var retagJournal: RetagJournal

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var lastNightMode = -1

    override fun onCreate() {
        super.onCreate()

        lastNightMode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK

        // Puente reproductor → widgets de pantalla de inicio: vive todo el proceso.
        widgetBridge.start(appScope)

        // Garantizar que la playlist de favoritos exista (idempotente).
        appScope.launch { playlistRepository.ensureFavoritesPlaylist() }

        // Reescrituras de archivos que quedaron a medias (guardar la letra dentro de una canción
        // local). Va PRIMERO y antes de cualquier reproducción: si el proceso murió durante el
        // volcado, el archivo del usuario está partido y la única copia buena es la de trabajo.
        appScope.launch {
            val recovered = retagJournal.recoverPending()
            if (recovered > 0) appLogger.lifecycle("Reescrituras de letras recuperadas: $recovered")
        }

        // Healing de carátulas huérfanas: tras "limpiar caché" del sistema (o cualquier
        // pérdida del directorio de covers), re-extrae del audio local o limpia el URI.
        // No bloquea el arranque; corre en background al iniciar el proceso.
        appScope.launch { artworkHealingManager.heal() }

        // Programar worker de colores en background con restricciones de batería
        // Solo ejecutar cuando: batería OK, dispositivo idle (para no interferir con uso activo)
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .setRequiresDeviceIdle(true) // Solo cuando el dispositivo está inactivo
            .build()

        val request = OneTimeWorkRequestBuilder<ArtworkWorker>()
            .setConstraints(constraints)
            .setInitialDelay(30, java.util.concurrent.TimeUnit.SECONDS) // Esperar 30s después de inicio
            .build()

        WorkManager.getInstance(this).enqueueUniqueWork(
            WorkerTags.ARTWORK_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    // Los widgets hornean sus colores al traducirse a RemoteViews: sin este re-render,
    // el cambio claro↔oscuro del sistema no se refleja hasta el siguiente cambio de canción.
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val nightMode = newConfig.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        if (nightMode != lastNightMode) {
            lastNightMode = nightMode
            appScope.launch {
                runCatching { PlayerWidget().updateAll(this@MusicPlayerApp) }
                runCatching { QueueWidget().updateAll(this@MusicPlayerApp) }
            }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    // Usar el ImageLoader inyectado (configurado en AppModule)
    override fun newImageLoader(context: Context): ImageLoader = imageLoader

}