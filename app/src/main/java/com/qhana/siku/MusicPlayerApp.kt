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
 * Retraso inicial del backfill de colores: margen para que la primera pantalla —biblioteca,
 * restauración de sesión, escaneo local— tenga la CPU y el disco para ella sola.
 *
 * `ArtworkWorker` recorre las canciones que TIENEN carátula pero aún no tienen color guardado, y por
 * cada una decodifica la imagen y corre el pipeline de cuantización. Es trabajo de CPU y disco sobre
 * la biblioteca entera, así que arrancarlo pegado al inicio del proceso compite con lo que el
 * usuario está esperando ver.
 *
 * **El valor no es crítico, y eso es a propósito**: quien de verdad decide si es buen momento es el
 * propio worker, que se aparta y deja que WorkManager lo reprograme si encuentra un sync en marcha
 * (ver `ArtworkWorker`). Este plazo solo cubre el tramo en que la UI se está componiendo, que es
 * corto y no tiene señal observable desde `Application`; el competidor grande —el escaneo, que dura
 * minutos— se esquiva por condición, no por reloj.
 *
 * De hecho, treinta segundos **protegía del competidor equivocado**: a esa altura la UI hace rato
 * que arrancó y lo que está ocupando disco y BD es el `ScanWorker` que la propia pantalla encoló.
 * Diez segundos siguen dando varias veces el arranque en frío típico, y el trabajo es PRESCINDIBLE
 * de todos modos: el color de una canción se extrae solo al reproducirla (caché RAM → BD → extraer),
 * así que esto no habilita nada, únicamente lo precalcula para que el acento no parpadee.
 */
private const val ARTWORK_BACKFILL_DELAY_SECONDS = 10L

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

        // Worker de colores en background, con restricción de batería pero SIN exigir dispositivo
        // inactivo. `setRequiresDeviceIdle` suena prudente y en la práctica es una condición que
        // puede no cumplirse en días: pide Doze de verdad, no "pantalla apagada", así que en un
        // teléfono de uso frecuente el backfill de colores se quedaba pendiente indefinidamente y
        // la biblioteca sin acento. Con "batería no baja" el trabajo llega a correr y sigue sin
        // pelearse por CPU cuando el usuario la necesita: es un lote acotado y WorkManager ya lo
        // programa en un momento oportuno.
        //
        // El ENCOLADO va FUERA del hilo principal, y no por prudencia genérica: el initializer automático de
        // WorkManager está REMOVIDO en el manifest (inicialización on-demand para Hilt), así que
        // esta es la PRIMERA llamada a `getInstance` del proceso — la que construye su base de
        // datos Room, sus executors y reprograma el trabajo pendiente. Hecha aquí, ese arranque se
        // pagaba entero en `Application.onCreate`, o sea antes del primer frame de un arranque en
        // frío. En IO, cuando `provideWorkManager` la pida al crear los ViewModels de la primera
        // composición, ya está lista. El trabajo en sí no corre antes ni después: lleva su propio
        // retardo inicial de [ARTWORK_BACKFILL_DELAY_SECONDS].
        appScope.launch {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()

            val request = OneTimeWorkRequestBuilder<ArtworkWorker>()
                .setConstraints(constraints)
                .setInitialDelay(ARTWORK_BACKFILL_DELAY_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(this@MusicPlayerApp).enqueueUniqueWork(
                WorkerTags.ARTWORK_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
        }
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