package com.robert.randommesh

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

data class MeshData(
    val name: String,
    val kind: String,
    val settings: MeshSettings,
    val vertices: FloatArray,
    val normals: FloatArray,
    val colors: FloatArray,
    val indices: ShortArray,
    val uvs: FloatArray,
    val extremity: FloatArray,
    val part: FloatArray
) {
    val vertexCount: Int get() = vertices.size / 3
    val triangleCount: Int get() = indices.size / 3
}

object MeshGenerator {

    fun randomMesh(): MeshData {
        val rnd = Random(System.nanoTime())
        return generate(MeshSettings.random(rnd, null, emptySet()))
    }

    /** morphPhase drifts the noise phases / twist / bulge so a live re-generation
     *  visibly morphs the mesh instead of just swapping it.
     *  complexity 0 = the bare base shape, 1 = the full procedural mesh:
     *  every displacement, form and detail layer is scaled by it, so a mesh
     *  starts simple and the procedural detail is added in steps. */
    fun generate(s: MeshSettings, morphPhase: Float = 0f, complexity: Float = 1f): MeshData {
        val c = complexity.coerceIn(0f, 1f)
        val eff = s.copy(
            noisePhase1 = s.noisePhase1 + morphPhase,
            noisePhase2 = s.noisePhase2 + morphPhase * 0.7f,
            noiseAmplitude = s.noiseAmplitude * c,
            detailAmplitude = s.detailAmplitude * c,
            ridgeAmount = s.ridgeAmount * c,
            spikeHeight = s.spikeHeight * c,
            craterDepth = s.craterDepth * c,
            bumpHeight = s.bumpHeight * c,
            grooveDepth = s.grooveDepth * c,
            extremityLength = s.extremityLength * c,
            vertexJitter = s.vertexJitter * c,
            twistDeg = s.twistDeg * c + sin(morphPhase) * 25f * c,
            taper = s.taper * c,
            bend = s.bend * c,
            bulge = (s.bulge * c + sin(morphPhase * 0.8f) * 0.15f * c).coerceIn(-0.6f, 0.8f),
            pinch = s.pinch * c,
            shear = s.shear * c,
            terraceSteps = if (c < 0.6f) 0 else s.terraceSteps
        )
        val rnd = Random(s.seed)
        val cap = if (s.flatShading || s.shape == "Crystal") 6000 else 12000
        val (rows, cols) = cappedSegments(s.segmentsV, s.segmentsU, s.detailSubdiv, cap)
        val (verts, idx, uvs) = when (s.shape) {
            "Twisted Torus" -> torus(eff, rows, cols)
            "Wave Terrain" -> terrain(eff, rows, cols)
            "Twisted Column" -> column(eff, rows, cols)
            "Torus Knot" -> torusKnot(eff, rows, cols)
            "Gear" -> gear(eff, rows, cols)
            "Spiral Shell" -> spiralShell(eff, rows, cols)
            "Helix Coil" -> helixCoil(eff, rows, cols)
            "Möbius Strip" -> mobiusStrip(eff, rows, cols)
            else -> sphereLike(eff, rows, cols)
        }
        val extRnd = Random(s.seed + 999L)
        val extremityDirs = List(s.extremityCount) { randomDir(extRnd) }
        val displaced = applyPipeline(eff, verts, rnd, extremityDirs)
        // Per-vertex attributes for the shader: how much this vertex belongs to
        // a growing extremity, and which moving part (angular sector) it is in.
        val extremity = FloatArray(displaced.size / 3)
        val part = FloatArray(displaced.size / 3)
        for (i in extremity.indices) {
            val x = displaced[i * 3]; val y = displaced[i * 3 + 1]; val z = displaced[i * 3 + 2]
            val r = sqrt(x * x + y * y + z * z).coerceAtLeast(0.001f)
            val dx = x / r; val dy = y / r; val dz = z / r
            var w = 0f
            for (e in extremityDirs) {
                val dot = (dx * e[0] + dy * e[1] + dz * e[2]).coerceIn(-1f, 1f)
                val ang = acos(dot)
                val g = exp(-((ang / s.extremityThickness) * (ang / s.extremityThickness)).toDouble()).toFloat()
                if (g > w) w = g
            }
            extremity[i] = w
            val theta = atan2(z, x)
            part[i] = floor(((theta + PI.toFloat()) / (2f * PI.toFloat())) * s.partCount).toFloat()
        }
        return finish(s, displaced, idx, uvs, extremity, part)
    }

    private fun cappedSegments(v: Int, u: Int, subdiv: Int, cap: Int): Pair<Int, Int> {
        var rows = (v * subdiv).coerceIn(8, 96)
        var cols = (u * subdiv).coerceIn(8, 96)
        while (rows * cols > cap) {
            if (rows > cols) rows /= 2 else cols /= 2
        }
        return Pair(rows, cols)
    }

    // ---------- base noise ----------

    private fun baseNoise(s: MeshSettings, x: Float, y: Float, z: Float): Float {
        return sin(x * 1.7f + s.noisePhase1) * sin(y * 2.3f + s.noisePhase2) * sin(z * 1.9f + s.noisePhase3) +
            0.5f * cos(x * 3.1f + s.noisePhase2) * cos(y * 2.7f + s.noisePhase1)
    }

    private fun fbm(s: MeshSettings, x: Float, y: Float, z: Float): Float {
        var total = 0f
        var norm = 0f
        var amp = 1f
        var freq = s.noiseFrequency
        for (o in 0 until s.noiseOctaves) {
            var v = baseNoise(s, x * freq, y * freq, z * freq)
            v = when (s.noiseType) {
                "Ridged" -> 1f - abs(v)
                "Billow" -> abs(v)
                "Sine" -> sin((x + y + z) * freq + s.noisePhase1)
                else -> v
            }
            total += v * amp
            norm += amp
            freq *= s.noiseLacunarity
            amp *= s.noisePersistence
        }
        return if (norm > 0f) total / norm else 0f
    }

    private fun randomDir(rnd: Random): FloatArray {
        val theta = rnd.nextFloat() * 2f * PI.toFloat()
        val phi = acos(2f * rnd.nextFloat() - 1f)
        return floatArrayOf(sin(phi) * cos(theta), cos(phi), sin(phi) * sin(theta))
    }

    // ---------- procedural pipeline ----------

    private fun applyPipeline(
        s: MeshSettings, vertsIn: FloatArray, rnd: Random,
        extremityDirs: List<FloatArray>
    ): FloatArray {
        val craterDirs = List(s.craterCount) { randomDir(rnd) }
        val bumpDirs = List(s.bumpCount) { randomDir(rnd) }
        val isTerrain = s.shape == "Wave Terrain"
        val out = FloatArray(vertsIn.size)
        val n = vertsIn.size / 3
        for (i in 0 until n) {
            var x = vertsIn[i * 3]
            var y = vertsIn[i * 3 + 1]
            var z = vertsIn[i * 3 + 2]
            val r0 = sqrt(x * x + y * y + z * z).coerceAtLeast(0.001f)
            val dx = x / r0; val dy = y / r0; val dz = z / r0
            val theta = atan2(z, x)
            val phi = acos((dy).coerceIn(-1f, 1f))
            val sym = theta * s.symmetryFolds

            val f = fbm(s, x, y, z)
            var d = s.noiseAmplitude * f
            d += s.detailAmplitude * baseNoise(s, x * s.detailFrequency, y * s.detailFrequency, z * s.detailFrequency)
            d += s.ridgeAmount * (1f - abs(f)).toDouble().pow(s.edgeSharpness.toDouble()).toFloat() * 0.4f
            if (s.spikeCount > 0) {
                val sp = abs(sin(sym + s.noisePhase1)) * abs(sin(phi * max(1, s.spikeCount - 1) + s.noisePhase2))
                d += s.spikeHeight * sp.toDouble().pow(s.spikeSharpness.toDouble()).toFloat()
            }
            for (c in craterDirs) {
                val dot = (dx * c[0] + dy * c[1] + dz * c[2]).coerceIn(-1f, 1f)
                val ang = acos(dot)
                d -= s.craterDepth * exp(-((ang / s.craterRadius) * (ang / s.craterRadius)).toDouble()).toFloat()
            }
            for (b in bumpDirs) {
                val dot = (dx * b[0] + dy * b[1] + dz * b[2]).coerceIn(-1f, 1f)
                val ang = acos(dot)
                d += s.bumpHeight * exp(-((ang / 0.25f) * (ang / 0.25f)).toDouble()).toFloat()
            }
            if (s.grooveCount > 0) {
                d -= s.grooveDepth * abs(sin(phi * s.grooveCount + s.noisePhase3)).toDouble().pow(2.0).toFloat()
            }
            // Growing extremities: a short nub now, the shader grows the rest
            // over extremityGrowthSeconds. Weight = closeness to an extremity axis.
            if (extremityDirs.isNotEmpty()) {
                var w = 0f
                for (e in extremityDirs) {
                    val dot = (dx * e[0] + dy * e[1] + dz * e[2]).coerceIn(-1f, 1f)
                    val ang = acos(dot)
                    val g = exp(-((ang / s.extremityThickness) * (ang / s.extremityThickness)).toDouble()).toFloat()
                    if (g > w) w = g
                }
                d += s.extremityLength * w * 0.3f
            }

            if (isTerrain) {
                y += d * 1.5f
            } else {
                val newR = (r0 + d).coerceIn(0.2f, 3f)
                val k = newR / r0
                x *= k; y *= k; z *= k
            }
            if (s.terraceSteps > 0) {
                val r = sqrt(x * x + y * y + z * z).coerceAtLeast(0.001f)
                val step = 3f / s.terraceSteps
                val rq = floor(r / step) * step + step * 0.5f
                val k = rq / r
                x *= k; y *= k; z *= k
            }
            val yn = (y / 1.5f).coerceIn(-1f, 1f)
            if (s.twistDeg != 0f) {
                val a = s.twistDeg * PI.toFloat() / 180f * yn
                val ca = cos(a); val sa = sin(a)
                val nx = x * ca - z * sa
                val nz = x * sa + z * ca
                x = nx; z = nz
            }
            val taperK = 1f - s.taper * yn
            x *= taperK; z *= taperK
            x += s.bend * yn * yn
            val bulgeK = 1f + s.bulge * sin(PI.toFloat() * (yn + 1f) / 2f)
            x *= bulgeK; z *= bulgeK
            val pinchK = 1f - s.pinch * abs(yn)
            x *= pinchK; z *= pinchK
            x += s.shear * y
            if (s.vertexJitter > 0f) {
                val h1 = sin(i * 12.9898f) * 43758.5453f
                val h2 = sin(i * 78.233f) * 12543.1234f
                val j1 = (h1 - floor(h1) - 0.5f) * s.vertexJitter
                val j2 = (h2 - floor(h2) - 0.5f) * s.vertexJitter
                x += j1; y += j2; z += j1 * 0.5f
            }
            out[i * 3] = x * s.scaleX * s.baseRadius
            out[i * 3 + 1] = y * s.scaleY * s.baseRadius
            out[i * 3 + 2] = z * s.scaleZ * s.baseRadius
        }
        return out
    }

    // ---------- helpers ----------

    fun hsv(h: Float, sat: Float, v: Float): FloatArray {
        val hh = ((h % 1f) + 1f) % 1f * 6f
        val c = v * sat
        val x = c * (1f - abs(hh % 2f - 1f))
        val m = v - c
        val (r, g, b) = when (hh.toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return floatArrayOf(r + m, g + m, b + m)
    }

    private fun parametric(
        rows: Int, cols: Int, wrapU: Boolean,
        pos: (u: Float, v: Float) -> FloatArray
    ): Triple<FloatArray, IntArray, FloatArray> {
        val verts = ArrayList<Float>()
        val uvs = ArrayList<Float>()
        val idx = ArrayList<Int>()
        val uCount = if (wrapU) cols else cols + 1
        for (r in 0..rows) {
            val v = r.toFloat() / rows
            for (c in 0 until uCount) {
                val u = c.toFloat() / cols
                val p = pos(u, v)
                verts.add(p[0]); verts.add(p[1]); verts.add(p[2])
                uvs.add(u); uvs.add(v)
            }
        }
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val c2 = if (wrapU) (c + 1) % cols else c + 1
                val a = r * uCount + c
                val b = r * uCount + c2
                val d = (r + 1) * uCount + c
                val e = (r + 1) * uCount + c2
                idx.add(a); idx.add(d); idx.add(b)
                idx.add(b); idx.add(d); idx.add(e)
            }
        }
        return Triple(verts.toFloatArray(), idx.toIntArray(), uvs.toFloatArray())
    }

    private fun tubePoint(
        centre: (t: Float) -> FloatArray, t: Float, vAngle: Float, tubeR: Float
    ): FloatArray {
        val eps = 0.01f
        val c = centre(t)
        val c1 = centre((t + eps).coerceAtMost(1f))
        val c0 = centre((t - eps).coerceAtLeast(0f))
        var tx = c1[0] - c0[0]; var ty = c1[1] - c0[1]; var tz = c1[2] - c0[2]
        val tl = sqrt(tx * tx + ty * ty + tz * tz).coerceAtLeast(0.0001f)
        tx /= tl; ty /= tl; tz /= tl
        var nx = -tz
        var ny = 0f
        var nz = tx
        var nl = sqrt(nx * nx + ny * ny + nz * nz)
        if (nl < 0.001f) { nx = 1f; ny = 0f; nz = 0f; nl = 1f }
        nx /= nl; ny /= nl; nz /= nl
        val bx = ty * nz - tz * ny
        val by = tz * nx - tx * nz
        val bz = tx * ny - ty * nx
        val ca = cos(vAngle); val sa = sin(vAngle)
        return floatArrayOf(
            c[0] + (nx * ca + bx * sa) * tubeR,
            c[1] + (ny * ca + by * sa) * tubeR,
            c[2] + (nz * ca + bz * sa) * tubeR
        )
    }

    private fun computeNormals(vertices: FloatArray, indices: IntArray): FloatArray {
        val n = FloatArray(vertices.size)
        var t = 0
        while (t < indices.size) {
            val a = indices[t] * 3; val b = indices[t + 1] * 3; val c = indices[t + 2] * 3
            val ux = vertices[b] - vertices[a]; val uy = vertices[b + 1] - vertices[a + 1]; val uz = vertices[b + 2] - vertices[a + 2]
            val vx = vertices[c] - vertices[a]; val vy = vertices[c + 1] - vertices[a + 1]; val vz = vertices[c + 2] - vertices[a + 2]
            var nx = uy * vz - uz * vy
            var ny = uz * vx - ux * vz
            var nz = ux * vy - uy * vx
            val len = sqrt(nx * nx + ny * ny + nz * nz)
            if (len > 0.00001f) { nx /= len; ny /= len; nz /= len }
            n[a] += nx; n[a + 1] += ny; n[a + 2] += nz
            n[b] += nx; n[b + 1] += ny; n[b + 2] += nz
            n[c] += nx; n[c + 1] += ny; n[c + 2] += nz
            t += 3
        }
        var i = 0
        while (i < n.size) {
            val len = sqrt(n[i] * n[i] + n[i + 1] * n[i + 1] + n[i + 2] * n[i + 2])
            if (len > 0.00001f) { n[i] /= len; n[i + 1] /= len; n[i + 2] /= len }
            else { n[i] = 0f; n[i + 1] = 1f; n[i + 2] = 0f }
            i += 3
        }
        return n
    }

    private fun colourFor(s: MeshSettings, x: Float, y: Float, z: Float, index: Int, minY: Float, spanY: Float, minR: Float, spanR: Float): FloatArray {
        val r = sqrt(x * x + y * y + z * z)
        val t = when (s.gradientMode) {
            "Radius" -> ((r - minR) / spanR).coerceIn(0f, 1f)
            "Noise" -> (fbm(s, x, y, z) * 0.5f + 0.5f).coerceIn(0f, 1f)
            "Bands" -> (sin(y * 6f + s.noisePhase1) * 0.5f + 0.5f)
            "Random" -> { val h = sin(index * 12.9898f) * 43758.5453f; h - floor(h) }
            else -> ((y - minY) / spanY).coerceIn(0f, 1f)
        }
        val hash = sin(index * 78.233f) * 12543.1234f
        val hueJitter = (hash - floor(hash) - 0.5f) * s.colourNoise
        val hue = s.hueA + (s.hueB - s.hueA) * t + hueJitter
        val bright = s.brightnessLow + (s.brightnessHigh - s.brightnessLow) * t
        return hsv(hue, s.saturation, bright.coerceIn(0.05f, 1f))
    }

    private fun finish(
        s: MeshSettings, verts: FloatArray, idx: IntArray,
        uvs: FloatArray, extremity: FloatArray, part: FloatArray
    ): MeshData {
        val flat = s.flatShading || s.shape == "Crystal"
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var minR = Float.MAX_VALUE; var maxR = -Float.MAX_VALUE
        var i = 0
        while (i < verts.size / 3) {
            val y = verts[i * 3 + 1]
            val r = sqrt(verts[i * 3] * verts[i * 3] + y * y + verts[i * 3 + 2] * verts[i * 3 + 2])
            minY = min(minY, y); maxY = max(maxY, y)
            minR = min(minR, r); maxR = max(maxR, r)
            i++
        }
        val spanY = max(0.001f, maxY - minY)
        val spanR = max(0.001f, maxR - minR)
        val kind = s.shape.lowercase().replace(" ", "")
        if (flat) {
            val triCount = idx.size / 3
            val eVerts = FloatArray(idx.size * 3)
            val eNormals = FloatArray(idx.size * 3)
            val eColors = FloatArray(idx.size * 3)
            val eUvs = FloatArray(idx.size * 2)
            val eExt = FloatArray(idx.size)
            val ePart = FloatArray(idx.size)
            val eIdx = ShortArray(idx.size) { it.toShort() }
            for (t in 0 until triCount) {
                val a = idx[t * 3]; val b = idx[t * 3 + 1]; val c = idx[t * 3 + 2]
                val ax = verts[a * 3]; val ay = verts[a * 3 + 1]; val az = verts[a * 3 + 2]
                val bx = verts[b * 3]; val by = verts[b * 3 + 1]; val bz = verts[b * 3 + 2]
                val cx = verts[c * 3]; val cy = verts[c * 3 + 1]; val cz = verts[c * 3 + 2]
                var nx = (by - ay) * (cz - az) - (bz - az) * (cy - ay)
                var ny = (bz - az) * (cx - ax) - (bx - ax) * (cz - az)
                var nz = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
                val len = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(0.00001f)
                nx /= len; ny /= len; nz /= len
                val src = intArrayOf(a, b, c)
                for (k in 0..2) {
                    val dst = (t * 3 + k) * 3
                    val sv = src[k]
                    eVerts[dst] = verts[sv * 3]; eVerts[dst + 1] = verts[sv * 3 + 1]; eVerts[dst + 2] = verts[sv * 3 + 2]
                    eNormals[dst] = nx; eNormals[dst + 1] = ny; eNormals[dst + 2] = nz
                    val rgb = colourFor(s, eVerts[dst], eVerts[dst + 1], eVerts[dst + 2], sv, minY, spanY, minR, spanR)
                    eColors[dst] = rgb[0]; eColors[dst + 1] = rgb[1]; eColors[dst + 2] = rgb[2]
                    val dstUv = (t * 3 + k) * 2
                    eUvs[dstUv] = uvs[sv * 2]; eUvs[dstUv + 1] = uvs[sv * 2 + 1]
                    eExt[t * 3 + k] = extremity[sv]
                    ePart[t * 3 + k] = part[sv]
                }
            }
            return MeshData(s.shape, kind, s, eVerts, eNormals, eColors, eIdx, eUvs, eExt, ePart)
        }
        val normals = computeNormals(verts, idx)
        val colors = FloatArray(verts.size)
        var v = 0
        while (v < verts.size / 3) {
            val rgb = colourFor(s, verts[v * 3], verts[v * 3 + 1], verts[v * 3 + 2], v, minY, spanY, minR, spanR)
            colors[v * 3] = rgb[0]; colors[v * 3 + 1] = rgb[1]; colors[v * 3 + 2] = rgb[2]
            v++
        }
        val shortIdx = ShortArray(idx.size) { idx[it].toShort() }
        return MeshData(s.shape, kind, s, verts, normals, colors, shortIdx, uvs, extremity, part)
    }

    // ---------- base shapes ----------

    private fun sphereLike(s: MeshSettings, rows: Int, cols: Int): Triple<FloatArray, IntArray, FloatArray> {
        val crystalBoost = if (s.shape == "Crystal") 1.6f else 1f
        return parametric(rows, cols, true) { u, v ->
            val theta = u * 2f * PI.toFloat()
            val phi = v * PI.toFloat()
            val sx = sin(phi) * cos(theta); val sy = cos(phi); val sz = sin(phi) * sin(theta)
            val r = 1f + 0.12f * crystalBoost * sin(5f * phi + s.noisePhase1) * cos(4f * theta + s.noisePhase2)
            floatArrayOf(sx * r, sy * r, sz * r)
        }
    }

    private fun torus(s: MeshSettings, rows: Int, cols: Int): Triple<FloatArray, IntArray, FloatArray> {
        return parametric(rows, cols, true) { u, v ->
            val a = u * 2f * PI.toFloat()
            val b = v * 2f * PI.toFloat()
            val cx = (s.torusMajor + s.torusMinor * cos(b)) * cos(a)
            val cy = s.torusMinor * sin(b)
            val cz = (s.torusMajor + s.torusMinor * cos(b)) * sin(a)
            floatArrayOf(cx, cy, cz)
        }
    }

    private fun terrain(s: MeshSettings, rows: Int, cols: Int): Triple<FloatArray, IntArray, FloatArray> {
        return parametric(rows, cols, false) { u, v ->
            val x = (u - 0.5f) * s.terrainSize
            val y = (v - 0.5f) * s.terrainSize
            val z = 0.3f * sin(x * s.noiseFrequency * 0.8f + s.noisePhase1) * cos(y * s.noiseFrequency * 0.8f + s.noisePhase2)
            floatArrayOf(x, z, y)
        }
    }

    private fun column(s: MeshSettings, rows: Int, cols: Int): Triple<FloatArray, IntArray, FloatArray> {
        return parametric(rows, cols, true) { u, v ->
            val y = (v - 0.5f) * s.columnHeight
            val taper = sin(PI.toFloat() * v).coerceIn(0.06f, 1f)
            val radius = (0.55f + 0.3f * sin(y * 3f + s.noisePhase1)) * taper
            val a = u * 2f * PI.toFloat()
            floatArrayOf(radius * cos(a), y, radius * sin(a))
        }
    }

    private fun torusKnot(s: MeshSettings, rows: Int, cols: Int): Triple<FloatArray, IntArray, FloatArray> {
        val p = s.knotP.toFloat(); val q = s.knotQ.toFloat()
        val centre: (Float) -> FloatArray = { t ->
            val a = t * 2f * PI.toFloat()
            val r = s.torusMajor + s.torusMinor * cos(q * a)
            floatArrayOf(r * cos(p * a), s.torusMinor * sin(q * a), r * sin(p * a))
        }
        val knotRows = min(180, rows * 3)
        val knotCols = min(24, cols / 2 + 8)
        return parametric(knotRows, knotCols, true) { u, v ->
            tubePoint(centre, u, v * 2f * PI.toFloat(), 0.16f + s.torusMinor * 0.15f)
        }
    }

    private fun gear(s: MeshSettings, rows: Int, cols: Int): Triple<FloatArray, IntArray, FloatArray> {
        return parametric(rows, cols, true) { u, v ->
            val a = u * 2f * PI.toFloat()
            val b = v * 2f * PI.toFloat()
            val tooth = if (sin(a * s.gearTeeth) > 0f) s.gearDepth else 0f
            val cx = (s.torusMajor + tooth + s.torusMinor * 0.6f * cos(b)) * cos(a)
            val cy = s.torusMinor * 0.6f * sin(b)
            val cz = (s.torusMajor + tooth + s.torusMinor * 0.6f * cos(b)) * sin(a)
            floatArrayOf(cx, cy, cz)
        }
    }

    private fun spiralShell(s: MeshSettings, rows: Int, cols: Int): Triple<FloatArray, IntArray, FloatArray> {
        val turns = 3f
        val centre: (Float) -> FloatArray = { t ->
            val a = t * turns * 2f * PI.toFloat()
            val r = 0.2f + t * 1.0f
            floatArrayOf(r * cos(a), (t - 0.5f) * 0.5f, r * sin(a))
        }
        return parametric(rows, cols, true) { u, v ->
            tubePoint(centre, u, v * 2f * PI.toFloat(), 0.3f * (1f - u * 0.55f))
        }
    }

    /** Day 1 of the daily procedures (2026-11-06): a spring — one tube wound
     *  around a vertical helix. Coil radius comes from torusMajor and tube
     *  thickness from torusMinor, so every roll varies; the rest of the
     *  pipeline (noise, twist, taper, spikes, craters) still applies on top,
     *  exactly like Torus Knot and Spiral Shell. */
    private fun helixCoil(s: MeshSettings, rows: Int, cols: Int): Triple<FloatArray, IntArray, FloatArray> {
        val turns = 4f
        val coilR = s.torusMajor
        val centre: (Float) -> FloatArray = { t ->
            val a = t * turns * 2f * PI.toFloat()
            floatArrayOf(coilR * cos(a), (t - 0.5f) * 1.6f, coilR * sin(a))
        }
        val coilRows = min(180, rows * 3)
        val coilCols = min(24, cols / 2 + 8)
        return parametric(coilRows, coilCols, true) { u, v ->
            tubePoint(centre, u, v * 2f * PI.toFloat(), 0.14f + s.torusMinor * 0.15f)
        }
    }

    /** Day 2 of the daily procedures (2026-11-07): a single-sided band —
     *  one loop around a ring with a half-twist, so the strip has only one
     *  side and one edge. Ring radius comes from torusMajor, strip
     *  half-width from torusMinor. The grid is left open (wrapU=false):
     *  the half-twist means the u=0 and u=1 edges meet with v flipped, so
     *  a wrapped grid would tear across the seam; the open edges coincide
     *  exactly, leaving only a seam line and no hole. */
    private fun mobiusStrip(s: MeshSettings, rows: Int, cols: Int): Triple<FloatArray, IntArray, FloatArray> {
        val ringR = s.torusMajor
        val halfW = s.torusMinor
        return parametric(rows, cols, false) { u, v ->
            val theta = u * 2f * PI.toFloat()
            val half = theta / 2f
            val w = (v - 0.5f) * 2f * halfW
            val cx = ringR + w * cos(half)
            floatArrayOf(cx * cos(theta), cx * sin(theta), w * sin(half))
        }
    }
}
