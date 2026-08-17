package com.qhana.siku.ui.viewmodel

import com.qhana.siku.data.model.EqProfile
import com.qhana.siku.data.preferences.MusicPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * Colección de **perfiles y presets del EQ** (guardados por el usuario + presets ocultos + el
 * ajuste de perfiles por ruta), expuesta como una sola pieza para que la compartan las DOS pantallas
 * que la tocan: la hoja del EQ (`PlaybackViewModel`, en el overlay del reproductor) y la pantalla de
 * gestión en Ajustes (`LibraryViewModel`).
 *
 * Antes cada ViewModel repetía LITERALMENTE estos flows y estos métodos —incluido el borrado, que
 * además limpia el id de los ocultos—. Eran pass-throughs sobre [MusicPreferences] (que sigue siendo
 * el único dueño del estado), pero copiados: si uno evolucionaba y el otro no, las dos pantallas
 * mostraban cosas distintas del mismo dato. Aquí la lógica vive una sola vez y cada ViewModel la
 * instancia con SU scope y SU política de sharing (el player mantiene la hoja viva con `Eagerly`,
 * Ajustes puede soltar con `WhileSubscribed`).
 *
 * Las escrituras leen el estado de [MusicPreferences] (`loadX`), no el snapshot de estos [StateFlow]:
 * la caché en memoria de preferencias es la fuente de verdad y siempre está al día, mientras que un
 * `stateIn` puede ir un frame por detrás.
 */
class EqPresetLibrary(
    private val prefs: MusicPreferences,
    scope: CoroutineScope,
    sharing: SharingStarted
) {
    /** Perfiles guardados por el usuario (config completa del sonido). */
    val profiles: StateFlow<List<EqProfile>> =
        prefs.eqProfilesFlow.stateIn(scope, sharing, prefs.loadEqProfiles())

    /** Ids ocultos del selector (presets de fábrica namespaced + perfiles por UUID). */
    val hidden: StateFlow<Set<String>> =
        prefs.hiddenEqPresetsFlow.stateIn(scope, sharing, prefs.loadHiddenEqPresets())

    /**
     * Guarda un perfil, SOBRESCRIBIENDO el existente con el mismo nombre (trim, sin distinguir
     * mayúsculas) en vez de crear un duplicado. Al sobrescribir conserva el **id** del existente:
     * los presets ocultos y cualquier referencia por id siguen apuntando al mismo perfil. Es la
     * verdad del disco ([MusicPreferences.loadEqProfiles]) la que se muta, no el snapshot del flow.
     */
    fun upsertProfile(profile: EqProfile) {
        val existing = prefs.loadEqProfiles()
        val idx = existing.indexOfFirst { it.name.trim().equals(profile.name.trim(), ignoreCase = true) }
        val updated = if (idx >= 0) {
            existing.toMutableList().also { it[idx] = profile.copy(id = existing[idx].id) }
        } else {
            existing + profile
        }
        prefs.saveEqProfiles(updated)
    }

    /**
     * Borra un perfil (definitivo, a diferencia de ocultar). Limpia también su id de los ocultos: un
     * UUID borrado no puede reaparecer, así que dejarlo en el set solo sería basura acumulándose.
     */
    fun deleteProfile(id: String) {
        prefs.saveEqProfiles(prefs.loadEqProfiles().filterNot { it.id == id })
        val hiddenIds = prefs.loadHiddenEqPresets()
        if (id in hiddenIds) prefs.saveHiddenEqPresets(hiddenIds - id)
    }

    fun setEntryHidden(id: String, hidden: Boolean) {
        val current = prefs.loadHiddenEqPresets()
        prefs.saveHiddenEqPresets(if (hidden) current + id else current - id)
    }

    fun restoreAll() = prefs.saveHiddenEqPresets(emptySet())
}
