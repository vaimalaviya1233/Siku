package com.qhana.siku.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qhana.siku.R
import com.qhana.siku.data.preferences.MusicPreferences
import com.qhana.siku.data.source.LocalMusicSource
import com.qhana.siku.data.source.MusicSourceRegistry
import com.qhana.siku.data.util.SnackbarManager
import com.qhana.siku.worker.DownloadScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Gestión de las fuentes de música (Fase 4). Deliberadamente ligero: lo usan tanto el
 * onboarding de primer arranque como la sección "Fuentes" de Ajustes, y el onboarding no
 * debe instanciar `LibraryViewModel` (paging, restore de sesión, colectores de sync).
 *
 * La sesión de OneDrive NO vive aquí: su fuente de verdad es `AuthViewModel`, que MainActivity
 * comparte con toda la app. Las pantallas reciben ese estado por parámetro.
 */
@HiltViewModel
class SourcesViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val localMusicSource: LocalMusicSource,
    private val sourceRegistry: MusicSourceRegistry,
    private val downloadScheduler: DownloadScheduler,
    private val musicPreferences: MusicPreferences,
    private val snackbarManager: SnackbarManager
) : ViewModel() {

    /**
     * ¿Quedó el onboarding a medias en el paso del tope de descargas? Se lee UNA vez, síncrono
     * (la caché de [MusicPreferences] se llena en su constructor), porque quien lo consulta es
     * el `startDestination` del NavHost: una decisión que se toma en la primera composición y
     * no puede esperar a un flow.
     */
    val storageStepPending: Boolean = musicPreferences.loadOnboardingStoragePending()

    /**
     * Marca que hay una fuente de nube conectada pero el tope aún no se ha decidido. Si el
     * proceso muere ahora (cierre forzado, el sistema mata la app), al reabrir se vuelve a ese
     * paso en vez de entrar a la biblioteca con la decisión pendiente.
     */
    fun markStorageStepPending() = musicPreferences.saveOnboardingStoragePending(true)

    /** El onboarding terminó (o dejó de aplicar): el paso pendiente deja de estar pendiente. */
    fun clearStorageStepPending() = musicPreferences.saveOnboardingStoragePending(false)

    /**
     * Carpetas de música elegidas (tree URIs de SAF). Vacío en modo dispositivo.
     *
     * Sale del DataStore y NO de un `MutableStateFlow` local: este ViewModel se resuelve en dos
     * scopes distintos (el onboarding vive en su propio `NavBackStackEntry`), así que con estado
     * en memoria la instancia que gobierna la navegación no vería lo que configura la otra.
     */
    val localFolderUris: StateFlow<Set<String>> = musicPreferences.localFolderUrisFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, localMusicSource.folderUris())

    /** ¿El modo local activo es "toda la música del dispositivo"? Excluyente con las carpetas. */
    val scanWholeDevice: StateFlow<Boolean> = musicPreferences.scanWholeDeviceFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, localMusicSource.scansWholeDevice())

    /** ¿Hay alguna fuente LOCAL configurada, sea cual sea el modo? */
    val hasLocalSource: StateFlow<Boolean> =
        combine(localFolderUris, scanWholeDevice) { folders, device -> device || folders.isNotEmpty() }
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                localMusicSource.scansWholeDevice() || localMusicSource.folderUris().isNotEmpty()
            )

    /**
     * ¿Hay una fuente local a la que VOLVER si se apaga el escaneo del dispositivo? Son las
     * carpetas seleccionadas o las guardadas al activar el dispositivo (stash). Gobierna si Ajustes
     * deja desactivar "escanear todo": sin nada a lo que volver (y sin nube), apagarlo dejaría la
     * biblioteca vacía y expulsaría al usuario al onboarding, que no debe pasar por un toggle.
     */
    val hasLocalFallback: StateFlow<Boolean> =
        combine(localFolderUris, musicPreferences.localFolderStashFlow) { folders, stash ->
            folders.isNotEmpty() || stash.isNotEmpty()
        }.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            localMusicSource.folderUris().isNotEmpty() || musicPreferences.loadStashedFolderUris().isNotEmpty()
        )

    /**
     * Permiso que exige el escaneo del dispositivo (`READ_MEDIA_AUDIO` en 33+, el de
     * almacenamiento antes). La UI lo pide justo al activar ese modo: es el único que lo necesita.
     */
    val audioPermission: String = localMusicSource.audioPermission()

    fun hasAudioPermission(): Boolean = localMusicSource.hasAudioPermission()

    /**
     * ¿Hay alguna fuente de NUBE configurada? Sale del registro ([MusicSourceRegistry]), no de
     * la sesión de OneDrive en particular: un proveedor cloud futuro cuenta solo. Gobierna las
     * features de descarga en Ajustes (tope de GB) — sin nube no hay nada que descargar.
     * `isConfigured()` es suspend y no reactivo, por eso se refresca bajo demanda
     * ([refreshCloudPresence]) desde la pantalla que lo muestra.
     */
    private val _hasCloudSource = MutableStateFlow(false)
    val hasCloudSource: StateFlow<Boolean> = _hasCloudSource.asStateFlow()

    fun refreshCloudPresence() {
        viewModelScope.launch {
            _hasCloudSource.value = sourceRegistry.activeSources().any { it.type.isCloud }
        }
    }

    /**
     * Carpeta de OneDrive que se escanea. Reactiva por el mismo motivo que [localFolderUris]: se
     * configura desde el onboarding y desde Ajustes, que resuelven instancias distintas del VM.
     */
    val oneDriveFolderPath: StateFlow<String> = musicPreferences.oneDriveFolderPathFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, musicPreferences.loadOneDriveFolderPath())

    /**
     * Cambia la carpeta de OneDrive y relanza el escaneo.
     *
     * Es una operación con pérdida y la UI lo advierte antes: el nuevo escaneo reconcilia contra
     * la carpeta elegida, así que las canciones que quedan fuera se borran de la biblioteca con su
     * historial y sus colores. Guardar la preferencia ya invalida el delta token (lo hace
     * `MusicPreferences`), de modo que el escaneo siguiente es completo y no incremental.
     *
     * @param scanNow false en el ONBOARDING, donde el escaneo lo dispara "Empezar" al final.
     */
    fun setOneDriveFolder(path: String, scanNow: Boolean = true) {
        if (path == musicPreferences.loadOneDriveFolderPath()) return
        musicPreferences.saveOneDriveFolderPath(path)
        if (scanNow) downloadScheduler.scheduleScan(force = true, requiresNetwork = true)
    }

    /**
     * Añade una carpeta a escanear (el permiso de lectura ya lo persistió la UI) y lanza un
     * escaneo. Añadir una carpeta desactiva el escaneo del dispositivo completo: son excluyentes.
     *
     * @param scanNow false en el ONBOARDING: allí el usuario sigue eligiendo fuentes y el scan
     *        único lo dispara "Empezar" con todo ya configurado. El VM no mira la navegación
     *        para saberlo — se lo dice la pantalla, que es la que conoce su propio contexto.
     */
    fun addLocalFolder(uri: String, scanNow: Boolean = true) {
        viewModelScope.launch {
            localMusicSource.addFolder(uri)
            // El snackbar promete "Escaneando…", cierto SOLO cuando de verdad se lanza el scan
            // (Ajustes). En el onboarding (scanNow=false) el escaneo lo dispara "Empezar", así que
            // aquí no se avisa nada: la tarjeta ya muestra la carpeta recién añadida.
            // Sin constraint de red: el escaneo local debe correr aunque el usuario esté offline.
            if (scanNow) {
                snackbarManager.show(context.getString(R.string.local_folder_set))
                downloadScheduler.scheduleScan(force = false, requiresNetwork = false)
            }
        }
    }

    /**
     * Deja de escanear una carpeta y retira de la biblioteca las canciones que solo ella cubría.
     * Los ARCHIVOS no se tocan: siguen en el dispositivo, solo salen del índice de la app.
     */
    fun removeLocalFolder(uri: String) {
        viewModelScope.launch {
            localMusicSource.removeFolder(uri)
            snackbarManager.show(context.getString(R.string.local_folder_removed))
        }
    }

    /**
     * Activa o desactiva el escaneo de TODA la música del dispositivo. Activarlo vacía la lista de
     * carpetas (modos excluyentes) sin borrar canciones: los ids coinciden entre ambos modos, así
     * que lo ya indexado se conserva con sus playlists y su historial.
     *
     * El permiso lo pide la UI ANTES de llamar aquí; sin él, el escaneo no vería nada.
     *
     * @param scanNow false en el onboarding, igual que en [addLocalFolder].
     */
    fun setScanWholeDevice(enabled: Boolean, scanNow: Boolean = true) {
        viewModelScope.launch {
            // Al desactivar, devuelve las carpetas que se restauraron (las que había antes de
            // activar el dispositivo): así el usuario no se queda sin fuente local.
            val restoredFolders = localMusicSource.setWholeDeviceScan(enabled)
            // Igual que en [addLocalFolder]: en el onboarding (scanNow=false) no se escanea nada
            // todavía, así que no se muestra el snackbar de "Escaneando…" que sería mentira.
            if (!scanNow) return@launch

            when {
                enabled -> {
                    snackbarManager.show(context.getString(R.string.local_device_scan_enabled))
                    downloadScheduler.scheduleScan(force = false, requiresNetwork = false)
                }
                // Se restauraron carpetas: hay que reescanear para reconciliar (quitar las
                // canciones que solo cubría el escaneo del dispositivo y ya no caen en las carpetas).
                restoredFolders.isNotEmpty() -> {
                    snackbarManager.show(context.getString(R.string.local_folders_restored))
                    downloadScheduler.scheduleScan(force = true, requiresNetwork = false)
                }
                else -> snackbarManager.show(context.getString(R.string.local_device_scan_disabled))
            }
        }
    }

    /**
     * Vuelve a escanear todas las fuentes configuradas desde cero (equivale al pull-to-refresh):
     * limpia el delta token, hace full scan y reconcilia las bajas.
     *
     * @param requiresNetwork false cuando la única fuente es la local — así el re-escaneo corre
     * aunque el usuario esté offline. Con OneDrive conectado hace falta red.
     */
    fun rescanSources(requiresNetwork: Boolean) {
        downloadScheduler.scheduleScan(force = true, requiresNetwork = requiresNetwork)
        snackbarManager.show(context.getString(R.string.sources_rescan_started))
    }
}
