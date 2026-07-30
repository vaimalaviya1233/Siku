package com.qhana.siku.data.source

import android.util.Log
import com.qhana.siku.data.model.Song
import com.qhana.siku.data.model.SourceType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registro de las fuentes de música. Rutea cada operación a la [MusicSource] correcta según
 * `song.sourceType`, y expone las fuentes **configuradas** para que el orquestador de sync
 * las escanee.
 *
 * Agregar un proveedor nuevo = implementar [MusicSource] y sumarlo a [sources].
 */
@Singleton
class MusicSourceRegistry @Inject constructor(
    oneDriveSource: OneDriveMusicSource,
    localSource: LocalMusicSource
) {
    private val sources: List<MusicSource> = listOf(oneDriveSource, localSource)

    /**
     * Fuentes que el usuario configuró (OneDrive con sesión, local con carpeta elegida).
     * Las no configuradas se saltan: así una biblioteca "sólo local" no falla por auth.
     */
    suspend fun activeSources(): List<MusicSource> = sources.filter { it.isConfigured() }

    /**
     * Los [SourceType] configurados AHORA MISMO, observable. Es la versión reactiva de
     * [activeSources] para quien no puede suspender ni debe acordarse de refrescar (la UI).
     *
     * Se expone el conjunto de TIPOS y no la lista de fuentes: un consumidor de UI solo necesita
     * saber qué clase de origen hay disponible ("¿tengo nube?", "¿tengo música del dispositivo?"),
     * y entregarle las fuentes le daría acceso a `discover()` y compañía, que son del orquestador.
     *
     * Añadir un proveedor nuevo no obliga a tocar esto: entra solo por [sources].
     */
    val configuredTypes: Flow<Set<SourceType>> =
        combine(sources.map { source -> source.isConfiguredFlow.map { source.type to it } }) { pairs ->
            pairs.filter { it.second }.map { it.first }.toSet()
        }

    /** ¿Hay alguna fuente de NUBE configurada? Ver [configuredTypes]. */
    val hasCloudSource: Flow<Boolean> = configuredTypes.map { types -> types.any { it.isCloud } }

    /**
     * Fuente que maneja [song], o `null` si ninguna declara su `sourceType`.
     *
     * Caía a `sources.first()` "por compat", es decir: rutea a OneDrive una canción que no es
     * suya. Eso no es compatibilidad, es un proveedor equivocado resolviendo URLs y metadata
     * con un handle que no entiende — y en silencio. Hoy es inalcanzable (cada `SourceType`
     * tiene su fuente registrada), y precisamente por eso el fallback solo podía esconder el
     * día en que alguien añada un proveedor y olvide sumarlo a [sources]: mejor ninguna fuente,
     * con su log, que la fuente que no es.
     */
    private fun sourceFor(song: Song): MusicSource? {
        val source = sources.firstOrNull { it.type == song.sourceType }
        if (source == null) {
            Log.e(TAG, "Sin fuente registrada para sourceType=${song.sourceType} (${song.id})")
        }
        return source
    }

    suspend fun resolveDownloadUrl(song: Song, forceRefresh: Boolean = false): String? =
        sourceFor(song)?.resolveDownloadUrl(song, forceRefresh)

    /** Sin fuente, la canción se devuelve tal cual: no hay de dónde sacar metadata. */
    suspend fun extractMetadata(song: Song): Song = sourceFor(song)?.extractMetadata(song) ?: song

    suspend fun fetchLightMetadata(song: Song): LightMetadata? = sourceFor(song)?.fetchLightMetadata(song)

    private companion object {
        const val TAG = "MusicSourceRegistry"
    }
}
