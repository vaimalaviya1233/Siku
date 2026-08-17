package com.qhana.siku.widget

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Foto del estado de reproducción que consumen los widgets.
 *
 * Serialización manual con org.json a propósito: sin reflexión → sin reglas R8.
 */
data class WidgetQueueItem(
    val index: Int,
    val title: String,
    val artist: String
)

data class WidgetSnapshot(
    val songId: String?,
    val title: String?,
    val artist: String?,
    val artPath: String?,
    val isPlaying: Boolean,
    val isFavorite: Boolean,
    val currentIndex: Int,
    /** Ventana de la cola DESPUÉS de la actual (índices absolutos para playAt). */
    val upNext: List<WidgetQueueItem>,
    /**
     * Acento extraído de la carátula (misma convención que NowPlaying: `primary` para tema
     * claro, `secondary` para oscuro). Null = sin colores aún → tonos del tema del sistema.
     */
    val accentLight: Int? = null,
    val accentDark: Int? = null
) {
    companion object {
        val EMPTY = WidgetSnapshot(
            songId = null, title = null, artist = null, artPath = null,
            isPlaying = false, isFavorite = false, currentIndex = -1, upNext = emptyList()
        )
    }
}

object WidgetSnapshotStore {

    private const val FILE_NAME = "widget_snapshot.json"

    fun toJson(snapshot: WidgetSnapshot): String = JSONObject().apply {
        put("songId", snapshot.songId ?: JSONObject.NULL)
        put("title", snapshot.title ?: JSONObject.NULL)
        put("artist", snapshot.artist ?: JSONObject.NULL)
        put("artPath", snapshot.artPath ?: JSONObject.NULL)
        put("isPlaying", snapshot.isPlaying)
        put("isFavorite", snapshot.isFavorite)
        put("currentIndex", snapshot.currentIndex)
        put("accentLight", snapshot.accentLight ?: JSONObject.NULL)
        put("accentDark", snapshot.accentDark ?: JSONObject.NULL)
        put("upNext", JSONArray().apply {
            snapshot.upNext.forEach { item ->
                put(JSONObject().apply {
                    put("index", item.index)
                    put("title", item.title)
                    put("artist", item.artist)
                })
            }
        })
    }.toString()

    fun fromJson(raw: String?): WidgetSnapshot {
        if (raw.isNullOrBlank()) return WidgetSnapshot.EMPTY
        return runCatching {
            val json = JSONObject(raw)
            WidgetSnapshot(
                songId = json.optString("songId").takeIf { it.isNotEmpty() && !json.isNull("songId") },
                title = json.optString("title").takeIf { it.isNotEmpty() && !json.isNull("title") },
                artist = json.optString("artist").takeIf { it.isNotEmpty() && !json.isNull("artist") },
                artPath = json.optString("artPath").takeIf { it.isNotEmpty() && !json.isNull("artPath") },
                isPlaying = json.optBoolean("isPlaying", false),
                isFavorite = json.optBoolean("isFavorite", false),
                currentIndex = json.optInt("currentIndex", -1),
                accentLight = if (json.isNull("accentLight")) null else json.optInt("accentLight"),
                accentDark = if (json.isNull("accentDark")) null else json.optInt("accentDark"),
                upNext = buildList {
                    val arr = json.optJSONArray("upNext") ?: return@buildList
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        add(WidgetQueueItem(o.getInt("index"), o.getString("title"), o.getString("artist")))
                    }
                }
            )
        }.getOrDefault(WidgetSnapshot.EMPTY)
    }

    /** Fallback en disco: rehidrata sesiones de widget NUEVAS (recién añadidos al launcher). */
    fun write(context: Context, snapshot: WidgetSnapshot) {
        runCatching {
            // Escritura vía temp + rename: un render nunca lee un JSON a medias.
            val target = File(context.filesDir, FILE_NAME)
            val tmp = File(context.filesDir, "$FILE_NAME.tmp")
            tmp.writeText(toJson(snapshot))
            if (!tmp.renameTo(target)) {
                target.writeText(toJson(snapshot))
                tmp.delete()
            }
        }
    }

    fun read(context: Context): WidgetSnapshot {
        return runCatching {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) return WidgetSnapshot.EMPTY
            fromJson(file.readText())
        }.getOrDefault(WidgetSnapshot.EMPTY)
    }
}

/**
 * Publica un snapshot hacia los widgets. CLAVE: `provideGlance` corre UNA vez por sesión —
 * los `update()` posteriores solo recomponen, así que los datos frescos deben viajar por el
 * `currentState` (Preferences) de cada widget, no por variables capturadas.
 */
object WidgetUpdater {

    val KEY_SNAPSHOT = stringPreferencesKey("snapshot_json")

    suspend fun push(context: Context, snapshot: WidgetSnapshot) {
        // El archivo se escribe SIEMPRE, haya widgets o no: es el respaldo que rehidrata el
        // primer render de un widget recién añadido (y tras la muerte del proceso), así que
        // saltárselo cuando la pantalla de inicio está vacía dejaría rancio justo ese caso.
        WidgetSnapshotStore.write(context, snapshot)

        val manager = GlanceAppWidgetManager(context)
        val playerIds = runCatching { manager.getGlanceIds(PlayerWidget::class.java) }
            .getOrDefault(emptyList())
        val queueIds = runCatching { manager.getGlanceIds(QueueWidget::class.java) }
            .getOrDefault(emptyList())
        // Sin ningún widget colocado no hay a quién avisar: ni serializar ni renderizar.
        if (playerIds.isEmpty() && queueIds.isEmpty()) return

        val json = WidgetSnapshotStore.toJson(snapshot)
        runCatching {
            playerIds.forEach { id ->
                updateAppWidgetState(context, id) { prefs -> prefs[KEY_SNAPSHOT] = json }
                PlayerWidget().update(context, id)
            }
        }
        runCatching {
            queueIds.forEach { id ->
                updateAppWidgetState(context, id) { prefs -> prefs[KEY_SNAPSHOT] = json }
                QueueWidget().update(context, id)
            }
        }
    }
}

/**
 * ¿Hay algún widget de la app colocado en la pantalla de inicio, y cuándo cambia eso?
 *
 * Existe para que [WidgetBridge] no observe el estado del reproductor —que incluye
 * `getFavoritesIds`, una consulta suscrita a la tabla `songs` que un sync masivo re-ejecuta decenas
 * de veces por segundo— cuando no hay ni un widget que actualizar. Sin esto, un proceso levantado
 * por un worker (sin UI, sin widgets) sostenía esa suscripción durante todo el escaneo, para nadie.
 *
 * [changes] es la SEÑAL de que la presencia PUDO cambiar (no el valor): los receivers la emiten en
 * `onEnabled`/`onDisabled`, y quien la observa vuelve a preguntar por [hasAnyWidget]. Lleva `replay`
 * 1 con una semilla en el `init`, así que un colector nuevo evalúa el estado actual de inmediato sin
 * esperar a un cambio. Es un `object` de proceso: los receivers de widget corren en el mismo proceso
 * que la app, así que comparten esta instancia.
 */
object WidgetPresence {
    private val _changes = MutableSharedFlow<Unit>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    init {
        // Semilla: el primer colector evalúa la presencia actual sin esperar a un alta/baja.
        _changes.tryEmit(Unit)
    }

    /** Lo llaman los receivers de widget al añadirse el primero o quitarse el último de su tipo. */
    fun notifyChanged() {
        _changes.tryEmit(Unit)
    }

    suspend fun hasAnyWidget(context: Context): Boolean {
        val manager = GlanceAppWidgetManager(context)
        val hasPlayer = runCatching { manager.getGlanceIds(PlayerWidget::class.java) }
            .getOrDefault(emptyList()).isNotEmpty()
        if (hasPlayer) return true
        return runCatching { manager.getGlanceIds(QueueWidget::class.java) }
            .getOrDefault(emptyList()).isNotEmpty()
    }
}
