package com.chesstutor.nativeapp

import org.junit.Assert.*
import org.junit.Test

class SavedGameLibraryTest {
    private fun game(id: String, pgn: String = "[Event \"$id\"]\n\n1. e4 *") = SavedGame(id, "White vs Black", pgn)

    @Test fun fourthGameEvictsOldestAndProgressUpdatesExistingEntry() {
        val old = listOf(game("3"), game("2"), game("1"))
        val added = SavedGameLibrary.save(old, game("4"), 3)
        assertEquals(listOf("4", "3", "2"), added.map { it.id })
        val progress = game("4", "1. e4 e5 2. Nf3 *")
        val updated = SavedGameLibrary.save(added, progress, 3)
        assertEquals(3, updated.size)
        assertEquals(progress, updated.first())
        assertEquals(1, updated.count { it.id == "4" })
    }
    @Test fun importingIdenticalPgnDoesNotCreateAnotherCopy() {
        val original = game("old")
        val imported = original.copy(id = "new")
        assertEquals(listOf(imported), SavedGameLibrary.save(listOf(original), imported, 3))
    }
    @Test fun automaticSaveCannotResurrectDeletedGameButExplicitSaveCan() {
        val old = listOf(game("other"))
        assertEquals(old, SavedGameLibrary.save(old, game("deleted"), 3, setOf("deleted")))
        assertEquals(listOf("deleted", "other"), SavedGameLibrary.save(old, game("deleted"), 3, setOf("deleted"), manual = true).map { it.id })
    }
    @Test fun retentionUsesUtf8ByteBudgetAndRejectsOversizedGameBeforeReplacingLibrary() {
        val first = game("first", "♟".repeat(350_000))
        val second = game("second", "♟".repeat(350_000))
        assertEquals(listOf(second), SavedGameLibrary.save(listOf(first), second, 3))
        val old = listOf(game("safe"))
        val result = runCatching { SavedGameLibrary.save(old, game("oversized", "♟".repeat(700_000)), 3) }
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertEquals("safe", old.single().id)
    }
}
