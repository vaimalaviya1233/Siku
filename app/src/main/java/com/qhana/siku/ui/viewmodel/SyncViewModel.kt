package com.qhana.siku.ui.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.qhana.siku.R
import com.qhana.siku.data.config.AppConfig.BYTES_PER_GB
import com.qhana.siku.data.coordinator.SyncManager
import com.qhana.siku.data.coordinator.SyncStatus
import com.qhana.siku.data.model.DownloadControlState
import com.qhana.siku.data.preferences.MusicPreferences
import com.qhana.siku.data.repository.IMusicRepository
import com.qhana.siku.worker.DownloadScheduler
import com.qhana.siku.worker.WorkerTags
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.qhana.siku.data.util.WhileUiSubscribed
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SyncViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val workManager: WorkManager,
    private val syncManager: SyncManager,
    private val musicRepository: IMusicRepository,
    private val musicPreferences: MusicPreferences,
    private val downloadScheduler: DownloadScheduler,
    private val sourceRegistry: com.qhana.siku.data.source.MusicSourceRegistry,
    private val snackbarManager: com.qhana.siku.data.util.SnackbarManager
) : ViewModel() {

    // Estado local para seguimiento de reparaciones
    private val _repairState = kotlinx.coroutines.flow.MutableStateFlow<SyncStatus?>(null)

    init {
        // Observar reparaciones en curso para actualizar la UI (Sin Memory Leak)
        viewModelScope.launch {
            workManager.getWorkInfosByTagFlow(WorkerTags.REPAIR_TAG)
                .collect { workInfos ->
                    val activeRepairs = workInfos.filter { !it.state.isFinished }
                    if (activeRepairs.isNotEmpty()) {
                        val progress = activeRepairs.first().progress
                        val status = progress.getString("status") ?: context.getString(R.string.repair_default_status)
                        val pVal = progress.getFloat("progress", 0f)

                        // `Downloading` cuenta ítems (hechos de un total) y el worker reporta una
                        // FRACCIÓN, así que se expresa sobre una escala de porcentaje. El 100 va una
                        // sola vez: escribirlo en los dos campos dejaba el numerador y el
                        // denominador como dos números independientes que solo por costumbre
                        // pertenecían a la misma escala.
                        _repairState.value = SyncStatus.Downloading(
                            current = (pVal * PERCENT_SCALE).toInt(),
                            total = PERCENT_SCALE,
                            failed = 0,
                            currentMessage = context.getString(R.string.repair_in_progress, status)
                        )
                    } else {
                        _repairState.value = null
                    }
                }
        }

        checkAndRepairCorruptFiles()
    }

    /**
     * Verifica si hay canciones marcadas como corruptas en la BD y lanza su reparación.
     * Se llama al inicio y debería llamarse después de un escaneo.
     */
    fun checkAndRepairCorruptFiles() {
        viewModelScope.launch(Dispatchers.IO) {
            val corruptSongs = musicRepository.getCorruptedSongs()
            
            if (corruptSongs.isNotEmpty()) {
                Log.d("SyncViewModel", "Encontrados ${corruptSongs.size} archivos corruptos en BD. Iniciando reparación.")
                
                corruptSongs.forEach { song ->
                    downloadScheduler.scheduleDownload(
                        songId = song.id,
                        forceRedownload = true,
                        isUserInitiated = false // Background repair
                    )
                }
            }
        }
    }

    // Exponer estado combinado: Manager + Reparaciones
    val syncStatus: StateFlow<SyncStatus> = kotlinx.coroutines.flow.combine(
        syncManager.state,
        _repairState
    ) { managerState, repairState ->
        // Si hay una reparación activa, mostramos ese estado sobre el Idle.
        // Si el manager está haciendo algo (Scanning/Downloading/Complete), el manager tiene prioridad
        if (repairState != null && (managerState is SyncStatus.Idle || managerState is SyncStatus.Error)) {
            repairState
        } else {
            managerState
        }
    }.stateIn(
        viewModelScope,
        WhileUiSubscribed,
        SyncStatus.Idle
    )
    
    val activeDownloads = syncManager.activeDownloads

    // Respaldada por BD (v18): la lista sobrevive a la muerte del proceso.
    val failedDownloads = syncManager.failedDownloads.stateIn(
        viewModelScope,
        WhileUiSubscribed,
        emptyList()
    )

    /**
     * Reintento INDIVIDUAL de una fallida: limpia su error/backoff y agenda un
     * SingleSongDownloadWorker (foreground, sobrevive al backgrounding). El worker
     * pasa por SyncManager.downloadSong → la descarga aparece en la pestaña Activas.
     */
    fun retryDownload(songId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            musicRepository.clearDownloadError(songId)
            downloadScheduler.scheduleDownload(songId, isUserInitiated = true)
            // Confirmación inmediata: al limpiar el error la fila DESAPARECE de Fallidas al
            // instante, pero el worker puede tardar unos segundos en asomar por Activas —
            // sin esto el toque parecía tragarse la canción sin hacer nada.
            val title = musicRepository.getSongById(songId).getOrNull()?.title ?: songId
            snackbarManager.show(context.getString(R.string.download_retry_started, title))
        }
    }

    fun retryFailedDownloads() {
        viewModelScope.launch(Dispatchers.IO) {
            // Si no hay un productor activo que consuma los reintentos inline (sync
            // terminado o ya drenando descargas en vuelo), agendamos un ScanWorker: corre
            // con foreground service y, con el error ya limpiado en BD, retoma solo.
            val handledInline = syncManager.retryFailedDownloads()
            if (!handledInline) {
                downloadScheduler.scheduleRetryScan()
            }
        }
    }

    /**
     * Encola un scan de todas las fuentes activas. La red solo es requisito si alguna de ellas
     * es de NUBE: re-escanear una carpeta local es 100% offline y no tiene por qué quedarse
     * esperando conexión (mismo criterio que `LibraryViewModel.onRefresh`). Se deriva acá y no
     * en cada llamador para que ninguno tenga que adivinarlo.
     */
    fun refreshSongs(force: Boolean = true) {
        viewModelScope.launch {
            // Se pide un escaneo nuevo: el fallo del anterior deja de ser noticia y su banner se
            // retira. Sin esto seguiría en pantalla contradiciendo al progreso que está a punto de
            // aparecer, y volvería a salir en cada arranque hasta que un escaneo llegara al final.
            musicPreferences.saveLastSyncFailure(null)
            val needsNetwork = sourceRegistry.activeSources().any { it.type.isCloud }
            downloadScheduler.scheduleScan(force, requiresNetwork = needsNetwork)
        }
    }

    /**
     * Re-lista SOLO las fuentes locales (ver [SyncManager.refreshLocalSources]). Lo dispara la
     * vuelta de la app a primer plano: es cuando el usuario acaba de copiar canciones al teléfono.
     *
     * Directo al SyncManager y NO por `DownloadScheduler`: un worker traería constraints, cola y
     * reintentos para un listado local que dura lo que dura, no toca la red y no debe competir
     * con el scan de verdad.
     */
    fun refreshLocalLibrary() {
        viewModelScope.launch { syncManager.refreshLocalSources() }
    }

    // ==================== Control de descargas (pausa / stop / tope) ====================

    /** Estado de control persistido (ACTIVE/PAUSED/STOPPED). */
    val downloadControlState: StateFlow<DownloadControlState> =
        musicPreferences.downloadControlStateFlow.stateIn(
            viewModelScope, WhileUiSubscribed, DownloadControlState.ACTIVE
        )

    /**
     * Consumo del audio descargado frente al tope, para el gestor de descargas. Reactivo por los
     * DOS lados: Room reemite al entrar o desalojarse archivos, y DataStore al cambiar el tope.
     * Arranca en null (= "aún no se sabe") para que la tarjeta no parpadee un "0 B" inicial.
     */
    val storageUsage: StateFlow<StorageUsage?> =
        combine(
            musicRepository.getTotalDownloadedBytesFlow(),
            musicPreferences.storageLimitBytesFlow
        ) { used, cap -> StorageUsage(usedBytes = used, capBytes = cap) }
            .stateIn(viewModelScope, WhileUiSubscribed, null)

    /** Tope de almacenamiento en GB (0 = sin límite), para el diálogo de opciones. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val storageLimitGb: StateFlow<Float> =
        musicPreferences.storageLimitBytesFlow.mapLatest { it / BYTES_PER_GB }.stateIn(
            viewModelScope, WhileUiSubscribed, 0f
        )

    /**
     * Banner global de descargas cuando NO están activas (pausadas o detenidas) y quedan
     * pendientes. Dos cierres, ambos desde el propio banner:
     * - Cerrar (X): oculta ESTE aviso; reaparece ante la próxima pausa/detención.
     * - "No volver a mostrar": lo silencia para siempre (el estado sigue visible y
     *   controlable desde el Download Manager, que tiene sus botones en la top bar).
     * Se reevalúa al terminar cada sync (por eso combina con syncManager.state).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val downloadBanner: StateFlow<DownloadBannerState?> =
        combine(
            musicPreferences.downloadControlStateFlow,
            musicPreferences.stopBannerDismissedFlow,
            musicPreferences.downloadBannerMutedFlow,
            syncManager.state
        ) { control, dismissed, muted, _ -> Triple(control, dismissed, muted) }
            .mapLatest { (control, dismissed, muted) ->
                val show = control != DownloadControlState.ACTIVE && !dismissed && !muted
                if (!show) return@mapLatest null
                val pending = musicRepository.countSongsNeedingWork()
                if (pending > 0) DownloadBannerState(control, pending) else null
            }
            .stateIn(viewModelScope, WhileUiSubscribed, null)

    // --- Duplicados entre fuentes (v23) ---

    /** Conteo de duplicados esperando decisión del usuario (null = nada que preguntar). */
    val duplicateDecisionNeeded: StateFlow<Int?> = syncManager.duplicateDecisionNeeded

    /** Respuesta del diálogo: persiste la política, fusiona si aplica y reanuda el sync. */
    fun resolveDuplicates(policy: com.qhana.siku.data.model.DuplicatePolicy) =
        syncManager.resolveDuplicateDecision(policy)

    /** "Ahora no": pospone la decisión (el próximo scan vuelve a preguntar). */
    fun dismissDuplicates() = syncManager.dismissDuplicateDecision()

    fun pauseDownloads() = syncManager.pauseDownloads()

    fun resumeDownloads() {
        syncManager.resumeDownloads()
        // Reanudar de verdad relanza el pipeline: agendamos un scan que retome lo pendiente.
        downloadScheduler.scheduleScan(force = false)
    }

    fun stopDownloads() = syncManager.stopDownloads()

    /** Cierra el banner UNA VEZ (sin reanudar): reaparece ante la próxima pausa/detención. */
    fun dismissDownloadBanner() = syncManager.dismissStopBanner()

    /** "No volver a mostrar": silencia el banner para siempre (sin reanudar). */
    fun muteDownloadBanner() = syncManager.muteDownloadBanner()

    /** Fija el tope de descargas en GB (0/negativo = sin límite) y desaloja el excedente. */
    fun setStorageLimitGb(gb: Float) {
        val bytes = if (gb <= 0f) 0L else (gb * BYTES_PER_GB).toLong()
        musicPreferences.saveStorageLimitBytes(bytes)
        viewModelScope.launch(Dispatchers.IO) { syncManager.enforceStorageLimit() }
    }

    private companion object {
        /**
         * Escala sobre la que se expresa un progreso que llega como fracción, para encajarlo en un
         * `SyncStatus.Downloading` que cuenta ítems (hechos / total). No es una preferencia: es la
         * definición de porcentaje, y el numerador y el denominador tienen que salir de aquí los dos
         * o dejarían de pertenecer a la misma escala.
         */
        const val PERCENT_SCALE = 100
    }
}

/** Estado del banner global de descargas (pausadas o detenidas) con pendientes. */
data class DownloadBannerState(val control: DownloadControlState, val pending: Int)

/**
 * Cuánto ocupa el audio descargado de la nube y cuánto permite el tope. Las canciones de fuente
 * LOCAL no cuentan: ya vivían en el dispositivo (ver `SongDao.getTotalDownloadedBytes`).
 */
data class StorageUsage(val usedBytes: Long, val capBytes: Long) {
    /** Sin tope (0) no hay barra que llenar ni restante que calcular: solo el consumo. */
    val hasCap: Boolean get() = capBytes > 0L

    /** Fracción del tope ocupada, acotada a 1: el consumo puede pasarse temporalmente. */
    val fraction: Float
        get() = if (!hasCap) 0f else (usedBytes.toFloat() / capBytes).coerceIn(0f, 1f)

    /**
     * Bytes que faltan para el tope. Negativo si se está POR ENCIMA — ocurre de verdad: al bajar
     * el tope desde Ajustes el desalojo respeta la canción en reproducción, y el sync masivo no
     * desaloja. La UI lo distingue en vez de enseñar un "quedan -2 GB".
     */
    val remainingBytes: Long get() = capBytes - usedBytes
}
