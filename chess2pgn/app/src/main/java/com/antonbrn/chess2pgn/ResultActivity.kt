package com.antonbrn.chess2pgn

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class ResultActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_PGN = "pgn"
        private const val EXTRA_MOVES = "moves"
        private const val EXTRA_WARNINGS = "warnings"

        fun start(context: Context, pgn: String, moves: Int, warnings: ArrayList<String>) {
            context.startActivity(Intent(context, ResultActivity::class.java).apply {
                putExtra(EXTRA_PGN, pgn)
                putExtra(EXTRA_MOVES, moves)
                putStringArrayListExtra(EXTRA_WARNINGS, warnings)
            })
        }
    }

    private var pgn: String = ""

    private val savePgn = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-chess-pgn")
    ) { uri ->
        if (uri != null) {
            contentResolver.openOutputStream(uri)?.use {
                it.write(pgn.toByteArray())
            }
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_result)

        pgn = intent.getStringExtra(EXTRA_PGN) ?: ""
        val moves = intent.getIntExtra(EXTRA_MOVES, 0)
        val warnings = intent.getStringArrayListExtra(EXTRA_WARNINGS) ?: arrayListOf()

        findViewById<TextView>(R.id.titleText).text =
            getString(R.string.result_title, moves)

        val warningsText = findViewById<TextView>(R.id.warningsText)
        if (warnings.isEmpty()) {
            warningsText.text = ""
        } else {
            warningsText.text = warnings.joinToString("\n") { "⚠ $it" }
        }

        findViewById<TextView>(R.id.pgnText).text = pgn

        findViewById<Button>(R.id.copyBtn).setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("PGN", pgn))
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.shareBtn).setOnClickListener {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, pgn)
            }
            startActivity(Intent.createChooser(send, getString(R.string.share)))
        }

        findViewById<Button>(R.id.saveBtn).setOnClickListener {
            savePgn.launch("partie.pgn")
        }
    }
}
