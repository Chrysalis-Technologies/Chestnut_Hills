package com.chrysalis.treeid.birds

import org.junit.Assert.*
import org.junit.Test

class BirdScoresTest {
    private val labels = listOf(BirdLabel("Turdus migratorius", "American Robin"),
        BirdLabel("Poecile atricapillus", "Black-capped Chickadee"), BirdLabel("Human vocal", "Human vocal"))
    @Test fun scoresUseOneSigmoidAndPeakAcrossWindowsWithoutSoftmax() {
        val result = BirdScores.rank(labels, listOf(floatArrayOf(0f, -2f, 10f), floatArrayOf(-2f, 2f, 10f)))
        assertEquals(listOf("Poecile atricapillus", "Turdus migratorius"), result.map { it.label.key })
        assertEquals(0.880797f, result[0].score, 1e-6f)
        assertEquals(0.5f, result[1].score, 0f)
    }
    @Test fun lowScoresAndNonEventsAreNotSpeciesSuggestions() {
        assertTrue(BirdScores.rank(labels, listOf(floatArrayOf(-20f, -20f, 20f))).isEmpty())
        assertTrue(BirdScores.rank(labels, emptyList()).isEmpty())
    }
    @Test fun sigmoidHandlesExtremeFiniteLogits() {
        assertEquals(0f, BirdScores.sigmoid(-1000f), 0f)
        assertEquals(1f, BirdScores.sigmoid(1000f), 0f)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsNanOutput() { BirdScores.rank(labels, listOf(floatArrayOf(Float.NaN, 0f, 0f))) }
    @Test(expected = IllegalArgumentException::class) fun rejectsWrongOutputShape() { BirdScores.rank(labels, listOf(floatArrayOf(1f))) }
    @Test fun limitsSuggestionsToFive() {
        val many = (1..8).map { BirdLabel("Species $it", "Bird $it") }
        assertEquals(5, BirdScores.rank(many, listOf(FloatArray(8) { it.toFloat() })).size)
    }
}
