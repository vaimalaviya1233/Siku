package com.qhana.siku.data.util.tags

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Trae un trozo concreto de un archivo remoto con `Range`. Es la pieza que hace barata la lectura
 * de tags: pedir 256 KB en vez de los 28 MB de una canción.
 *
 * **Un servidor puede ignorar el `Range`** y responder 200 con el archivo entero. No es un error:
 * se leen los bytes pedidos y se cierra el body, con lo que TCP corta el envío y el gasto real se
 * queda en lo que hubiera en vuelo. Por eso el bucle de lectura tiene tope propio y no se fía del
 * `Content-Length` de la respuesta.
 */
@Singleton
class HttpRangeFetcher @Inject constructor(
    @Named("download") private val client: OkHttpClient
) {

    /**
     * @return los bytes leídos (puede ser menos de [length] si el archivo es más corto), o `null`
     *         si la petición falló. Nunca lanza: un fallo de red aquí solo significa "esta canción
     *         se queda sin metadata ligera", y el llamador sigue con la siguiente.
     */
    suspend fun fetch(url: String, offset: Long, length: Int): ByteArray? = withContext(Dispatchers.IO) {
        if (length <= 0) return@withContext null
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=$offset-${offset + length - 1}")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Range ${response.code} en $offset+$length")
                    return@withContext null
                }
                val body = response.body ?: return@withContext null
                // Si el servidor ignoró el Range (200 en vez de 206) y además no empezó por el
                // offset pedido, los bytes no son los que creemos: mejor nada que basura.
                if (response.code == 200 && offset > 0) return@withContext null

                val source = body.source()
                val buffer = Buffer()
                var remaining = length.toLong()
                while (remaining > 0) {
                    val read = source.read(buffer, remaining)
                    if (read == -1L) break
                    remaining -= read
                }
                buffer.readByteArray()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Range falló: ${e.message}")
            null
        }
    }

    private companion object {
        const val TAG = "HttpRangeFetcher"
    }
}
