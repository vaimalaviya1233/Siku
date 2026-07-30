package com.qhana.siku.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qhana.siku.data.auth.AuthManager
import com.qhana.siku.data.auth.AuthResult
import com.qhana.siku.data.model.SourceType
import com.qhana.siku.data.preferences.MusicPreferences
import com.qhana.siku.worker.WorkerTags
import com.qhana.siku.data.repository.ArtworkRepository
import com.qhana.siku.data.repository.IMusicRepository
import com.qhana.siku.player.MusicController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import androidx.work.WorkManager

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authManager: AuthManager,
    private val repository: dagger.Lazy<IMusicRepository>,
    private val artworkRepository: dagger.Lazy<ArtworkRepository>,
    private val musicController: dagger.Lazy<MusicController>,
    private val syncManager: dagger.Lazy<com.qhana.siku.data.coordinator.SyncManager>,
    private val workManager: dagger.Lazy<WorkManager>,
    private val musicPreferences: MusicPreferences
) : ViewModel() {

    /**
     * ¿Se resolvió ya la sesión al arrancar? Es lo ÚNICO que este ViewModel sabe y `AuthManager`
     * no: la diferencia entre "no hay sesión" y "todavía no lo sé".
     */
    private val _sessionResolved = MutableStateFlow(false)

    /**
     * `null` = sesión aún sin resolver (MSAL inicializando/leyendo la cuenta de disco).
     * MainActivity retiene el splash mientras valga null, para no mostrar el onboarding un
     * instante y navegar a la biblioteca después (flash al reabrir).
     *
     * Se DERIVA de `AuthManager.hasSession`, que es el dueño del hecho. Antes era un
     * `MutableStateFlow` propio escrito en tres sitios (restaurar, entrar, salir), en paralelo a
     * la copia que mantenía `SourcesViewModel`: dos estados que representaban lo mismo y que solo
     * coincidían mientras nadie olvidara actualizar uno de ellos.
     */
    val isLoggedIn: StateFlow<Boolean?> =
        combine(_sessionResolved, authManager.hasSession) { resolved, hasSession ->
            if (resolved) hasSession else null
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        checkExistingSession()
    }

    private fun checkExistingSession() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // tryRestoreSession suspende hasta que MSAL esté listo (con timeout propio:
                // AuthManager.MSAL_INIT_TIMEOUT_MS, que es el techo real del splash) y siembra
                // `hasSession` con el resultado. Aquí solo hace falta marcar que ya se resolvió.
                authManager.tryRestoreSession()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Un fallo al restaurar significa exactamente "no hay sesión" — que es el valor
                // con el que `hasSession` ya arranca—, y es recuperable: el usuario entra desde
                // Ajustes. Lo que NO puede pasar es quedarse sin resolver.
                Log.w(TAG, "No se pudo restaurar la sesión: ${e.message}")
            } finally {
                // En el `finally`: mientras esto no se marque, `isLoggedIn` vale null y el splash
                // del sistema sigue en pantalla (MainActivity.setKeepOnScreenCondition). Si MSAL
                // lanza —cuenta corrupta, config inválida, error del broker— y no se marcara, la
                // app se quedaría colgada en el splash sin pantalla que mostrar ni forma de salir.
                _sessionResolved.value = true
                _isLoading.value = false
            }
        }
    }

    fun signIn(activity: android.app.Activity) {
        viewModelScope.launch {
            _isLoading.value = true
            // El error es de ESTE intento: se limpia al empezar uno nuevo. Sin esto, un fallo
            // (red, cancelación de MSAL) dejaba el banner de error para siempre — el reintento
            // podía tener éxito y el usuario seguía viendo el mensaje viejo bajo las tarjetas.
            _error.value = null
            // El `finally` es la única garantía de que el spinner se apaga: si `signIn` LANZA en
            // vez de emitir un `AuthResult.Error` —o si la corrutina se cancela—, apagarlo en
            // cada rama del `when` no sirve de nada y el botón de entrar se queda girando para
            // siempre, sin error visible y sin forma de reintentar.
            try {
                authManager.signIn(activity).collect { result ->
                    // El éxito NO se anota aquí: `AuthManager` ya publica la sesión nueva en
                    // `hasSession`, de donde sale `isLoggedIn`. Este colector solo se ocupa del
                    // error, que sí es de este intento y de nadie más.
                    when (result) {
                        is AuthResult.Error -> _error.value = result.message
                        is AuthResult.Success, AuthResult.Cancelled -> Unit
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.message ?: e.javaClass.simpleName
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Cierra la sesión de OneDrive. Desconecta SOLO esa fuente: borra sus canciones (y el audio
     * descargado en disco) pero conserva la música local, las playlists y los favoritos — con
     * varias fuentes, un logout ya no puede ser un borrado total de la biblioteca.
     *
     * Las playlists conservan las referencias a las canciones de OneDrive (no hay CASCADE desde
     * `songs`), así que al reconectar la cuenta se rellenan solas: los ids son portables.
     */
    fun logout() {
        viewModelScope.launch {
            authManager.signOut().collect { success ->
                if (success) {
                    // La sesión ya la dio de baja `AuthManager` en su propio callback; aquí solo
                    // queda la limpieza de datos que depende de ella.
                    // Un error de un login anterior no describe el estado actual: sin sesión no
                    // hay nada que hubiera fallado.
                    _error.value = null
                    // Accedemos a las dependencias Lazy solo aquí, cuando son necesarias.
                    syncManager.get().release() // cancela sync/descargas en curso antes de borrar datos
                    // El delta token sobrevive al borrado (vive en DataStore): sin limpiarlo, al
                    // reconectar la cuenta el primer scan sería incremental sobre una tabla vacía
                    // y la biblioteca quedaría en blanco hasta un pull-to-refresh manual.
                    musicPreferences.clearDeltaToken()
                    repository.get().clearSourceData(SourceType.ONEDRIVE)
                    artworkRepository.get().clearCache()
                    // Purgar de la cola TODOS los temas de OneDrive (no solo el actual): ya no
                    // existen en la BD y dejarlos solo difiere el fallo a cuando les llegue el
                    // turno. La música local sigue sonando; si sonaba OneDrive, la cola queda
                    // en pausa sobre el siguiente tema local (o parada si no queda nada).
                    musicController.get().purgeSource(SourceType.ONEDRIVE)
                    // Cancelar todo el trabajo pendiente con el token que va a morir: scan,
                    // descargas individuales en vuelo y la extracción de color de carátulas.
                    val wm = workManager.get()
                    wm.cancelUniqueWork(WorkerTags.SCAN_WORK_NAME)
                    wm.cancelAllWorkByTag(WorkerTags.DOWNLOAD_TRACKING_TAG)
                    wm.cancelUniqueWork(WorkerTags.ARTWORK_WORK_NAME)
                }
            }
        }
    }

    private companion object {
        const val TAG = "AuthViewModel"
    }
}