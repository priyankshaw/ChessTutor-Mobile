package com.chesstutor.nativeapp

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

class GameStore(context: Context) : SQLiteOpenHelper(context, "chesstutor.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) { db.execSQL("CREATE TABLE values_store (name TEXT PRIMARY KEY, value TEXT NOT NULL)") }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    @Synchronized fun read(key: String): String? = readableDatabase.rawQuery("SELECT value FROM values_store WHERE name=?", arrayOf(key)).use {
        if (it.moveToFirst()) it.getString(0) else null
    }
    @Synchronized fun write(key: String, value: String) {
        writableDatabase.insertWithOnConflict("values_store", null, ContentValues().apply { put("name", key); put("value", value) }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    @Synchronized fun saveGame(id: String, pgn: String, title: String, limit: Int, manual: Boolean = false): List<SavedGame> {
        val ignored = ignoredIds()
        val games = SavedGameLibrary.save(saved(), SavedGame(id, title, pgn), limit, ignored, manual)
        writableDatabase.beginTransaction()
        try {
            if (manual) write("ignored-autosaves", JSONArray(ignored - id).toString())
            writeGames(games)
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
        return games
    }
    @Synchronized fun deleteGame(id: String, currentId: String? = null): List<SavedGame> {
        val games = saved().filterNot { it.id == id }
        writableDatabase.beginTransaction()
        try {
            writeGames(games)
            write("ignored-autosaves", JSONArray((listOfNotNull(id, currentId) + ignoredIds()).distinct().take(30)).toString())
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
        return games
    }
    private fun ignoredIds(): Set<String> {
        val array = runCatching { JSONArray(read("ignored-autosaves") ?: "[]") }.getOrDefault(JSONArray())
        return (0 until array.length()).map { array.getString(it) }.toSet()
    }
    private fun writeGames(games: List<SavedGame>) {
        write("games", JSONArray(games.map { JSONObject().put("id", it.id).put("title", it.title).put("pgn", it.pgn) }).toString())
    }
    fun saved(): List<SavedGame> = runCatching {
        val array = JSONArray(read("games") ?: "[]")
        (0 until array.length()).map { array.getJSONObject(it).let { row -> SavedGame(row.getString("id"), row.getString("title"), row.getString("pgn")) } }
    }.getOrDefault(emptyList())
}
data class SavedGame(val id: String, val title: String, val pgn: String)

internal object SavedGameLibrary {
    fun save(old: List<SavedGame>, game: SavedGame, limit: Int, ignored: Set<String> = emptySet(), manual: Boolean = false): List<SavedGame> {
        if (!manual && game.id in ignored) return old
        require(game.pgn.toByteArray(Charsets.UTF_8).size <= 2_000_000) { "Game is too large for the saved library. Export PGN to keep a copy." }
        val games = (listOf(game) + old.filterNot { it.id == game.id || it.pgn == game.pgn }).take(limit.coerceIn(1, 30))
        var bytes = 0
        return games.takeWhile { bytes += it.pgn.toByteArray(Charsets.UTF_8).size; bytes <= 2_000_000 }
    }
}
