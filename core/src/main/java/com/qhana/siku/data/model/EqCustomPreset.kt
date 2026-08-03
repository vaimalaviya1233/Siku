package com.qhana.siku.data.model

/**
 * Preset de ecualizador creado por el usuario: un nombre y la configuración completa que tenía el
 * EQ al capturarlo ([settings], ver [EqSettings]).
 *
 * La curva se guarda CRUDA junto al modo de bandas de ese momento ([bandCount], 5 o 10): al
 * aplicarlo en el otro modo se interpola en espacio log-frecuencia (igual que los presets de
 * fábrica), así el preset suena consistente en 5 y 10 bandas.
 *
 * [id] es un identificador estable (para borrar/ocultar/seleccionar sin depender del nombre, que
 * el usuario puede repetir).
 */
data class EqCustomPreset(
    val id: String,
    val name: String,
    val settings: EqSettings
) {
    /** Atajos de lectura: el 90 % de los usos solo quiere la curva. */
    val bandCount: Int get() = settings.bandCount
    val gains: FloatArray get() = settings.gains
}
