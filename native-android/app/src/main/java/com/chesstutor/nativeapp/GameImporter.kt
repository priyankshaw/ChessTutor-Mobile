package com.chesstutor.nativeapp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import kotlin.coroutines.coroutineContext

data class DownloadedGame(val id: String, val white: String, val black: String, val result: String,
                          val speed: String, val pgn: String, val timestamp: Long)

class GameImporter {
    private val cooldown = mutableMapOf<String, Long>()
    private val serial = Mutex()
    private val cancellations = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var activeConnection: HttpURLConnection? = null
    fun cancelActive() { val current = activeConnection; cancellations.execute { current?.disconnect() } }
    suspend fun fetch(provider: String, input: String): List<DownloadedGame> = withContext(Dispatchers.IO) {
        serial.withLock {
        require(provider in listOf("chesscom", "lichess")) { "Choose Chess.com or Lichess." }
        val username = input.trim().lowercase()
        require(username.matches(Regex("[a-z0-9_-]{2,50}"))) { "Enter a valid public username." }
        require((cooldown[provider] ?: 0L) <= System.currentTimeMillis()) { "Too many requests. Wait a minute before refreshing." }
        val games = mutableListOf<DownloadedGame>()
        if (provider == "chesscom") {
            val base = "https://api.chess.com/pub/player/$username/games"
            val months = JSONObject(download("$base/archives", provider)).getJSONArray("archives")
            val valid = (0 until months.length()).mapNotNull {
                Regex("/games/(\\d{4}/(?:0[1-9]|1[0-2]))$").find(months.optString(it))?.groupValues?.get(1)
            }.distinct().sortedDescending().take(3)
            for (month in valid) {
                coroutineContext.ensureActive()
                val archive = JSONObject(download("$base/$month", provider)).getJSONArray("games")
                for (index in 0 until archive.length()) {
                    val game = archive.getJSONObject(index)
                    val pgn = game.optString("pgn")
                    if (game.optString("rules", "chess") != "chess" || pgn.isBlank() || pgn.length > 100_000) continue
                    games += DownloadedGame(game.optString("url", "$month-$index"),
                        (game.optJSONObject("white")?.optString("username") ?: "White").take(80),
                        (game.optJSONObject("black")?.optString("username") ?: "Black").take(80),
                        pgnResult(pgn), game.optString("time_class"), pgn, game.optLong("end_time"))
                }
                if (games.size >= 20) break
            }
        } else {
            val text = download("https://lichess.org/api/games/user/$username?max=20&pgnInJson=true&perfType=ultraBullet,bullet,blitz,rapid,classical,correspondence&moves=true&evals=false&clocks=false&ongoing=false&finished=true&sort=dateDesc", provider)
            for (line in text.lineSequence().filter { it.isNotBlank() }) {
                val game = JSONObject(line)
                val pgn = game.optString("pgn")
                if (game.optString("variant", "standard") != "standard" || pgn.isBlank() || pgn.length > 100_000) continue
                val players = game.optJSONObject("players")
                games += DownloadedGame(game.optString("id"), (players?.optJSONObject("white")?.optJSONObject("user")?.optString("name") ?: "Anonymous").take(80),
                    (players?.optJSONObject("black")?.optJSONObject("user")?.optString("name") ?: "Anonymous").take(80),
                    pgnResult(pgn), game.optString("speed"), pgn, game.optLong("lastMoveAt", game.optLong("createdAt")) / 1000)
            }
        }
        games.sortedByDescending { it.timestamp }.distinctBy { it.id }.take(20)
        }
    }
    private suspend fun download(url: String, provider: String): String {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        activeConnection = connection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 20_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("Accept", if (provider == "lichess") "application/x-ndjson" else "application/json")
        connection.setRequestProperty("User-Agent", "ChessTutor-Android/1.0 (public game import)")
        try {
            coroutineContext.ensureActive()
            when (connection.responseCode) {
                200 -> Unit
                429 -> {
                    cooldown[provider] = System.currentTimeMillis() + (connection.getHeaderField("Retry-After")?.toLongOrNull() ?: 60).coerceIn(60, 3600) * 1000
                    error("Too many requests. Wait at least a minute before refreshing.")
                }
                404, 410 -> error("Username not found, or public games unavailable.")
                401, 403 -> error("Provider denied public access. Try later or import PGN.")
                else -> error("Game provider unavailable. Try later.")
            }
            require(connection.contentLengthLong <= 16_000_000) { "Archive too large. Import a PGN file instead." }
            val bytes = connection.inputStream.use { stream ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    coroutineContext.ensureActive()
                    val count = stream.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 16_000_000) { "Archive too large. Import a PGN file instead." }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            return bytes.toString(Charsets.UTF_8)
        } finally { connection.disconnect(); if (activeConnection === connection) activeConnection = null }
    }
    companion object {
        fun pgnResult(pgn: String) = Regex("\\[Result\\s+\"(1-0|0-1|1/2-1/2|\\*)\"\\]").find(pgn)?.groupValues?.get(1) ?: "*"
        fun encodeCache(games: List<DownloadedGame>) = JSONArray(games.map { JSONObject().put("id", it.id).put("white", it.white).put("black", it.black).put("result", it.result).put("speed", it.speed).put("pgn", it.pgn).put("timestamp", it.timestamp) }).toString()
        fun decodeCache(json: String): List<DownloadedGame> {
            val array = JSONArray(json)
            return (0 until array.length()).map { array.getJSONObject(it).let { g -> DownloadedGame(g.getString("id"), g.getString("white"), g.getString("black"), g.getString("result"), g.getString("speed"), g.getString("pgn"), g.getLong("timestamp")) } }
        }
    }
}
