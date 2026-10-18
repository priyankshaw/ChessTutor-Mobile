package com.chesstutor.nativeapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EvaluationBarTest {
    private val fenWhite = "8/8/8/8/8/8/8/8 w - - 0 1"
    private val fenBlack = fenWhite.replace(" w ", " b ")
    @Test fun whitePerspectiveUsesBackendProbabilityAndClampsExtremeScores() {
        val score = evaluationReading(JSONObject().put("white_cp", -120).put("white_win_percent", 38), fenWhite, "white")
        assertEquals(.38f, score.whiteShare, .0001f)
        assertEquals("-1.2", score.label)
        assertEquals(.97f, evaluationReading(JSONObject().put("white_cp", 100000), fenWhite, "black").whiteShare, .0001f)
        assertTrue(evaluationReading(JSONObject().put("cp", 100), fenWhite, "black").whiteShare < .5f)
    }
    @Test fun mateSignAndTerminalWinnerArePreserved() {
        assertEquals("M3", evaluationReading(JSONObject().put("white_mate", 3), fenWhite, "white").label)
        assertEquals("−M2", evaluationReading(JSONObject().put("white_mate", -2), fenWhite, "white").label)
        assertEquals("M0", evaluationReading(JSONObject().put("white_mate", 0), fenBlack, "black").label)
        assertEquals("−M0", evaluationReading(JSONObject().put("white_mate", 0), fenWhite, "white").label)
    }
    @Test fun missingEvaluationRemainsPendingRatherThanUsingAnotherMove() {
        assertEquals(EvaluationReading(.5f, "Pending"), evaluationReading(null, fenWhite, "white"))
        assertEquals(EvaluationReading(.5f, "Pending"), evaluationReading(JSONObject().put("white_cp", "invalid"), fenWhite, "white"))
    }
}
