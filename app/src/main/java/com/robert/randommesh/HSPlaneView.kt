package com.robert.randommesh

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/** Hue x saturation colour picker plane: hue runs left to right, saturation
 *  runs top (vivid) to bottom (grey). The whole plane dims with
 *  [brightness] — brightness 0 is black. Tap or drag to pick; a crosshair
 *  marks the current pick. */
class HSPlaneView(context: Context) : View(context) {
    var selectedHue = 0f
    var selectedSat = 0.85f
    var brightness = 1f
    var onPick: ((hue: Float, sat: Float) -> Unit)? = null

    private var bitmap: Bitmap? = null
    private val dimPaint = Paint().apply { color = Color.BLACK }
    private val crossWhite = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }
    private val crossDark = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val bw = min(w, 512)
        val bh = min(h, 256)
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(bw * bh)
        for (y in 0 until bh) {
            val s = 1f - y.toFloat() / (bh - 1).coerceAtLeast(1)
            for (x in 0 until bw) {
                val hue = x.toFloat() / (bw - 1).coerceAtLeast(1) * 360f
                pixels[y * bw + x] = Color.HSVToColor(floatArrayOf(hue, s, 0.95f))
            }
        }
        bmp.setPixels(pixels, 0, bw, 0, 0, bw, bh)
        bitmap = bmp
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap ?: return
        canvas.drawBitmap(bmp, null,
            RectF(0f, 0f, width.toFloat(), height.toFloat()), null)
        if (brightness < 1f) {
            dimPaint.alpha = ((1f - brightness) * 255f).toInt().coerceIn(0, 255)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        }
        val cx = selectedHue * width
        val cy = (1f - selectedSat) * height
        val r = 12f * resources.displayMetrics.density
        canvas.drawCircle(cx, cy, r + 4f, crossDark)
        canvas.drawCircle(cx, cy, r, crossWhite)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                selectedHue = (event.x / width).coerceIn(0f, 1f)
                selectedSat = (1f - event.y / height).coerceIn(0f, 1f)
                onPick?.invoke(selectedHue, selectedSat)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
