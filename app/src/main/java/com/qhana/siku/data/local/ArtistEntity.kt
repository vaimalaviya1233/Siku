package com.qhana.siku.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Cache de metadatos de artista (foto de Deezer + selección manual).
 *
 * La PK es el string EXACTO de `songs.artist` (case-sensitive): es la clave de join
 * con la tabla de canciones — no normalizar.
 */
@Entity(tableName = "artists")
data class ArtistEntity(
    @PrimaryKey val name: String,
    val deezerId: Long? = null,
    /** Foto grande para headers (picture_xl ?: picture_big de Deezer). */
    val imageUrl: String? = null,
    /** Miniatura para listas (picture_medium). */
    val thumbUrl: String? = null,
    /** true = elegido por el usuario en el picker; el auto-match nunca lo pisa. */
    val manuallySet: Boolean = false,
    /**
     * Epoch ms del último intento de fetch. Con imageUrl == null actúa como cache de
     * not-found, con el TTL que dicta [notFoundAttempts].
     *
     * `null` significa **nunca se llegó a preguntar a Deezer**, y esa distinción es
     * load-bearing: un fallo de red NO lo escribe, así que sigue contando como pendiente
     * de primera búsqueda (ver `ArtistDao.countArtistsNeverAttempted`).
     */
    val fetchedAt: Long? = null,
    /**
     * Búsquedas consecutivas en Deezer que no devolvieron nada. 0 = resuelto, manual, o
     * aún sin preguntar. Cada fallo DUPLICA la espera hasta el siguiente reintento, de
     * modo que un artista que Deezer sencillamente no tiene (banda local, autoeditado)
     * deja de re-consultarse cada 14 días para siempre. Sin esto la lista de "pendientes"
     * nunca converge a vacía y el backfill repite el mismo lote inútil de por vida.
     */
    val notFoundAttempts: Int = 0
)
