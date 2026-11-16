package com.tabwallpaper

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** 竖屏框选的区域。数值都是相对整张照片的比例（0~1）。 */
data class Crop(val left: Float, val top: Float, val width: Float, val height: Float) {
    companion object {
        /** 照片正中间、比例为 aspect（宽/高）的最大区域 */
        fun centered(imgW: Float, imgH: Float, aspect: Float): Crop {
            val imgAspect = imgW / imgH
            return if (imgAspect > aspect) {
                val w = aspect / imgAspect
                Crop((1f - w) / 2f, 0f, w, 1f)
            } else {
                val h = imgAspect / aspect
                Crop(0f, (1f - h) / 2f, 1f, h)
            }
        }
    }
}

data class WallItem(val uri: String, val crop: Crop? = null)

object Prefs {
    const val NAME = "tab_wallpaper"
    const val KEY_ITEMS = "items"
    const val KEY_INDEX = "index"
    const val KEY_MODE = "portrait_mode"

    /** 竖屏：用自己框选的画面 */
    const val MODE_CROP = "crop"
    /** 竖屏：跟着桌面左右滑动 */
    const val MODE_SCROLL = "scroll"

    fun sp(c: Context): SharedPreferences = c.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun items(c: Context): List<WallItem> {
        val raw = sp(c).getString(KEY_ITEMS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val crop = o.optJSONObject("crop")?.let {
                    Crop(
                        it.getDouble("l").toFloat(),
                        it.getDouble("t").toFloat(),
                        it.getDouble("w").toFloat(),
                        it.getDouble("h").toFloat()
                    )
                }
                WallItem(o.getString("uri"), crop)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveItems(c: Context, items: List<WallItem>) {
        val arr = JSONArray()
        items.forEach { item ->
            val o = JSONObject().put("uri", item.uri)
            item.crop?.let {
                o.put(
                    "crop", JSONObject()
                        .put("l", it.left.toDouble())
                        .put("t", it.top.toDouble())
                        .put("w", it.width.toDouble())
                        .put("h", it.height.toDouble())
                )
            }
            arr.put(o)
        }
        sp(c).edit().putString(KEY_ITEMS, arr.toString()).apply()
    }

    fun index(c: Context): Int = sp(c).getInt(KEY_INDEX, 0)
    fun setIndex(c: Context, i: Int) = sp(c).edit().putInt(KEY_INDEX, i).apply()

    fun mode(c: Context): String = sp(c).getString(KEY_MODE, MODE_CROP) ?: MODE_CROP
    fun setMode(c: Context, m: String) = sp(c).edit().putString(KEY_MODE, m).apply()

    const val KEY_LAND = "landscape_mode"
    const val LAND_AUTO = "auto"
    const val LAND_FILL = "fill"
    const val LAND_FIT = "fit"
    fun landMode(c: Context): String = sp(c).getString(KEY_LAND, LAND_AUTO) ?: LAND_AUTO
    fun setLandMode(c: Context, m: String) = sp(c).edit().putString(KEY_LAND, m).apply()

    const val KEY_DEBUG = "debug"
    fun debug(c: Context): Boolean = sp(c).getBoolean(KEY_DEBUG, false)
    fun setDebug(c: Context, on: Boolean) = sp(c).edit().putBoolean(KEY_DEBUG, on).apply()
}
