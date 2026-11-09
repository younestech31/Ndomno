package com.ndomno.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ScoringTest {
    @Test
    fun teamAWinsAndScoresTeamBsRemainingPips() {
        assertEquals(10 to 0, calculateRoundScore(teamAPips = 8, teamBPips = 10, winner = 1))
    }

    @Test
    fun teamBWinsAndScoresTeamAsRemainingPips() {
        assertEquals(0 to 8, calculateRoundScore(teamAPips = 8, teamBPips = 10, winner = 2))
    }

    @Test
    fun noWinnerProducesNoPoints() {
        assertEquals(0 to 0, calculateRoundScore(teamAPips = 8, teamBPips = 10, winner = 0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativePipsAreRejected() {
        calculateRoundScore(teamAPips = -1, teamBPips = 10, winner = 1)
    }

    @Test
    fun entryScoringParsesFourNonNegativePipValues() {
        assertEquals(
            10 to 0,
            calculateRoundScoreFromEntries(listOf("3", "4"), listOf("6", "4"), winner = 1)
        )
    }

    @Test
    fun entryScoringRejectsNegativeOrNonNumericPips() {
        assertEquals(null, calculateRoundScoreFromEntries(listOf("-1", "2"), listOf("3", "4"), 1))
        assertEquals(null, calculateRoundScoreFromEntries(listOf("x", "2"), listOf("3", "4"), 1))
    }

    @Test
    fun entryScoringRejectsIntegerOverflow() {
        assertEquals(
            null,
            calculateRoundScoreFromEntries(
                listOf("2147483647", "1"),
                listOf("0", "0"),
                winner = 1
            )
        )
    }
}
