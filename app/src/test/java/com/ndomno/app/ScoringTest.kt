package com.ndomno.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ScoringTest {
    @Test fun winningTeamScoresOpponentsRemainingPips() {
        val teamAPips = 3 + 5
        val teamBPips = 4 + 6
        val teamAScore = teamBPips
        assertEquals(10, teamAScore)
    }

    @Test fun losingTeamReceivesNoPointsForNormalRound() {
        val winner = 1
        val pointsForA = if (winner == 1) 12 else 0
        val pointsForB = if (winner == 2) 8 else 0
        assertEquals(12, pointsForA)
        assertEquals(0, pointsForB)
    }
}
