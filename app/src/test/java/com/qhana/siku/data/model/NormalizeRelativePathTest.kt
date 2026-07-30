package com.qhana.siku.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * La normalización de rutas decide el ID de una canción local, así que un cambio aquí reindexa
 * bibliotecas enteras. Lo que se fija en estos casos es la propiedad de la que depende todo lo
 * demás: la MISMA canción vista por dos caminos distintos tiene que dar la misma cadena.
 */
class NormalizeRelativePathTest {

    @Test
    fun `colapsa las barras repetidas que deja RELATIVE_PATH`() {
        // MediaStore.RELATIVE_PATH ya termina en '/', así que componerlo con el nombre duplicaba
        // el separador. Caso real leído del log de un dispositivo.
        assertEquals(
            "primary/music/coldrain/final destination/12 8am (album ver.).flac",
            normalizeRelativePath("primary/Music/Coldrain/Final Destination//12 8AM (Album ver.).flac")
        )
    }

    @Test
    fun `el mismo archivo da el mismo id por MediaStore y por SAF`() {
        // Es la promesa del esquema de ids por volumen: cambiar de modo de escaneo no puede
        // duplicar filas ni romper las referencias de las playlists.
        // MediaStore compone volumen + RELATIVE_PATH ("Music/Rock/") + nombre; SAF parte del docId
        // "primary:Music/Rock/song.flac". El volumen ya llega unificado por `canonicalVolume`.
        val fromMediaStore = normalizeRelativePath("primary/Music/Rock//song.flac")
        val fromSaf = normalizeRelativePath("primary/Music/Rock/song.flac")
        assertEquals(fromSaf, fromMediaStore)
    }

    @Test
    fun `unifica separadores, bordes y mayusculas`() {
        assertEquals("music/rock/song.flac", normalizeRelativePath("\\Music\\Rock\\song.flac\\"))
        assertEquals("music/rock/song.flac", normalizeRelativePath("/Music/Rock/song.flac/"))
    }

    @Test
    fun `una ruta ya normalizada no cambia`() {
        val normalized = "primary/music/rock/song.flac"
        assertEquals(normalized, normalizeRelativePath(normalized))
    }

    @Test
    fun `tres o mas barras seguidas tambien colapsan`() {
        assertEquals("a/b", normalizeRelativePath("a///b"))
        assertEquals("", normalizeRelativePath("///"))
    }
}
