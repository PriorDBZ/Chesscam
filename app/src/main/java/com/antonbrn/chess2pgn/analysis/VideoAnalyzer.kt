package com.antonbrn.chess2pgn.analysis

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

class AnalysisResult(
    val pgn: String,
    val moveCount: Int,
    val warnings: List<String>
)

class VideoAnalyzer(
    private val context: Context,
    private val uri: Uri,
    private val corners: FloatArray,
    private val clockRect: FloatArray, // [left, top, right, bottom] en coords bitmap
    fps: Int = 2
) {

    companion object {
        const val MAX_DIM = 640

        // seuils de vision, à ajuster selon l'éclairage / le matériel si besoin
        const val STABLE_DIFF = 5f       // en dessous : le plateau est immobile
        const val STABLE_SECONDS = 0.5f  // durée d'immobilité requise avant capture
        const val CHANGE_THRESHOLD = 20f // au-dessus : la case a changé d'état
        const val MIN_CHANGED = 2        // un coup modifie au moins 2 cases
        const val CLOCK_DIFF = 14f       // au-dessus : une main passe sur la pendule
    }

    private val fps = fps.coerceIn(1, 60)
    private val frameIntervalUs = 1_000_000L / this.fps
    private val stableFramesNeeded = (this.fps * STABLE_SECONDS).toInt().coerceAtLeast(2)

    suspend fun run(onProgress: (frame: Int, total: Int, moves: Int) -> Unit): AnalysisResult =
        withContext(Dispatchers.Default) {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            try {
                analyze(retriever, onProgress)
            } finally {
                retriever.release()
            }
        }

    private suspend fun analyze(
        retriever: MediaMetadataRetriever,
        onProgress: (Int, Int, Int) -> Unit
    ): AnalysisResult = withContext(Dispatchers.Default) {
        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull() ?: throw IllegalStateException("Durée de la vidéo illisible")
        val durationUs = durationMs * 1000
        val totalFrames = (durationUs / frameIntervalUs).toInt().coerceAtLeast(1)

        val warper = BoardWarper(corners)
        val inferencer = MoveInferencer()
        val warnings = mutableListOf<String>()

        // état plateau
        var prevStats: FloatArray? = null
        var committed: FloatArray? = null
        var stableRun = 0
        var failLogged = false

        // état pendule : un coup n'est validé qu'après un appui détecté
        var prevClockLuma: FloatArray? = null
        var clockBusyRun = 0
        var pendingMove = false
        var pressTimeUs = 0L

        var frameIdx = 0
        var t = 0L
        while (t < durationUs && isActive) {
            val bmp = retriever.getScaledFrameAtTime(
                t, MediaMetadataRetriever.OPTION_CLOSEST, MAX_DIM, MAX_DIM
            )
            if (bmp != null) {
                // --- pendule : pic de changement puis retour au calme = appui ---
                val clockLuma = clockLuma(bmp)
                val prevLuma = prevClockLuma
                if (prevLuma != null) {
                    val busy = meanAbsDiff(prevLuma, clockLuma) > CLOCK_DIFF
                    if (busy) {
                        clockBusyRun++
                    } else {
                        if (clockBusyRun >= 1) {
                            pendingMove = true
                            pressTimeUs = t
                        }
                        clockBusyRun = 0
                    }
                }
                prevClockLuma = clockLuma

                // --- plateau ---
                val warped = warper.warp(bmp)
                bmp.recycle()
                val stats = SquareStats.compute(warped)
                warped.recycle()

                val p = prevStats
                stableRun = if (p != null && SquareStats.globalDiff(p, stats) < STABLE_DIFF) {
                    stableRun + 1
                } else 0
                prevStats = stats

                if (stableRun >= stableFramesNeeded) {
                    if (committed == null) {
                        // première position stable = position de départ
                        committed = stats
                    } else if (pendingMove) {
                        val changed = SquareStats.changedSquares(committed!!, stats, CHANGE_THRESHOLD)
                        if (changed.size < MIN_CHANGED) {
                            // appui sans coup visible (lancement de la pendule, etc.)
                            pendingMove = false
                        } else {
                            val applied = inferencer.inferAndApply(changed)
                            if (applied > 0) {
                                committed = stats
                                pendingMove = false
                                failLogged = false
                            } else if (!failLogged) {
                                warnings.add(
                                    "Appui pendule à ${fmt(pressTimeUs)} mais coup inexpliqué " +
                                        "(${changed.size} cases modifiées)"
                                )
                                failLogged = true
                            }
                        }
                    }
                }
            }
            frameIdx++
            if (frameIdx % 4 == 0) onProgress(frameIdx, totalFrames, inferencer.moveCount)
            t += frameIntervalUs
        }

        AnalysisResult(inferencer.pgn(), inferencer.moveCount, warnings)
    }

    // luminance sous-échantillonnée de la zone pendule (grille ~24x24 max)
    private var clockPixelBuf: IntArray? = null

    private fun clockLuma(frame: Bitmap): FloatArray {
        val l = clockRect[0].roundToInt().coerceIn(0, frame.width - 2)
        val tY = clockRect[1].roundToInt().coerceIn(0, frame.height - 2)
        val r = clockRect[2].roundToInt().coerceIn(l + 1, frame.width - 1)
        val b = clockRect[3].roundToInt().coerceIn(tY + 1, frame.height - 1)
        val w = r - l
        val h = b - tY

        var buf = clockPixelBuf
        if (buf == null || buf.size < w * h) {
            buf = IntArray(w * h)
            clockPixelBuf = buf
        }
        frame.getPixels(buf, 0, w, l, tY, w, h)

        val stepX = (w / 24).coerceAtLeast(1)
        val stepY = (h / 24).coerceAtLeast(1)
        val out = ArrayList<Float>()
        var y = 0
        while (y < h) {
            var x = 0
            val base = y * w
            while (x < w) {
                val px = buf[base + x]
                out.add(
                    0.299f * ((px shr 16) and 0xFF) +
                        0.587f * ((px shr 8) and 0xFF) +
                        0.114f * (px and 0xFF)
                )
                x += stepX
            }
            y += stepY
        }
        return out.toFloatArray()
    }

    private fun meanAbsDiff(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var sum = 0f
        for (i in a.indices) sum += abs(a[i] - b[i])
        return sum / a.size
    }

    private fun fmt(us: Long): String {
        val s = us / 1_000_000
        return "%d:%02d".format(s / 60, s % 60)
    }
}
