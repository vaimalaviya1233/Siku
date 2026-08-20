package com.qhana.siku.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qhana.siku.R
import com.qhana.siku.ui.viewmodel.OneDriveFolderPickerViewModel

/**
 * Devuelve la acción "cambiar la carpeta de OneDrive", ya resueltos la hoja del explorador y —si
 * procede— el aviso de que el cambio borra canciones.
 *
 * Existe como gate reutilizable por el mismo motivo que [rememberDeviceScanActivator]: hay DOS
 * caminos hacia el mismo cambio (el onboarding y Ajustes → Fuentes) y la advertencia no puede
 * depender de que cada pantalla se acuerde de ponerla.
 *
 * @param warnBeforeChange false en el onboarding: aún no hay biblioteca que perder, y advertir de
 *        un borrado que no va a ocurrir solo asusta.
 */
@Composable
fun rememberOneDriveFolderChanger(
    warnBeforeChange: Boolean,
    onApply: (path: String) -> Unit
): () -> Unit {
    var showPicker by remember { mutableStateOf(false) }
    var pendingPath by remember { mutableStateOf<String?>(null) }

    if (showPicker) {
        OneDriveFolderPickerSheet(
            onDismiss = { showPicker = false },
            onConfirm = { path ->
                showPicker = false
                if (warnBeforeChange) pendingPath = path else onApply(path)
            }
        )
    }

    pendingPath?.let { path ->
        AlertDialog(
            onDismissRequest = { pendingPath = null },
            title = { Text(stringResource(R.string.onedrive_folder_change_title)) },
            text = { Text(stringResource(R.string.onedrive_folder_change_body)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingPath = null
                    onApply(path)
                }) { Text(stringResource(R.string.onedrive_folder_change_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingPath = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    return { showPicker = true }
}

/**
 * Explorador de carpetas de OneDrive: el equivalente en la nube al selector SAF de la fuente local.
 *
 * Se puede confirmar en CUALQUIER nivel, incluida la raíz — que significa "escanea toda la cuenta",
 * igual que el modo "todo el dispositivo" de la fuente local. Entrar en una carpeta y elegirla son
 * gestos distintos a propósito: tocar la fila navega, y el botón de abajo confirma dónde estás.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OneDriveFolderPickerSheet(
    onDismiss: () -> Unit,
    onConfirm: (path: String) -> Unit,
    viewModel: OneDriveFolderPickerViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // `skipPartiallyExpanded = true` ya es el default de AppModalSheet (evita el doble-back).
    AppModalSheet(onDismissRequest = onDismiss) {
        // Confirmar la carpeta cierra la hoja desde dentro. Ver [LocalSheetCloser].
        val close = LocalSheetCloser.current
        Column(modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text(
                text = stringResource(R.string.onedrive_folder_title),
                style = MaterialTheme.typography.headlineSmallEmphasized
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.onedrive_folder_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(12.dp))

            // Migas: cada nivel es pulsable para volver de un salto, sin encadenar "atrás".
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                state.crumbs.forEachIndexed { index, crumb ->
                    if (index > 0) {
                        Text(
                            text = " / ",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = crumb.name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (index == state.crumbs.lastIndex) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(enabled = index != state.crumbs.lastIndex) {
                            viewModel.goTo(index)
                        }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Box(modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 360.dp)) {
                when {
                    state.isLoading -> LoadingIndicator(modifier = Modifier.align(Alignment.Center))

                    state.error != null -> Column(modifier = Modifier.align(Alignment.Center)) {
                        Text(
                            text = state.error ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        TextButton(onClick = { viewModel.retry() }) {
                            Text(stringResource(R.string.common_retry))
                        }
                    }

                    state.folders.isEmpty() -> Text(
                        text = stringResource(R.string.onedrive_folder_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center)
                    )

                    else -> LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(state.folders, key = { it.id }) { folder ->
                            Row(
                                modifier = Modifier
                                    .animateItem()
                                    .fillMaxWidth()
                                    .clickable { viewModel.open(folder) }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                MaterialSymbol("folder", size = 24.sp)
                                Spacer(Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = folder.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = pluralStringResource(
                                            R.plurals.onedrive_folder_items,
                                            folder.childCount,
                                            folder.childCount
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                MaterialSymbol("chevron_right", size = 20.sp)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (state.canGoUp) {
                    TextButton(onClick = { viewModel.goUp() }) {
                        Text(stringResource(R.string.onedrive_folder_up))
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Button(
                    onClick = { close { onConfirm(state.path) } },
                    enabled = !state.isLoading,
                    shapes = ButtonDefaults.shapes()
                ) {
                    Text(
                        text = if (state.canGoUp) {
                            stringResource(R.string.onedrive_folder_use, state.crumbs.last().name)
                        } else {
                            stringResource(R.string.onedrive_folder_use_root)
                        }
                    )
                }
            }
        }
    }
}
