package com.antonbrn.chess2pgn

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.antonbrn.chess2pgn.analysis.VideoAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var picker: CornerPickerView
    private lateinit var hint: TextView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var pickBtn: Button
    private lateinit var resetBtn: Button
    private lateinit var analyzeBtn: Button

    private var videoUri: Uri? = null
    private var analyzing = false

    private val pickVideo = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            videoUri = uri
            loadFirstFrame(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        picker = findViewById(R.id.cornerPicker)
        hint = findViewById(R.id.hintText)
        status = findViewById(R.id.statusText)
        progress = findViewById(R.id.progressBar)
        pickBtn = findViewById(R.id.pickBtn)
        resetBtn = findViewById(R.id.resetBtn)
        analyzeBtn = findViewById(R.id.analyzeBtn)

        picker.onPointsChanged = { count -> updateHint(count) }

        pickBtn.setOnClickListener {
            if (!analyzing) {
                pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
            }
        }
        resetBtn.setOnClickListener { if (!analyzing) picker.reset() }
        analyzeBtn.setOnClickListener { startAnalysis() }

        updateHint(0)
    }

    private fun updateHint(count: Int) {
        hint.text = when {
            videoUri == null -> getString(R.string.hint_pick_video)
            count < 4 -> getString(R.string.hint_tap_corner, CornerPickerView.BOARD_LABELS[count])
            count < CornerPickerView.TOTAL_POINTS -> getString(R.string.hint_tap_clock, count - 3)
            else -> getString(R.string.hint_ready)
        }
        analyzeBtn.isEnabled = videoUri != null &&
            count == CornerPickerView.TOTAL_POINTS && !analyzing
    }

    private fun loadFirstFrame(uri: Uri) {
        lifecycleScope.launch {
            val frame: Bitmap? = withContext(Dispatchers.IO) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(this@MainActivity, uri)
                    retriever.getScaledFrameAtTime(
                        0, MediaMetadataRetriever.OPTION_CLOSEST,
                        VideoAnalyzer.MAX_DIM, VideoAnalyzer.MAX_DIM
                    )
                } catch (e: Exception) {
                    null
                } finally {
                    retriever.release()
                }
            }
            if (frame != null) {
                picker.setFrame(frame)
            } else {
                Toast.makeText(this@MainActivity, R.string.error_video, Toast.LENGTH_LONG).show()
                videoUri = null
                updateHint(0)
            }
        }
    }

    private fun startAnalysis() {
        val uri = videoUri ?: return
        if (!picker.isComplete()) return

        analyzing = true
        analyzeBtn.isEnabled = false
        pickBtn.isEnabled = false
        resetBtn.isEnabled = false
        progress.visibility = View.VISIBLE
        status.visibility = View.VISIBLE
        status.text = getString(R.string.status_starting)

        val analyzer = VideoAnalyzer(applicationContext, uri, picker.corners(), picker.clockRect())

        lifecycleScope.launch {
            try {
                val result = analyzer.run { frame, total, moves ->
                    runOnUiThread {
                        progress.max = total
                        progress.progress = frame
                        status.text = getString(R.string.status_progress,
                            frame * 100 / total, moves)
                    }
                }
                if (result.moveCount == 0) {
                    status.text = getString(R.string.status_no_moves)
                } else {
                    status.text = getString(R.string.status_done, result.moveCount)
                    ResultActivity.start(this@MainActivity, result.pgn,
                        result.moveCount, ArrayList(result.warnings))
                }
            } catch (e: Exception) {
                status.text = getString(R.string.status_error, e.message ?: "?")
            } finally {
                analyzing = false
                pickBtn.isEnabled = true
                resetBtn.isEnabled = true
                analyzeBtn.isEnabled = picker.isComplete()
                progress.visibility = View.GONE
            }
        }
    }
}
