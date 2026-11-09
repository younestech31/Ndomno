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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

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
        if (FirebaseSyncRepository.isConfigured(context)) {
            runCatching { FirebaseSyncRepository(context) }.getOrNull()
        } else null
    }
    val scope = rememberCoroutineScope()

    val players = remember {
        mutableStateListOf<Player>().apply {
            addAll(loadPlayers(preferences))
        }
    }
    val matches = remember {
        mutableStateListOf<PlayedMatch>().apply {
            addAll(loadMatches(preferences, players.toList()))
        }
    }

    var groupCode by remember { mutableStateOf(preferences.getString("group_code", "").orEmpty()) }
    var showSyncDialog by remember { mutableStateOf(false) }
    var inviteCodeInput by remember { mutableStateOf("") }
    var syncMessage by remember { mutableStateOf<String?>(null) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var newName by remember { mutableStateOf("") }

    var teamAIds by remember { mutableStateOf(listOf<String>()) }
    var teamBIds by remember { mutableStateOf(listOf<String>()) }
    var pipsA1 by remember { mutableStateOf("0") }
    var pipsA2 by remember { mutableStateOf("0") }
    var pipsB1 by remember { mutableStateOf("0") }
    var pipsB2 by remember { mutableStateOf("0") }
    var winner by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }

    var editingPlayer by remember { mutableStateOf<Player?>(null) }
    var editPlayerName by remember { mutableStateOf("") }
    var deletingPlayer by remember { mutableStateOf<Player?>(null) }
    var editingMatch by remember { mutableStateOf<PlayedMatch?>(null) }
    var editScoreA by remember { mutableStateOf("") }
    var editScoreB by remember { mutableStateOf("") }
    var deletingMatch by remember { mutableStateOf<PlayedMatch?>(null) }

    var remotePlayersSnapshot by remember { mutableStateOf<List<SyncedPlayer>?>(null) }
    var remoteMatchesSnapshot by remember { mutableStateOf<List<SyncedMatch>?>(null) }

    // Local SharedPreferences remains a durable copy. Firebase listeners update it
    // only after both synchronized collections have delivered their initial snapshot.
    LaunchedEffect(players.toList(), matches.toList()) {
        savePlayers(preferences, players)
        saveMatches(preferences, matches)
    }

    DisposableEffect(syncRepository, groupCode) {
        remotePlayersSnapshot = null
        remoteMatchesSnapshot = null
        if (syncRepository != null && groupCode.isNotBlank()) {
            val playersListener = syncRepository.listenPlayers(
                groupCode,
                { remotePlayersSnapshot = it },
                { syncMessage = "Player sync error: " + (it.localizedMessage ?: "Unknown error") }
            )
            val matchesListener = syncRepository.listenMatches(
                groupCode,
                { remoteMatchesSnapshot = it },
                { syncMessage = "Game sync error: " + (it.localizedMessage ?: "Unknown error") }
            )
            onDispose {
                playersListener.remove()
                matchesListener.remove()
            }
        } else {
            onDispose { }
        }
    }

    LaunchedEffect(groupCode, remotePlayersSnapshot, remoteMatchesSnapshot) {
        val remotePlayers = remotePlayersSnapshot
        val remoteMatches = remoteMatchesSnapshot
        if (groupCode.isNotBlank() && remotePlayers != null && remoteMatches != null) {
            val syncedPlayers = remotePlayers.map { Player(it.id, it.name) }
            val syncedMatches = remoteMatches.map { remote ->
                val idsA = if (remote.teamAIds.size == 2) {
                    remote.teamAIds
                } else {
                    resolveLegacyPlayerIds(remote.teamA, syncedPlayers)
                }
                val idsB = if (remote.teamBIds.size == 2) {
                    remote.teamBIds
                } else {
                    resolveLegacyPlayerIds(remote.teamB, syncedPlayers)
                }
                PlayedMatch(
                    teamA = remote.teamA,
                    teamB = remote.teamB,
                    scoreA = remote.scoreA,
                    scoreB = remote.scoreB,
                    date = remote.createdAtMillis,
                    id = remote.id,
                    teamAIds = idsA,
                    teamBIds = idsB
                )
            }

            // Upgrade pre-ID cloud rounds once, preserving their scores, order and names.
            if (syncRepository != null) {
                remoteMatches.filter {
                    it.teamAIds.size != 2 || it.teamBIds.size != 2
                }.forEach { legacy ->
                    val migrated = syncedMatches.firstOrNull { it.id == legacy.id }
                    if (migrated != null) {
                        scope.launch {
                            runCatching {
                                syncRepository.updateMatch(
                                    groupCode,
                                    migrated.id,
                                    migrated.teamA,
                                    migrated.teamB,
                                    migrated.teamAIds,
                                    migrated.teamBIds,
                                    migrated.scoreA,
                                    migrated.scoreB
                                )
                            }.onFailure {
                                syncMessage = "Old round kept locally; cloud ID migration pending: " +
                                    (it.localizedMessage ?: "Unknown error")
                            }
                        }
                    }
                }
            }

            players.clear()
            players.addAll(syncedPlayers)
            matches.clear()
            matches.addAll(syncedMatches)
        }
    }

    val activeNamesById = buildMap {
        players.forEach { put(it.id, it.name) }
        matches.forEach { match ->
            match.teamAIds.forEachIndexed { index, id ->
                if (!containsKey(id)) put(id, match.teamA.getOrNull(index) ?: "Unknown player")
            }
            match.teamBIds.forEachIndexed { index, id ->
                if (!containsKey(id)) put(id, match.teamB.getOrNull(index) ?: "Unknown player")
            }
        }
    }

    MaterialTheme(colorScheme = lightColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF176B55))) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Ndomno", fontWeight = FontWeight.Bold) },
                    actions = {
                        TextButton(onClick = { showSyncDialog = true }) {
                            Text(if (groupCode.isBlank()) "Sync" else "Shared")
                        }
                    }
                )
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                TabRow(selectedTabIndex = selectedTab) {
                    listOf("New game", "Players", "Rankings", "History").forEachIndexed { index, label ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(label) }
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))

                when (selectedTab) {
                    0 -> {
                        Text("Create a 2 vs 2 match", style = MaterialTheme.typography.titleLarge)
                        Text("Choose any four players. Partnerships can change every game.")
                        Spacer(Modifier.height(12.dp))

                        Text("Team A", fontWeight = FontWeight.Bold)
                        players.forEach { player ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = player.id in teamAIds,
                                    onCheckedChange = { checked ->
                                        teamAIds = if (checked) teamAIds + player.id else teamAIds - player.id
                                        if (checked) teamBIds = teamBIds - player.id
                                    },
                                    enabled = player.id in teamAIds || teamAIds.size < 2
                                )
                                Text(player.name)
                            }
                        }
                        Text(
                            "Team A: " + teamAIds.mapNotNull { id -> players.firstOrNull { it.id == id }?.name }
                                .joinToString(" + ").ifBlank { "Choose two players" }
                        )

                        Spacer(Modifier.height(6.dp))
                        Text("Team B — select two different players", fontWeight = FontWeight.Bold)
                        players.filter { it.id !in teamAIds }.forEach { player ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = player.id in teamBIds,
                                    onCheckedChange = { checked ->
                                        teamBIds = if (checked) teamBIds + player.id else teamBIds - player.id
                                    },
                                    enabled = player.id in teamBIds || teamBIds.size < 2
                                )
                                Text(player.name)
                            }
                        }
                        Text(
                            "Team B: " + teamBIds.mapNotNull { id -> players.firstOrNull { it.id == id }?.name }
                                .joinToString(" + ").ifBlank { "Choose two players" }
                        )

                        HorizontalDivider(Modifier.padding(vertical = 10.dp))
                        Text("Round scoring", fontWeight = FontWeight.Bold)
                        Text("Enter the remaining pips for each player. The winner scores the opponents' combined pips.")
                        NumberField("Team A player 1 pips", pipsA1) { pipsA1 = it }
                        NumberField("Team A player 2 pips", pipsA2) { pipsA2 = it }
                        NumberField("Team B player 1 pips", pipsB1) { pipsB1 = it }
                        NumberField("Team B player 2 pips", pipsB2) { pipsB2 = it }
                        Row {
                            FilterChip(
                                selected = winner == 1,
                                onClick = { winner = 1 },
                                label = { Text("Team A won") }
                            )
                            Spacer(Modifier.width(8.dp))
                            FilterChip(
                                selected = winner == 2,
                                onClick = { winner = 2 },
                                label = { Text("Team B won") }
                            )
                        }

                        val parsedPips = listOf(pipsA1, pipsA2, pipsB1, pipsB2).map { it.toIntOrNull() }
                        val validPips = parsedPips.all { it != null && it >= 0 }
                        val calculatedScore = if (validPips) {
                            calculateRoundScore(
                                parsedPips[0]!! + parsedPips[1]!!,
                                parsedPips[2]!! + parsedPips[3]!!,
                                winner
                            )
                        } else {
                            0 to 0
                        }
                        val roundPoints = if (winner == 1) calculatedScore.first else calculatedScore.second
                        Text("Round points: " + roundPoints)

                        Button(
                            onClick = {
                                if (
                                    teamAIds.size == 2 && teamBIds.size == 2 &&
                                    teamAIds.toSet().intersect(teamBIds.toSet()).isEmpty() &&
                                    winner != 0 && validPips
                                ) {
                                    val namesA = teamAIds.mapNotNull { id -> players.firstOrNull { it.id == id }?.name }
                                    val namesB = teamBIds.mapNotNull { id -> players.firstOrNull { it.id == id }?.name }
                                    if (namesA.size == 2 && namesB.size == 2) {
                                        val savedMatch = PlayedMatch(
                                            teamA = namesA,
                                            teamB = namesB,
                                            scoreA = calculatedScore.first,
                                            scoreB = calculatedScore.second,
                                            id = UUID.randomUUID().toString(),
                                            teamAIds = teamAIds.toList(),
                                            teamBIds = teamBIds.toList()
                                        )
                                        matches.add(savedMatch)
                                        if (groupCode.isNotBlank() && syncRepository != null) {
                                            scope.launch {
                                                runCatching {
                                                    syncRepository.saveMatch(
                                                        groupCode,
                                                        savedMatch.teamA,
                                                        savedMatch.teamB,
                                                        savedMatch.teamAIds,
                                                        savedMatch.teamBIds,
                                                        savedMatch.scoreA,
                                                        savedMatch.scoreB,
                                                        savedMatch.id
                                                    )
                                                }.onFailure {
                                                    syncMessage = "Round kept on this phone; cloud sync failed: " +
                                                        (it.localizedMessage ?: "Unknown error")
                                                }
                                            }
                                        }
                                        val totalA = matches.filter {
                                            it.teamAIds == teamAIds && it.teamBIds == teamBIds
                                        }.sumOf { it.scoreA }
                                        val totalB = matches.filter {
                                            it.teamAIds == teamAIds && it.teamBIds == teamBIds
                                        }.sumOf { it.scoreB }
                                        message = "Round saved. Pairing total: " + totalA + " – " + totalB
                                        pipsA1 = "0"
                                        pipsA2 = "0"
                                        pipsB1 = "0"
                                        pipsB2 = "0"
                                        winner = 0
                                    } else {
                                        message = "Choose four active players."
                                    }
                                } else {
                                    message = "Choose two players per team, a winner, and valid pip counts."
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Save round")
                        }
                        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                    }

                    1 -> {
                        Text("Player list", style = MaterialTheme.typography.titleLarge)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = newName,
                                onValueChange = { newName = it },
                                modifier = Modifier.weight(1f),
                                label = { Text("Player name") },
                                singleLine = true
                            )
                            Spacer(Modifier.width(8.dp))
                            Button(
                                enabled = newName.trim().isNotEmpty() && players.none {
                                    it.name.equals(newName.trim(), ignoreCase = true)
                                },
                                onClick = {
                                    val name = newName.trim()
                                    if (name.isNotEmpty()) {
                                        val addedPlayer = Player(UUID.randomUUID().toString(), name)
                                        players.add(addedPlayer)
                                        if (groupCode.isNotBlank() && syncRepository != null) {
                                            scope.launch {
                                                runCatching {
                                                    syncRepository.addPlayer(groupCode, name, addedPlayer.id)
                                                }.onFailure {
                                                    syncMessage = "Player saved locally; cloud sync failed: " +
                                                        (it.localizedMessage ?: "Unknown error")
                                                }
                                            }
                                        }
                                        newName = ""
                                    }
                                }
                            ) { Text("Add") }
                        }
                        Spacer(Modifier.height(8.dp))
                        if (players.isEmpty()) {
                            Text("No players yet. Add the players who take part in your games.")
                        } else {
                            LazyColumn(Modifier.weight(1f)) {
                                items(players, key = { it.id }) { player ->
                                    ListItem(
                                        headlineContent = { Text(player.name) },
                                        trailingContent = {
                                            Row {
                                                TextButton(onClick = {
                                                    editingPlayer = player
                                                    editPlayerName = player.name
                                                }) { Text("Edit") }
                                                TextButton(onClick = { deletingPlayer = player }) {
                                                    Text("Delete", color = MaterialTheme.colorScheme.error)
                                                }
                                            }
                                        }
                                    )
                                    HorizontalDivider()
                                }
                            }
                        }
                        Text(
                            "Deleting a player removes them from future selections. Their previous rounds and ranking history are retained.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    2 -> {
                        Text("Individual ranking", style = MaterialTheme.typography.titleLarge)
                        val scoresByPlayer = mutableMapOf<String, Int>()
                        matches.forEach { match ->
                            match.teamAIds.forEach { id ->
                                scoresByPlayer[id] = (scoresByPlayer[id] ?: 0) + match.scoreA
                            }
                            match.teamBIds.forEach { id ->
                                scoresByPlayer[id] = (scoresByPlayer[id] ?: 0) + match.scoreB
                            }
                        }
                        scoresByPlayer.entries
                            .sortedByDescending { it.value }
                            .forEachIndexed { index, entry ->
                                Text(
                                    (index + 1).toString() + ". " +
                                        (activeNamesById[entry.key] ?: "Unknown player") +
                                        " — " + entry.value + " pts",
                                    Modifier.padding(6.dp)
                                )
                            }

                        Spacer(Modifier.height(12.dp))
                        Text("Partnership ranking", style = MaterialTheme.typography.titleLarge)
                        val pairScores = mutableMapOf<String, Int>()
                        val pairLabels = mutableMapOf<String, String>()
                        matches.forEach { match ->
                            listOf(
                                match.teamAIds to match.scoreA,
                                match.teamBIds to match.scoreB
                            ).forEach { (ids, score) ->
                                val key = ids.sorted().joinToString("|")
                                pairScores[key] = (pairScores[key] ?: 0) + score
                                pairLabels[key] = ids.sorted().joinToString(" + ") {
                                    activeNamesById[it] ?: "Unknown player"
                                }
                            }
                        }
                        pairScores.entries
                            .sortedByDescending { it.value }
                            .forEachIndexed { index, entry ->
                                Text(
                                    (index + 1).toString() + ". " +
                                        (pairLabels[entry.key] ?: "Unknown team") +
                                        " — " + entry.value + " pts",
                                    Modifier.padding(6.dp)
                                )
                            }
                    }

                    else -> {
                        Text("Match history", style = MaterialTheme.typography.titleLarge)
                        if (matches.isEmpty()) {
                            Text("Saved rounds will appear here.")
                        } else {
                            LazyColumn(Modifier.weight(1f)) {
                                items(matches.sortedByDescending { it.date }, key = { it.id }) { match ->
                                    val teamALabel = displayTeamNames(match.teamAIds, match.teamA, players)
                                        .joinToString(" + ")
                                    val teamBLabel = displayTeamNames(match.teamBIds, match.teamB, players)
                                        .joinToString(" + ")
                                    ListItem(
                                        headlineContent = { Text(teamALabel + " vs " + teamBLabel) },
                                        supportingContent = {
                                            Text("Score: " + match.scoreA + " – " + match.scoreB)
                                        },
                                        trailingContent = {
                                            Row {
                                                TextButton(onClick = {
                                                    editingMatch = match
                                                    editScoreA = match.scoreA.toString()
                                                    editScoreB = match.scoreB.toString()
                                                }) { Text("Edit") }
                                                TextButton(onClick = { deletingMatch = match }) {
                                                    Text("Delete", color = MaterialTheme.colorScheme.error)
                                                }
                                            }
                                        }
                                    )
                                    HorizontalDivider()
                                }
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
                        Text(
                            "Firebase is not connected yet. Add app/google-services.json, enable Anonymous sign-in, create a Firestore database, and publish firestore.rules."
                        )
                    } else if (groupCode.isNotBlank()) {
                        Text("This phone is linked to shared group:")
                        Text(groupCode, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text("Give this code to your friend. They can enter it on their phone to join.")
                        Text("Players, round corrections and deletions sync through this shared group.")
                    } else {
                        Text("Create a group on the phone that has your existing players and game history. Your friend can then join with the code.")
                        OutlinedTextField(
                            value = inviteCodeInput,
                            onValueChange = {
                                inviteCodeInput = it.uppercase().filter { char -> char.isLetterOrDigit() }.take(10)
                            },
                            label = { Text("Friend's 10-character code") },
                            singleLine = true
                        )
                    }
                    syncMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                }
            },
            confirmButton = {
                Row {
                    if (syncRepository != null && groupCode.isBlank()) {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    val existingPlayers = players.toList()
                                    val existingMatches = matches.toList()
                                    runCatching { syncRepository.createGroup() }
                                        .onSuccess { code ->
                                            preferences.edit().putString("group_code", code).apply()
                                            groupCode = code
                                            var failures = 0
                                            existingPlayers.forEach { player ->
                                                runCatching {
                                                    syncRepository.addPlayer(code, player.name, player.id)
                                                }.onFailure { failures++ }
                                            }
                                            existingMatches.forEach { match ->
                                                runCatching {
                                                    syncRepository.saveMatch(
                                                        code,
                                                        match.teamA,
                                                        match.teamB,
                                                        match.teamAIds,
                                                        match.teamBIds,
                                                        match.scoreA,
                                                        match.scoreB,
                                                        match.id
                                                    )
                                                }.onFailure { failures++ }
                                            }
                                            syncMessage = if (failures == 0) {
                                                "Group created and existing data uploaded. Share the code with your friend."
                                            } else {
                                                "Group created, but " + failures +
                                                    " records did not upload. Check the sync error and try again."
                                            }
                                        }
                                        .onFailure {
                                            syncMessage = it.localizedMessage ?: "Could not create group."
                                        }
                                }
                            }
                        ) { Text("Create group") }

                        TextButton(
                            enabled = inviteCodeInput.length == 10,
                            onClick = {
                                scope.launch {
                                    runCatching { syncRepository.joinGroup(inviteCodeInput) }
                                        .onSuccess { code ->
                                            preferences.edit().putString("group_code", code).apply()
                                            groupCode = code
                                            syncMessage = "Joined group. Shared players and games are loading."
                                        }
                                        .onFailure {
                                            syncMessage = it.localizedMessage ?: "Could not join group."
                                        }
                                }
                            }
                        ) { Text("Join") }
                    }
                    TextButton(onClick = { showSyncDialog = false }) { Text("Close") }
                }
            }
        )
    }

    editingPlayer?.let { player ->
        AlertDialog(
            onDismissRequest = { editingPlayer = null },
            title = { Text("Edit player") },
            text = {
                OutlinedTextField(
                    value = editPlayerName,
                    onValueChange = { editPlayerName = it },
                    label = { Text("Player name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    enabled = editPlayerName.trim().isNotEmpty() &&
                        players.none {
                            it.id != player.id &&
                                it.name.equals(editPlayerName.trim(), ignoreCase = true)
                        },
                    onClick = {
                        val updated = player.copy(name = editPlayerName.trim())
                        val index = players.indexOfFirst { it.id == player.id }
                        if (index >= 0) players[index] = updated
                        editingPlayer = null
                        if (groupCode.isNotBlank() && syncRepository != null) {
                            scope.launch {
                                runCatching {
                                    syncRepository.updatePlayer(groupCode, player.id, updated.name)
                                }.onFailure {
                                    syncMessage = "Player edit kept locally; cloud update failed: " +
                                        (it.localizedMessage ?: "Unknown error")
                                }
                            }
                        }
                    }
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editingPlayer = null }) { Text("Cancel") }
            }
        )
    }

    deletingPlayer?.let { player ->
        AlertDialog(
            onDismissRequest = { deletingPlayer = null },
            title = { Text("Delete player?") },
            text = {
                Text(
                    "Remove " + player.name +
                        " from the active player list? Their previous rounds and rankings will remain in history."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        players.removeAll { it.id == player.id }
                        teamAIds = teamAIds - player.id
                        teamBIds = teamBIds - player.id
                        deletingPlayer = null
                        if (groupCode.isNotBlank() && syncRepository != null) {
                            scope.launch {
                                runCatching {
                                    syncRepository.deletePlayer(groupCode, player.id)
                                }.onFailure {
                                    syncMessage = "Player removed locally; cloud deletion failed: " +
                                        (it.localizedMessage ?: "Unknown error")
                                }
                            }
                        }
                    }
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deletingPlayer = null }) { Text("Cancel") }
            }
        )
    }

    editingMatch?.let { match ->
        AlertDialog(
            onDismissRequest = { editingMatch = null },
            title = { Text("Correct round score") },
            text = {
                Column {
                    Text(
                        displayTeamNames(match.teamAIds, match.teamA, players).joinToString(" + ") +
                            " vs " +
                            displayTeamNames(match.teamBIds, match.teamB, players).joinToString(" + ")
                    )
                    NumberField("Team A points", editScoreA) { editScoreA = it }
                    NumberField("Team B points", editScoreB) { editScoreB = it }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = (editScoreA.toIntOrNull() ?: -1) >= 0 &&
                        (editScoreB.toIntOrNull() ?: -1) >= 0,
                    onClick = {
                        val updated = match.copy(
                            scoreA = editScoreA.toInt(),
                            scoreB = editScoreB.toInt()
                        )
                        val index = matches.indexOfFirst { it.id == match.id }
                        if (index >= 0) matches[index] = updated
                        editingMatch = null
                        if (groupCode.isNotBlank() && syncRepository != null) {
                            scope.launch {
                                runCatching {
                                    syncRepository.updateMatch(
                                        groupCode,
                                        updated.id,
                                        updated.teamA,
                                        updated.teamB,
                                        updated.teamAIds,
                                        updated.teamBIds,
                                        updated.scoreA,
                                        updated.scoreB
                                    )
                                }.onFailure {
                                    syncMessage = "Score correction kept locally; cloud update failed: " +
                                        (it.localizedMessage ?: "Unknown error")
                                }
                            }
                        }
                    }
                ) { Text("Save correction") }
            },
            dismissButton = {
                TextButton(onClick = { editingMatch = null }) { Text("Cancel") }
            }
        )
    }

    deletingMatch?.let { match ->
        AlertDialog(
            onDismissRequest = { deletingMatch = null },
            title = { Text("Delete round?") },
            text = { Text("This removes the round and updates both individual and partnership rankings.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        matches.removeAll { it.id == match.id }
                        deletingMatch = null
                        if (groupCode.isNotBlank() && syncRepository != null) {
                            scope.launch {
                                runCatching {
                                    syncRepository.deleteMatch(groupCode, match.id)
                                }.onFailure {
                                    syncMessage = "Round removed locally; cloud deletion failed: " +
                                        (it.localizedMessage ?: "Unknown error")
                                }
                            }
                        }
                    }
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deletingMatch = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun NumberField(label: String, value: String, onValue: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )
}

private fun loadPlayers(preferences: android.content.SharedPreferences): List<Player> = runCatching {
    val json = JSONArray(preferences.getString("players", "[]"))
    (0 until json.length()).map { index ->
        val item = json.getJSONObject(index)
        val rawId = item.opt("id")
        val id = when (rawId) {
            is Number -> legacyLocalPlayerId(rawId.toInt())
            is String -> rawId.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
            else -> UUID.randomUUID().toString()
        }
        Player(id, item.getString("name"))
    }.distinctBy { it.id }
}.getOrDefault(emptyList())

private fun savePlayers(preferences: android.content.SharedPreferences, players: List<Player>) {
    val json = JSONArray()
    players.forEach { player ->
        json.put(JSONObject().put("id", player.id).put("name", player.name))
    }
    preferences.edit().putString("players", json.toString()).apply()
}

private fun loadMatches(
    preferences: android.content.SharedPreferences,
    players: List<Player>
): List<PlayedMatch> = runCatching {
    val json = JSONArray(preferences.getString("matches", "[]"))
    (0 until json.length()).map { index ->
        val item = json.getJSONObject(index)
        val teamAJson = item.getJSONArray("teamA")
        val teamBJson = item.getJSONArray("teamB")
        val teamA = (0 until teamAJson.length()).map { teamAJson.getString(it) }
        val teamB = (0 until teamBJson.length()).map { teamBJson.getString(it) }
        val storedIdsA = item.optJSONArray("teamAIds")
        val storedIdsB = item.optJSONArray("teamBIds")
        val idsA = if (storedIdsA != null && storedIdsA.length() == 2) {
            (0 until storedIdsA.length()).map { storedIdsA.getString(it) }
        } else {
            resolveLegacyPlayerIds(teamA, players)
        }
        val idsB = if (storedIdsB != null && storedIdsB.length() == 2) {
            (0 until storedIdsB.length()).map { storedIdsB.getString(it) }
        } else {
            resolveLegacyPlayerIds(teamB, players)
        }
        PlayedMatch(
            teamA = teamA,
            teamB = teamB,
            scoreA = item.getInt("scoreA"),
            scoreB = item.getInt("scoreB"),
            date = item.optLong("date", 0L),
            id = item.optString("id").takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
            teamAIds = idsA,
            teamBIds = idsB
        )
    }
}.getOrDefault(emptyList())

private fun saveMatches(
    preferences: android.content.SharedPreferences,
    matches: List<PlayedMatch>
) {
    val json = JSONArray()
    matches.forEach { match ->
        val teamA = JSONArray().apply { match.teamA.forEach { put(it) } }
        val teamB = JSONArray().apply { match.teamB.forEach { put(it) } }
        val teamAIds = JSONArray().apply { match.teamAIds.forEach { put(it) } }
        val teamBIds = JSONArray().apply { match.teamBIds.forEach { put(it) } }
        json.put(
            JSONObject()
                .put("teamA", teamA)
                .put("teamB", teamB)
                .put("teamAIds", teamAIds)
                .put("teamBIds", teamBIds)
                .put("scoreA", match.scoreA)
                .put("scoreB", match.scoreB)
                .put("date", match.date)
                .put("id", match.id)
        )
    }
    preferences.edit().putString("matches", json.toString()).apply()
}
