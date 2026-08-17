package com.qhana.siku.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences
import androidx.media3.common.Tracks
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.qhana.siku.MainActivity
import com.qhana.siku.data.preferences.MusicPreferences
import com.qhana.siku.data.util.AppLogger
import com.qhana.siku.data.util.LogLevel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Servicio de reproducción de música con Media3 MediaSession.
 *
 * La cola completa vive en ExoPlayer (ver MusicController), por lo que la notificación
 * y los controles de auto/wear usan la API estándar del player (`seekToNextMediaItem()`,
 * `hasNextMediaItem()`, etc.) sin intermediarios.
 */
@AndroidEntryPoint
class MusicPlaybackService : MediaSessionService() {

    @Volatile private var mediaSession: MediaSession? = null
    @Volatile private var player: ExoPlayer? = null

    @Inject
    lateinit var dataSourceFactory: DataSource.Factory

    @Inject
    lateinit var appLogger: AppLogger

    @Inject
    lateinit var musicPreferences: MusicPreferences

    @Inject
    lateinit var equalizerProcessor: com.qhana.siku.player.audio.EqualizerAudioProcessor

    @Inject
    lateinit var eqProfileManager: com.qhana.siku.player.audio.EqProfileManager

    /** Realce de agudos. Vive dentro de [equalizerProcessor], pero su estado se rehidrata aparte. */
    @Inject
    lateinit var clarity: com.qhana.siku.player.audio.clarity.Clarity

    /** Scope del watchdog de offload; se cancela en onDestroy. */
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /** El DSP aceptó de verdad el offload para el track en curso (lo dice Media3). */
    @Volatile private var offloadActive = false

    /** Ya se hizo el fallback en esta sesión: no repetirlo ni re-preparar en bucle. */
    @Volatile private var offloadDisabled = false

    /**
     * ¿El último build del sink dejó al processor TRANSFORMANDO el audio (float, sin offload)?
     * Es el estado REAL de la pipeline; [reevaluateAudioPipeline] lo compara con lo que pide
     * [EqualizerAudioProcessor.shouldProcess] para reconstruir solo cuando de verdad cambia.
     */
    private var processorRunning = false

    private var offloadWatchdogJob: Job? = null

    companion object {
        const val ACTION_SHOW_NOW_PLAYING = "com.qhana.siku.SHOW_NOW_PLAYING"

        /**
         * Ventana de búfer de ExoPlayer, en ms. Van nombrados en vez de escritos en la llamada
         * detrás de un comentario-etiqueta: ese comentario no lo comprueba el compilador, y son
         * cuatro `Int` seguidos, así que reordenarlos no fallaría en compilación y cambiaría el
         * comportamiento del reproductor en silencio.
         *
         * **Los cuatro se desvían del default de Media3**, y conviene tener las dos parejas
         * separadas porque responden a preguntas distintas:
         *
         * - **Cuánto se acumula** (`min`/`max`, defaults 50 s / 50 s): aquí 120 s / 180 s, o sea
         *   entre dos y tres veces más. El motivo es la RADIO, no la fluidez — cada relleno la sube
         *   a su estado de alto consumo y la deja ahí con una cola de 10-20 s, así que con 50 s
         *   prácticamente nunca llegaba a bajar. Tres minutos convierten el goteo continuo en
         *   ráfagas espaciadas. Barato en memoria porque es audio (~3 MB); lo que cuesta es
         *   descargar de más si el usuario salta de canción.
         *
         * - **Cuánto hay que tener para empezar a sonar** (`forPlayback` 500 ms contra 1000 de
         *   default, `afterRebuffer` 2500 contra 2000). Es latencia percibida, y la asimetría tiene
         *   sentido leída junta: al dar al play se arriesga —la mitad del default, para que suene
         *   cuanto antes— y tras un corte se acumula MÁS que el default, porque un corte ya es la
         *   prueba de que la red no da y volver enseguida solo encadenaría otro.
         *
         * **Ojo con el arranque de 500 ms si algún día aparecen cortes al empezar una canción por
         * streaming**: no está medido ni tiene autoridad detrás, y medio segundo de audio es poco
         * margen con un FLAC de tasa alta (los de varios cientos de MB) sobre una conexión que dé
         * menos de lo previsto. Es el primer valor a mirar en ese caso, no la ventana de arriba.
         */
        private const val BUFFER_MIN_MS = 120_000
        private const val BUFFER_MAX_MS = 180_000
        private const val BUFFER_FOR_PLAYBACK_MS = 500
        private const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 2_500
        const val CMD_GET_SESSION_ID = "GET_AUDIO_SESSION_ID"
        const val KEY_SESSION_ID = "AUDIO_SESSION_ID"

        /**
         * Cuánto puede estar el player en BUFFERING —con el offload ACTIVO, queriendo sonar y
         * sin que la posición avance— antes de declarar roto el DSP.
         *
         * El plazo depende de la FUENTE, y la diferencia es enorme para el usuario: un archivo
         * LOCAL no bufferiza jamás (abrirlo e iniciar el decoder son decenas de ms), así que
         * 1,5 s ya es un orden de magnitud de margen y no hay otra explicación posible que el
         * cuelgue del DSP. En STREAMING un buffering de varios segundos es legítimo (red mala),
         * y ahí hay que esperar más para no confundirlo.
         *
         * Un falso positivo solo cuesta perder el ahorro de batería, nunca el audio
         * (degradación segura): por eso se puede ser agresivo en el caso local.
         */
        private const val OFFLOAD_STALL_TIMEOUT_LOCAL_MS = 1_500L
        private const val OFFLOAD_STALL_TIMEOUT_REMOTE_MS = 6_000L
    }

    private val customCallback = object : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(SessionCommand(CMD_GET_SESSION_ID, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): com.google.common.util.concurrent.ListenableFuture<SessionResult> {
            if (customCommand.customAction == CMD_GET_SESSION_ID) {
                val resultBundle = Bundle()
                val sessionId = player?.audioSessionId ?: 0
                resultBundle.putInt(KEY_SESSION_ID, sessionId)
                return Futures.immediateFuture(
                    SessionResult(SessionResult.RESULT_SUCCESS, resultBundle)
                )
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            appLogger.log("SERVICE", "ExoPlayer.onIsPlayingChanged: isPlaying=$isPlaying")
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val stateName = when (playbackState) {
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                else -> "UNKNOWN($playbackState)"
            }
            appLogger.log("SERVICE", "ExoPlayer.onPlaybackStateChanged: state=$stateName")
            evaluateOffloadWatchdog()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            evaluateOffloadWatchdog()
            val reasonName = when (reason) {
                Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> "USER_REQUEST"
                Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> "AUDIO_FOCUS_LOSS"
                Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> "AUDIO_BECOMING_NOISY"
                Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE -> "REMOTE"
                Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM -> "END_OF_MEDIA_ITEM"
                Player.PLAY_WHEN_READY_CHANGE_REASON_SUPPRESSED_TOO_LONG -> "SUPPRESSED_TOO_LONG"
                else -> "UNKNOWN($reason)"
            }
            appLogger.log("SERVICE", "ExoPlayer.onPlayWhenReadyChanged: playWhenReady=$playWhenReady, reason=$reasonName")
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val reasonName = when (reason) {
                Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> "AUTO"
                Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> "PLAYLIST_CHANGED"
                Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> "REPEAT"
                Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> "SEEK"
                else -> "UNKNOWN($reason)"
            }
            appLogger.log("SERVICE", "ExoPlayer.onMediaItemTransition: mediaId=${mediaItem?.mediaId}, reason=$reasonName")
            // El watchdog mide "ESTE item lleva N ms sin avanzar": al cambiar de canción esa
            // medición caduca. Se cancela y se re-evalúa sobre el item nuevo — dejarlo correr
            // hacía que el veredicto comparase la posición de dos items distintos (y con el
            // `isLocal` del anterior, que decide si el veredicto se PERSISTE).
            offloadWatchdogJob?.cancel()
            offloadWatchdogJob = null
            evaluateOffloadWatchdog()
            // El origen puede cambiar dentro de la MISMA cola (una pista descargada seguida de una
            // que aún se transmite), así que el wake mode se re-evalúa por item y no una vez.
            player?.let { applyWakeMode(it) }
        }

        override fun onPlayerError(error: PlaybackException) {
            appLogger.log("SERVICE", "ExoPlayer.onPlayerError: code=${error.errorCode}, msg=${error.message}", LogLevel.ERROR)
        }
        // onTracksChanged y onEvents (posición en cada discontinuidad) se quitaron:
        // logueaban en alta frecuencia durante toda la reproducción sin aportar diagnóstico útil.
    }

    /**
     * Media3 avisa cuando la reproducción offload realmente EMPIEZA en el DSP. Solo se usa
     * para loguear: NO puede ser condición del watchdog, porque en el DSP roto la reproducción
     * no empieza nunca y este callback JAMÁS llega — gatear con él dejaba el watchdog sin
     * armar y el cuelgue seguía intacto (bug encontrado en la primera prueba real).
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private val offloadListener = object : ExoPlayer.AudioOffloadListener {
        override fun onOffloadedPlayback(isOffloadedPlayback: Boolean) {
            offloadActive = isOffloadedPlayback
            appLogger.log("SERVICE", "Audio offload activo en el DSP: $isOffloadedPlayback")
            evaluateOffloadWatchdog()
        }
    }

    /**
     * WATCHDOG DEL OFFLOAD. Arranca un temporizador cuando se dan a la vez: offload HABILITADO
     * por preferencia (no hace falta confirmación del DSP — ver [offloadListener]), estado
     * BUFFERING y el usuario queriendo escuchar. Si al vencer el plazo el player SIGUE en
     * BUFFERING y la posición NO avanzó ni un milisegundo, el DSP se tragó el audio: es el
     * cuelgue silencioso (ver [disableOffloadPermanently]).
     *
     * Cualquier otra transición (READY, pausa) cancela el temporizador, así que un buffering
     * normal —por corto que sea— nunca dispara el fallback.
     */
    private fun evaluateOffloadWatchdog() {
        val p = player ?: return
        // `isOffloadRequested()` y no solo `!offloadDisabled`: con el EQ activo el offload está
        // DESACTIVADO (los processors se saltarían), así que un atasco no puede ser culpa suya.
        // Vigilarlo igual llevaba a "recuperarse" de un offload que no estaba en uso —cortando el
        // audio con un stop/prepare/seek— y, con un archivo local, a PERSISTIR el veredicto de
        // DSP roto de por vida en un dispositivo sano.
        val suspicious = isOffloadRequested() &&
            p.playbackState == Player.STATE_BUFFERING &&
            p.playWhenReady

        if (!suspicious) {
            offloadWatchdogJob?.cancel()
            offloadWatchdogJob = null
            return
        }
        if (offloadWatchdogJob?.isActive == true) return // ya hay uno vigilando

        val positionAtStart = p.currentPosition
        // El item vigilado se captura junto con su posición: el veredicto es "ESTE item no avanzó
        // ni un milisegundo", y sin la comprobación dos canciones distintas bufferizando en
        // posición 0 (red mala) se leían como un atasco.
        val watchedItemId = p.currentMediaItem?.mediaId
        val isLocal = isCurrentItemLocal(p)
        val timeout = if (isLocal) OFFLOAD_STALL_TIMEOUT_LOCAL_MS else OFFLOAD_STALL_TIMEOUT_REMOTE_MS
        offloadWatchdogJob = serviceScope.launch {
            delay(timeout)
            val player = this@MusicPlaybackService.player ?: return@launch
            val stillStalled = player.playbackState == Player.STATE_BUFFERING &&
                player.playWhenReady &&
                player.currentMediaItem?.mediaId == watchedItemId &&
                player.currentPosition == positionAtStart
            if (stillStalled && isOffloadRequested()) {
                disableOffloadAndRecover(player, timeout, persistVerdict = isLocal)
            }
        }
    }

    /**
     * ¿El offload está pedido de verdad para lo que suena ahora? Es la MISMA condición que aplica
     * [applyOffloadPreference] al player, y por eso vive en un solo sitio: si el watchdog y la
     * preferencia pudieran discrepar, el watchdog vigilaría un modo que no está activo.
     */
    private fun isOffloadRequested(): Boolean = !offloadDisabled && !equalizerProcessor.shouldProcess()

    /** `file://` o `content://` = los bytes están en el dispositivo: no existe buffering de red. */
    private fun isCurrentItemLocal(player: ExoPlayer): Boolean {
        val scheme = player.currentMediaItem?.localConfiguration?.uri?.scheme ?: return false
        return scheme == "file" || scheme == "content"
    }

    /**
     * Wake mode SEGÚN EL ORIGEN de lo que suena, re-evaluado en cada cambio de pista.
     *
     * Con streaming y la pantalla apagada, entre dos rellenos del búfer pasan decenas de segundos
     * en los que nada mantiene despierto ni el CPU ni el WiFi: el sistema puede dormir la radio y
     * el siguiente relleno llega tarde, con el resultado de un corte de audio a mitad de canción.
     * `WAKE_MODE_NETWORK` es exactamente la red de seguridad para eso (un wakelock parcial más un
     * WifiLock, ambos sostenidos por ExoPlayer SOLO mientras reproduce).
     *
     * Con un archivo del dispositivo se pide `WAKE_MODE_NONE` a propósito, y no por ahorrar un
     * poco: ahí no hay red que mantener viva y el AudioTrack ya impide que el sistema se duerma
     * mientras hay audio saliendo. Dejar el modo de red puesto para todo sería pagar un WifiLock
     * permanente por una reproducción que no toca la red.
     */
    private fun applyWakeMode(player: ExoPlayer) {
        player.setWakeMode(
            if (isCurrentItemLocal(player)) C.WAKE_MODE_NONE else C.WAKE_MODE_NETWORK
        )
    }

    /**
     * El offload de ESTE dispositivo se tragó el audio: se desactiva y se re-prepara la
     * canción por el camino de software, retomando en la misma posición (el usuario ve un
     * tirón de ~1,5 s, una única vez en la vida del dispositivo).
     *
     * [persistVerdict] distingue la certeza: con un archivo LOCAL colgado no existe otra
     * explicación (no hay red) → el veredicto se PERSISTE y en los próximos arranques el
     * offload ni se intenta. Con STREAMING el estancamiento podría ser la red, así que solo
     * se desactiva para esta sesión — un dispositivo sano no queda marcado para siempre por
     * un mal momento de WiFi.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun disableOffloadAndRecover(player: ExoPlayer, stalledMs: Long, persistVerdict: Boolean) {
        offloadDisabled = true
        appLogger.log(
            "SERVICE",
            "Offload COLGADO (BUFFERING ${stalledMs}ms sin avanzar): desactivando " +
                (if (persistVerdict) "PARA SIEMPRE (archivo local: veredicto inequívoco)" else "para esta sesión (streaming: pudo ser la red)") +
                " y re-preparando.",
            LogLevel.ERROR
        )
        if (persistVerdict) musicPreferences.saveOffloadBroken(true)

        val index = player.currentMediaItemIndex
        val position = player.currentPosition

        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setAudioOffloadPreferences(
                AudioOffloadPreferences.Builder()
                    .setAudioOffloadMode(AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED)
                    .build()
            )
            .build()

        // El AudioSink en modo offload está muerto: hay que rehacer la pipeline. stop() NO
        // borra la cola, así que se re-prepara y se retoma donde estaba.
        player.stop()
        player.prepare()
        player.seekTo(index, position)
        player.play()
    }

    /**
     * Aplica el modo de offload que corresponde al estado actual: DESACTIVADO si el DSP está
     * marcado como roto ([offloadDisabled]) o si el processor debe transformar el audio
     * ([EqualizerAudioProcessor.shouldProcess] — los processors se saltan en offload); ACTIVADO en
     * cualquier otro caso, incluido el EQ encendido pero con curva plana y la hoja cerrada, que es
     * transparente y no necesita quitarle el offload al DSP.
     */
    private fun applyOffloadPreference(player: ExoPlayer) {
        val disable = !isOffloadRequested()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setAudioOffloadPreferences(
                AudioOffloadPreferences.Builder()
                    .setAudioOffloadMode(
                        if (disable) AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
                        else AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
                    )
                    .build()
            )
            .build()
    }

    /**
     * Reconstruye la pipeline del sink SOLO si cambió el que el processor deba TRANSFORMAR el audio
     * o quedarse transparente y cederle el offload al DSP (ver [EqualizerAudioProcessor.shouldProcess]).
     * `isActive` del processor solo se consulta al (re)configurar el sink, así que un cambio de esa
     * condición no llega a la pipeline por sí solo — de ahí que lo dispare todo lo que puede moverla:
     *  - el toggle del EQ (colector de [MusicPreferences.eqEnabledFlow]);
     *  - abrir/cerrar la hoja del EQ ([EqualizerAudioProcessor.onProcessingConditionChanged]), que
     *    es lo que hace que subir un slider desde plano suene en vivo;
     *  - un cambio de perfil por ruta de salida ([EqProfileManager.applied]) — que además coincide
     *    con la reconexión física del dispositivo, así que el reajuste pasa desapercibido.
     *
     * **La condición gobierna TODO el DSP propio, no solo las bandas**: el limitador y Clarity viven
     * dentro del mismo processor y se van con él. `stop()` no borra la cola: se re-prepara y se
     * retoma en la misma posición (tirón de <1s). El guard contra [processorRunning] evita
     * reconstruir en cada frame de arrastre de un slider — con la hoja abierta la condición ya es
     * `true` y no vuelve a cambiar hasta cerrarla— y también el rebuild espurio del primer emit de
     * cada colector al suscribirse.
     */
    private fun reevaluateAudioPipeline() {
        val desired = equalizerProcessor.shouldProcess()
        if (desired == processorRunning) return
        processorRunning = desired
        appLogger.log(
            "SERVICE",
            "Pipeline de audio: EQ ${if (desired) "PROCESANDO (float, sin offload)" else "TRANSPARENTE (offload disponible)"} — reconstruyendo"
        )
        // Sin player no hay pipeline que rehacer: la armará `onCreate` con la condición ya puesta.
        val p = player ?: return
        applyOffloadPreference(p)

        if (p.mediaItemCount == 0 || p.playbackState == Player.STATE_IDLE) return
        val index = p.currentMediaItemIndex
        val position = p.currentPosition
        val wasPlaying = p.playWhenReady
        p.stop()
        p.prepare()
        p.seekTo(index, position)
        if (wasPlaying) p.play()
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        appLogger.log("SERVICE", "MusicPlaybackService.onCreate()")

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(dataSourceFactory)

        // Estado inicial del EQ desde preferencias, ANTES de construir el player: la pipeline
        // del sink consulta isActive del processor al configurarse.
        equalizerProcessor.setEnabled(musicPreferences.loadEqEnabled())
        val eqBandCount = musicPreferences.loadEqBandCount()
        equalizerProcessor.setBands(
            com.qhana.siku.player.audio.EqualizerAudioProcessor.bandsFor(eqBandCount),
            musicPreferences.loadEqBandGains(eqBandCount)
        )
        equalizerProcessor.setBassBoost(musicPreferences.loadEqBassBoost())
        equalizerProcessor.setTrebleBoost(musicPreferences.loadEqTrebleBoost())
        // null = el usuario nunca movió el centro; el default es del processor, no de preferencias.
        musicPreferences.loadEqBassFreq()?.let { equalizerProcessor.setBassBoostFreq(it) }
        musicPreferences.loadEqTrebleFreq()?.let { equalizerProcessor.setTrebleBoostFreq(it) }
        // Protección de nivel. Van aquí por el mismo motivo que lo de arriba: el processor es un
        // singleton de proceso y este es el sitio donde su estado se rehidrata desde disco.
        equalizerProcessor.setPreamp(musicPreferences.loadEqPreamp())
        equalizerProcessor.setLimiterEnabled(musicPreferences.loadEqLimiterEnabled())
        // El umbral se resuelve AQUÍ y no solo en el ViewModel: si el usuario nunca abre la hoja
        // del ecualizador, ese colector no llega a existir y el limitador se quedaría con un
        // umbral que no corresponde al modo elegido.
        //
        // En AUTOMÁTICO es 0 dBFS fijo, o sea pura protección contra recorte (true-peak desde el
        // 29 jul). Hasta esa fecha aquí se replicaba la fórmula "−pico de la curva" del ViewModel:
        // eso convertía el limitador en un compresor que engancha en cuanto hay curva, que es el
        // mismo "corregir por detrás" que el proyecto ya rechazó dos veces en el preamp — y de paso
        // era una fórmula DUPLICADA en dos sitios que tenían que coincidir a mano. Ahora hay TRES
        // escritores de este parámetro (este, el ViewModel y EqProfileManager) y la fórmula vive en
        // uno solo, que es lo que los mantiene de acuerdo.
        equalizerProcessor.setLimiterThreshold(
            com.qhana.siku.player.audio.EqualizerAudioProcessor.effectiveLimiterThresholdDb(
                auto = musicPreferences.loadEqLimiterThresholdAuto(),
                manualDb = musicPreferences.loadEqLimiterThreshold()
                    ?: com.qhana.siku.player.audio.EqualizerAudioProcessor.LIMITER_THRESHOLD_MAX_DB
            )
        )

        // Clarity: singleton de proceso, y este es el sitio donde su estado se rehidrata
        // desde disco. NO necesita colector propio (a diferencia del toggle del EQ) porque
        // no cambia si el processor entra en la cadena: vive dentro y solo suena con el
        // ecualizador encendido.
        clarity.setEnabled(musicPreferences.loadClarityEnabled())
        clarity.setGainDb(musicPreferences.loadClarityGain())


        // setExtensionRendererMode se quitó: no hay renderers de extensión empaquetados,
        // así que no tenía efecto. setEnableDecoderFallback sí importa (cae a otro decoder
        // si el preferido falla al inicializar).
        // buildAudioSink se sobreescribe para colar el EQ propio en la cadena del sink
        // (EqAudioProcessorChain: EQ float + sonic, SIN silence-skipping — ver su kdoc).
        // Con el EQ apagado el processor está inactivo y la pipeline es idéntica a la stock.
        val renderersFactory = object : androidx.media3.exoplayer.DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioOutputPlaybackParams: Boolean
            ): androidx.media3.exoplayer.audio.AudioSink {
                return androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .setAudioProcessorChain(
                        com.qhana.siku.player.audio.EqAudioProcessorChain(equalizerProcessor)
                    )
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                    .build()
            }
        }
            .setEnableDecoderFallback(true)

        // Búfer LARGO a propósito, y el motivo es la RADIO, no la fluidez. Cada relleno la sube a
        // su estado de alto consumo y la deja ahí con una cola de 10-20 s; con los 50 s que había
        // antes se rellenaba tan seguido que prácticamente nunca llegaba a bajar. Estirarlo a tres
        // minutos convierte un goteo continuo en ráfagas espaciadas, que es el patrón que Android
        // recomienda para gastar menos: se descarga lo mismo, pero la radio duerme entre medias.
        //
        // Barato en memoria porque es audio: 3 min de FLAC son ~3 MB (un vídeo a la misma duración
        // serían cientos). El coste real de subirlo es descargar de más cuando el usuario salta de
        // canción antes de terminarla — aceptable, y menor que el de una radio siempre encendida.
        //
        // Los dos últimos valores (arranque y re-arranque tras un corte) NO se tocan: son la
        // latencia que el usuario percibe al dar al play, y no tienen nada que ver con lo anterior.
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                BUFFER_MIN_MS,
                BUFFER_MAX_MS,
                BUFFER_FOR_PLAYBACK_MS,
                BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            )
            // Manda el TIEMPO, no los bytes: sin esto, el techo de tamaño por defecto recortaría
            // la ventana justo en los FLAC, que son el formato de la biblioteca.
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val playerBuilder = ExoPlayer.Builder(this)
        playerBuilder.setRenderersFactory(renderersFactory)
        playerBuilder.setMediaSourceFactory(mediaSourceFactory)
        playerBuilder.setLoadControl(loadControl)
        playerBuilder.setAudioAttributes(audioAttributes, true)
        playerBuilder.setHandleAudioBecomingNoisy(true)

        player = playerBuilder.build()
        player?.addListener(playerListener)
        player?.addAudioOffloadListener(offloadListener)
        // Valor de arranque; a partir de aquí lo mantiene `onMediaItemTransition`. Sin cola aún,
        // `isCurrentItemLocal` responde false y se queda en el modo conservador (el de red).
        player?.let { applyWakeMode(it) }

        // Offload: el DSP decodifica y la CPU duerme (ahorro de batería real con la pantalla
        // apagada). Se intenta SIEMPRE, salvo: (a) dispositivos donde el watchdog ya comprobó
        // que el DSP lo implementa mal y se cuelga (veredicto persistido), o (b) mientras el
        // processor TRANSFORMA el audio ([EqualizerAudioProcessor.shouldProcess]) — en offload el
        // audio comprimido va directo al DSP y toda la cadena de AudioProcessors (el EQ incluido) se
        // salta: sonaría sin ecualizar. Encendido pero con curva plana y la hoja cerrada NO cuenta:
        // ahí el processor es transparente y no hay razón para renunciar al offload (ese es el
        // ahorro que este cálculo recupera).
        val offloadBroken = musicPreferences.loadOffloadBroken()
        offloadDisabled = offloadBroken
        applyOffloadPreference(player!!)
        // El estado REAL con el que queda el sink recién construido. A partir de aquí lo mantiene
        // [reevaluateAudioPipeline]; sembrarlo aquí es lo que evita el rebuild espurio del primer
        // emit de cada colector.
        processorRunning = equalizerProcessor.shouldProcess()
        // Abrir/cerrar la hoja del EQ mueve [EqualizerAudioProcessor.shouldProcess] sin pasar por
        // ningún colector: el processor avisa por aquí. Se reprograma al hilo del servicio antes de
        // tocar el player (el aviso llega en el hilo del colector del medidor).
        equalizerProcessor.onProcessingConditionChanged = {
            serviceScope.launch { reevaluateAudioPipeline() }
        }

        appLogger.log(
            "SERVICE",
            when {
                offloadBroken -> "ExoPlayer creado con audio offload DESACTIVADO (DSP marcado como roto en este dispositivo)"
                processorRunning -> "ExoPlayer creado con audio offload cedido al ECUALIZADOR (pipeline float)"
                else -> "ExoPlayer creado con audio offload ACTIVO (ahorro de batería; watchdog vigilando)"
            }
        )

        // Toggle del EQ en caliente (desde la hoja del NowPlaying): sigue la preferencia y reevalúa
        // la pipeline. dataStore emite el valor actual al suscribirse; el guard de
        // [reevaluateAudioPipeline] contra [processorRunning] absorbe ese primer emit sin rebuild.
        serviceScope.launch {
            musicPreferences.eqEnabledFlow
                .distinctUntilChanged()
                .collect { enabled ->
                    equalizerProcessor.setEnabled(enabled)
                    reevaluateAudioPipeline()
                }
        }

        // Cambio de perfil por ruta de salida: [EqProfileManager] reescribe la curva al conectar un
        // dispositivo (sin tocar el toggle), y eso puede cruzar el umbral transparente↔activo. Se
        // reevalúa aquí; el reajuste, si toca, coincide con la reconexión física y no se nota.
        serviceScope.launch {
            eqProfileManager.applied.collect { reevaluateAudioPipeline() }
        }

        // Perfiles del EQ por ruta de salida. Se engancha AQUÍ y no en el Application: la ruta solo
        // importa mientras hay reproducción, así que el AudioDeviceCallback del sistema queda
        // registrado exactamente durante la vida del servicio. Va después de rehidratar el
        // processor porque el manager puede aplicar un perfil en el acto (si arrancamos con una
        // ruta distinta a la de la última sesión) y ese valor debe ser el último en escribirse.
        eqProfileManager.start()
        // Si `start()` acaba de aplicar el perfil de otra ruta, su emisión de `applied` pudo caer
        // antes de que el colector de arriba se suscribiera (SharedFlow sin replay). Se reevalúa una
        // vez a mano para que la pipeline arranque coherente con la curva ya aplicada; idempotente.
        reevaluateAudioPipeline()

        val intent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_SHOW_NOW_PLAYING
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Conectamos el player DIRECTO (sin ForwardingPlayer); la cola nativa ya expone
        // next/prev correctamente al cliente de notificación.
        mediaSession = MediaSession.Builder(this, player!!)
            .setSessionActivity(pendingIntent)
            .setCallback(customCallback)
            .build()

        appLogger.log("SERVICE", "MediaSession created")
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    /**
     * Swipe de recientes con música sonando = SEGUIR sonando (comportamiento estándar de las
     * apps de música: Spotify/YT Music hacen lo mismo; es el patrón recomendado de Media3 y
     * NO es un bug). Solo se detiene el servicio si ya estaba en pausa o sin cola.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        appLogger.log("SERVICE", "onTaskRemoved() called")
        val player = mediaSession?.player
        if (player != null) {
            val shouldStop = !player.playWhenReady || player.mediaItemCount == 0
            if (shouldStop) {
                stopSelf()
            }
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onDestroy() {
        appLogger.log("SERVICE", "onDestroy() called")

        offloadWatchdogJob?.cancel()
        eqProfileManager.stop()
        // El processor es singleton de proceso y sobrevive al servicio: hay que soltar el callback
        // o quedaría apuntando a un serviceScope ya cancelado.
        equalizerProcessor.onProcessingConditionChanged = null
        serviceScope.cancel()
        try { this.player?.removeAudioOffloadListener(offloadListener) } catch (_: Exception) {}

        mediaSession?.let { session ->
            try { session.release() } catch (e: Exception) {
                appLogger.log("SERVICE", "Error releasing mediaSession: ${e.message}", LogLevel.ERROR)
            }
            mediaSession = null
        }

        this.player?.let { exoPlayer ->
            try { exoPlayer.removeListener(playerListener) } catch (_: Exception) {}
            try { exoPlayer.release() } catch (e: Exception) {
                appLogger.log("SERVICE", "Error releasing exoPlayer: ${e.message}", LogLevel.ERROR)
            }
        }
        this.player = null

        super.onDestroy()
    }
}
