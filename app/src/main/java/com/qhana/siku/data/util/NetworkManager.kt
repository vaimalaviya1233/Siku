package com.qhana.siku.data.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Estado de la red POR DEFECTO — la que va a usar el tráfico de la app.
 *
 * [isUnmetered] es la que gobierna las descargas masivas y se llama así, y no "isWifi", porque
 * lo que importa no es la tecnología del enlace sino quién paga los bytes: un WiFi que el
 * usuario marcó como "de uso medido" en Ajustes cuenta como datos, y un hotspot también.
 */
data class NetworkStatus(
    val isAvailable: Boolean,
    val isUnmetered: Boolean
) {
    companion object {
        val Offline = NetworkStatus(isAvailable = false, isUnmetered = false)
    }
}

/**
 * Centraliza las verificaciones de estado de red.
 *
 * Observa la red por PUSH (`registerDefaultNetworkCallback`, API 24+) en vez de consultar al
 * sistema cada vez que alguien pregunta. Es la convención de "esperar por SEÑAL, no por
 * intervalo" aplicada a la red: quien necesite reaccionar a un cambio colecta [status] y
 * despierta en el instante del cambio, en lugar de sondear cada N segundos y enterarse tarde.
 *
 * Dos detalles que hacen que la detección sea fiable:
 *
 *  - **La señal que importa es `onCapabilitiesChanged`, no `onAvailable`/`onLost`.** Pasar de
 *    WiFi a datos no siempre produce un par perder/ganar: el sistema puede sustituir la red por
 *    defecto y entregar solo un cambio de capacidades. Y marcar el WiFi actual como "de uso
 *    medido" no cambia de red en absoluto — únicamente retira `NET_CAPABILITY_NOT_METERED`.
 *    Escuchando solo altas y bajas, ese caso no se detecta nunca.
 *  - **Cada callback dispara una RE-LECTURA del estado real**, no una interpretación del evento
 *    concreto. Durante una transición llegan eventos de la red vieja y de la nueva sin orden
 *    garantizado, así que traducir cada uno por su cuenta (`onLost` ⇒ sin red) abre una ventana
 *    en la que se reporta un estado que ya no es cierto. Preguntar "¿cuál es la red por defecto
 *    AHORA?" no tiene esa ventana y además deja una única definición del estado.
 */
@Singleton
class NetworkManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val connectivityManager: ConnectivityManager by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    private val _status = MutableStateFlow(queryNetworkState())

    /**
     * Estado actual y sus cambios. Colectarlo es la forma de esperar por una red concreta
     * (`status.first { it.isUnmetered }`) sin sondear.
     */
    val status: StateFlow<NetworkStatus> = _status.asStateFlow()

    /**
     * Si el registro del callback falla, las lecturas vuelven a consultar al sistema en cada
     * llamada. Sin esta bandera el StateFlow se quedaría congelado en su valor inicial para
     * siempre, que es un fallo mucho peor —y silencioso— que el coste de la consulta.
     */
    private var observing = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refresh()
        override fun onLost(network: Network) = refresh()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = refresh()
    }

    /**
     * Hilo propio para los callbacks del sistema.
     *
     * `registerDefaultNetworkCallback` sin handler entrega en el hilo PRINCIPAL, y
     * `onCapabilitiesChanged` no llega solo cuando cambia la red: el sistema lo dispara cada vez
     * que revisa su estimación de ancho de banda del enlace, que en móvil es muy a menudo. Cada
     * uno cuesta dos llamadas binder ([queryNetworkState]) cuyo resultado casi siempre es idéntico
     * al anterior y el StateFlow descarta — trabajo tirado, pero en el hilo que dibuja. Aquí no
     * hace falta el main: [_status] es un StateFlow y sus consumidores ya eligen dónde colectar.
     */
    private val callbackThread = HandlerThread("NetworkManager").apply { start() }

    init {
        try {
            connectivityManager.registerDefaultNetworkCallback(
                callback,
                Handler(callbackThread.looper)
            )
            observing = true
        } catch (e: Exception) {
            // Documentado como posible si la app supera el límite de callbacks del sistema.
            Log.w(TAG, "No se pudo observar la red, se consultará bajo demanda: ${e.message}")
        }
    }

    private fun refresh() {
        _status.value = queryNetworkState()
    }

    /**
     * Estado de la red por defecto tal y como lo ve el sistema en este instante.
     *
     * `NET_CAPABILITY_INTERNET` y no `NET_CAPABILITY_VALIDATED`: "validada" significa que el
     * sistema comprobó que hay salida real (y descartaría un portal cautivo de hotel), pero
     * también se queda en falso en redes que funcionan y no alcanzan el servidor de validación.
     * Bloquear el sync ahí sería peor que el problema que resuelve — un portal cautivo hace
     * fallar las descargas, que ya se clasifican como TRANSIENT y se reintentan con backoff.
     */
    private fun queryNetworkState(): NetworkStatus {
        val network = connectivityManager.activeNetwork ?: return NetworkStatus.Offline
        val capabilities = connectivityManager.getNetworkCapabilities(network)
            ?: return NetworkStatus.Offline
        return NetworkStatus(
            isAvailable = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            isUnmetered = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        )
    }

    private fun current(): NetworkStatus = if (observing) _status.value else queryNetworkState()

    /**
     * Verifica si hay conexión a internet disponible.
     */
    fun isAvailable(): Boolean = current().isAvailable

    /**
     * Verifica si la red actual NO es de uso medido (WiFi normal). Ver [NetworkStatus].
     */
    fun isWifi(): Boolean = current().isUnmetered

    /**
     * Estimación del ancho de banda de bajada del enlace activo en kbps, según el sistema
     * (en WiFi deriva del link speed negociado con el AP). 0 si no hay red o el sistema
     * no reporta nada. Es una estimación optimista del ENLACE, no del ISP — sirve como
     * techo para dimensionar paralelismo, no como medición real.
     */
    fun downlinkKbps(): Int {
        val network = connectivityManager.activeNetwork ?: return 0
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return 0
        return capabilities.linkDownstreamBandwidthKbps
    }

    private companion object {
        const val TAG = "NetworkManager"
    }
}
