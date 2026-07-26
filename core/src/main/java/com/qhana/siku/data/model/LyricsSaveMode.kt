package com.qhana.siku.data.model

/**
 * Dónde guarda el usuario las letras cuando pulsa "guardar" en la pantalla de letras.
 *
 * [ASK] es el valor inicial: se pregunta cada vez hasta que el usuario marca "no volver a
 * preguntar" en el diálogo, que fija una de las otras dos. Desde Ajustes se puede cambiar la
 * elección o volver a [ASK] — arrepentirse no debe exigir reinstalar nada.
 */
enum class LyricsSaveMode {
    /** Preguntar en cada guardado (por defecto). */
    ASK,

    /** Archivo `.lrc` junto a la canción. No modifica el audio: es la opción sin riesgo. */
    LRC_FILE,

    /** Dentro del propio archivo de audio, en sus tags. Portátil, pero lo reescribe. */
    EMBEDDED
}
