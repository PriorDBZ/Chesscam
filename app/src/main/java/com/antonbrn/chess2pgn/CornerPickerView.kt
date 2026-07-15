package com.antonbrn.chess2pgn

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

class CornerPickerView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    companion object {
        val BOARD_LABELS = arrayOf("a8", "h8", "h1", "a1")
        const val TOTAL_POINTS = 6 // 4 coins plateau + 2 coins zone pendule

        private const val GRAB_RADIUS = 64f      // rayon de saisie d'un point (px écran)
        private const val MAG_RADIUS = 120f      // rayon de la loupe
        private const val MAG_ZOOM = 3.5f
        private const val MAG_OFFSET = 220f      // distance loupe <-> doigt
    }

    var onPointsChanged: ((Int) -> Unit)? = null

    private var bitmap: Bitmap? = null
    private val boardPoints = mutableListOf<PointF>() // coordonnées bitmap
    private val clockPoints = mutableListOf<PointF>()
    private val displayMatrix = Matrix()
    private val inverseMatrix = Matrix()
    private val magMatrix = Matrix()
    private val magClip = Path()

    private var dragIndex = -1
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f
    private var touchX = 0f
    private var touchY = 0f

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val boardDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E53935"); style = Paint.Style.FILL
    }
    private val boardLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E53935"); style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val clockDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFB300"); style = Paint.Style.FILL
    }
    private val clockRectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFB300"); style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 36f
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }
    private val magBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 4f
    }
    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E53935"); style = Paint.Style.STROKE; strokeWidth = 2f
    }

    fun setFrame(bmp: Bitmap) {
        bitmap = bmp
        boardPoints.clear()
        clockPoints.clear()
        dragIndex = -1
        updateMatrix()
        onPointsChanged?.invoke(0)
        invalidate()
    }

    fun reset() {
        boardPoints.clear()
        clockPoints.clear()
        dragIndex = -1
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

    private fun pointAt(index: Int): PointF =
        if (index < boardPoints.size) boardPoints[index]
        else clockPoints[index - boardPoints.size]

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

        if (dragIndex >= 0) drawMagnifier(canvas, bmp)
    }

    private fun drawMagnifier(canvas: Canvas, bmp: Bitmap) {
        val pt = pointAt(dragIndex)
        val vp = floatArrayOf(pt.x, pt.y)
        displayMatrix.mapPoints(vp)

        // loupe au-dessus du doigt, ou en dessous si trop haut
        val cx = touchX.coerceIn(MAG_RADIUS, width - MAG_RADIUS)
        var cy = touchY - MAG_OFFSET
        if (cy < MAG_RADIUS) cy = touchY + MAG_OFFSET
        cy = cy.coerceIn(MAG_RADIUS, height - MAG_RADIUS)

        canvas.save()
        magClip.reset()
        magClip.addCircle(cx, cy, MAG_RADIUS, Path.Direction.CW)
        canvas.clipPath(magClip)
        canvas.drawColor(Color.BLACK)

        magMatrix.set(displayMatrix)
        magMatrix.postScale(MAG_ZOOM, MAG_ZOOM, vp[0], vp[1])
        magMatrix.postTranslate(cx - vp[0], cy - vp[1])
        canvas.drawBitmap(bmp, magMatrix, bmpPaint)
        canvas.restore()

        canvas.drawCircle(cx, cy, MAG_RADIUS, magBorderPaint)
        canvas.drawLine(cx - 24f, cy, cx + 24f, cy, crosshairPaint)
        canvas.drawLine(cx, cy - 24f, cx, cy + 24f, crosshairPaint)

        val label = if (dragIndex < 4) BOARD_LABELS[dragIndex] else "pendule"
        canvas.drawText(label, cx - textPaint.measureText(label) / 2f, cy - MAG_RADIUS - 12f, textPaint)
    }

    private fun findNearestPoint(x: Float, y: Float): Int {
        var best = -1
        var bestDist = GRAB_RADIUS
        for (i in 0 until pointCount()) {
            val pt = pointAt(i)
            val vp = floatArrayOf(pt.x, pt.y)
            displayMatrix.mapPoints(vp)
            val d = hypot(vp[0] - x, vp[1] - y)
            if (d < bestDist) {
                bestDist = d
                best = i
            }
        }
        return best
    }

    private fun moveDragged(x: Float, y: Float) {
        val bmp = bitmap ?: return
        val p = floatArrayOf(x + dragOffsetX, y + dragOffsetY)
        inverseMatrix.mapPoints(p)
        val pt = pointAt(dragIndex)
        pt.x = p[0].coerceIn(0f, bmp.width - 1f)
        pt.y = p[1].coerceIn(0f, bmp.height - 1f)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null) return super.onTouchEvent(event)
        touchX = event.x
        touchY = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val hit = findNearestPoint(event.x, event.y)
                if (hit >= 0) {
                    dragIndex = hit
                    val pt = pointAt(hit)
                    val vp = floatArrayOf(pt.x, pt.y)
                    displayMatrix.mapPoints(vp)
                    dragOffsetX = vp[0] - event.x
                    dragOffsetY = vp[1] - event.y
                } else if (!isComplete()) {
                    val p = floatArrayOf(event.x, event.y)
                    inverseMatrix.mapPoints(p)
                    val bmp = bitmap!!
                    val np = PointF(
                        p[0].coerceIn(0f, bmp.width - 1f),
                        p[1].coerceIn(0f, bmp.height - 1f)
                    )
                    if (boardPoints.size < 4) boardPoints.add(np) else clockPoints.add(np)
                    dragIndex = pointCount() - 1
                    dragOffsetX = 0f
                    dragOffsetY = 0f
                    onPointsChanged?.invoke(pointCount())
                } else {
                    return super.onTouchEvent(event)
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragIndex >= 0) {
                    moveDragged(event.x, event.y)
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragIndex >= 0) {
                    dragIndex = -1
                    invalidate()
                    performClick()
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
