package com.qhana.siku.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El caso que motivó [FuzzyMatch] es `deram` → `Dream Theater`, y falló en la primera versión
 * porque una transposición cuesta 2 con Levenshtein clásico. Por eso los tests de transposición
 * están primero: son la razón de ser de la clase, no un extra.
 */
class FuzzyMatchTest {

    @Test
    fun `transposicion de dos letras encuentra el artista`() {
        assertTrue(FuzzyMatch.matches("Dream Theater", "deram"))
    }

    @Test
    fun `letra cambiada encuentra el artista`() {
        assertTrue(FuzzyMatch.matches("Dream Theater", "dreem"))
    }

    @Test
    fun `letra faltante encuentra el artista`() {
        assertTrue(FuzzyMatch.matches("Radiohead", "radiohed"))
    }

    @Test
    fun `coincidencia literal sigue funcionando`() {
        assertTrue(FuzzyMatch.matches("Dream Theater", "theater"))
        assertTrue(FuzzyMatch.matches("Dream Theater", "DREAM"))
    }

    @Test
    fun `ignora acentos en los dos sentidos`() {
        assertTrue(FuzzyMatch.matches("Dvořák", "dvorak"))
        assertTrue(FuzzyMatch.matches("Canción de cuna", "cancion"))
    }

    /**
     * Con dos o tres letras no se tolera ninguna errata: a esa longitud casi cualquier palabra
     * queda a un carácter y la lista se llenaría de resultados sin relación.
     */
    @Test
    fun `una consulta muy corta no admite erratas`() {
        assertFalse(FuzzyMatch.matches("Dream Theater", "dua"))
        assertTrue(FuzzyMatch.matches("Dua Lipa", "dua"))
    }

    @Test
    fun `lo que no se parece no coincide`() {
        assertFalse(FuzzyMatch.matches("Dream Theater", "metallica"))
        assertFalse(FuzzyMatch.matches("Radiohead", "coldplay"))
    }

    /** Una coincidencia literal siempre puntúa mejor (menor) que una corregida. */
    @Test
    fun `las literales van antes que las aproximadas`() {
        val literal = FuzzyMatch.score("Dream Theater", "dream")
        val aproximada = FuzzyMatch.score("Dream Theater", "deram")
        assertTrue(literal < aproximada)
        assertEquals(0, literal)
    }

    /** Entre dos literales gana la que empieza por lo escrito. */
    @Test
    fun `entre literales gana la que empieza por la consulta`() {
        val empieza = FuzzyMatch.score("Beatles", "beatles")
        val contiene = FuzzyMatch.score("The Beatles", "beatles")
        assertTrue(empieza < contiene)
    }

    @Test
    fun `consulta vacia no filtra nada`() {
        assertTrue(FuzzyMatch.matches("Dream Theater", ""))
        assertTrue(FuzzyMatch.matches("Dream Theater", "   "))
    }
}
