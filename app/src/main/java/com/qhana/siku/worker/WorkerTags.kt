package com.qhana.siku.worker

/**
 * Constantes centralizadas para etiquetas y nombres únicos de Workers.
 * Evita el uso de "magic strings" dispersos en la app.
 */
object WorkerTags {
    // Escaneo
    const val SCAN_WORK_NAME = "OneDriveScan"

    // Extracción de colores de carátulas (unique work)
    const val ARTWORK_WORK_NAME = "ArtworkColorExtraction"

    // Reparación
    const val REPAIR_TAG = "repair_corrupt"
    
    // Descargas
    /**
     * Marca TODA descarga individual (auto o iniciada por el usuario). Sirve para dos cosas: el
     * logout cancela por este tag las descargas en vuelo, y el observador de snackbars las escucha.
     */
    const val DOWNLOAD_TRACKING_TAG = "download_tracking"

    /**
     * Marca las descargas AUTOMÁTICAS de fondo (prefetch al reproducir por streaming). El observador
     * de snackbars las OMITE: son best-effort y su fallo no es del usuario —la canción suena igual
     * por streaming—, así que un "Fallo al descargar" ahí solo confunde. Se cancelan en logout como
     * cualquier otra porque además llevan [DOWNLOAD_TRACKING_TAG].
     */
    const val AUTO_DOWNLOAD_TAG = "auto_download"

    // Helper para generar tags dinámicos
    fun downloadTag(songId: String) = "download_$songId"
    fun repairTag(songId: String) = "repair_$songId"
}