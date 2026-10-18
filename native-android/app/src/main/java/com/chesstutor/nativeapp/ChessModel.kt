package com.chesstutor.nativeapp

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.github.bhlangonijr.chesslib.move.MoveList
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID

enum class Screen { HOME, AI_SETUP, AI_GAME, LIVE, ANALYSIS, SUMMARY, REVIEW, OPENINGS, OPENING }
enum class GameMode { LIVE, AI, REVIEW, OPENING }
data class UiState(
    val screen: Screen = Screen.HOME, val mode: GameMode = GameMode.LIVE,
    val position: BoardPosition = ChessGame().position(), val history: List<Ply> = emptyList(),
    val index: Int = -1, val feedback: Map<Long, JSONObject> = emptyMap(),
    val moveErrors: Map<Long, String> = emptyMap(), val queued: Int = 0,
    val flipped: Boolean = false, val hints: Boolean = true, val best: Boolean = false,
    val practice: Boolean = false, val practiceMessage: String = "",
    val practiceSolved: Boolean = false,
    val variationRoot: Int? = null,
    val thinking: Boolean = false, val result: String = "*", val error: String? = null,
    val progress: Int = 0, val total: Int = 0, val analysis: JSONObject? = null,
    val white: String = "Live White", val black: String = "Live Black",
    val whiteRating: Int? = null, val blackRating: Int? = null,
    val saved: List<SavedGame> = emptyList(), val downloads: List<DownloadedGame> = emptyList(), val downloadAccount: String = "",
    val importing: Boolean = false, val openingResults: List<JSONObject> = emptyList(),
    val openingTitle: String = "", val openingDescription: String = "", val loadingOpenings: Boolean = false,
    val theme: String = "Green", val sound: Boolean = true, val depth: Int = 16,
    val completeExplanation: Boolean = true, val usePieceNames: Boolean = true, val savedGameLimit: Int = 3,
    val resumable: Boolean = false, val resuming: Boolean = false
)

class TutorApplication : Application() {
    val model by lazy { ChessModel(this) }
}

class ChessModel(private val application: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val runtime = ChessRuntime(application)
    private val store = GameStore(application)
    private val importer = GameImporter()
    private data class SessionSnapshot(val id: String, val pgn: String, val mode: String, val level: String, val side: String,
        val time: Double, val flipped: Boolean, val feedback: List<JSONObject?>, val analysis: JSONObject?,
        val index: Int, val variation: VariationSnapshot?)
    private data class VariationSnapshot(val root: Int, val moves: List<String>, val feedback: List<JSONObject?>)
    private val saves = Channel<SessionSnapshot>(Channel.CONFLATED)
    private val settingsSaves = Channel<String>(Channel.CONFLATED)
    private val libraryMutex = Mutex()
    private var gameLoaded = false
    private var game = ChessGame()
    private var reviewOriginal: ChessGame? = null
    private val originalGame: ChessGame get() = reviewOriginal ?: game
    private var epoch = 0L
    private var work: Job? = null
    private var importWork: Job? = null
    private var importEpoch = 0L
    private var searchWork: Job? = null
    private val pending = ArrayDeque<Ply>()
    private var level = "1800"
    private var side = "white"
    private var thinkTime = 3.0
    private var gameId = UUID.randomUUID().toString()
    private var analysisPgn: String? = null
    private var restore: JSONObject? = null
    private var notificationStarted = false
    var state by mutableStateOf(UiState())
        private set
    var onMoveSound: ((Boolean) -> Unit)? = null

    init {
        scope.launch(Dispatchers.IO) {
            for (settings in settingsSaves) try { store.write("settings", settings) }
            catch (error: Exception) { withContext(Dispatchers.Main.immediate) { state = state.copy(error = "Could not save settings. Free some storage and retry.") } }
        }
        scope.launch(Dispatchers.IO) {
            for (session in saves) {
                val snapshot = JSONObject().put("game_id", session.id).put("pgn", session.pgn).put("mode", session.mode).put("level", session.level)
                    .put("analysis_revision", BuildConfig.ANALYSIS_REVISION)
                    .put("side", session.side).put("time", session.time).put("flipped", session.flipped)
                    .put("feedback", JSONArray(session.feedback.map { it ?: JSONObject.NULL })).put("analysis", session.analysis)
                    .put("index", session.index).put("variation", session.variation?.let {
                        JSONObject().put("root", it.root).put("moves", JSONArray(it.moves))
                            .put("feedback", JSONArray(it.feedback.map { value -> value ?: JSONObject.NULL }))
                    })
                try {
                    store.write("session", snapshot.toString())
                    withContext(Dispatchers.Main.immediate) { restore = snapshot }
                } catch (error: Exception) {
                    withContext(Dispatchers.Main.immediate) { state = state.copy(error = "Could not save game. Free some storage and retry.") }
                }
            }
        }
        scope.launch {
            val values = withContext(Dispatchers.IO) {
                Triple(store.saved(), store.read("settings"), store.read("session"))
            }
            val settings = runCatching { JSONObject(values.second ?: "{}") }.getOrDefault(JSONObject())
            restore = runCatching { JSONObject(values.third ?: "{}") }.getOrNull()?.takeIf { it.has("pgn") }
            state = state.copy(saved = values.first, theme = settings.optString("theme", "Green"),
                sound = settings.optBoolean("sound", true), depth = settings.optInt("depth", 16).coerceIn(10, 22),
                completeExplanation = settings.optBoolean("completeExplanation", true), usePieceNames = settings.optBoolean("usePieceNames", true),
                savedGameLimit = settings.optInt("savedGameLimit", 3).coerceIn(1, 30), resumable = restore != null)
        }
    }
    private fun notifyWork(text: String = "Finding your best moves…") {
        val intent = Intent(application, AnalysisService::class.java).putExtra("text", text)
        if (!notificationStarted) {
            ContextCompat.startForegroundService(application, intent)
            notificationStarted = true
        } else application.startService(intent)
    }
    private fun stopNotification() {
        if (notificationStarted) application.stopService(Intent(application, AnalysisService::class.java))
        notificationStarted = false
    }
    private fun cancelRestore() {
        if (state.resuming) { epoch++; state = state.copy(resuming = false) }
    }
    fun navigate(screen: Screen) { cancelRestore(); state = state.copy(screen = screen, error = null) }
    fun home() { cancelRestore(); state = state.copy(screen = Screen.HOME, position = boardPosition(state.index), best = false, practice = false, error = null) }
    fun clearError() { state = state.copy(error = null) }
    fun flip() { state = state.copy(flipped = !state.flipped); persistSession() }
    fun toggleHints() { state = state.copy(hints = !state.hints) }
    fun settings(theme: String = state.theme, sound: Boolean = state.sound, depth: Int = state.depth,
                 completeExplanation: Boolean = state.completeExplanation, usePieceNames: Boolean = state.usePieceNames,
                 savedGameLimit: Int = state.savedGameLimit) {
        state = state.copy(theme = theme, sound = sound, depth = depth.coerceIn(10, 22),
            completeExplanation = completeExplanation, usePieceNames = usePieceNames, savedGameLimit = savedGameLimit.coerceIn(1, 30))
        settingsSaves.trySend(JSONObject().put("theme", state.theme).put("sound", state.sound).put("depth", state.depth)
            .put("completeExplanation", state.completeExplanation).put("usePieceNames", state.usePieceNames)
            .put("savedGameLimit", state.savedGameLimit).toString())
    }
    private fun reset(newGame: ChessGame, mode: GameMode, screen: Screen, saveSession: Boolean = true) {
        if (gameLoaded && game.plies.isNotEmpty() && state.mode != GameMode.OPENING) saveGame()
        epoch++
        work?.cancel(); work = null; pending.clear(); stopNotification()
        reviewOriginal = null
        game = newGame
        gameLoaded = true
        gameId = UUID.randomUUID().toString()
        state = state.copy(mode = mode, screen = screen, position = game.position(), history = game.plies.toList(),
            index = game.plies.lastIndex, feedback = emptyMap(), moveErrors = emptyMap(), queued = 0,
            best = false, practice = false, variationRoot = null, thinking = false, result = game.result, error = null,
            analysis = null, progress = 0, total = game.plies.size,
            white = (game.headers["White"] ?: "White").take(80), black = (game.headers["Black"] ?: "Black").take(80), resumable = true, resuming = false)
        state = state.copy(whiteRating = game.headers["WhiteElo"]?.toIntOrNull()?.takeIf { it in 1..4000 },
            blackRating = game.headers["BlackElo"]?.toIntOrNull()?.takeIf { it in 1..4000 })
        if (saveSession) persistSession()
    }
    fun startLive(fen: String? = null) {
        if (fen == null) reset(ChessGame(), GameMode.LIVE, Screen.LIVE)
        else scope.launch {
            try {
                val valid = runtime.call("/api/validate-fen", JSONObject().put("fen", fen)) as JSONObject
                reset(ChessGame(valid.getString("fen")), GameMode.LIVE, Screen.LIVE)
            } catch (error: Exception) { if (error !is CancellationException) state = state.copy(error = error.message) }
        }
    }
    fun startAi(strength: String, player: String, time: Double) {
        level = strength; side = player; thinkTime = time
        val opponent = "Stockfish ${if (strength == "max") "Maximum" else "$strength Elo"}"
        reset(ChessGame(headers = mapOf("White" to if (side == "white") "You" else opponent,
            "Black" to if (side == "black") "You" else opponent, "Event" to "ChessTutor practice")), GameMode.AI, Screen.AI_GAME)
        state = state.copy(flipped = side == "black")
        if (side == "black") aiReply()
    }
    private fun boardPosition(index: Int = game.plies.lastIndex) = game.position(index, editable = state.mode in listOf(GameMode.LIVE, GameMode.REVIEW))
    val canMove: Boolean get() = (state.practice && !state.practiceSolved) || (!state.practice && !state.thinking && !state.resuming && !state.best &&
        state.position.legal.isNotEmpty() && when (state.mode) {
            GameMode.LIVE -> true
            GameMode.REVIEW -> state.screen == Screen.REVIEW && state.analysis != null
            GameMode.AI -> !game.isOver && game.turn == side && state.index == game.plies.lastIndex
            GameMode.OPENING -> false
        })

    fun move(uci: String) {
        if (state.practice) { practiceMove(uci); return }
        if (!canMove) return
        try {
            require(uci in state.position.legal) { "Illegal move." }
            if (state.mode == GameMode.REVIEW && reviewOriginal == null && game.plies.getOrNull(state.index + 1)?.uci == uci) {
                select(state.index + 1)
                return
            }
            require(state.index + 1 < 2000) { "Game reached the 2000-move limit. Save and start a new game." }
            var replaced = false
            if (state.mode == GameMode.REVIEW && (reviewOriginal == null || state.index < (state.variationRoot ?: -1))) {
                reviewOriginal = originalGame
                game = originalGame.fork(state.index)
                state = state.copy(variationRoot = state.index)
                replaced = true
            } else if (state.index < game.plies.lastIndex) {
                game.truncateAfter(state.index)
                replaced = true
            }
            if (replaced) {
                epoch++; work?.cancel(); work = null; pending.clear(); stopNotification()
                val ids = (game.plies + (reviewOriginal?.plies ?: emptyList())).map { it.id }.toSet()
                state = state.copy(feedback = state.feedback.filterKeys { it in ids }, moveErrors = state.moveErrors.filterKeys { it in ids }, queued = 0)
            }
            val ply = game.play(uci)
            state = state.copy(position = boardPosition(), history = game.plies.toList(), index = game.plies.lastIndex,
                result = game.result, best = false, error = null)
            if (state.sound) onMoveSound?.invoke(ply.capture)
            persistSession()
            if (game.isOver && state.mode != GameMode.REVIEW) saveGame()
            if (state.mode in listOf(GameMode.LIVE, GameMode.REVIEW)) {
                if (replaced) { retryCoaching(); return }
                pending.addLast(ply)
                state = state.copy(queued = pending.size)
                notifyWork("Coaching ${pending.size} move${if (pending.size == 1) "" else "s"}")
                drainCoaching()
            } else if (!game.isOver) aiReply()
        } catch (error: Exception) { state = state.copy(error = error.message) }
    }
    private fun drainCoaching() {
        if (work?.isActive == true || pending.isEmpty()) return
        val generation = epoch
        work = scope.launch {
            try {
                while (pending.isNotEmpty() && generation == epoch) {
                    val ply = pending.first()
                    try {
                        val index = game.plies.indexOfFirst { it.id == ply.id }
                        val previousGrade = game.plies.getOrNull(index - 1)?.let { state.feedback[it.id]?.optJSONObject("classification")?.optString("classification") }
                        val rating = game.headers[if (ply.before.split(' ')[1] == "w") "WhiteElo" else "BlackElo"]?.toIntOrNull()?.takeIf { it in 1..4000 }
                        val response = runtime.call("/api/evaluate-move", JSONObject().put("fen_before", ply.before)
                            .put("move_uci", ply.uci).put("depth", state.depth)
                            .put("start_fen", game.startFen).put("player_elo", rating)
                            .put("prev_was_opponent_error", previousGrade in listOf("blunder", "mistake", "miss"))
                            .put("history_san", JSONArray(game.plies.take(index).map { it.san }))) as JSONObject
                        if (generation != epoch) break
                        require(response.optBoolean("is_legal")) { "Engine rejected this move." }
                        state = state.copy(feedback = state.feedback + (ply.id to response), moveErrors = state.moveErrors - ply.id)
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        if (generation == epoch) state = state.copy(moveErrors = state.moveErrors + (ply.id to (error.message ?: "Analysis failed. Tap Retry.")))
                    }
                    pending.removeFirst()
                    state = state.copy(queued = pending.size)
                    if (pending.isNotEmpty()) notifyWork("Coaching ${pending.size} remaining moves")
                    persistSession()
                }
            } finally { if (generation == epoch) stopNotification() }
        }
    }
    fun retryCoaching() {
        if (state.mode != GameMode.LIVE && reviewOriginal == null) return
        val moves = if (reviewOriginal == null) game.plies else game.plies.drop((state.variationRoot ?: -1) + 1)
        for (ply in moves) if (!state.feedback.containsKey(ply.id) && pending.none { it.id == ply.id }) pending.addLast(ply)
        state = state.copy(queued = pending.size, moveErrors = emptyMap())
        if (pending.isNotEmpty()) { notifyWork(); drainCoaching() }
    }
    private fun aiReply() {
        if (game.isOver || game.turn == side || state.thinking) return
        val generation = epoch
        state = state.copy(thinking = true, error = null)
        notifyWork("Stockfish is thinking…")
        work = scope.launch {
            try {
                val reply = runtime.call("/api/play/move", JSONObject().put("level", level).put("start_fen", game.startFen)
                    .put("moves", JSONArray(game.plies.map { it.uci })).put("think_time", thinkTime).put("game_id", gameId)) as JSONObject
                if (generation == epoch && reply.has("move_uci") && !reply.isNull("move_uci")) {
                    val ply = game.play(reply.getString("move_uci"))
                    state = state.copy(position = game.position(), history = game.plies.toList(), index = game.plies.lastIndex, result = game.result)
                    if (state.sound) onMoveSound?.invoke(ply.capture)
                    persistSession()
                    if (game.isOver) saveGame()
                }
            } catch (error: Exception) { if (error !is CancellationException && generation == epoch) state = state.copy(error = error.message ?: "Opponent failed. Retry.") }
            finally { if (generation == epoch) { state = state.copy(thinking = false); stopNotification() } }
        }
    }
    fun retryAi() { aiReply() }
    fun undo() {
        if (state.mode !in listOf(GameMode.LIVE, GameMode.AI) || game.plies.isEmpty()) return
        epoch++; work?.cancel(); work = null; stopNotification(); pending.clear()
        game.undo()
        if (state.mode == GameMode.AI && game.turn != side && game.plies.isNotEmpty()) game.undo()
        val ids = game.plies.map { it.id }.toSet()
        state = state.copy(position = boardPosition(), history = game.plies.toList(), index = game.plies.lastIndex,
            feedback = state.feedback.filterKeys { it in ids }, moveErrors = state.moveErrors.filterKeys { it in ids },
            result = "*", queued = 0, thinking = false, best = false)
        persistSession()
        if (state.mode == GameMode.LIVE) retryCoaching() else if (game.turn != side) aiReply()
    }
    fun resign() {
        epoch++; work?.cancel(); work = null; stopNotification()
        game.result = if (side == "white") "0-1" else "1-0"
        state = state.copy(result = game.result, thinking = false)
        saveGame(); persistSession()
    }
    fun select(index: Int) {
        if (index !in -1..game.plies.lastIndex) return
        state = state.copy(index = index, position = boardPosition(index), best = false, practice = false)
        persistSession()
    }
    fun next() { select((state.index + 1).coerceAtMost(game.plies.lastIndex)) }
    fun previous() { select((state.index - 1).coerceAtLeast(-1)) }
    fun currentFeedback(): JSONObject? = game.plies.getOrNull(state.index)?.let { state.feedback[it.id] }
    fun resumeOriginal() {
        val original = reviewOriginal ?: return
        val index = minOf(state.index, state.variationRoot ?: -1)
        epoch++; work?.cancel(); work = null; pending.clear(); stopNotification()
        game = original
        reviewOriginal = null
        val ids = game.plies.map { it.id }.toSet()
        state = state.copy(history = game.plies.toList(), index = index, position = boardPosition(index), variationRoot = null,
            feedback = state.feedback.filterKeys { it in ids }, moveErrors = state.moveErrors.filterKeys { it in ids },
            result = game.result, queued = 0, thinking = false, best = false, practice = false, error = null)
        persistSession()
    }
    fun showBest() {
        val feedback = currentFeedback() ?: return
        val best = feedback.optJSONObject("best_move")?.optString("uci") ?: return
        if (best.isBlank() || best == feedback.optString("uci")) return
        state = state.copy(best = !state.best, practice = false, position = boardPosition(state.index))
    }
    fun startPractice() {
        val feedback = currentFeedback() ?: return
        state = state.copy(practice = true, practiceSolved = false, best = false, practiceMessage = "Try the better move on the board.",
            position = ChessGame(feedback.getString("fen_before")).position())
    }
    private fun practiceMove(uci: String) {
        if (state.practiceSolved) return
        val feedback = currentFeedback() ?: return
        val position = state.position
        val generation = epoch
        scope.launch {
            try {
                val reply = runtime.call("/api/check-solution", JSONObject().put("fen", feedback.getString("fen_before"))
                    .put("move_uci", uci).put("best_move_uci", feedback.getJSONObject("best_move").getString("uci"))) as JSONObject
                if (generation != epoch || !state.practice || state.position !== position || state.practiceSolved) return@launch
                state = state.copy(practiceMessage = reply.optString("message"))
                if (reply.optBoolean("is_best")) state = state.copy(practiceSolved = true, position = ChessGame.preview(feedback.getString("fen_before"), uci, game.position(state.index - 1).pieces))
            } catch (error: Exception) {
                if (error !is CancellationException && generation == epoch && state.practice && state.position === position)
                    state = state.copy(error = error.message)
            }
        }
    }
    fun exitPractice() { state = state.copy(practice = false, best = false, position = boardPosition(state.index)) }
    fun cancelAnalysis() {
        val cancelled = pending.associate { it.id to "Analysis cancelled. Tap Retry." }
        epoch++; work?.cancel(); work = null; pending.clear(); stopNotification()
        state = state.copy(thinking = false, queued = 0, error = "Analysis cancelled. Your game is saved.", moveErrors = state.moveErrors + cancelled)
        persistSession()
    }
    fun reviewCurrent() { analyzePgn(originalGame.pgn(), gameId) }
    fun retryAnalysis() {
        if (state.mode == GameMode.REVIEW && state.total > 0) refreshReview()
        else analyzePgn(analysisPgn ?: game.pgn(), gameId)
    }
    private suspend fun requestReview(pgn: String, generation: Long): JSONObject =
        runtime.call("/api/analyze", JSONObject().put("pgn", pgn).put("depth", state.depth)) { progress ->
            scope.launch {
                if (generation == epoch) {
                    state = state.copy(progress = progress.optInt("completed_plies"), total = progress.optInt("total_plies"))
                    notifyWork("${progress.optInt("completed_moves")} / ${progress.optInt("total_moves")} moves analysed")
                }
            }
        } as JSONObject

    private fun reviewedMoves(data: JSONObject, original: ChessGame): Map<Long, JSONObject> {
        val positions = data.getJSONArray("positions")
        require(positions.length() == original.plies.size) { "Review returned an incomplete game." }
        return original.plies.mapIndexed { index, ply ->
            val position = positions.getJSONObject(index)
            require(position.getString("uci") == ply.uci) { "Review move order changed. Retry analysis." }
            ply.id to position
        }.toMap()
    }

    private fun refreshReview() {
        val original = originalGame
        val pgn = original.pgn()
        epoch++; work?.cancel(); pending.clear(); stopNotification()
        val generation = epoch
        state = state.copy(screen = Screen.ANALYSIS, progress = 0, total = original.plies.size, analysis = null,
            feedback = emptyMap(), moveErrors = emptyMap(), queued = 0, best = false, practice = false, error = null)
        notifyWork()
        work = scope.launch {
            var ready = false
            try {
                val data = requestReview(pgn, generation)
                if (generation != epoch) return@launch
                state = state.copy(feedback = reviewedMoves(data, original), analysis = data, progress = original.plies.size,
                    screen = if (state.screen == Screen.ANALYSIS) Screen.REVIEW else state.screen)
                persistSession()
                ready = true
            } catch (error: Exception) {
                if (error !is CancellationException && generation == epoch) state = state.copy(error = error.message ?: "Analysis failed. Retry.")
            } finally {
                if (generation == epoch) {
                    work = null
                    stopNotification()
                    if (ready && reviewOriginal != null) retryCoaching()
                }
            }
        }
    }
    fun analyzePgn(pgn: String, savedId: String? = null) {
        require(pgn.length <= 1_000_000) { "PGN file exceeds 1 MB." }
        analysisPgn = pgn
        if (gameLoaded && game.plies.isNotEmpty() && state.mode != GameMode.OPENING) saveGame()
        reviewOriginal = null
        gameId = savedId ?: UUID.randomUUID().toString()
        epoch++; work?.cancel(); pending.clear(); stopNotification()
        val generation = epoch
        state = state.copy(screen = Screen.ANALYSIS, mode = GameMode.REVIEW, thinking = false, progress = 0,
            total = 0, error = null, queued = 0, analysis = null, feedback = emptyMap(), moveErrors = emptyMap(),
            best = false, practice = false, variationRoot = null, history = emptyList(), white = "White", black = "Black", resuming = false)
        persistSession()
        notifyWork()
        work = scope.launch {
            try {
                val parsed = runtime.call("/api/parse-pgn", JSONObject().put("pgn", pgn)) as JSONObject
                val parsedGame = withContext(Dispatchers.Default) { fromParsed(parsed) }
                if (generation != epoch) return@launch
                game = parsedGame
                gameLoaded = true
                state = state.copy(mode = GameMode.REVIEW, position = game.position(), history = game.plies.toList(), index = game.plies.lastIndex,
                    result = game.result, white = (game.headers["White"] ?: "White").take(80), black = (game.headers["Black"] ?: "Black").take(80),
                    total = game.plies.size, feedback = emptyMap(), analysis = null, best = false, practice = false, resumable = true)
                state = state.copy(whiteRating = game.headers["WhiteElo"]?.toIntOrNull()?.takeIf { it in 1..4000 },
                    blackRating = game.headers["BlackElo"]?.toIntOrNull()?.takeIf { it in 1..4000 })
                saveGame(); persistSession()
                val data = requestReview(pgn, generation)
                if (generation != epoch) return@launch
                val feedback = reviewedMoves(data, game)
                state = state.copy(feedback = feedback, analysis = data, progress = game.plies.size,
                    screen = if (state.screen == Screen.ANALYSIS) Screen.SUMMARY else state.screen)
                persistSession()
            } catch (error: Exception) {
                if (error !is CancellationException && generation == epoch) state = state.copy(error = error.message ?: "Analysis failed. Retry.")
            } finally { if (generation == epoch) stopNotification() }
        }
    }
    fun beginReview() { select(0); state = state.copy(screen = Screen.REVIEW) }
    fun saveGame(manual: Boolean = false) {
        // While parsing a review, game still holds the previous board.
        if (!gameLoaded || (state.mode == GameMode.REVIEW && state.total == 0)) return
        val pgn = originalGame.pgn()
        val title = "${state.white} vs ${state.black}"
        val id = gameId
        val limit = state.savedGameLimit
        scope.launch {
            try {
                libraryMutex.withLock {
                    val saved = withContext(Dispatchers.IO) { store.saveGame(id, pgn, title, limit, manual) }
                    state = state.copy(saved = saved)
                }
            }
            catch (error: Exception) {
                if (error !is CancellationException) state = state.copy(error = if (error is IllegalArgumentException) error.message else "Could not save game. Free some storage and retry.")
            }
        }
    }
    fun deleteSavedGame(id: String) {
        val entry = state.saved.firstOrNull { it.id == id } ?: return
        val currentId = gameId.takeIf { gameLoaded && state.history.isNotEmpty() && originalGame.pgn() == entry.pgn }
        scope.launch {
            try {
                libraryMutex.withLock {
                    val saved = withContext(Dispatchers.IO) { store.deleteGame(id, currentId) }
                    state = state.copy(saved = saved)
                }
            } catch (error: Exception) {
                if (error !is CancellationException) state = state.copy(error = "Could not delete saved game. Retry.")
            }
        }
    }
    fun exportPgn(): String = originalGame.pgn()
    fun fetchGames(provider: String, username: String) {
        if (state.importing) return
        val generation = ++importEpoch
        state = state.copy(importing = true, error = null)
        importWork = scope.launch {
            try {
                val key = "downloads-$provider-${username.trim().lowercase()}"
                val cache = withContext(Dispatchers.IO) { store.read(key) }
                state = state.copy(downloadAccount = key, downloads = cache?.let { GameImporter.decodeCache(it) } ?: emptyList())
                val downloaded = importer.fetch(provider, username)
                if (generation != importEpoch) return@launch
                state = state.copy(downloads = downloaded)
                withContext(Dispatchers.IO) {
                    val value = GameImporter.encodeCache(downloaded)
                    if (value.toByteArray().size <= 2_000_000) {
                        val keys = JSONArray(store.read("download-keys") ?: "[]")
                        var bytes = value.toByteArray().size
                        val recent = (listOf(key) + (0 until keys.length()).map { keys.getString(it) }.filter { it != key }).take(4).filter { cachedKey ->
                            if (cachedKey == key) true else {
                                bytes += (store.read(cachedKey) ?: "[]").toByteArray().size
                                bytes <= 2_000_000
                            }
                        }
                        (0 until keys.length()).map { keys.getString(it) }.filter { it !in recent }.forEach { store.write(it, "[]") }
                        store.write(key, value); store.write("download-keys", JSONArray(recent).toString())
                    }
                }
            } catch (error: Exception) { if (error !is CancellationException && generation == importEpoch) state = state.copy(error = error.message ?: "Download failed. Check internet connection.") }
            finally { if (generation == importEpoch) state = state.copy(importing = false) }
        }
    }
    fun cancelImport() { importEpoch++; importer.cancelActive(); importWork?.cancel(); state = state.copy(importing = false) }
    fun searchOpenings(query: String) {
        cancelRestore()
        state = state.copy(screen = Screen.OPENINGS, loadingOpenings = true)
        searchWork?.cancel()
        searchWork = scope.launch {
            try {
                delay(180)
                val path = if (query.isBlank()) "/api/openings/popular?limit=40" else "/api/openings/search?q=${URLEncoder.encode(query, "UTF-8")}&limit=60"
                val entries = runtime.call(path) as JSONArray
                state = state.copy(openingResults = (0 until entries.length()).map { entries.getJSONObject(it) }, loadingOpenings = false)
            } catch (error: Exception) { if (error !is CancellationException) state = state.copy(error = error.message, loadingOpenings = false) }
        }
    }
    fun openOpening(entry: JSONObject) {
        scope.launch {
            try {
                val opening = withContext(Dispatchers.Default) {
                    val line = MoveList().apply { loadFromSan(entry.getString("moves_str")) }
                    ChessGame(headers = mapOf("White" to "White", "Black" to "Black")).apply { line.forEach { play(it.toString()) } }
                }
                reset(opening, GameMode.OPENING, Screen.OPENING)
                select(-1)
                state = state.copy(openingTitle = entry.getString("name"), openingDescription = entry.optString("theme", entry.optString("description")))
            } catch (error: Exception) { state = state.copy(error = error.message) }
        }
    }
    private fun persistSession() {
        if (state.mode == GameMode.OPENING) return
        val original = originalGame
        val pgn = if (state.mode == GameMode.REVIEW && state.total == 0) analysisPgn ?: original.pgn() else original.pgn()
        val variation = state.variationRoot?.let { root ->
            val moves = game.plies.drop(root + 1)
            VariationSnapshot(root, moves.map { it.uci }, moves.map { state.feedback[it.id] })
        }
        saves.trySend(SessionSnapshot(gameId, pgn, state.mode.name, level, side, thinkTime, state.flipped,
            original.plies.map { state.feedback[it.id] }, state.analysis, state.index, variation))
    }
    fun resumeGame() {
        if (state.resuming) return
        if (state.mode == GameMode.OPENING) gameLoaded = false
        if (gameLoaded) {
            state = state.copy(screen = when (state.mode) {
                GameMode.LIVE -> Screen.LIVE
                GameMode.AI -> Screen.AI_GAME
                GameMode.REVIEW -> if (state.analysis != null) Screen.REVIEW else Screen.ANALYSIS
                GameMode.OPENING -> Screen.OPENING
            })
            if (state.mode == GameMode.LIVE && work?.isActive != true) retryCoaching()
            if (reviewOriginal != null && work?.isActive != true) retryCoaching()
            if (state.mode == GameMode.REVIEW && state.analysis == null && work?.isActive != true) retryAnalysis()
            return
        }
        val saved = restore ?: return
        val generation = epoch
        state = state.copy(resuming = true, error = null)
        scope.launch {
            try {
                val parsed = runtime.call("/api/parse-pgn", JSONObject().put("pgn", saved.getString("pgn")).put("allow_empty", true)) as JSONObject
                val restored = withContext(Dispatchers.Default) { fromParsed(parsed) }
                if (generation != epoch) return@launch
                val mode = GameMode.valueOf(saved.getString("mode"))
                val cacheCurrent = saved.optString("analysis_revision") == BuildConfig.ANALYSIS_REVISION
                val variation = saved.optJSONObject("variation")?.takeIf { mode == GameMode.REVIEW }?.let { row ->
                    withContext(Dispatchers.Default) {
                        runCatching {
                            val root = row.getInt("root")
                            val moves = row.getJSONArray("moves")
                            require(root in -1..restored.plies.lastIndex && moves.length() > 0 && root + 1 + moves.length() <= 2000)
                            root to restored.fork(root).apply { for (i in 0 until moves.length()) play(moves.getString(i)) }
                        }
                    }
                }
                if (generation != epoch) return@launch
                level = saved.optString("level", "1800"); side = saved.optString("side", "white"); thinkTime = saved.optDouble("time", 3.0)
                reset(restored, mode, when (mode) { GameMode.LIVE -> Screen.LIVE; GameMode.AI -> Screen.AI_GAME; else -> Screen.REVIEW }, saveSession = false)
                gameId = saved.optString("game_id").takeIf { it.isNotBlank() } ?: gameId
                val feedback = if (cacheCurrent) saved.optJSONArray("feedback") ?: JSONArray() else JSONArray()
                state = state.copy(flipped = saved.optBoolean("flipped"), analysis = if (cacheCurrent) saved.optJSONObject("analysis") else null,
                    feedback = game.plies.mapIndexedNotNull { index, ply -> feedback.optJSONObject(index)?.let { ply.id to it } }.toMap())
                variation?.getOrNull()?.let { (root, branch) ->
                    reviewOriginal = game
                    game = branch
                    val replies = if (cacheCurrent) saved.getJSONObject("variation").optJSONArray("feedback") ?: JSONArray() else JSONArray()
                    val branchFeedback = branch.plies.drop(root + 1).mapIndexedNotNull { i, ply ->
                        replies.optJSONObject(i)?.takeIf { it.optString("uci") == ply.uci }?.let { ply.id to it }
                    }.toMap()
                    state = state.copy(history = branch.plies.toList(), variationRoot = root, result = branch.result, feedback = state.feedback + branchFeedback)
                }
                val index = saved.optInt("index", game.plies.lastIndex).coerceIn(-1, game.plies.lastIndex)
                state = state.copy(index = index, position = boardPosition(index),
                    error = if (variation?.isFailure == true) "Could not restore variation. Original game is available." else null)
                persistSession()
                if (mode == GameMode.LIVE) retryCoaching()
                else if (mode == GameMode.AI && game.turn != side && !game.isOver) aiReply()
                else if (mode == GameMode.REVIEW && (state.analysis == null || originalGame.plies.any { it.id !in state.feedback })) refreshReview()
                else if (reviewOriginal != null) retryCoaching()
            } catch (error: Exception) {
                if (error !is CancellationException && generation == epoch) state = state.copy(error = error.message)
            } finally {
                if (generation == epoch) state = state.copy(resuming = false)
            }
        }
    }
    private fun fromParsed(parsed: JSONObject): ChessGame {
        val headers = parsed.getJSONObject("headers")
        val game = ChessGame(parsed.getString("start_fen"), headers.keys().asSequence().associateWith { headers.getString(it) })
        val moves = parsed.getJSONArray("moves")
        for (index in 0 until moves.length()) game.play(moves.getString(index), importing = true)
        game.result = headers.optString("Result", "*")
        return game
    }
}
