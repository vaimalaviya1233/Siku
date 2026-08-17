package com.qhana.siku.data.coordinator

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sabe si el almacenamiento del dispositivo cambió desde el último listado local.
 *
 * Existe para responder una pregunta que antes no se hacía nadie: **¿hace falta re-listar?**
 * `SyncManager.refreshLocalSources` se dispara en cada vuelta de la app a primer plano, y sin esta
 * pieza eso significaba un walk completo del almacenamiento CADA vez — medido en un Poco F5, 2,8 s
 * de I/O para 777 archivos. Mirar una notificación y volver quince veces en una sesión de escucha
 * son quince listados idénticos con la pantalla encendida.
 *
 * La señal es un [ContentObserver] sobre MediaStore: el sistema avisa cuando aparece o desaparece
 * un archivo de audio, así que copiar música al teléfono sigue apareciendo en el acto al volver a
 * la app —que es justo el caso de uso por el que existe el refresco— y no volver a copiar nada no
 * cuesta nada. Es la convención 13 ("esperar por SEÑAL, no por intervalo") aplicada al listado.
 *
 * **El intervalo mínimo NO sobra**, y es lo que hace que esto sea seguro y no un ahorro que rompe
 * casos: el observer cubre bien el almacenamiento compartido —lo que MediaProvider indexa— pero no
 * garantiza cubrir una carpeta SAF que el indexador ignore (un `.nomedia`, un volumen que no
 * escanea). Ahí la señal puede no llegar nunca, así que pasado [MIN_REFRESH_INTERVAL_MS] se
 * re-lista igual. El resultado es: con cambios, inmediato; sin cambios, como mucho un listado cada
 * diez minutos en vez de uno por alt-tab.
 *
 * El observer queda registrado toda la vida del proceso a propósito: es push del sistema, no
 * cuesta nada mientras no pase nada, y desregistrarlo por ciclo de vida abriría la ventana en la
 * que justamente se copian los archivos (con la app en segundo plano).
 */
@Singleton
class LocalLibraryChangeMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private companion object {
        const val TAG = "LocalLibraryChange"

        /**
         * Tope de espera cuando NO llegó ninguna señal. Ver arriba: es la red para las carpetas
         * que MediaProvider no indexa, no la cadencia normal de refresco.
         */
        const val MIN_REFRESH_INTERVAL_MS = 10L * 60 * 1000
    }

    /**
     * Arranca en `true`: al levantar el proceso no sabemos qué pasó mientras estuvo muerto, y el
     * primer listado nunca debe saltarse por una suposición.
     */
    private val dirty = AtomicBoolean(true)

    private var lastRefreshAt = 0L

    private val observerThread = HandlerThread("LocalLibraryChangeMonitor").apply { start() }

    private val observer = object : ContentObserver(Handler(observerThread.looper)) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            dirty.set(true)
        }
    }

    init {
        try {
            context.contentResolver.registerContentObserver(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                /* notifyForDescendants = */ true,
                observer
            )
        } catch (e: Exception) {
            // Sin observer el gate cae al intervalo, que sigue siendo correcto: como mucho se
            // re-lista de más. Nunca de menos.
            Log.w(TAG, "No se pudo observar MediaStore, se usará solo el intervalo: ${e.message}")
        }
    }

    /**
     * ¿Merece la pena volver a listar el almacenamiento?
     *
     * Consultarlo NO consume la señal: eso lo hace [markRefreshed], y solo cuando el listado de
     * verdad terminó. Si el refresco se salta (porque hay un sync completo en marcha) o se
     * cancela a la mitad, la marca sigue puesta y el siguiente intento lo hará.
     */
    fun shouldRefresh(): Boolean =
        dirty.get() || System.currentTimeMillis() - lastRefreshAt >= MIN_REFRESH_INTERVAL_MS

    /** El listado terminó: se consume la señal y arranca la cuenta del intervalo. */
    fun markRefreshed() {
        dirty.set(false)
        lastRefreshAt = System.currentTimeMillis()
    }
}
