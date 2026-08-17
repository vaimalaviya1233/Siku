package com.qhana.siku.data.model

/**
 * Configuración COMPLETA del ecualizador: todo lo que hace que un sonido sea ese sonido.
 *
 * Es la unidad que llamamos **perfil**: la que el usuario guarda ([EqProfile]) Y la que se recuerda
 * por ruta de salida, que son la misma cosa a propósito (un perfil aplicado a mano y uno restaurado
 * al conectar unos cascos dejan el EQ idéntico). Un **preset** es solo la curva de bandas, y de
 * esos solo hay los de fábrica. Que
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
 *
 * [clarityEnabled]/[clarityGainDb] (realce de agudos) SÍ forman parte del sonido guardado: es un
 * shelf de agudos y por tanto timbre, no una red de seguridad como el limitador. Entra por el mismo
 * motivo que los refuerzos — un perfil por ruta que no lo recordara dejaría fuera un ajuste de
 * timbre— y NO produce el corte audible del toggle on/off del EQ (Clarity se aplica en vivo, sin
 * reconstruir la pipeline). No es nullable: sus defaults (apagado, 0 dB) son valores fijos que no
 * dependen de la capa `player`.
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
    val limiterThresholdAuto: Boolean = true,
    val clarityEnabled: Boolean = false,
    val clarityGainDb: Float = 0f
) {
    /**
     * Los campos nullable resueltos a los defaults que el llamador conoce (viven en la capa
     * `player`, que este módulo no puede importar). "Ausente" y "el default" son el mismo sonido —
     * ver el kdoc de arriba—, así que compararlos sin resolver daría distinto para configuraciones
     * idénticas.
     */
    fun withDefaults(bassHz: Double, trebleHz: Double, limiterDb: Float): EqSettings = copy(
        bassFreqHz = bassFreqHz ?: bassHz,
        trebleFreqHz = trebleFreqHz ?: trebleHz,
        limiterThresholdDb = limiterThresholdDb ?: limiterDb
    )

    /**
     * Todo MENOS la curva. Lo usa el selector del EQ para decidir si un perfil guardado está
     * realmente aplicado: la curva se compara aparte y CON TOLERANCIA, porque puede venir
     * remuestreada del otro modo de bandas, mientras que [equals] exige igualdad exacta.
     *
     * Los nullables hay que resolverlos ANTES con [withDefaults], o un perfil guardado antes de que
     * el campo existiera nunca se marcaría como activo aunque suene exactamente igual.
     */
    fun matchesApartFromGains(other: EqSettings): Boolean =
        bassBoostDb == other.bassBoostDb &&
            trebleBoostDb == other.trebleBoostDb &&
            bassFreqHz == other.bassFreqHz &&
            trebleFreqHz == other.trebleFreqHz &&
            preampDb == other.preampDb &&
            limiterEnabled == other.limiterEnabled &&
            limiterThresholdAuto == other.limiterThresholdAuto &&
            clarityEnabled == other.clarityEnabled &&
            clarityGainDb == other.clarityGainDb &&
            // Con el umbral en automático el valor del slider NO se aplica (manda un 0 dBFS fijo),
            // así que exigir que coincida marcaría como distintas dos configuraciones que suenan
            // exactamente igual — un perfil que sí está puesto se vería sin su check.
            (limiterThresholdAuto || limiterThresholdDb == other.limiterThresholdDb)

    // equals/hashCode explícitos: FloatArray usa identidad por defecto y rompería el diffing de
    // Compose y los `remember` por perfil (mismo motivo que en [EqProfile]).
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
            limiterThresholdAuto == other.limiterThresholdAuto &&
            clarityEnabled == other.clarityEnabled &&
            clarityGainDb == other.clarityGainDb
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
        result = 31 * result + clarityEnabled.hashCode()
        result = 31 * result + clarityGainDb.hashCode()
        return result
    }

    companion object {
        /**
         * El sonido de un dispositivo que NO tiene nada guardado: curva plana y todo lo demás en
         * su default (sin refuerzos, sin preamp, sin limitador, sin Clarity).
         *
         * Existe para que "esta ruta no tiene perfil" sea un valor y no una ausencia. Cuando era
         * una ausencia, `EqProfileManager` no tenía nada que aplicar al cambiar de ruta y se
         * quedaba puesta la configuración del dispositivo ANTERIOR — la curva de unos cascos
         * sonando por el altavoz del teléfono, que es lo contrario de lo que promete una memoria
         * por dispositivo.
         *
         * Es plano de verdad, protección incluida: el preamp y el limitador también son parte del
         * perfil (por eso viven en esta clase), así que heredarlos de otra ruta sería el mismo
         * error con otro campo. Quien los quiera en un dispositivo los enciende ahí, y la memoria
         * por ruta se encarga de que vuelvan.
         *
         * [bandCount] se conserva y no se fuerza al default porque es el MODO de la interfaz
         * (5 o 10 bandas), no timbre: cambiarlo al conectar unos cascos reorganizaría los sliders
         * del usuario sin que él haya pedido nada.
         */
        fun neutral(bandCount: Int): EqSettings =
            EqSettings(bandCount = bandCount, gains = FloatArray(bandCount))
    }
}
