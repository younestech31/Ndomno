package com.ndomno.app

import java.util.Locale

/** Player IDs are stable Firebase document IDs, never derived from mutable names. */
data class Player(val id: String, val name: String)

/**
 * Names are retained as historical display fallbacks; IDs are the canonical
 * relationship used by rankings and synchronization.
 */
data class PlayedMatch(
    val teamA: List<String>,
    val teamB: List<String>,
    val scoreA: Int,
    val scoreB: Int,
    val date: Long = System.currentTimeMillis(),
    val id: String,
    val teamAIds: List<String>,
    val teamBIds: List<String>
)

fun legacyLocalPlayerId(numericId: Int): String = "player-$numericId"

/**
 * Converts older rounds, which stored names only, into stable player references.
 * Unmatched names remain deterministic legacy references so history is not lost.
 */
fun resolveLegacyPlayerIds(names: List<String>, players: List<Player>): List<String> =
    names.map { name ->
        players.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }?.id
            ?: ("legacy:" + name.trim().lowercase(Locale.ROOT))
    }

fun displayTeamNames(
    playerIds: List<String>,
    nameSnapshots: List<String>,
    players: List<Player>
): List<String> = playerIds.mapIndexed { index, id ->
    players.firstOrNull { it.id == id }?.name
        ?: nameSnapshots.getOrNull(index)
        ?: "Unknown player"
}
