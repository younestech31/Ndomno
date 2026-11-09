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
}
