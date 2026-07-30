package com.qhana.siku.data.remote

import android.util.Log
import com.qhana.siku.data.auth.AuthManager
import com.qhana.siku.data.auth.AuthResult
import dagger.Lazy
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Intercepta respuestas 401 de la API Microsoft Graph: refresca el token silenciosamente
 * vía MSAL y reintenta la request una sola vez con el nuevo token.
 *
 * Requisitos:
 * - La request original debe llevar un header `Authorization` (de lo contrario el interceptor no hace nada).
 * - Solo se reintenta UNA vez; se marca con `X-Retry-Auth: 1` para evitar loops.
 *
 * Nota: usa `dagger.Lazy<AuthManager>` para romper cualquier dependencia circular potencial
 * entre Hilt → OkHttp → Interceptor → AuthManager. `runBlocking` es aceptable aquí porque
 * los interceptors de OkHttp ya son bloqueantes por diseño.
 */
@Singleton
class AuthRefreshInterceptor @Inject constructor(
    private val authManagerProvider: Lazy<AuthManager>
) : Interceptor {

    companion object {
        private const val TAG = "AuthRefreshInterceptor"
        private const val HEADER_RETRY_MARKER = "X-Retry-Auth"

        /**
         * Las rutas de la carpeta de la app (backup de playlists) necesitan el scope de escritura.
         * Refrescar con los scopes de lectura devolvería un token válido pero sin permiso: el
         * reintento moriría en 403.
         */
        private const val APP_FOLDER_SEGMENT = "approot"

        /**
         * Marca que una request necesita el scope de escritura sobre el drive (guardar letras
         * junto a la canción). No se puede deducir de la URL como con [APP_FOLDER_SEGMENT]: son
         * rutas normales de `/me/drive/items/…`, idénticas a las de lectura. Refrescar con los
         * scopes de lectura devolvería un token válido pero sin permiso, y el reintento moriría
         * en 403.
         */
        const val HEADER_WRITE_SCOPE = "X-Siku-Write-Scope"
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        if (response.code != 401) return response
        // Solo intentamos refrescar si la request original traía auth
        if (request.header("Authorization") == null) return response
        // Evitamos loops infinitos si el retry también devuelve 401
        if (request.header(HEADER_RETRY_MARKER) != null) return response

        Log.w(TAG, "401 recibido en ${request.url.encodedPath}, refrescando token")

        val newToken = try {
            runBlocking {
                val authManager = authManagerProvider.get()
                when {
                    request.header(HEADER_WRITE_SCOPE) != null -> authManager.getWriteAccessToken().firstOrNull()
                    request.url.encodedPath.contains(APP_FOLDER_SEGMENT) -> authManager.getBackupAccessToken().firstOrNull()
                    else -> authManager.getAccessToken().firstOrNull()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error refrescando token", e)
            null
        }

        if (newToken !is AuthResult.Success || newToken.token.isBlank()) {
            // Se devuelve el 401 ORIGINAL, sin tocar la red. Antes se cerraba arriba y aquí se
            // hacía `chain.proceed(request)`: o sea, la MISMA petición con el MISMO token muerto,
            // que solo podía dar otro 401 — coste doble en cada petición de un scan con la sesión
            // rota. Por eso el `close()` se movió a después de esta decisión: quien devuelve una
            // respuesta no puede haberla cerrado.
            Log.e(TAG, "Token refresh falló, devolviendo 401 original")
            return response
        }

        // A partir de aquí sí se descarta: el cuerpo del 401 no lo va a leer nadie y hay que
        // cerrarlo para liberar la conexión antes del reintento.
        response.close()

        val retryRequest = request.newBuilder()
            .header("Authorization", "Bearer ${newToken.token}")
            .header(HEADER_RETRY_MARKER, "1")
            .build()
        return chain.proceed(retryRequest)
    }
}
