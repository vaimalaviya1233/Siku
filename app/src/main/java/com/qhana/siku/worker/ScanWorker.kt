package com.qhana.siku.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.qhana.siku.MainActivity
import com.qhana.siku.R
import com.qhana.siku.data.coordinator.IncompleteReason
import com.qhana.siku.data.coordinator.SyncManager
import com.qhana.siku.data.coordinator.SyncOutcome
import com.qhana.siku.data.coordinator.SyncStatus
import com.qhana.siku.data.remote.HttpStatus
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.IOException

@HiltWorker
class ScanWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val syncManager: SyncManager,
    private val downloadScheduler: DownloadScheduler,
    private val musicPreferences: com.qhana.siku.data.preferences.MusicPreferences
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "ScanWorker"
        private const val NOTIFICATION_CHANNEL_ID = "sync_channel"
        private const val NOTIFICATION_ID = 2002

        // Suelo para la continuación que reintenta las descargas fallidas: si el backoff más
        // próximo ya venció (o vence en segundos), no tiene sentido re-encolar de inmediato —
        // sería otra corrida que vuelve a fallar por la misma causa que aún no se resolvió.
        private const val MIN_RETRY_CONTINUATION_DELAY_MS = 60_000L

        /**
         * Wakelock que mantiene la CPU despierta durante el sync. El TAG lleva el prefijo del
         * paquete porque es lo que aparece en `dumpsys power` y en el informe de batería del
         * sistema: un wakelock anónimo es imposible de atribuir cuando toca diagnosticar.
         */
        private const val WAKE_LOCK_TAG = "siku:sync"

        /**
         * Plazo del wakelock del sync. Es un SEGURO contra fugarlo si el sync se cuelga sin llegar
         * al `finally`, no una previsión de duración: el caso normal lo suelta mucho antes. Se
         * elige generoso por el mismo criterio que el resto de timeouts defensivos del proyecto
         * (convención 12) — quedarse corto abortaría a mitad una biblioteca grande por una conexión
         * lenta, que es justo el escenario en el que el sync más falta hace, mientras que pasarse
         * solo importa en el caso raro de que además haya un cuelgue.
         *
         * WorkManager, por su parte, da 10 minutos a un worker antes de considerarlo colgado y
         * detenerlo, así que este plazo nunca es lo que decide en la práctica: existe para el hueco
         * entre esa parada y un `finally` que no llegara a ejecutarse.
         */
        private const val WAKE_LOCK_TIMEOUT_MS = 60 * 60 * 1000L

        /**
         * Cadencia máxima con la que se reescribe la notificación del sync. Un segundo es el orden
         * de lo que tarda alguien en leer un contador que cambia; por debajo de eso la diferencia
         * ya no la ve nadie y cada actualización sigue costando una llamada al sistema.
         */
        private const val NOTIFICATION_THROTTLE_MS = 1_000L

        /**
         * **Los dos topes de reintento gobiernan cuánto insiste WorkManager POR SU CUENTA, no si el
         * escaneo llega a hacerse.** Agotarlos no pierde nada: el trabajo se vuelve a encolar en
         * cada arranque y en cada vuelta a primer plano (`MusicPlayerScreen`), además del
         * pull-to-refresh. Rendirse solo significa "no sigo intentándolo en segundo plano; espero a
         * que la app se abra otra vez", que ante un problema persistente es lo correcto.
         *
         * Por eso lo que importa de estos números es la RELACIÓN, no la magnitud: el caso que
         * progresa insiste el doble que el que no. Las magnitudes en sí son una elección — no hay
         * ninguna medida ni referencia externa de la que salgan, y no la hay porque la pregunta
         * ("¿cuántas veces insisto antes de esperar al usuario?") no tiene una respuesta objetiva.
         * Lo que las hace tolerables es que equivocarse por abajo cuesta un reintento que llegará
         * igual al abrir la app, y por arriba, unos minutos de batería.
         */

        /**
         * El escaneo FALLÓ (excepción de red o de Graph) y por tanto **no avanzó nada**: el intento
         * siguiente repite el mismo trabajo desde cero.
         *
         * Con el backoff exponencial desde [DownloadScheduler.BACKOFF_MINUTES] (1 min), los dos
         * reintentos caen al minuto y a los tres. **Bajó de cinco**, que repartidos por ese mismo
         * backoff sumaban un cuarto de hora insistiendo sobre algo que ya había fallado tres veces
         * seguidas: pasados unos minutos, lo que falla no es un bache pasajero. Y desde que
         * rendirse SE VE (ver [recordingFailure]), llegar antes a ese punto es mejor que tardar —
         * el usuario se entera y puede reintentar cuando quiera, en vez de esperar sin saberlo.
         */
        private const val MAX_RETRY_ATTEMPTS = 3

        /**
         * El escaneo se INTERRUMPIÓ por el entorno (batería baja, se cayó la red) habiendo avanzado:
         * lo ya descargado quedó como `file://` en la BD, así que cada reintento retoma donde lo
         * dejó y procesa solo lo que falta.
         *
         * **Se DERIVA del otro, al doble, y esa proporción es lo único que aquí se defiende**: donde
         * cada intento progresa, insistir sirve —una biblioteca grande por una conexión intermitente
         * necesita varias pasadas— mientras que repetir un fallo idéntico no aporta nada. Con un
         * número suelto, esa diferencia dependía de que alguien recordara moverlos juntos.
         *
         * Estaba escrito como un `10` a pelo al lado de un tope ya nombrado, de modo que parecía un
         * descuido lo que en realidad es una distinción deliberada entre fallar y ser interrumpido.
         */
        private const val MAX_INCOMPLETE_ATTEMPTS = 2 * MAX_RETRY_ATTEMPTS

        /**
         * Motivos con los que se registra un escaneo perdido. Son CATEGORÍAS y no el error crudo:
         * lo que se persiste acaba en un banner de la biblioteca, y ahí "Excepción: SocketTimeout…"
         * no le dice nada a nadie. La causa técnica sigue yendo al log y al `workDataOf`.
         */
        const val FAILURE_AUTH = "auth"
        const val FAILURE_NETWORK = "network"
        const val FAILURE_SERVER = "server"
        const val FAILURE_NOT_FOUND = "not_found"
        const val FAILURE_UNKNOWN = "unknown"
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        buildForegroundInfo(
            applicationContext.getString(R.string.notif_syncing_title),
            applicationContext.getString(R.string.notif_checking_changes)
        )

    private fun buildForegroundInfo(title: String, text: String): ForegroundInfo {
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, applicationContext.getString(R.string.notif_channel_sync), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_sync_anim)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()
        // API 34+ exige declarar foregroundServiceType explícitamente al crear el FGS,
        // de lo contrario lanza InvalidForegroundServiceTypeException. El manifest ya
        // declara FOREGROUND_SERVICE_DATA_SYNC permission + SystemForegroundService con
        // foregroundServiceType="dataSync".
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    @OptIn(FlowPreview::class)
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (isStopped) return@withContext Result.success()

        // Promoción a foreground service: mantiene el proceso vivo cuando la app va a
        // background. Sin esto, MIUI/Xiaomi y otros OEMs agresivos matan el sync.
        try {
            setForeground(getForegroundInfo())
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo promover a foreground: ${e.message}")
        }

        // Wakelock PARCIAL mientras dura el sync: con la pantalla apagada el SoC suspende en cuanto
        // nada lo retiene, y ahí las descargas se estancan hasta que el watchdog (60s) las mata en
        // cascada. Ser un foreground service NO evita eso — impide que te maten, no que la CPU se
        // duerma—, así que este es el mecanismo que de verdad cubre el caso.
        //
        // Aquí había un `WifiLock` con `WIFI_MODE_FULL_HIGH_PERF` que NO hacía ese trabajo, y su
        // comentario afirmaba que sí. Según la propia documentación de la plataforma (SDK 36,
        // WifiManager): HIGH_PERF está deprecado y "is automatically replaced with
        // WIFI_MODE_FULL_LOW_LATENCY with all the restrictions documented on that lock", y ese lock
        // "is only active when the screen is on" y "when the acquiring app is running in the
        // foreground" — o sea que se apagaba exactamente en el escenario para el que se puso. Y en
        // el escenario en que sí actuaba, la misma doc advierte de que "battery life may be
        // reduced" y "throughput may be reduced": pagaba batería para ir más lento.
        //
        // El plazo del `acquire` es una RED DE SEGURIDAD contra fugarlo (un cuelgue del sync
        // dejaría el teléfono sin dormir), no una estimación de lo que tarda: generoso a propósito,
        // como el resto de timeouts defensivos del proyecto. Quien manda en la duración real es el
        // `release()` del finally.
        val wakeLock: PowerManager.WakeLock? = try {
            val powerManager = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo adquirir el wakelock del sync: ${e.message}")
            null
        }

        // Observador paralelo del state del sync para actualizar la notificación con
        // progreso real. Se cancela al terminar para no fugar el scope.
        val notificationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val notificationJob: Job = notificationScope.launch {
            val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            // MUESTREADO: el estado avanza una vez por canción terminada y con hasta 32 descargas
            // en paralelo eso son varias por segundo, cada una un `notify()` que cruza binder y
            // hace que el sistema reconstruya la notificación. Nadie lee "17/500" contra "19/500",
            // así que la cadencia la marca lo que el usuario puede leer, no lo que el sync produce.
            syncManager.state.sample(NOTIFICATION_THROTTLE_MS).collectLatest { state ->
                val (title, text) = when (state) {
                    is SyncStatus.Scanning -> applicationContext.getString(R.string.notif_syncing_title) to
                        (if (state.found > 0) applicationContext.getString(R.string.notif_changes_detected, state.found) else applicationContext.getString(R.string.notif_checking_changes))
                    is SyncStatus.Downloading -> applicationContext.getString(R.string.notif_downloading) to
                        applicationContext.getString(
                            R.string.notif_download_progress,
                            state.current,
                            state.total,
                            if (state.failed > 0) applicationContext.getString(R.string.sync_failed_suffix, state.failed) else ""
                        )
                    // Las fases de preparación llevan su propio nombre también aquí: la
                    // notificación es lo único visible con la app en segundo plano, y es donde
                    // más caro sale que un minuto de trabajo real parezca un cuelgue.
                    is SyncStatus.Preparing ->
                        state.message to (
                            if (state.total > 0)
                                applicationContext.getString(R.string.sync_progress, state.current, state.total, "")
                            else applicationContext.getString(R.string.notif_checking_changes)
                            )
                    // La espera de red también se cuenta en la notificación: el foreground
                    // service sigue vivo durante ella, así que sin esto la barra se queda
                    // anunciando una descarga que ya no avanza.
                    is SyncStatus.Paused ->
                        applicationContext.getString(R.string.sync_paused) to state.message
                    is SyncStatus.Complete,
                    is SyncStatus.Error,
                    SyncStatus.Idle -> return@collectLatest
                }
                val notification = buildForegroundInfo(title, text).notification
                try {
                    notificationManager.notify(NOTIFICATION_ID, notification)
                } catch (e: Exception) {
                    Log.w(TAG, "Error actualizando notificación: ${e.message}")
                }
            }
        }

        try {
            val forceRefresh = inputData.getBoolean("force_refresh", false)
            when (val outcome = syncManager.startSync(forceRefresh)) {
                SyncOutcome.Skipped -> Result.success()
                is SyncOutcome.Completed -> {
                    // Quedaron fallidas en backoff: programamos la continuación para cuando
                    // venza la más próxima (cola persistente v18). Sin esto, solo se
                    // reintentarían en la próxima apertura de la app.
                    outcome.nextRetryAt?.let { retryAt ->
                        val delayMs = (retryAt - System.currentTimeMillis())
                            .coerceAtLeast(MIN_RETRY_CONTINUATION_DELAY_MS)
                        Log.d(TAG, "Fallidas en backoff: continuación en ${delayMs / 1000}s")
                        downloadScheduler.scheduleRetryScan(initialDelayMs = delayMs)
                    }
                    // Terminó: se borra el fallo anterior, si lo había. Un escaneo que llega hasta
                    // el final es la única prueba de que el problema se resolvió.
                    musicPreferences.saveLastSyncFailure(null)
                    Result.success()
                }
                is SyncOutcome.Incomplete -> when (outcome.reason) {
                    // Logout/release: no hay nada que reanudar.
                    IncompleteReason.CANCELLED -> Result.success()
                    // Hay red pero no WiFi: el scan ya corrió; encadenamos una continuación
                    // que espera red no medida en vez de reintentar en caliente por datos.
                    IncompleteReason.NO_WIFI -> {
                        Log.d(TAG, "Sin WiFi para descargas: programando continuación UNMETERED")
                        downloadScheduler.scheduleScanContinuationOnWifi()
                        Result.success()
                    }
                    // Batería/red: WorkManager reanuda con backoff + constraints. El progreso
                    // es incremental (lo descargado queda file:// en BD), así que reintentar
                    // solo procesa lo pendiente.
                    IncompleteReason.LOW_BATTERY,
                    IncompleteReason.NETWORK_LOST -> {
                        Log.w(TAG, "Sync incompleto (${outcome.reason}), solicitando retry")
                        if (runAttemptCount < MAX_INCOMPLETE_ATTEMPTS) Result.retry()
                        else Result.failure(workDataOf("error" to outcome.reason.name))
                            .recordingFailure(outcome.reason.name)
                    }
                }
                is SyncOutcome.Failed -> classifyFailure(outcome)
            }
        } finally {
            notificationJob.cancel()
            notificationScope.cancel()
            // `isHeld` porque el acquire lleva plazo: si venció, soltarlo lanzaría.
            try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        }
    }

    /**
     * Deja constancia de que el trabajo se dio por perdido, para que la biblioteca pueda decirlo.
     *
     * Rendirse en silencio era el agujero: tras agotar los reintentos, `Result.failure` solo dejaba
     * un `workDataOf` que no lee nadie, así que el usuario se quedaba con la biblioteca a medias y
     * sin ninguna señal de que hubo un problema — a diferencia de una descarga fallida, que sí
     * aparece listada. Los reintentos siguen absorbiendo los baches pasajeros; lo que cambia es que
     * ahora AGOTARLOS se ve.
     */
    private fun Result.recordingFailure(reason: String): Result {
        musicPreferences.saveLastSyncFailure(reason)
        return this
    }

    /**
     * Traduce un fallo del sync a la decisión de WorkManager. Reproduce la clasificación
     * que antes vivía en catch-blocks muertos (startSync tragaba las excepciones y este
     * worker nunca las veía).
     */
    private fun classifyFailure(outcome: SyncOutcome.Failed): Result {
        val cause = outcome.cause
        return when {
            outcome.isAuthError -> {
                Log.e(TAG, "Auth error, not retrying: ${outcome.message}")
                Result.failure(workDataOf("error" to FAILURE_AUTH)).recordingFailure(FAILURE_AUTH)
            }
            // Los predicados salen de [HttpStatus] en vez de una lista de códigos escrita aquí:
            // aquélla era CERRADA (`408, 429, 500, 502, 503, 504`), así que cualquier otro 5xx —un
            // 507, o los 52x que mete un proxy delante— caía en el `else` y se daba por fallo
            // definitivo sin un solo reintento, siendo exactamente la clase de error que sí pasa
            // sola. Enumerar los 5xx que existen no se puede; preguntar por la familia, sí.
            cause is HttpException -> when {
                HttpStatus.isAuthFailure(cause.code()) ->
                    Result.failure(workDataOf("error" to FAILURE_AUTH)).recordingFailure(FAILURE_AUTH)
                cause.code() == HttpStatus.NOT_FOUND ->
                    Result.failure(workDataOf("error" to FAILURE_NOT_FOUND))
                        .recordingFailure(FAILURE_NOT_FOUND)
                // Throttle incluido: aquí "reintentar" es reprogramar el worker con su backoff de
                // minutos, no volver de inmediato, así que un 429 también merece otra pasada.
                HttpStatus.isRetriableTransport(cause.code()) ||
                    cause.code() == HttpStatus.TOO_MANY_REQUESTS -> {
                    Log.w(TAG, "Temporary error ${cause.code()}, will retry")
                    if (runAttemptCount < MAX_RETRY_ATTEMPTS) Result.retry()
                    else Result.failure(workDataOf("error" to FAILURE_NETWORK))
                        .recordingFailure(FAILURE_NETWORK)
                }
                else -> Result.failure(workDataOf("error" to "http_${cause.code()}"))
                    .recordingFailure(FAILURE_SERVER)
            }
            cause is IOException -> {
                Log.w(TAG, "Network error, will retry: ${cause.message}")
                if (runAttemptCount < MAX_RETRY_ATTEMPTS) Result.retry()
                else Result.failure(workDataOf("error" to FAILURE_NETWORK))
                    .recordingFailure(FAILURE_NETWORK)
            }
            else -> {
                Log.e(TAG, "Unexpected error, not retrying: ${outcome.message}", cause)
                Result.failure(workDataOf("error" to (cause?.javaClass?.simpleName ?: outcome.message)))
                    .recordingFailure(FAILURE_UNKNOWN)
            }
        }
    }
}
