package com.robert.randommesh

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Procedurally paints a 256x256 texture for the current mesh, from its
 *  texture settings: pattern, scale, contrast and the mesh's hues. */
object TextureGenerator {
    const val SIZE = 256

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    fun generate(
        s: MeshSettings, brightnessScale: Float = 1f, size: Int = SIZE,
        contrastA: Float? = null, contrastB: Float? = null,
        saturationA: Float? = null, saturationB: Float? = null,
        brightnessA: Float? = null, brightnessB: Float? = null,
        antialias: Boolean = false
    ): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val scale = s.textureScale
        val cA = contrastA ?: s.textureContrast
        val cB = contrastB ?: s.textureContrast
        val sA = saturationA ?: s.saturation
        val sB = saturationB ?: s.saturation
        val vA = brightnessA ?: 1f
        val vB = brightnessB ?: 1f
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val p = patternValue(s, x, y, scale, size, antialias)
                // Contrast around 0.5, then map through the hue gradient.
                // Each end of the gradient can have its own contrast,
                // saturation and brightness, blended across the pattern.
                val c = cA + (cB - cA) * p
                val t = (0.5f + (p - 0.5f) * c).coerceIn(0f, 1f)
                val hue = s.hueA + (s.hueB - s.hueA) * t
                val sat = sA + (sB - sA) * t
                val bright = (0.45f + 0.5f * t).coerceIn(0.05f, 1f) * (vA + (vB - vA) * t)
                val rgb = MeshGenerator.hsv(hue, sat, bright)
                val r = (rgb[0] * 255f * brightnessScale).toInt().coerceIn(0, 255)
                val g = (rgb[1] * 255f * brightnessScale).toInt().coerceIn(0, 255)
                val b = (rgb[2] * 255f * brightnessScale).toInt().coerceIn(0, 255)
                pixels[y * size + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        bmp.setPixels(pixels, 0, size, 0, 0, size, size)
        return bmp
    }

    // Antialiasing: hard-edged patterns blend across ~1.5 texels at each
    // edge (smoothstep from the edge midpoint) instead of flipping in one
    // texel, so edges read as smooth lines rather than stair steps. The
    // sine patterns (Marble, Stripes, Waves, Rings, Camo) are continuous
    // already and need no help. Diamond also uses floor() for its rotated
    // cell indices, so negative coordinates alternate correctly.
    private fun patternValue(s: MeshSettings, x: Int, y: Int, scale: Float, size: Int, aa: Boolean): Float {
        val fx = x.toFloat(); val fy = y.toFloat()
        return when (s.texturePattern) {
            "Stripes" -> 0.5f + 0.5f * sin((fx + fy) * 0.06f * scale + s.noisePhase1)
            "Checker" -> {
                val cell = (32f / scale).coerceAtLeast(4f)
                val cx = fx / cell; val cy = fy / cell
                val inside = (cx.toInt() + cy.toInt()) % 2 == 0
                if (!aa) {
                    if (inside) 0.9f else 0.15f
                } else {
                    val dx = min(cx - floor(cx), 1f - (cx - floor(cx))) * cell
                    val dy = min(cy - floor(cy), 1f - (cy - floor(cy))) * cell
                    val f = smoothstep(0f, 1.5f, min(dx, dy))
                    0.525f + (if (inside) 0.375f else -0.375f) * f
                }
            }
            "Speckle" -> {
                val h = sin(fx * 12.9898f + fy * 78.233f) * 43758.5453f
                val frac = h - floor(h)
                if (!aa) {
                    if (frac > 0.85f) 1f else 0.25f
                } else {
                    0.25f + 0.75f * smoothstep(0.80f, 0.95f, frac)
                }
            }
            "Gradient" -> fx / size
            "Camo" -> 0.5f + 0.5f * sin(fx * 0.03f * scale + s.noisePhase1) * sin(fy * 0.035f * scale + s.noisePhase2)
            "Waves" -> 0.5f + 0.5f * sin((fx * 0.045f + fy * 0.03f) * scale + s.noisePhase1)
            "Rings" -> {
                val dx = fx - size / 2f; val dy = fy - size / 2f
                val d = sqrt(dx * dx + dy * dy)
                0.5f + 0.5f * sin(d * 0.12f * scale + s.noisePhase1)
            }
            "Dots" -> {
                val cell = (32f / scale).coerceAtLeast(8f)
                val cx = fx % cell - cell / 2f; val cy = fy % cell - cell / 2f
                val d = sqrt(cx * cx + cy * cy)
                val r = cell * 0.28f
                if (!aa) {
                    if (d < r) 1f else 0.15f
                } else {
                    val f = smoothstep(0f, 1.5f, abs(d - r))
                    if (d < r) 0.575f + 0.425f * f else 0.575f - 0.425f * f
                }
            }
            "Grid" -> {
                val cell = (32f / scale).coerceAtLeast(8f)
                fun axis(v: Float): Float {
                    val l = v % cell
                    val onLine = l < 2f
                    if (!aa) return if (onLine) 0.9f else 0.15f
                    val dist = min(min(l, abs(l - 2f)), cell - l)
                    val f = smoothstep(0f, 1.5f, dist)
                    return 0.525f + (if (onLine) 0.375f else -0.375f) * f
                }
                max(axis(fx), axis(fy))
            }
            "Bricks" -> {
                val cw = (32f / scale).coerceAtLeast(8f); val ch = (16f / scale).coerceAtLeast(4f)
                val row = (fy / ch).toInt()
                val off = if (row % 2 == 0) 0f else cw / 2f
                val lx = (fx + off) % cw; val ly = fy % ch
                if (!aa) {
                    if (lx < 2f || ly < 2f) 0.15f else 0.9f
                } else {
                    fun axis(l: Float, c: Float): Float {
                        val onLine = l < 2f
                        val dist = min(min(l, abs(l - 2f)), c - l)
                        val f = smoothstep(0f, 1.5f, dist)
                        return 0.525f + (if (onLine) 0.375f else -0.375f) * f
                    }
                    min(axis(lx, cw), axis(ly, ch))
                }
            }
            "Diamond" -> {
                val cell = (32f / scale).coerceAtLeast(4f)
                val u = (fx + fy) / 2f; val v = (fx - fy) / 2f
                val iu = floor(u / cell).toInt(); val iv = floor(v / cell).toInt()
                val inside = (iu + iv) % 2 == 0
                if (!aa) {
                    if (inside) 0.9f else 0.15f
                } else {
                    val du = min(u / cell - floor(u / cell), 1f - (u / cell - floor(u / cell))) * cell
                    val dv = min(v / cell - floor(v / cell), 1f - (v / cell - floor(v / cell))) * cell
                    val f = smoothstep(0f, 1.5f, min(du, dv))
                    0.525f + (if (inside) 0.375f else -0.375f) * f
                }
            }
            else -> { // Marble
                val wobble = 3f * sin(fy * 0.03f * scale + s.noisePhase2)
                0.5f + 0.5f * sin((fx * 0.05f * scale + wobble) + s.noisePhase1)
            }
        }.coerceIn(0f, 1f)
    }
}
