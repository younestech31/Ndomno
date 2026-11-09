package com.ndomno.app

/**
 * Standard open-round scoring: the winning team receives the combined
 * remaining pips of the opposing team. A tie/unknown winner scores zero.
 */
fun calculateRoundScore(teamAPips: Int, teamBPips: Int, winner: Int): Pair<Int, Int> {
    require(teamAPips >= 0 && teamBPips >= 0) { "Remaining pips cannot be negative" }
    return when (winner) {
        1 -> teamBPips to 0
        2 -> 0 to teamAPips
        else -> 0 to 0
    }
}
