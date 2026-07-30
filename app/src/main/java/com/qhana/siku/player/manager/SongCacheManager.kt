package com.qhana.siku.player.manager

import android.util.LruCache
import com.qhana.siku.data.model.Song
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SongCacheManager @Inject constructor() {
    // LRU Cache: Thread-safe (LruCache synchronizes internally on get/put).
    private val songCache = object : LruCache<String, Song>(500) {}

    /**
     * Lookup síncrono SOLO en memoria. Retorna null si no hay hit. Útil en listeners
     * no-suspend (p.ej. `Player.Listener.onMediaItemTransition`).
     */
    fun getSongSync(id: String): Song? = songCache.get(id)

    fun cacheSong(song: Song) {
        updateCaches(song)
    }

    fun cacheSongs(songs: List<Song>) {
        songs.forEach { updateCaches(it) }
    }

    /**
     * Guarda [song] con su path TAL CUAL, sin la fusión de [selectBestPath].
     *
     * Es para quien SABE que el origen de la canción cambió: la re-descarga forzada
     * (`switchCurrentToStreaming`) y la reparación de un error de reproducción borran el archivo
     * local, y la fusión —que siempre prefiere un `file://` ya conocido— resucitaba el path de un
     * archivo BORRADO. El síntoma era una canción que se anunciaba como descargada (y con la que
     * "descargar" no hacía nada) hasta que el flow de la BD volvía a emitir.
     *
     * La decisión no puede tomarla el caché: desde aquí "path vacío" y "path que ya no existe" son
     * indistinguibles de una fila incompleta, y por eso la fusión sigue siendo el comportamiento
     * por defecto.
     */
    fun cacheSongLocation(song: Song) {
        synchronized(songCache) {
            songCache.put(song.id, song)
        }
    }

    private fun updateCaches(song: Song) {
        // Synchronized compound read-then-write via LruCache's internal lock
        synchronized(songCache) {
            val existing = songCache.get(song.id)
            val finalPath = if (existing != null) {
                selectBestPath(song.path, existing.path)
            } else {
                song.path
            }
            songCache.put(song.id, song.copy(path = finalPath))
        }
    }

    private fun selectBestPath(newPath: String, existingPath: String?): String {
        return when {
            newPath.startsWith("file://") -> newPath
            existingPath?.startsWith("file://") == true -> existingPath
            existingPath?.isNotEmpty() == true -> existingPath
            else -> newPath
        }
    }
}
