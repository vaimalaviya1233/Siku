package com.qhana.siku.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qhana.siku.R
import com.qhana.siku.data.backup.ImportSummary
import com.qhana.siku.data.backup.PlaylistBackupRepository
import com.qhana.siku.data.model.AppError
import com.qhana.siku.data.model.AppResult
import com.qhana.siku.data.util.SnackbarManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Exportar/restaurar playlists contra la carpeta de la app en OneDrive (Fase 6).
 * Ambas operaciones son de red: [isBusy] deshabilita los botones mientras corren.
 */
@HiltViewModel
class BackupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backupRepository: PlaylistBackupRepository,
    private val snackbarManager: SnackbarManager
) : ViewModel() {

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    fun exportPlaylists() = runBusy {
        when (val result = backupRepository.export()) {
            is AppResult.Success ->
                snackbarManager.show(context.getString(R.string.backup_export_success, result.data))
            is AppResult.Error -> snackbarManager.show(messageFor(result.error))
            AppResult.Loading -> Unit
        }
    }

    fun importPlaylists() = runBusy {
        when (val result = backupRepository.import()) {
            is AppResult.Success -> snackbarManager.show(summaryMessage(result.data))
            is AppResult.Error -> snackbarManager.show(messageFor(result.error))
            AppResult.Loading -> Unit
        }
    }

    /**
     * Corre [block] con [isBusy] levantado, y lo baja pase lo que pase.
     *
     * El `finally` no es cosmético: [isBusy] es a la vez el indicador visual Y el guard de
     * reentrada (`if (_isBusy.value) return`), así que una excepción que lo dejara en `true`
     * —y son operaciones de RED, que es donde más se lanza— inutilizaba **exportar e importar
     * a la vez y de forma permanente**, con los dos botones deshabilitados hasta reiniciar la
     * app. Bajarlo en la última línea del `launch` solo funcionaba en el camino feliz.
     */
    private fun runBusy(block: suspend () -> Unit) {
        if (_isBusy.value) return
        viewModelScope.launch {
            _isBusy.value = true
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                snackbarManager.show(e.message ?: e.javaClass.simpleName)
            } finally {
                _isBusy.value = false
            }
        }
    }

    /** Las canciones no encontradas se mencionan solo si las hubo: son la parte accionable. */
    private fun summaryMessage(summary: ImportSummary): String {
        val base = context.getString(
            R.string.backup_import_success,
            summary.songsAdded,
            summary.playlistsCreated
        )
        return if (summary.songsMissing > 0) {
            base + context.getString(R.string.backup_import_missing_suffix, summary.songsMissing)
        } else {
            base
        }
    }

    private fun messageFor(error: AppError): String = when (error) {
        is AppError.NotFound -> context.getString(R.string.backup_none)
        // El scope Files.ReadWrite.AppFolder es nuevo: una sesión guardada de antes no lo tiene y
        // Graph responde 403 hasta que el usuario vuelve a consentir.
        is AppError.Auth -> context.getString(R.string.backup_needs_reconnect)
        else -> context.getString(R.string.backup_error, error.message)
    }
}
