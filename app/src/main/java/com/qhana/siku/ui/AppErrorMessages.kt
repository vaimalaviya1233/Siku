package com.qhana.siku.ui

import android.content.Context
import com.qhana.siku.R
import com.qhana.siku.data.model.AppError

/**
 * Traduce un [AppError] a un texto localizado para la UI.
 *
 * [AppError] vive en `:core` (Kotlin puro) y sus mensajes por defecto están escritos en español
 * dentro del propio modelo; mostrarlos tal cual pintaba ESE español a los usuarios en inglés (y el
 * `fromException` metía el `message` crudo de la excepción, en inglés técnico). La regla es la misma
 * que con las letras y la autenticación: la capa de datos entrega un TIPO, la UI resuelve el string.
 *
 * Por eso los consumidores nunca deben leer `error.message` (queda para logs): usan esto.
 */
fun AppError.toUserMessage(context: Context): String {
    val res = when (this) {
        is AppError.Network -> if (isTimeout) R.string.error_network_timeout else R.string.error_network_generic
        is AppError.Auth -> R.string.error_session_generic
        is AppError.Data -> R.string.error_data_generic
        is AppError.NotFound -> R.string.error_notfound_generic
        is AppError.Playback -> R.string.error_playback_generic
        is AppError.Unknown -> R.string.error_generic
    }
    return context.getString(res)
}
