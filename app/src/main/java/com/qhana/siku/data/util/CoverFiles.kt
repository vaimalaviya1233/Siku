package com.qhana.siku.data.util

import java.security.MessageDigest

/**
 * Nombre del archivo de carátula, derivado de SU CONTENIDO (`<sha1 de los bytes>.jpg`).
 *
 * Antes se derivaba del id de la canción que la extrajo, y de ahí salieron tres bugs seguidos:
 * el id local ES una ruta, así que `covers/$songId.jpg` apuntaba a subdirectorios inexistentes y
 * la escritura fallaba en silencio; sanear los `/` no era inyectivo, así que dos canciones podían
 * pisarse; y como una misma portada se comparte entre las canciones de un álbum, retirar a la que
 * le daba nombre borraba el archivo de todas.
 *
 * Con el contenido como nombre esos tres desaparecen por construcción: no hay caracteres que
 * sanear, no hay colisiones (bytes distintos ⇒ nombre distinto), y las doce canciones de un disco
 * convergen SOLAS al mismo archivo, que además se escribe una sola vez. Renombrar una canción —la
 * migración de ids locales— tampoco tiene ya nada que ver con su portada.
 *
 * SHA-1 y no un hash criptográfico serio a propósito: aquí solo identifica contenido, no protege
 * nada, y es notablemente más rápido sobre imágenes de varios MB.
 */
fun coverFileName(artData: ByteArray): String =
    MessageDigest.getInstance("SHA-1")
        .digest(artData)
        .joinToString("") { "%02x".format(it) } + ".jpg"

// Las carátulas que guardaron versiones anteriores (`<songId>.jpg`) NO necesitan ninguna función
// aquí: sus filas las referencian por URI, que es lo único que consulta el borrado, y las que
// queden sin referencia las barre la poda del sync. Nada que migrar ni que retirar luego.
