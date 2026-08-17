package com.qhana.siku.ui.viewmodel

import com.qhana.siku.data.coordinator.SyncManager
import com.qhana.siku.data.coordinator.SyncStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take

/**
 * Cuánto del tiempo puede ocupar, como mucho, re-consultar la biblioteca mientras el sync la está
 * reescribiendo.
 *
 * **Es un presupuesto, no un intervalo, y esa es toda la idea.** Cuánto tarda una de estas
 * consultas depende del tamaño de la biblioteca y del teléfono: un GROUP BY sobre 100 canciones
 * son un par de milisegundos y sobre 20 000 en un dispositivo lento pueden ser cientos. Cualquier
 * "cada N segundos" que se elija acierta para una biblioteca y falla para las demás — de más en la
 * pequeña (se refresca mucho menos de lo que podría, gratis) y de menos en la grande (justo donde
 * duele). Lo que sí es constante entre dispositivos es la decisión de diseño: *el refresco de las
 * listas no debe robarle a la descarga más de una décima parte del tiempo*.
 *
 * De aquí sale la espera, midiendo lo que la consulta REALMENTE costó (ver [cooldownAfter]): una
 * agregación de 2 ms se repite casi en vivo y una de 300 ms se espacia sola a ~2,7 s. El número
 * que antes estaba escrito a mano (3 s) resulta ser lo que esta fórmula da para una biblioteca
 * grande, que es la única razón por la que parecía razonable.
 */
private const val SAMPLING_DUTY_CYCLE = 0.1f

/**
 * Suelo de la espera: un frame a 60 Hz.
 *
 * No es una elección de gusto — por debajo de un frame no hay nada que ganar, porque la pantalla
 * no puede mostrar dos resultados distintos dentro del mismo. En un panel de 120 Hz el suelo real
 * sería la mitad, pero refrescar una lista dos veces por frame no aporta y 60 Hz es el ritmo del
 * que ningún dispositivo baja.
 */
private const val FRAME_MS = 16L

/**
 * Cuánto esperar antes de volver a consultar, a partir de lo que costó la consulta anterior.
 *
 * Con un presupuesto del 10 %, por cada unidad de tiempo consultando hay que dejar nueve sin
 * hacerlo: `espera = coste · (1 − duty) / duty`. Se mide en cada vuelta en lugar de calibrarse una
 * vez porque el coste crece con la biblioteca mientras el propio sync la hace crecer.
 */
internal fun cooldownAfter(queryCostMs: Long): Long =
    (queryCostMs * ((1f - SAMPLING_DUTY_CYCLE) / SAMPLING_DUTY_CYCLE)).toLong()
        .coerceAtLeast(FRAME_MS)

/**
 * ¿La biblioteca se está moviendo ahora mismo?
 *
 * `Preparing` cuenta junto a `Scanning` y `Downloading`: la metadata ligera escribe canción a
 * canción, y para la tabla `songs` eso es tanto trajín como una descarga.
 */
fun SyncManager.settlingFlow(): Flow<Boolean> = state
    .map { it is SyncStatus.Scanning || it is SyncStatus.Downloading || it is SyncStatus.Preparing }
    .distinctUntilChanged()

/**
 * Muestrea el flow mientras el sync reescribe la biblioteca, en vez de seguir cada invalidación.
 *
 * Room invalida **por tabla, no por consulta**: cualquier escritura en `songs` —y un sync hace
 * miles: el upsert, el género, los colores, `artworkAttemptedAt`— re-ejecuta TODAS las consultas
 * suscritas a ella. Las de la biblioteca son una docena, y varias son GROUP BY sobre la tabla
 * entera (artistas, álbumes, álbumes del momento, artista más escuchado, top de géneros).
 * Sostener eso durante los minutos que dura un sync es CPU continuo justo cuando el dispositivo
 * ya está ocupado descargando y analizando.
 *
 * La única forma de que una consulta no corra es NO estar suscrito a ella, así que durante el
 * sync la suscripción permanente se cambia por una toma puntual: se colecta, se recoge un valor y
 * se suelta. Las listas siguen poblándose solas mientras el sync avanza —que es lo que se vería
 * mal si simplemente se cortara el flujo— pero a un ritmo que **la propia consulta decide**, no
 * un intervalo elegido a mano: cada vuelta mide lo que costó y espera lo que haga falta para
 * respetar [SAMPLING_DUTY_CYCLE]. Al terminar el sync se vuelve a la suscripción normal y el
 * estado definitivo llega de inmediato.
 *
 * Consecuencia buscada: **el mecanismo se calibra solo**. En una biblioteca pequeña las listas se
 * ven prácticamente en vivo durante el escaneo, porque ahí consultar no cuesta nada; en una
 * grande se espacian, que es exactamente donde había que espaciarlas. Nadie tiene que ajustar un
 * número al cambiar de dispositivo ni al crecer la biblioteca.
 *
 * Va SIEMPRE antes de `distinctUntilChanged` y de cualquier `stateIn`, y **después** del operador
 * que produce las filas: lo que se está espaciando es la consulta, no lo que se hace con ella.
 *
 * Es la misma excepción a "esperar por SEÑAL, no por intervalo" (convención 13) que ya acepta
 * `PlaybackViewModel.eqGainReductionDb`: cuando la señal se dispara miles de veces y ningún
 * disparo concreto importa, lo correcto es muestrear.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun <T> Flow<T>.sampledDuringSync(settling: Flow<Boolean>): Flow<T> {
    val source = this
    return settling.flatMapLatest { isSettling ->
        if (!isSettling) source
        else flow {
            while (true) {
                // El cronómetro abarca la consulta Y la entrega del valor, que es justo el
                // trabajo que se está presupuestando. `take(1)` cierra la suscripción en cuanto
                // llega el primer resultado: es lo que hace que Room deje de re-ejecutarla.
                val startedAt = System.nanoTime()
                emitAll(source.take(1))
                val costMs = (System.nanoTime() - startedAt) / 1_000_000
                delay(cooldownAfter(costMs))
            }
        }
    }
}
