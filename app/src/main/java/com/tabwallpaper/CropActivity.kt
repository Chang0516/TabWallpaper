package com.tabwallpaper

import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import kotlin.math.max
import kotlin.math.min

/** 竖屏框选：拖动移动、双指缩放、双击重置。框内就是打直时的壁纸画面。 */
class CropActivity : Activity() {

    private lateinit var cropView: CropView
    private var index = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        index = intent.getIntExtra(EXTRA_INDEX, 0)
        val items = Prefs.items(this)
        if (index !in items.indices) {
            finish()
            return
        }
        val item = items[index]

        // 框的比例 = 平板打直时的屏幕比例
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(dm)
        val aspect = min(dm.widthPixels, dm.heightPixels).toFloat() / max(dm.widthPixels, dm.heightPixels)

        cropView = CropView(this).apply {
            frameAspect = aspect
            bottomReserve = dp(80)
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xCC000000.toInt())
        }
        bar.addView(barButton("重置") { cropView.resetToCenter() })
        bar.addView(barButton("取消") { finish() })
        bar.addView(barButton("保存") { save() })

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(cropView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(bar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(72), Gravity.BOTTOM))
        setContentView(root)

        Toast.makeText(this, "拖动移动 · 双指缩放 · 双击重置", Toast.LENGTH_SHORT).show()

        Thread {
            val b = ImageLoader.load(this, Uri.parse(item.uri), ImageLoader.screenMaxDim(this))
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (b == null) {
                    Toast.makeText(this, "读不到这张照片，可能已被删除", Toast.LENGTH_LONG).show()
                    finish()
                } else {
                    cropView.setImage(b, item.crop)
                }
            }
        }.start()
    }

    private fun save() {
        val crop = cropView.currentCrop() ?: return
        val items = Prefs.items(this).toMutableList()
        if (index in items.indices) {
            items[index] = items[index].copy(crop = crop)
            Prefs.saveItems(this, items)
        }
        finish()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun barButton(text: String, onClick: () -> Unit) =
        Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            this.text = text
            isAllCaps = false
            setTextColor(Color.WHITE)
            textSize = 17f
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }

    companion object {
        const val EXTRA_INDEX = "index"
    }
}
