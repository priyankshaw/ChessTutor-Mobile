package com.chesstutor.nativeapp

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Locale

private val Background = Color(0xff161512)
private val Surface = Color(0xff262421)
private val Subtle = Color(0xff1e1d1a)
private val Border = Color(0xff3d3a34)
private val Accent = Color(0xff8bb34f)
private val Muted = Color(0xffa1a1aa)
private val White = Color(0xfff1f1f1)

@Composable fun ChessTutorApp(model: ChessModel, requestNotifications: () -> Unit) {
    val state = model.state
    var importing by rememberSaveable { mutableStateOf(false) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var options by remember { mutableStateOf(false) }
    var savedGames by remember { mutableStateOf(false) }
    var deleteCandidate by remember { mutableStateOf<SavedGame?>(null) }
    var fenDialog by remember { mutableStateOf(false) }
    var confirmNewGame by remember { mutableStateOf(false) }
    val context = LocalContext.current
    fun share() {
        val file = File(context.cacheDir, "exports/game.pgn").apply { parentFile!!.mkdirs(); writeText(model.exportPgn()) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/x-chess-pgn")
            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share chess game"))
    }
    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, onPrimary = Color.Black, background = Background,
        surface = Surface, onSurface = White, onBackground = White, outline = Border, secondary = Accent)) {
        CompositionLocalProvider(LocalContentColor provides White) {
        BackHandler(state.screen != Screen.HOME || importing || settings || savedGames) {
            when {
                importing -> { importing = false; model.cancelImport() }
                settings -> settings = false
                savedGames -> savedGames = false
                else -> model.home()
            }
        }
        Column(Modifier.fillMaxSize().background(Background).safeDrawingPadding()) {
            Column(Modifier.weight(1f).then(if (importing) Modifier.blur(10.dp) else Modifier)) {
                Header(when (state.screen) {
                    Screen.HOME -> "ChessTutor"; Screen.AI_SETUP, Screen.AI_GAME -> "Play vs AI"
                    Screen.LIVE -> "Live Analysis"; Screen.OPENINGS, Screen.OPENING -> "Opening Explorer"; else -> "Game Review"
                }, model::home, { options = true }, { settings = true })
                when (state.screen) {
                    Screen.HOME -> HomeScreen(state, { model.navigate(Screen.AI_SETUP) }, { requestNotifications(); model.startLive() },
                        { importing = true }, { model.searchOpenings("") }, model::resumeGame, { savedGames = true })
                    Screen.AI_SETUP -> AiSetup(state) { level, side, time -> requestNotifications(); model.startAi(level, side, time) }
                    Screen.AI_GAME, Screen.LIVE, Screen.REVIEW -> Workspace(model, state, { requestNotifications(); model.reviewCurrent() })
                    Screen.ANALYSIS -> AnalysisScreen(state, model::cancelAnalysis, model::retryAnalysis)
                    Screen.SUMMARY -> ReviewSummary(state, model::beginReview)
                    Screen.OPENINGS -> OpeningsScreen(state, model::searchOpenings, model::openOpening)
                    Screen.OPENING -> OpeningScreen(model, state)
                }
            }
            if (state.error != null && state.screen != Screen.ANALYSIS && !importing) Row(
                Modifier.fillMaxWidth().background(Color(0xff422b25)).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(state.error, fontSize = 12.sp, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
                if (state.mode == GameMode.AI) TextButton(onClick = model::retryAi) { Text("Retry") }
                IconButton(onClick = model::clearError) { Icon(Icons.Outlined.Close, "Dismiss error") }
            }
        }
        if (importing) ImportDialog(state, onDismiss = { importing = false; model.cancelImport(); model.clearError() },
            fetch = model::fetchGames, analyze = { pgn -> importing = false; model.clearError(); requestNotifications(); model.analyzePgn(pgn) })
        if (settings) SettingsDialog(state, model::settings) { settings = false }
        if (savedGames) CenterDialog("Saved games", { savedGames = false }) {
            Text("${state.saved.size} saved · Keep up to ${state.savedGameLimit} on next save", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(16.dp))
            LazyColumn(Modifier.heightIn(max = 440.dp)) {
                itemsIndexed(state.saved, key = { _, game -> game.id }) { _, game ->
                    Column(Modifier.fillMaxWidth().padding(8.dp).background(Subtle, RoundedCornerShape(12.dp)).padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(game.title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            IconButton(onClick = { deleteCandidate = game }) { Icon(Icons.Outlined.DeleteOutline, "Delete game: ${game.title}", tint = Muted) }
                        }
                        PrimaryButton("Review", Modifier.fillMaxWidth().padding(top = 10.dp)) { savedGames = false; requestNotifications(); model.analyzePgn(game.pgn, game.id) }
                    }
                }
                if (state.saved.isEmpty()) item { Text("Your saved games appear here.", color = Muted, modifier = Modifier.padding(24.dp)) }
            }
            if (state.error != null) Text(state.error, color = Color(0xffffbc99), fontSize = 12.sp, modifier = Modifier.padding(16.dp))
        }
        deleteCandidate?.let { game ->
            AlertDialog(onDismissRequest = { deleteCandidate = null }, title = { Text("Delete saved game?") },
                text = { ImmersiveDialogWindow(); Text("Delete ${game.title} from Saved games? Your current board stays available.") },
                confirmButton = { TextButton(onClick = { model.clearError(); model.deleteSavedGame(game.id); deleteCandidate = null }) { Text("Delete") } },
                dismissButton = { TextButton(onClick = { deleteCandidate = null }) { Text("Cancel") } })
        }
        if (options) CenterDialog("Game options", { options = false }) {
            Column(Modifier.padding(12.dp)) {
                OptionRow(Icons.Outlined.Flip, "Flip board") { model.flip(); options = false }
                if (state.screen in listOf(Screen.LIVE, Screen.AI_GAME) && state.history.isNotEmpty()) OptionRow(Icons.AutoMirrored.Outlined.Undo, "Take back move") { model.undo(); options = false }
                if (state.history.isNotEmpty()) {
                    OptionRow(Icons.Outlined.Save, "Save game") { model.saveGame(manual = true); options = false }
                    OptionRow(Icons.Outlined.Share, "Share PGN") { share(); options = false }
                }
                OptionRow(Icons.Outlined.FolderOpen, "Saved games") { savedGames = true; options = false }
                if (state.screen == Screen.LIVE) OptionRow(Icons.Outlined.GridOn, "Load position (FEN)") { fenDialog = true; options = false }
                if (state.screen == Screen.AI_GAME && state.result == "*") OptionRow(Icons.Outlined.Flag, "Resign") { model.resign(); options = false }
                if (state.screen == Screen.LIVE || state.screen == Screen.AI_GAME) OptionRow(Icons.Outlined.RestartAlt, "New game") { confirmNewGame = true; options = false }
                if (state.screen == Screen.REVIEW && model.currentFeedback() != null) OptionRow(Icons.Outlined.School, "Try the better move") { model.startPractice(); options = false }
            }
        }
        if (fenDialog) {
            var fen by remember { mutableStateOf("") }
            CenterDialog("Load chess position", { fenDialog = false }) { Column(Modifier.padding(16.dp)) {
                OutlinedTextField(fen, { fen = it }, label = { Text("FEN") }, modifier = Modifier.fillMaxWidth(), maxLines = 4)
                PrimaryButton("Load position", Modifier.fillMaxWidth().padding(top = 12.dp), fen.isNotBlank()) { model.startLive(fen); fenDialog = false }
            } }
        }
        if (confirmNewGame) AlertDialog(onDismissRequest = { confirmNewGame = false }, title = { Text("Start a new game?") },
            text = { ImmersiveDialogWindow(); Text("Current game will be saved before you choose a new game.") },
            confirmButton = { TextButton(onClick = { model.saveGame(); confirmNewGame = false; if (state.mode == GameMode.AI) model.navigate(Screen.AI_SETUP) else model.startLive() }) { Text("New game") } },
            dismissButton = { TextButton(onClick = { confirmNewGame = false }) { Text("Continue playing") } })
    }
    }
}

@Composable private fun Header(title: String, back: () -> Unit, more: () -> Unit, settings: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(48.dp).background(Subtle)) {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.align(Alignment.Center))
        IconButton(back, Modifier.align(Alignment.CenterStart)) { Icon(Icons.Outlined.ChevronLeft, "Back to home", tint = Muted) }
        Row(Modifier.align(Alignment.CenterEnd)) {
            IconButton(more, Modifier.size(40.dp)) { Icon(Icons.Outlined.MoreHoriz, "Game options", tint = Muted) }
            IconButton(settings, Modifier.size(40.dp)) { Icon(Icons.Outlined.Tune, "Settings", tint = Muted) }
        }
        HorizontalDivider(Modifier.align(Alignment.BottomCenter), color = Border, thickness = .5.dp)
    }
}
@Composable internal fun PrimaryButton(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, lightText: Boolean = false, action: () -> Unit) {
    Button(onClick = action, enabled = enabled, modifier = modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(9.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = if (lightText) White else Color.Black, disabledContainerColor = Accent.copy(alpha = .4f), disabledContentColor = White.copy(alpha = .4f))) {
        Text(label, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}
@Composable private fun OptionRow(icon: ImageVector, text: String, action: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = action).heightIn(min = 52.dp).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Accent, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(12.dp)); Text(text)
    }
}
@Composable private fun HomeScreen(state: UiState, ai: () -> Unit, live: () -> Unit, import: () -> Unit,
    openings: () -> Unit, resume: () -> Unit, saved: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp)) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Brush.verticalGradient(listOf(Color(0xff2c3023), Subtle), endY = 700f))
            .border(1.dp, Border, RoundedCornerShape(26.dp))) {
            Column(Modifier.padding(horizontal = 24.dp).padding(top = 30.dp, bottom = 20.dp)) {
                Text("•  YOUR CHESS WORKSPACE", color = Color(0xffb2cf78), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
                Spacer(Modifier.height(14.dp))
                Text("Learn. Play.", fontSize = 38.sp, fontWeight = FontWeight.Black, lineHeight = 42.sp)
                Text("Improve.", color = Color(0xffa8d66e), fontSize = 38.sp, fontWeight = FontWeight.Black, lineHeight = 42.sp)
                Spacer(Modifier.height(10.dp))
                Text("A clear path to your next better move. Play, review your games, or explore an opening.", fontSize = 12.sp, lineHeight = 18.sp, color = Color(0xffccc8b8))
            }
            HomeCard("Play against AI", "1800–2500 Elo or maximum Stockfish", Icons.Outlined.PlayArrow, Color(0xffa8d66e), true, ai)
            HomeCard("Live Play & Free Analysis", "Try a move. Get instant engine insight.", Icons.Outlined.AutoAwesome, Color(0xffa8d66e), false, live)
            HomeCard("Import from Chess.com or Upload PGN", "Chess.com, Lichess, pasted notation, or a PGN file", Icons.Outlined.Public, Color(0xffa0c4dd), false, import)
            HomeCard("Opening Explorer", "Study 3,800+ named opening lines.", Icons.AutoMirrored.Outlined.MenuBook, Color(0xffd9b893), false, openings)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp).border(.5.dp, Border).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).background(Accent, CircleShape)); Spacer(Modifier.width(8.dp))
                Text("Powered by Stockfish 19", color = Color(0xffbcb8a9), fontSize = 10.sp)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (state.resumable) TextButton(onClick = resume, enabled = !state.resuming) {
                if (state.resuming) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = Accent, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (state.resuming) "Restoring game…" else "Resume game", color = Accent)
            }
            TextButton(onClick = saved) { Text("Saved games", color = Muted) }
        }
    }
}
@Composable private fun HomeCard(title: String, subtitle: String, icon: ImageVector, tint: Color, featured: Boolean, action: () -> Unit) {
    Row(Modifier.padding(horizontal = 12.dp, vertical = 3.dp).fillMaxWidth().heightIn(min = 118.dp)
        .clip(RoundedCornerShape(15.dp)).background(if (featured) Accent.copy(alpha = .055f) else Color(0xff1b1b17))
        .border(1.dp, if (featured) Accent.copy(alpha = .5f) else Border, RoundedCornerShape(15.dp))
        .clickable(onClick = action).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).background(tint.copy(alpha = .12f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(23.dp))
        }
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, lineHeight = 18.sp)
            Text(subtitle, fontSize = 11.sp, color = Color(0xffbcb8a9), lineHeight = 17.sp, modifier = Modifier.padding(top = 3.dp))
        }
        Icon(Icons.Outlined.ChevronRight, null, tint = Color(0xff737866), modifier = Modifier.size(18.dp))
    }
}

@Composable internal fun Choice(label: String, choices: List<Pair<String, String>>, value: String, modifier: Modifier = Modifier, select: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier) {
        if (label.isNotBlank()) Text(label, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().height(49.dp), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Border), colors = ButtonDefaults.outlinedButtonColors(contentColor = White, containerColor = Background)) {
                Text(choices.firstOrNull { it.first == value }?.second ?: value, modifier = Modifier.weight(1f), textAlign = TextAlign.Start, fontSize = 14.sp)
                Icon(Icons.Outlined.ExpandMore, null, Modifier.size(20.dp))
            }
            DropdownMenu(expanded, { expanded = false }) { choices.forEach { (key, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { select(key); expanded = false }) } }
        }
    }
}
@Composable private fun AiSetup(state: UiState, start: (String, String, Double) -> Unit) {
    var strength by rememberSaveable { mutableStateOf("1800") }
    var side by rememberSaveable { mutableStateOf("white") }
    var time by rememberSaveable { mutableStateOf("3") }
    var flipped by rememberSaveable { mutableStateOf(false) }
    val initial = remember { ChessGame().position() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val boardSize = maxWidth - 32.dp
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 14.dp).background(Subtle, RoundedCornerShape(12.dp)).border(1.dp, Border, RoundedCornerShape(12.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Choose your opponent", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); Text("Untimed practice", color = Muted, fontSize = 11.sp)
            }
            Box(Modifier.align(Alignment.CenterHorizontally).border(4.dp, Color(0xff39432b), RoundedCornerShape(16.dp)).padding(4.dp).clip(RoundedCornerShape(12.dp))) {
                ChessBoard(initial, boardSize, flipped, false, state.theme) {}
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Select a level and start a game.", color = Color(0xffa8d66e), fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { flipped = !flipped }, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Border)) { Text("Flip board", color = White, fontSize = 12.sp) }
            }
            Column(Modifier.fillMaxWidth().padding(top = 12.dp).background(Subtle, RoundedCornerShape(18.dp)).border(1.dp, Border, RoundedCornerShape(18.dp)).padding(20.dp)) {
                Text("Play against AI", fontWeight = FontWeight.ExtraBold, fontSize = 21.sp, modifier = Modifier.padding(bottom = 24.dp))
                Choice("Opponent strength", listOf("1800" to "1800 Elo", "2000" to "2000 Elo", "2200" to "2200 Elo", "2500" to "2500 Elo", "max" to "Maximum Stockfish"), strength) { strength = it }
                Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Choice("Play as", listOf("white" to "White", "black" to "Black"), side, Modifier.weight(1f)) { side = it }
                    Choice("Think time / move", listOf("1" to "1 second", "3" to "3 seconds", "5" to "5 seconds", "10" to "10 seconds"), time, Modifier.weight(1f)) { time = it }
                }
                Text("Elo levels are Stockfish strength targets, not verified ratings.", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(vertical = 16.dp))
                PrimaryButton("Start game", Modifier.fillMaxWidth()) { start(strength, side, time.toDouble()) }
            }
        }
    }
}

@Composable private fun Workspace(model: ChessModel, state: UiState, review: () -> Unit) {
    val feedback = model.currentFeedback()
    val grade = feedback?.optJSONObject("classification")?.optString("classification")
    val ai = state.mode == GameMode.AI
    Column(Modifier.fillMaxSize()) {
        if (state.variationRoot != null) Row(Modifier.fillMaxWidth().background(Color(0xff383020)).padding(start = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Variation · Original game kept", color = Color(0xffe5c078), fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = model::resumeOriginal, modifier = Modifier.semantics { contentDescription = "Resume original game" }) { Text("Resume", color = Accent, fontWeight = FontWeight.Bold) }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val coachHeight = if (maxHeight < 550.dp) 110.dp else 136.dp
            val boardSize = minOf(maxWidth - 16.dp, (maxHeight - (if (ai) 72.dp else coachHeight + 74.dp)).coerceAtLeast(160.dp))
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                if (!ai) CoachPanel(state, feedback, coachHeight, model::showBest, model::startPractice, model::exitPractice, model::retryCoaching)
                else Row(Modifier.fillMaxWidth().padding(12.dp).heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (state.result != "*") resultText(state.result) else if (state.thinking) "Stockfish is thinking…" else "Your move", color = Accent, fontWeight = FontWeight.Bold)
                    if (state.thinking) CircularProgressIndicator(Modifier.padding(start = 12.dp).size(16.dp), color = Accent, strokeWidth = 2.dp)
                }
                Spacer(Modifier.weight(1f))
                val barSpace = if (ai) 0.dp else 16.dp
                Column(Modifier.width(boardSize + barSpace)) {
                    Box(Modifier.padding(start = barSpace)) { PlayerBar(if (state.flipped) state.white else state.black, !state.flipped, state.position.fen.split(' ')[1] == if (state.flipped) "w" else "b", if (state.flipped) state.whiteRating else state.blackRating) }
                    Row {
                        if (!ai) {
                            EvaluationBar(if (state.practice) null else feedback?.optJSONObject("eval"), state.position.fen, feedback?.optString("color"), state.flipped, boardSize)
                        }
                        ChessBoard(state.position, boardSize, state.flipped, model.canMove, state.theme,
                            if (!ai && state.hints) grade else null,
                            if (state.best && state.hints) feedback?.optJSONObject("best_move")?.optString("uci") else null, model::move)
                    }
                    Box(Modifier.padding(start = barSpace)) { PlayerBar(if (state.flipped) state.black else state.white, state.flipped, state.position.fen.split(' ')[1] == if (state.flipped) "b" else "w", if (state.flipped) state.blackRating else state.whiteRating) }
                }
                Spacer(Modifier.weight(1f))
            }
        }
        MoveTrack(state, model::previous, model::next, model::select)
        Row(Modifier.fillMaxWidth().background(Surface).padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (ai) {
                ToolButton("Undo", Icons.AutoMirrored.Outlined.Undo, enabled = state.history.isNotEmpty(), action = model::undo)
                ToolButton("Flip", Icons.Outlined.Flip, action = model::flip)
                PrimaryButton(if (state.result == "*") "Resign" else "Review", Modifier.weight(1f), state.history.isNotEmpty(), lightText = true) {
                    if (state.result == "*") model.resign() else review()
                }
            } else {
                ToolButton("Show", Icons.Outlined.Visibility, state.hints, action = model::toggleHints)
                if (feedback?.optJSONObject("best_move")?.optString("uci")?.let { it != feedback.optString("uci") } == true)
                    ToolButton("Best", Icons.Outlined.StarBorder, state.best, action = model::showBest)
                ToolButton("Review", Icons.Outlined.WorkspacePremium, action = {
                    if (state.analysis != null) { model.resumeOriginal(); model.navigate(Screen.SUMMARY) } else review()
                })
                PrimaryButton("Next  ›", Modifier.weight(1f), state.index < state.history.lastIndex, lightText = true, action = model::next)
            }
        }
    }
}
@Composable private fun PlayerBar(name: String, black: Boolean, active: Boolean, rating: Int?) {
    Row(Modifier.fillMaxWidth().height(26.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(22.dp).background(if (black) Color(0xff383836) else White, RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.PersonOutline, null, tint = if (black) White else Color.Black, modifier = Modifier.size(20.dp))
            if (active) Box(Modifier.align(Alignment.TopEnd).size(6.dp).background(Accent, CircleShape))
        }
        Text(name, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp).weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (rating != null) Text("$rating", fontSize = 10.sp, color = Muted, modifier = Modifier.padding(start = 7.dp).border(.5.dp, Border, RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 3.dp))
    }
}
@Composable private fun ToolButton(label: String, icon: ImageVector, active: Boolean = false, enabled: Boolean = true, action: () -> Unit) {
    Column(Modifier.widthIn(min = 48.dp).height(52.dp).clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled, onClick = action), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, label, tint = if (!enabled) Muted.copy(alpha = .35f) else if (active) Accent else Muted, modifier = Modifier.size(22.dp))
        Text(label, color = if (active) Accent else Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 3.dp))
    }
}
@Composable private fun MoveTrack(state: UiState, previous: () -> Unit, next: () -> Unit, select: (Int) -> Unit) {
    val scroll = rememberLazyListState()
    LaunchedEffect(state.index) { if (state.index >= 0 && state.history.isNotEmpty()) scroll.animateScrollToItem(state.index.coerceAtMost(state.history.lastIndex)) }
    Row(Modifier.fillMaxWidth().height(44.dp).background(Subtle).border(.5.dp, Border), verticalAlignment = Alignment.CenterVertically) {
        IconButton(previous, Modifier.size(42.dp), enabled = state.index >= 0) { Icon(Icons.Outlined.ChevronLeft, "Previous move", tint = Muted) }
        LazyRow(Modifier.weight(1f), state = scroll) {
            itemsIndexed(state.history, key = { _, ply -> ply.id }) { index, ply ->
                val grade = state.feedback[ply.id]?.optJSONObject("classification")?.optString("classification")
                Column(Modifier.height(44.dp).widthIn(min = 44.dp).background(if (state.index == index) Color(0xff303128) else Color.Transparent)
                    .clickable { select(index) }.padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text("${if (ply.before.split(' ')[1] == "w") "${ply.before.substringAfterLast(' ')}. " else ""}${ply.san}",
                        fontWeight = FontWeight.Bold, fontSize = 13.sp, color = if (state.index == index) White else Muted)
                    if (grade != null) Box(Modifier.padding(top = 3.dp).size(4.dp).background(grades[grade]?.color ?: Accent, CircleShape))
                    if (state.index == index) HorizontalDivider(color = Accent, thickness = 2.dp)
                }
            }
        }
        IconButton(next, Modifier.size(42.dp), enabled = state.index < state.history.lastIndex) { Icon(Icons.Outlined.ChevronRight, "Next move", tint = Muted) }
    }
}

@Composable private fun CoachPanel(state: UiState, feedback: JSONObject?, height: androidx.compose.ui.unit.Dp,
    best: () -> Unit, practice: () -> Unit, exit: () -> Unit, retry: () -> Unit) {
    val hasBest = feedback?.optJSONObject("best_move")?.optString("uci")?.let { it.isNotBlank() && it != feedback.optString("uci") } == true
    val pager = rememberPagerState { if (hasBest) 2 else 1 }
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.index) { pager.scrollToPage(0) }
    LaunchedEffect(state.best) { if (hasBest) pager.scrollToPage(if (state.best) 1 else 0) }
    LaunchedEffect(pager.currentPage) { if (hasBest && (pager.currentPage == 1) != state.best) best() }
    val ply = state.history.getOrNull(state.index)
    val error = ply?.let { state.moveErrors[it.id] }
    Box(Modifier.fillMaxWidth().height(height).padding(top = 8.dp, end = 8.dp, bottom = 3.dp)) {
        Image(painterResource(R.drawable.coach), null, Modifier.align(Alignment.BottomStart).width(64.dp).height(84.dp))
        Column(Modifier.padding(start = 64.dp).fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White).padding(12.dp)) {
                if (state.practice) Column {
                    Text("Find the better move", color = Color(0xff262421), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(state.practiceMessage, color = Color(0xff262421), fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                    TextButton(onClick = exit, contentPadding = PaddingValues(0.dp)) { Text("Exit practice", color = Color(0xff5c7e34), fontSize = 11.sp) }
                } else HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        val grade = if (page == 1) "best" else feedback?.optJSONObject("classification")?.optString("classification") ?: "good"
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (feedback == null && ply != null) CircularProgressIndicator(Modifier.size(22.dp), color = Accent, strokeWidth = 2.dp) else GradeBadge(grade)
                            fun moveName(san: String) = if (state.usePieceNames) moveInWords(san) else san
                            val title = if (page == 1) "Best: ${moveName(feedback?.optJSONObject("best_move")?.optString("san") ?: "")}" else if (ply == null) "Your move coach"
                                else "${moveName(ply.san)} · ${feedback?.optJSONObject("classification")?.optString("name") ?: "Analysing"}"
                            Text(title, color = Color(0xff262421), fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(start = 6.dp), lineHeight = 17.sp)
                            if (page == 0) evalLabel(feedback?.optJSONObject("eval"))?.let { Text(it, color = Color(0xff555555), fontSize = 10.sp, modifier = Modifier.background(Color(0xfff1f1ef), RoundedCornerShape(4.dp)).padding(5.dp)) }
                        }
                        val explanation = feedback?.optJSONObject("explanation")
                        val text = when {
                            page == 1 -> coachExplanation(explanation, best = true, complete = state.completeExplanation)
                            ply == null -> "Play or select a move to get coaching."
                            error != null -> error
                            feedback == null -> "Analysing ${ply.san}…${if (state.queued > 1) " ${state.queued} moves queued." else ""}"
                            else -> coachExplanation(explanation, best = false, complete = state.completeExplanation)
                        }
                        Text(explanationForSide(explanationInWords(text ?: "Analysis ready.", state.usePieceNames), feedback?.optString("color")), color = Color(0xff333333), fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 8.dp))
                        if (page == 1) TextButton(onClick = practice, contentPadding = PaddingValues(0.dp)) { Text("Try this move", color = Color(0xff5c7e34), fontSize = 11.sp) }
                        if (error != null) TextButton(onClick = retry, contentPadding = PaddingValues(0.dp)) { Text("Retry analysis", color = Color(0xff5c7e34), fontSize = 11.sp) }
                    }
                }
            }
            if (hasBest && !state.practice) Row(Modifier.fillMaxWidth().height(18.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                repeat(2) { page -> Box(Modifier.padding(horizontal = 4.dp).width(if (pager.currentPage == page) 12.dp else 5.dp).height(5.dp)
                    .clip(CircleShape).background(if (pager.currentPage == page) Accent else Muted.copy(alpha = .5f)).clickable { scope.launch { pager.animateScrollToPage(page) } }) }
                Text(if (state.best) "Instead of ${ply?.san?.let { if (state.usePieceNames) moveInWords(it, false) else it }}" else "Swipe for the best move", color = Muted, fontSize = 9.sp, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}
private fun evalLabel(eval: JSONObject?): String? {
    eval ?: return null
    if (eval.has("white_mate") && !eval.isNull("white_mate")) {
        val mate = eval.getInt("white_mate")
        return if (mate == 0) "Mate" else "${if (mate < 0) "−" else ""}M${kotlin.math.abs(mate)}"
    }
    return if (eval.has("white_cp")) String.format(Locale.US, "%+.2f", eval.optDouble("white_cp") / 100) else null
}
private fun explanationForSide(text: String, color: String?): String {
    if (color == null) return text
    val side = if (color == "black") "Black" else "White"
    val opponent = if (color == "black") "White" else "Black"
    val replacements = listOf("your opponent's" to "$opponent's", "your opponent" to opponent,
        "(?:the )?opponent's" to "$opponent's", "(?:the )?opponent" to opponent,
        "your" to "$side's", "you have" to "$side has", "you are" to "$side is", "you play" to "$side plays", "you" to side)
    return replacements.fold(text) { value, (pattern, replacement) -> Regex("\\b$pattern\\b", RegexOption.IGNORE_CASE).replace(value, replacement) }
}
internal fun resultText(result: String) = when (result) { "1-0" -> "White wins"; "0-1" -> "Black wins"; "1/2-1/2" -> "Draw"; else -> "Game in progress" }
