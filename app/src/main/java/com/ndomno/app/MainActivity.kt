package com.ndomno.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import java.util.UUID
import androidx.compose.ui.platform.LocalContext
import org.json.JSONArray
import org.json.JSONObject
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

data class Player(val id: Int, val name: String)
data class PlayedMatch(val teamA: List<String>, val teamB: List<String>, val scoreA: Int, val scoreB: Int, val date: Long = System.currentTimeMillis(), val id: String = UUID.randomUUID().toString())

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { NdomnoApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NdomnoApp() {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("ndomno_data", Context.MODE_PRIVATE) }
    val syncRepository = remember(context) {
        if (FirebaseSyncRepository.isConfigured(context)) runCatching { FirebaseSyncRepository(context) }.getOrNull() else null
    }
    val scope = rememberCoroutineScope()
    var groupCode by remember { mutableStateOf(preferences.getString("group_code", "").orEmpty()) }
    var showSyncDialog by remember { mutableStateOf(false) }
    var inviteCodeInput by remember { mutableStateOf("") }
    var syncMessage by remember { mutableStateOf<String?>(null) }
    val players = remember {
        mutableStateListOf<Player>().apply { addAll(loadPlayers(preferences)) }
    }
    val matches = remember {
        mutableStateListOf<PlayedMatch>().apply { addAll(loadMatches(preferences)) }
    }
    LaunchedEffect(players.toList(), matches.toList()) {
        savePlayers(preferences, players)
        saveMatches(preferences, matches)
    }
    var newName by remember { mutableStateOf("") }
    var selectedTab by remember { mutableIntStateOf(0) }
    var teamA by remember { mutableStateOf(listOf<String>()) }
    var teamB by remember { mutableStateOf(listOf<String>()) }
    var pipsA1 by remember { mutableStateOf("0") }
    var pipsA2 by remember { mutableStateOf("0") }
    var pipsB1 by remember { mutableStateOf("0") }
    var pipsB2 by remember { mutableStateOf("0") }
    var winner by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }

    DisposableEffect(syncRepository, groupCode) {
        if (syncRepository != null && groupCode.isNotBlank()) {
            val playersListener = syncRepository.listenPlayers(groupCode, { remotePlayers ->
                players.clear()
                players.addAll(remotePlayers.map { Player(it.id.hashCode(), it.name) })
            }, { syncMessage = "Player sync error: ${it.localizedMessage}" })
            val matchesListener = syncRepository.listenMatches(groupCode, { remoteMatches ->
                matches.clear()
                matches.addAll(remoteMatches.map {
                    PlayedMatch(it.teamA, it.teamB, it.scoreA, it.scoreB, it.createdAtMillis, it.id)
                })
            }, { syncMessage = "Game sync error: ${it.localizedMessage}" })
            onDispose {
                playersListener.remove()
                matchesListener.remove()
            }
        } else {
            onDispose { }
        }
    }

    MaterialTheme(colorScheme = lightColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF176B55))) {
        Scaffold(topBar = { TopAppBar(title = { Text("Ndomno", fontWeight = FontWeight.Bold) }, actions = { TextButton(onClick = { showSyncDialog = true }) { Text(if (groupCode.isBlank()) "Sync" else "Shared") } }) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                TabRow(selectedTabIndex = selectedTab) {
                    listOf("New game", "Players", "Rankings", "History").forEachIndexed { i, label ->
                        Tab(selected = selectedTab == i, onClick = { selectedTab = i }, text = { Text(label) })
                    }
                }
                Spacer(Modifier.height(16.dp))
                when (selectedTab) {
                    0 -> {
                        Text("Create a 2 vs 2 match", style = MaterialTheme.typography.titleLarge)
                        Text("Choose any four players. Partnerships can change every game.")
                        Spacer(Modifier.height(12.dp))
                        Text("Team A", fontWeight = FontWeight.Bold)
                        players.forEach { p ->
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Checkbox(checked = p.name in teamA, onCheckedChange = { checked ->
                                    teamA = if (checked && teamA.size < 2) teamA + p.name else teamA - p.name
                                    if (p.name in teamB) teamB = teamB - p.name
                                }, enabled = p.name in teamA || teamA.size < 2)
                                Text(p.name)
                            }
                        }
                        Text("Team A: ${teamA.joinToString(" + ").ifBlank { "Choose two players" }}")
                        Spacer(Modifier.height(6.dp))
                        Text("Team B — select two different players", fontWeight = FontWeight.Bold)
                        players.filter { it.name !in teamA }.forEach { p ->
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Checkbox(checked = p.name in teamB, onCheckedChange = { checked ->
                                    teamB = if (checked && teamB.size < 2) teamB + p.name else teamB - p.name
                                }, enabled = p.name in teamB || teamB.size < 2)
                                Text(p.name)
                            }
                        }
                        Text("Team B: ${teamB.joinToString(" + ").ifBlank { "Choose two players" }}")
                        HorizontalDivider(Modifier.padding(vertical = 10.dp))
                        Text("Round scoring", fontWeight = FontWeight.Bold)
                        Text("Enter the remaining pips for each player. The winner scores the opponents' combined pips.")
                        NumberField("Team A player 1 pips", pipsA1) { pipsA1 = it }
                        NumberField("Team A player 2 pips", pipsA2) { pipsA2 = it }
                        NumberField("Team B player 1 pips", pipsB1) { pipsB1 = it }
                        NumberField("Team B player 2 pips", pipsB2) { pipsB2 = it }
                        Row {
                            FilterChip(selected = winner == 1, onClick = { winner = 1 }, label = { Text("Team A won") })
                            Spacer(Modifier.width(8.dp))
                            FilterChip(selected = winner == 2, onClick = { winner = 2 }, label = { Text("Team B won") })
                        }
                        val calculatedScore = calculateRoundScore(
                            (pipsA1.toIntOrNull() ?: 0).coerceAtLeast(0) + (pipsA2.toIntOrNull() ?: 0).coerceAtLeast(0),
                            (pipsB1.toIntOrNull() ?: 0).coerceAtLeast(0) + (pipsB2.toIntOrNull() ?: 0).coerceAtLeast(0),
                            winner
                        )
                        val roundPoints = if (winner == 1) calculatedScore.first else calculatedScore.second
                        Text("Round points: $roundPoints")
                        Button(onClick = {
                            if (teamA.size == 2 && teamB.size == 2 && winner != 0 &&
                                listOf(pipsA1, pipsA2, pipsB1, pipsB2).all { (it.toIntOrNull() ?: -1) >= 0 }) {
                                val savedMatch = PlayedMatch(
                                    teamA.toList(), teamB.toList(),
                                    calculatedScore.first, calculatedScore.second
                                )
                                matches.add(savedMatch)
                                if (groupCode.isNotBlank() && syncRepository != null) {
                                    scope.launch {
                                        runCatching {
                                            syncRepository.saveMatch(
                                                groupCode, savedMatch.teamA, savedMatch.teamB,
                                                savedMatch.scoreA, savedMatch.scoreB, savedMatch.id
                                            )
                                        }.onFailure { syncMessage = "Round saved locally; cloud sync pending: ${it.localizedMessage}" }
                                    }
                                }
                                val totalA = matches.filter { it.teamA == teamA && it.teamB == teamB }.sumOf { it.scoreA }
                                val totalB = matches.filter { it.teamA == teamA && it.teamB == teamB }.sumOf { it.scoreB }
                                message = "Round saved. Pairing total: $totalA – $totalB"
                            } else message = "Choose two players per team, a winner, and valid pip counts."
                        }, modifier = Modifier.fillMaxWidth()) { Text("Save round") }
                        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                    }
                    1 -> {
                        Text("Player list", style = MaterialTheme.typography.titleLarge)
                        Row {
                            OutlinedTextField(newName, { newName = it }, modifier = Modifier.weight(1f), label = { Text("Player name") }, singleLine = true)
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = {
                                val name = newName.trim()
                                if (name.isNotEmpty() && players.none { it.name.equals(name, true) }) {
                                    val addedPlayer = Player((players.maxOfOrNull { it.id } ?: 0) + 1, name)
                                    players.add(addedPlayer)
                                    if (groupCode.isNotBlank() && syncRepository != null) {
                                        scope.launch {
                                            runCatching { syncRepository.addPlayer(groupCode, name, "player-${addedPlayer.id}") }
                                                .onFailure { syncMessage = "Player saved locally; cloud sync pending: ${it.localizedMessage}" }
                                        }
                                    }
                                    newName = ""
                                }
                            }) { Text("Add") }
                        }
                        LazyColumn { items(players) { Text(it.name, Modifier.padding(8.dp)) } }
                    }
                    2 -> {
                        Text("Individual ranking", style = MaterialTheme.typography.titleLarge)
                        val ranking = players.map { p ->
                            p.name to matches.sumOf { m ->
                                (if (p.name in m.teamA) m.scoreA else if (p.name in m.teamB) m.scoreB else 0)
                            }
                        }.sortedByDescending { it.second }
                        ranking.forEachIndexed { i, entry -> Text("${i + 1}. ${entry.first} — ${entry.second} pts", Modifier.padding(6.dp)) }
                        Spacer(Modifier.height(12.dp))
                        Text("Partnership ranking", style = MaterialTheme.typography.titleLarge)
                        val pairs = matches.flatMap { listOf(it.teamA to it.scoreA, it.teamB to it.scoreB) }
                            .groupBy { it.first.sorted().joinToString(" + ") }
                            .mapValues { (_, values) -> values.sumOf { it.second } }
                            .toList().sortedByDescending { it.second }
                        pairs.forEachIndexed { i, pair -> Text("${i + 1}. ${pair.first} — ${pair.second} pts", Modifier.padding(6.dp)) }
                    }
                    else -> {
                        Text("Match history", style = MaterialTheme.typography.titleLarge)
                        LazyColumn {
                            items(matches.reversed()) { m ->
                                ListItem(headlineContent = { Text("${m.teamA.joinToString(" + ")} vs ${m.teamB.joinToString(" + ")}") },
                                    supportingContent = { Text("Score: ${m.scoreA} – ${m.scoreB}") })
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSyncDialog) {
        AlertDialog(
            onDismissRequest = { showSyncDialog = false },
            title = { Text("Sync between phones") },
            text = {
                Column {
                    if (syncRepository == null) {
                        Text("Firebase is not connected yet. Add app/google-services.json, enable Anonymous sign-in, create a Firestore database, and deploy firestore.rules.")
                    } else {
                        if (groupCode.isNotBlank()) {
                            Text("This phone is linked to shared group:")
                            Text(groupCode, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            Text("Give this code to your friend. They can enter it on their phone to join.")
                        } else {
                            Text("Create a group on the phone that has your existing players and game history. Your friend can then join with the code.")
                            OutlinedTextField(
                                value = inviteCodeInput,
                                onValueChange = { inviteCodeInput = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(10) },
                                label = { Text("Friend's 10-character code") },
                                singleLine = true
                            )
                        }
                    }
                    syncMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                }
            },
            confirmButton = {
                Row {
                    if (syncRepository != null && groupCode.isBlank()) {
                        TextButton(onClick = {
                            scope.launch {
                                runCatching {
                                    val code = syncRepository.createGroup()
                                    players.toList().forEach { syncRepository.addPlayer(code, it.name, "player-${it.id}") }
                                    matches.toList().forEach {
                                        syncRepository.saveMatch(code, it.teamA, it.teamB, it.scoreA, it.scoreB, it.id)
                                    }
                                    preferences.edit().putString("group_code", code).apply()
                                    groupCode = code
                                    syncMessage = "Group created. Share the code with your friend."
                                }.onFailure { syncMessage = it.localizedMessage ?: "Could not create group." }
                            }
                        }) { Text("Create group") }
                        TextButton(
                            enabled = inviteCodeInput.length == 10,
                            onClick = {
                                scope.launch {
                                    runCatching {
                                        val code = syncRepository.joinGroup(inviteCodeInput)
                                        preferences.edit().putString("group_code", code).apply()
                                        groupCode = code
                                        syncMessage = "Joined group. Shared players and games are loading."
                                    }.onFailure { syncMessage = it.localizedMessage ?: "Could not join group." }
                                }
                            }
                        ) { Text("Join") }
                    }
                    TextButton(onClick = { showSyncDialog = false }) { Text("Close") }
                }
            }
        )
    }
}

@Composable
private fun NumberField(label: String, value: String, onValue: (String) -> Unit) {
    OutlinedTextField(value, onValue, label = { Text(label) }, modifier = Modifier.fillMaxWidth(),
        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
}


private fun loadPlayers(preferences: android.content.SharedPreferences): List<Player> = runCatching {
    val json = JSONArray(preferences.getString("players", "[]"))
    (0 until json.length()).map { index ->
        val item = json.getJSONObject(index)
        Player(item.getInt("id"), item.getString("name"))
    }
}.getOrDefault(emptyList())

private fun savePlayers(preferences: android.content.SharedPreferences, players: List<Player>) {
    val json = JSONArray()
    players.forEach { player ->
        json.put(JSONObject().put("id", player.id).put("name", player.name))
    }
    preferences.edit().putString("players", json.toString()).apply()
}

private fun loadMatches(preferences: android.content.SharedPreferences): List<PlayedMatch> = runCatching {
    val json = JSONArray(preferences.getString("matches", "[]"))
    (0 until json.length()).map { index ->
        val item = json.getJSONObject(index)
        val teamAJson = item.getJSONArray("teamA")
        val teamBJson = item.getJSONArray("teamB")
        val teamA = (0 until teamAJson.length()).map { teamAJson.getString(it) }
        val teamB = (0 until teamBJson.length()).map { teamBJson.getString(it) }
        PlayedMatch(teamA, teamB, item.getInt("scoreA"), item.getInt("scoreB"), item.optLong("date"), item.optString("id", UUID.randomUUID().toString()))
    }
}.getOrDefault(emptyList())

private fun saveMatches(preferences: android.content.SharedPreferences, matches: List<PlayedMatch>) {
    val json = JSONArray()
    matches.forEach { match ->
        val teamA = JSONArray().apply { match.teamA.forEach { put(it) } }
        val teamB = JSONArray().apply { match.teamB.forEach { put(it) } }
        json.put(JSONObject()
            .put("teamA", teamA)
            .put("teamB", teamB)
            .put("scoreA", match.scoreA)
            .put("scoreB", match.scoreB)
            .put("date", match.date)
            .put("id", match.id))
    }
    preferences.edit().putString("matches", json.toString()).apply()
}
