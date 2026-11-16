package com.tabwallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max
import kotlin.math.min

class CropView(context: Context) : View(context) {

    /** 框的宽/高比例（打直的屏幕比例） */
    var frameAspect = 10f / 16f
    /** 底部按钮占用的高度 */
    var bottomReserve = 0

    private var bmp: Bitmap? = null
    private var pendingCrop: Crop? = null
    private var needsInit = false

    private val frame = RectF()
    private var scale = 1f
    private var tx = 0f
    private var ty = 0f

    private val density = resources.displayMetrics.density
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dimPaint = Paint().apply { color = 0xAA000000.toInt() }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 2 * density
    }
    private val gridPaint = Paint().apply {
        color = 0x66FFFFFF
        strokeWidth = 1 * density
    }
    private val dst = RectF()

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val b = bmp ?: return false
            val minS = minScale(b)
            val newScale = (scale * d.scaleFactor).coerceIn(minS, minS * 8f)
            val f = newScale / scale
            tx = d.focusX - (d.focusX - tx) * f
            ty = d.focusY - (d.focusY - ty) * f
            scale = newScale
            clamp()
            invalidate()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            tx -= dx
            ty -= dy
            clamp()
            invalidate()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            resetToCenter()
            return true
        }
    })

    fun setImage(b: Bitmap, crop: Crop?) {
        bmp = b
        pendingCrop = crop
        needsInit = true
        if (!frame.isEmpty) initTransform()
        invalidate()
    }

    fun resetToCenter() {
        pendingCrop = null
        needsInit = true
        initTransform()
        invalidate()
    }

    /** 现在框住的区域（相对整张照片的比例） */
    fun currentCrop(): Crop? {
        val b = bmp ?: return null
        val iw = b.width * scale
        val ih = b.height * scale
        return Crop((frame.left - tx) / iw, (frame.top - ty) / ih, frame.width() / iw, frame.height() / ih)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val availH = (h - bottomReserve).toFloat()
        val pad = 24 * density
        var fw = w - 2 * pad
        var fh = fw / frameAspect
        if (fh > availH - 2 * pad) {
            fh = availH - 2 * pad
            fw = fh * frameAspect
        }
        frame.set((w - fw) / 2f, (availH - fh) / 2f, (w + fw) / 2f, (availH + fh) / 2f)
        if (bmp != null) {
            if (needsInit) initTransform() else clamp()
        }
    }

    private fun minScale(b: Bitmap) = max(frame.width() / b.width, frame.height() / b.height)

    private fun initTransform() {
        val b = bmp ?: return
        if (frame.isEmpty) return
        val c = pendingCrop ?: Crop.centered(b.width.toFloat(), b.height.toFloat(), frameAspect)
        scale = frame.width() / (c.width * b.width)
        tx = frame.left - c.left * b.width * scale
        ty = frame.top - c.top * b.height * scale
        needsInit = false
        clamp()
    }

    /** 不让照片露出框外的空白 */
    private fun clamp() {
        val b = bmp ?: return
        scale = max(scale, minScale(b))
        val iw = b.width * scale
        val ih = b.height * scale
        tx = min(frame.left, max(frame.right - iw, tx))
        ty = min(frame.top, max(frame.bottom - ih, ty))
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        if (!scaleDetector.isInProgress) gestureDetector.onTouchEvent(e)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val b = bmp ?: return
        dst.set(tx, ty, tx + b.width * scale, ty + b.height * scale)
        canvas.drawBitmap(b, null, dst, bmpPaint)

        val vw = width.toFloat()
        val vh = height.toFloat()
        canvas.drawRect(0f, 0f, vw, frame.top, dimPaint)
        canvas.drawRect(0f, frame.bottom, vw, vh, dimPaint)
        canvas.drawRect(0f, frame.top, frame.left, frame.bottom, dimPaint)
        canvas.drawRect(frame.right, frame.top, vw, frame.bottom, dimPaint)

        for (i in 1..2) {
            val x = frame.left + frame.width() * i / 3f
            val y = frame.top + frame.height() * i / 3f
            canvas.drawLine(x, frame.top, x, frame.bottom, gridPaint)
            canvas.drawLine(frame.left, y, frame.right, y, gridPaint)
        }
        canvas.drawRect(frame, borderPaint)
    }
}
