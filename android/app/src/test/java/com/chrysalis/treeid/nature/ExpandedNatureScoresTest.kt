package com.chrysalis.treeid.nature

import org.junit.Assert.*
import org.junit.Test

class ExpandedNatureScoresTest {
    private val taxa = listOf(
        NatureTaxon(0,"ny","NY fungus","NY fungus",NatureKind.MUSHROOM,nyObservations=20),
        NatureTaxon(1,"outside","Outside","Outside",null),
        NatureTaxon(2,"second","Second fungus","Second fungus",NatureKind.MUSHROOM,nyObservations=3))
    @Test fun softmaxIsStableForLargeLogitsAndShiftInvariant() {
        val probabilities = ExpandedNatureScores.probabilities(floatArrayOf(1001f,1000f,-1000f))
        assertEquals(1f, probabilities.sum(), 0.000001f)
        assertEquals(0.7310586f, probabilities[0], 0.000001f)
        assertArrayEquals(probabilities, ExpandedNatureScores.probabilities(floatArrayOf(1f,0f,-2000f)), 0.000001f)
    }
    @Test fun outOfCatalogWinnerDoesNotForceANewYorkAnswer() {
        val scores = floatArrayOf(0.15f,0.8f,0.05f)
        assertTrue(ExpandedNatureScores.clear(scores,0.5f,0.2f))
        assertTrue(ExpandedNatureScores.rank(taxa,scores,NatureKind.MUSHROOM).isEmpty())
    }
    @Test fun filteringNeverRenormalizesScores() {
        val results = ExpandedNatureScores.rank(taxa,floatArrayOf(0.72f,0.18f,0.10f),NatureKind.MUSHROOM)
        assertEquals(listOf("ny","second"),results.map { it.taxon.key })
        assertEquals(0.72f,results[0].score,0f); assertEquals(0.10f,results[1].score,0f)
    }
    @Test fun fernViewDoesNotShowAnUnrelatedPlantWinner() {
        val plants = listOf(NatureTaxon(0,"flower","Flower","Flower",NatureKind.FLOWER),
            NatureTaxon(1,"fern","Fern","Fern",NatureKind.FLOWER,fern=true))
        assertTrue(ExpandedNatureScores.rank(plants,floatArrayOf(0.8f,0.2f),NatureKind.FERN).isEmpty())
        assertEquals("fern",ExpandedNatureScores.rank(plants,floatArrayOf(0.2f,0.8f),NatureKind.FERN).single().taxon.key)
    }
    @Test fun bothTopScoreAndMarginMustPass() {
        assertFalse(ExpandedNatureScores.clear(floatArrayOf(0.69f,0.30f,0.01f),0.70f,0.25f))
        assertFalse(ExpandedNatureScores.clear(floatArrayOf(0.6f,0.4f),0.5f,0.25f))
        assertTrue(ExpandedNatureScores.clear(floatArrayOf(0.8f,0.2f),0.7f,0.25f))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsNonfiniteLogits() { ExpandedNatureScores.probabilities(floatArrayOf(Float.NaN,0f)) }
    @Test(expected = IllegalArgumentException::class) fun rejectsUnnormalizedProbabilities() { ExpandedNatureScores.clear(floatArrayOf(2f,1f),0.5f,0.2f) }
}
