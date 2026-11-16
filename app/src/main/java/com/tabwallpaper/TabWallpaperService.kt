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

private const val FADE_MS = 300L

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
        private val debugPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.YELLOW
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
        }

        private var cur: Slide? = null
        private var prev: Slide? = null      // 换图时淡出的旧图
        private var preloaded: Slide? = null // 提前读好的下一张，双击时马上能换
        private var fadeStart = 0L
        private var mode = Prefs.MODE_CROP
        private var landMode = Prefs.LAND_AUTO
        private var debug = false
        private var loadToken = 0

        private var w = 0
        private var h = 0

        // 左右滑动：桌面有真的在报告位置变化才用它；否则改用手指在桌面上滑的距离
        private var launcherOffset = 0.5f
        private var firstLauncherOffset = -1f
        private var launcherScrolls = false
        private var swipeOffset = 0.5f

        // 调试用计数
        private var offsetEvents = 0
        private var touchEvents = 0
        private var tapCommands = 0

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
                        if (isScrollMode() || debug) drawFrame()
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
            offsetEvents++
            if (xStep > 0f && xStep < 1f) {
                if (firstLauncherOffset < 0f) firstLauncherOffset = xOffset
                else if (abs(xOffset - firstLauncherOffset) > 0.01f) launcherScrolls = true
                launcherOffset = xOffset
            }
            if (isScrollMode() || debug) drawFrame()
        }

        override fun onTouchEvent(event: MotionEvent) {
            touchSeen = true
            touchEvents++
            gestures.onTouchEvent(event)
            super.onTouchEvent(event)
        }

        override fun onCommand(
            action: String?, x: Int, y: Int, z: Int, extras: Bundle?, resultRequested: Boolean
        ): Bundle? {
            if (action == WallpaperManager.COMMAND_TAP) {
                tapCommands++
                if (!touchSeen) {
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
            }
            return super.onCommand(action, x, y, z, extras, resultRequested)
        }

        override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
            when (key) {
                Prefs.KEY_INDEX -> reload(fade = true)
                Prefs.KEY_ITEMS, Prefs.KEY_MODE, Prefs.KEY_LAND, Prefs.KEY_DEBUG -> reload(fade = false)
            }
        }

        private fun isScrollMode() = mode == Prefs.MODE_SCROLL && h > w

        private fun nextWallpaper() {
            val n = Prefs.items(ctx).size
            if (n > 1) Prefs.setIndex(ctx, (Prefs.index(ctx).mod(n) + 1) % n)
        }

        private fun reload(fade: Boolean) {
            mode = Prefs.mode(ctx)
            landMode = Prefs.landMode(ctx)
            debug = Prefs.debug(ctx)
            val items = Prefs.items(ctx)
            if (items.isEmpty()) {
                cur = null
                prev = null
                preloaded = null
                drawFrame()
                return
            }
            val idx = Prefs.index(ctx).mod(items.size)
            val item = items[idx]
            val current = cur
            if (current != null && current.item.uri == item.uri) {
                // 同一张照片，只是设置改了，不用重新读图
                cur = Slide(item, current.bmp, current.blur)
                drawFrame()
                preloadNext(items, idx)
                return
            }
            val ready = preloaded
            if (ready != null && ready.item.uri == item.uri) {
                // 下一张已经提前读好了 → 马上换，不卡
                loadToken++
                show(Slide(item, ready.bmp, ready.blur), fade)
                preloadNext(items, idx)
                return
            }
            val token = ++loadToken
            executor.execute {
                val slide = decode(item)
                handler.post {
                    if (token != loadToken) return@post
                    if (slide == null) {
                        drawFrame()
                        return@post
                    }
                    show(slide, fade)
                    preloadNext(items, idx)
                }
            }
        }

        private fun show(slide: Slide, fade: Boolean) {
            prev = if (fade) cur else null
            fadeStart = SystemClock.uptimeMillis()
            cur = slide
            drawFrame()
        }

        private fun preloadNext(items: List<WallItem>, idx: Int) {
            if (items.size < 2) {
                preloaded = null
                return
            }
            val next = items[(idx + 1) % items.size]
            if (preloaded?.item?.uri == next.uri) return
            executor.execute {
                val slide = decode(next) ?: return@execute
                handler.post { preloaded = slide }
            }
        }

        private fun decode(item: WallItem): Slide? {
            val bmp = ImageLoader.load(ctx, Uri.parse(item.uri), ImageLoader.screenMaxDim(ctx)) ?: return null
            bmp.prepareToDraw()
            return Slide(item, bmp, ImageLoader.blurOf(bmp))
        }

        private fun drawFrame() {
            handler.removeCallbacks(drawRunnable)
            if (w == 0 || h == 0) return
            val holder = surfaceHolder
            var canvas: Canvas? = null
            try {
                // 用 GPU 画，换图淡入淡出才顺
                canvas = try { holder.lockHardwareCanvas() } catch (e: Exception) { null }
                    ?: holder.lockCanvas()
                if (canvas != null) render(canvas)
            } catch (_: Exception) {
            } finally {
                if (canvas != null) {
                    try { holder.unlockCanvasAndPost(canvas) } catch (_: Exception) {}
                }
            }
            if (prev != null) handler.postDelayed(drawRunnable, 12)
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
            } else {
                val t = ((SystemClock.uptimeMillis() - fadeStart) / FADE_MS.toFloat()).coerceIn(0f, 1f)
                if (t >= 1f) {
                    prev = null
                    drawSlide(canvas, c, 255)
                } else {
                    drawSlide(canvas, p, 255)
                    drawSlide(canvas, c, (t * 255).toInt())
                }
            }
            if (debug) drawDebug(canvas)
        }

        private fun drawDebug(canvas: Canvas) {
            debugPaint.textSize = min(w, h) / 40f
            val lines = listOf(
                "画面 ${w}x$h",
                "桌面位置事件: $offsetEvents  位置=${"%.2f".format(launcherOffset)}  桌面会滑=${launcherScrolls}",
                "触摸事件: $touchEvents  点击指令: $tapCommands",
                "手指滑动位置=${"%.2f".format(swipeOffset)}"
            )
            var y = h * 0.12f
            for (l in lines) {
                canvas.drawText(l, w * 0.05f, y, debugPaint)
                y += debugPaint.textSize * 1.4f
            }
        }

        private fun drawSlide(canvas: Canvas, s: Slide, alpha: Int) {
            val fw = w.toFloat()
            val fh = h.toFloat()
            val bw = s.bmp.width.toFloat()
            val bh = s.bmp.height.toFloat()
            paint.alpha = alpha

            when {
                // 打横
                fh <= fw -> {
                    val screenAspect = fw / fh
                    val imgAspect = bw / bh
                    val diff = max(screenAspect, imgAspect) / min(screenAspect, imgAspect)
                    val fill = when (landMode) {
                        Prefs.LAND_FILL -> true
                        Prefs.LAND_FIT -> false
                        else -> diff < 1.2f   // 比例差不多 → 直接填满，不留边
                    }
                    if (fill) {
                        drawCover(canvas, s.bmp, fw, fh)
                    } else {
                        // 完整照片，空位用同一张照片的模糊版填满
                        drawCover(canvas, s.blur, fw, fh)
                        shade.alpha = (alpha * 0.2f).toInt()
                        canvas.drawRect(0f, 0f, fw, fh, shade)
                        val fs = min(fw / bw, fh / bh)
                        val dw = bw * fs
                        val dh = bh * fs
                        canvas.drawBitmap(s.bmp, null, RectF((fw - dw) / 2, (fh - dh) / 2, (fw + dw) / 2, (fh + dh) / 2), paint)
                    }
                }

                // 打直 + 跟着滑动
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

        private fun drawCover(canvas: Canvas, b: Bitmap, fw: Float, fh: Float) {
            val cs = max(fw / b.width, fh / b.height)
            val cw = b.width * cs
            val ch = b.height * cs
            canvas.drawBitmap(b, null, RectF((fw - cw) / 2, (fh - ch) / 2, (fw + cw) / 2, (fh + ch) / 2), paint)
        }

        private fun cropSrc(s: Slide, aspect: Float): Rect {
            val bw = s.bmp.width.toFloat()
            val bh = s.bmp.height.toFloat()
            val c = s.item.crop ?: Crop.centered(bw, bh, aspect)
            var cw = c.width * bw
            var ch = c.height * bh
            val cx = (c.left + c.width / 2f) * bw
            val cy = (c.top + c.height / 2f) * bh
            if (cw / ch > aspect) cw = ch * aspect else ch = cw / aspect
            return Rect((cx - cw / 2).toInt(), (cy - ch / 2).toInt(), (cx + cw / 2).toInt(), (cy + ch / 2).toInt())
        }
    }
}
