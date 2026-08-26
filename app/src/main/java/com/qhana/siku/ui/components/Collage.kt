package com.qhana.siku.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import com.qhana.siku.ui.theme.AppColors

/**
 * Cuántas carátulas usa como MUCHO [AdaptiveCollage]. Vive aquí, junto al componente que define ese
 * tope, para que quien prepara la lista no tenga que adivinarlo: pasarle más solo descarta trabajo
 * ya hecho, y las dos fuentes que lo alimentan (el inicio y las favoritas) cortaban con un 4 escrito
 * a mano cada una.
 */
const val COLLAGE_MAX_TILES = 4

/**
 * Collage ADAPTATIVO de hasta 4 carátulas DISTINTAS: 1 a sangre, 2 en mitades verticales, 3 con
 * una grande + dos apiladas, 4 en mosaico 2×2.
 *
 * **Nunca rellena huecos repitiendo una imagen**: se lee como un error de la app. Por eso el
 * número de celdas se adapta a lo que hay en vez de exigir cuatro — el Home lo exigía, y cuando
 * las carátulas pasaron a identificarse por su contenido (todas las canciones de un álbum comparten
 * archivo, ver `coverFileName`) el `distinct()` empezó a devolver de verdad una sola imagen en
 * bibliotecas de pocos discos, así que la tarjeta caía siempre al ícono de relleno. Antes "salían
 * cuatro" solo porque la misma portada tenía cuatro URIs distintos.
 *
 * Con la lista vacía no dibuja nada: el placeholder es cosa de cada pantalla (un icono de
 * favoritos, uno de tipo de contexto…), así que lo decide el caller.
 */
@Composable
fun AdaptiveCollage(arts: List<String>, modifier: Modifier = Modifier) {
    Box(modifier) {
        when (arts.size) {
            0 -> Unit
            1 -> CollageTile(arts[0], Modifier.matchParentSize())
            2 -> Row(modifier = Modifier.matchParentSize()) {
                CollageTile(arts[0], Modifier.weight(1f))
                CollageTile(arts[1], Modifier.weight(1f))
            }
            3 -> Row(modifier = Modifier.matchParentSize()) {
                CollageTile(arts[0], Modifier.weight(1f))
                Column(modifier = Modifier.weight(1f)) {
                    CollageTile(arts[1], Modifier.weight(1f).fillMaxWidth())
                    CollageTile(arts[2], Modifier.weight(1f).fillMaxWidth())
                }
            }
            else -> Column(modifier = Modifier.matchParentSize()) {
                Row(modifier = Modifier.weight(1f)) {
                    CollageTile(arts[0], Modifier.weight(1f))
                    CollageTile(arts[1], Modifier.weight(1f))
                }
                Row(modifier = Modifier.weight(1f)) {
                    CollageTile(arts[2], Modifier.weight(1f))
                    CollageTile(arts[3], Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * Celda del collage. El fondo tonal evita el hueco blanco mientras la imagen carga.
 *
 * Sin receiver de scope a propósito: se la llama tanto desde el [Box] raíz (con `matchParentSize`)
 * como desde los `Row`/`Column` internos (con `weight`), y el modifier lo resuelve el sitio de la
 * llamada.
 */
@Composable
private fun CollageTile(art: String, modifier: Modifier = Modifier) {
    AsyncImage(
        model = art,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .fillMaxHeight()
            .background(AppColors.surfaceContainerHighest)
    )
}
