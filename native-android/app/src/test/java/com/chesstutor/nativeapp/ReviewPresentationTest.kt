package com.chesstutor.nativeapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReviewPresentationTest {
    @Test fun namesKeepCaptureDisambiguationPromotionAndCheckMeaning() {
        assertEquals("Knight from b to d2", moveInWords("Nbd2"))
        assertEquals("e-pawn captures on d5", moveInWords("exd5", capitalize = false))
        assertEquals("Pawn to e8, promotes to queen, checkmate", moveInWords("e8=Q#"))
        assertEquals("Kingside castling, with check", moveInWords("O-O+"))
        assertEquals("Black's knight captures on f3, with check", moveInWords("...Nxf3+"))
    }
    @Test fun proseExpandsMovesWithoutTurningSquaresIntoMoves() {
        assertEquals("Controls c5 and e5.", explanationInWords("Controls c5 and e5.", true))
        assertEquals("e4 is weak.", explanationInWords("e4 is weak.", true))
        assertEquals("Moving the knight to f3 defends e5.", explanationInWords("Nf3 defends e5.", true))
        assertEquals("Prepares a pawn move to e4.", explanationInWords("Prepares e4.", true))
        assertEquals("Nf3 develops a piece.", explanationInWords("Nf3 develops a piece.", false))
    }
    @Test fun fullExplanationSettingSelectsExistingFieldsAndHandlesNullFallback() {
        val data = JSONObject().put("why_played", "Short played").put("complete_why_played", "Full played")
            .put("why_best", "Short best").put("complete_why_best", "Full best")
        assertEquals("Short played", coachExplanation(data, best = false, complete = false))
        assertEquals("Full played", coachExplanation(data, best = false, complete = true))
        assertEquals("Full best", coachExplanation(data, best = true, complete = true))
        data.put("complete_why_best", JSONObject.NULL)
        assertEquals("Short best", coachExplanation(data, best = true, complete = true))
    }
}
