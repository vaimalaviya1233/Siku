package com.qhana.siku.ui.components

import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qhana.siku.R
import com.qhana.siku.data.lyrics.LyricsSaveOptions
import com.qhana.siku.data.lyrics.SaveBlocker
import com.qhana.siku.data.lyrics.SaveEffect
import com.qhana.siku.data.lyrics.SaveOption
import com.qhana.siku.data.model.LyricsSaveMode

/**
 * Pregunta dónde guardar la letra.
 *
 * Cada opción declara lo que va a pasar ANTES de que el usuario confirme: si se reescribe el
 * archivo, si sube algo a la nube y cuánto pesa. Es información que no se puede dejar para después
 * — reescribir un archivo del usuario o gastarle 40 MB de subida no son cosas que se descubran.
 *
 * Las opciones que no aplican a esta canción se muestran deshabilitadas CON su motivo, en vez de
 * ocultarse: que un WAV no admita letras dentro es justo lo que el usuario necesita saber.
 */
@Composable
fun SaveLyricsDialog(
    options: LyricsSaveOptions,
    onDismiss: () -> Unit,
    onConfirm: (mode: LyricsSaveMode, remember: Boolean) -> Unit
) {
    val firstAvailable = when {
        options.lrc is SaveOption.Available -> LyricsSaveMode.LRC_FILE
        options.embedded is SaveOption.Available -> LyricsSaveMode.EMBEDDED
        else -> null
    }
    var selected by remember { mutableStateOf(firstAvailable) }
    var dontAskAgain by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lyrics_save_title), fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.lyrics_save_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))

                SaveOptionRow(
                    title = stringResource(R.string.lyrics_save_option_lrc),
                    description = stringResource(R.string.lyrics_save_option_lrc_desc),
                    option = options.lrc,
                    uploadBytes = options.uploadBytes,
                    lyricsFolderName = options.lyricsFolderName,
                    isLrc = true,
                    selected = selected == LyricsSaveMode.LRC_FILE,
                    onSelect = { selected = LyricsSaveMode.LRC_FILE }
                )
                Spacer(Modifier.height(8.dp))
                SaveOptionRow(
                    title = stringResource(R.string.lyrics_save_option_embedded),
                    description = stringResource(R.string.lyrics_save_option_embedded_desc),
                    option = options.embedded,
                    uploadBytes = options.uploadBytes,
                    lyricsFolderName = options.lyricsFolderName,
                    isLrc = false,
                    selected = selected == LyricsSaveMode.EMBEDDED,
                    onSelect = { selected = LyricsSaveMode.EMBEDDED }
                )

                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = dontAskAgain,
                            onValueChange = { dontAskAgain = it },
                            role = Role.Checkbox
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = dontAskAgain, onCheckedChange = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.lyrics_save_dont_ask),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { selected?.let { onConfirm(it, dontAskAgain) } },
                enabled = selected != null
            ) {
                Text(stringResource(R.string.lyrics_save_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    )
}

@Composable
private fun SaveOptionRow(
    title: String,
    description: String,
    option: SaveOption,
    uploadBytes: Long,
    lyricsFolderName: String?,
    isLrc: Boolean,
    selected: Boolean,
    onSelect: () -> Unit
) {
    val available = option is SaveOption.Available
    val container =
        if (selected) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHighest

    Surface(
        color = container,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = available, role = Role.RadioButton, onClick = onSelect)
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            RadioButton(selected = selected, onClick = null, enabled = available)
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = titleColorFor(available)
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                when (option) {
                    is SaveOption.Available -> option.effects.forEach { effect ->
                        Text(
                            text = effectText(effect, uploadBytes, isLrc, lyricsFolderName),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                    is SaveOption.Blocked -> Text(
                        text = blockerText(option.reason),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

/** Nombre propio y no `contentColorFor`: ese ya existe en Material 3 con otra semántica. */
@Composable
private fun titleColorFor(enabled: Boolean) =
    if (enabled) MaterialTheme.colorScheme.onSurface
    else MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_CONTENT_ALPHA)

/**
 * El peso solo se nombra cuando se sube el AUDIO: para el `.lrc` decir "se subirán 3 KB" es ruido,
 * y para el audio callarlo sería esconder lo único que de verdad cuesta.
 */
@Composable
private fun effectText(
    effect: SaveEffect,
    uploadBytes: Long,
    isLrc: Boolean,
    lyricsFolderName: String?
): String = when (effect) {
    SaveEffect.REWRITES_FILE -> stringResource(R.string.lyrics_save_warn_rewrites)
    SaveEffect.NEEDS_CLOUD_CONSENT -> stringResource(R.string.lyrics_save_warn_consent)
    SaveEffect.SAVED_TO_LYRICS_FOLDER -> stringResource(
        R.string.lyrics_save_effect_lyrics_folder,
        lyricsFolderName.orEmpty()
    )
    SaveEffect.UPLOADS_TO_CLOUD ->
        if (isLrc || uploadBytes <= 0L) stringResource(R.string.lyrics_save_warn_upload_small)
        else stringResource(
            R.string.lyrics_save_warn_upload,
            Formatter.formatShortFileSize(LocalContext.current, uploadBytes)
        )
}

@Composable
private fun blockerText(reason: SaveBlocker): String = stringResource(
    when (reason) {
        SaveBlocker.FORMAT_UNSUPPORTED -> R.string.lyrics_save_blocked_format
        SaveBlocker.NOT_DOWNLOADED -> R.string.lyrics_save_blocked_not_downloaded
        SaveBlocker.NO_LYRICS_FOLDER -> R.string.lyrics_save_blocked_no_folder
        SaveBlocker.NO_REMOTE_HANDLE -> R.string.lyrics_save_blocked_no_remote
        SaveBlocker.FOLDER_READ_ONLY -> R.string.lyrics_save_blocked_folder_read_only
    }
)

