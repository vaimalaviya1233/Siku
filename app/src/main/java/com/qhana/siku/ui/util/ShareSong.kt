package com.qhana.siku.ui.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.qhana.siku.data.model.Song
import java.io.File

/**
 * Comparte la canción en curso. Manda el ARCHIVO cuando hay uno a mano y, si no, solo el texto:
 * son las dos cosas distintas que la gente quiere decir con "compartir" y no hay forma de elegir
 * por ella sin preguntar, así que manda lo que la canción permite.
 *
 * - Descargada de la nube (`file://` dentro de `files/music`): va por [FileProvider], el único
 *   camino para sacar un archivo del almacenamiento privado.
 * - Del dispositivo (`content://`): se reenvía el URI tal cual con permiso de lectura.
 * - En streaming sin descargar: no hay archivo, así que va el texto suelto.
 *
 * Cualquier fallo resolviendo el audio degrada a compartir texto en vez de romper la acción: que
 * el archivo esté fuera del alcance del provider no es motivo para no poder compartir nada.
 */
fun shareSong(context: Context, song: Song, text: String, chooserTitle: String) {
    val audioUri = runCatching { resolveShareableAudio(context, song) }.getOrNull()

    val intent = Intent(Intent.ACTION_SEND).apply {
        if (audioUri != null) {
            type = AUDIO_MIME
            putExtra(Intent.EXTRA_STREAM, audioUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            type = TEXT_MIME
        }
        // El texto viaja SIEMPRE, también con archivo adjunto: quien reciba el audio ve igual de
        // qué canción se trata en apps que muestran el mensaje junto al adjunto.
        putExtra(Intent.EXTRA_TEXT, text)
        putExtra(Intent.EXTRA_SUBJECT, song.title)
    }

    context.startActivity(Intent.createChooser(intent, chooserTitle))
}

private fun resolveShareableAudio(context: Context, song: Song): Uri? {
    val path = song.path
    return when {
        path.startsWith(CONTENT_SCHEME) -> Uri.parse(path)
        path.startsWith(FILE_SCHEME) -> {
            val file = File(path.removePrefix(FILE_SCHEME))
            // getUriForFile lanza si el archivo cae fuera de lo declarado en file_paths.xml; el
            // runCatching de arriba lo convierte en "comparte solo el texto".
            if (file.exists()) {
                FileProvider.getUriForFile(context, "${context.packageName}$PROVIDER_SUFFIX", file)
            } else null
        }
        else -> null
    }
}

private const val CONTENT_SCHEME = "content://"
private const val FILE_SCHEME = "file://"

/** Debe coincidir con `android:authorities` del provider en el manifest. */
private const val PROVIDER_SUFFIX = ".fileprovider"

private const val AUDIO_MIME = "audio/*"
private const val TEXT_MIME = "text/plain"
