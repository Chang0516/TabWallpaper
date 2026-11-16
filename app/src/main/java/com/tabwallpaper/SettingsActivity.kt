package com.tabwallpaper

import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

class SettingsActivity : Activity() {

    private val executor = Executors.newFixedThreadPool(2)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var listBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(32))
        }

        root.addView(button("＋ 添加照片") { pickImages() })

        root.addView(label("竖屏时怎么显示", bold = true))
        val radioCrop = RadioButton(this).apply {
            id = View.generateViewId()
            text = "自己框选画面（每张照片可以单独框）"
        }
        val radioScroll = RadioButton(this).apply {
            id = View.generateViewId()
            text = "跟着桌面左右滑动（像以前的手机）"
        }
        val group = RadioGroup(this).apply {
            addView(radioCrop)
            addView(radioScroll)
        }
        if (Prefs.mode(this) == Prefs.MODE_SCROLL) radioScroll.isChecked = true else radioCrop.isChecked = true
        group.setOnCheckedChangeListener { _, checkedId ->
            Prefs.setMode(this, if (checkedId == radioScroll.id) Prefs.MODE_SCROLL else Prefs.MODE_CROP)
        }
        root.addView(group)

        root.addView(button("设为桌面壁纸") { applyWallpaper() })
        root.addView(label("打横时永远显示完整照片。在桌面空白处双击可以换下一张。"))

        root.addView(label("我的照片", bold = true))
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listBox)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }

    private fun pickImages() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(i, REQ_PICK)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null) return

        val uris = mutableListOf<Uri>()
        data.clipData?.let { cd -> for (i in 0 until cd.itemCount) uris += cd.getItemAt(i).uri }
        data.data?.let { if (it !in uris) uris += it }

        val items = Prefs.items(this).toMutableList()
        for (u in uris) {
            // 记住读取权限，重启平板后壁纸依然能读到照片
            try {
                contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {}
            if (items.none { it.uri == u.toString() }) items += WallItem(u.toString())
        }
        Prefs.saveItems(this, items)
        refreshList()
    }

    private fun refreshList() {
        listBox.removeAllViews()
        val items = Prefs.items(this)
        if (items.isEmpty()) {
            listBox.addView(label("还没有照片，先点上面的「添加照片」。"))
            return
        }
        val current = Prefs.index(this).mod(items.size)
        items.forEachIndexed { i, item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
            }
            val thumb = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(0xFFDDDDDD.toInt())
            }
            row.addView(thumb, LinearLayout.LayoutParams(dp(96), dp(64)))
            loadThumb(thumb, item.uri)

            val info = TextView(this).apply {
                text = buildString {
                    append("第 ${i + 1} 张")
                    if (i == current) append(" · 正在使用")
                    append("\n")
                    append(if (item.crop != null) "已框选竖屏画面" else "竖屏：默认居中")
                }
                setPadding(dp(12), 0, dp(8), 0)
            }
            row.addView(info, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            row.addView(smallButton("框选") {
                startActivity(Intent(this, CropActivity::class.java).putExtra(CropActivity.EXTRA_INDEX, i))
            })
            row.addView(smallButton("用这张") {
                Prefs.setIndex(this, i)
                refreshList()
            })
            row.addView(smallButton("删除") { remove(i) })
            listBox.addView(row)
        }
    }

    private fun remove(i: Int) {
        val items = Prefs.items(this).toMutableList()
        if (i !in items.indices) return
        val removed = items.removeAt(i)
        try {
            contentResolver.releasePersistableUriPermission(Uri.parse(removed.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {}
        Prefs.saveItems(this, items)
        val idx = Prefs.index(this)
        if (i < idx) Prefs.setIndex(this, idx - 1)
        refreshList()
    }

    private fun loadThumb(view: ImageView, uri: String) {
        view.tag = uri
        executor.execute {
            val b = ImageLoader.load(this, Uri.parse(uri), 300)
            handler.post { if (view.tag == uri && b != null) view.setImageBitmap(b) }
        }
    }

    private fun applyWallpaper() {
        val comp = ComponentName(this, TabWallpaperService::class.java)
        try {
            startActivity(
                Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                    .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, comp)
            )
        } catch (e: Exception) {
            try {
                startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
            } catch (_: Exception) {
                Toast.makeText(this, "请到 设置 > 壁纸 里选择「Tab 壁纸」", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---------- 小工具 ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) }
    }

    private fun smallButton(text: String, onClick: () -> Unit) =
        Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            this.text = text
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            setOnClickListener { onClick() }
        }

    private fun label(text: String, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = if (bold) 18f else 14f
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(if (bold) 20 else 8), 0, dp(4))
    }

    companion object {
        private const val REQ_PICK = 1
    }
}
