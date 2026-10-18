package com.chesstutor.nativeapp

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GameImporterTest {
    @Test fun rejectsInvalidProviderAndUsernameBeforeNetwork() = runBlocking {
        for ((provider, username) in listOf("unknown" to "alice", "chesscom" to "../alice", "lichess" to "x", "chesscom" to "user?x=1")) {
            val result = runCatching { GameImporter().fetch(provider, username) }
            assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        }
    }
    @Test fun preservesCachedPgnAndResults() {
        val game = DownloadedGame("1", "Alice", "Bob", "1-0", "blitz", "[Result \"1-0\"]\n\n1. e4 e5 1-0", 123)
        assertEquals(listOf(game), GameImporter.decodeCache(GameImporter.encodeCache(listOf(game))))
        assertEquals("1-0", GameImporter.pgnResult(game.pgn))
        assertEquals("*", GameImporter.pgnResult("1. e4 *"))
    }
}
