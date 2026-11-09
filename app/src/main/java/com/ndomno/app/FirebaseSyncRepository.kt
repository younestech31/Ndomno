package com.ndomno.app

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.tasks.await
import java.security.SecureRandom
import java.util.UUID

data class SyncedPlayer(
    val id: String,
    val name: String,
    val createdAtMillis: Long = System.currentTimeMillis()
)

data class SyncedMatch(
    val id: String,
    val teamA: List<String>,
    val teamB: List<String>,
    val teamAIds: List<String>,
    val teamBIds: List<String>,
    val scoreA: Int,
    val scoreB: Int,
    val createdAtMillis: Long = System.currentTimeMillis()
)

/**
 * Firestore-backed shared game space. Player document IDs are stable identifiers
 * referenced by every new round. Legacy rounds without IDs are still read;
 * MainActivity resolves their name snapshots against the synchronized players.
 */
class FirebaseSyncRepository(context: Context) {
    private val app = FirebaseApp.getApps(context).firstOrNull()
        ?: error("Firebase is not configured. Add app/google-services.json first.")
    private val auth = FirebaseAuth.getInstance(app)
    private val db = FirebaseFirestore.getInstance(app)

    suspend fun ensureSignedIn(): String {
        auth.currentUser?.uid?.let { return it }
        return auth.signInAnonymously().await().user?.uid
            ?: error("Firebase could not create a device identity.")
    }

    suspend fun createGroup(): String {
        val uid = ensureSignedIn()
        repeat(5) {
            val code = newInviteCode()
            val group = db.collection("groups").document(code)
            try {
                db.runTransaction { transaction ->
                    check(!transaction.get(group).exists()) { "Invite code collision" }
                    transaction.set(
                        group,
                        mapOf(
                            "inviteCode" to code,
                            "memberIds" to listOf(uid),
                            "createdBy" to uid,
                            "createdAt" to FieldValue.serverTimestamp()
                        )
                    )
                    code
                }.await()
                return code
            } catch (error: Exception) {
                if (error.message?.contains("collision", ignoreCase = true) != true) throw error
            }
        }
        error("Could not generate a unique invite code. Please try again.")
    }

    suspend fun joinGroup(rawCode: String): String {
        val code = rawCode.trim().uppercase()
        require(code.matches(Regex("[A-Z0-9]{10}"))) { "Enter the 10-character invite code." }
        val uid = ensureSignedIn()
        val group = db.collection("groups").document(code)
        val snapshot = group.get().await()
        require(snapshot.exists() && snapshot.getString("inviteCode") == code) {
            "That invite code was not found."
        }
        val members = snapshot.get("memberIds") as? List<*> ?: emptyList<Any>()
        if (uid !in members) {
            require(members.size < 10) { "This group is full." }
            group.update("memberIds", FieldValue.arrayUnion(uid)).await()
        }
        return code
    }

    fun listenPlayers(
        groupCode: String,
        onPlayers: (List<SyncedPlayer>) -> Unit,
        onError: (Exception) -> Unit
    ): ListenerRegistration = db.collection("groups").document(groupCode)
        .collection("players")
        .orderBy("name")
        .addSnapshotListener { snapshot, error ->
            if (error != null) onError(error)
            else onPlayers(snapshot?.documents.orEmpty().mapNotNull { doc ->
                val name = doc.getString("name") ?: return@mapNotNull null
                SyncedPlayer(doc.id, name, doc.getTimestamp("createdAt")?.toDate()?.time ?: 0L)
            })
        }

    fun listenMatches(
        groupCode: String,
        onMatches: (List<SyncedMatch>) -> Unit,
        onError: (Exception) -> Unit
    ): ListenerRegistration = db.collection("groups").document(groupCode)
        .collection("matches")
        .orderBy("createdAt")
        .addSnapshotListener { snapshot, error ->
            if (error != null) onError(error)
            else onMatches(snapshot?.documents.orEmpty().mapNotNull { doc ->
                val teamA = doc.get("teamA") as? List<*> ?: return@mapNotNull null
                val teamB = doc.get("teamB") as? List<*> ?: return@mapNotNull null
                val scoreA = (doc.get("scoreA") as? Number)?.toInt() ?: return@mapNotNull null
                val scoreB = (doc.get("scoreB") as? Number)?.toInt() ?: return@mapNotNull null
                SyncedMatch(
                    id = doc.id,
                    teamA = teamA.filterIsInstance<String>(),
                    teamB = teamB.filterIsInstance<String>(),
                    teamAIds = (doc.get("teamAIds") as? List<*>)?.filterIsInstance<String>().orEmpty(),
                    teamBIds = (doc.get("teamBIds") as? List<*>)?.filterIsInstance<String>().orEmpty(),
                    scoreA = scoreA,
                    scoreB = scoreB,
                    createdAtMillis = doc.getTimestamp("createdAt")?.toDate()?.time ?: 0L
                )
            })
        }

    suspend fun addPlayer(
        groupCode: String,
        name: String,
        playerId: String = UUID.randomUUID().toString()
    ): String {
        val uid = ensureSignedIn()
        val normalized = name.trim()
        require(normalized.isNotEmpty() && normalized.length <= 60) {
            "Player names must contain between 1 and 60 characters."
        }
        db.collection("groups").document(groupCode).collection("players")
            .document(playerId)
            .set(
                mapOf(
                    "name" to normalized,
                    "createdBy" to uid,
                    "createdAt" to FieldValue.serverTimestamp()
                )
            ).await()
        return playerId
    }

    suspend fun updatePlayer(groupCode: String, playerId: String, name: String) {
        ensureSignedIn()
        val normalized = name.trim()
        require(normalized.isNotEmpty() && normalized.length <= 60) {
            "Player names must contain between 1 and 60 characters."
        }
        db.collection("groups").document(groupCode).collection("players")
            .document(playerId)
            .update("name", normalized)
            .await()
    }

    suspend fun deletePlayer(groupCode: String, playerId: String) {
        ensureSignedIn()
        db.collection("groups").document(groupCode).collection("players")
            .document(playerId)
            .delete()
            .await()
    }

    suspend fun saveMatch(
        groupCode: String,
        teamA: List<String>,
        teamB: List<String>,
        teamAIds: List<String>,
        teamBIds: List<String>,
        scoreA: Int,
        scoreB: Int,
        matchId: String = UUID.randomUUID().toString()
    ): String {
        val uid = ensureSignedIn()
        validateMatch(teamAIds, teamBIds, scoreA, scoreB)
        require(teamA.size == 2 && teamB.size == 2) {
            "Each team must have two player names."
        }
        val id = matchId
        db.collection("groups").document(groupCode).collection("matches")
            .document(id)
            .set(
                mapOf(
                    "teamA" to teamA,
                    "teamB" to teamB,
                    "teamAIds" to teamAIds,
                    "teamBIds" to teamBIds,
                    "scoreA" to scoreA,
                    "scoreB" to scoreB,
                    "createdBy" to uid,
                    "createdAt" to FieldValue.serverTimestamp()
                )
            ).await()
        return id
    }

    suspend fun updateMatch(
        groupCode: String,
        matchId: String,
        teamA: List<String>,
        teamB: List<String>,
        teamAIds: List<String>,
        teamBIds: List<String>,
        scoreA: Int,
        scoreB: Int
    ) {
        ensureSignedIn()
        validateMatch(teamAIds, teamBIds, scoreA, scoreB)
        require(teamA.size == 2 && teamB.size == 2) {
            "Each team must have two player names."
        }
        db.collection("groups").document(groupCode).collection("matches")
            .document(matchId)
            .update(
                mapOf(
                    "teamA" to teamA,
                    "teamB" to teamB,
                    "teamAIds" to teamAIds,
                    "teamBIds" to teamBIds,
                    "scoreA" to scoreA,
                    "scoreB" to scoreB
                )
            ).await()
    }

    suspend fun deleteMatch(groupCode: String, matchId: String) {
        ensureSignedIn()
        db.collection("groups").document(groupCode).collection("matches")
            .document(matchId)
            .delete()
            .await()
    }

    private fun validateMatch(
        teamAIds: List<String>,
        teamBIds: List<String>,
        scoreA: Int,
        scoreB: Int
    ) {
        require(
            teamAIds.size == 2 && teamBIds.size == 2 &&
                teamAIds.toSet().intersect(teamBIds.toSet()).isEmpty()
        ) { "A match requires two different players on each team." }
        require(scoreA >= 0 && scoreB >= 0) { "Scores cannot be negative." }
    }

    private fun newInviteCode(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val random = SecureRandom()
        return buildString(10) { repeat(10) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }

    companion object {
        fun isConfigured(context: Context): Boolean =
            FirebaseApp.getApps(context).isNotEmpty()
    }
}
