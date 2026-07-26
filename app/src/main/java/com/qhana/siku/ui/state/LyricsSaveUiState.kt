package com.qhana.siku.ui.state

import android.content.IntentSender
import androidx.compose.runtime.Immutable
import com.qhana.siku.data.lyrics.LyricsSaveOptions

/**
 * Estado de la acción "guardar letra" de la pantalla de letras.
 *
 * Los dos permisos que pueden faltar se representan por separado porque se resuelven de formas
 * distintas: el de OneDrive con una pantalla de Microsoft y el del sistema con un `IntentSender`
 * que solo la Activity puede lanzar.
 */
@Immutable
data class LyricsSaveUiState(
    /** Diálogo abierto (con lo que se le puede ofrecer a la canción actual) o `null` si cerrado. */
    val options: LyricsSaveOptions? = null,
    val isSaving: Boolean = false,
    /** Falta el permiso de escritura en OneDrive. */
    val needsCloudConsent: Boolean = false,
    /** MediaStore en API 30+: hay que pedirle al usuario permiso sobre este archivo concreto. */
    val pendingPermission: IntentSender? = null
)
