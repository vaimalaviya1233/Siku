package com.qhana.siku.data.model

/**
 * Perfil de ecualizador guardado por el usuario: un nombre y el sonido completo ([settings]).
 *
 * **Preset y perfil son cosas distintas y esta clase es solo la segunda.** Un *preset* es una curva
 * de bandas y nada más — los diez de fábrica (Rock, Pop…), que no tocan refuerzos, preamp ni
 * limitador. Un *perfil* es todo el [EqSettings], la MISMA unidad que se recuerda por ruta de
 * salida (ver `EqProfileManager`), y por eso comparten nombre: aplicar uno a mano y llegar a él
 * conectando unos cascos dejan el ecualizador idéntico.
 *
 * Lo que el usuario guarda es SIEMPRE un perfil: guardar media pantalla de controles y no la otra
 * media sería una decisión que hay que explicar cada vez, y quien ajusta un preamp para que su
 * curva no recorte espera que eso forme parte del sonido que guarda. Los presets no se crean, se
 * eligen.
 *
 * La curva se guarda CRUDA junto al modo de bandas de ese momento ([bandCount], 5 o 10): al
 * aplicarla en el otro modo se interpola en espacio log-frecuencia (igual que los presets de
 * fábrica), así suena consistente en 5 y 10 bandas.
 *
 * [id] es un identificador estable (para borrar/ocultar/seleccionar sin depender del nombre, que
 * el usuario puede repetir).
 */
data class EqProfile(
    val id: String,
    val name: String,
    val settings: EqSettings
) {
    /** Atajos de lectura: el 90 % de los usos solo quiere la curva. */
    val bandCount: Int get() = settings.bandCount
    val gains: FloatArray get() = settings.gains
}
