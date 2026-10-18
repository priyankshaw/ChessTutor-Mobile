package com.chesstutor.nativeapp

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp

internal data class EvaluationReading(val whiteShare: Float, val label: String)

internal fun evaluationReading(eval: JSONObject?, fen: String, mover: String?): EvaluationReading {
    fun number(key: String): Double? = if (eval == null || eval.isNull(key)) null else eval.optDouble(key).takeIf { it.isFinite() }
    val cp = number("white_cp") ?: number("cp")?.times(if (mover == "black") -1 else 1)
    val mate = number("white_mate") ?: number("mate")?.times(if (mover == "black") -1 else 1)
    if (mate != null) {
        val whiteWins = if (mate == 0.0) fen.split(' ').getOrNull(1) == "b" else mate > 0
        val share = if (mate == 0.0) { if (whiteWins) .985 else .015 }
            else if (whiteWins) (.985 - minOf(6.0, abs(mate)) * .002).coerceAtLeast(.97)
            else (.015 + minOf(6.0, abs(mate)) * .002).coerceAtMost(.03)
        return EvaluationReading(share.toFloat(), "${if (whiteWins) "" else "−"}M${abs(mate).toInt()}")
    }
    val supplied = number("white_win_percent")
    if (cp == null && supplied == null) return EvaluationReading(.5f, "Pending")
    val share = ((supplied ?: (100.0 / (1 + exp(-.00368208 * (cp ?: 0.0))))).coerceIn(3.0, 97.0) / 100).toFloat()
    val pawns = if (abs(cp ?: 0.0) < 5) 0.0 else cp!! / 100
    return EvaluationReading(share, if (pawns == 0.0) "0.0" else String.format(Locale.US, "%+.1f", pawns))
}

@Composable internal fun EvaluationBar(eval: JSONObject?, fen: String, mover: String?, flipped: Boolean, height: Dp) {
    val reading = evaluationReading(eval, fen, mover)
    val whiteShare by animateFloatAsState(reading.whiteShare, tween(300), label = "Evaluation")
    Canvas(Modifier.width(16.dp).height(height).semantics { contentDescription = "Evaluation: ${reading.label}, from White's perspective" }) {
        drawRect(Color(0xff34312d))
        val whiteHeight = size.height * whiteShare
        drawRect(Color(0xffeeeeD2), if (flipped) Offset.Zero else Offset(0f, size.height - whiteHeight), Size(size.width, whiteHeight))
        drawLine(Color.Gray.copy(alpha = .45f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.dp.toPx())
    }
}
