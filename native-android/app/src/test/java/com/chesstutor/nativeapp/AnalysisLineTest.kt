package com.chesstutor.nativeapp

import org.junit.Assert.*
import org.junit.Test

class AnalysisLineTest {
    private fun opening() = ChessGame().apply {
        listOf("e2e4", "e7e5", "g1f3", "b8c6").forEach { play(it) }
    }

    @Test fun liveRewindReplacesFutureWithoutReusingMoveIds() {
        val game = opening()
        val prefix = game.plies.first()
        val removedIds = game.plies.drop(1).map { it.id }
        assertTrue("c7c5" in game.position(0, editable = true).legal)
        game.truncateAfter(0)
        val replacement = game.play("c7c5")
        assertEquals(listOf("e4", "c5"), game.plies.map { it.san })
        assertEquals(prefix, game.plies.first())
        assertTrue(replacement.id > removedIds.max())
        assertFalse(game.pgn().contains("Nf3"))
        game.truncateAfter(-1)
        assertEquals(game.startFen, game.fen)
        assertEquals("d4", game.play("d2d4").san)
    }

    @Test fun reviewForkKeepsOriginalPgnAndPieceIdentity() {
        val original = opening()
        original.result = "1-0" // A resignation does not make its final position unplayable for study.
        val pgn = original.pgn()
        val branch = original.fork(1)
        val bishop = original.position(1).pieces.first { it.square == ChessGame.squareIndex("f1") }
        branch.play("f1c4")
        branch.play("g8f6")
        assertEquals(pgn, original.pgn())
        assertEquals("b8c6", original.plies.last().uci)
        assertEquals(bishop.id, branch.position().pieces.first { it.square == ChessGame.squareIndex("c4") }.id)
        assertEquals(original.plies.take(2), branch.plies.take(2))
        assertEquals("*", branch.result)
        assertTrue("d2d3" in branch.position().legal)
        assertTrue(original.position().legal.isEmpty())
        assertTrue(original.position(editable = true).legal.isNotEmpty())
    }

    @Test fun editingBranchAndStartingAnotherNeverReuseDiscardedFeedbackIds() {
        val original = opening()
        val branch = original.fork(1)
        val discarded = branch.play("f1c4")
        branch.play("g8f6")
        branch.truncateAfter(1)
        val replacement = branch.play("d2d4")
        assertTrue(replacement.id > discarded.id)
        val other = original.fork(-1).play("c2c4")
        assertTrue(other.id > replacement.id)
        assertEquals(listOf("e4", "e5", "Nf3", "Nc6"), original.plies.map { it.san })
    }

    @Test fun rewindAndBranchPreserveRepetitionHistory() {
        val original = ChessGame()
        listOf("g1f3", "g8f6", "f3g1", "f6g8", "g1f3", "g8f6", "f3g1").forEach { original.play(it) }
        val branch = original.fork(original.plies.lastIndex)
        branch.play("f6g8")
        assertEquals("1/2-1/2", branch.result)
        assertEquals("*", original.result)
        branch.undo()
        assertEquals("*", branch.result)
        branch.play("f6g8")
        assertEquals("1/2-1/2", branch.result)
        original.truncateAfter(3)
        listOf("g1f3", "g8f6", "f3g1", "f6g8").forEach { original.play(it) }
        assertEquals("1/2-1/2", original.result)
    }

    @Test fun forksPreserveEnPassantCastlingPromotionAndBlackFirstNumbering() {
        val ep = ChessGame("4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 2").fork(-1)
        assertTrue(ep.play("e5d6").capture)
        ep.undo()
        assertTrue("e5d6" in ep.position().legal)
        val castle = ChessGame("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1").fork(-1)
        castle.play("e1g1"); castle.undo()
        assertTrue("e1c1" in castle.position().legal)
        val promotion = ChessGame("7k/P7/8/8/8/8/8/7K w - - 0 1").fork(-1)
        promotion.play("a7a8n")
        assertEquals('N', promotion.position().pieces.first { it.square == 56 }.symbol)
        val black = ChessGame("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR b KQkq - 0 7").fork(-1)
        black.play("e7e5")
        assertTrue(black.pgn().contains("7... e5"))
    }

    @Test fun rewindFromCheckmateRestoresPlayableEarlierPosition() {
        val game = ChessGame()
        listOf("f2f3", "e7e5", "g2g4", "d8h4").forEach { game.play(it) }
        assertTrue(game.position(editable = true).legal.isEmpty())
        assertTrue("b8c6" in game.position(2, editable = true).legal)
        game.truncateAfter(2)
        game.play("b8c6")
        assertEquals("*", game.result)
        assertFalse(game.isOver)
    }
}
