package com.qhana.siku.player.audio

import com.qhana.siku.data.model.EqSettings
import com.qhana.siku.data.preferences.MusicPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
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
    private val processor: EqualizerAudioProcessor
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
        preferences.eqRouteProfilesEnabledFlow.distinctUntilChanged().collectLatest { enabled ->
            if (!enabled) {
                // Olvidar la ruta sellada es lo que evita una sorpresa al REACTIVAR la función:
                // con la marca puesta, volver a encenderla meses después se leería como un cambio
                // de ruta y aplicaría un perfil viejo de golpe. Sin ella, la primera ruta que se
                // vea adopta la configuración que el usuario tenga en ese momento.
                preferences.clearLastEqRoute()
                return@collectLatest
            }
            routeMonitor.route.collect { onRoute(it.name) }
        }
    }

    private fun onRoute(routeKey: String) {
        val last = preferences.loadLastEqRoute()
        when (last) {
            // Nunca se usó la función (o se acaba de activar): la ruta actual ADOPTA lo que hay
            // puesto. No se aplica nada, así que activar el ajuste nunca cambia el sonido.
            null -> {
                preferences.saveEqRouteProfile(routeKey, currentSettings())
                preferences.saveLastEqRoute(routeKey)
            }
            // Misma ruta que la última sellada: el estado global YA es el suyo. Se refresca el
            // snapshot para que sobreviva a la muerte del proceso con los cambios de esta sesión.
            routeKey -> preferences.saveEqRouteProfile(routeKey, currentSettings())
            // Cambio de ruta de verdad: sella la que dejamos y restaura la que llega.
            else -> {
                preferences.saveEqRouteProfile(last, currentSettings())
                val target = preferences.loadEqRouteProfile(routeKey)
                if (target != null) apply(target) else {
                    preferences.saveEqRouteProfile(routeKey, currentSettings())
                }
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
            limiterThresholdAuto = preferences.loadEqLimiterThresholdAuto()
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

        preferences.saveEqBandCount(settings.bandCount)
        preferences.saveEqBandGains(settings.bandCount, settings.gains)
        preferences.saveEqBassBoost(settings.bassBoostDb)
        preferences.saveEqTrebleBoost(settings.trebleBoostDb)
        preferences.saveEqBassFreq(bassFreq)
        preferences.saveEqTrebleFreq(trebleFreq)
        preferences.saveEqPreamp(settings.preampDb)
        preferences.saveEqLimiterEnabled(settings.limiterEnabled)
        preferences.saveEqLimiterThreshold(threshold)
        preferences.saveEqLimiterThresholdAuto(settings.limiterThresholdAuto)

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

        _applied.tryEmit(settings)
    }
}
