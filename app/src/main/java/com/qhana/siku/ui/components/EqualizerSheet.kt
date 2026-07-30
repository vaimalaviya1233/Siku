package com.qhana.siku.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qhana.siku.R
import com.qhana.siku.data.model.EqCustomPreset
import com.qhana.siku.player.audio.AudioRoute
import com.qhana.siku.player.audio.EqCurve
import com.qhana.siku.player.audio.EqualizerAudioProcessor
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import com.qhana.siku.ui.theme.appEffectsSpec
import com.qhana.siku.ui.theme.appShrinkFadeOut
import com.qhana.siku.ui.theme.appExpandFadeIn

/**
 * Presets del EQ. Cada curva se define con 5 ANCLAS en las frecuencias del modo de 5 bandas
 * (valores tomados de los presets clásicos del AudioFx de Android); para el modo de 10
 * bandas se interpola linealmente en espacio log-frecuencia (fuera del rango de anclas se
 * extiende el extremo). Así un mismo preset suena consistente en ambos modos.
 */
internal object EqPresets {

    internal class Preset(val labelRes: Int, val anchors: FloatArray)

    val ALL = listOf(
        Preset(R.string.eq_preset_flat, floatArrayOf(0f, 0f, 0f, 0f, 0f)),
        Preset(R.string.eq_preset_rock, floatArrayOf(5f, 3f, -1f, 3f, 5f)),
        Preset(R.string.eq_preset_pop, floatArrayOf(-1f, 2f, 5f, 1f, -2f)),
        Preset(R.string.eq_preset_jazz, floatArrayOf(4f, 2f, -2f, 2f, 5f)),
        Preset(R.string.eq_preset_classical, floatArrayOf(5f, 3f, -2f, 4f, 4f)),
        Preset(R.string.eq_preset_dance, floatArrayOf(6f, 0f, 2f, 4f, 1f)),
        Preset(R.string.eq_preset_hiphop, floatArrayOf(5f, 3f, 0f, 1f, 3f)),
        Preset(R.string.eq_preset_bass, floatArrayOf(6f, 4f, 1f, 0f, 0f)),
        Preset(R.string.eq_preset_treble, floatArrayOf(0f, 0f, 1f, 4f, 6f)),
        Preset(R.string.eq_preset_vocal, floatArrayOf(-2f, 1f, 4f, 3f, -1f))
    )

    fun gainsFor(preset: Preset, bandCount: Int): FloatArray =
        resample(preset.anchors, EqualizerAudioProcessor.bandsFor(5), bandCount)

    /**
     * Remuestrea una curva [anchors] (definida en las frecuencias [anchorFreqs]) al modo de
     * [bandCount] bandas, interpolando en espacio log-frecuencia. Si las frecuencias destino
     * coinciden con las de origen la curva se conserva exacta. Lo usan tanto los presets de
     * fábrica como los personalizados (guardados en 5 o 10 bandas).
     */
    fun resample(anchors: FloatArray, anchorFreqs: FloatArray, bandCount: Int): FloatArray {
        val target = EqualizerAudioProcessor.bandsFor(bandCount)
        if (target.contentEquals(anchorFreqs)) return anchors.copyOf()
        return FloatArray(target.size) { i -> interpolateLog(target[i], anchorFreqs, anchors) }
    }

    private fun interpolateLog(freq: Float, xs: FloatArray, ys: FloatArray): Float {
        if (freq <= xs.first()) return ys.first()
        if (freq >= xs.last()) return ys.last()
        for (i in 0 until xs.size - 1) {
            if (freq <= xs[i + 1]) {
                val t = (ln(freq) - ln(xs[i])) / (ln(xs[i + 1]) - ln(xs[i]))
                return ys[i] + (ys[i + 1] - ys[i]) * t
            }
        }
        return ys.last()
    }
}

/**
 * Hoja del ecualizador propio: toggle maestro + un slider por banda (±12 dB) + reset.
 * Las ganancias se aplican EN VIVO al processor (onBandChange) y se persisten al soltar
 * (onBandChangeFinished). Mantiene el acceso al ecualizador del sistema como alternativa
 * (algunos usuarios prefieren el de MIUI/fabricante, que además aplica a todo el sistema).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun EqualizerSheet(
    enabled: Boolean,
    bandCount: Int,
    gains: List<Float>,
    bassBoost: Float,
    trebleBoost: Float,
    bassFreq: Double,
    trebleFreq: Double,
    headroomDb: Float,
    preamp: Float,
    suggestedPreampDb: Float,
    limiterEnabled: Boolean,
    limiterThresholdDb: Float,
    limiterThresholdAuto: Boolean,
    gainReductionDb: Float,
    audioRoute: AudioRoute,
    customPresets: List<EqCustomPreset>,
    conflictWarningSuppressed: Boolean,
    onSuppressConflictWarning: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onBandCountChange: (Int) -> Unit,
    onApplyPreset: (FloatArray) -> Unit,
    onApplyCustomPreset: (EqCustomPreset) -> Unit,
    onSaveCurrentAsPreset: (String) -> Unit,
    onDeleteCustomPreset: (String) -> Unit,
    onBandChange: (band: Int, db: Float) -> Unit,
    onBandChangeFinished: () -> Unit,
    onBassBoostChange: (Float) -> Unit,
    onTrebleBoostChange: (Float) -> Unit,
    onBassFreqChange: (Double) -> Unit,
    onTrebleFreqChange: (Double) -> Unit,
    onBoostChangeFinished: () -> Unit,
    onPreampChange: (Float) -> Unit,
    onPreampChangeFinished: () -> Unit,
    onLimiterEnabledChange: (Boolean) -> Unit,
    onLimiterThresholdChange: (Float) -> Unit,
    onLimiterThresholdChangeFinished: () -> Unit,
    onLimiterThresholdAutoChange: (Boolean) -> Unit,
    onReset: () -> Unit,
    onOpenSystemEq: () -> Unit,
    onDismiss: () -> Unit
) {
    val frequencies = EqualizerAudioProcessor.bandsFor(bandCount)
    var showSaveDialog by remember { mutableStateOf(false) }
    // Aviso (no bloqueante) al ENCENDER el EQ propio: Android no permite saber de forma fiable
    // si hay un EQ del sistema/fabricante activo (Xiaomi misound vive fuera de la API pública),
    // así que no se puede bloquear el toggle; en su lugar recordamos que ambos se sumarían.
    // Suprimible con "No volver a mostrar" (persistido).
    var showSystemEqWarning by remember { mutableStateOf(false) }
    // Aviso simétrico al ABRIR el EQ del sistema con el propio encendido (mismo conflicto,
    // sentido contrario): ofrece apagar el propio antes de abrir el panel.
    var showOpenSystemWarning by remember { mutableStateOf(false) }
    // La curva se recalcula en cada frame de arrastre, así que se cachea por los valores que la
    // determinan. `gains` es una List<Float> nueva en cada emisión del ViewModel, de ahí que la
    // clave sea su contenido y no la referencia.
    val response = remember(gains, bandCount, bassBoost, trebleBoost, bassFreq, trebleFreq, preamp) {
        EqCurve.response(
            bandGainsDb = gains.toFloatArray(),
            frequencies = frequencies,
            bassBoostDb = bassBoost,
            trebleBoostDb = trebleBoost,
            bassBoostFreqHz = bassFreq,
            trebleBoostFreqHz = trebleFreq,
            // El preamp DESPLAZA la curva entera, y tiene que verse: si el gráfico ignorara el
            // preamp, moverlo no cambiaría nada en pantalla y parecería un control muerto.
            preampDb = preamp
        )
    }

    // Aportación SOLO de los refuerzos: mismas frecuencias y centros, pero con las bandas a cero y
    // sin preamp (el preamp desplaza el conjunto, no es parte de lo que aporta el refuerzo).
    // null cuando no hay ninguno activo, para no dibujar una recta sobre la línea de 0 dB.
    val boostResponse = remember(bassBoost, trebleBoost, bassFreq, trebleFreq, bandCount) {
        if (bassBoost < EqualizerAudioProcessor.IDENTITY_EPSILON_DB &&
            trebleBoost < EqualizerAudioProcessor.IDENTITY_EPSILON_DB
        ) {
            null
        } else {
            EqCurve.response(
                bandGainsDb = FloatArray(bandCount),
                frequencies = frequencies,
                bassBoostDb = bassBoost,
                trebleBoostDb = trebleBoost,
                bassBoostFreqHz = bassFreq,
                trebleBoostFreqHz = trebleFreq
            )
        }
    }

    // Overlay FULL-SCREEN (ya no es bottom sheet): el EQ es denso (gráfico + 5–10 bandas +
    // refuerzos + acciones), así respira y queda consistente con lyrics/cola. El slide-up + el
    // BackHandler los maneja el caller.
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    IconButton(onClick = onDismiss) { MaterialSymbol("arrow_back") }
                    Spacer(Modifier.width(4.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.eq_title),
                            style = MaterialTheme.typography.titleLarge
                        )
                        // El aviso de que el EQ desactiva el offload (ahorro de batería del DSP)
                        // baja aquí desde su párrafo propio: es una consecuencia de encender el
                        // switch que tiene al lado, y el sitio que ocupaba ahora es el gráfico.
                        Text(
                            text = stringResource(R.string.eq_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = enabled,
                    // Al encender pedimos confirmación (posible doble ecualización con un EQ
                    // del sistema), salvo "No volver a mostrar" ya marcado; al apagar no hay
                    // conflicto posible → pasa directo.
                    onCheckedChange = { checked ->
                        if (checked && !conflictWarningSuppressed) showSystemEqWarning = true
                        else onEnabledChange(checked)
                    }
                )
            }
            Spacer(modifier = Modifier.height(8.dp))

            // Selector de preset (desplegable): colapsa fábrica + personalizados en un solo
            // control. Marca el preset actual (o "Personalizado" si la curva no coincide con
            // ninguno). Los personalizados llevan una X para borrarlos sin aplicarlos.
            //
            // Va ENCIMA del gráfico: elegir preset es lo primero que se hace al entrar, y así la
            // curva queda inmediatamente debajo — se toca el preset y se ve la forma cambiar en el
            // sitio al que ya estabas mirando, sin saltar por encima del control.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(PresetRowGap),
                modifier = Modifier.fillMaxWidth()
            ) {
                PresetSelector(
                    bandCount = bandCount,
                    gains = gains,
                    customPresets = customPresets,
                    enabled = enabled,
                    onApplyPreset = onApplyPreset,
                    onApplyCustomPreset = onApplyCustomPreset,
                    onDeleteCustomPreset = onDeleteCustomPreset,
                    modifier = Modifier.weight(1f)
                )
                // El modo de bandas vive aquí, no dentro de la sección: está acoplado al preset
                // (que se remuestrea al modo activo) y así "Bandas" enseña los sliders sin una
                // segunda fila de control por medio.
                BandCountChip(
                    selected = bandCount,
                    enabled = enabled,
                    onSelect = onBandCountChange
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // GRÁFICO: es lo único que muestra el resultado de todo lo demás junto (bandas +
            // refuerzos). Sustituye a la descripción de texto, que decía en palabras lo que ahora
            // se ve. Queda entre el preset y los controles, o sea entre sus dos causas.
            EqResponseGraph(
                response = response,
                boostResponse = boostResponse,
                headroomDb = headroomDb,
                cautionDb = HEADROOM_CAUTION_DB,
                riskDb = HEADROOM_RISK_DB,
                enabled = enabled
            )

            // El aviso de headroom solo aparece cuando HAY algo que avisar, y va PEGADO al gráfico
            // porque explica lo que se está viendo en él. Antes era una tarjeta permanente al
            // fondo: en estado normal ocupaba sitio para decir "todo bien", y lejos de los
            // controles que mueven el número. Ahora el estado normal lo comunica el color de la
            // curva y esto solo se despliega al cruzar un umbral.
            // Segunda condición: en una ruta con volumen absoluto (Bluetooth) la atenuación
            // digital del mixer no existe, así que CUALQUIER ganancia positiva puede recortar de
            // verdad — y sin el limitador no hay nada que lo impida. Ahí el aviso aparece mucho
            // antes que el umbral normal, porque el umbral normal presupone un margen que en esa
            // ruta no está.
            val routeAtRisk = !limiterEnabled && audioRoute.absoluteVolumeLikely && headroomDb > 0f
            AnimatedVisibility(
                visible = enabled && (headroomDb >= HEADROOM_CAUTION_DB || routeAtRisk),
                enter = appExpandFadeIn(),
                exit = appShrinkFadeOut()
            ) {
                Column {
                    Spacer(modifier = Modifier.height(4.dp))
                    HeadroomIndicator(
                        headroomDb = headroomDb,
                        enabled = enabled,
                        limiterEnabled = limiterEnabled,
                        routeAtRisk = routeAtRisk
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Bandas y refuerzo, LOS DOS a la vez. Hubo un conmutador de sección entre medias, y
            // sobraba: con los sliders verticales el contenido cabe entero en una pantalla, así
            // que dividirlo en dos vistas solo añadía un control y escondía la mitad de la curva
            // que el gráfico de arriba está mostrando junta.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    // Sigue scrolleando por si el aparato es corto o el usuario tiene el texto
                    // grande: lo que se quitó es la división, no la garantía de que quepa.
                    .verticalScroll(rememberScrollState())
            ) {
                BandSliders(
                    frequencies = frequencies,
                    gains = gains,
                    enabled = enabled,
                    onBandChange = onBandChange,
                    onBandChangeFinished = onBandChangeFinished
                )

                Spacer(modifier = Modifier.height(SectionGap))

                // Refuerzos: peakings ANCHOS, no shelves — ver el kdoc del processor. Son ADITIVOS
                // sobre la curva de bandas, y esa suma es justo lo que el gráfico de arriba deja
                // ver mientras se tocan. Cada uno lleva su ganancia y su CENTRO: elegir la
                // frecuencia es lo que evita adivinar dónde poner la energía (80 Hz es punch,
                // 200 Hz cuerpo; 6 kHz brillo, 12 kHz aire).
                Text(
                    text = stringResource(R.string.eq_boost_section),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                BoostSlider(
                    labelRes = R.string.eq_boost_bass,
                    value = bassBoost,
                    enabled = enabled,
                    onValueChange = onBassBoostChange,
                    onValueChangeFinished = onBoostChangeFinished
                )
                FreqSlider(
                    value = bassFreq,
                    minHz = EqualizerAudioProcessor.BASS_BOOST_FREQ_MIN_HZ,
                    maxHz = EqualizerAudioProcessor.BASS_BOOST_FREQ_MAX_HZ,
                    enabled = enabled,
                    onValueChange = onBassFreqChange,
                    onValueChangeFinished = onBoostChangeFinished
                )
                Spacer(modifier = Modifier.height(12.dp))
                BoostSlider(
                    labelRes = R.string.eq_boost_treble,
                    value = trebleBoost,
                    enabled = enabled,
                    onValueChange = onTrebleBoostChange,
                    onValueChangeFinished = onBoostChangeFinished
                )
                FreqSlider(
                    value = trebleFreq,
                    minHz = EqualizerAudioProcessor.TREBLE_BOOST_FREQ_MIN_HZ,
                    maxHz = EqualizerAudioProcessor.TREBLE_BOOST_FREQ_MAX_HZ,
                    enabled = enabled,
                    onValueChange = onTrebleFreqChange,
                    onValueChangeFinished = onBoostChangeFinished
                )

                Spacer(modifier = Modifier.height(SectionGap))

                // PROTECCIÓN DE NIVEL. Va al final porque es consecuencia de todo lo de arriba:
                // primero se decide la curva, después qué hacer con el headroom que consume. Los
                // dos controles responden al mismo problema y NO compiten — el preamp es lineal
                // (distorsión cero) pero cuesta volumen siempre, el limitador no cuesta nada hasta
                // que de verdad hace falta. Ver el kdoc del processor.
                Text(
                    text = stringResource(R.string.eq_protection_section),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                PreampSlider(
                    value = preamp,
                    enabled = enabled,
                    onValueChange = onPreampChange,
                    onValueChangeFinished = onPreampChangeFinished
                )
                // El sugerido solo se ofrece cuando cambiaría algo: con la curva casi plana, o si
                // el usuario ya lo aplicó, un botón que no hace nada es ruido. Que se SUGIERA y no
                // se aplique solo es toda la diferencia con el auto-preamp que se rechazó dos
                // veces — el número se ve y la decisión es del usuario.
                AnimatedVisibility(
                    visible = enabled &&
                        abs(suggestedPreampDb - preamp) >= PREAMP_SUGGESTION_EPSILON_DB,
                    enter = appExpandFadeIn(),
                    exit = appShrinkFadeOut()
                ) {
                    Row(
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        AssistChip(
                            onClick = {
                                onPreampChange(suggestedPreampDb)
                                onPreampChangeFinished()
                            },
                            label = {
                                Text(
                                    stringResource(
                                        R.string.eq_preamp_apply_suggested,
                                        String.format(
                                            Locale.getDefault(), "%+.1f", suggestedPreampDb
                                        )
                                    )
                                )
                            },
                            leadingIcon = { MaterialSymbol(icon = "auto_fix_high", size = 18.sp) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                LimiterCard(
                    limiterEnabled = limiterEnabled,
                    limiterThresholdDb = limiterThresholdDb,
                    limiterThresholdAuto = limiterThresholdAuto,
                    gainReductionDb = gainReductionDb,
                    enabled = enabled,
                    onEnabledChange = onLimiterEnabledChange,
                    onThresholdChange = onLimiterThresholdChange,
                    onThresholdChangeFinished = onLimiterThresholdChangeFinished,
                    onThresholdAutoChange = onLimiterThresholdAutoChange
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Acciones en ButtonGroup Expressive CONECTADO a ancho completo → respeta el padding
            // horizontal del sheet. Formas asimétricas: extremos redondeados hacia afuera +
            // esquinas internas pequeñas (8dp), separación 2dp, squish al presionar.
            // "Restablecer" lleva más peso para no truncarse.
            //
            // En material3 1.5.0-alpha24 el overload SIN overflow DESAPARECIÓ: solo queda este,
            // con `overflowIndicator` obligatorio y un `content` que ya NO es @Composable sino un
            // `ButtonGroupScope` — de ahí que cada botón vaya dentro de `customItem`. Antes había
            // aquí una nota que decía que este overload crasheaba con `fillMaxWidth` (su measure
            // copiaba un maxWidth menor dejando el minWidth fijo). Ya no hay alternativa que
            // elegir; si ese bug siguiera vivo en alpha24, el síntoma sería un crash AL ABRIR esta
            // hoja, y el plan B es un Row con `Arrangement.spacedBy(ConnectedSpaceBetween)`
            // renunciando al squish (`animateWidth` solo existe dentro del scope del grupo).
            val leadingShape = ButtonGroupDefaults.connectedLeadingButtonShape
            val trailingShape = ButtonGroupDefaults.connectedTrailingButtonShape
            val compactPadding = PaddingValues(horizontal = 12.dp)
            ButtonGroup(
                // Los tres botones reparten el ancho por weight, así que no debería desbordar
                // nunca; el indicador existe solo porque el API lo exige.
                overflowIndicator = { menuState ->
                    FilledTonalIconButton(onClick = { menuState.show() }) {
                        MaterialSymbol(icon = "more_horiz", size = 18.sp)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)
            ) {
                // Los tres van TONALES, a la misma jerarquía: guardar un preset no es la acción
                // principal de la hoja (el EQ ya está sonando con lo que se toque), así que
                // resaltarlo con `Button` filled solo desequilibraba el grupo.
                customItem(
                    buttonGroupContent = {
                        val saveSource = remember { MutableInteractionSource() }
                        FilledTonalButton(
                            onClick = { showSaveDialog = true },
                            enabled = enabled,
                            interactionSource = saveSource,
                            shape = leadingShape,
                            contentPadding = compactPadding,
                            modifier = Modifier.weight(1f).animateWidth(saveSource)
                        ) {
                            MaterialSymbol(icon = "save", size = 18.sp)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.eq_preset_save_short), maxLines = 1)
                        }
                    },
                    menuContent = { state ->
                        // Items CLÁSICOS (sin `shape`) a propósito: el contenedor de este
                        // overflow lo pone `ButtonGroup`, que usa un `DropdownMenu` normal, y los
                        // items que él mismo genera tampoco llevan forma. Ver la nota larga en
                        // NowPlayingControls.
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.eq_preset_save_short)) },
                            leadingIcon = { MaterialSymbol(icon = "save") },
                            enabled = enabled,
                            onClick = {
                                showSaveDialog = true
                                state.dismiss()
                            }
                        )
                    }
                )
                customItem(
                    buttonGroupContent = {
                        val resetSource = remember { MutableInteractionSource() }
                        FilledTonalButton(
                            onClick = onReset,
                            enabled = enabled,
                            interactionSource = resetSource,
                            shape = ShapeDefaults.Small,
                            contentPadding = compactPadding,
                            modifier = Modifier.weight(1.25f).animateWidth(resetSource)
                        ) {
                            MaterialSymbol(icon = "restart_alt", size = 18.sp)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.eq_reset), maxLines = 1)
                        }
                    },
                    menuContent = { state ->
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.eq_reset)) },
                            leadingIcon = { MaterialSymbol(icon = "restart_alt") },
                            enabled = enabled,
                            onClick = {
                                onReset()
                                state.dismiss()
                            }
                        )
                    }
                )
                customItem(
                    buttonGroupContent = {
                        val systemSource = remember { MutableInteractionSource() }
                        FilledTonalButton(
                            // Con el EQ propio encendido, abrir el del sistema es el mismo
                            // conflicto de doble ecualización que cubre el aviso del toggle: se
                            // ofrece apagar antes.
                            onClick = { if (enabled) showOpenSystemWarning = true else onOpenSystemEq() },
                            interactionSource = systemSource,
                            shape = trailingShape,
                            contentPadding = compactPadding,
                            modifier = Modifier.weight(1f).animateWidth(systemSource)
                        ) {
                            MaterialSymbol(icon = "tune", size = 18.sp)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.eq_system_short), maxLines = 1)
                        }
                    },
                    menuContent = { state ->
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.eq_system_short)) },
                            leadingIcon = { MaterialSymbol(icon = "tune") },
                            onClick = {
                                if (enabled) showOpenSystemWarning = true else onOpenSystemEq()
                                state.dismiss()
                            }
                        )
                    }
                )
            }
        }
    }

    if (showSystemEqWarning) {
        var dontShowAgain by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showSystemEqWarning = false },
            icon = { MaterialSymbol(icon = "warning", size = 24.sp) },
            title = { Text(stringResource(R.string.eq_system_conflict_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.eq_system_conflict_message))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Checkbox(
                            checked = dontShowAgain,
                            onCheckedChange = { dontShowAgain = it }
                        )
                        Text(
                            text = stringResource(R.string.eq_warning_dont_show),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (dontShowAgain) onSuppressConflictWarning()
                    onEnabledChange(true)
                    showSystemEqWarning = false
                }) {
                    Text(stringResource(R.string.eq_enable_anyway))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSystemEqWarning = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    // Conflicto en sentido contrario: abrir el EQ del sistema con el propio ENCENDIDO.
    // Confirmar apaga el propio y abre; el botón secundario abre sin apagar (el usuario
    // puede querer solo mirar el panel); cancelar = tocar fuera o back.
    if (showOpenSystemWarning) {
        AlertDialog(
            onDismissRequest = { showOpenSystemWarning = false },
            icon = { MaterialSymbol(icon = "warning", size = 24.sp) },
            title = { Text(stringResource(R.string.eq_open_system_conflict_title)) },
            text = { Text(stringResource(R.string.eq_open_system_conflict_message)) },
            confirmButton = {
                TextButton(onClick = {
                    onEnabledChange(false)
                    showOpenSystemWarning = false
                    onOpenSystemEq()
                }) {
                    Text(stringResource(R.string.eq_open_system_disable))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showOpenSystemWarning = false
                    onOpenSystemEq()
                }) {
                    Text(stringResource(R.string.eq_open_system_anyway))
                }
            }
        )
    }

    if (showSaveDialog) {
        SavePresetDialog(
            onConfirm = {
                onSaveCurrentAsPreset(it)
                showSaveDialog = false
            },
            onDismiss = { showSaveDialog = false }
        )
    }
}

/** Modos de banda que ofrece el processor. */
private val BAND_COUNTS = listOf(5, 10)

/** Aire entre el selector de preset y el chip de bandas. */
private val PresetRowGap = 8.dp

/**
 * Chip de modo de bandas, al lado del preset. Comparte componente y lenguaje con él —el mismo
 * contenedor tonal, el mismo chevron que rota— pero en versión compacta y sin etiqueta.
 *
 * Va ARRIBA, junto al preset, y no dentro de la sección de bandas: los dos están acoplados de
 * verdad (un preset se remuestrea al modo activo) y así la sección muestra los sliders
 * directamente, sin una segunda fila de control entre medias.
 */
@Composable
private fun BandCountChip(
    selected: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    TonalDropdownButton(
        value = stringResource(R.string.eq_bands_label, selected),
        enabled = enabled,
        modifier = modifier
    ) { dismiss ->
        BAND_COUNTS.forEachIndexed { index, count ->
            DropdownMenuItem(
                selected = selected == count,
                onClick = {
                    if (selected != count) onSelect(count)
                    dismiss()
                },
                text = { Text(stringResource(R.string.eq_bands_label, count)) },
                shapes = MenuDefaults.itemShape(index = index, count = BAND_COUNTS.size),
                selectedLeadingIcon = { MenuItemIcon("check") }
            )
        }
    }
}

/**
 * Selector de preset desplegable. Detecta si la curva actual coincide (con tolerancia) con un
 * preset de fábrica o propio para marcarlo; si no, muestra "Personalizado". Los presets propios
 * llevan un icono de borrar que no dispara la aplicación (clic aparte).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetSelector(
    bandCount: Int,
    gains: List<Float>,
    customPresets: List<EqCustomPreset>,
    enabled: Boolean,
    onApplyPreset: (FloatArray) -> Unit,
    onApplyCustomPreset: (EqCustomPreset) -> Unit,
    onDeleteCustomPreset: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    fun matches(g: FloatArray) = gains.size == g.size &&
        gains.indices.all { abs(gains[it] - g[it]) < 0.1f }

    val builtInMatch = EqPresets.ALL.firstOrNull { matches(EqPresets.gainsFor(it, bandCount)) }
    val customMatch = customPresets.firstOrNull {
        matches(EqPresets.resample(it.gains, EqualizerAudioProcessor.bandsFor(it.bandCount), bandCount))
    }
    val currentLabel = when {
        builtInMatch != null -> stringResource(builtInMatch.labelRes)
        customMatch != null -> customMatch.name
        else -> stringResource(R.string.eq_preset_custom)
    }

    // Ancla TONAL y no un `TextField` de solo lectura: un campo de texto comunica escritura y
    // arrastra label flotante e indicador inferior, que sobre la superficie del reproductor se
    // leían como un formulario. Ver [TonalDropdownButton].
    // Sin etiqueta ni icono: el valor ("Rock", "Personalizado") ya dice lo que es, y el rótulo
    // "Preset" encima era el resto del text field del que viene este control — dos líneas de texto
    // para un dato de una.
    TonalDropdownButton(
        value = currentLabel,
        enabled = enabled,
        matchAnchorWidth = true,
        fillWidth = true,
        modifier = modifier
    ) { dismiss ->
        // Aplicar un preset es una SELECCIÓN EXCLUSIVA (solo uno puede estar activo, y de
        // hecho `builtInMatch`/`customMatch` ya calculan cuál): sobrecarga `selected`, que lo
        // marca con contenedor propio y morph de forma. El check pasa a `selectedLeadingIcon`
        // en AMBAS listas — antes los de fábrica lo ponían de trailing y los personalizados
        // de leading, así que el mismo estado se señalaba en lados opuestos del mismo menú.
        EqPresets.ALL.forEachIndexed { index, preset ->
            DropdownMenuItem(
                selected = builtInMatch == preset,
                onClick = {
                    onApplyPreset(EqPresets.gainsFor(preset, bandCount))
                    dismiss()
                },
                text = { Text(stringResource(preset.labelRes)) },
                shapes = MenuDefaults.itemShape(index = index, count = EqPresets.ALL.size),
                selectedLeadingIcon = { MenuItemIcon("check") },
                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
            )
        }
        if (customPresets.isNotEmpty()) {
            // Los personalizados son su PROPIO bloque (índices desde 0 otra vez), así que el
            // divisor separa dos grupos con esquinas cerradas en vez de partir uno solo.
            HorizontalDivider(modifier = Modifier.padding(MenuDefaults.HorizontalDividerPadding))
            customPresets.forEachIndexed { index, preset ->
                DropdownMenuItem(
                    selected = customMatch?.id == preset.id,
                    onClick = {
                        onApplyCustomPreset(preset)
                        dismiss()
                    },
                    text = { Text(preset.name) },
                    shapes = MenuDefaults.itemShape(index = index, count = customPresets.size),
                    selectedLeadingIcon = { MenuItemIcon("check") },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                onDeleteCustomPreset(preset.id)
                                dismiss()
                            }
                        ) {
                            MaterialSymbol(
                                icon = "delete",
                                size = 20.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun SavePresetDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.eq_preset_save_title)) },
        text = {
            // FILLED, igual que el selector de presets: los dos campos de esta pantalla siguen la
            // misma variante.
            TextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.eq_preset_name_label)) }
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank()
            ) {
                Text(stringResource(R.string.eq_preset_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}

// Anchos compartidos por TODOS los sliders de la hoja (bandas y refuerzos): la etiqueta y el
// valor tienen que medir lo mismo en todos, o los sliders no arrancan ni terminan a la misma
// altura y la columna se ve torcida. La etiqueta necesita 68dp por "Frecuencia" y el valor 72dp
// por "16.000 Hz".
private val EqLabelWidth = 68.dp
private val EqValueWidth = 72.dp

/**
 * Resolución de los sliders de GANANCIA (bandas, refuerzos, preamp, umbral). Cuantizan por
 * REDONDEO en `onValueChange`, no con el parámetro `steps` del Slider: `steps` dibuja una marca por
 * posición, y con 0,5 dB sobre un rango de ±12 son 47 marcas que se leen como una línea continua y
 * ensucian el control. El imán se conserva igual; lo único que se pierde son los puntitos.
 *
 * El de frecuencia sí usa `steps`, y por el motivo contrario: ahí las paradas son POCAS y verlas es
 * justamente lo que hace que se pueda clavar un valor (ver [THIRD_OCTAVE_HZ]).
 */
private const val GAIN_STEP_DB = 0.5f

/**
 * Paradas del slider de frecuencia de los refuerzos: las frecuencias nominales de TERCIO DE OCTAVA
 * de la **ISO 266** (números preferidos de la serie R10). Cada refuerzo se queda con el tramo que
 * cae dentro de su rango vía [freqStops], así que la lista es una sola para los dos.
 *
 * Dos propiedades que la hacen la rejilla correcta y no una cualquiera:
 *
 *  - **Uniformes en el dominio log** (razón 2^(1/3) ≈ 1.26 entre vecinas, con los redondeos de la
 *    norma dentro de ±1.6%). Esto es lo que permite usar `steps`, que reparte posiciones uniformes
 *    sobre el recorrido: el eje del slider pasa a ser el índice de la parada, y un eje lineal en
 *    índices ES el eje logarítmico en frecuencia — el que hacía falta desde el principio, ahora
 *    por construcción y no con una fórmula aparte.
 *  - **Cada parada es una frecuencia que significa algo**: son las mismas con las que se etiquetan
 *    los ecualizadores (las 10 bandas del modo ISO son un subconjunto, las octavas), así que el
 *    valor que se lee arriba es siempre un número redondo del oficio y no un 4.370 Hz cualquiera.
 *
 * Sustituye a un paso fijo en Hz (10 Hz abajo, 100 arriba). El paso fino era exacto en Hz pero
 * dejaba ~21 posiciones en graves y ~140 en agudos: sin marcas y con esa densidad, el control se
 * sentía continuo y clavar un valor concreto dependía del pulso. Con esta rejilla son 9 paradas en
 * graves y 10 en agudos — visibles, con imán y todas alcanzables sin puntería. Lo que se pierde es
 * el ajuste sub-tercio (ya no hay 45 Hz), que sobre un peaking de 1.92 octavas de ancho no cambia
 * nada audible.
 *
 * Los rangos de los dos refuerzos (40–250 y 2k–16k) empiezan y acaban EN valores de la serie, así
 * que los extremos siguen siendo alcanzables.
 */
private val THIRD_OCTAVE_HZ = doubleArrayOf(
    20.0, 25.0, 31.5, 40.0, 50.0, 63.0, 80.0, 100.0, 125.0, 160.0,
    200.0, 250.0, 315.0, 400.0, 500.0, 630.0, 800.0, 1_000.0, 1_250.0, 1_600.0,
    2_000.0, 2_500.0, 3_150.0, 4_000.0, 5_000.0, 6_300.0, 8_000.0, 10_000.0, 12_500.0, 16_000.0,
    20_000.0
)

/** El tramo de [THIRD_OCTAVE_HZ] que cabe en el rango de un refuerzo. */
private fun freqStops(minHz: Double, maxHz: Double): List<Double> =
    THIRD_OCTAVE_HZ.filter { it in minHz..maxHz }

/**
 * Posición de [hz] en el eje del slider, que es el ÍNDICE de la parada.
 *
 * Interpola en log dentro del tramo en vez de quedarse con la parada más cercana porque el valor
 * que llega puede no estar en la rejilla (uno guardado con el paso viejo en Hz): así el thumb
 * aparece donde de verdad está el valor y salta a la parada al primer arrastre, en lugar de pintar
 * una mentira desde el principio.
 */
private fun freqStopPosition(hz: Double, stops: List<Double>): Float {
    val i = stops.indexOfLast { it <= hz }
    if (i < 0) return 0f
    if (i >= stops.lastIndex) return stops.lastIndex.toFloat()
    return (i + ln(hz / stops[i]) / ln(stops[i + 1] / stops[i])).toFloat()
}

/**
 * Slider de ganancia de un refuerzo (0..MAX_BOOST_DB). Mismo reparto de anchos que las bandas para
 * que las dos secciones queden alineadas en columna.
 */
@Composable
private fun BoostSlider(
    labelRes: Int,
    value: Float,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit
) {
    EqSliderRow(
        label = stringResource(labelRes),
        readout = String.format(Locale.getDefault(), "%+.1f", value),
        enabled = enabled
    ) { modifier ->
        Slider(
            value = value,
            onValueChange = { v -> onValueChange(snapGain(v)) },
            onValueChangeFinished = onValueChangeFinished,
            valueRange = 0f..EqualizerAudioProcessor.MAX_BOOST_DB,
            enabled = enabled,
            modifier = modifier
        )
    }
}

/**
 * Preamp: ganancia global, SOLO negativa (el rango lo fija el processor). Comparte fila y anchos
 * con el resto de sliders de la hoja.
 */
@Composable
private fun PreampSlider(
    value: Float,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit
) {
    EqSliderRow(
        label = stringResource(R.string.eq_preamp),
        readout = String.format(Locale.getDefault(), "%+.1f", value),
        enabled = enabled
    ) { modifier ->
        Slider(
            value = value,
            onValueChange = { v -> onValueChange(snapGain(v)) },
            onValueChangeFinished = onValueChangeFinished,
            valueRange = EqualizerAudioProcessor.PREAMP_MIN_DB..EqualizerAudioProcessor.PREAMP_MAX_DB,
            enabled = enabled,
            modifier = modifier
        )
    }
}

/**
 * Limitador: interruptor, explicación y MEDIDOR de lo que está reduciendo.
 *
 * El medidor no es decoración, es la garantía anti-humo de toda la función: con material normal se
 * queda en cero y el usuario VE que el limitador no está tocando su música. Un procesador que se
 * puede observar sin trabajar es lo contrario de un placebo — y era la objeción de fondo a meter
 * dinámica en una cadena que hasta ahora era estrictamente lineal.
 */
@Composable
private fun LimiterCard(
    limiterEnabled: Boolean,
    limiterThresholdDb: Float,
    limiterThresholdAuto: Boolean,
    gainReductionDb: Float,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onThresholdChange: (Float) -> Unit,
    onThresholdChangeFinished: () -> Unit,
    onThresholdAutoChange: (Boolean) -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.eq_limiter),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.eq_limiter_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = limiterEnabled,
                    onCheckedChange = onEnabledChange,
                    enabled = enabled
                )
            }
            // El umbral solo tiene sentido con el limitador encendido, y cambia lo que ES: en 0 dB
            // es pura protección contra recorte; por debajo, un compresor que solo toca lo que
            // asoma por encima y deja intacto el resto (a diferencia del preamp, que desplaza la
            // señal entera).
            AnimatedVisibility(
                visible = enabled && limiterEnabled,
                enter = appExpandFadeIn(),
                exit = appShrinkFadeOut()
            ) {
                Column {
                    Spacer(Modifier.height(4.dp))
                    // El automático fija el umbral en 0 dBFS (protección true-peak y nada más), así
                    // que el slider sigue MOSTRANDO el valor pero no se puede arrastrar. Vaciarlo o
                    // esconderlo perdería el dato justo cuando es más interesante: ver a qué nivel
                    // decidió entrar. Hasta el 29 jul lo ataba al pico de la curva, y eso lo
                    // volvía un compresor que engancha siempre que hay EQ.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Checkbox(
                            checked = limiterThresholdAuto,
                            onCheckedChange = onThresholdAutoChange,
                            enabled = enabled
                        )
                        Text(
                            text = stringResource(R.string.eq_limiter_threshold_auto),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    EqSliderRow(
                        label = stringResource(R.string.eq_limiter_threshold),
                        readout = String.format(
                            Locale.getDefault(), "%.1f dB", limiterThresholdDb
                        ),
                        enabled = enabled && !limiterThresholdAuto
                    ) { modifier ->
                        Slider(
                            value = limiterThresholdDb,
                            onValueChange = { v -> onThresholdChange(snapGain(v)) },
                            onValueChangeFinished = onThresholdChangeFinished,
                            valueRange = EqualizerAudioProcessor.LIMITER_THRESHOLD_MIN_DB..
                                EqualizerAudioProcessor.LIMITER_THRESHOLD_MAX_DB,
                            enabled = enabled && !limiterThresholdAuto,
                            modifier = modifier
                        )
                    }
                }
            }
            // El medidor se muestra SIEMPRE, esté el limitador encendido o no. Apagado enseña lo
            // que REDUCIRÍA, que es justo el dato con el que se decide encenderlo: esconderlo
            // mientras está apagado obligaba a encenderlo para poder averiguar si hacía falta.
            AnimatedVisibility(
                visible = enabled,
                enter = appExpandFadeIn(),
                exit = appShrinkFadeOut()
            ) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    GainReductionMeter(
                        gainReductionDb = gainReductionDb,
                        active = limiterEnabled
                    )
                }
            }
        }
    }
}

/**
 * Medidor de reducción de ganancia. Barra dibujada a mano y no un indicador de progreso de M3: no
 * es progreso hacia ninguna parte, y darle esa semántica confundiría al lector de pantalla.
 */
@Composable
private fun GainReductionMeter(
    gainReductionDb: Float,
    /** Con el limitador apagado el valor sigue siendo real, pero es hipotético: "reduciría". */
    active: Boolean
) {
    val fraction = (gainReductionDb / LIMITER_METER_RANGE_DB).coerceIn(0f, 1f)
    // Suavizado: el valor se muestrea cada 60 ms y sin animar la barra parpadearía a saltos.
    //
    // Token *effects* aunque lo que cambia sea el ANCHO de la barra, que es la excepción a la regla
    // general de Motion.kt: los tokens spatial rebotan, y un medidor que sobrepasa muestra durante
    // unos frames una reducción que no ocurrió. Un instrumento no puede exagerar su lectura.
    val animated by animateFloatAsState(
        targetValue = fraction,
        animationSpec = appEffectsSpec(),
        label = "eqGainReduction"
    )
    val shape = RoundedCornerShape(MeterHeight / 2)
    // Inactivo va en `outline` y no en `primary`: el número es igual de real, pero no está
    // pasando. Un medidor hipotético pintado con el color de acento se leería como que sí.
    val barColor = if (active) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.outline
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(
                if (active) R.string.eq_limiter_meter else R.string.eq_limiter_meter_would
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(EqLabelWidth)
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(MeterHeight)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(animated)
                    .clip(shape)
                    .background(barColor)
            )
        }
        Text(
            text = if (gainReductionDb <= 0f) {
                stringResource(R.string.eq_limiter_idle)
            } else {
                String.format(Locale.getDefault(), "%.1f dB", -gainReductionDb)
            },
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
            color = if (gainReductionDb > 0f && active) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(EqValueWidth),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            maxLines = 1
        )
    }
}

/**
 * Fondo de escala del medidor. 6 dB de reducción ya es muchísimo para un limitador de seguridad
 * (significa que la curva pide el doble de amplitud de la que cabe); más rango solo aplastaría la
 * parte útil de la barra, que es la de 0 a 2 dB.
 */
private const val LIMITER_METER_RANGE_DB = 6f

private val MeterHeight = 8.dp

/**
 * Diferencia mínima entre el preamp puesto y el sugerido para ofrecer el botón. Coincide con el
 * paso del slider: por debajo de eso el botón no podría cambiar el valor aunque se pulsara.
 */
private const val PREAMP_SUGGESTION_EPSILON_DB = GAIN_STEP_DB

/**
 * Frecuencia central de un refuerzo: slider de paradas DISCRETAS sobre la rejilla de tercios de
 * octava ([THIRD_OCTAVE_HZ]), con las marcas a la vista y el imán del propio componente.
 *
 * El eje del slider es el ÍNDICE de la parada, no la frecuencia. Eso mantiene la escala
 * **logarítmica** que este control necesita —en lineal, con un rango de 2–16 kHz, la mitad inferior
 * del recorrido cubriría solo hasta 9 kHz y el ajuste fino quedaría apelotonado al principio— y
 * además la hace compatible con `steps`, que reparte posiciones uniformes sobre el recorrido: con
 * las paradas ya uniformes en log, uniforme en recorrido es uniforme en proporción de frecuencia.
 * La conversión log explícita que había antes desaparece; el eje ya es logarítmico.
 */
@Composable
private fun FreqSlider(
    value: Double,
    minHz: Double,
    maxHz: Double,
    enabled: Boolean,
    onValueChange: (Double) -> Unit,
    onValueChangeFinished: () -> Unit
) {
    val stops = remember(minHz, maxHz) { freqStops(minHz, maxHz) }
    EqSliderRow(
        label = stringResource(R.string.eq_boost_center),
        // Siempre en Hz enteros: las paradas son valores exactos de la norma y así se leen tal
        // cual ("6.300 Hz"), sin un ".3 kHz" que redondee lo que el control sí puede clavar. OJO
        // si algún día un rango baja de 40 Hz: la única parada no entera de la serie es 31,5 y
        // aquí se leería "32 Hz", con el processor recibiendo 31,5.
        readout = String.format(Locale.getDefault(), "%,d Hz", value.roundToInt()),
        enabled = enabled
    ) { modifier ->
        Slider(
            value = freqStopPosition(value, stops),
            // El componente ya entrega la posición imantada a la marca; el redondeo es la red de
            // seguridad que garantiza que lo que sale de aquí sea SIEMPRE una parada de la rejilla.
            onValueChange = { pos ->
                onValueChange(stops[pos.roundToInt().coerceIn(stops.indices)])
            },
            onValueChangeFinished = onValueChangeFinished,
            valueRange = 0f..(stops.size - 1).toFloat(),
            // `steps` son las marcas INTERMEDIAS: los dos extremos no se cuentan.
            steps = stops.size - 2,
            enabled = enabled,
            modifier = modifier
        )
    }
}

/** Cuantiza una ganancia a [GAIN_STEP_DB]. Ver por qué no se usa `steps` en su kdoc. */
private fun snapGain(db: Float): Float = (db / GAIN_STEP_DB).roundToInt() * GAIN_STEP_DB

/**
 * Fila de bandas con sliders VERTICALES, que es la forma canónica de un ecualizador: el gesto de
 * subir o bajar una banda coincide con lo que hace, y las bandas vecinas quedan una al lado de
 * otra, así que la curva se lee en los propios controles. Apiladas en horizontal —como estaban—
 * no había ninguna relación espacial entre ellas.
 *
 * En modo 10 la fila SCROLLEA en horizontal: diez bandas en el ancho de un teléfono dan ~36dp
 * cada una, por debajo del mínimo táctil de 48dp. En modo 5 caben de sobra y el scroll no llega
 * a activarse.
 */
@Composable
private fun BandSliders(
    frequencies: FloatArray,
    gains: List<Float>,
    enabled: Boolean,
    onBandChange: (band: Int, db: Float) -> Unit,
    onBandChangeFinished: () -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val count = frequencies.size
        // ¿Caben todas a su ancho mínimo? Se decide MIDIENDO, no por el número de bandas: en modo
        // 5 caben de sobra en un teléfono normal (y estirarse queda mucho mejor que dejar un hueco
        // muerto a la derecha), pero en uno estrecho —o con el modo 10 en cualquiera— no entran.
        // Atarlo a `bandCount == 5` daría la respuesta correcta por el motivo equivocado y se
        // rompería en la primera pantalla angosta.
        val fits = BandColumnWidth * count + BandSpacing * (count - 1) <= maxWidth

        Row(
            modifier = Modifier
                .fillMaxWidth()
                // El scroll SOLO cuando hace falta: un Row scrollable se mide con ancho infinito,
                // y ahí `weight` no tiene contra qué repartir.
                .then(if (fits) Modifier else Modifier.horizontalScroll(rememberScrollState())),
            horizontalArrangement = Arrangement.spacedBy(BandSpacing)
        ) {
            frequencies.forEachIndexed { band, hz ->
                BandSlider(
                    label = formatBandLabel(hz),
                    value = gains.getOrNull(band) ?: 0f,
                    enabled = enabled,
                    onValueChange = { onBandChange(band, it) },
                    onValueChangeFinished = onBandChangeFinished,
                    // Repartido cuando cabe (el ancho sobrante se va a las bandas, no a un hueco),
                    // mínimo fijo cuando toca scrollear.
                    modifier = if (fits) Modifier.weight(1f) else Modifier.width(BandColumnWidth)
                )
            }
        }
    }
}

/**
 * Una banda: lectura arriba, slider vertical y frecuencia abajo.
 *
 * `VerticalSlider` trabaja con [SliderState] y no con `value`/`onValueChange`, así que el estado se
 * crea una vez y se SINCRONIZA en cada composición: `state.value = value` refleja lo que venga de
 * fuera (aplicar un preset, un reset), mientras que `onValueChange` publica hacia arriba lo que
 * hace el dedo. Con `onValueChange` asignado, el propio state no se auto-actualiza al arrastrar —
 * el valor vuelve por el camino largo (ViewModel → processor → recomposición), que es lo que
 * mantiene una sola fuente de verdad.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BandSlider(
    label: String,
    value: Float,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    /** Lo decide [BandSliders]: `weight` si caben todas, ancho fijo si la fila scrollea. */
    modifier: Modifier = Modifier
) {
    val state = remember {
        SliderState(
            value = value,
            valueRange = -EqualizerAudioProcessor.MAX_GAIN_DB..EqualizerAudioProcessor.MAX_GAIN_DB
        )
    }
    state.value = value
    state.onValueChange = { onValueChange(snapGain(it)) }
    state.onValueChangeFinished = onValueChangeFinished

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        Text(
            text = String.format(Locale.getDefault(), "%+.1f", value),
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        Spacer(Modifier.height(4.dp))
        VerticalSlider(
            state = state,
            enabled = enabled,
            // De abajo hacia arriba: subir el dedo sube la ganancia. Con el default (top to
            // bottom) el control iría al revés de lo que representa.
            topToBottom = false,
            modifier = Modifier.height(BandSliderHeight)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

/**
 * Ancho MÍNIMO de la columna de una banda, por encima del mínimo táctil de 48dp. Es un piso, no
 * una medida fija: cuando las bandas caben en el ancho disponible se reparten con `weight` y cada
 * una queda más ancha (con 5 en un teléfono normal, ~70dp en vez de 52).
 */
private val BandColumnWidth = 52.dp
private val BandSpacing = 4.dp

/**
 * Recorrido del slider vertical. 150dp sobre un rango de ±12 dB deja ~6dp por dB, así que el paso
 * de 0,5 dB son ~3dp de arrastre — por encima de lo que el dedo resuelve. Bajó desde 200dp al
 * quitar el conmutador de sección: lo que se gana en alto es justo lo que necesita el refuerzo
 * para caber debajo sin scroll.
 */
private val BandSliderHeight = 150.dp

/** Separación entre el bloque de bandas y el de refuerzo. */
private val SectionGap = 24.dp

/** Estructura común de todas las filas: etiqueta, slider elástico y lectura alineada a la derecha. */
@Composable
private fun EqSliderRow(
    label: String,
    readout: String,
    enabled: Boolean,
    slider: @Composable (Modifier) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(EqLabelWidth)
        )
        slider(Modifier.weight(1f))
        Text(
            text = readout,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
            color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(EqValueWidth),
            textAlign = androidx.compose.ui.text.style.TextAlign.End
        )
    }
}

// Umbrales CALIBRADOS con la medición, no elegidos a ojo. La regla que los fija: un preset de
// fábrica por sí solo NUNCA debe avisar — ya compensado, el más agresivo (Bass/Dance) llega a
// +6.04 dB, y si eso pintara en color el indicador se volvería ruido visual que se ignora. El
// aviso tiene que aparecer justo donde empieza el problema real: preset + refuerzo (+11.4 dB),
// que es exactamente la combinación que sonó a ruido en julio.
//
// LIMITACIÓN CONOCIDA (27 jul): esto mide la ganancia de la CURVA, no el nivel de salida — no
// sabe a qué nivel está masterizada la canción. Sobre un máster moderno a 0 dBFS (medido en
// 687 Days: 36 400 muestras ya pegadas al tope) el refuerzo a +6 recorta el 4.93 % de las
// muestras aunque el indicador esté en verde. El aviso es correcto pero incompleto; para que
// dijera la verdad haría falta el nivel real de la pista.

/** Desde aquí la curva satura a volumen alto (~84 % o más). */
private const val HEADROOM_CAUTION_DB = 9f

/** Desde aquí satura en casi cualquier volumen de escucha normal (seguro solo bajo ~76 %). */
private const val HEADROOM_RISK_DB = 14f

/**
 * Cuánto puede ganar la señal sobre su nivel original con la curva actual (bandas + refuerzos).
 * Es INFORMATIVO: no se corrige por detrás. Existe porque lo que hizo que los refuerzos sonaran a
 * ruido en julio no fue la forma de los filtros, sino que se sumaban al preset hasta +12..14 dB
 * sin que nada lo dijera.
 */
@Composable
private fun HeadroomIndicator(
    headroomDb: Float,
    enabled: Boolean,
    limiterEnabled: Boolean,
    /** Ruta con volumen absoluto y sin limitador: ver la condición que decide mostrar esto. */
    routeAtRisk: Boolean
) {
    val risky = headroomDb >= HEADROOM_RISK_DB
    val caution = headroomDb >= HEADROOM_CAUTION_DB
    // El limitador no hace desaparecer el problema —la curva sigue pidiendo más nivel del que
    // cabe— pero sí impide que acabe en recorte, así que rebaja el TONO del aviso en vez de
    // suprimirlo. Ocultarlo del todo mentiría igual que no mostrarlo antes.
    val severe = routeAtRisk || (risky && !limiterEnabled)
    val container = when {
        !enabled -> MaterialTheme.colorScheme.surfaceContainerHigh
        severe -> MaterialTheme.colorScheme.errorContainer
        caution -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val content = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
        severe -> MaterialTheme.colorScheme.onErrorContainer
        caution -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val messageRes = when {
        routeAtRisk -> R.string.eq_headroom_bluetooth
        limiterEnabled && (risky || caution) -> R.string.eq_headroom_limited
        risky -> R.string.eq_headroom_risk
        caution -> R.string.eq_headroom_caution
        else -> R.string.eq_headroom_ok
    }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = container,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            MaterialSymbol(
                icon = if (risky || caution || routeAtRisk) "warning" else "graphic_eq",
                size = 20.sp,
                color = content
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = stringResource(
                        R.string.eq_headroom_label,
                        String.format(Locale.getDefault(), "%+.1f", headroomDb)
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = content
                )
                Text(
                    text = stringResource(messageRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = content
                )
            }
        }
    }
}

private fun formatBandLabel(hz: Float): String = if (hz >= 1000f) {
    val k = hz / 1000f
    if (k % 1f == 0f) String.format(Locale.getDefault(), "%.0f kHz", k)
    else String.format(Locale.getDefault(), "%.1f kHz", k)
} else {
    String.format(Locale.getDefault(), "%.0f Hz", hz)
}
