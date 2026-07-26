package com.qhana.siku.ui.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qhana.siku.data.model.AppResult
import com.qhana.siku.data.repository.OneDriveFolderBrowser
import com.qhana.siku.data.repository.RemoteFolder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Navegación por las carpetas de OneDrive para elegir cuál se escanea.
 *
 * La ruta se arma con los NOMBRES de las carpetas visitadas, no con los ids: lo que se persiste es
 * una ruta legible (`Música/FLAC`) que el escaneo convierte en la URL del delta, y que el usuario
 * reconoce al verla en Ajustes. Los ids solo sirven para navegar dentro de esta sesión.
 */
@HiltViewModel
class OneDriveFolderPickerViewModel @Inject constructor(
    private val browser: OneDriveFolderBrowser
) : ViewModel() {

    /** Un nivel de la navegación. `id` null = raíz del drive. */
    @Immutable
    data class Crumb(val id: String?, val name: String)

    @Immutable
    data class State(
        val crumbs: List<Crumb> = listOf(ROOT),
        val folders: List<RemoteFolder> = emptyList(),
        val isLoading: Boolean = false,
        val error: String? = null
    ) {
        /** Ruta relativa a la raíz del drive; vacía en la raíz (= la cuenta entera). */
        val path: String get() = crumbs.drop(1).joinToString("/") { it.name }
        val canGoUp: Boolean get() = crumbs.size > 1
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        load()
    }

    fun open(folder: RemoteFolder) {
        _state.update { it.copy(crumbs = it.crumbs + Crumb(folder.id, folder.name)) }
        load()
    }

    fun goUp() {
        if (!_state.value.canGoUp) return
        _state.update { it.copy(crumbs = it.crumbs.dropLast(1)) }
        load()
    }

    /** Vuelve a un nivel concreto de las migas (tocar "OneDrive" o una carpeta intermedia). */
    fun goTo(index: Int) {
        val crumbs = _state.value.crumbs
        if (index !in crumbs.indices || index == crumbs.lastIndex) return
        _state.update { it.copy(crumbs = crumbs.take(index + 1)) }
        load()
    }

    fun retry() = load()

    private fun load() {
        val parentId = _state.value.crumbs.last().id
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null, folders = emptyList()) }
            when (val result = browser.listFolders(parentId)) {
                is AppResult.Success -> _state.update {
                    // El nivel pudo cambiar mientras cargaba (el usuario siguió navegando): se
                    // descarta el resultado viejo en vez de pintar la carpeta equivocada.
                    if (it.crumbs.last().id != parentId) it
                    else it.copy(folders = result.data, isLoading = false)
                }
                is AppResult.Error -> _state.update {
                    if (it.crumbs.last().id != parentId) it
                    else it.copy(isLoading = false, error = result.error.message)
                }
                // El browser nunca devuelve Loading (es una suspend que ya resolvió), pero la
                // rama existe para que añadir un estado al sealed no pase inadvertido aquí.
                AppResult.Loading -> Unit
            }
        }
    }

    private companion object {
        val ROOT = Crumb(id = null, name = "OneDrive")
    }
}
