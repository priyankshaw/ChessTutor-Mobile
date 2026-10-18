package com.chesstutor.nativeapp

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray

class ChessGameTest {
    @Test fun incrementalPlayUndoAndStableIdentity() {
        val game = ChessGame()
        val pawn = game.position().pieces.first { it.square == 12 }
        val move = game.play("e2e4")
        assertEquals("e4", move.san)
        assertEquals(pawn.id, game.position().pieces.first { it.square == 28 }.id)
        game.undo()
        assertEquals(game.startFen, game.fen)
        assertEquals(pawn, game.position().pieces.first { it.square == 12 })
    }
    @Test fun enPassantCastlingAndAllPromotions() {
        val ep = ChessGame("4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 2")
        assertTrue(ep.play("e5d6").capture)
        assertFalse(ep.position().pieces.any { it.square == 35 })
        val castle = ChessGame("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1")
        val rook = castle.position().pieces.first { it.square == 7 }
        assertEquals("O-O", castle.play("e1g1").san)
        assertEquals(rook.id, castle.position().pieces.first { it.square == 5 }.id)
        for (piece in "qrbn") {
            val game = ChessGame("7k/P7/8/8/8/8/8/7K w - - 0 1")
            game.play("a7a8$piece")
            assertEquals(piece.uppercaseChar(), game.position().pieces.first { it.square == 56 }.symbol)
        }
    }
    @Test fun mateRepetitionAndBlackFirstPgn() {
        val game = ChessGame()
        listOf("f2f3", "e7e5", "g2g4", "d8h4").forEach { game.play(it) }
        assertTrue(game.isOver)
        assertEquals("0-1", game.result)
        assertEquals("Qh4#", game.plies.last().san)
        val repeat = ChessGame()
        repeat(2) { listOf("g1f3", "g8f6", "f3g1", "f6g8").forEach { repeat.play(it) } }
        assertEquals("1/2-1/2", repeat.result)
        val black = ChessGame("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR b KQkq - 0 7")
        black.play("e7e5")
        assertTrue(black.pgn().contains("7... e5"))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectIllegalMove() { ChessGame().play("e2e5") }
    @Test fun matchesPythonChessLegalMovesAndSan() {
        val fixtures = JSONArray(javaClass.getResource("/chess-rules.json")!!.readText())
        for (index in 0 until fixtures.length()) {
            val fixture = fixtures.getJSONObject(index)
            val game = ChessGame(fixture.getString("fen"))
            val expected = fixture.getJSONArray("legal")
            assertEquals("Position $index", (0 until expected.length()).map { expected.getString(it) }.sorted(), game.position().legal.sorted())
            assertEquals("SAN at position $index", fixture.getString("san"), game.play(fixture.getString("move"), importing = true).san)
        }
    }
}
