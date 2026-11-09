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


/**
 * Parses the four remaining-pip entries without allowing malformed or overflowing
 * values to reach the scoring function. Returns null until all entries are valid.
 */
fun calculateRoundScoreFromEntries(
    teamAPips: List<String>,
    teamBPips: List<String>,
    winner: Int
): Pair<Int, Int>? {
    if (teamAPips.size != 2 || teamBPips.size != 2) return null

    val values = (teamAPips + teamBPips).map { it.toLongOrNull() ?: return null }
    if (values.any { it < 0L }) return null

    val totalA = values[0] + values[1]
    val totalB = values[2] + values[3]
    if (totalA > Int.MAX_VALUE || totalB > Int.MAX_VALUE) return null

    return calculateRoundScore(totalA.toInt(), totalB.toInt(), winner)
}
