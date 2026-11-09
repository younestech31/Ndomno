package com.ndomno.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Leaderboard
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Home
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    val mainScrollState = rememberScrollState()

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
    var pendingGroupCode by remember { mutableStateOf(preferences.getString("pending_group_code", "").orEmpty()) }
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

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = BrandGreen,
            onPrimary = Color.White,
            secondary = Color(0xFFC48B32),
            background = Color(0xFFF4F7F4),
            surface = Color.White,
            surfaceVariant = Color(0xFFEAF0EC),
            onSurface = Color(0xFF1E2E26),
            onSurfaceVariant = Color(0xFF63746B),
            error = Color(0xFFB3261E)
        )
    ) {
        Scaffold(
            containerColor = Color(0xFFF4F7F4),
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                modifier = Modifier.size(42.dp),
                                shape = RoundedCornerShape(14.dp),
                                color = BrandGreen.copy(alpha = 0.12f)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Filled.Casino,
                                        contentDescription = null,
                                        tint = BrandGreen,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text("Ndomno", fontWeight = FontWeight.ExtraBold)
                                Text(
                                    "DOMINO SCOREKEEPER",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    actions = {
                        FilledTonalButton(
                            onClick = { showSyncDialog = true },
                            shape = RoundedCornerShape(14.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Filled.CloudSync, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (groupCode.isBlank()) "Sync" else "Shared")
                        }
                        Spacer(Modifier.width(8.dp))
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFFF4F7F4))
                )
            },
            bottomBar = {
                NavigationBar(
                    containerColor = Color.White,
                    tonalElevation = 5.dp
                ) {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                        label = { Text("Game") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = { Icon(Icons.Filled.People, contentDescription = null) },
                        label = { Text("Players") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        icon = { Icon(Icons.Filled.Leaderboard, contentDescription = null) },
                        label = { Text("Rankings") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 3,
                        onClick = { selectedTab = 3 },
                        icon = { Icon(Icons.Filled.History, contentDescription = null) },
                        label = { Text("History") }
                    )
                }
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp, vertical = 14.dp)
                    .then(
                        if (selectedTab == 0 || selectedTab == 2) {
                            Modifier.verticalScroll(mainScrollState)
                        } else Modifier
                    ),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                syncMessage?.let { status ->
                    SyncMessageBanner(
                        message = status,
                        onDismiss = { syncMessage = null }
                    )
                }

                when (selectedTab) {
                    0 -> {
                        PageHeading(
                            title = "New round",
                            subtitle = "Pick four players, choose the winner, and record the points."
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            MetricCard(
                                modifier = Modifier.weight(1f),
                                label = "Players",
                                value = players.size.toString(),
                                hint = "In your roster"
                            )
                            MetricCard(
                                modifier = Modifier.weight(1f),
                                label = "Rounds",
                                value = matches.size.toString(),
                                hint = "Recorded games"
                            )
                        }

                        if (players.size < 4) {
                            Card(
                                shape = RoundedCornerShape(18.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF5E4))
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Filled.PersonAdd, contentDescription = null, tint = Color(0xFF94621E))
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text("You need four players", fontWeight = FontWeight.SemiBold)
                                        Text(
                                            "Add players to your roster before starting a 2 vs 2 round.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    TextButton(onClick = { selectedTab = 1 }) { Text("Add") }
                                }
                            }
                        }

                        TeamSelectionCard(
                            title = "TEAM A",
                            subtitle = "First partnership",
                            players = players,
                            selectedIds = teamAIds,
                            accent = BrandGreen,
                            onToggle = { id, checked ->
                                teamAIds = if (checked) teamAIds + id else teamAIds - id
                                if (checked) teamBIds = teamBIds - id
                            }
                        )

                        TeamSelectionCard(
                            title = "TEAM B",
                            subtitle = "Second partnership",
                            players = players.filter { it.id !in teamAIds || it.id in teamBIds },
                            selectedIds = teamBIds,
                            accent = Color(0xFFB77927),
                            onToggle = { id, checked ->
                                teamBIds = if (checked) teamBIds + id else teamBIds - id
                            }
                        )

                        Card(
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            border = BorderStroke(1.dp, Color(0xFFE5ECE7))
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text("Remaining pips", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Text(
                                        "Enter what each player still has in hand.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text("TEAM A", style = MaterialTheme.typography.labelMedium, color = BrandGreen, fontWeight = FontWeight.Bold)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    NumberField(
                                        teamAIds.getOrNull(0)?.let { id -> players.firstOrNull { it.id == id }?.name } ?: "Player 1",
                                        pipsA1,
                                        { pipsA1 = it },
                                        Modifier.weight(1f)
                                    )
                                    NumberField(
                                        teamAIds.getOrNull(1)?.let { id -> players.firstOrNull { it.id == id }?.name } ?: "Player 2",
                                        pipsA2,
                                        { pipsA2 = it },
                                        Modifier.weight(1f)
                                    )
                                }
                                HorizontalDivider(color = Color(0xFFEDF1EE))
                                Text("TEAM B", style = MaterialTheme.typography.labelMedium, color = Color(0xFFB77927), fontWeight = FontWeight.Bold)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    NumberField(
                                        teamBIds.getOrNull(0)?.let { id -> players.firstOrNull { it.id == id }?.name } ?: "Player 1",
                                        pipsB1,
                                        { pipsB1 = it },
                                        Modifier.weight(1f)
                                    )
                                    NumberField(
                                        teamBIds.getOrNull(1)?.let { id -> players.firstOrNull { it.id == id }?.name } ?: "Player 2",
                                        pipsB2,
                                        { pipsB2 = it },
                                        Modifier.weight(1f)
                                    )
                                }
                            }
                        }

                        val calculatedScore = calculateRoundScoreFromEntries(
                            teamAPips = listOf(pipsA1, pipsA2),
                            teamBPips = listOf(pipsB1, pipsB2),
                            winner = winner
                        )
                        val validSelection = teamAIds.size == 2 &&
                            teamBIds.size == 2 &&
                            teamAIds.toSet().intersect(teamBIds.toSet()).isEmpty()
                        val canSave = validSelection && winner != 0 && calculatedScore != null

                        Card(
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFEAF2ED))
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text("Who won this round?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    FilterChip(
                                        selected = winner == 1,
                                        onClick = { winner = if (winner == 1) 0 else 1 },
                                        label = { Text("Team A won") },
                                        leadingIcon = if (winner == 1) {
                                            { Icon(Icons.Filled.EmojiEvents, contentDescription = null, modifier = Modifier.size(17.dp)) }
                                        } else null
                                    )
                                    FilterChip(
                                        selected = winner == 2,
                                        onClick = { winner = if (winner == 2) 0 else 2 },
                                        label = { Text("Team B won") },
                                        leadingIcon = if (winner == 2) {
                                            { Icon(Icons.Filled.EmojiEvents, contentDescription = null, modifier = Modifier.size(17.dp)) }
                                        } else null
                                    )
                                }
                                ScorePreviewCard(score = calculatedScore, winner = winner)

                                Button(
                                    onClick = {
                                        if (canSave) {
                                            val namesA = teamAIds.mapNotNull { id -> players.firstOrNull { it.id == id }?.name }
                                            val namesB = teamBIds.mapNotNull { id -> players.firstOrNull { it.id == id }?.name }
                                            if (namesA.size == 2 && namesB.size == 2) {
                                                val savedMatch = PlayedMatch(
                                                    teamA = namesA,
                                                    teamB = namesB,
                                                    scoreA = calculatedScore!!.first,
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
                                                val pairingMatches = matches.filter {
                                                    it.teamAIds == teamAIds && it.teamBIds == teamBIds
                                                }
                                                val totalA = pairingMatches.sumOf { it.scoreA }
                                                val totalB = pairingMatches.sumOf { it.scoreB }
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
                                    enabled = canSave,
                                    modifier = Modifier.fillMaxWidth().height(52.dp),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Icon(Icons.Filled.Add, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Save round", fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        message?.let {
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFE5F3EA))
                            ) {
                                Text(
                                    it,
                                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                                    color = BrandGreen,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    1 -> {
                        PageHeading(
                            title = "Players",
                            subtitle = "Your shared roster. Pairings can change every round."
                        )
                        Card(
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            border = BorderStroke(1.dp, Color(0xFFE5ECE7))
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = newName,
                                    onValueChange = { newName = it },
                                    modifier = Modifier.weight(1f),
                                    label = { Text("Player name") },
                                    placeholder = { Text("e.g. Sam") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(14.dp)
                                )
                                FilledTonalButton(
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
                                    },
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.height(52.dp)
                                ) {
                                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Add")
                                }
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("ROSTER", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.weight(1f))
                            Text("${players.size} players", style = MaterialTheme.typography.labelLarge, color = BrandGreen, fontWeight = FontWeight.SemiBold)
                        }

                        if (players.isEmpty()) {
                            EmptyStateCard(
                                icon = { Icon(Icons.Filled.People, contentDescription = null, tint = BrandGreen) },
                                title = "No players yet",
                                subtitle = "Add the people you play dominoes with. You can create new teams every round."
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(bottom = 8.dp)
                            ) {
                                items(players, key = { it.id }) { player ->
                                    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                                        ListItem(
                                            leadingContent = {
                                                Surface(
                                                    modifier = Modifier.size(40.dp),
                                                    shape = CircleShape,
                                                    color = BrandGreen.copy(alpha = 0.10f)
                                                ) {
                                                    Box(contentAlignment = Alignment.Center) {
                                                        Text(
                                                            player.name.firstOrNull()?.uppercase() ?: "?",
                                                            color = BrandGreen,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                    }
                                                }
                                            },
                                            headlineContent = { Text(player.name, fontWeight = FontWeight.SemiBold) },
                                            supportingContent = { Text("Active player", style = MaterialTheme.typography.bodySmall) },
                                            trailingContent = {
                                                Row {
                                                    IconButton(onClick = {
                                                        editingPlayer = player
                                                        editPlayerName = player.name
                                                    }) {
                                                        Icon(Icons.Filled.Edit, contentDescription = "Edit player", tint = BrandGreen)
                                                    }
                                                    IconButton(onClick = { deletingPlayer = player }) {
                                                        Icon(Icons.Filled.Delete, contentDescription = "Delete player", tint = MaterialTheme.colorScheme.error)
                                                    }
                                                }
                                            },
                                            colors = ListItemDefaults.colors(containerColor = Color.White)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    2 -> {
                        val scoresByPlayer = mutableMapOf<String, Int>()
                        matches.forEach { match ->
                            match.teamAIds.forEach { id ->
                                scoresByPlayer[id] = (scoresByPlayer[id] ?: 0) + match.scoreA
                            }
                            match.teamBIds.forEach { id ->
                                scoresByPlayer[id] = (scoresByPlayer[id] ?: 0) + match.scoreB
                            }
                        }
                        val individualRows = scoresByPlayer.entries
                            .sortedByDescending { it.value }
                            .map { entry -> (activeNamesById[entry.key] ?: "Unknown player") to entry.value }

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
                        val partnershipRows = pairScores.entries
                            .sortedByDescending { it.value }
                            .map { entry -> (pairLabels[entry.key] ?: "Unknown team") to entry.value }

                        PageHeading(
                            title = "Rankings",
                            subtitle = "See who is scoring most—individually and as a partnership."
                        )
                        RankingCard(
                            title = "Individual ranking",
                            subtitle = "Points earned by every player",
                            rows = individualRows,
                            accent = BrandGreen
                        )
                        RankingCard(
                            title = "Partnership ranking",
                            subtitle = "Points earned together",
                            rows = partnershipRows,
                            accent = Color(0xFFB77927)
                        )
                    }

                    else -> {
                        PageHeading(
                            title = "Match history",
                            subtitle = "Every saved round, with corrections and deletions."
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${matches.size} rounds recorded",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.weight(1f))
                            Text("Latest first", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (matches.isEmpty()) {
                            EmptyStateCard(
                                icon = { Icon(Icons.Filled.History, contentDescription = null, tint = BrandGreen) },
                                title = "No rounds yet",
                                subtitle = "Your saved games will appear here. Start a new round to build your history."
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                contentPadding = PaddingValues(bottom = 8.dp)
                            ) {
                                items(matches.sortedByDescending { it.date }, key = { it.id }) { match ->
                                    val teamALabel = displayTeamNames(match.teamAIds, match.teamA, players).joinToString(" + ")
                                    val teamBLabel = displayTeamNames(match.teamBIds, match.teamB, players).joinToString(" + ")
                                    RoundHistoryCard(
                                        match = match,
                                        teamALabel = teamALabel,
                                        teamBLabel = teamBLabel,
                                        onEdit = {
                                            editingMatch = match
                                            editScoreA = match.scoreA.toString()
                                            editScoreB = match.scoreB.toString()
                                        },
                                        onDelete = { deletingMatch = match }
                                    )
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
                        if (pendingGroupCode.isNotBlank()) {
                            Text("A group was created, but its initial data upload is not complete.")
                            Text("Pending group code:", style = MaterialTheme.typography.bodyMedium)
                            Text(pendingGroupCode, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            Text("Your local data is still safe. Retry the upload before sharing this code.")
                        } else {
                            Text("Create a group on the phone that has your existing players and game history. Your friend can then join with the code.")
                        }
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
                                    syncMessage = null
                                    runCatching {
                                        val code = pendingGroupCode.ifBlank {
                                            syncRepository.createGroup().also { createdCode ->
                                                pendingGroupCode = createdCode
                                                preferences.edit()
                                                    .putString("pending_group_code", createdCode)
                                                    .apply()
                                            }
                                        }
                                        // Upload the complete local snapshot before enabling listeners.
                                        // This prevents an initial empty cloud snapshot from erasing local data.
                                        syncRepository.uploadInitialData(code, existingPlayers, existingMatches)
                                        code
                                    }.onSuccess { code ->
                                        preferences.edit()
                                            .putString("group_code", code)
                                            .remove("pending_group_code")
                                            .apply()
                                        groupCode = code
                                        pendingGroupCode = ""
                                        syncMessage = "Group created and existing data uploaded. Share the code with your friend."
                                    }.onFailure { error ->
                                        syncMessage = if (pendingGroupCode.isNotBlank()) {
                                            "Initial upload failed. Your local data is safe. Retry with code " +
                                                pendingGroupCode + ". " + (error.localizedMessage ?: "Check your connection.")
                                        } else {
                                            error.localizedMessage ?: "Could not create group."
                                        }
                                    }
                                }
                            }
                        ) { Text(if (pendingGroupCode.isBlank()) "Create group" else "Retry upload") }

                        TextButton(
                            enabled = inviteCodeInput.length == 10 && pendingGroupCode.isBlank(),
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


private val BrandGreen = Color(0xFF176B55)

@Composable
private fun PageHeading(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MetricCard(modifier: Modifier = Modifier, label: String, value: String, hint: String) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Color(0xFFE5ECE7))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = BrandGreen)
            Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TeamSelectionCard(
    title: String,
    subtitle: String,
    players: List<Player>,
    selectedIds: List<String>,
    accent: Color,
    onToggle: (String, Boolean) -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Color(0xFFE5ECE7))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(42.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = accent.copy(alpha = 0.12f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(title.takeLast(1), color = accent, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Surface(shape = CircleShape, color = accent.copy(alpha = 0.12f)) {
                    Text(
                        "${selectedIds.size}/2",
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                        color = accent,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            if (players.isEmpty()) {
                Text("No available players. Add players in the Players tab.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    players.forEach { player ->
                        FilterChip(
                            selected = player.id in selectedIds,
                            enabled = player.id in selectedIds || selectedIds.size < 2,
                            onClick = { onToggle(player.id, player.id !in selectedIds) },
                            label = { Text(player.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        )
                    }
                }
            }
            val selectedNames = selectedIds.mapNotNull { id -> players.firstOrNull { it.id == id }?.name }
            Text(
                text = if (selectedNames.isEmpty()) "Select two players" else selectedNames.joinToString("  +  "),
                style = MaterialTheme.typography.bodyMedium,
                color = if (selectedNames.size == 2) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (selectedNames.size == 2) FontWeight.SemiBold else FontWeight.Normal
            )
        }
    }
}

@Composable
private fun ScorePreviewCard(score: Pair<Int, Int>?, winner: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text("ROUND SCORE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
            Text(
                if (winner == 0) "Select the winner to calculate points" else "Points awarded to the winning team",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = if (winner == 1) BrandGreen.copy(alpha = 0.12f) else Color(0xFFF0F2F0)
        ) {
            Text(
                score?.first?.toString() ?: "—",
                modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
                color = if (winner == 1) BrandGreen else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold
            )
        }
        Text("–", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = if (winner == 2) Color(0xFFFFEED8) else Color(0xFFF0F2F0)
        ) {
            Text(
                score?.second?.toString() ?: "—",
                modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
                color = if (winner == 2) Color(0xFF9A601C) else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold
            )
        }
    }
}

@Composable
private fun RankingCard(
    title: String,
    subtitle: String,
    rows: List<Pair<String, Int>>,
    accent: Color
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Color(0xFFE5ECE7))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (rows.isEmpty()) {
                Text("No scores yet. Record a few rounds to see the rankings.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                rows.forEachIndexed { index, row ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            modifier = Modifier.size(34.dp),
                            shape = CircleShape,
                            color = if (index == 0) accent.copy(alpha = 0.16f) else Color(0xFFF0F3F1)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                if (index == 0) {
                                    Icon(Icons.Filled.EmojiEvents, contentDescription = "First place", tint = accent, modifier = Modifier.size(18.dp))
                                } else {
                                    Text((index + 1).toString(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        Spacer(Modifier.width(11.dp))
                        Text(row.first, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = if (index == 0) FontWeight.SemiBold else FontWeight.Normal)
                        Text(
                            "${row.second} pts",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = accent
                        )
                    }
                    if (index != rows.lastIndex) HorizontalDivider(color = Color(0xFFEDF1EE))
                }
            }
        }
    }
}

@Composable
private fun EmptyStateCard(icon: @Composable () -> Unit, title: String, subtitle: String) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Color(0xFFE5ECE7))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(modifier = Modifier.size(52.dp), shape = CircleShape, color = BrandGreen.copy(alpha = 0.10f)) {
                Box(contentAlignment = Alignment.Center) { icon() }
            }
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun SyncMessageBanner(message: String, onDismiss: () -> Unit) {
    val isError = listOf("error", "failed", "pending").any { message.contains(it, ignoreCase = true) }
    Surface(
        shape = RoundedCornerShape(15.dp),
        color = if (isError) MaterialTheme.colorScheme.errorContainer else Color(0xFFE5F3EA)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) MaterialTheme.colorScheme.onErrorContainer else BrandGreen
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = "Dismiss message", modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun RoundHistoryCard(
    match: PlayedMatch,
    teamALabel: String,
    teamBLabel: String,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Color(0xFFE5ECE7))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (match.date > 0L) SimpleDateFormat("dd MMM yyyy · HH:mm", Locale.getDefault()).format(Date(match.date)) else "Date unavailable",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Edit, contentDescription = "Correct score", tint = BrandGreen, modifier = Modifier.size(19.dp))
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete round", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(19.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("TEAM A", style = MaterialTheme.typography.labelSmall, color = BrandGreen, fontWeight = FontWeight.Bold)
                    Text(teamALabel, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFFF0F4F1)) {
                    Text(
                        "${match.scoreA} – ${match.scoreB}",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("TEAM B", style = MaterialTheme.typography.labelSmall, color = Color(0xFFB77927), fontWeight = FontWeight.Bold)
                    Text(teamBLabel, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End)
                }
            }
        }
    }
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = { input ->
            if (input.length <= 9 && input.all { it.isDigit() }) onValue(input)
        },
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
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
