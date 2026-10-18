package com.chesstutor.nativeapp

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Constants
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move
import com.github.bhlangonijr.chesslib.move.MoveList
import androidx.compose.runtime.Immutable
import java.util.concurrent.atomic.AtomicLong

@Immutable data class BoardPiece(val id: Int, val symbol: Char, val square: Int)
data class Ply(val id: Long, val uci: String, val san: String, val before: String,
               val after: String, val pieces: List<BoardPiece>, val capture: Boolean)
@Immutable data class BoardPosition(val fen: String, val pieces: List<BoardPiece>, val legal: List<String>,
                         val lastMove: String? = null, val checkedKing: Int? = null)

/** Only this game's owner mutates Board. Rendering receives immutable snapshots. */
class ChessGame(val startFen: String = Constants.startStandardFENPosition,
                val headers: Map<String, String> = mapOf("White" to "Live White", "Black" to "Live Black"),
                seedPieces: List<BoardPiece>? = null) {
    private val board = Board().apply { loadFromFen(startFen) }
    private val initialPieces = seedPieces ?: piecesFromFen(startFen)
    private var pieces = initialPieces
    private var moveIds = AtomicLong()
    val plies = mutableListOf<Ply>()
    var result = "*"
    val turn: String get() = if (board.sideToMove == Side.WHITE) "white" else "black"
    val fen: String get() = board.fen
    val isOver: Boolean get() = result != "*" || board.isMated || board.isDraw

    fun position(index: Int = plies.lastIndex, editable: Boolean = false): BoardPosition {
        val ply = plies.getOrNull(index)
        val currentFen = ply?.after ?: startFen
        val current = if (index == plies.lastIndex) board else boardAt(index)
        return BoardPosition(currentFen, ply?.pieces ?: initialPieces,
            if (if (editable) !current.isMated && !current.isDraw else index == plies.lastIndex && !isOver)
                current.legalMoves().map { it.toString() } else emptyList(),
            ply?.uci, if (current.isKingAttacked) current.getKingSquare(current.sideToMove).ordinal else null)
    }

    fun play(uci: String, importing: Boolean = false): Ply {
        require(uci.matches(Regex("[a-h][1-8][a-h][1-8][qrbn]?"))) { "Invalid move." }
        val move = Move(uci, board.sideToMove)
        require((importing || !isOver) && move in board.legalMoves()) { "Illegal move." }
        val before = board.fen
        val san = MoveList(before).apply { add(move) }.toSanArray()[0]
        val moving = pieces.first { it.square == move.from.ordinal }
        val enPassant = moving.symbol.lowercaseChar() == 'p' && move.from.file != move.to.file && board.getPiece(move.to) == Piece.NONE
        val capturedSquare = if (enPassant) move.to.ordinal + if (board.sideToMove == Side.WHITE) -8 else 8 else move.to.ordinal
        val capture = pieces.any { it.square == capturedSquare }
        val castling = moving.symbol.lowercaseChar() == 'k' && kotlin.math.abs(move.to.ordinal - move.from.ordinal) == 2
        pieces = pieces.filter { it.square != capturedSquare }.map {
            when {
                it.id == moving.id -> it.copy(square = move.to.ordinal,
                    symbol = if (move.promotion == Piece.NONE) it.symbol else move.promotion.fenSymbol.single())
                castling && it.square == if (move.to.ordinal > move.from.ordinal) move.from.ordinal + 3 else move.from.ordinal - 4 ->
                    it.copy(square = if (move.to.ordinal > move.from.ordinal) move.from.ordinal + 1 else move.from.ordinal - 1)
                else -> it
            }
        }
        check(board.doMove(move, true))
        val ply = Ply(moveIds.incrementAndGet(), uci, san, before, board.fen, pieces, capture)
        plies += ply
        if (board.isMated) result = if (board.sideToMove == Side.WHITE) "0-1" else "1-0"
        else if (board.isDraw) result = "1/2-1/2"
        return ply
    }

    fun undo(): Ply? {
        if (plies.isEmpty()) return null
        val removed = plies.last()
        truncateAfter(plies.lastIndex - 1)
        return removed
    }

    private fun boardAt(index: Int): Board = Board().apply {
        loadFromFen(plies.getOrNull(index)?.after ?: startFen)
        // FEN alone loses repetition history, including draws spanning the branch point.
        history.clear()
        history.addAll(board.history.take(index + 2))
    }

    fun truncateAfter(index: Int) {
        require(index in -1..plies.lastIndex) { "Invalid move index." }
        val previous = boardAt(index)
        board.loadFromFen(previous.fen)
        board.history.clear()
        board.history.addAll(previous.history)
        plies.subList(index + 1, plies.size).clear()
        pieces = plies.lastOrNull()?.pieces ?: initialPieces
        result = when { board.isMated -> if (turn == "white") "0-1" else "1-0"; board.isDraw -> "1/2-1/2"; else -> "*" }
    }

    fun fork(index: Int): ChessGame {
        require(index in -1..plies.lastIndex) { "Invalid branch point." }
        val previous = boardAt(index)
        return ChessGame(startFen, headers, initialPieces).also { copy ->
            copy.board.loadFromFen(previous.fen)
            copy.board.history.clear()
            copy.board.history.addAll(previous.history)
            copy.plies.addAll(plies.take(index + 1))
            copy.pieces = copy.plies.lastOrNull()?.pieces ?: initialPieces
            copy.moveIds = moveIds
            copy.result = when { copy.board.isMated -> if (copy.turn == "white") "0-1" else "1-0"; copy.board.isDraw -> "1/2-1/2"; else -> "*" }
        }
    }

    fun pgn(): String {
        val allHeaders = headers + mapOf("Result" to result) + if (startFen != Constants.startStandardFENPosition)
            mapOf("SetUp" to "1", "FEN" to startFen) else emptyMap()
        val start = startFen.split(' ')
        val number = start.last().toIntOrNull() ?: 1
        val blackFirst = start.getOrNull(1) == "b"
        val moves = plies.mapIndexed { index, ply ->
            val half = index + if (blackFirst) 1 else 0
            when {
                half % 2 == 0 -> "${number + half / 2}. ${ply.san}"
                index == 0 -> "$number... ${ply.san}"
                else -> ply.san
            }
        }.joinToString(" ")
        return allHeaders.entries.joinToString("\n") { (key, value) -> "[$key \"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\"]" } + "\n\n$moves $result"
    }

    companion object {
        fun piecesFromFen(fen: String): List<BoardPiece> {
            val result = mutableListOf<BoardPiece>()
            var rank = 7
            var file = 0
            for (symbol in fen.substringBefore(' ')) {
                when {
                    symbol == '/' -> { rank--; file = 0 }
                    symbol.isDigit() -> file += symbol.digitToInt()
                    else -> { result += BoardPiece(result.size, symbol, rank * 8 + file); file++ }
                }
            }
            return result
        }
        fun squareName(square: Int) = "${('a'.code + square % 8).toChar()}${square / 8 + 1}"
        fun squareIndex(square: String) = (square[1] - '1') * 8 + (square[0] - 'a')
        fun preview(fen: String, uci: String, seedPieces: List<BoardPiece>? = null): BoardPosition {
            val game = ChessGame(fen, seedPieces = seedPieces)
            game.play(uci)
            return game.position()
        }
    }
}
