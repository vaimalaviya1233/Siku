package com.qhana.siku.player.audio

import com.qhana.siku.data.model.EqSettings
import com.qhana.siku.data.preferences.MusicPreferences
import com.qhana.siku.player.audio.clarity.Clarity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Recuerda la configuración del ecualizador POR CATEGORÍA de ruta de salida (cable, Bluetooth,
 * USB, altavoz) y la restaura al cambiar de una a otra.
 *
 * La forma de la función es "memoria", no "asignación": el usuario no ata perfiles a dispositivos
 * en ninguna pantalla — simplemente, lo último que dejó puesto con los cascos por cable vuelve
 * cuando reconecta los cascos por cable. Cero configuración, y funciona igual con 3 presets que
 * con 20.
 *
 * **Por CATEGORÍA y no por dispositivo concreto**, a propósito: distinguir unos cascos Bluetooth
 * de otros exige `AudioDeviceInfo.getAddress()`, y eso arrastra el permiso `BLUETOOTH_CONNECT`
 * (sin él la MAC llega como `02:00:00:00:00:00`). Pedir un permiso nuevo para afinar un caso que
 * casi nadie tiene sería mal negocio; la categoría cubre el uso real, y si algún día hacen falta
 * dos auriculares Bluetooth distintos se añade encima sin cambiar este diseño.
 *
 * Lo que se guarda es el ESTADO REAL del EQ ([EqSettings]), coincida o no con un preset guardado.
 * Guardar solo "qué preset estaba aplicado" perdería sin avisar los retoques de quien mueve un
 * slider y no lo guarda, que es el uso normal de un ecualizador.
 *
 * La verdad se lee y se escribe SIEMPRE en [MusicPreferences], nunca en el processor: las
 * preferencias están cargadas en memoria desde el arranque, mientras que el processor puede no
 * tener todavía la config aplicada cuando el servicio arranca — leerlo ahí guardaría una curva
 * plana encima del perfil del usuario.
 */
@Singleton
class EqProfileManager @Inject constructor(
    private val preferences: MusicPreferences,
    private val routeMonitor: AudioRouteMonitor,
    private val processor: EqualizerAudioProcessor,
    private val clarity: Clarity
) {
    private val scope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
    private var job: Job? = null

    /**
     * Emite cada vez que un cambio de ruta aplica un perfil. Lo colecta `PlaybackViewModel` para
     * resincronizar su estado: sus flows de bandas/refuerzos/preamp son `MutableStateFlow` locales
     * (tienen que serlo — se escriben en cada frame de arrastre de un slider, y persistir por frame
     * sería absurdo), así que un escritor externo necesita avisar. Sin esto, la hoja del EQ abierta
     * mientras conectás los cascos seguiría dibujando la curva anterior.
     */
    private val _applied = MutableSharedFlow<EqSettings>(extraBufferCapacity = 4)
    val applied: SharedFlow<EqSettings> = _applied.asSharedFlow()

    /**
     * Arranca la observación. Lo llama `MusicPlaybackService`: la ruta solo importa mientras hay
     * reproducción, y así el `AudioDeviceCallback` no queda registrado con la app cerrada.
     * Idempotente — el servicio puede recrearse.
     */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { observe() }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun observe() {
        // SIEMPRE activo. La memoria por ruta de salida dejó de ser un ajuste opcional (era un
        // toggle en Ajustes, default OFF): el usuario espera que el sonido vuelva solo al reconectar
        // cada dispositivo, sin activar nada. Se mantiene el criterio de "adoptar la primera vez"
        // ([onRoute], rama null), que es lo que hace que empezar a usarlo no cambie el sonido de golpe.
        routeMonitor.route.collect { onRoute(it.name) }
    }

    private fun onRoute(routeKey: String) {
        val last = preferences.loadLastEqRoute()
        when (last) {
            // Nunca se selló una ruta (primer arranque tras actualizar, o biblioteca nueva): la
            // ruta actual ADOPTA lo que hay puesto. No se aplica nada, así que estrenar la memoria
            // por ruta nunca cambia el sonido de golpe.
            null -> {
                preferences.saveEqRouteProfile(routeKey, currentSettings())
                preferences.saveLastEqRoute(routeKey)
            }
            // Misma ruta que la última sellada: el estado global YA es el suyo. Se refresca el
            // snapshot para que sobreviva a la muerte del proceso con los cambios de esta sesión.
            routeKey -> preferences.saveEqRouteProfile(routeKey, currentSettings())
            // Cambio de ruta de verdad: sella la que dejamos y restaura la que llega.
            //
            // **Y si la que llega no tiene perfil, se queda PLANA** ([EqSettings.neutral]). Esta
            // rama antes copiaba el estado actual como perfil del dispositivo nuevo, y eso hacía
            // dos cosas mal a la vez: al desconectar los cascos seguía sonando su curva por el
            // altavoz, y además esa curva quedaba SELLADA como si fuera la del altavoz, así que la
            // adopción se propagaba a cada dispositivo la primera vez que se usaba. La adopción es
            // correcta UNA vez —la rama `null`, para estrenar la función sin cambiar el sonido de
            // golpe— y solo esa vez; a partir de ahí, "no tengo perfil" significa neutro, no
            // "heredo el del anterior".
            else -> {
                preferences.saveEqRouteProfile(last, currentSettings())
                apply(
                    preferences.loadEqRouteProfile(routeKey)
                        ?: EqSettings.neutral(preferences.loadEqBandCount())
                )
                // Se sella lo que quedó puesto (leído de preferencias, que `apply` ya normalizó),
                // de modo que el estado de esta ruta sea explícito y no dependa de que más tarde
                // pase por aquí otra vez.
                preferences.saveEqRouteProfile(routeKey, currentSettings())
                preferences.saveLastEqRoute(routeKey)
            }
        }
    }

    /** Estado actual del EQ tal como está persistido. Ver el kdoc de la clase. */
    private fun currentSettings(): EqSettings {
        val bandCount = preferences.loadEqBandCount()
        return EqSettings(
            bandCount = bandCount,
            gains = preferences.loadEqBandGains(bandCount),
            bassBoostDb = preferences.loadEqBassBoost(),
            trebleBoostDb = preferences.loadEqTrebleBoost(),
            bassFreqHz = preferences.loadEqBassFreq(),
            trebleFreqHz = preferences.loadEqTrebleFreq(),
            preampDb = preferences.loadEqPreamp(),
            limiterEnabled = preferences.loadEqLimiterEnabled(),
            limiterThresholdDb = preferences.loadEqLimiterThreshold(),
            limiterThresholdAuto = preferences.loadEqLimiterThresholdAuto(),
            clarityEnabled = preferences.loadClarityEnabled(),
            clarityGainDb = preferences.loadClarityGain()
        )
    }

    /**
     * Aplica un perfil: preferencias primero (son la verdad) y processor después (es el efecto).
     *
     * El toggle on/off del EQ NO entra aquí a propósito — cambiarlo reconstruye la pipeline de
     * audio y produciría un corte audible justo al conectar el dispositivo. Ver [EqSettings].
     */
    private fun apply(settings: EqSettings) {
        val bassFreq = settings.bassFreqHz
            ?: EqualizerAudioProcessor.BASS_BOOST_FREQ_DEFAULT_HZ
        val trebleFreq = settings.trebleFreqHz
            ?: EqualizerAudioProcessor.TREBLE_BOOST_FREQ_DEFAULT_HZ
        val threshold = settings.limiterThresholdDb
            ?: EqualizerAudioProcessor.LIMITER_THRESHOLD_MAX_DB

        // Preferencias primero, en UN solo volcado atómico (ver [MusicPreferences.saveEqSettings]):
        // los nullables van resueltos para que el default quede escrito de forma determinista.
        preferences.saveEqSettings(
            settings.copy(
                bassFreqHz = bassFreq,
                trebleFreqHz = trebleFreq,
                limiterThresholdDb = threshold
            )
        )

        processor.setBands(
            EqualizerAudioProcessor.bandsFor(settings.bandCount),
            settings.gains
        )
        processor.setBassBoost(settings.bassBoostDb)
        processor.setTrebleBoost(settings.trebleBoostDb)
        processor.setBassBoostFreq(bassFreq)
        processor.setTrebleBoostFreq(trebleFreq)
        processor.setPreamp(settings.preampDb)
        processor.setLimiterEnabled(settings.limiterEnabled)
        // El umbral EFECTIVO sale de una sola fórmula compartida con PlaybackViewModel. Los dos
        // escriben este parámetro del processor —el ViewModel puede no existir con la app en
        // segundo plano, así que aquí no es opcional—, y que ambos deriven el valor del mismo
        // sitio es lo que impide que se pisen con números distintos (convención 14).
        processor.setLimiterThreshold(
            EqualizerAudioProcessor.effectiveLimiterThresholdDb(
                auto = settings.limiterThresholdAuto,
                manualDb = threshold
            )
        )
        // Clarity vive dentro del processor y se aplica en vivo (no reconstruye la pipeline como el
        // toggle on/off del EQ), así que es seguro moverlo al conectar el dispositivo.
        clarity.setEnabled(settings.clarityEnabled)
        clarity.setGainDb(settings.clarityGainDb)

        _applied.tryEmit(settings)
    }
}
