package com.robert.randommesh

import android.content.ContentValues
import android.content.Context
import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.EGLExt
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Renders the current mesh as a high-resolution portrait video (1080x1920,
 *  30 fps, H.264 MP4) to Downloads/RandomMesh, for a user-chosen length.
 *  Runs on its own thread with its own EGL context (recordable surface from
 *  the encoder), reusing the app's shaders and the mesh's CPU-side data, so
 *  it never touches the live GLSurfaceView. Returns the display path, or
 *  null on failure. Call from a background thread. */
object VideoExporter {
    const val WIDTH = 1080
    const val HEIGHT = 1920
    const val FPS = 30
    const val DEFAULT_LENGTH_SEC = 6
    private const val BITRATE = 20_000_000
    private const val TAG = "VideoExporter"

    /** The exact failure from the last export, for the toast / error file. */
    var lastError: String? = null

    private fun fail(msg: String): String? {
        lastError = msg
        Log.e(TAG, msg)
        return null
    }

    fun export(context: Context, snap: MeshRenderer.Snapshot, durationSec: Int = DEFAULT_LENGTH_SEC): String? {
        lastError = null
        val lengthSec = durationSec.coerceAtLeast(1)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val base = "RandomMesh_" + snap.mesh.name.replace(" ", "") + "_" + stamp + "_" + lengthSec + "s.mp4"
        var pfd: ParcelFileDescriptor? = null
        var videoUri: android.net.Uri? = null
        var success = false
        var muxer: MediaMuxer? = null
        var encoder: MediaCodec? = null
        var display: EGLDisplay? = null
        var eglContext: EGLContext? = null
        var eglSurface: EGLSurface? = null
        try {
            // --- Output file: Downloads collection, like the mesh save.
            // v1.4 used the Video collection with a Download/ relative path,
            // which MediaStore rejects — that is what killed the export.
            // Marked pending until the muxer finishes, deleted on failure,
            // so no partial file lingers.
            val muxerPath: String?
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues()
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, base)
                values.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/RandomMesh")
                values.put(MediaStore.MediaColumns.IS_PENDING, 1)
                videoUri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return fail("MediaStore insert returned null")
                pfd = context.contentResolver.openFileDescriptor(videoUri!!, "w")
                    ?: return fail("Could not open output file")
                muxer = MediaMuxer(pfd!!.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                muxerPath = null
            } else {
                val dir = File(context.getExternalFilesDir(null), "RandomMesh")
                dir.mkdirs()
                val file = File(dir, base)
                muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                muxerPath = file.absolutePath
            }

            // --- Encoder ---
            // A scrolling high-detail texture is worst-case content for an
            // encoder: every pixel moves every frame, so temporal prediction
            // barely helps, and a flat-rate Baseline encode starves it —
            // the picture slowly macroblocks over minutes. High profile
            // (CABAC + 8x8 transforms) and VBR, which spends bits where the
            // scene needs them instead of clamping to a flat rate, hold the
            // quality steady for the whole video.
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            format.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
            format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel42)
            format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            try {
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            } catch (e: Exception) {
                // Older encoders may not take High profile + VBR: fall back
                // to the plain Baseline settings rather than failing.
                Log.w(TAG, "High profile configure failed, falling back", e)
                val fallback = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT)
                fallback.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                fallback.setInteger(MediaFormat.KEY_BIT_RATE, 12_000_000)
                fallback.setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                fallback.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                encoder.configure(fallback, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            val inputSurface = encoder.createInputSurface()
            encoder.start()

            // --- EGL on the encoder's input surface ---
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return fail("No EGL display")
            val version = IntArray(2)
            EGL14.eglInitialize(display, version, 0, version, 1)
            val config = chooseConfig(display) ?: return fail("No recordable EGL config")
            val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            eglContext = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
            if (eglContext == EGL14.EGL_NO_CONTEXT) return fail("Could not create EGL context")
            eglSurface = EGL14.eglCreateWindowSurface(display, config, inputSurface, intArrayOf(EGL14.EGL_NONE), 0)
            if (eglSurface == EGL14.EGL_NO_SURFACE) return fail("Could not create EGL window surface")
            if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, eglContext)) return fail("Could not make EGL current")

            // --- GL program, buffers, texture (same shaders as the live view) ---
            val program = buildProgram()
            val loc = Locations(program)
            val s = snap.settings
            val a = snap.anim
            // The video starts the way the mesh started: the bare base shape
            // (complexity 0), then grows into the full mesh (complexity 1)
            // at the same noise phase, so the shader can blend between them
            // exactly like a live morph. Both generations share one topology
            // (vertex count depends on settings only), so the blend is safe;
            // if they ever disagreed, fall back to the snapshot mesh for both.
            val startMesh = MeshGenerator.generate(s, snap.morphPhase, 0f)
            val endMesh = MeshGenerator.generate(s, snap.morphPhase, 1f)
            val sameTopology = startMesh.vertexCount == endMesh.vertexCount
                    && endMesh.vertexCount == snap.mesh.vertexCount
            val fromMesh = if (sameTopology) startMesh else snap.mesh
            val toMesh = if (sameTopology) endMesh else snap.mesh
            val mesh = toMesh
            val vertexBuffer = floatBuffer(fromMesh.vertices)
            val nextVertexBuffer = floatBuffer(toMesh.vertices)
            val normalBuffer = floatBuffer(fromMesh.normals)
            val nextNormalBuffer = floatBuffer(toMesh.normals)
            val colorBuffer = floatBuffer(fromMesh.colors)
            val nextColorBuffer = floatBuffer(toMesh.colors)
            val uvBuffer = floatBuffer(mesh.uvs)
            val extremityBuffer = floatBuffer(mesh.extremity)
            val partBuffer = floatBuffer(mesh.part)
            val indexBuffer = ByteBuffer.allocateDirect(mesh.indices.size * 2)
                .order(ByteOrder.nativeOrder()).asShortBuffer().apply { put(mesh.indices); position(0) }
            val textureId = uploadTexture(s)
            // Scrolling texture background: same quad, texture and scroll
            // math as the live view, so the video background matches.
            val bgProgram = buildBgProgram()
            val bgAPosition = GLES20.glGetAttribLocation(bgProgram, "aPosition")
            val bgAUV = GLES20.glGetAttribLocation(bgProgram, "aUV")
            val bgUOffset = GLES20.glGetUniformLocation(bgProgram, "uOffset")
            val bgUScale = GLES20.glGetUniformLocation(bgProgram, "uScale")
            val bgUTexture = GLES20.glGetUniformLocation(bgProgram, "uTexture")
            val bgSettings = snap.settings.copy(
                texturePattern = snap.backgroundTexturePattern,
                textureScale = snap.backgroundTextureDetailScale,
                noisePhase1 = 0f, noisePhase2 = 0f,
                hueA = snap.backgroundHueA, hueB = snap.backgroundHueB
            )
            val bgSize = when (snap.backgroundTextureQuality) {
                1 -> 512
                2 -> 1024
                else -> TextureGenerator.SIZE
            }
            val bgTextureId = uploadTexture(
                bgSettings,
                size = bgSize,
                contrastA = snap.backgroundContrastA, contrastB = snap.backgroundContrastB,
                saturationA = snap.backgroundSaturationA, saturationB = snap.backgroundSaturationB,
                brightnessA = snap.backgroundBrightnessA, brightnessB = snap.backgroundBrightnessB,
                antialias = snap.backgroundAntialias
            )
            val bgVertexBuffer = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
            val bgUvBuffer = floatBuffer(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
            val bgIndexBuffer = ByteBuffer.allocateDirect(6 * 2).order(ByteOrder.nativeOrder())
                .asShortBuffer().apply { put(shortArrayOf(0, 1, 2, 2, 1, 3)); position(0) }
            // How long the growth takes on screen: one morph per complexity
            // step, each lasting morphDuration + the 0.35s gap the live loop
            // waits before the next step. Short videos compress it so the
            // growth still finishes (at most 3/4 of the video).
            val growthSteps = ceil(1f / s.complexityStep).toInt().coerceAtLeast(1)
            val appGrowthTime = growthSteps * (s.morphDurationSeconds + 0.35f)
            val growthTime = min(appGrowthTime, lengthSec * 0.75f).coerceAtLeast(0.5f)

            val proj = FloatArray(16)
            val view = FloatArray(16)
            val model = FloatArray(16)
            val tmp = FloatArray(16)
            val mvp = FloatArray(16)
            val ratio = WIDTH.toFloat() / HEIGHT.toFloat()
            Matrix.frustumM(proj, 0, -ratio, ratio, -1f, 1f, 1f, 20f)
            Matrix.setLookAtM(view, 0, 0f, 0.4f, snap.zoomDistance, 0f, 0f, 0f, 0f, 1f, 0f)

            // --- Frame loop ---
            drainTrack = -1
            val totalFrames = FPS * lengthSec
            val bufferInfo = MediaCodec.BufferInfo()
            for (frame in 0 until totalFrames) {
                val t = frame.toFloat() / FPS
                // Video time in the same units as the live view's animTime:
                // the animation-speed slider scales everything, and the
                // rotation-speed slider scales only the rotation.
                val vt = t * snap.speedMultiplier
                GLES20.glViewport(0, 0, WIDTH, HEIGHT)
                val bg = snap.backgroundColor
                GLES20.glClearColor(Color.red(bg) / 255f, Color.green(bg) / 255f, Color.blue(bg) / 255f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
                if (snap.backgroundMode == MeshRenderer.BG_TEXTURE) {
                    GLES20.glDisable(GLES20.GL_DEPTH_TEST)
                    GLES20.glDepthMask(false)
                    GLES20.glUseProgram(bgProgram)
                    val ox = vt * snap.backgroundScrollX
                    val oy = vt * snap.backgroundScrollY
                    GLES20.glUniform2f(bgUOffset, ox, oy)
                    GLES20.glUniform2f(bgUScale, ratio * snap.backgroundTextureScale, snap.backgroundTextureScale)
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bgTextureId)
                    GLES20.glUniform1i(bgUTexture, 0)
                    bindAttrib(bgAPosition, 2, bgVertexBuffer)
                    bindAttrib(bgAUV, 2, bgUvBuffer)
                    GLES20.glDrawElements(GLES20.GL_TRIANGLES, 6, GLES20.GL_UNSIGNED_SHORT, bgIndexBuffer)
                    GLES20.glDisableVertexAttribArray(bgAPosition)
                    GLES20.glDisableVertexAttribArray(bgAUV)
                    GLES20.glDepthMask(true)
                    GLES20.glEnable(GLES20.GL_DEPTH_TEST)
                }
                GLES20.glEnable(GLES20.GL_DEPTH_TEST)
                GLES20.glUseProgram(program)

                val spawn = min(1f, vt / 0.6f)
                val ease = 1f - (1f - spawn) * (1f - spawn)
                val pulse = 1f + a.pulseAmp * sin(vt * 2.2f)
                val scale = (0.2f + 0.8f * ease) * pulse
                val bounceY = if (a.bounceAmp > 0f) abs(sin(vt * 1.8f)) * a.bounceAmp else 0f
                Matrix.setIdentityM(model, 0)
                Matrix.translateM(model, 0, a.orbitRadius * cos(vt * 0.45f), bounceY, a.orbitRadius * sin(vt * 0.45f) * 0.5f)
                Matrix.rotateM(model, 0, snap.rotationOffsetY, 0f, 1f, 0f)
                Matrix.rotateM(model, 0, a.rotX * vt * snap.rotationSpeed, 1f, 0f, 0f)
                Matrix.rotateM(model, 0, a.rotY * vt * snap.rotationSpeed, 0f, 1f, 0f)
                Matrix.rotateM(model, 0, a.rotZ * vt * snap.rotationSpeed, 0f, 0f, 1f)
                Matrix.scaleM(model, 0, scale, scale, scale)
                Matrix.multiplyMM(tmp, 0, view, 0, model, 0)
                Matrix.multiplyMM(mvp, 0, proj, 0, tmp, 0)

                val growRaw = (vt / s.extremityGrowthSeconds).coerceIn(0f, 1f)
                val growth = growRaw * growRaw * (3f - 2f * growRaw)
                // Complexity ramp: 0 (bare base shape) -> 1 (full mesh),
                // smoothstep-eased over the growth time, then held.
                val mixRaw = (vt / growthTime).coerceIn(0f, 1f)
                val morphMix = mixRaw * mixRaw * (3f - 2f * mixRaw)

                GLES20.glUniformMatrix4fv(loc.uMvp, 1, false, mvp, 0)
                GLES20.glUniformMatrix4fv(loc.uModel, 1, false, model, 0)
                GLES20.glUniform1f(loc.uTime, vt)
                GLES20.glUniform1f(loc.uMorphMix, morphMix)
                GLES20.glUniform1f(loc.uWobbleAmp, a.wobbleAmp)
                GLES20.glUniform1f(loc.uWobbleFreq, a.wobbleFreq)
                GLES20.glUniform1f(loc.uGrowth, growth)
                GLES20.glUniform1f(loc.uExtremityLength, s.extremityLength)
                GLES20.glUniform1f(loc.uExtremityPulse, s.extremityPulse)
                GLES20.glUniform1f(loc.uPartMoveAmount, s.partMoveAmount)
                GLES20.glUniform1f(loc.uPartMoveSpeed, s.partMoveSpeed)
                GLES20.glUniform1f(loc.uTexScroll, s.textureScrollSpeed)
                GLES20.glUniform1f(loc.uAmbient, s.ambientLevel)
                GLES20.glUniform1f(loc.uSpotIntensity, s.spotlightIntensity)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
                GLES20.glUniform1i(loc.uTexture, 0)

                bindAttrib(loc.aPosition, 3, vertexBuffer)
                bindAttrib(loc.aNextPosition, 3, nextVertexBuffer)
                bindAttrib(loc.aNormal, 3, normalBuffer)
                bindAttrib(loc.aNextNormal, 3, nextNormalBuffer)
                bindAttrib(loc.aColor, 3, colorBuffer)
                bindAttrib(loc.aNextColor, 3, nextColorBuffer)
                bindAttrib(loc.aUV, 2, uvBuffer)
                bindAttrib(loc.aExtremity, 1, extremityBuffer)
                bindAttrib(loc.aPart, 1, partBuffer)
                GLES20.glDrawElements(GLES20.GL_TRIANGLES, mesh.indices.size, GLES20.GL_UNSIGNED_SHORT, indexBuffer)

                EGLExt.eglPresentationTimeANDROID(display, eglSurface, frame * 1_000_000_000L / FPS)
                EGL14.eglSwapBuffers(display, eglSurface)

                // Drain whatever the encoder has finished.
                drain(encoder, muxer, bufferInfo)
            }

            encoder.signalEndOfInputStream()
            while (!drain(encoder, muxer, bufferInfo)) {
                // Drain until the encoder reports end-of-stream.
            }
            success = true
            return if (muxerPath != null) muxerPath else "Download/RandomMesh/$base"
        } catch (e: Exception) {
            Log.e(TAG, "Video export failed", e)
            lastError = e.toString()
            try {
                MeshSaver.writeFile(context, "video_export_error.txt", "text/plain",
                    ("Video export failed\n" + e.toString() + "\n" + Log.getStackTraceString(e)).toByteArray(Charsets.UTF_8))
            } catch (_: Exception) {}
            return null
        } finally {
            try { muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            try { encoder?.stop() } catch (_: Exception) {}
            try { encoder?.release() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
            if (videoUri != null) {
                try {
                    if (success) {
                        val clear = ContentValues()
                        clear.put(MediaStore.MediaColumns.IS_PENDING, 0)
                        context.contentResolver.update(videoUri!!, clear, null, null)
                    } else {
                        context.contentResolver.delete(videoUri!!, null, null)
                    }
                } catch (_: Exception) {}
            }
            if (display != null && display != EGL14.EGL_NO_DISPLAY) {
                if (eglSurface != null && eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
                if (eglContext != null && eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, eglContext)
                EGL14.eglTerminate(display)
            }
        }
    }

    // Set by drain() when the encoder reports its output format.
    private var drainTrack = -1

    /** Drains all finished encoder output into the muxer. Returns true
     *  when the encoder reports end-of-stream. */
    private fun drain(encoder: MediaCodec, muxer: MediaMuxer, info: MediaCodec.BufferInfo): Boolean {
        var eos = false
        while (true) {
            val index = encoder.dequeueOutputBuffer(info, 10_000)
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) break
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                drainTrack = muxer.addTrack(encoder.outputFormat)
                muxer.start()
                continue
            }
            if (index < 0) break
            val buffer = encoder.getOutputBuffer(index)
            if (buffer != null && info.size > 0 && drainTrack >= 0) {
                buffer.position(info.offset)
                buffer.limit(info.offset + info.size)
                muxer.writeSampleData(drainTrack, buffer, info)
            }
            encoder.releaseOutputBuffer(index, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { eos = true; break }
        }
        return eos
    }

    private class Locations(program: Int) {
        val aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        val aNextPosition = GLES20.glGetAttribLocation(program, "aNextPosition")
        val aNormal = GLES20.glGetAttribLocation(program, "aNormal")
        val aNextNormal = GLES20.glGetAttribLocation(program, "aNextNormal")
        val aColor = GLES20.glGetAttribLocation(program, "aColor")
        val aNextColor = GLES20.glGetAttribLocation(program, "aNextColor")
        val aUV = GLES20.glGetAttribLocation(program, "aUV")
        val aExtremity = GLES20.glGetAttribLocation(program, "aExtremity")
        val aPart = GLES20.glGetAttribLocation(program, "aPart")
        val uMvp = GLES20.glGetUniformLocation(program, "uMVP")
        val uModel = GLES20.glGetUniformLocation(program, "uModel")
        val uTime = GLES20.glGetUniformLocation(program, "uTime")
        val uMorphMix = GLES20.glGetUniformLocation(program, "uMorphMix")
        val uWobbleAmp = GLES20.glGetUniformLocation(program, "uWobbleAmp")
        val uWobbleFreq = GLES20.glGetUniformLocation(program, "uWobbleFreq")
        val uGrowth = GLES20.glGetUniformLocation(program, "uGrowth")
        val uExtremityLength = GLES20.glGetUniformLocation(program, "uExtremityLength")
        val uExtremityPulse = GLES20.glGetUniformLocation(program, "uExtremityPulse")
        val uPartMoveAmount = GLES20.glGetUniformLocation(program, "uPartMoveAmount")
        val uPartMoveSpeed = GLES20.glGetUniformLocation(program, "uPartMoveSpeed")
        val uTexScroll = GLES20.glGetUniformLocation(program, "uTexScroll")
        val uAmbient = GLES20.glGetUniformLocation(program, "uAmbient")
        val uSpotIntensity = GLES20.glGetUniformLocation(program, "uSpotIntensity")
        val uTexture = GLES20.glGetUniformLocation(program, "uTexture")
    }

    private fun bindAttrib(location: Int, size: Int, buffer: java.nio.FloatBuffer) {
        if (location < 0) return
        GLES20.glEnableVertexAttribArray(location)
        GLES20.glVertexAttribPointer(location, size, GLES20.GL_FLOAT, false, 0, buffer)
    }

    private fun floatBuffer(data: FloatArray): java.nio.FloatBuffer {
        val bb = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder())
        val fb = bb.asFloatBuffer()
        fb.put(data)
        fb.position(0)
        return fb
    }

    private fun uploadTexture(
        s: MeshSettings, brightnessScale: Float = 1f, size: Int = TextureGenerator.SIZE,
        contrastA: Float? = null, contrastB: Float? = null,
        saturationA: Float? = null, saturationB: Float? = null,
        brightnessA: Float? = null, brightnessB: Float? = null,
        antialias: Boolean = false
    ): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT)
        val bmp = TextureGenerator.generate(s, brightnessScale, size, contrastA, contrastB,
            saturationA, saturationB, brightnessA, brightnessB, antialias)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        bmp.recycle()
        return ids[0]
    }

    private fun buildProgram(): Int {
        val vs = loadShader(GLES20.GL_VERTEX_SHADER, MeshRenderer.VERTEX_SHADER)
        val fs = loadShader(GLES20.GL_FRAGMENT_SHADER, MeshRenderer.FRAGMENT_SHADER)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        return program
    }

    private fun buildBgProgram(): Int {
        val vs = loadShader(GLES20.GL_VERTEX_SHADER, MeshRenderer.BG_VERTEX_SHADER)
        val fs = loadShader(GLES20.GL_FRAGMENT_SHADER, MeshRenderer.BG_FRAGMENT_SHADER)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        return program
    }

    private fun loadShader(type: Int, code: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, code)
        GLES20.glCompileShader(shader)
        return shader
    }

    private fun chooseConfig(display: EGLDisplay): EGLConfig? {
        val attribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, 4, // EGL_OPENGL_ES2_BIT
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 16, // Without a depth buffer the depth test
            // has nothing to test against, and hidden triangles draw through
            // the ones in front of them.
            0x3142, 1, // EGL_RECORDABLE_ANDROID
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0)) return null
        return if (count[0] > 0) configs[0] else null
    }
}
