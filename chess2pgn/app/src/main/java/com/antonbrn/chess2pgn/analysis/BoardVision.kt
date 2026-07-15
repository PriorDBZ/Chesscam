package com.antonbrn.chess2pgn.analysis

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Redresse la zone du plateau (4 coins -> carré) via une transformation
 * perspective, puis calcule des statistiques par case.
 */
class BoardWarper(corners: FloatArray) {

    companion object {
        const val SIZE = 320
        const val CELL = SIZE / 8
    }

    private val matrix = Matrix()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    init {
        val s = SIZE.toFloat()
        // ordre des coins : a8, h8, h1, a1 -> a8 en haut-gauche de l'image redressée
        val dst = floatArrayOf(0f, 0f, s, 0f, s, s, 0f, s)
        require(matrix.setPolyToPoly(corners, 0, dst, 0, 4)) {
            "Coins du plateau invalides"
        }
    }

    fun warp(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, matrix, paint)
        return out
    }
}

/**
 * Pour chaque case : couleur moyenne (r, g, b) et écart-type de luminance
 * sur la zone centrale. Index 0..63 avec a1 = 0, b1 = 1, ..., h8 = 63
 * (même convention que chesslib).
 */
object SquareStats {

    private const val MARGIN = 8
    private const val F = 4 // features par case : r, g, b, sd

    fun compute(warped: Bitmap): FloatArray {
        val size = BoardWarper.SIZE
        val cell = BoardWarper.CELL
        val pixels = IntArray(size * size)
        warped.getPixels(pixels, 0, size, 0, 0, size, size)

        val out = FloatArray(64 * F)
        for (row in 0 until 8) {         // row 0 = rangée 8 (haut de l'image)
            for (col in 0 until 8) {     // col 0 = colonne a
                var sr = 0.0; var sg = 0.0; var sb = 0.0
                var sl = 0.0; var sl2 = 0.0
                var n = 0
                val x0 = col * cell + MARGIN
                val y0 = row * cell + MARGIN
                val x1 = (col + 1) * cell - MARGIN
                val y1 = (row + 1) * cell - MARGIN
                for (y in y0 until y1) {
                    val base = y * size
                    for (x in x0 until x1) {
                        val p = pixels[base + x]
                        val r = (p shr 16) and 0xFF
                        val g = (p shr 8) and 0xFF
                        val b = p and 0xFF
                        val l = 0.299 * r + 0.587 * g + 0.114 * b
                        sr += r; sg += g; sb += b
                        sl += l; sl2 += l * l
                        n++
                    }
                }
                val rank = 7 - row
                val idx = (rank * 8 + col) * F
                out[idx] = (sr / n).toFloat()
                out[idx + 1] = (sg / n).toFloat()
                out[idx + 2] = (sb / n).toFloat()
                val mean = sl / n
                out[idx + 3] = sqrt((sl2 / n - mean * mean).coerceAtLeast(0.0)).toFloat()
            }
        }
        return out
    }

    /** Distance entre une même case dans deux états. */
    fun squareDiff(a: FloatArray, b: FloatArray, square: Int): Float {
        val i = square * F
        val dc = (abs(a[i] - b[i]) + abs(a[i + 1] - b[i + 1]) + abs(a[i + 2] - b[i + 2])) / 3f
        val ds = abs(a[i + 3] - b[i + 3])
        return dc + ds * 0.5f
    }

    /** Diff moyen sur tout le plateau, pour la détection de stabilité. */
    fun globalDiff(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (sq in 0 until 64) sum += squareDiff(a, b, sq)
        return sum / 64f
    }

    fun changedSquares(a: FloatArray, b: FloatArray, threshold: Float): Set<Int> {
        val s = HashSet<Int>()
        for (sq in 0 until 64) {
            if (squareDiff(a, b, sq) > threshold) s.add(sq)
        }
        return s
    }
}
