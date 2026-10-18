package com.chesstutor.nativeapp

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import java.io.File
import androidx.compose.ui.geometry.Offset

class NativeFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val model get() = (compose.activity.application as TutorApplication).model
    @Before fun isolateSession() {
        compose.runOnIdle { model.cancelAnalysis(); model.clearError(); model.home() }
    }
    private fun snapshot(name: String) {
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        Thread.sleep(150)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val file = File(compose.activity.getExternalFilesDir(null), "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun homeSetupImportAndLiveMoves() {
        compose.runOnIdle { model.home() }
        compose.onNodeWithText("Learn. Play.").assertIsDisplayed()
        snapshot("home")
        compose.onNodeWithText("Play against AI").performClick()
        compose.onNodeWithText("Choose your opponent").assertIsDisplayed()
        snapshot("ai-setup")
        compose.onNodeWithContentDescription("Back to home").performClick()
        compose.onNodeWithText("Import from Chess.com or Upload PGN").performClick()
        compose.onNodeWithText("Import Chess Game").assertIsDisplayed()
        snapshot("import")
        compose.onNodeWithContentDescription("Close dialog").performClick()
        compose.runOnIdle { model.startLive() }
        snapshot("live-initial")
        compose.onNodeWithText("Live Analysis").assertIsDisplayed()
        compose.onNodeWithContentDescription("e2, White pawn", useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription("e4, empty", useUnmergedTree = true).performClick()
        compose.runOnIdle { assertEquals("e4", model.state.history.last().san) }
        compose.runOnIdle { assertTrue("board disabled", model.canMove); assertTrue("Black moves missing: ${model.state.position.legal}", "e7e5" in model.state.position.legal) }
        compose.onNodeWithContentDescription("e7, Black pawn", useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription("e5, empty", useUnmergedTree = true).performClick()
        compose.runOnIdle { assertEquals("After Black's tap move: ${model.state.position.fen}", 2, model.state.history.size) }
        compose.onNodeWithContentDescription("g1, White knight", useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription("f3, empty", useUnmergedTree = true).performClick()
        compose.runOnIdle { assertEquals(3, model.state.history.size); assertEquals(2, model.state.index) }
        compose.waitUntil(120_000) { model.state.feedback.size == 3 || model.state.moveErrors.isNotEmpty() }
        compose.runOnIdle {
            assertTrue(model.state.moveErrors.toString(), model.state.moveErrors.isEmpty())
            assertEquals(3, model.state.feedback.size)
            assertEquals("Nf3", model.currentFeedback()?.getString("san"))
            assertEquals("g1f3", model.state.position.lastMove)
        }
        snapshot("live")
        compose.runOnIdle { model.select(0) }
        compose.runOnIdle { assertEquals("e4", model.currentFeedback()?.getString("san")) }
        compose.runOnIdle { model.select(2) }
        assertTrue(compose.onAllNodesWithContentDescription("Book Move", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        compose.runOnIdle {
            model.startLive()
            val start = android.os.SystemClock.elapsedRealtimeNanos()
            model.move("e2e4"); model.move("e7e5"); model.move("g1f3")
            val elapsedMs = (android.os.SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
            assertEquals(3, model.state.history.size)
            assertEquals(3, model.state.queued)
            assertNull(model.currentFeedback())
            File(compose.activity.getExternalFilesDir(null), "input-timing.json").writeText("{\"threeMovesMs\":$elapsedMs,\"device\":\"${android.os.Build.MODEL}\",\"build\":\"debug\"}")
        }
        compose.waitUntil(120_000) { model.state.feedback.size == 3 || model.state.moveErrors.isNotEmpty() }
        compose.runOnIdle {
            assertEquals(3, model.state.feedback.size)
            model.startPractice()
            model.move(model.currentFeedback()!!.getJSONObject("best_move").getString("uci"))
        }
        compose.waitUntil(30_000) { model.state.practiceSolved || model.state.error != null }
        compose.runOnIdle {
            assertNull(model.state.error)
            assertTrue(model.state.practiceSolved)
            assertFalse(model.canMove)
            assertEquals(3, model.state.history.size)
            model.exitPractice()
            assertTrue(model.canMove)
            model.startPractice()
            model.move(model.currentFeedback()!!.getJSONObject("best_move").getString("uci"))
            model.exitPractice()
        }
        compose.waitForIdle()
        Thread.sleep(300)
        compose.runOnIdle {
            assertEquals(model.state.history.last().after, model.state.position.fen)
            assertFalse(model.state.practice)
            model.cancelAnalysis(); model.clearError()
        }
    }
    @Test fun reviewFullOfflineGameAndSave() {
        compose.runOnIdle { model.analyzePgn("[White \"White\"]\n[Black \"Black\"]\n[WhiteElo \"523\"]\n[BlackElo \"831\"]\n[Result \"0-1\"]\n\n1. f3 e5 2. g4 Qh4# 0-1") }
        compose.waitUntil(120_000) { model.state.screen == Screen.SUMMARY || model.state.error != null }
        compose.runOnIdle { assertNull(model.state.error); assertEquals(4, model.state.feedback.size); assertNotNull(model.state.analysis) }
        compose.onNodeWithText("523").assertIsDisplayed()
        compose.onNodeWithText("831").assertIsDisplayed()
        snapshot("summary")
        compose.onNodeWithText("▶  Start Review").performClick()
        compose.runOnIdle { assertEquals(0, model.state.index); assertEquals("f3", model.currentFeedback()?.getString("san")) }
        snapshot("review")
        compose.runOnIdle { model.next(); model.next(); model.next() }
        compose.runOnIdle { assertEquals("Qh4#", model.currentFeedback()?.getString("san")); assertEquals("0-1", model.state.result) }
        compose.waitUntil(5_000) { model.state.saved.isNotEmpty() }
    }
    @Test fun aiHasNoCoachingUntilReview() {
        compose.runOnIdle { model.startAi("1800", "white", .1); model.move("e2e4") }
        compose.waitUntil(60_000) { model.state.history.size == 2 || model.state.error != null }
        compose.runOnIdle {
            assertNull(model.state.error)
            assertTrue(model.state.feedback.isEmpty())
            assertFalse(model.state.thinking)
            model.resign()
            assertEquals("0-1", model.state.result)
        }
        compose.onNodeWithText("Review", useUnmergedTree = true).assertExists()
    }
    @Test fun liveRewindReplacesFutureAndQueuesFeedbackForNewLine() {
        compose.runOnIdle {
            model.startLive()
            listOf("e2e4", "e7e5", "g1f3").forEach(model::move)
            val oldIds = model.state.history.map { it.id }
            model.select(0)
            assertTrue(model.canMove)
            model.move("e2e5")
            assertEquals(3, model.state.history.size)
            model.move("c7c5")
            assertEquals(listOf("e4", "c5"), model.state.history.map { it.san })
            assertTrue(model.state.history.last().id > oldIds.max())
        }
        compose.waitUntil(120_000) { model.state.queued == 0 || model.state.moveErrors.isNotEmpty() }
        compose.runOnIdle {
            assertTrue(model.state.moveErrors.toString(), model.state.moveErrors.isEmpty())
            assertEquals(2, model.state.feedback.size)
            assertEquals("c7c5", model.currentFeedback()?.getString("uci"))
        }
    }
    @Test fun importedVariationCanRewindResumeAndRestoreWithoutReplacingOriginal() {
        compose.runOnIdle { model.analyzePgn("[Result \"*\"]\n\n1. e4 e5 2. Nf3 Nc6 *") }
        compose.waitUntil(120_000) { model.state.screen == Screen.SUMMARY || model.state.error != null }
        lateinit var originalPgn: String
        compose.runOnIdle {
            assertNull(model.state.error)
            originalPgn = model.exportPgn()
            model.beginReview()
            model.move("e7e5")
            assertNull(model.state.variationRoot)
            assertEquals(1, model.state.index)
            model.move("f1c4")
            model.move("g8f6")
            model.move("d2d3")
            assertEquals(1, model.state.variationRoot)
            assertEquals(originalPgn, model.exportPgn())
        }
        compose.waitUntil(120_000) { model.state.queued == 0 || model.state.moveErrors.isNotEmpty() }
        compose.runOnIdle {
            assertTrue(model.state.moveErrors.toString(), model.state.moveErrors.isEmpty())
            assertEquals("d2d3", model.currentFeedback()?.getString("uci"))
        }
        val store = GameStore(compose.activity.application)
        compose.waitUntil(10_000) {
            store.read("session")?.let { org.json.JSONObject(it).optJSONObject("variation")?.optJSONArray("moves")?.length() == 3 } == true
        }
        lateinit var restored: ChessModel
        compose.runOnIdle { restored = ChessModel(compose.activity.application) }
        compose.waitUntil(10_000) { restored.state.resumable }
        compose.runOnIdle { restored.resumeGame() }
        compose.waitUntil(30_000) { !restored.state.resuming }
        compose.runOnIdle {
            assertNull(restored.state.error)
            assertEquals(1, restored.state.variationRoot)
            assertEquals(listOf("e4", "e5", "Bc4", "Nf6", "d3"), restored.state.history.map { it.san })
            assertEquals(originalPgn, restored.exportPgn())
            restored.resumeOriginal()
            restored.cancelAnalysis(); restored.clearError()
            model.previous(); model.previous(); model.previous()
            assertEquals(1, model.state.index)
        }
        compose.onNodeWithContentDescription("Resume original game").performClick()
        compose.runOnIdle {
            assertNull(model.state.variationRoot)
            assertEquals(listOf("e4", "e5", "Nf3", "Nc6"), model.state.history.map { it.san })
            model.next()
            assertEquals("g1f3", model.currentFeedback()?.getString("uci"))
            model.move("d7d6")
            assertEquals(2, model.state.variationRoot)
            model.resumeOriginal() // A late branch reply must not replace the original Nc6 feedback.
            model.next()
            assertEquals("b8c6", model.currentFeedback()?.getString("uci"))
            assertEquals(originalPgn, model.exportPgn())
        }
    }
    @Test fun dragBothColorsAndChoosePromotion() {
        compose.runOnIdle { model.startLive() }
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        compose.onNodeWithText("Live Analysis").assertIsDisplayed()
        compose.onNodeWithContentDescription("e2, White pawn", useUnmergedTree = true).performTouchInput {
            swipe(center, center + Offset(0f, -height * 2f), durationMillis = 200)
        }
        compose.runOnIdle { assertEquals("e4", model.state.history.last().san) }
        compose.onNodeWithContentDescription("e7, Black pawn", useUnmergedTree = true).performTouchInput {
            swipe(center, center + Offset(0f, height * 2f), durationMillis = 200)
        }
        compose.runOnIdle { assertEquals("e5", model.state.history.last().san); model.startLive("7k/P7/8/8/8/8/8/7K w - - 0 1") }
        compose.waitUntil(10_000) { model.state.position.fen.startsWith("7k/P7") }
        compose.onNodeWithContentDescription("a7, White pawn", useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription("a8, empty", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Choose promotion").assertIsDisplayed()
        compose.onNodeWithContentDescription("Promote to rook").performClick()
        compose.runOnIdle { assertEquals("a7a8r", model.state.history.last().uci); model.cancelAnalysis(); model.clearError() }
    }
    @Test fun reviewContinuesWhileAppBackgrounded() {
        val activity = compose.activity
        val notifications = activity.getSystemService(android.app.NotificationManager::class.java)
        compose.runOnIdle {
            model.analyzePgn("[White \"White\"]\n[Black \"Black\"]\n\n1. f3 e5 2. Nh3 d5 3. Nf2 Nc6 4. e3 Nf6 5. Bb5 Bd6 6. O-O O-O *")
        }
        compose.waitUntil(10_000) { notifications.activeNotifications.any { it.id == 41 } }
        val returnToGame = notifications.activeNotifications.first { it.id == 41 }.notification.contentIntent
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        try {
            assertTrue(automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME))
            val deadline = android.os.SystemClock.elapsedRealtime() + 120_000
            while (model.state.analysis == null && model.state.error == null && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
            assertNull(model.state.error)
            assertEquals(12, model.state.analysis?.getJSONArray("positions")?.length())
        } finally {
            returnToGame.send()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            compose.waitUntil(10_000) { activity.lifecycle.currentState == androidx.lifecycle.Lifecycle.State.RESUMED }
        }
    }
    @Test fun bundledOpeningsSearchAndStep() {
        compose.runOnIdle { model.searchOpenings("Italian") }
        compose.waitUntil(30_000) { !model.state.loadingOpenings }
        compose.runOnIdle { assertNull(model.state.error); assertTrue(model.state.openingResults.isNotEmpty()); model.openOpening(model.state.openingResults.first()) }
        compose.waitUntil(5_000) { model.state.screen == Screen.OPENING }
        compose.runOnIdle { assertEquals(-1, model.state.index); model.next(); assertEquals(0, model.state.index); assertNotNull(model.state.position.lastMove) }
        snapshot("opening")
    }
    @Test fun savedSessionRestoresWithImmediateLoadingFeedback() {
        val application = compose.activity.application
        compose.runOnIdle { model.startAi("1800", "white", .1); model.move("e2e4") }
        compose.waitUntil(60_000) { model.state.history.size == 2 || model.state.error != null }
        lateinit var savedPgn: String
        lateinit var lastMove: String
        compose.runOnIdle { assertNull(model.state.error); savedPgn = model.exportPgn(); lastMove = model.state.history.last().uci }
        val store = GameStore(application)
        compose.waitUntil(10_000) {
            store.read("session")?.let { org.json.JSONObject(it).optString("pgn") == savedPgn } == true
        }
        lateinit var restored: ChessModel
        compose.runOnIdle { restored = ChessModel(application) }
        compose.waitUntil(10_000) { restored.state.resumable }
        compose.runOnIdle { restored.resumeGame(); assertTrue(restored.state.resuming) }
        compose.waitUntil(30_000) { !restored.state.resuming }
        compose.runOnIdle {
            assertNull(restored.state.error)
            assertEquals(Screen.AI_GAME, restored.state.screen)
            assertEquals(2, restored.state.history.size)
            assertEquals(lastMove, restored.state.position.lastMove)
            assertTrue(restored.state.feedback.isEmpty())
        }
    }
    @Test fun oldAnalysisCacheRefreshesWithoutLosingImportedGameOrVariation() {
        compose.runOnIdle { model.analyzePgn("[WhiteElo \"523\"]\n[BlackElo \"831\"]\n\n1. e4 e5 2. Nf3 Nc6 *") }
        compose.waitUntil(120_000) { model.state.screen == Screen.SUMMARY || model.state.error != null }
        lateinit var original: String
        compose.runOnIdle {
            assertNull(model.state.error)
            original = model.exportPgn()
            model.beginReview(); model.select(1); model.move("f1c4")
        }
        compose.waitUntil(120_000) { model.state.queued == 0 || model.state.moveErrors.isNotEmpty() }
        val store = GameStore(compose.activity.application)
        compose.waitUntil(10_000) {
            store.read("session")?.let {
                org.json.JSONObject(it).optJSONObject("variation")?.optJSONArray("feedback")?.optJSONObject(0)?.optString("uci") == "f1c4"
            } == true
        }
        val saved = org.json.JSONObject(store.read("session")!!).put("analysis_revision", "previous-snapshot")
        fun markStale(rows: org.json.JSONArray?) {
            if (rows != null) for (i in 0 until rows.length()) rows.optJSONObject(i)?.getJSONObject("classification")?.put("classification", "stale")
        }
        markStale(saved.optJSONArray("feedback"))
        markStale(saved.getJSONObject("variation").optJSONArray("feedback"))
        store.write("session", saved.toString())
        lateinit var restored: ChessModel
        compose.runOnIdle { restored = ChessModel(compose.activity.application) }
        compose.waitUntil(10_000) { restored.state.resumable }
        compose.runOnIdle { restored.resumeGame() }
        compose.waitUntil(120_000) {
            restored.state.error != null || (restored.state.screen == Screen.REVIEW && restored.state.analysis != null && restored.state.queued == 0)
        }
        compose.runOnIdle {
            assertNull(restored.state.error)
            assertTrue(restored.state.moveErrors.toString(), restored.state.moveErrors.isEmpty())
            assertEquals(1, restored.state.variationRoot)
            assertEquals(2, restored.state.index)
            assertEquals(original, restored.exportPgn())
            assertEquals("f1c4", restored.currentFeedback()?.getString("uci"))
            assertFalse(restored.state.feedback.values.any { it.getJSONObject("classification").getString("classification") == "stale" })
            restored.resumeOriginal()
            restored.cancelAnalysis(); restored.clearError()
            model.resumeOriginal()
        }
    }
}
