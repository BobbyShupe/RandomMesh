package com.robert.randommesh

import android.graphics.Color
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

data class AnimParams(
    val mode: String,
    val rotX: Float,
    val rotY: Float,
    val rotZ: Float,
    val wobbleAmp: Float,
    val wobbleFreq: Float,
    val pulseAmp: Float,
    val bounceAmp: Float,
    val orbitRadius: Float
)

class MeshRenderer(private val listener: Listener) : GLSurfaceView.Renderer {

    interface Listener {
        fun onMeshChanged(mesh: MeshData, anim: AnimParams)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private var program = 0
    private var aPosition = 0
    private var aNextPosition = 0
    private var aNormal = 0
    private var aNextNormal = 0
    private var aColor = 0
    private var aNextColor = 0
    private var aUV = 0
    private var aExtremity = 0
    private var aPart = 0
    private var uMvp = 0
    private var uModel = 0
    private var uTime = 0
    private var uMorphMix = 0
    private var uWobbleAmp = 0
    private var uWobbleFreq = 0
    private var uGrowth = 0
    private var uExtremityLength = 0
    private var uExtremityPulse = 0
    private var uPartMoveAmount = 0
    private var uPartMoveSpeed = 0
    private var uTexScroll = 0
    private var uAmbient = 0
    private var uSpotIntensity = 0
    private var uTexture = 0

    // Current state buffers, and the morph target's (same topology, so the
    // shader can blend every vertex, normal and colour between the two).
    private var vertexBuffer: FloatBuffer? = null
    private var normalBuffer: FloatBuffer? = null
    private var colorBuffer: FloatBuffer? = null
    private var nextVertexBuffer: FloatBuffer? = null
    private var nextNormalBuffer: FloatBuffer? = null
    private var nextColorBuffer: FloatBuffer? = null
    private var uvBuffer: FloatBuffer? = null
    private var extremityBuffer: FloatBuffer? = null
    private var partBuffer: FloatBuffer? = null
    private var indexBuffer: ShortBuffer? = null
    private var indexCount = 0
    private var textureId = 0

    @Volatile var paused = false
    @Volatile var speedMultiplier = 1.0f
    // User view controls, live and in the video: base Y rotation in degrees,
    // camera distance, and a rotation-only speed multiplier.
    @Volatile var rotationOffsetY = 0f
    @Volatile var zoomDistance = 4.4f
    @Volatile var rotationSpeed = 1f
    // Chosen colour palette, or null for a fresh random palette per mesh.
    // A set palette overrides the rolled hues on every new mesh.
    @Volatile var palette: Palette? = null
    // Scene background colour (ARGB). Default is the original dark navy.
    @Volatile var backgroundColor: Int = 0xFF05050D.toInt()
    // Scrolling texture background: fully separate from the mesh. It has
    // its own pattern, scroll, tile scale, pattern detail scale, and per
    // colour its own hue, saturation, brightness (0 = black) and contrast —
    // nothing is inherited from the mesh's settings. Colours are snapshotted
    // from the first mesh once, then only change via the picker, "Match mesh
    // palette" (a snapshot copy, not a link), or randomization. While
    // backgroundRandomize is on, every new mesh also rolls a fresh random
    // background. Quality picks the texture resolution (0 = 256px,
    // 1 = 512px, 2 = 1024px); antialiasing smooths the hard pattern edges
    // so they don't stair-step. Colour changes raise the dirty flag; the GL
    // thread regenerates at most once a frame.
    @Volatile var backgroundMode: Int = BG_SOLID
    @Volatile var backgroundTexturePattern: String = "Marble"
    @Volatile var backgroundScrollX: Float = 0.25f
    @Volatile var backgroundScrollY: Float = 0f
    @Volatile var backgroundTextureScale: Float = 1f
    @Volatile var backgroundTextureDetailScale: Float = 1f
    @Volatile var backgroundContrastA: Float = 1f
    @Volatile var backgroundContrastB: Float = 1f
    @Volatile var backgroundTextureQuality: Int = 0
    @Volatile var backgroundAntialias: Boolean = true
    @Volatile var backgroundRandomize: Boolean = true
    @Volatile var backgroundHueA: Float = 0.6f
    @Volatile var backgroundHueB: Float = 0.8f
    @Volatile var backgroundSaturationA: Float = 0.85f
    @Volatile var backgroundSaturationB: Float = 0.85f
    @Volatile var backgroundBrightnessA: Float = 1f
    @Volatile var backgroundBrightnessB: Float = 1f
    @Volatile var backgroundTextureDirty = false
    private var backgroundColorsInitialized = false
    private var backgroundTextureId = 0
    private var bgProgram = 0
    private var bgAPosition = 0
    private var bgAUV = 0
    private var bgUOffset = 0
    private var bgUScale = 0
    private var bgUTexture = 0
    private var viewportAspect = 1f
    private val bgVertexBuffer = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val bgUvBuffer = floatBuffer(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
    private val bgIndexBuffer = ByteBuffer.allocateDirect(6 * 2).order(ByteOrder.nativeOrder())
        .asShortBuffer().apply { put(shortArrayOf(0, 1, 2, 2, 1, 3)); position(0) }
    @Volatile var autoChange = true

    private var mesh: MeshData? = null
    private var targetMeshData: MeshData? = null
    private var currentSettings: MeshSettings? = null
    val lockedKeys: MutableSet<String> = mutableSetOf()

    /** The committed mesh, its animation and its settings, for saving / video.
     *  morphPhase is included so the video can re-generate this exact mesh
     *  at any complexity (same phase => same shape, just simpler/fuller).
     *  The view controls ride along so the video frames exactly what the
     *  user sees on screen. */
    data class Snapshot(
        val mesh: MeshData, val anim: AnimParams, val settings: MeshSettings,
        val morphPhase: Float, val rotationOffsetY: Float, val zoomDistance: Float,
        val rotationSpeed: Float, val speedMultiplier: Float, val backgroundColor: Int,
        val backgroundMode: Int, val backgroundTexturePattern: String,
        val backgroundScrollX: Float, val backgroundScrollY: Float,
        val backgroundTextureScale: Float, val backgroundTextureDetailScale: Float,
        val backgroundContrastA: Float, val backgroundContrastB: Float,
        val backgroundTextureQuality: Int, val backgroundAntialias: Boolean,
        val backgroundHueA: Float,
        val backgroundHueB: Float, val backgroundSaturationA: Float,
        val backgroundSaturationB: Float, val backgroundBrightnessA: Float,
        val backgroundBrightnessB: Float
    )

    fun snapshot(): Snapshot? {
        val m = mesh
        val s = currentSettings
        return if (m != null && s != null) Snapshot(m, anim, s, morphPhase,
            rotationOffsetY, zoomDistance, rotationSpeed, speedMultiplier, backgroundColor,
            backgroundMode, backgroundTexturePattern, backgroundScrollX,
            backgroundScrollY, backgroundTextureScale, backgroundTextureDetailScale,
            backgroundContrastA, backgroundContrastB, backgroundTextureQuality,
            backgroundAntialias,
            backgroundHueA, backgroundHueB, backgroundSaturationA,
            backgroundSaturationB, backgroundBrightnessA, backgroundBrightnessB) else null
    }

    fun backgroundTextureSize(): Int = when (backgroundTextureQuality) {
        1 -> 512
        2 -> 1024
        else -> TextureGenerator.SIZE
    }
    private var anim = randomAnim(Random(System.nanoTime()))
    private var autoInterval = 20f
    private var animTime = 0f
    // Rotation runs on its own clock so changing the rotation speed never
    // jumps the angle: it just changes how fast this clock ticks.
    private var rotationTime = 0f
    private var bornTime = 0f
    private var lastFrameNanos = 0L

    // Smooth-morph state. A morph never swaps instantly: the target is
    // generated once, then the shader blends into it over morphDuration
    // with a smoothstep ease, and only then is it committed as current.
    private var morphPhase = 0f
    private var morphTimer = 0f
    private var morphing = false
    private var morphProgress = 0f
    private var complexity = 0f
    private var targetComplexity = 0f

    private val projMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private val tmpMatrix = FloatArray(16)

    fun randomAnim(rnd: Random): AnimParams {
        val mode = listOf("Spin", "Tumble", "Wobble", "Pulse", "Orbit", "Bounce").random(rnd)
        return AnimParams(
            mode = mode,
            rotX = (rnd.nextFloat() - 0.5f) * 90f,
            rotY = (rnd.nextFloat() - 0.5f) * 120f + if (mode == "Spin") 50f else 0f,
            rotZ = (rnd.nextFloat() - 0.5f) * 60f,
            wobbleAmp = if (mode == "Wobble") 0.08f + rnd.nextFloat() * 0.1f else 0.02f + rnd.nextFloat() * 0.05f,
            wobbleFreq = 1f + rnd.nextFloat() * 2.5f,
            pulseAmp = if (mode == "Pulse") 0.18f + rnd.nextFloat() * 0.15f else 0.05f + rnd.nextFloat() * 0.08f,
            bounceAmp = if (mode == "Bounce") 0.3f + rnd.nextFloat() * 0.3f else 0f,
            orbitRadius = if (mode == "Orbit") 0.5f + rnd.nextFloat() * 0.5f else 0f
        )
    }

    fun requestNewMesh() {
        val rnd = Random(System.nanoTime())
        var settings = MeshSettings.random(rnd, currentSettings, lockedKeys)
        palette?.let {
            settings = settings.copy(hueA = it.hueA, hueB = it.hueB)
            it.saturation?.let { sat -> settings = settings.copy(saturation = sat) }
        }
        currentSettings = settings
        // Born simple: complexity 0 is the bare base shape. Detail is added
        // in smooth steps by the morph loop below.
        complexity = 0f
        targetComplexity = 0f
        morphPhase = 0f
        morphTimer = 0f
        morphing = false
        morphProgress = 0f
        mesh = MeshGenerator.generate(settings, 0f, 0f)
        anim = randomAnim(rnd)
        autoInterval = settings.holdSeconds
        bornTime = animTime
        uploadMesh(mesh!!)
        uploadMorphTarget(mesh!!)
        uploadTexture(settings)
        if (backgroundRandomize) randomizeBackground()
        uploadBackgroundTexture()
        val m = mesh!!
        val a = anim
        mainHandler.post { listener.onMeshChanged(m, a) }
    }

    /** Roll a fresh random background (pattern, colours, contrast, scroll).
     *  Quality (HQ flag) is a preference, not a look — it never randomizes.
     *  In solid mode it rolls a random dark solid colour instead. */
    fun randomizeBackground() { // GL thread
        val rnd = Random(System.nanoTime())
        if (backgroundMode == BG_SOLID) {
            val rgb = MeshGenerator.hsv(rnd.nextFloat(),
                0.5f + rnd.nextFloat() * 0.5f, 0.1f + rnd.nextFloat() * 0.2f)
            backgroundColor = (0xFF shl 24) or ((rgb[0] * 255).toInt() shl 16) or
                ((rgb[1] * 255).toInt() shl 8) or (rgb[2] * 255).toInt()
            return
        }
        backgroundTexturePattern = BACKGROUND_PATTERNS.random(rnd)
        backgroundHueA = rnd.nextFloat()
        backgroundHueB = ((backgroundHueA + (rnd.nextFloat() - 0.5f) * 0.4f) % 1f + 1f) % 1f
        backgroundSaturationA = 0.4f + rnd.nextFloat() * 0.6f
        backgroundSaturationB = 0.4f + rnd.nextFloat() * 0.6f
        backgroundBrightnessA = 0.5f + rnd.nextFloat() * 0.5f
        backgroundBrightnessB = 0.5f + rnd.nextFloat() * 0.5f
        backgroundContrastA = 0.5f + rnd.nextFloat()
        backgroundContrastB = 0.5f + rnd.nextFloat()
        backgroundTextureDetailScale = 0.5f + rnd.nextFloat() * 3.5f
        backgroundScrollX = (rnd.nextFloat() * 2f - 1f) * 0.75f
        backgroundScrollY = (rnd.nextFloat() * 2f - 1f) * 0.75f
        backgroundTextureScale = 0.5f + rnd.nextFloat() * 1.5f
        backgroundTextureDirty = true
    }

    /** Apply a palette: it sticks for every mesh from here on, and the mesh
     *  on screen recolours immediately — regenerated at its current
     *  complexity and morph phase, so its shape and growth don't restart.
     *  Null clears the palette (future meshes roll fresh hues); the current
     *  mesh keeps its colours. Must run on the GL thread. */
    fun applyPalette(p: Palette?) {
        palette = p
        if (p == null) return
        val s = currentSettings ?: return
        if (mesh == null) return
        var ns = s.copy(hueA = p.hueA, hueB = p.hueB)
        p.saturation?.let { ns = ns.copy(saturation = it) }
        currentSettings = ns
        mesh = MeshGenerator.generate(ns, morphPhase, complexity)
        uploadMesh(mesh!!)
        val target = MeshGenerator.generate(ns, morphPhase, targetComplexity)
        targetMeshData = target
        uploadMorphTarget(target)
        uploadTexture(ns)
        uploadBackgroundTexture()
        val m = mesh!!
        val a = anim
        mainHandler.post { listener.onMeshChanged(m, a) }
    }

    /** Generate the next state (more complexity, or a phase drift once the
     *  mesh is fully grown) and start blending into it. Nothing swaps. */
    private fun startMorph() {
        val s = currentSettings ?: return
        if (complexity < 1f) {
            targetComplexity = min(1f, complexity + s.complexityStep)
            morphPhase += 0.15f
        } else {
            targetComplexity = 1f
            morphPhase += 0.4f
        }
        val target = MeshGenerator.generate(s, morphPhase, targetComplexity)
        targetMeshData = target
        uploadMorphTarget(target)
        morphing = true
        morphProgress = 0f
        morphTimer = 0f
    }

    private fun commitMorph() {
        vertexBuffer = nextVertexBuffer
        normalBuffer = nextNormalBuffer
        colorBuffer = nextColorBuffer
        targetMeshData?.let { mesh = it }
        complexity = targetComplexity
        morphing = false
        morphProgress = 0f
        morphTimer = 0f
    }

    private fun uploadMesh(m: MeshData) {
        vertexBuffer = floatBuffer(m.vertices)
        normalBuffer = floatBuffer(m.normals)
        colorBuffer = floatBuffer(m.colors)
        uvBuffer = floatBuffer(m.uvs)
        extremityBuffer = floatBuffer(m.extremity)
        partBuffer = floatBuffer(m.part)
        val bb = ByteBuffer.allocateDirect(m.indices.size * 2).order(ByteOrder.nativeOrder())
        indexBuffer = bb.asShortBuffer()
        indexBuffer!!.put(m.indices)
        indexBuffer!!.position(0)
        indexCount = m.indices.size
    }

    private fun uploadMorphTarget(m: MeshData) {
        nextVertexBuffer = floatBuffer(m.vertices)
        nextNormalBuffer = floatBuffer(m.normals)
        nextColorBuffer = floatBuffer(m.colors)
    }

    private fun uploadTexture(s: MeshSettings) {
        if (textureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        textureId = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT)
        val bmp = TextureGenerator.generate(s)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        bmp.recycle()
    }

    /** Regenerate the background texture from the background's own
     *  settings with the chosen pattern. */
    fun applyBackgroundTexturePattern(pattern: String) { // GL thread
        backgroundTexturePattern = pattern
        uploadBackgroundTexture()
    }

    /** Copy the current mesh's palette into the background's own colours
     *  (a snapshot, not a link — later mesh rolls won't change it). */
    fun matchMeshPalette() {
        val s = currentSettings ?: return
        backgroundHueA = ((s.hueA % 1f) + 1f) % 1f
        backgroundHueB = ((s.hueB % 1f) + 1f) % 1f
        backgroundSaturationA = s.saturation
        backgroundSaturationB = s.saturation
        backgroundBrightnessA = 1f
        backgroundBrightnessB = 1f
        backgroundTextureDirty = true
    }

    private fun uploadBackgroundTexture() {
        val s = currentSettings ?: return
        backgroundTextureDirty = false
        if (!backgroundColorsInitialized) {
            backgroundColorsInitialized = true
            backgroundHueA = ((s.hueA % 1f) + 1f) % 1f
            backgroundHueB = ((s.hueB % 1f) + 1f) % 1f
            backgroundSaturationA = s.saturation
            backgroundSaturationB = s.saturation
            backgroundBrightnessA = 1f
            backgroundBrightnessB = 1f
        }
        if (backgroundTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(backgroundTextureId), 0)
            backgroundTextureId = 0
        }
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        backgroundTextureId = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, backgroundTextureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT)
        val bgSettings = s.copy(
            texturePattern = backgroundTexturePattern,
            textureScale = backgroundTextureDetailScale,
            noisePhase1 = 0f, noisePhase2 = 0f,
            hueA = backgroundHueA, hueB = backgroundHueB
        )
        val bmp = TextureGenerator.generate(
            bgSettings,
            size = backgroundTextureSize(),
            contrastA = backgroundContrastA, contrastB = backgroundContrastB,
            saturationA = backgroundSaturationA, saturationB = backgroundSaturationB,
            brightnessA = backgroundBrightnessA, brightnessB = backgroundBrightnessB,
            antialias = backgroundAntialias
        )
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        bmp.recycle()
    }

    /** Fullscreen textured quad behind the mesh, scrolling with time. */
    private fun drawBackground() {
        if (backgroundTextureId == 0 || bgProgram == 0) return
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)
        GLES20.glUseProgram(bgProgram)
        val ox = animTime * backgroundScrollX
        val oy = animTime * backgroundScrollY
        GLES20.glUniform2f(bgUOffset, ox, oy)
        GLES20.glUniform2f(bgUScale, viewportAspect * backgroundTextureScale, backgroundTextureScale)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, backgroundTextureId)
        GLES20.glUniform1i(bgUTexture, 0)
        GLES20.glEnableVertexAttribArray(bgAPosition)
        GLES20.glVertexAttribPointer(bgAPosition, 2, GLES20.GL_FLOAT, false, 0, bgVertexBuffer)
        GLES20.glEnableVertexAttribArray(bgAUV)
        GLES20.glVertexAttribPointer(bgAUV, 2, GLES20.GL_FLOAT, false, 0, bgUvBuffer)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, 6, GLES20.GL_UNSIGNED_SHORT, bgIndexBuffer)
        GLES20.glDisableVertexAttribArray(bgAPosition)
        GLES20.glDisableVertexAttribArray(bgAUV)
        GLES20.glDepthMask(true)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    private fun floatBuffer(data: FloatArray): FloatBuffer {
        val bb = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder())
        val fb = bb.asFloatBuffer()
        fb.put(data)
        fb.position(0)
        return fb
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.02f, 0.02f, 0.05f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        val vs = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fs = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aNextPosition = GLES20.glGetAttribLocation(program, "aNextPosition")
        aNormal = GLES20.glGetAttribLocation(program, "aNormal")
        aNextNormal = GLES20.glGetAttribLocation(program, "aNextNormal")
        aColor = GLES20.glGetAttribLocation(program, "aColor")
        aNextColor = GLES20.glGetAttribLocation(program, "aNextColor")
        aUV = GLES20.glGetAttribLocation(program, "aUV")
        aExtremity = GLES20.glGetAttribLocation(program, "aExtremity")
        aPart = GLES20.glGetAttribLocation(program, "aPart")
        uMvp = GLES20.glGetUniformLocation(program, "uMVP")
        uModel = GLES20.glGetUniformLocation(program, "uModel")
        uTime = GLES20.glGetUniformLocation(program, "uTime")
        uMorphMix = GLES20.glGetUniformLocation(program, "uMorphMix")
        uWobbleAmp = GLES20.glGetUniformLocation(program, "uWobbleAmp")
        uWobbleFreq = GLES20.glGetUniformLocation(program, "uWobbleFreq")
        uGrowth = GLES20.glGetUniformLocation(program, "uGrowth")
        uExtremityLength = GLES20.glGetUniformLocation(program, "uExtremityLength")
        uExtremityPulse = GLES20.glGetUniformLocation(program, "uExtremityPulse")
        uPartMoveAmount = GLES20.glGetUniformLocation(program, "uPartMoveAmount")
        uPartMoveSpeed = GLES20.glGetUniformLocation(program, "uPartMoveSpeed")
        uTexScroll = GLES20.glGetUniformLocation(program, "uTexScroll")
        uAmbient = GLES20.glGetUniformLocation(program, "uAmbient")
        uSpotIntensity = GLES20.glGetUniformLocation(program, "uSpotIntensity")
        uTexture = GLES20.glGetUniformLocation(program, "uTexture")
        val bgVs = loadShader(GLES20.GL_VERTEX_SHADER, BG_VERTEX_SHADER)
        val bgFs = loadShader(GLES20.GL_FRAGMENT_SHADER, BG_FRAGMENT_SHADER)
        bgProgram = GLES20.glCreateProgram()
        GLES20.glAttachShader(bgProgram, bgVs)
        GLES20.glAttachShader(bgProgram, bgFs)
        GLES20.glLinkProgram(bgProgram)
        bgAPosition = GLES20.glGetAttribLocation(bgProgram, "aPosition")
        bgAUV = GLES20.glGetAttribLocation(bgProgram, "aUV")
        bgUOffset = GLES20.glGetUniformLocation(bgProgram, "uOffset")
        bgUScale = GLES20.glGetUniformLocation(bgProgram, "uScale")
        bgUTexture = GLES20.glGetUniformLocation(bgProgram, "uTexture")
        if (mesh == null) {
            requestNewMesh()
        } else {
            textureId = 0
            backgroundTextureId = 0
            currentSettings?.let { uploadTexture(it); uploadBackgroundTexture() }
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val ratio = width.toFloat() / height.toFloat()
        viewportAspect = ratio
        Matrix.frustumM(projMatrix, 0, -ratio, ratio, -1f, 1f, 1f, 20f)
        Matrix.setLookAtM(viewMatrix, 0, 0f, 0.4f, 4.4f, 0f, 0f, 0f, 0f, 1f, 0f)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        if (lastFrameNanos == 0L) lastFrameNanos = now
        val dt = ((now - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.1f)
        lastFrameNanos = now
        if (!paused) animTime += dt * speedMultiplier
        if (!paused) rotationTime += dt * speedMultiplier * rotationSpeed

        val m = mesh
        val s = currentSettings
        if (m == null || s == null) return

        // Colour sliders only raise this flag; regenerate at most once
        // per frame so a fast drag can't queue up texture builds.
        if (backgroundTextureDirty) {
            backgroundTextureDirty = false
            uploadBackgroundTexture()
        }

        // Smooth morph loop: while the mesh is still growing, start the next
        // step almost as soon as the last one lands; once fully grown,
        // wait morphIntervalSeconds between phase-drift morphs.
        if (!paused) {
            if (morphing) {
                morphProgress += dt * speedMultiplier / s.morphDurationSeconds
                if (morphProgress >= 1f) commitMorph()
            } else {
                morphTimer += dt * speedMultiplier
                val gap = if (complexity < 1f) 0.35f else s.morphIntervalSeconds
                if (morphTimer >= gap) startMorph()
            }
        }
        val mixRaw = if (morphing) morphProgress.coerceIn(0f, 1f) else 0f
        val morphMix = mixRaw * mixRaw * (3f - 2f * mixRaw) // smoothstep

        if (autoChange && !paused && animTime - bornTime > autoInterval) {
            requestNewMesh()
            return
        }

        val bg = backgroundColor
        GLES20.glClearColor(Color.red(bg) / 255f, Color.green(bg) / 255f, Color.blue(bg) / 255f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        if (backgroundMode == BG_TEXTURE) drawBackground()
        GLES20.glUseProgram(program)

        val age = animTime - bornTime
        val spawn = min(1f, age / 0.6f)
        val ease = 1f - (1f - spawn) * (1f - spawn)
        val pulse = 1f + anim.pulseAmp * sin(animTime * 2.2f)
        val scale = (0.2f + 0.8f * ease) * pulse
        val bounceY = if (anim.bounceAmp > 0f) abs(sin(animTime * 1.8f)) * anim.bounceAmp else 0f
        val orbitX = anim.orbitRadius * cos(animTime * 0.45f)
        val orbitZ = anim.orbitRadius * sin(animTime * 0.45f) * 0.5f

        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, orbitX, bounceY, orbitZ)
        Matrix.rotateM(modelMatrix, 0, rotationOffsetY, 0f, 1f, 0f)
        Matrix.rotateM(modelMatrix, 0, anim.rotX * rotationTime, 1f, 0f, 0f)
        Matrix.rotateM(modelMatrix, 0, anim.rotY * rotationTime, 0f, 1f, 0f)
        Matrix.rotateM(modelMatrix, 0, anim.rotZ * rotationTime, 0f, 0f, 1f)
        Matrix.scaleM(modelMatrix, 0, scale, scale, scale)

        Matrix.setLookAtM(viewMatrix, 0, 0f, 0.4f, zoomDistance, 0f, 0f, 0f, 0f, 1f, 0f)
        Matrix.multiplyMM(tmpMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projMatrix, 0, tmpMatrix, 0)

        val growRaw = (age / s.extremityGrowthSeconds).coerceIn(0f, 1f)
        val growth = growRaw * growRaw * (3f - 2f * growRaw)

        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(uModel, 1, false, modelMatrix, 0)
        GLES20.glUniform1f(uTime, animTime)
        GLES20.glUniform1f(uMorphMix, morphMix)
        GLES20.glUniform1f(uWobbleAmp, anim.wobbleAmp)
        GLES20.glUniform1f(uWobbleFreq, anim.wobbleFreq)
        GLES20.glUniform1f(uGrowth, growth)
        GLES20.glUniform1f(uExtremityLength, s.extremityLength)
        GLES20.glUniform1f(uExtremityPulse, s.extremityPulse)
        GLES20.glUniform1f(uPartMoveAmount, s.partMoveAmount)
        GLES20.glUniform1f(uPartMoveSpeed, s.partMoveSpeed)
        GLES20.glUniform1f(uTexScroll, s.textureScrollSpeed)
        GLES20.glUniform1f(uAmbient, s.ambientLevel)
        GLES20.glUniform1f(uSpotIntensity, s.spotlightIntensity)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(uTexture, 0)

        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(aNextPosition)
        GLES20.glVertexAttribPointer(aNextPosition, 3, GLES20.GL_FLOAT, false, 0, nextVertexBuffer)
        GLES20.glEnableVertexAttribArray(aNormal)
        GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, 0, normalBuffer)
        GLES20.glEnableVertexAttribArray(aNextNormal)
        GLES20.glVertexAttribPointer(aNextNormal, 3, GLES20.GL_FLOAT, false, 0, nextNormalBuffer)
        GLES20.glEnableVertexAttribArray(aColor)
        GLES20.glVertexAttribPointer(aColor, 3, GLES20.GL_FLOAT, false, 0, colorBuffer)
        GLES20.glEnableVertexAttribArray(aNextColor)
        GLES20.glVertexAttribPointer(aNextColor, 3, GLES20.GL_FLOAT, false, 0, nextColorBuffer)
        GLES20.glEnableVertexAttribArray(aUV)
        GLES20.glVertexAttribPointer(aUV, 2, GLES20.GL_FLOAT, false, 0, uvBuffer)
        GLES20.glEnableVertexAttribArray(aExtremity)
        GLES20.glVertexAttribPointer(aExtremity, 1, GLES20.GL_FLOAT, false, 0, extremityBuffer)
        GLES20.glEnableVertexAttribArray(aPart)
        GLES20.glVertexAttribPointer(aPart, 1, GLES20.GL_FLOAT, false, 0, partBuffer)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, indexBuffer)
        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aNextPosition)
        GLES20.glDisableVertexAttribArray(aNormal)
        GLES20.glDisableVertexAttribArray(aNextNormal)
        GLES20.glDisableVertexAttribArray(aColor)
        GLES20.glDisableVertexAttribArray(aNextColor)
        GLES20.glDisableVertexAttribArray(aUV)
        GLES20.glDisableVertexAttribArray(aExtremity)
        GLES20.glDisableVertexAttribArray(aPart)
    }

    private fun loadShader(type: Int, code: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, code)
        GLES20.glCompileShader(shader)
        return shader
    }

    companion object {
        const val BG_SOLID = 0
        const val BG_TEXTURE = 1
        val BACKGROUND_PATTERNS = listOf("Marble", "Stripes", "Checker", "Speckle",
            "Gradient", "Camo", "Waves", "Rings", "Dots", "Grid", "Bricks", "Diamond")
        /** Fullscreen quad behind the mesh, for a scrolling texture background. */
        const val BG_VERTEX_SHADER = """
            attribute vec2 aPosition;
            attribute vec2 aUV;
            varying vec2 vUV;
            void main() { gl_Position = vec4(aPosition, 0.0, 1.0); vUV = aUV; }
        """
        const val BG_FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uTexture;
            uniform vec2 uOffset;
            uniform vec2 uScale;
            varying vec2 vUV;
            void main() { gl_FragColor = texture2D(uTexture, vUV * uScale + uOffset); }
        """
        const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec3 aNextPosition;
            attribute vec3 aNormal;
            attribute vec3 aNextNormal;
            attribute vec3 aColor;
            attribute vec3 aNextColor;
            attribute vec2 aUV;
            attribute float aExtremity;
            attribute float aPart;
            uniform mat4 uMVP;
            uniform mat4 uModel;
            uniform float uTime;
            uniform float uMorphMix;
            uniform float uWobbleAmp;
            uniform float uWobbleFreq;
            uniform float uGrowth;
            uniform float uExtremityLength;
            uniform float uExtremityPulse;
            uniform float uPartMoveAmount;
            uniform float uPartMoveSpeed;
            uniform float uTexScroll;
            varying vec3 vColor;
            varying vec3 vNormal;
            varying vec3 vWorldPos;
            varying vec2 vUV;
            void main() {
                // Smooth morph: blend position, normal and colour into the
                // next generated state. uMorphMix eases 0 -> 1, never jumps.
                vec3 pos = mix(aPosition.xyz, aNextPosition, uMorphMix);
                vec3 baseNormal = normalize(mix(aNormal, aNextNormal, uMorphMix));
                vec3 baseColor = mix(aColor, aNextColor, uMorphMix);
                // Moving parts: each angular sector rotates / bobs on its own phase.
                float partAngle = uPartMoveAmount * sin(uTime * uPartMoveSpeed + aPart * 1.7);
                float ca = cos(partAngle);
                float sa = sin(partAngle);
                pos = vec3(pos.x * ca - pos.z * sa,
                           pos.y + uPartMoveAmount * 0.4 * sin(uTime * uPartMoveSpeed + aPart * 2.1),
                           pos.x * sa + pos.z * ca);
                // Vertex wobble along the normal.
                float w = sin(dot(pos, vec3(2.0, 2.0, 2.0)) * uWobbleFreq + uTime * 2.0) * uWobbleAmp;
                pos += baseNormal * w;
                // Growing extremities: grow outward along the normal as uGrowth rises.
                float grow = uGrowth * (1.0 + uExtremityPulse * sin(uTime * 1.6));
                pos += baseNormal * aExtremity * uExtremityLength * 0.7 * grow;
                vec4 world = uModel * vec4(pos, 1.0);
                vWorldPos = world.xyz;
                vNormal = normalize((uModel * vec4(baseNormal, 0.0)).xyz);
                vColor = baseColor;
                vUV = aUV + vec2(uTime * uTexScroll, uTime * uTexScroll * 0.5);
                gl_Position = uMVP * vec4(pos, 1.0);
            }
        """
        const val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec3 vColor;
            varying vec3 vNormal;
            varying vec3 vWorldPos;
            varying vec2 vUV;
            uniform sampler2D uTexture;
            uniform float uAmbient;
            uniform float uSpotIntensity;
            void main() {
                vec3 tex = texture2D(uTexture, fract(vUV)).rgb;
                vec3 base = mix(vColor, tex, 0.55);
                vec3 N = normalize(vNormal);
                vec3 L1 = normalize(vec3(0.5, 0.8, 0.6));
                float diff1 = max(dot(N, L1), 0.0);
                vec3 spotPos = vec3(1.2, 2.5, 3.5);
                vec3 toSpot = spotPos - vWorldPos;
                float dist = length(toSpot);
                vec3 Ls = toSpot / dist;
                float diffSpot = max(dot(N, Ls), 0.0);
                float atten = 1.0 / (1.0 + 0.18 * dist * dist);
                vec3 V = normalize(vec3(0.0, 0.4, 4.4) - vWorldPos);
                vec3 H = normalize(Ls + V);
                float spec = pow(max(dot(N, H), 0.0), 24.0) * 0.5 * atten;
                float light = uAmbient + 0.4 * diff1 + diffSpot * atten * uSpotIntensity;
                vec3 col = base * light + vec3(spec);
                gl_FragColor = vec4(col, 1.0);
            }
        """
    }
}
