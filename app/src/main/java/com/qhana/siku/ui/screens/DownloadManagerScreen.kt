package com.qhana.siku.ui.screens

import android.text.format.Formatter
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.qhana.siku.R
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.qhana.siku.data.coordinator.SyncStatus
import com.qhana.siku.data.repository.FailedDownload
import com.qhana.siku.ui.components.ComponentConfig
import com.qhana.siku.ui.components.ConnectedChoiceGroup
import com.qhana.siku.ui.components.DownloadStateBanner
import com.qhana.siku.ui.components.MaterialSymbol
import com.qhana.siku.ui.components.SongItem
import com.qhana.siku.ui.components.rememberListItemShape
import com.qhana.siku.ui.model.toUiModel
import com.qhana.siku.ui.viewmodel.StorageUsage
import com.qhana.siku.ui.viewmodel.SyncViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.qhana.siku.ui.theme.EXPRESSIVE_DEFAULT_EFFECTS_MS
import com.qhana.siku.ui.theme.EXPRESSIVE_FAST_EFFECTS_MS
import com.qhana.siku.ui.theme.ExpressiveDefaultEffectsEasing
import com.qhana.siku.ui.theme.ExpressiveFastEffectsEasing
import com.qhana.siku.ui.theme.SCREEN_ENTER_MS
import com.qhana.siku.ui.theme.ScreenEnterEasing
import androidx.compose.animation.core.tween

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DownloadManagerScreen(
    onBackClick: () -> Unit,
    viewModel: SyncViewModel = hiltViewModel()
) {
    val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()
    val activeDownloads by viewModel.activeDownloads.collectAsStateWithLifecycle()
    val failedDownloads by viewModel.failedDownloads.collectAsStateWithLifecycle()
    val controlState by viewModel.downloadControlState.collectAsStateWithLifecycle()
    val downloadBanner by viewModel.downloadBanner.collectAsStateWithLifecycle()
    val storageUsage by viewModel.storageUsage.collectAsStateWithLifecycle()

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val tabs = listOf(
        stringResource(R.string.download_tab_active, activeDownloads.size),
        stringResource(R.string.download_tab_failed, failedDownloads.size)
    )

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            // Sin subtitle: el progreso de la cola vive en la tarjeta de resumen (con su barra y
            // el consumo frente al tope). Repetirlo en la barra era el mismo dato dos veces.
            TopAppBar(
                title = { Text(stringResource(R.string.download_title)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        MaterialSymbol("arrow_back")
                    }
                },
                actions = {
                    // Pausar/Reanudar según el estado; Detener solo cuando está activo.
                    if (controlState == com.qhana.siku.data.model.DownloadControlState.ACTIVE) {
                        val pauseDesc = stringResource(R.string.download_pause)
                        IconButton(
                            onClick = { viewModel.pauseDownloads() },
                            modifier = Modifier.semantics { contentDescription = pauseDesc }
                        ) { MaterialSymbol("pause") }
                        val stopDesc = stringResource(R.string.download_stop)
                        IconButton(
                            onClick = { viewModel.stopDownloads() },
                            modifier = Modifier.semantics { contentDescription = stopDesc }
                        ) { MaterialSymbol("stop") }
                    } else {
                        val resumeDesc = stringResource(R.string.download_resume)
                        IconButton(
                            onClick = { viewModel.resumeDownloads() },
                            modifier = Modifier.semantics { contentDescription = resumeDesc }
                        ) { MaterialSymbol("play_arrow") }
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Aviso de pausa/detenido (persistente, mismo banner que el home): explica por qué
            // no se descarga y ofrece reanudar / cancelar.
            downloadBanner?.let { banner ->
                DownloadStateBanner(
                    control = banner.control,
                    pending = banner.pending,
                    onResume = { viewModel.resumeDownloads() },
                    onDismiss = { viewModel.dismissDownloadBanner() },
                    onMute = { viewModel.muteDownloadBanner() }
                )
            }

            // Connected button group: el segmentado dejó de recomendarse en M3 Expressive. Los
            // colores custom que llevaba aquí desaparecen a propósito — eran el tonal por defecto
            // escrito a mano, que es justo lo que da `tonalToggleButtonColors()`.
            ConnectedChoiceGroup(
                options = tabs.indices.toList(),
                selected = selectedTabIndex,
                onSelect = { selectedTabIndex = it },
                labelFor = { tabs[it] },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            Box(modifier = Modifier.weight(1f)) {
                // Cambiar de pestaña reemplaza toda la región: transición (shared axis vertical),
                // así que easing + duración del spec y no springs. Antes iba con los defaults de
                // compose-animation, que no leen nada del tema.
                val tabSlide = tween<IntOffset>(SCREEN_ENTER_MS, easing = ScreenEnterEasing)
                val tabFadeIn = tween<Float>(EXPRESSIVE_DEFAULT_EFFECTS_MS, easing = ExpressiveDefaultEffectsEasing)
                val tabFadeOut = tween<Float>(EXPRESSIVE_FAST_EFFECTS_MS, easing = ExpressiveFastEffectsEasing)
                AnimatedContent(
                    targetState = selectedTabIndex,
                    transitionSpec = {
                        if (targetState > initialState) {
                            (slideInVertically(tabSlide) { height -> height } + fadeIn(tabFadeIn)) togetherWith
                                    (slideOutVertically(tabSlide) { height -> -height } + fadeOut(tabFadeOut))
                        } else {
                            (slideInVertically(tabSlide) { height -> -height } + fadeIn(tabFadeIn)) togetherWith
                                    (slideOutVertically(tabSlide) { height -> height } + fadeOut(tabFadeOut))
                        }
                    },
                    label = "TabTransition"
                ) { targetIndex ->
                    when (targetIndex) {
                        0 -> ActiveDownloadsTab(syncStatus, activeDownloads, storageUsage)
                        1 -> FailedDownloadsTab(
                            failedDownloads = failedDownloads,
                            onRetryAll = { viewModel.retryFailedDownloads() },
                            onRetryOne = { songId -> viewModel.retryDownload(songId) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Resumen ÚNICO de la pestaña Activas: progreso de la cola (si hay descarga en curso) y consumo
 * frente al tope, en la misma tarjeta.
 *
 * **PERSISTENTE a propósito.** Antes la tarjeta entera colgaba de `syncStatus is Downloading`, y
 * ese estado solo lo publica el pipeline mientras corre: al terminar el sync queda en `Complete`
 * (nunca vuelve a `Idle`), así que abrir el gestor con la cola ya drenada —o pausada, o esperando
 * WiFi— dejaba la pantalla sin ningún resumen. El progreso de cola sigue siendo condicional (sin
 * descarga no hay nada que medir), pero el almacenamiento se ve siempre.
 *
 * Los indicadores son LINEALES, así que van con `LinearWavyProgressIndicator` — criterio unificado
 * de la app (los circulares de descarga usan `LoadingIndicator`).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun OverallProgressCard(syncStatus: SyncStatus, usage: StorageUsage?) {
    val downloading = syncStatus as? SyncStatus.Downloading
    // Una biblioteca 100% local no descarga nada: sin cola ni bytes ni tope no hay nada que decir.
    val hasStorageInfo = usage != null && (usage.usedBytes > 0L || usage.hasCap)
    if (downloading == null && !hasStorageInfo) return

    val context = LocalContext.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MaterialSymbol("cloud_download", color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    stringResource(R.string.download_global_progress),
                    style = MaterialTheme.typography.titleMedium
                )
            }

            if (downloading != null) {
                Spacer(modifier = Modifier.height(12.dp))
                // Onda expressive determinada: MISMO indicador que los banners de progreso de la
                // biblioteca (antes aquí era una barra plana clásica).
                LinearWavyProgressIndicator(
                    progress = {
                        if (downloading.total > 0) downloading.current.toFloat() / downloading.total.toFloat()
                        else 0f
                    },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(
                        R.string.download_completed_progress,
                        downloading.current,
                        downloading.total
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (usage != null && hasStorageInfo) {
                if (downloading != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                Spacer(modifier = Modifier.height(16.dp))

                val used = Formatter.formatShortFileSize(context, usage.usedBytes)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.download_storage_section),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = if (usage.hasCap) {
                            stringResource(
                                R.string.download_storage_used_of,
                                used,
                                Formatter.formatShortFileSize(context, usage.capBytes)
                            )
                        } else {
                            stringResource(R.string.download_storage_used_only, used)
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (usage.hasCap) {
                    Spacer(modifier = Modifier.height(12.dp))
                    LinearWavyProgressIndicator(
                        progress = { usage.fraction },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    // Por encima del tope no se dice "quedan -2 GB": se nombra el exceso, que es
                    // lo que el desalojo LRU irá liberando.
                    val remaining = usage.remainingBytes
                    Text(
                        text = if (remaining >= 0L) {
                            stringResource(
                                R.string.download_storage_remaining,
                                Formatter.formatShortFileSize(context, remaining)
                            )
                        } else {
                            stringResource(
                                R.string.download_storage_over_cap,
                                Formatter.formatShortFileSize(context, -remaining)
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// `LoadingIndicator` pasó a exigir opt-in explícito en material3 1.5.0-alpha24.
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ActiveDownloadsTab(
    syncStatus: SyncStatus,
    activeDownloads: List<com.qhana.siku.data.coordinator.ActiveDownload>,
    storageUsage: StorageUsage?
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp)
    ) {
        // Key estable y item SIEMPRE presente: antes la tarjeta se insertaba en la posición 0 solo
        // al empezar a descargar, y LazyColumn ancla el viewport al ítem visible — así que el
        // nuevo item 0 nacía POR ENCIMA del scroll y había que subir a mano para verlo. Estando
        // siempre, al entrar se ve sin tocar nada (y sale de vista solo si el usuario scrollea).
        item(key = "overall_progress") { OverallProgressCard(syncStatus, storageUsage) }

        if (activeDownloads.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp), 
                    contentAlignment = Alignment.Center
                ) {
                    if (syncStatus is SyncStatus.Downloading || syncStatus is SyncStatus.Scanning ||
                        syncStatus is SyncStatus.Preparing
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            LoadingIndicator(modifier = Modifier.size(48.dp))
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(stringResource(R.string.download_syncing), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            MaterialSymbol("check_circle", size = 64.sp, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(stringResource(R.string.download_up_to_date), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        } else {
            // Updated List using rememberListItemShape
            itemsIndexed(activeDownloads, key = { _, item -> item.song.id }) { index, download ->
                val uiModel = remember(download.song) { download.song.toUiModel() }
                
                // Forma dinámica basada en posición
                val shape = rememberListItemShape(index = index, count = activeDownloads.size)

                Surface(
                    shape = shape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    // Esta lista se vacía sola conforme terminan las descargas: es donde el
                    // jump-cut más se notaba.
                    modifier = Modifier
                        .animateItem()
                        .padding(horizontal = 16.dp, vertical = 1.dp)
                ) {
                                                    SongItem(
                                                        song = uiModel,
                                                        isPlaying = false,
                                                        isDownloading = true, // Enable for AlbumArt spinner
                                                        downloadProgress = download.progress,
                                                        showDuration = false,
                                                        showStatusIcon = false, // Keep side spinner hidden
                                                        useRingProgress = true, // aro de progreso alrededor de la carátula
                                                        trailingContent = {                            Text(
                                "${(download.progress * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FailedDownloadsTab(
    failedDownloads: List<FailedDownload>,
    onRetryAll: () -> Unit,
    onRetryOne: (String) -> Unit
) {
    if (failedDownloads.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                MaterialSymbol("check", size = 64.sp, color = MaterialTheme.colorScheme.outline)
                Spacer(modifier = Modifier.height(16.dp))
                Text(stringResource(R.string.download_no_errors), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    } else {
        Column {
            Button(
                onClick = onRetryAll,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                MaterialSymbol("refresh")
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.download_retry_all))
            }

            LazyColumn(
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                itemsIndexed(failedDownloads, key = { _, item -> item.song.id }) { index, failed ->
                    val shape = rememberListItemShape(index = index, count = failedDownloads.size)

                    Surface(
                        shape = shape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier
                            .animateItem()
                            .padding(horizontal = 16.dp, vertical = 1.dp)
                    ) {
                        Column {
                            SongItem(
                                song = failed.song.toUiModel(),
                                isPlaying = false,
                                showStatusIcon = false,
                                trailingContent = {
                                    val retryDesc = stringResource(R.string.download_retry_one)
                                    FilledTonalIconButton(
                                        onClick = { onRetryOne(failed.song.id) },
                                        shapes = IconButtonDefaults.shapes(),
                                        modifier = Modifier
                                            .size(40.dp)
                                            .semantics { contentDescription = retryDesc }
                                    ) {
                                        MaterialSymbol("refresh", size = 20.sp)
                                    }
                                }
                            )
                            FailedDownloadCause(failed)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Diagnóstico de la fila fallida, alineado con el texto del SongItem de arriba:
 * clase de error + causa cruda, y la hora del próximo reintento automático si hay backoff.
 */
@Composable
private fun FailedDownloadCause(failed: FailedDownload) {
    // 12 (padding fila) + 56 (carátula) + 16 (gap) = misma columna de texto que el SongItem.
    val textIndent = 12.dp + ComponentConfig.SongItemIconSize + 16.dp
    val now = System.currentTimeMillis()

    Column(modifier = Modifier.padding(start = textIndent, end = 16.dp, bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MaterialSymbol(
                if (failed.isTransient) "schedule" else "error",
                size = 14.sp,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.width(6.dp))
            val kindLabel = stringResource(
                if (failed.isTransient) R.string.download_error_transient else R.string.download_error_permanent
            )
            val attempts = pluralStringResource(R.plurals.download_attempts, failed.attempts, failed.attempts)
            Text(
                text = "$kindLabel · $attempts",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
        val retryAt = failed.nextRetryAt?.takeIf { it > now }
        val retryText = if (retryAt != null) {
            val time = remember(retryAt) {
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(retryAt))
            }
            stringResource(R.string.download_retry_scheduled, time)
        } else null
        val detail = listOfNotNull(failed.error, retryText).joinToString(" · ")
        if (detail.isNotEmpty()) {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}