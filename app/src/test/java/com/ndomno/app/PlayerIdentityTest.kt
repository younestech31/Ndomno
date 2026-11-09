package com.ndomno.app

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerIdentityTest {
    @Test
    fun oldNumericPlayerIdsKeepTheirExistingFirestoreDocumentIds() {
        assertEquals("player-7", legacyLocalPlayerId(7))
        assertEquals("player-0", legacyLocalPlayerId(0))
    }

    @Test
    fun legacyRoundNamesResolveToStablePlayerIdsCaseInsensitively() {
        val players = listOf(
            Player("player-1", "Tea"),
            Player("player-2", "Ro")
        )

        assertEquals(
            listOf("player-1", "player-2"),
            resolveLegacyPlayerIds(listOf("TEA", "ro"), players)
        )
    }

    @Test
    fun unknownLegacyNamesRemainStableInsteadOfBeingDropped() {
        val first = resolveLegacyPlayerIds(listOf("Old player"), emptyList())
        val second = resolveLegacyPlayerIds(listOf("Old player"), emptyList())

        assertEquals(first, second)
        assertEquals(listOf("legacy:old player"), first)
    }
}
