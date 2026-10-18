package com.chesstutor.nativeapp

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

private val Surface = Color(0xff262421)
private val Subtle = Color(0xff1e1d1a)
private val Border = Color(0xff3d3a34)
private val Accent = Color(0xff8bb34f)
private val Muted = Color(0xffa1a1aa)

@Composable internal fun ImmersiveDialogWindow() {
    val view = LocalView.current
    DisposableEffect(view) {
        (view.parent as? DialogWindowProvider)?.window?.let(::hideSystemBars)
        onDispose { }
    }
}

@Composable internal fun CenterDialog(title: String, dismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        ImmersiveDialogWindow()
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp).imePadding().heightIn(max = 680.dp)
            .clip(RoundedCornerShape(18.dp)).background(Surface).border(1.dp, Border, RoundedCornerShape(18.dp))) {
            Row(Modifier.fillMaxWidth().height(60.dp).padding(start = 18.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = dismiss) { Icon(Icons.Outlined.Close, "Close dialog", tint = Muted) }
            }
            HorizontalDivider(color = Border)
            content()
        }
    }
}
@Composable internal fun ImportDialog(state: UiState, onDismiss: () -> Unit, fetch: (String, String) -> Unit, analyze: (String) -> Unit) {
    var online by rememberSaveable { mutableStateOf(true) }
    var provider by rememberSaveable { mutableStateOf("chesscom") }
    var username by rememberSaveable { mutableStateOf("") }
    var pgn by rememberSaveable { mutableStateOf("") }
    var fileError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val downloads = if (state.downloadAccount == "downloads-$provider-${username.trim().lowercase()}") state.downloads else emptyList()
    val scope = rememberCoroutineScope()
    val document = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= 1_000_000) { "PGN file exceeds 1 MB." }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray().toString(Charsets.UTF_8)
                    } ?: error("Could not read PGN file.")
                }
                pgn = text; fileError = null
            } catch (error: Exception) { fileError = error.message }
        }
    }
    CenterDialog("Import Chess Game", onDismiss) {
        Row(Modifier.fillMaxWidth().height(62.dp)) {
            ImportTab("Paste or Upload PGN", Icons.Outlined.Description, !online, Modifier.weight(1f)) { online = false }
            ImportTab("Chess.com / Lichess", Icons.Outlined.Public, online, Modifier.weight(1f)) { online = true }
        }
        HorizontalDivider(color = Border)
        Column(Modifier.fillMaxWidth().heightIn(min = 210.dp, max = 500.dp).verticalScroll(rememberScrollState()).padding(16.dp)) {
            if (online) {
                Choice("", listOf("chesscom" to "Chess.com", "lichess" to "Lichess"), provider) { provider = it }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(username, { username = it.take(50) }, singleLine = true, placeholder = { Text("Enter player username…", fontSize = 14.sp) },
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(13.dp), colors = inputColors())
                    PrimaryButton(if (state.importing) "…" else "⌕ Fetch", enabled = !state.importing && username.trim().length >= 2) { fetch(provider, username) }
                }
                if (state.importing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp), color = Accent)
                if (state.error != null) Text(state.error, color = Color(0xffffbc99), fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
                if (downloads.isEmpty()) Text("Enter a username to browse recent online games.", color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 44.dp))
                for (game in downloads) Column(Modifier.fillMaxWidth().padding(top = 10.dp).background(Subtle, RoundedCornerShape(13.dp)).border(1.dp, Border, RoundedCornerShape(13.dp)).padding(12.dp)) {
                    val whiteElo = Regex("\\[WhiteElo \"(\\d+)\"\\]").find(game.pgn)?.groupValues?.get(1)
                    val blackElo = Regex("\\[BlackElo \"(\\d+)\"\\]").find(game.pgn)?.groupValues?.get(1)
                    Text("${game.white}${whiteElo?.let { " ($it)" } ?: ""} vs ${game.black}${blackElo?.let { " ($it)" } ?: ""}", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text("${game.speed} · ${resultText(game.result)}", fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 3.dp))
                    PrimaryButton("Analyze", Modifier.fillMaxWidth().padding(top = 9.dp)) { analyze(game.pgn) }
                }
            } else {
                Text("Paste your game notation or choose a PGN file.", fontSize = 12.sp, color = Muted)
                OutlinedTextField(pgn, { if (it.length <= 1_000_000) pgn = it else fileError = "PGN exceeds 1 MB." }, placeholder = { Text("1. e4 e5 2. Nf3 Nc6…") },
                    modifier = Modifier.fillMaxWidth().height(170.dp).padding(top = 12.dp), shape = RoundedCornerShape(12.dp), colors = inputColors())
                OutlinedButton(onClick = { document.launch(arrayOf("application/x-chess-pgn", "application/octet-stream", "text/*")) }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                    Icon(Icons.Outlined.UploadFile, null, Modifier.size(20.dp)); Text("Choose PGN file", Modifier.padding(start = 8.dp))
                }
                if (fileError != null) Text(fileError!!, fontSize = 12.sp, color = Color(0xffffbc99))
                PrimaryButton("Analyze game", Modifier.fillMaxWidth().padding(top = 12.dp), pgn.isNotBlank()) { analyze(pgn) }
            }
        }
    }
}
@Composable private fun ImportTab(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, active: Boolean, modifier: Modifier, action: () -> Unit) {
    Column(modifier.fillMaxHeight().clickable(onClick = action), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, null, tint = if (active) Color.White else Muted, modifier = Modifier.size(18.dp))
        Text(label, color = if (active) Color.White else Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 5.dp))
        Spacer(Modifier.height(8.dp)); HorizontalDivider(color = if (active) Accent else Color.Transparent, thickness = 2.dp)
    }
}
@Composable internal fun SettingsDialog(state: UiState, update: (String, Boolean, Int, Boolean, Boolean, Int) -> Unit, dismiss: () -> Unit) {
    CenterDialog("Settings", dismiss) { Column(Modifier.padding(18.dp).verticalScroll(rememberScrollState())) {
        fun change(theme: String = state.theme, sound: Boolean = state.sound, depth: Int = state.depth,
                   complete: Boolean = state.completeExplanation, names: Boolean = state.usePieceNames, limit: Int = state.savedGameLimit) {
            update(theme, sound, depth, complete, names, limit)
        }
        Choice("Board style", listOf("Green" to "Green", "Wood" to "Wood", "Ocean" to "Ocean"), state.theme) { change(theme = it) }
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Move sounds", fontWeight = FontWeight.Bold); Text("Soft move and capture sounds", fontSize = 12.sp, color = Muted) }
            Switch(state.sound, { change(sound = it) })
        }
        SettingSwitch("Complete explanation", "Full explanation of purpose, threats and alternatives. Turn off for short summaries.", state.completeExplanation) { change(complete = it) }
        SettingSwitch("Use piece names", "Read Knight to f3 instead of Nf3 in coaching.", state.usePieceNames) { change(names = it) }
        Choice("Saved game limit", listOf("3" to "3 games", "5" to "5 games", "10" to "10 games", "30" to "30 games"), state.savedGameLimit.toString(), Modifier.padding(top = 16.dp)) { change(limit = it.toInt()) }
        Text("On the next save, only the newest ${state.savedGameLimit} games are kept. Existing games stay until then, or you can delete them from Saved games.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 8.dp))
        Choice("Analysis depth", (10..22).map { it.toString() to if (it == 16) "$it · Desktop default" else it.toString() }, state.depth.toString(), Modifier.padding(top = 16.dp)) { change(depth = it.toInt()) }
        Text("Stockfish 19 and coaching run on your phone. Internet needed only for public game downloads.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 22.dp))
        Text("Made by Priyank Shaw", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.padding(top = 16.dp))
    } }
}
@Composable private fun SettingSwitch(title: String, description: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(description, color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
        }
        Switch(checked, change, modifier = Modifier.semantics { contentDescription = title })
    }
}
@Composable internal fun AnalysisScreen(state: UiState, cancel: () -> Unit, retry: () -> Unit) {
    val startsBlack = if (state.history.firstOrNull()?.before?.split(' ')?.getOrNull(1) == "b") 1 else 0
    val totalMoves = (state.total + startsBlack + 1) / 2
    val completedMoves = if (state.progress >= state.total) totalMoves else (state.progress + startsBlack) / 2
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(120.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xff9bb377)), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) { val cell = size.width / 4; for (row in 0..3) for (col in 0..3) if ((col + row) % 2 == 1) drawRect(Color(0xff657e47), androidx.compose.ui.geometry.Offset(col * cell, row * cell), androidx.compose.ui.geometry.Size(cell, cell)) }
            Box(Modifier.size(74.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xff314722)), contentAlignment = Alignment.Center) {
                Image(painterResource(R.drawable.wk), null, Modifier.size(64.dp))
            }
        }
        Text("Analysing your game", fontWeight = FontWeight.ExtraBold, fontSize = 25.sp, modifier = Modifier.padding(top = 28.dp))
        Text("Finding the best moves and explaining your choices.", color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp, bottom = 20.dp))
        Column(Modifier.fillMaxWidth().background(Subtle, RoundedCornerShape(13.dp)).border(1.dp, Border, RoundedCornerShape(13.dp)).padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${state.white}  vs  ${state.black}", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text("$totalMoves moves", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }
        if (state.total == 0) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 18.dp), color = Accent, trackColor = Border)
        else LinearProgressIndicator(progress = { state.progress.toFloat() / state.total }, Modifier.fillMaxWidth().padding(top = 18.dp).height(8.dp).clip(RoundedCornerShape(4.dp)), color = Accent, trackColor = Border)
        Text("$completedMoves / $totalMoves moves analysed", fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 20.dp))
        Text("Your review will open when ready.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
        if (state.error != null) { Text(state.error, color = Color(0xffffbc99), modifier = Modifier.padding(top = 16.dp)); PrimaryButton("Retry analysis", Modifier.padding(top = 12.dp), action = retry) }
        TextButton(cancel, Modifier.padding(top = 20.dp)) { Text("Cancel", color = Muted) }
    }
}
@Composable internal fun ReviewSummary(state: UiState, start: () -> Unit) {
    val analysis = state.analysis
    val accuracy = analysis?.optJSONObject("accuracy")
    val counts = analysis?.optJSONObject("counts")
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth().height(90.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Players", fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
            SummaryPlayer(state.white, false, state.whiteRating, Modifier.weight(1f)); SummaryPlayer(state.black, true, state.blackRating, Modifier.weight(1f))
        }
        SummaryMetric("Accuracy", accuracy?.optDouble("white")?.let { String.format(Locale.US, "%.1f%%", it) } ?: "—",
            accuracy?.optDouble("black")?.let { String.format(Locale.US, "%.1f%%", it) } ?: "—")
        HorizontalDivider(color = Border, modifier = Modifier.padding(vertical = 12.dp))
        for ((type, grade) in grades) Row(Modifier.fillMaxWidth().height(42.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(grade.name, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1.35f))
            Text("${counts?.optJSONObject("white")?.optInt(type) ?: 0}", color = grade.color, fontWeight = FontWeight.ExtraBold, fontSize = 23.sp, modifier = Modifier.weight(.6f), textAlign = TextAlign.Center)
            GradeBadge(type, 25.dp)
            Text("${counts?.optJSONObject("black")?.optInt(type) ?: 0}", color = grade.color, fontWeight = FontWeight.ExtraBold, fontSize = 23.sp, modifier = Modifier.weight(.9f), textAlign = TextAlign.Center)
        }
        HorizontalDivider(color = Border, modifier = Modifier.padding(vertical = 12.dp))
        SummaryMetric("Est. rating", accuracy?.optInt("white_performance")?.toString() ?: "—", accuracy?.optInt("black_performance")?.toString() ?: "—")
        }
        PrimaryButton("▶  Start Review", Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp), lightText = true, action = start)
    }
}
@Composable private fun SummaryPlayer(name: String, black: Boolean, rating: Int?, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(name, fontWeight = FontWeight.Bold, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1)
        Box(Modifier.padding(top = 8.dp).size(50.dp).background(if (black) Color(0xff44443f) else Color(0xffe8e8e4), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.PersonOutline, null, tint = Muted, modifier = Modifier.size(34.dp))
        }
        if (rating != null) Text("$rating", color = Muted, fontSize = 10.sp, lineHeight = 12.sp, modifier = Modifier.padding(top = 3.dp))
    }
}
@Composable private fun SummaryMetric(label: String, white: String, black: String) {
    Row(Modifier.fillMaxWidth().height(50.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
        for ((index, value) in listOf(white, black).withIndex()) Box(Modifier.weight(1f).padding(horizontal = 6.dp).fillMaxHeight()
            .background(if (index == 0) Color(0xffededeb) else Color(0xff454440), RoundedCornerShape(7.dp)), contentAlignment = Alignment.Center) {
            Text(value, color = if (index == 0) Color(0xff262421) else Color.White, fontWeight = FontWeight.Black, fontSize = 24.sp)
        }
    }
}
@Composable internal fun OpeningsScreen(state: UiState, search: (String) -> Unit, open: (JSONObject) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("Find your next opening", fontSize = 25.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(vertical = 12.dp))
        OutlinedTextField(query, { query = it; search(it) }, placeholder = { Text("Name, ECO, or moves…") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true,
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = inputColors())
        if (state.loadingOpenings) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp), color = Accent)
        LazyColumn(Modifier.weight(1f).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(state.openingResults, key = { index, entry -> "${entry.optString("id")}-${entry.optString("moves_str")}-${index}" }) { _, entry ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Subtle).border(1.dp, Border, RoundedCornerShape(14.dp)).clickable { open(entry) }.padding(16.dp)) {
                    Text(entry.optString("eco"), color = Accent, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    Text(entry.optString("name"), fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(top = 5.dp))
                    Text(entry.optString("moves_str"), fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 7.dp), maxLines = 2)
                }
            }
            if (!state.loadingOpenings && state.openingResults.isEmpty()) item { Text("No matching openings.", color = Muted, modifier = Modifier.padding(24.dp)) }
        }
    }
}
@Composable internal fun OpeningScreen(model: ChessModel, state: UiState) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val boardSize = minOf(maxWidth - 24.dp, maxHeight - 200.dp)
        Column(Modifier.fillMaxSize().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(state.openingTitle, fontWeight = FontWeight.ExtraBold, fontSize = 21.sp, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
            Text(state.openingDescription, color = Muted, fontSize = 12.sp, maxLines = 3, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp))
            Spacer(Modifier.weight(1f))
            ChessBoard(state.position, boardSize, state.flipped, false, state.theme, onMove = {})
            Spacer(Modifier.weight(1f))
            Text(state.history.getOrNull(state.index)?.san ?: "Starting position", fontWeight = FontWeight.Bold, color = Accent, modifier = Modifier.padding(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(model::previous, Modifier.weight(1f), enabled = state.index >= 0) { Text("Previous") }
                OutlinedButton(model::flip) { Icon(Icons.Outlined.Flip, "Flip board") }
                PrimaryButton("Next", Modifier.weight(1f), state.index < state.history.lastIndex, lightText = true, action = model::next)
            }
        }
    }
}
@Composable internal fun inputColors() = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Border, focusedBorderColor = Accent,
    unfocusedContainerColor = Color(0xff1b1a17), focusedContainerColor = Color(0xff1b1a17))
