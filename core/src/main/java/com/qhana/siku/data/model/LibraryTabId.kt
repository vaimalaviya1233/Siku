package com.qhana.siku.data.model

/**
 * Pestañas de la biblioteca. Vive en `:core` (y no como enum privado de `LibraryScreen`) porque
 * ahora el usuario decide su ORDEN y cuáles se muestran, y esa config se persiste.
 *
 * Desacoplado de [SongFilter] a propósito: Artistas/Álbumes/Géneros no son filtros de canciones
 * (ese enum sigue siendo solo para el paging/orden persistido de la pestaña Todas).
 */
enum class LibraryTabId {
    HOME,
    SONGS,
    ARTISTS,
    ALBUMS,
    GENRES,
    PLAYLISTS
}

/** Una pestaña con su visibilidad; el orden lo da la posición en la lista de config. */
data class LibraryTabState(
    val tab: LibraryTabId,
    val visible: Boolean
)

/**
 * Config de las pestañas: orden + visibilidad. Se persiste como string delimitado en DataStore
 * (`MusicPreferences`) y se reconcilia con el set completo al decodificar, igual que
 * [PlayerToolbarConfig]: una pestaña nueva de una versión futura entra con su default en vez de
 * desaparecer para quien ya tenía config guardada.
 */
object LibraryTabsConfig {

    /** Todas visibles, en el orden histórico + Géneros antes de Listas. */
    val DEFAULT: List<LibraryTabState> = listOf(
        LibraryTabState(LibraryTabId.HOME, visible = true),
        LibraryTabState(LibraryTabId.SONGS, visible = true),
        LibraryTabState(LibraryTabId.ARTISTS, visible = true),
        LibraryTabState(LibraryTabId.ALBUMS, visible = true),
        LibraryTabState(LibraryTabId.GENRES, visible = true),
        LibraryTabState(LibraryTabId.PLAYLISTS, visible = true)
    )

    /**
     * Mínimo de pestañas visibles. Con cero, la biblioteca se quedaría sin ningún contenido y sin
     * forma de volver a activarlas desde el propio pager: la UI de Ajustes lo hace cumplir.
     */
    const val MIN_VISIBLE = 1

    fun encode(list: List<LibraryTabState>): String =
        list.joinToString(",") { "${it.tab.name}:${if (it.visible) 1 else 0}" }

    fun decode(raw: String?): List<LibraryTabState> {
        if (raw.isNullOrBlank()) return DEFAULT
        val parsed = raw.split(",").mapNotNull { token ->
            val parts = token.split(":")
            val tab = parts.getOrNull(0)
                ?.let { name -> LibraryTabId.entries.firstOrNull { it.name == name } }
                ?: return@mapNotNull null
            LibraryTabState(tab, visible = parts.getOrNull(1) == "1")
        }
        val seen = parsed.map { it.tab }.toSet()
        val missing = DEFAULT.filter { it.tab !in seen }
        val merged = (parsed + missing).distinctBy { it.tab }
        // Una config corrupta (o de una versión que permitía ocultarlas todas) no puede dejar la
        // biblioteca en blanco: se cae al default antes que devolver una lista sin nada visible.
        return if (merged.none { it.visible }) DEFAULT else merged
    }
}
