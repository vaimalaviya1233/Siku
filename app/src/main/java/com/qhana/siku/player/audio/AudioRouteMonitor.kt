package com.qhana.siku.player.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Por dónde está saliendo el audio.
 *
 * [absoluteVolumeLikely] es el dato que motiva toda esta clase: dice si la ruta anula la
 * ATENUACIÓN DIGITAL del volumen de media, que es el mecanismo en el que se apoyaba el
 * ecualizador para que sus picos por encima de fondo de escala no recortaran (ver el kdoc de
 * [EqualizerAudioProcessor]). Con volumen absoluto de Bluetooth (AVRCP 1.4+, el default en
 * Android desde la 6) el teléfono transmite a nivel FIJO y quien atenúa es el auricular, así que
 * el mixer no aplica nada — y esa red de seguridad no existe, esté donde esté el control de
 * volumen. Sin esto, el indicador de headroom pinta verde en la única situación donde de verdad
 * hay riesgo.
 *
 * Es "likely" y no una certeza a propósito: **no hay API pública para saber si el volumen absoluto
 * está activo**. Se puede desactivar por dispositivo desde opciones de desarrollador y algunos
 * fabricantes lo apagan para ciertos auriculares. Lo que sí se sabe con certeza es la RUTA, y en
 * Bluetooth el volumen absoluto es el caso por defecto. La UI lo redacta como una advertencia
 * ("puede que…"), nunca como un diagnóstico.
 */
enum class AudioRoute(internal val priority: Int, val absoluteVolumeLikely: Boolean) {
    BLUETOOTH(priority = 0, absoluteVolumeLikely = true),
    USB(priority = 1, absoluteVolumeLikely = false),
    WIRED(priority = 2, absoluteVolumeLikely = false),
    SPEAKER(priority = 3, absoluteVolumeLikely = false),
    OTHER(priority = 4, absoluteVolumeLikely = false)
}

/**
 * Observa la ruta de salida de audio activa.
 *
 * El flow es FRÍO hasta que alguien lo colecta ([SharingStarted.WhileSubscribed]): el callback del
 * sistema solo queda registrado mientras hay quien pregunte. Un singleton escuchando cambios de
 * dispositivo para siempre sería gasto puro con la app cerrada.
 *
 * Lo consumen dos sitios, con vidas distintas a propósito: la hoja del ecualizador (mientras está
 * abierta, para el aviso de headroom) y [EqProfileManager] (mientras vive el servicio de
 * reproducción, para restaurar el perfil de la ruta que se conecta). El segundo mantiene el
 * callback registrado durante toda la reproducción, que es barato — el sistema avisa, aquí no se
 * consulta nada en bucle.
 */
@Singleton
class AudioRouteMonitor @Inject constructor(
    @ApplicationContext context: Context
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val route: StateFlow<AudioRoute> = callbackFlow {
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                trySend(currentRoute())
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                trySend(currentRoute())
            }
        }
        trySend(currentRoute())
        audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        awaitClose { audioManager.unregisterAudioDeviceCallback(callback) }
    }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), currentRoute())

    private fun currentRoute(): AudioRoute {
        // API 31+ sabe la respuesta EXACTA: qué dispositivo recibiría audio con estos atributos.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            audioManager.getAudioDevicesForAttributes(attributes).firstOrNull()?.let {
                return routeOf(it.type)
            }
        }
        // Por debajo de API 31 no hay forma pública de preguntar por dónde SALE el audio, solo qué
        // salidas existen. Se infiere por prioridad, que reproduce el orden en que Android rutea
        // media: un Bluetooth conectado gana al jack, y el jack al altavoz.
        var best: AudioRoute? = null
        for (device in audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            val candidate = routeOf(device.type)
            if (best == null || candidate.priority < best.priority) best = candidate
        }
        return best ?: AudioRoute.OTHER
    }

    /**
     * Los tipos BLE (API 31+) se referencian directamente y es seguro: son constantes `int` que el
     * compilador INLINEA, así que en un dispositivo viejo simplemente no coincide ninguna rama —
     * no hay acceso a un campo inexistente en tiempo de ejecución.
     */
    private fun routeOf(type: Int): AudioRoute = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST -> AudioRoute.BLUETOOTH

        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> AudioRoute.USB

        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> AudioRoute.WIRED

        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AudioRoute.SPEAKER

        else -> AudioRoute.OTHER
    }

    private companion object {
        /**
         * Margen antes de soltar el callback al dejar de colectar. Cubre el cambio de
         * configuración (rotar la pantalla recompone la hoja) sin desregistrar y volver a
         * registrar en el sistema.
         */
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
