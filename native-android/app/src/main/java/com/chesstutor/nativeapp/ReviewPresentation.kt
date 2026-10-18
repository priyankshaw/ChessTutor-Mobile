package com.chesstutor.nativeapp

import org.json.JSONObject

internal fun coachExplanation(explanation: JSONObject?, best: Boolean, complete: Boolean): String? {
    val key = if (best) "why_best" else "why_played"
    fun value(name: String) = if (explanation == null || explanation.isNull(name)) null else explanation.optString(name).takeIf { it.isNotBlank() }
    return (if (complete) value("complete_$key") else null) ?: value(key)
}

private val pieceNames = mapOf("K" to "king", "Q" to "queen", "R" to "rook", "B" to "bishop", "N" to "knight")
private val sanMove = Regex("""^(\.\.\.)?(O-O(?:-O)?|([KQRBN])?([a-h][1-8]|[a-h]|[1-8])?(x)?([a-h][1-8])(?:=([QRBN]))?)([+#])?$""")
private val moveTokens = Regex("""(?<![\w-])(?:\.\.\.)?(?:O-O(?:-O)?|[KQRBN](?:[a-h][1-8]|[a-h]|[1-8])?x?[a-h][1-8]|[a-h]x[a-h][1-8]|[a-h][1-8])(?:=[QRBN])?[+#]?(?![\w-])""")

internal fun moveInWords(san: String, capitalize: Boolean = true): String {
    val match = sanMove.matchEntire(san) ?: return san
    val (black, notation, piece, origin, capture, destination, promotion, check) = match.destructured
    var text = if (notation.startsWith("O-O")) {
        if (notation == "O-O-O") "queenside castling" else "kingside castling"
    } else {
        val name = pieceNames[piece] ?: if (origin.isNotEmpty() && capture.isNotEmpty()) "$origin-pawn" else "pawn"
        name + (if (piece.isNotEmpty() && origin.isNotEmpty()) " from $origin" else "") +
            (if (capture.isNotEmpty()) " captures on $destination" else " to $destination") +
            (if (promotion.isNotEmpty()) ", promotes to ${pieceNames[promotion]}" else "")
    }
    if (black.isNotEmpty()) text = "Black's $text"
    text += when (check) { "#" -> ", checkmate"; "+" -> ", with check"; else -> "" }
    return if (capitalize) text.replaceFirstChar { it.uppercase() } else text
}

/** Presentation only: follow desktop wording while preserving references to squares. */
internal fun explanationInWords(text: String, enabled: Boolean): String {
    if (!enabled) return text
    var previousMoveEnd = -1
    return moveTokens.replace(text) { match ->
        val token = match.value
        val before = text.substring(0, match.range.first)
        val after = text.substring(match.range.last + 1)
        val sentenceStart = before.isBlank() || Regex("""[.!?]\s*$""").containsMatchIn(before)
        if (Regex("[a-h][1-8]").matches(token)) {
            val start = before.isBlank() || Regex("""[.!?;:]\s*$""").containsMatchIn(before)
            val context = Regex("""\b(supports|prepares|play|plays|playing|reply|move|break|allows|prevents|opponent|after|before|with|via|for)\s*$""", RegexOption.IGNORE_CASE).containsMatchIn(before)
            val line = previousMoveEnd >= 0 && text.substring(previousMoveEnd, match.range.first).isNotEmpty() && text.substring(previousMoveEnd, match.range.first).isBlank()
            val square = Regex("""^\s+(is|are|was|were|pawn|pawns|square|squares)\b""", RegexOption.IGNORE_CASE).containsMatchIn(after)
            if ((!start && !context && !line) || square) return@replace token
        }
        previousMoveEnd = match.range.last + 1
        val move = sanMove.matchEntire(token) ?: return@replace token
        val (black, notation, piece, origin, capture, destination, promotion, check) = move.destructured
        if (!notation.startsWith("O-O") && promotion.isEmpty()) {
            val name = pieceNames[piece] ?: if (origin.isNotEmpty() && capture.isNotEmpty()) "$origin-pawn" else "pawn"
            val owner = if (black.isNotEmpty()) "Black's $name" else "the $name"
            if (check.isEmpty() && Regex("""^\s+(attacks|defends|clears|keeps|removes|opens|develops|makes|controls|reinforces|avoids|adds|puts|takes|recovers|wins|trades|recaptures|completes|prepares|allows|leaves|offers|moves)\b""", RegexOption.IGNORE_CASE).containsMatchIn(after)) {
                val action = if (capture.isNotEmpty()) "capturing on $destination with $owner" else "moving $owner to $destination"
                return@replace if (sentenceStart) action.replaceFirstChar { it.uppercase() } else action
            }
            if (Regex("""(\ba future|\bwith|\bagainst|\banswer|\bmet by|\bsupports|\bprepares|\ballows|\ballowing)\s*$""", RegexOption.IGNORE_CASE).containsMatchIn(before)) {
                if (Regex("""\ba future\s*$""", RegexOption.IGNORE_CASE).containsMatchIn(before)) return@replace if (black.isNotEmpty()) "move of Black's $name to $destination" else "$name move to $destination"
                val action = when { capture.isNotEmpty() -> "$name capture on $destination"; check.isNotEmpty() -> "$name ${if (check == "#") "checkmate" else "check"} on $destination"; else -> "$name move to $destination" }
                return@replace if (black.isNotEmpty()) "Black's $action" else "a $action"
            }
        }
        moveInWords(token, sentenceStart)
    }
}
