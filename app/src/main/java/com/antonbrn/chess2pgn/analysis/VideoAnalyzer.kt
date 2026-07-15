package com.antonbrn.chess2pgn.analysis

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

class AnalysisResult(
    val pgn: String,
    val moveCount: Int,
    val warnings: List<String>
)

class VideoAnalyzer(
    private val context: Context,
    private val uri: Uri,
    private val corners: FloatArray
) {

    companion object {
        // fréquence d'échantillonnage : 2 images/s suffisent pour une partie normale
        const val FRAME_INTERVAL_US = 500_000L
        const val MAX_DIM = 640

        // seuils de vision, à ajuster selon l'éclairage / le matériel si besoin
        const val STABLE_DIFF = 5f      // en dessous : la scène est considérée immobile
        const val STABLE_FRAMES = 2     // nb de frames immobiles consécutives requises
        const val CHANGE_THRESHOLD = 20f // au-dessus : la case a changé d'état
        const val MIN_CHANGED = 2       // un coup modifie au moins 2 cases
    }

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
        val totalFrames = (durationUs / FRAME_INTERVAL_US).toInt().coerceAtLeast(1)

        val warper = BoardWarper(corners)
        val inferencer = MoveInferencer()
        val warnings = mutableListOf<String>()

        var prevStats: FloatArray? = null
        var committed: FloatArray? = null
        var stableRun = 0
        var pendingFailLogged = false

        var frameIdx = 0
        var t = 0L
        while (t < durationUs && isActive) {
            val bmp = retriever.getScaledFrameAtTime(
                t, MediaMetadataRetriever.OPTION_CLOSEST, MAX_DIM, MAX_DIM
            )
            if (bmp != null) {
                val warped = warper.warp(bmp)
                bmp.recycle()
                val stats = SquareStats.compute(warped)
                warped.recycle()

                val p = prevStats
                stableRun = if (p != null && SquareStats.globalDiff(p, stats) < STABLE_DIFF) {
                    stableRun + 1
                } else 0
                prevStats = stats

                if (stableRun >= STABLE_FRAMES) {
                    if (committed == null) {
                        // première position stable = position de départ
                        committed = stats
                    } else {
                        val changed = SquareStats.changedSquares(committed!!, stats, CHANGE_THRESHOLD)
                        if (changed.size >= MIN_CHANGED) {
                            val applied = inferencer.inferAndApply(changed)
                            if (applied > 0) {
                                committed = stats
                                pendingFailLogged = false
                            } else if (!pendingFailLogged) {
                                warnings.add(
                                    "Changement inexpliqué à ${fmt(t)} " +
                                        "(${changed.size} cases modifiées) — coup ignoré"
                                )
                                pendingFailLogged = true
                            }
                        }
                    }
                }
            }
            frameIdx++
            if (frameIdx % 4 == 0) onProgress(frameIdx, totalFrames, inferencer.moveCount)
            t += FRAME_INTERVAL_US
        }

        AnalysisResult(inferencer.pgn(), inferencer.moveCount, warnings)
    }

    private fun fmt(us: Long): String {
        val s = us / 1_000_000
        return "%d:%02d".format(s / 60, s % 60)
    }
}
