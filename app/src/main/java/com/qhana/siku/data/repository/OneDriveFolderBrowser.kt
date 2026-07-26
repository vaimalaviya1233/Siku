package com.qhana.siku.data.repository

import android.util.Log
import com.qhana.siku.data.auth.AuthManager
import com.qhana.siku.data.auth.AuthResult
import com.qhana.siku.data.model.AppError
import com.qhana.siku.data.model.AppResult
import com.qhana.siku.data.remote.OneDriveApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

/** Una carpeta del drive, tal como la lista el explorador. */
data class RemoteFolder(
    val id: String,
    val name: String,
    /** Cuántos elementos contiene, para orientar sin tener que entrar. */
    val childCount: Int
)

/**
 * Navega las carpetas del OneDrive del usuario para que pueda ELEGIR cuál contiene su música.
 *
 * Solo lee (`Files.Read` basta) y solo lista carpetas: los archivos se descartan porque el
 * objetivo es una ruta, no un archivo, y mezclarlos llenaría la lista de ruido.
 */
@Singleton
class OneDriveFolderBrowser @Inject constructor(
    private val oneDriveApi: OneDriveApi,
    private val authManager: AuthManager
) {

    /** Subcarpetas de [parentId], o de la raíz del drive si es `null`. */
    suspend fun listFolders(parentId: String?): AppResult<List<RemoteFolder>> =
        withContext(Dispatchers.IO) {
            try {
                val token = bearerToken()
                val folders = mutableListOf<RemoteFolder>()
                var url: String? = childrenUrlFor(parentId)
                var pages = 0

                while (url != null && pages < MAX_PAGES) {
                    val response = oneDriveApi.listChildren(token, url)
                    response.value.forEach { item ->
                        val facet = item.folder
                        val name = item.name
                        if (facet != null && name != null) {
                            folders.add(RemoteFolder(item.id, name, facet.childCount ?: 0))
                        }
                    }
                    url = response.nextLink
                    pages++
                }

                // Orden natural, insensible a mayúsculas: Graph ordena por nombre pero el
                // criterio varía entre cuentas, y una lista desordenada se nota enseguida.
                AppResult.Success(folders.sortedBy { it.name.lowercase() })
            } catch (e: HttpException) {
                Log.w(TAG, "No se pudieron listar las carpetas: ${e.code()}")
                AppResult.Error(
                    if (e.code() == HTTP_UNAUTHORIZED || e.code() == HTTP_FORBIDDEN) {
                        AppError.Auth(needsRelogin = true)
                    } else {
                        AppError.Network("Error de OneDrive (${e.code()})", e)
                    }
                )
            } catch (e: IllegalStateException) {
                AppResult.Error(AppError.Auth(needsRelogin = true))
            } catch (e: Exception) {
                AppResult.Error(AppError.fromException(e))
            }
        }

    private suspend fun bearerToken(): String {
        val result = authManager.getAccessToken().firstOrNull()
        check(result is AuthResult.Success && result.token.isNotBlank()) { "Sin sesión de OneDrive" }
        return "Bearer ${result.token}"
    }

    private fun childrenUrlFor(parentId: String?): String {
        val base = if (parentId == null) "$GRAPH_DRIVE/root/children" else "$GRAPH_DRIVE/items/$parentId/children"
        return "$base?\$select=id,name,folder&\$top=$PAGE_SIZE"
    }

    private companion object {
        const val TAG = "OneDriveFolderBrowser"
        const val GRAPH_DRIVE = "https://graph.microsoft.com/v1.0/me/drive"
        const val PAGE_SIZE = 200
        /** Tope de sanidad: una carpeta con miles de hijos no debe bloquear el selector. */
        const val MAX_PAGES = 10
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
    }
}
