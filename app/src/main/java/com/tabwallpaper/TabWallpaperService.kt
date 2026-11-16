package com.tabwallpaper

import android.app.WallpaperManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.SurfaceHolder
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private class Slide(val item: WallItem, val bmp: Bitmap, val blur: Bitmap)

private const val FADE_MS = 350L

class TabWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = TabEngine()

    inner class TabEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener {

        private val ctx: Context get() = this@TabWallpaperService
        private val handler = Handler(Looper.getMainLooper())
        private val executor = Executors.newSingleThreadExecutor()

        private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val shade = Paint().apply { color = Color.BLACK }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
        }

        private var cur: Slide? = null
        private var prev: Slide? = null      // 换图时淡出的旧图
        private var fadeStart = 0L
        private var mode = Prefs.MODE_CROP
        private var loadToken = 0

        private var w = 0
        private var h = 0

        // 左右滑动：优先用桌面（launcher）给的位置；有些桌面不提供，就改用手指滑动的距离
        private var launcherOffset = 0.5f
        private var launcherScrolls = false
        private var swipeOffset = 0.5f

        // 双击的后备方案：有些系统不把触摸传给壁纸，只发 "tap" 指令
        private var touchSeen = false
        private var lastTapTime = 0L
        private var lastTapX = 0
        private var lastTapY = 0

        private val drawRunnable = Runnable { drawFrame() }
        private lateinit var gestures: GestureDetector

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(true)
            setOffsetNotificationsEnabled(true)
            gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent) = true

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    nextWallpaper()
                    return true
                }

                override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                    if (!launcherScrolls && w > 0) {
                        swipeOffset = (swipeOffset + dx / (w * 2f)).coerceIn(0f, 1f)
                        if (isScrollMode()) drawFrame()
                    }
                    return false
                }
            })
            Prefs.sp(ctx).registerOnSharedPreferenceChangeListener(this)
            reload(fade = false)
        }

        override fun onDestroy() {
            Prefs.sp(ctx).unregisterOnSharedPreferenceChangeListener(this)
            handler.removeCallbacksAndMessages(null)
            executor.shutdownNow()
            super.onDestroy()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            w = width
            h = height
            drawFrame()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            handler.removeCallbacks(drawRunnable)
            super.onSurfaceDestroyed(holder)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            if (visible) drawFrame() else handler.removeCallbacks(drawRunnable)
        }

        override fun onOffsetsChanged(
            xOffset: Float, yOffset: Float, xStep: Float, yStep: Float, xPixels: Int, yPixels: Int
        ) {
            if (xStep > 0f && xStep < 1f) {   // 桌面有多页，且会告诉壁纸现在滑到哪
                launcherScrolls = true
                launcherOffset = xOffset
                if (isScrollMode()) drawFrame()
            }
        }

        override fun onTouchEvent(event: MotionEvent) {
            touchSeen = true
            gestures.onTouchEvent(event)
            super.onTouchEvent(event)
        }

        override fun onCommand(
            action: String?, x: Int, y: Int, z: Int, extras: Bundle?, resultRequested: Boolean
        ): Bundle? {
            if (action == WallpaperManager.COMMAND_TAP && !touchSeen) {
                val now = SystemClock.uptimeMillis()
                if (now - lastTapTime < 400 && abs(x - lastTapX) < 150 && abs(y - lastTapY) < 150) {
                    lastTapTime = 0
                    nextWallpaper()
                } else {
                    lastTapTime = now
                    lastTapX = x
                    lastTapY = y
                }
            }
            return super.onCommand(action, x, y, z, extras, resultRequested)
        }

        override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
            when (key) {
                Prefs.KEY_INDEX -> reload(fade = true)
                Prefs.KEY_ITEMS, Prefs.KEY_MODE -> reload(fade = false)
            }
        }

        private fun isScrollMode() = mode == Prefs.MODE_SCROLL && h > w

        private fun nextWallpaper() {
            val n = Prefs.items(ctx).size
            if (n > 1) Prefs.setIndex(ctx, (Prefs.index(ctx).mod(n) + 1) % n)
        }

        private fun reload(fade: Boolean) {
            mode = Prefs.mode(ctx)
            val items = Prefs.items(ctx)
            if (items.isEmpty()) {
                cur = null
                prev = null
                drawFrame()
                return
            }
            val item = items[Prefs.index(ctx).mod(items.size)]
            val current = cur
            if (current != null && current.item.uri == item.uri) {
                // 同一张照片，只是框选改了，不用重新读图
                cur = Slide(item, current.bmp, current.blur)
                drawFrame()
                return
            }
            val token = ++loadToken
            val maxDim = ImageLoader.screenMaxDim(ctx)
            executor.execute {
                val bmp = ImageLoader.load(ctx, Uri.parse(item.uri), maxDim)
                val blur = bmp?.let { ImageLoader.blurOf(it) }
                handler.post {
                    if (token != loadToken) return@post
                    if (bmp == null || blur == null) {
                        drawFrame()
                        return@post
                    }
                    prev = if (fade) cur else null
                    fadeStart = SystemClock.uptimeMillis()
                    cur = Slide(item, bmp, blur)
                    drawFrame()
                }
            }
        }

        private fun drawFrame() {
            handler.removeCallbacks(drawRunnable)
            if (w == 0 || h == 0) return
            val holder = surfaceHolder
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas()
                if (canvas != null) render(canvas)
            } catch (_: Exception) {
            } finally {
                if (canvas != null) {
                    try { holder.unlockCanvasAndPost(canvas) } catch (_: Exception) {}
                }
            }
            if (prev != null) handler.postDelayed(drawRunnable, 16)
        }

        private fun render(canvas: Canvas) {
            canvas.drawColor(Color.BLACK)
            val c = cur
            if (c == null) {
                textPaint.textSize = min(w, h) / 22f
                canvas.drawText("打开「Tab 壁纸」App 添加照片", w / 2f, h / 2f, textPaint)
                return
            }
            val p = prev
            if (p == null) {
                drawSlide(canvas, c, 255)
                return
            }
            val t = ((SystemClock.uptimeMillis() - fadeStart) / FADE_MS.toFloat()).coerceIn(0f, 1f)
            if (t >= 1f) {
                prev = null
                drawSlide(canvas, c, 255)
            } else {
                drawSlide(canvas, p, 255)
                drawSlide(canvas, c, (t * 255).toInt())
            }
        }

        private fun drawSlide(canvas: Canvas, s: Slide, alpha: Int) {
            val fw = w.toFloat()
            val fh = h.toFloat()
            val bw = s.bmp.width.toFloat()
            val bh = s.bmp.height.toFloat()
            paint.alpha = alpha

            when {
                // 打横：完整照片，两边用模糊的同一张照片填满
                fh <= fw -> {
                    val cs = max(fw / bw, fh / bh)
                    val cw = bw * cs
                    val ch = bh * cs
                    canvas.drawBitmap(s.blur, null, RectF((fw - cw) / 2, (fh - ch) / 2, (fw + cw) / 2, (fh + ch) / 2), paint)
                    shade.alpha = (alpha * 0.35f).toInt()
                    canvas.drawRect(0f, 0f, fw, fh, shade)

                    val fs = min(fw / bw, fh / bh)
                    val dw = bw * fs
                    val dh = bh * fs
                    canvas.drawBitmap(s.bmp, null, RectF((fw - dw) / 2, (fh - dh) / 2, (fw + dw) / 2, (fh + dh) / 2), paint)
                }

                // 打直 + 跟着滑动：最左页显示照片最左边，往右滑画面跟着移动
                mode == Prefs.MODE_SCROLL -> {
                    val sc = max(fw / bw, fh / bh)
                    val sw = bw * sc
                    val sh = bh * sc
                    val off = if (launcherScrolls) launcherOffset else swipeOffset
                    val left = -(sw - fw) * off
                    val top = (fh - sh) / 2f
                    canvas.drawBitmap(s.bmp, null, RectF(left, top, left + sw, top + sh), paint)
                }

                // 打直 + 自己框选
                else -> canvas.drawBitmap(s.bmp, cropSrc(s, fw / fh), RectF(0f, 0f, fw, fh), paint)
            }
        }

        private fun cropSrc(s: Slide, aspect: Float): Rect {
            val bw = s.bmp.width.toFloat()
            val bh = s.bmp.height.toFloat()
            val c = s.item.crop ?: Crop.centered(bw, bh, aspect)
            var cw = c.width * bw
            var ch = c.height * bh
            val cx = (c.left + c.width / 2f) * bw
            val cy = (c.top + c.height / 2f) * bh
            // 保持屏幕比例，避免画面被拉伸
            if (cw / ch > aspect) cw = ch * aspect else ch = cw / aspect
            return Rect((cx - cw / 2).toInt(), (cy - ch / 2).toInt(), (cx + cw / 2).toInt(), (cy + ch / 2).toInt())
        }
    }
}
