package com.qhana.siku.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.qhana.siku.R
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade

// ============== CONSTANTES ==============

private val PlaceholderDark = Color(0xFF2A2A2A)
private val PlaceholderLight = Color(0xFFE0E0E0)

// Anillo de progreso (modo cola): traza y holgura entre el aro y la carátula.
private val RingStroke = 3.dp
private val RingGap = 2.dp

// ============== ALBUM ART ==============

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AlbumArt(
    albumArtUri: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 8.dp,
    shape: Shape? = null, // Si se pasa, tiene prioridad sobre cornerRadius
    cacheKey: String? = null,
    // Resolución PEDIDA a Coil. El default (thumbnail de 156px) es correcto para list items
    // chicos, pero pixela en tarjetas grandes (p.ej. carruseles del home de 150dp ≈ 410px):
    // pasar `null` deja que Coil pida el tamaño MEDIDO del composable → nítido sin desperdiciar.
    requestSizePx: Int? = ComponentConfig.ThumbnailSize,
    downloadProgress: Float? = null,
    isDownloading: Boolean = false,
    /**
     * Modo ANILLO: durante la descarga la carátula se deja INTACTA (levemente encogida) y un aro
     * de progreso la rodea, en vez de dibujar el indicador ENCIMA. Desde la metadata ligera la
     * portada existe antes de bajar el audio, y superponerle un icono la ensuciaba. Lo usa la cola
     * de descargas; el resto de la app mantiene el indicador centrado (velo + spinner).
     */
    useRingProgress: Boolean = false
) {
    val isDarkTheme = isSystemInDarkTheme()
    val primaryColor = MaterialTheme.colorScheme.primary
    // Velo sobre la carátula durante la descarga (M3 scrim token, no negro fijo).
    val scrimColor = MaterialTheme.colorScheme.scrim
    val ringTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)

    val isDownloadState = isDownloading ||
        (downloadProgress != null && downloadProgress > 0f && downloadProgress < 1f)
    // En modo anillo, la carátula cede sitio al aro exterior (traza + holgura). Solo mientras
    // descarga; como los ítems de la cola están SIEMPRE descargando, no hay salto visible.
    val ringActive = useRingProgress && isDownloadState && albumArtUri != null
    val artInset = if (ringActive) RingStroke + RingGap else 0.dp

    val colors = remember(isDarkTheme) {
        AlbumArtColors(
            placeholderColor = if (isDarkTheme) PlaceholderDark else PlaceholderLight,
            iconTint = if (isDarkTheme) Color(0xFFB3B3B3) else Color(0xFF666666)
        )
    }

    val density = LocalDensity.current
    val iconSizeSp = remember(size, density) { with(density) { (size / 2).toSp() } }

    // Contenedor transparente del tamaño pedido. La carátula vive en un Box INTERNO que se
    // encoge en modo anillo para dejar sitio al aro; así el footprint total no cambia y el aro
    // no se dibuja sobre la imagen.
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(size - artInset * 2)
                .clip(shape ?: RoundedCornerShape(cornerRadius))
                .background(colors.placeholderColor),
            contentAlignment = Alignment.Center
        ) {
            if (albumArtUri != null) {
                val context = LocalContext.current
                val imageRequest = remember(albumArtUri, cacheKey, requestSizePx) {
                    ImageRequest.Builder(context)
                        .data(albumArtUri)
                        .apply {
                            // requestSizePx != null → tamaño fijo (thumbnails de listas). null → sin
                            // .size(), Coil resuelve al tamaño medido del composable (tarjetas grandes).
                            if (requestSizePx != null) size(requestSizePx)
                            if (cacheKey != null) {
                                memoryCacheKey(cacheKey)
                                diskCacheKey(cacheKey)
                            }
                        }
                        .crossfade(200)
                        .build()
                }

                AsyncImage(
                    model = imageRequest,
                    contentDescription = stringResource(R.string.common_album_art),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }

            when {
                // MODO ANILLO: la carátula queda intacta; el progreso va en el aro exterior (ver
                // abajo). Solo mientras "prepara" (sin progreso aún) se muestra un spinner breve
                // centrado, para no dejar la portada sin ninguna señal de actividad.
                ringActive -> {
                    if (downloadProgress == null || downloadProgress <= 0f) {
                        LoadingIndicator(modifier = Modifier.size(size / 3), color = primaryColor)
                    }
                }
                // Resto de la app: indicador CENTRADO sobre la carátula, con velo para contraste.
                isDownloadState -> {
                    if (albumArtUri != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(scrimColor.copy(alpha = 0.45f))
                        )
                    }
                    // LoadingIndicator expressive (morfea entre MaterialShapes): determinado con
                    // progreso, indeterminado mientras prepara. Mismo lenguaje que el resto de la app.
                    if (downloadProgress != null && downloadProgress > 0f) {
                        LoadingIndicator(
                            progress = { downloadProgress },
                            modifier = Modifier.size(size / 2),
                            color = primaryColor
                        )
                    } else {
                        LoadingIndicator(modifier = Modifier.size(size / 2), color = primaryColor)
                    }
                }
                // Sin descarga y sin carátula: nota musical de relleno.
                albumArtUri == null -> {
                    MaterialSymbol(icon = "music_note", size = iconSizeSp, color = colors.iconTint)
                }
            }
        }

        // ANILLO DE PROGRESO alrededor de la carátula (modo cola). Traza tenue completa + arco
        // primario = progreso, empezando arriba (−90°) en sentido horario. Circular aunque la
        // carátula sea cookie: el aro circunscribe la forma sin tocarla.
        if (ringActive) {
            val stroke = with(density) { RingStroke.toPx() }
            Canvas(modifier = Modifier.size(size)) {
                val inset = stroke / 2f
                val arcSize = androidx.compose.ui.geometry.Size(
                    this.size.width - stroke, this.size.height - stroke
                )
                val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
                drawArc(
                    color = ringTrackColor,
                    startAngle = 0f, sweepAngle = 360f, useCenter = false,
                    topLeft = topLeft, size = arcSize,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round
                    )
                )
                val progress = downloadProgress
                if (progress != null && progress > 0f) {
                    drawArc(
                        color = primaryColor,
                        startAngle = -90f, sweepAngle = 360f * progress.coerceIn(0f, 1f),
                        useCenter = false, topLeft = topLeft, size = arcSize,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                            width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round
                        )
                    )
                }
            }
        }
    }
}

@Immutable
private data class AlbumArtColors(
    val placeholderColor: Color,
    val iconTint: Color
)
