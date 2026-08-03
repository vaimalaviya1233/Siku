package com.qhana.siku.data.model

/**
 * Configuración COMPLETA del ecualizador: todo lo que hace que un sonido sea ese sonido.
 *
 * Es la unidad que se guarda como preset propio Y la que se recuerda por ruta de salida. Que
 * incluya el preamp y el limitador —y no solo la curva de bandas— es lo que hace útil el perfil
 * por ruta: por Bluetooth con volumen absoluto NO existe la atenuación digital del mixer, así que
 * el ajuste que de verdad cambia al conectar unos cascos no es el timbre sino la protección de
 * nivel (ver el kdoc de `EqualizerAudioProcessor`). Un perfil que solo llevara las ganancias
 * dejaría fuera justo el parámetro que la ruta obliga a cambiar.
 *
 * **El toggle on/off del EQ NO forma parte de esto, a propósito**: cambiarlo reconstruye la
 * pipeline de audio (stop/prepare/seek), así que un perfil que lo apagara produciría un corte
 * audible al conectar el dispositivo. Encender o apagar el ecualizador es una decisión global, no
 * una propiedad del sonido guardado.
 *
 * [bassFreqHz]/[trebleFreqHz] son nullable con el mismo criterio que en `MusicPreferences`: null
 * significa "el default del processor", y el default vive en la capa `player` — este módulo es
 * Kotlin puro y no puede importarla. [limiterThresholdDb] es null por lo mismo.
 */
data class EqSettings(
    val bandCount: Int,
    val gains: FloatArray,
    val bassBoostDb: Float = 0f,
    val trebleBoostDb: Float = 0f,
    val bassFreqHz: Double? = null,
    val trebleFreqHz: Double? = null,
    val preampDb: Float = 0f,
    val limiterEnabled: Boolean = false,
    val limiterThresholdDb: Float? = null,
    val limiterThresholdAuto: Boolean = true
) {
    // equals/hashCode explícitos: FloatArray usa identidad por defecto y rompería el diffing de
    // Compose y los `remember` por preset (mismo motivo que en [EqCustomPreset]).
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EqSettings) return false
        return bandCount == other.bandCount &&
            gains.contentEquals(other.gains) &&
            bassBoostDb == other.bassBoostDb &&
            trebleBoostDb == other.trebleBoostDb &&
            bassFreqHz == other.bassFreqHz &&
            trebleFreqHz == other.trebleFreqHz &&
            preampDb == other.preampDb &&
            limiterEnabled == other.limiterEnabled &&
            limiterThresholdDb == other.limiterThresholdDb &&
            limiterThresholdAuto == other.limiterThresholdAuto
    }

    override fun hashCode(): Int {
        var result = bandCount
        result = 31 * result + gains.contentHashCode()
        result = 31 * result + bassBoostDb.hashCode()
        result = 31 * result + trebleBoostDb.hashCode()
        result = 31 * result + (bassFreqHz?.hashCode() ?: 0)
        result = 31 * result + (trebleFreqHz?.hashCode() ?: 0)
        result = 31 * result + preampDb.hashCode()
        result = 31 * result + limiterEnabled.hashCode()
        result = 31 * result + (limiterThresholdDb?.hashCode() ?: 0)
        result = 31 * result + limiterThresholdAuto.hashCode()
        return result
    }
}
