package com.qhana.siku.ui.model

import androidx.compose.runtime.Immutable
import com.qhana.siku.data.model.Song
import com.qhana.siku.ui.components.formatTime

/**
 * Modelo optimizado para la UI.
 * Anotado con @Immutable para garantizar que Compose salte recomposiciones si los datos no cambian.
 * Solo contiene lo necesario para pintar la celda.
 */
@Immutable
data class SongUiModel(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationText: String,
    val imageUrl: String?,
    val isDownloaded: Boolean,
    /**
     * ¿La canción viene de una fuente en la nube? Gobierna el indicador de estado del archivo
     * (descargada / se transmitirá): una canción del propio dispositivo no tiene ese estado.
     * Por `sourceType`, NO por `isLocalAudio`: una de nube ya descargada es `file://` y SÍ
     * conserva el indicador.
     */
    val isCloud: Boolean,
    val isActive: Boolean = false // Si está sonando o seleccionada
)

/**
 * Extension para convertir Song a SongUiModel
 */
fun Song.toUiModel(isActive: Boolean = false): SongUiModel {
    // Formatear duración aquí para no hacerlo en cada frame. El formato sale de `formatTime`, que
    // es el mismo que usan el reproductor y las listas: aquí vivía una segunda copia del cálculo.
    val durationFormatted = formatTime(duration)

    return SongUiModel(
        id = id,
        title = title,
        artist = artist,
        album = album,
        durationText = durationFormatted,
        imageUrl = albumArtUriString,
        isDownloaded = isLocalAudio,
        isCloud = sourceType.isCloud,
        isActive = isActive
    )
}
