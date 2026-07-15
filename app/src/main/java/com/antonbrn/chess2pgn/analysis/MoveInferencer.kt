package com.antonbrn.chess2pgn.analysis

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move
import com.github.bhlangonijr.chesslib.move.MoveGenerator
import com.github.bhlangonijr.chesslib.move.MoveList
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * On ne reconnaît jamais les pièces : on part de la position initiale et,
 * à chaque changement observé sur l'échiquier, on cherche le coup légal
 * dont l'effet correspond le mieux aux cases modifiées.
 */
class MoveInferencer {

    companion object {
        const val SINGLE_THRESHOLD = 0.55
        const val PAIR_THRESHOLD = 0.55
    }

    private val board = Board()
    private val played = MoveList()

    var moveCount = 0
        private set

    private fun occupancy(): IntArray = IntArray(64) { i ->
        val p = board.getPiece(Square.squareAt(i))
        when {
            p == Piece.NONE -> 0
            p.pieceSide == Side.WHITE -> 1
            else -> 2
        }
    }

    private fun diff(a: IntArray, b: IntArray): Set<Int> {
        val s = HashSet<Int>()
        for (i in 0 until 64) if (a[i] != b[i]) s.add(i)
        return s
    }

    private fun jaccard(a: Set<Int>, b: Set<Int>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val inter = a.count { it in b }
        return inter.toDouble() / (a.size + b.size - inter)
    }

    // léger biais pour préférer la promotion dame quand l'image ne peut pas trancher
    private fun promoBias(m: Move): Double {
        val p = m.promotion ?: return 0.0
        if (p == Piece.NONE) return 0.0
        return if (p.pieceType == PieceType.QUEEN) 0.001 else 0.0
    }

    /**
     * Tente d'expliquer les cases modifiées par un coup légal, ou par deux
     * coups consécutifs si un état intermédiaire a été raté.
     * Retourne le nombre de coups appliqués (0 si rien de convaincant).
     */
    fun inferAndApply(changed: Set<Int>): Int {
        val before = occupancy()

        var bestMove: Move? = null
        var bestScore = 0.0
        for (m in MoveGenerator.generateLegalMoves(board)) {
            board.doMove(m)
            val score = jaccard(diff(before, occupancy()), changed) + promoBias(m)
            board.undoMove()
            if (score > bestScore) {
                bestScore = score
                bestMove = m
            }
        }
        if (bestMove != null && bestScore >= SINGLE_THRESHOLD) {
            apply(bestMove)
            return 1
        }

        var bestPair: Pair<Move, Move>? = null
        var bestPairScore = 0.0
        for (m1 in MoveGenerator.generateLegalMoves(board)) {
            board.doMove(m1)
            for (m2 in MoveGenerator.generateLegalMoves(board)) {
                board.doMove(m2)
                val score = jaccard(diff(before, occupancy()), changed) + promoBias(m1) + promoBias(m2)
                board.undoMove()
                if (score > bestPairScore) {
                    bestPairScore = score
                    bestPair = m1 to m2
                }
            }
            board.undoMove()
        }
        if (bestPair != null && bestPairScore >= PAIR_THRESHOLD) {
            apply(bestPair.first)
            apply(bestPair.second)
            return 2
        }

        return 0
    }

    private fun apply(m: Move) {
        board.doMove(m)
        played.add(m)
        moveCount++
    }

    fun lastMovesSan(count: Int): String {
        val sans = sanArray()
        return sans.takeLast(count).joinToString(" ")
    }

    private fun sanArray(): Array<String> = try {
        played.toSanArray()
    } catch (e: Exception) {
        played.map { it.toString() }.toTypedArray()
    }

    fun pgn(): String {
        val date = SimpleDateFormat("yyyy.MM.dd", Locale.US).format(Date())
        val sb = StringBuilder()
        sb.append("[Event \"?\"]\n")
        sb.append("[Site \"?\"]\n")
        sb.append("[Date \"$date\"]\n")
        sb.append("[Round \"?\"]\n")
        sb.append("[White \"?\"]\n")
        sb.append("[Black \"?\"]\n")
        sb.append("[Result \"*\"]\n\n")

        val sans = sanArray()
        val line = StringBuilder()
        for (i in sans.indices) {
            val token = if (i % 2 == 0) "${i / 2 + 1}. ${sans[i]}" else sans[i]
            if (line.length + token.length + 1 > 80) {
                sb.append(line.trim()).append('\n')
                line.clear()
            }
            line.append(token).append(' ')
        }
        line.append('*')
        sb.append(line.trim()).append('\n')
        return sb.toString()
    }
}
