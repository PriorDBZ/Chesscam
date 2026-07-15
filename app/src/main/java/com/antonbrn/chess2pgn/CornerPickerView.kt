package com.antonbrn.chess2pgn

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class CornerPickerView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    companion object {
        val BOARD_LABELS = arrayOf("a8", "h8", "h1", "a1")
        const val TOTAL_POINTS = 6 // 4 coins plateau + 2 coins zone pendule
    }

    var onPointsChanged: ((Int) -> Unit)? = null

    private var bitmap: Bitmap? = null
    private val boardPoints = mutableListOf<PointF>() // coordonnées bitmap
    private val clockPoints = mutableListOf<PointF>()
    private val displayMatrix = Matrix()
    private val inverseMatrix = Matrix()

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val boardDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E53935")
        style = Paint.Style.FILL
    }
    private val boardLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E53935")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val clockDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFB300")
        style = Paint.Style.FILL
    }
    private val clockRectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFB300")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 36f
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }

    fun setFrame(bmp: Bitmap) {
        bitmap = bmp
        boardPoints.clear()
        clockPoints.clear()
        updateMatrix()
        onPointsChanged?.invoke(0)
        invalidate()
    }

    fun reset() {
        boardPoints.clear()
        clockPoints.clear()
        onPointsChanged?.invoke(0)
        invalidate()
    }

    fun pointCount() = boardPoints.size + clockPoints.size

    fun isComplete() = pointCount() == TOTAL_POINTS

    fun corners(): FloatArray {
        val arr = FloatArray(8)
        for (i in boardPoints.indices) {
            arr[i * 2] = boardPoints[i].x
            arr[i * 2 + 1] = boardPoints[i].y
        }
        return arr
    }

    /** Rectangle de la pendule en coordonnées bitmap : [left, top, right, bottom]. */
    fun clockRect(): FloatArray {
        val a = clockPoints[0]
        val b = clockPoints[1]
        return floatArrayOf(
            minOf(a.x, b.x), minOf(a.y, b.y),
            maxOf(a.x, b.x), maxOf(a.y, b.y)
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        updateMatrix()
    }

    private fun updateMatrix() {
        val bmp = bitmap ?: return
        if (width == 0 || height == 0) return
        val scale = minOf(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        val dx = (width - bmp.width * scale) / 2f
        val dy = (height - bmp.height * scale) / 2f
        displayMatrix.reset()
        displayMatrix.postScale(scale, scale)
        displayMatrix.postTranslate(dx, dy)
        displayMatrix.invert(inverseMatrix)
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = bitmap ?: return
        canvas.drawBitmap(bmp, displayMatrix, bmpPaint)

        // plateau
        val pts = FloatArray(boardPoints.size * 2)
        for (i in boardPoints.indices) {
            pts[i * 2] = boardPoints[i].x
            pts[i * 2 + 1] = boardPoints[i].y
        }
        displayMatrix.mapPoints(pts)
        if (boardPoints.size >= 2) {
            for (i in 0 until boardPoints.size - 1) {
                canvas.drawLine(pts[i * 2], pts[i * 2 + 1], pts[i * 2 + 2], pts[i * 2 + 3], boardLinePaint)
            }
            if (boardPoints.size == 4) {
                canvas.drawLine(pts[6], pts[7], pts[0], pts[1], boardLinePaint)
            }
        }
        for (i in boardPoints.indices) {
            canvas.drawCircle(pts[i * 2], pts[i * 2 + 1], 12f, boardDotPaint)
            canvas.drawText(BOARD_LABELS[i], pts[i * 2] + 16f, pts[i * 2 + 1] - 16f, textPaint)
        }

        // pendule
        if (clockPoints.isNotEmpty()) {
            val cpts = FloatArray(clockPoints.size * 2)
            for (i in clockPoints.indices) {
                cpts[i * 2] = clockPoints[i].x
                cpts[i * 2 + 1] = clockPoints[i].y
            }
            displayMatrix.mapPoints(cpts)
            for (i in clockPoints.indices) {
                canvas.drawCircle(cpts[i * 2], cpts[i * 2 + 1], 12f, clockDotPaint)
            }
            if (clockPoints.size == 2) {
                canvas.drawRect(
                    minOf(cpts[0], cpts[2]), minOf(cpts[1], cpts[3]),
                    maxOf(cpts[0], cpts[2]), maxOf(cpts[1], cpts[3]),
                    clockRectPaint
                )
                canvas.drawText("pendule", minOf(cpts[0], cpts[2]), minOf(cpts[1], cpts[3]) - 12f, textPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN && bitmap != null && !isComplete()) {
            val p = floatArrayOf(event.x, event.y)
            inverseMatrix.mapPoints(p)
            val bmp = bitmap!!
            val x = p[0].coerceIn(0f, bmp.width - 1f)
            val y = p[1].coerceIn(0f, bmp.height - 1f)
            if (boardPoints.size < 4) {
                boardPoints.add(PointF(x, y))
            } else {
                clockPoints.add(PointF(x, y))
            }
            onPointsChanged?.invoke(pointCount())
            invalidate()
            performClick()
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
