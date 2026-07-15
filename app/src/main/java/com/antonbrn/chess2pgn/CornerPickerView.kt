package com.antonbrn.chess2pgn

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class CornerPickerView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    companion object {
        val LABELS = arrayOf("a8", "h8", "h1", "a1")
    }

    var onPointsChanged: ((Int) -> Unit)? = null

    private var bitmap: Bitmap? = null
    private val points = mutableListOf<PointF>() // en coordonnées bitmap
    private val displayMatrix = Matrix()
    private val inverseMatrix = Matrix()

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E53935")
        style = Paint.Style.FILL
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E53935")
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
        points.clear()
        updateMatrix()
        onPointsChanged?.invoke(0)
        invalidate()
    }

    fun reset() {
        points.clear()
        onPointsChanged?.invoke(0)
        invalidate()
    }

    fun isComplete() = points.size == 4

    fun corners(): FloatArray {
        val arr = FloatArray(8)
        for (i in points.indices) {
            arr[i * 2] = points[i].x
            arr[i * 2 + 1] = points[i].y
        }
        return arr
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

        val pts = FloatArray(points.size * 2)
        for (i in points.indices) {
            pts[i * 2] = points[i].x
            pts[i * 2 + 1] = points[i].y
        }
        displayMatrix.mapPoints(pts)

        if (points.size >= 2) {
            for (i in 0 until points.size - 1) {
                canvas.drawLine(pts[i * 2], pts[i * 2 + 1], pts[i * 2 + 2], pts[i * 2 + 3], linePaint)
            }
            if (points.size == 4) {
                canvas.drawLine(pts[6], pts[7], pts[0], pts[1], linePaint)
            }
        }
        for (i in points.indices) {
            canvas.drawCircle(pts[i * 2], pts[i * 2 + 1], 12f, dotPaint)
            canvas.drawText(LABELS[i], pts[i * 2] + 16f, pts[i * 2 + 1] - 16f, textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN && bitmap != null && points.size < 4) {
            val p = floatArrayOf(event.x, event.y)
            inverseMatrix.mapPoints(p)
            val bmp = bitmap!!
            val x = p[0].coerceIn(0f, bmp.width - 1f)
            val y = p[1].coerceIn(0f, bmp.height - 1f)
            points.add(PointF(x, y))
            onPointsChanged?.invoke(points.size)
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
