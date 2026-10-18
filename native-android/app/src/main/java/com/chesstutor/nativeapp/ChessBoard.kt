package com.chesstutor.nativeapp

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.ArrowRightAlt
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlin.math.*

private val resources = mapOf('K' to R.drawable.wk, 'Q' to R.drawable.wq, 'R' to R.drawable.wr,
    'B' to R.drawable.wb, 'N' to R.drawable.wn, 'P' to R.drawable.wp,
    'k' to R.drawable.bk, 'q' to R.drawable.bq, 'r' to R.drawable.br,
    'b' to R.drawable.bb, 'n' to R.drawable.bn, 'p' to R.drawable.bp)
fun pieceResource(symbol: Char) = resources.getValue(symbol)
private fun pieceName(symbol: Char): String = when (symbol.lowercaseChar()) {
    'k' -> "king"; 'q' -> "queen"; 'r' -> "rook"; 'b' -> "bishop"; 'n' -> "knight"; else -> "pawn"
}
data class Grade(val name: String, val symbol: String, val color: Color)
val grades = linkedMapOf(
    "brilliant" to Grade("Brilliant", "!!", Color(0xff1baca6)),
    "great" to Grade("Great Move", "!", Color(0xff5c8bb0)),
    "best" to Grade("Best Move", "★", Color(0xff81b64c)),
    "excellent" to Grade("Excellent", "👍", Color(0xff96bc4b)),
    "good" to Grade("Good", "✓", Color(0xff96bc4b)),
    "book" to Grade("Book Move", "▣", Color(0xffd5a47e)),
    "forced" to Grade("Forced", "➜", Color(0xff6b7280)),
    "inaccuracy" to Grade("Inaccuracy", "?!", Color(0xfff0c15c)),
    "mistake" to Grade("Mistake", "?", Color(0xffe58f2a)),
    "miss" to Grade("Miss", "×", Color(0xffee6655)),
    "blunder" to Grade("Blunder", "??", Color(0xffca3431))
)

@Composable fun GradeBadge(type: String, size: Dp = 22.dp) {
    val grade = grades[type] ?: grades.getValue("good")
    Box(Modifier.size(size).background(grade.color, CircleShape).semantics { contentDescription = grade.name },
        contentAlignment = androidx.compose.ui.Alignment.Center) {
        val icon = when (type) {
            "best" -> Icons.Outlined.Star
            "excellent" -> Icons.Outlined.ThumbUp
            "good" -> Icons.Outlined.Check
            "book" -> Icons.AutoMirrored.Outlined.MenuBook
            "forced" -> Icons.AutoMirrored.Outlined.ArrowRightAlt
            "miss" -> Icons.Outlined.Close
            else -> null
        }
        if (icon != null) Icon(icon, null, tint = Color.White, modifier = Modifier.size(size * .66f))
        else Text(grade.symbol, color = Color.White, fontWeight = FontWeight.Black, fontSize = (size.value * .6f).sp, maxLines = 1)
    }
}

@Composable fun ChessBoard(position: BoardPosition, size: Dp, flipped: Boolean, enabled: Boolean,
    theme: String, grade: String? = null, arrow: String? = null, onMove: (String) -> Unit) {
    val cell = size / 8
    val density = LocalDensity.current
    val cellPx = with(density) { cell.toPx() }
    val stamp = "${position.fen}:$flipped"
    var selection by remember { mutableStateOf<Pair<String, Int>?>(null) }
    val selected = selection?.takeIf { it.first == stamp && enabled }?.second
    var promotion by remember(position.fen) { mutableStateOf<List<String>>(emptyList()) }
    var dragId by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var droppedId by remember { mutableStateOf<Int?>(null) }
    val pieces = remember(position.pieces) { position.pieces.associateBy { it.square } }
    val legal = remember(position.legal) { position.legal.groupBy { ChessGame.squareIndex(it.take(2)) } }
    fun column(square: Int) = if (flipped) 7 - square % 8 else square % 8
    fun row(square: Int) = if (flipped) square / 8 else 7 - square / 8
    fun at(point: Offset): Int? {
        val col = floor(point.x / cellPx).toInt()
        val rank = floor(point.y / cellPx).toInt()
        if (col !in 0..7 || rank !in 0..7) return null
        return if (flipped) rank * 8 + 7 - col else (7 - rank) * 8 + col
    }
    fun attempt(from: Int, to: Int): Boolean {
        val choices = legal[from].orEmpty().filter { ChessGame.squareIndex(it.substring(2, 4)) == to }
        if (choices.isEmpty()) return false
        if (choices.size > 1) promotion = choices else onMove(choices.first())
        return true
    }
    fun tap(square: Int) {
        if (!enabled) return
        val from = selection?.takeIf { it.first == stamp }?.second
        if (from != null && attempt(from, square)) { selection = null; return }
        selection = if (square in legal && square != from) stamp to square else null
    }
    // Lambdas capture fresh position; local function references can compare equal.
    val currentTap by rememberUpdatedState<(Int) -> Unit>({ square -> tap(square) })
    val currentAttempt by rememberUpdatedState<(Int, Int) -> Boolean>({ from, to -> attempt(from, to) })
    val currentLegal by rememberUpdatedState(legal)
    val currentPieces by rememberUpdatedState(pieces)
    val currentStamp by rememberUpdatedState(stamp)
    val light = when (theme) { "Wood" -> Color(0xffebd5ad); "Ocean" -> Color(0xffdee8ed); else -> Color(0xffeeeed2) }
    val dark = when (theme) { "Wood" -> Color(0xffa87b50); "Ocean" -> Color(0xff648da4); else -> Color(0xff769656) }
    val destinations = remember(selected, position.legal) { selected?.let { legal[it] }.orEmpty().map { ChessGame.squareIndex(it.substring(2, 4)) }.toSet() }
    val currentMove = position.lastMove
    Box(Modifier.size(size).clip(RoundedCornerShape(4.dp))
        .pointerInput(enabled, flipped, cellPx) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val origin = at(down.position)
                val originStamp = currentStamp
                val movable = enabled && origin != null && origin in currentLegal
                val reached = awaitTouchSlopOrCancellation(down.id) { change, _ -> if (movable) change.consume() }
                if (reached == null) {
                    val up = currentEvent.changes.firstOrNull { it.id == down.id }
                    if (enabled && origin != null && up != null && !up.pressed && !up.isConsumed) currentTap(origin)
                } else if (movable) {
                    try {
                        selection = originStamp to origin
                        dragId = currentPieces[origin]?.id
                        dragOffset = reached.position - Offset(cellPx / 2, cellPx / 2)
                        val released = drag(reached.id) { change -> change.consume(); dragOffset = change.position - Offset(cellPx / 2, cellPx / 2) }
                        val to = at(dragOffset + Offset(cellPx / 2, cellPx / 2))
                        if (released && to != null && originStamp == currentStamp) { droppedId = dragId; currentAttempt(origin, to) }
                    } finally { dragId = null }
                }
            }
        }) {
        BoardSquares(light, dark, flipped, cell)
        Canvas(Modifier.fillMaxSize()) {
            currentMove?.let { uci ->
                for (square in listOf(ChessGame.squareIndex(uci.take(2)), ChessGame.squareIndex(uci.substring(2, 4))))
                    drawRect(Color(0xfff6e643).copy(alpha = .67f), Offset(column(square) * cellPx, row(square) * cellPx), Size(cellPx, cellPx))
            }
            position.checkedKing?.let { drawCircle(Color.Red.copy(alpha = .46f), cellPx * .48f, Offset((column(it) + .5f) * cellPx, (row(it) + .5f) * cellPx)) }
            selected?.let { drawRect(Color(0xfff6e643).copy(alpha = .75f), Offset(column(it) * cellPx, row(it) * cellPx), Size(cellPx, cellPx)) }
            for (square in destinations) {
                val center = Offset((column(square) + .5f) * cellPx, (row(square) + .5f) * cellPx)
                if (pieces.containsKey(square)) drawCircle(Color.Black.copy(alpha = .2f), cellPx * .45f, center, style = androidx.compose.ui.graphics.drawscope.Stroke(cellPx * .09f))
                else drawCircle(Color.Black.copy(alpha = .18f), cellPx * .13f, center)
            }
        }
        for (piece in position.pieces) key(piece.id) {
            val x by animateFloatAsState(column(piece.square) * cellPx, tween(if (piece.id == droppedId) 0 else 140), label = "piece x")
            val y by animateFloatAsState(row(piece.square) * cellPx, tween(if (piece.id == droppedId) 0 else 140), label = "piece y")
            Image(painterResource(pieceResource(piece.symbol)), contentDescription = null,
                modifier = Modifier.size(cell).graphicsLayer {
                    translationX = if (piece.id == dragId) dragOffset.x else x
                    translationY = if (piece.id == dragId) dragOffset.y else y
                    shadowElevation = if (piece.id == dragId) 8.dp.toPx() else 0f
                    scaleX = if (piece.id == dragId) 1.12f else 1f; scaleY = scaleX
                }.then(if (piece.id == dragId) Modifier.zIndex(2f) else Modifier))
        }
        if (arrow != null && arrow.length >= 4) Canvas(Modifier.fillMaxSize()) {
            val from = ChessGame.squareIndex(arrow.take(2))
            val to = ChessGame.squareIndex(arrow.substring(2, 4))
            val start = Offset((column(from) + .5f) * cellPx, (row(from) + .5f) * cellPx)
            val end = Offset((column(to) + .5f) * cellPx, (row(to) + .5f) * cellPx)
            val direction = end - start
            val length = direction.getDistance()
            if (length > 0) {
                val unit = direction / length
                val perpendicular = Offset(-unit.y, unit.x)
                val base = end - unit * cellPx * .47f
                drawLine(Color(0xff81b64c).copy(alpha = .78f), start, base, cellPx * .16f)
                drawPath(Path().apply { moveTo(end.x, end.y); lineTo((base + perpendicular * cellPx * .25f).x, (base + perpendicular * cellPx * .25f).y); lineTo((base - perpendicular * cellPx * .25f).x, (base - perpendicular * cellPx * .25f).y); close() }, Color(0xff81b64c).copy(alpha = .9f))
            }
        }
        for (square in 0..63) {
            val piece = pieces[square]
            val label = "${ChessGame.squareName(square)}${piece?.let { ", ${if (it.symbol.isUpperCase()) "White" else "Black"} ${pieceName(it.symbol)}" } ?: ", empty"}"
            Box(Modifier.offset(cell * column(square), cell * row(square)).size(cell).semantics {
                contentDescription = label; role = Role.Button
                if (enabled) onClick("Select ${ChessGame.squareName(square)}") { tap(square); true }
            })
        }
        if (grade != null && currentMove != null) {
            val square = ChessGame.squareIndex(currentMove.substring(2, 4))
            Box(Modifier.offset(cell * column(square) + cell * .62f, cell * row(square) + 1.dp)) { GradeBadge(grade, cell * .38f) }
        }
    }
    if (promotion.isNotEmpty()) AlertDialog(onDismissRequest = { promotion = emptyList() },
        title = { Text("Choose promotion") }, text = { Row {
            for (uci in promotion) TextButton(onClick = { promotion = emptyList(); onMove(uci) }) {
                val symbol = if (position.fen.split(' ')[1] == "w") uci.last().uppercaseChar() else uci.last()
                Image(painterResource(pieceResource(symbol)), contentDescription = "Promote to ${pieceName(symbol)}", Modifier.size(52.dp))
            }
        } }, confirmButton = {})
}

@Composable private fun BoardSquares(light: Color, dark: Color, flipped: Boolean, cell: Dp) {
    Canvas(Modifier.fillMaxSize()) {
        val side = size.width / 8
        for (row in 0..7) for (column in 0..7) drawRect(if ((row + column) % 2 == 0) light else dark,
            Offset(column * side, row * side), Size(side, side))
    }
    for (index in 0..7) {
        Text(if (flipped) "${index + 1}" else "${8 - index}", color = if (index % 2 == 0) dark else light,
            fontWeight = FontWeight.Bold, fontSize = 9.sp, modifier = Modifier.offset(3.dp, cell * index + 1.dp))
        Text("${if (flipped) 'h' - index else 'a' + index}", color = if (index % 2 == 0) light else dark,
            fontWeight = FontWeight.Bold, fontSize = 9.sp, modifier = Modifier.offset(cell * index + cell - 8.dp, cell * 8 - 13.dp))
    }
}
