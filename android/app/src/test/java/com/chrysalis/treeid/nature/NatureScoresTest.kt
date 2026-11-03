package com.chrysalis.treeid.nature

import org.junit.Assert.*
import org.junit.Test

class NatureScoresTest {
    private val catalog = NatureCatalog(listOf(
        NatureTaxon(0, "flower", "Flower", "Flower", NatureKind.FLOWER),
        NatureTaxon(1, "fungus1", "Fungus one", "Fungus one", NatureKind.MUSHROOM),
        NatureTaxon(2, "fungus2", "Fungus two", "Fungus two", NatureKind.MUSHROOM),
        NatureTaxon(3, "bird", "Bird", "Bird", null), NatureTaxon(4, "insect", "Insect", "Insect", null)))
    @Test fun keepsOriginalScoresWithoutRenormalizingCategoryOrApplyingSoftmaxAgain() {
        val result = NatureScores.rank(catalog, floatArrayOf(0.1f, 0.7f, 0.2f, 0f, 0f), NatureKind.MUSHROOM)
        assertEquals(listOf("fungus1", "fungus2"), result.map { it.taxon.key })
        assertEquals(0.7f, result[0].score, 0f); assertEquals(0.2f, result[1].score, 0f)
    }
    @Test fun wrongCategoryOrUnrelatedTopResultReturnsNoForcedIdentification() {
        assertTrue(NatureScores.rank(catalog, floatArrayOf(0.8f, 0.15f, 0.05f, 0f, 0f), NatureKind.MUSHROOM).isEmpty())
        assertTrue(NatureScores.rank(catalog, floatArrayOf(0.1f, 0.1f, 0.1f, 0.7f, 0f), NatureKind.FLOWER).isEmpty())
    }
    @Test fun uncertainTopScoreReturnsNoSuggestion() {
        assertTrue(NatureScores.rank(catalog, floatArrayOf(0.24f, 0.2f, 0.2f, 0.18f, 0.18f), NatureKind.FLOWER).isEmpty())
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsNonFiniteScores() {
        NatureScores.rank(catalog, floatArrayOf(Float.NaN, 0f, 0f, 0f, 0f), NatureKind.FLOWER)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsMismatchedOutput() {
        NatureScores.rank(catalog, floatArrayOf(1f), NatureKind.FLOWER)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsUnnormalizedOutputs() {
        NatureScores.rank(catalog, floatArrayOf(0.1f, 0.1f, 0.1f, 0.1f, 0.1f), NatureKind.FLOWER)
    }
}
