package com.qhana.siku.ui.screens

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qhana.siku.R
import com.qhana.siku.ui.components.MaterialSymbol
import com.qhana.siku.ui.components.OneDriveSourceCard
import com.qhana.siku.ui.components.rememberOneDriveFolderChanger
import com.qhana.siku.ui.components.PhoneMusicSourceCard
import com.qhana.siku.ui.components.StorageLimitCard
import com.qhana.siku.ui.components.rememberDeviceScanActivator
import com.qhana.siku.ui.viewmodel.SourcesViewModel
import com.qhana.siku.ui.viewmodel.SyncViewModel
import com.qhana.siku.ui.theme.EXPRESSIVE_DEFAULT_EFFECTS_MS
import com.qhana.siku.ui.theme.EXPRESSIVE_FAST_EFFECTS_MS
import com.qhana.siku.ui.theme.ExpressiveDefaultEffectsEasing
import com.qhana.siku.ui.theme.ExpressiveFastEffectsEasing
import com.qhana.siku.ui.theme.SCREEN_ENTER_MS
import com.qhana.siku.ui.theme.SCREEN_SLIDE_DIVISOR
import com.qhana.siku.ui.theme.ScreenEnterEasing
import androidx.compose.animation.core.tween


/**
 * Posición del paso dentro de los pasos ACTIVOS. Se pasa como los dos números y no como la frase
 * ya formateada porque el encabezado pinta con ellos dos cosas —la barra de avance y el texto—, y
 * de una frase no se puede recuperar la fracción.
 */
private data class StepProgress(val index: Int, val count: Int) {
    /**
     * Cuánto del flujo se ha recorrido. Cuenta el paso ACTUAL como hecho (`index + 1`), que es lo
     * que hace que la barra se vea llena en el último paso, acompañando al "Paso 3 de 3" que tiene
     * al lado. Con `index / count` el final del onboarding se leería como incompleto.
     */
    val fraction: Float get() = (index + 1).toFloat() / count
}

/** Ancho de la barra de avance del encabezado: una pista corta bajo el hero, no un riel a todo lo ancho. */
private val OnboardingProgressWidth = 96.dp

/** Música del propio teléfono: dispositivo entero o carpetas. Se puede omitir. */
private const val STEP_LOCAL = 0

/** OneDrive. También se puede omitir, pero no si el paso anterior quedó vacío. */
private const val STEP_CLOUD = 1

/** Tope de descargas. Solo existe si se conectó una fuente de NUBE. */
private const val STEP_STORAGE = 2

/** Notificaciones: explica PARA QUÉ antes de pedirlas. Solo si faltan (Android 13+). */
private const val STEP_NOTIFICATIONS = 3

/**
 * Primer arranque (Fase 4): el usuario conecta las fuentes que quiera —OneDrive, una carpeta
 * local, o ambas— antes de entrar en la biblioteca. Ninguna es obligatoria por separado, pero
 * hace falta al menos una: sin fuentes la biblioteca estaría vacía.
 *
 * **Los pasos son DINÁMICOS**: música del teléfono y nube van en pantallas SEPARADAS (tres
 * tarjetas juntas eran demasiado para lo primero que se ve de la app, y mezclaban "lo que ya
 * tengo aquí" con "conectar una cuenta"), más el tope de descargas (solo con nube) y las
 * notificaciones (solo si faltan). Por eso el indicador "Paso N de M" se calcula sobre los pasos
 * ACTIVOS: numerar sobre un total fijo prometería pantallas que para muchos usuarios no existen.
 *
 * Cada paso de fuente se puede OMITIR por separado, pero no se puede terminar sin ninguna: el
 * botón del último paso de fuentes se bloquea si no se eligió nada en ninguno de los dos.
 *
 * Los pasos son estado INTERNO (no rutas del NavHost): son un flujo cerrado que solo tiene
 * sentido completo, y sacarlos al grafo obligaría a cada ruta a conocer las precondiciones de
 * la otra. El back del sistema retrocede de paso mientras haya paso al que volver.
 *
 * **Nada de sync durante el onboarding**: ni conectar OneDrive ni elegir carpeta lanzan escaneo.
 * El único scan del primer arranque lo dispara [onFinish], ya con las fuentes elegidas y el tope
 * decidido (ver `MusicPlayerScreen` y `SourcesViewModel.addLocalFolder`).
 *
 * Lenguaje M3 Expressive: hero con forma `MaterialShapes` (misma familia que el shape reveal
 * del NowPlaying y el badge de Favoritos), tipografía display y botón Expressive real.
 *
 * El estado de la sesión de OneDrive llega por parámetro desde MainActivity (única instancia de
 * `AuthViewModel`); lo local lo maneja [SourcesViewModel].
 */
// ExperimentalMaterial3ExpressiveApi: los specs del MotionScheme que animan el cambio de paso.
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OnboardingScreen(
    isLoggedIn: Boolean,
    authLoading: Boolean,
    authError: String?,
    onConnectOneDrive: (Activity) -> Unit,
    onDisconnectOneDrive: () -> Unit,
    onFinish: () -> Unit,
    viewModel: SourcesViewModel = hiltViewModel(),
    syncViewModel: SyncViewModel = hiltViewModel()
) {
    val localFolderUris by viewModel.localFolderUris.collectAsStateWithLifecycle()
    val scanWholeDevice by viewModel.scanWholeDevice.collectAsStateWithLifecycle()
    val oneDriveFolder by viewModel.oneDriveFolderPath.collectAsStateWithLifecycle()

    // "Hay nube" = la sesión de OneDrive. Aquí SÍ se usa la sesión y no el registro de fuentes
    // (`SourcesViewModel.hasCloudSource`, hoy también reactivo) porque son cosas distintas en
    // esta pantalla: lo que gobierna los pasos es la cuenta que el usuario acaba de conectar
    // AQUÍ, y `isLoggedIn` además distingue el "todavía no lo sé" del arranque.
    val hasCloudSource = isLoggedIn

    // Cada paso de fuente se puede omitir, pero terminar sin NINGUNA dejaría una biblioteca
    // vacía sin salida (y `MusicPlayerScreen` devolvería al usuario aquí mismo).
    val hasAnySource = isLoggedIn || scanWholeDevice || localFolderUris.isNotEmpty()

    // Volver directo al paso del tope si una sesión anterior lo dejó pendiente: el usuario ya
    // conectó su nube y lo único que falta es esa decisión. `rememberSaveable` cubre la rotación;
    // el valor inicial cubre la muerte del proceso, que es justo el caso que persiste el flag.
    var step by rememberSaveable {
        mutableIntStateOf(if (hasCloudSource && viewModel.storageStepPending) STEP_STORAGE else STEP_LOCAL)
    }

    // ¿Hace falta el paso de notificaciones? Se decide UNA vez, al entrar: si se reevaluara,
    // conceder el permiso desde ese mismo paso haría desaparecer la pantalla bajo los pies del
    // usuario. Antes de Android 13 el permiso no existe y el paso no aparece nunca.
    val context = LocalContext.current
    val needsNotificationStep = rememberSaveable {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
    }

    // Pasos que de verdad aplican a ESTE usuario, en orden. De aquí salen tanto la navegación
    // como la numeración, así que no pueden desincronizarse.
    val activeSteps = buildList {
        add(STEP_LOCAL)
        add(STEP_CLOUD)
        if (hasCloudSource) add(STEP_STORAGE)
        if (needsNotificationStep) add(STEP_NOTIFICATIONS)
    }
    val stepIndex = activeSteps.indexOf(step).coerceAtLeast(0)

    // Conectar la nube deja el tope pendiente HASTA que se pulse el botón final. Si el proceso
    // muere en medio, el próximo arranque vuelve acá en vez de entrar a la biblioteca.
    LaunchedEffect(hasCloudSource) {
        if (hasCloudSource) {
            viewModel.markStorageStepPending()
        } else {
            // Sin nube, el paso del tope no tiene objeto: deja de pender y, si el usuario estaba
            // en él (acaba de desconectar), se vuelve a fuentes.
            viewModel.clearStorageStepPending()
            if (step == STEP_STORAGE) step = STEP_CLOUD
        }
    }
    BackHandler(enabled = stepIndex > 0) { step = activeSteps[stepIndex - 1] }

    // Terminar el onboarding cierra la decisión pendiente, venga del botón del paso que venga.
    val finishOnboarding = {
        viewModel.clearStorageStepPending()
        onFinish()
    }

    // Avanzar al siguiente paso aplicable; si no queda ninguno, este ERA el final.
    val advance = {
        val next = activeSteps.getOrNull(stepIndex + 1)
        if (next != null) step = next else finishOnboarding()
    }

    // Cambiar de paso mueve TODA la región de contenido: es una transición (shared axis), no un
    // componente, así que va con easing + duración del spec y no con springs. Ver Motion.kt.
    // El movimiento va por el token SPATIAL y las opacidades por los de EFFECTS, cada uno con su
    // duración: es como el spec define el shared axis y la razón de que sean tres specs y no uno.
    val forwardSpatial = tween<IntOffset>(SCREEN_ENTER_MS, easing = ScreenEnterEasing)
    val enterFade = tween<Float>(EXPRESSIVE_DEFAULT_EFFECTS_MS, easing = ExpressiveDefaultEffectsEasing)
    val exitFade = tween<Float>(EXPRESSIVE_FAST_EFFECTS_MS, easing = ExpressiveFastEffectsEasing)

    AnimatedContent(
        targetState = step,
        // Patrón *forward and backward* del spec, el mismo que el `NavHost`: el botón de "siguiente"
        // navega entre niveles consecutivos del flujo, así que se comporta igual que abrir un
        // detalle. Avanzar entra desde la derecha y retroceder desde la izquierda.
        //
        // Las DOS caras se desplazan el mismo tercio ([SCREEN_SLIDE_DIVISOR], compartido con la
        // navegación) y en el mismo sentido: es lo que lo convierte en un shared axis en vez de una
        // capa tapando a otra. Hasta el 30 jul la saliente solo se DESVANECÍA, sin moverse, así que
        // el eje lo recorría una sola de las dos.
        transitionSpec = {
            val forward = targetState > initialState
            val enterFrom: (Int) -> Int = { w ->
                if (forward) w / SCREEN_SLIDE_DIVISOR else -w / SCREEN_SLIDE_DIVISOR
            }
            val exitTo: (Int) -> Int = { w ->
                if (forward) -w / SCREEN_SLIDE_DIVISOR else w / SCREEN_SLIDE_DIVISOR
            }
            (slideInHorizontally(forwardSpatial, enterFrom) + fadeIn(enterFade))
                .togetherWith(slideOutHorizontally(forwardSpatial, exitTo) + fadeOut(exitFade))
        },
        label = "onboarding_step"
    ) { currentStep ->
        // Numeración del paso que se está COMPONIENDO, no del actual: durante la transición
        // conviven ambas pantallas, y con el índice de fuera la saliente mostraría el número
        // de la entrante mientras se va.
        val index = activeSteps.indexOf(currentStep).coerceAtLeast(0)
        // Los DOS NÚMEROS y no la frase ya formateada: con ellos el encabezado puede pintar
        // además la barra de avance (ver [OnboardingStep]). Con un solo paso no hay progreso
        // que enseñar y va null, igual que antes.
        val progress = if (activeSteps.size > 1) StepProgress(index, activeSteps.size) else null
        val isLast = index == activeSteps.lastIndex

        when (currentStep) {
            STEP_NOTIFICATIONS -> NotificationsStep(
                hasCloudSource = hasCloudSource,
                stepProgress = progress,
                // Conceda o no, el onboarding termina: el permiso no condiciona nada.
                onDecided = finishOnboarding,
                onBack = { activeSteps.getOrNull(index - 1)?.let { step = it } }
            )
            STEP_STORAGE -> StorageStep(
                syncViewModel = syncViewModel,
                stepProgress = progress,
                isLastStep = isLast,
                onBack = { step = STEP_CLOUD },
                onContinue = advance
            )
            STEP_CLOUD -> CloudSourceStep(
                isLoggedIn = isLoggedIn,
                authLoading = authLoading,
                authError = authError,
                // Con nube conectada siempre viene el paso del tope, así que aquí nunca es el
                // final; sin ella, este paso cierra el bloque de fuentes y por eso exige que se
                // haya elegido algo en alguno de los dos.
                hasAnySource = hasAnySource,
                stepProgress = progress,
                isLastStep = isLast,
                onConnectOneDrive = onConnectOneDrive,
                onDisconnectOneDrive = onDisconnectOneDrive,
                oneDriveFolder = oneDriveFolder,
                // scanNow = false: en el onboarding el escaneo lo dispara "Empezar" con todo ya
                // configurado, igual que al añadir una carpeta local.
                onChangeOneDriveFolder = { viewModel.setOneDriveFolder(it, scanNow = false) },
                onBack = { step = STEP_LOCAL },
                onContinue = advance
            )
            else -> LocalSourcesStep(
                localFolderUris = localFolderUris,
                scanWholeDevice = scanWholeDevice,
                audioPermission = viewModel.audioPermission,
                hasAudioPermission = viewModel::hasAudioPermission,
                stepProgress = progress,
                onFolderPicked = { viewModel.addLocalFolder(it, scanNow = false) },
                onRemoveFolder = viewModel::removeLocalFolder,
                onScanWholeDeviceChange = { viewModel.setScanWholeDevice(it, scanNow = false) },
                onContinue = advance
            )
        }
    }
}

/**
 * Primer paso: la música que YA está en el teléfono, en sus dos formas. Va antes que la nube
 * porque la app es local-first y porque no exige cuenta, red ni esperar a nada.
 *
 * No pide permisos por adelantado: solo el escaneo del dispositivo necesita uno, y lo pide su
 * propia tarjeta al activarse.
 *
 * Siempre se puede seguir: quien no tenga música en el teléfono continúa a la nube, y el botón
 * lo dice ("Omitir" en vez de "Continuar") para que no parezca que falta rellenar algo.
 */
@Composable
private fun LocalSourcesStep(
    localFolderUris: Set<String>,
    scanWholeDevice: Boolean,
    audioPermission: String,
    hasAudioPermission: () -> Boolean,
    stepProgress: StepProgress?,
    onFolderPicked: (String) -> Unit,
    onRemoveFolder: (String) -> Unit,
    onScanWholeDeviceChange: (Boolean) -> Unit,
    onContinue: () -> Unit
) {
    val hasLocal = scanWholeDevice || localFolderUris.isNotEmpty()

    val activateDeviceScan = rememberDeviceScanActivator(
        permission = audioPermission,
        hasPermission = hasAudioPermission,
        onActivate = { onScanWholeDeviceChange(true) }
    )

    OnboardingStep(
        icon = "library_music",
        title = stringResource(R.string.onboarding_local_title),
        subtitle = stringResource(R.string.onboarding_local_subtitle),
        stepProgress = stepProgress
    ) {
        // Una sola tarjeta con las dos formas de la música local (escanear todo · añadir carpeta):
        // en Ajustes van separadas, pero para el primer arranque son una única decisión.
        PhoneMusicSourceCard(
            scanWholeDevice = scanWholeDevice,
            folderUris = localFolderUris,
            onScanWholeDevice = activateDeviceScan,
            onDisableScan = { onScanWholeDeviceChange(false) },
            onFolderPicked = onFolderPicked,
            onRemoveFolder = onRemoveFolder
        )

        Spacer(modifier = Modifier.height(36.dp))

        OnboardingPrimaryButton(
            label = stringResource(
                if (hasLocal) R.string.onboarding_continue else R.string.onboarding_skip
            ),
            enabled = true,
            onClick = onContinue
        )
    }
}

/**
 * Segundo paso: OneDrive, también opcional.
 *
 * Es el que cierra el bloque de fuentes, así que aquí sí se exige haber elegido ALGO —en este
 * paso o en el anterior—: terminar sin ninguna fuente dejaría una biblioteca vacía y
 * `MusicPlayerScreen` devolvería al usuario a este mismo onboarding.
 */
@Composable
private fun CloudSourceStep(
    isLoggedIn: Boolean,
    authLoading: Boolean,
    authError: String?,
    hasAnySource: Boolean,
    stepProgress: StepProgress?,
    isLastStep: Boolean,
    onConnectOneDrive: (Activity) -> Unit,
    onDisconnectOneDrive: () -> Unit,
    oneDriveFolder: String,
    onChangeOneDriveFolder: (String) -> Unit,
    onBack: () -> Unit,
    onContinue: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity

    // Sin advertencia: en el onboarding no hay biblioteca que perder todavía.
    val changeFolder = rememberOneDriveFolderChanger(warnBeforeChange = false, onApply = onChangeOneDriveFolder)

    OnboardingStep(
        icon = "cloud",
        title = stringResource(R.string.onboarding_cloud_title),
        subtitle = stringResource(R.string.onboarding_cloud_subtitle),
        stepProgress = stepProgress
    ) {
        OneDriveSourceCard(
            isConnected = isLoggedIn,
            isLoading = authLoading,
            onConnect = { activity?.let(onConnectOneDrive) },
            onDisconnect = onDisconnectOneDrive,
            folderPath = oneDriveFolder,
            // Solo tiene sentido elegir carpeta con la cuenta ya conectada: el explorador
            // necesita el token para listar nada.
            onChangeFolder = if (isLoggedIn) changeFolder else null
        )

        if (authError != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(16.dp)
                ) {
                    MaterialSymbol("error", color = MaterialTheme.colorScheme.onErrorContainer, size = 20.sp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.common_error_format, authError),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(36.dp))

        // "Empezar" manda sobre todo lo demás: si este es el final, el botón no puede decir
        // "Omitir" (sugeriría que queda algo por delante). Sin conectar y con pasos detrás,
        // avanzar ES omitir, y decirlo evita que parezca que falta rellenar algo.
        OnboardingPrimaryButton(
            label = stringResource(
                when {
                    isLastStep -> R.string.onboarding_start
                    !isLoggedIn -> R.string.onboarding_skip
                    else -> R.string.onboarding_continue
                }
            ),
            enabled = hasAnySource,
            onClick = onContinue
        )

        if (!hasAnySource) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.onboarding_need_source),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }

        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.common_back))
        }
    }
}

/** Paso solo con nube: cuánto espacio puede ocupar el audio descargado. */
@Composable
private fun StorageStep(
    syncViewModel: SyncViewModel,
    stepProgress: StepProgress?,
    isLastStep: Boolean,
    onBack: () -> Unit,
    onContinue: () -> Unit
) {
    // El tope se persiste en DataStore, así que esta instancia de ViewModel (propia del
    // backstack entry) y la de Ajustes leen y escriben exactamente el mismo valor.
    val storageLimitGb by syncViewModel.storageLimitGb.collectAsStateWithLifecycle()

    OnboardingStep(
        icon = "storage",
        title = stringResource(R.string.onboarding_storage_title),
        subtitle = stringResource(R.string.onboarding_storage_subtitle),
        stepProgress = stepProgress
    ) {
        StorageLimitCard(
            limitGb = storageLimitGb,
            onLimitChangeFinished = syncViewModel::setStorageLimitGb
        )

        Spacer(modifier = Modifier.height(36.dp))

        OnboardingPrimaryButton(
            label = stringResource(
                if (isLastStep) R.string.onboarding_start else R.string.onboarding_continue
            ),
            enabled = true,
            onClick = onContinue
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Volver a fuentes: el mismo destino que el back del sistema, visible para quien no usa
        // gestos (y para el usuario que llega aquí y se acuerda de que le falta la carpeta local).
        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.onboarding_back_to_sources))
        }
    }
}

/**
 * Último paso: explica PARA QUÉ sirven las notificaciones antes de que aparezca el diálogo del
 * sistema, que por sí solo no dice nada ("¿Permitir notificaciones?" invita a decir que no).
 *
 * Son dos usos distintos y conviene separarlos, porque el primero sorprende: la notificación del
 * reproductor NO es cosa de la nube — es la que trae los controles a la barra de estado y a la
 * pantalla de bloqueo, y la quiere igual quien solo escucha música del teléfono. El aviso de
 * progreso sí es de la nube, y por eso solo se menciona si hay una conectada.
 *
 * Se puede saltar: el permiso no condiciona nada de la app, así que negarlo (o posponerlo) tiene
 * que dejar terminar el onboarding igual.
 */
@Composable
private fun NotificationsStep(
    hasCloudSource: Boolean,
    stepProgress: StepProgress?,
    onDecided: () -> Unit,
    onBack: () -> Unit
) {
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { onDecided() }

    OnboardingStep(
        icon = "notifications_active",
        title = stringResource(R.string.onboarding_notifications_title),
        subtitle = stringResource(R.string.onboarding_notifications_subtitle),
        stepProgress = stepProgress
    ) {
        NotificationUseRow(
            icon = "play_circle",
            title = stringResource(R.string.onboarding_notif_player_title),
            description = stringResource(R.string.onboarding_notif_player_desc)
        )

        if (hasCloudSource) {
            Spacer(modifier = Modifier.height(12.dp))
            NotificationUseRow(
                icon = "cloud_download",
                title = stringResource(R.string.onboarding_notif_sync_title),
                description = stringResource(R.string.onboarding_notif_sync_desc)
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = stringResource(R.string.onboarding_notif_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(28.dp))

        OnboardingPrimaryButton(
            label = stringResource(R.string.onboarding_notif_allow),
            enabled = true,
            onClick = {
                // TIRAMISU garantizado: sin él este paso ni siquiera está en la lista.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    onDecided()
                }
            }
        )

        Spacer(modifier = Modifier.height(4.dp))

        TextButton(onClick = onDecided, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.onboarding_notif_skip))
        }

        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.common_back))
        }
    }
}

/** Una fila "icono + qué hace" de la explicación de notificaciones. Contenedor tonal sólido. */
@Composable
private fun NotificationUseRow(icon: String, title: String, description: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    MaterialSymbol(
                        icon = icon,
                        size = 26.sp,
                        fill = true,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Andamiaje común de un paso: hero con forma Expressive, título, subtítulo, indicador opcional
 * y el contenido propio del paso. Los pasos solo aportan lo que cambia.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun OnboardingStep(
    icon: String,
    title: String,
    subtitle: String,
    stepProgress: StepProgress?,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Hero: icono sobre una cookie Expressive (la misma familia de formas que el shape
        // reveal de la carátula del NowPlaying — es lo primero que se ve de la app).
        Surface(
            shape = MaterialShapes.Cookie12Sided.toShape(),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(120.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                MaterialSymbol(
                    icon = icon,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    size = 56.sp,
                    opticalSize = 48,
                    fill = true
                )
            }
        }

        if (stepProgress != null) {
            Spacer(modifier = Modifier.height(20.dp))
            // Barra de avance + "Paso N de M". La frase sola obliga a hacer la cuenta para saber
            // cuánto falta; la barra sola no dice cuántas pantallas quedan. Van juntas porque
            // responden a preguntas distintas, y el texto es además lo que lee el lector de
            // pantalla (la barra va marcada como decorativa para no anunciar el mismo dato dos
            // veces).
            //
            // `LinearWavyProgressIndicator` y no el clásico: criterio unificado de la app — todo
            // indicador LINEAL de la app es el ondulado. Determinado, porque aquí el total se
            // conoce.
            LinearWavyProgressIndicator(
                progress = { stepProgress.fraction },
                // Onda CONGELADA. El default de la variante determinada es
                // `waveSpeed = wavelength`, o sea un frame por vsync mientras la pantalla viva —
                // y el onboarding se queda ahí quieto todo el tiempo que el usuario tarde en
                // decidir. Es la regla de "cero productores continuos de frames" de la app; la
                // forma ondulada se conserva, que es lo que aporta el componente aquí.
                waveSpeed = 0.dp,
                modifier = Modifier
                    .width(OnboardingProgressWidth)
                    .clearAndSetSemantics {}
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(
                    R.string.onboarding_step,
                    stepProgress.index + 1,
                    stepProgress.count
                ),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
        } else {
            Spacer(modifier = Modifier.height(28.dp))
        }

        Text(
            text = title,
            style = MaterialTheme.typography.displaySmallEmphasized,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(36.dp))

        content()

        Spacer(modifier = Modifier.height(16.dp))
    }
}

/**
 * Botón Expressive REAL (shape-morph al presionar), como los de las pantallas de detalle.
 *
 * **Es un botón de tamaño MEDIUM del spec, no un botón normal estirado a un alto inventado.**
 * M3 Expressive define cinco tamaños (32 / 40 / 56 / 96 / 136 dp) y de cada uno DERIVA su forma,
 * su forma al presionar, su padding y el tamaño de su icono; aquí había un `.height(60.dp)` a
 * mano, que no es ninguno de los cinco y dejaba el resto de las medidas en las del tamaño por
 * defecto (40dp) dentro de una caja más alta. Ahora el alto es el único valor que se elige y
 * `shapesFor` / `contentPaddingFor` / `iconSizeFor` sacan de él todo lo demás.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun OnboardingPrimaryButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val height = ButtonDefaults.MediumContainerHeight
    Button(
        onClick = onClick,
        enabled = enabled,
        shapes = ButtonDefaults.shapesFor(height),
        contentPadding = ButtonDefaults.contentPaddingFor(height),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = height)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMediumEmphasized
        )
        Spacer(modifier = Modifier.width(ButtonDefaults.iconSpacingFor(height)))
        // Sin color explícito: hereda el LocalContentColor del Button, que se atenúa solo cuando
        // está deshabilitado (con onPrimary fijo, la flecha seguía brillante sobre el texto gris).
        //
        // `iconSizeFor` devuelve Dp y `MaterialSymbol` mide en sp, que es la convención de toda la
        // app (sus glifos escalan con la tipografía del sistema). El número es el mismo; se toma de
        // ahí para que siga al alto del botón en vez de quedar clavado como estaba (22.sp).
        MaterialSymbol("arrow_forward", size = ButtonDefaults.iconSizeFor(height).value.sp)
    }
}
