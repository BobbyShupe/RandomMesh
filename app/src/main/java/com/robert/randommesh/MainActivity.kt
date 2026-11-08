package com.robert.randommesh

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity(), MeshRenderer.Listener {

    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: MeshRenderer
    private lateinit var meshName: TextView
    private lateinit var meshInfo: TextView
    private lateinit var animInfo: TextView
    private lateinit var btnPause: Button
    private lateinit var btnAuto: Button
    private lateinit var btnSettings: Button
    private lateinit var btnSave: Button
    private lateinit var btnVideo: Button
    private lateinit var btnPalette: Button
    private lateinit var btnVideoLength: Button
    private var videoLengthSec = VideoExporter.DEFAULT_LENGTH_SEC
    private lateinit var speedValue: TextView
    private lateinit var rotationValue: TextView
    private lateinit var zoomValue: TextView
    private lateinit var rotSpeedValue: TextView
    private lateinit var settingsPanel: ScrollView
    private lateinit var settingsList: LinearLayout
    private lateinit var settingsTitle: TextView

    private var isPaused = false
    private var autoOn = true
    private var currentSettings: MeshSettings? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Edge-to-edge (enforced at targetSdk 35) draws behind the system
        // bars, so the top header and the bottom controls would sit under
        // the status / navigation bar. Pad them clear with the live insets
        // instead: the GL view stays fullscreen behind the bars, the
        // controls sit above them. The settings panel's bottom margin grows
        // by the same amount so it still clears the taller bottom panel.
        val density = resources.displayMetrics.density
        val topBase = (16 * density).toInt()
        val bottomBase = (12 * density).toInt()
        val settingsMarginBase = (180 * density).toInt()
        val topPanel = findViewById<LinearLayout>(R.id.topPanel)
        val bottomPanel = findViewById<LinearLayout>(R.id.bottomPanel)
        val settingsPanelView = findViewById<ScrollView>(R.id.settingsPanel)
        ViewCompat.setOnApplyWindowInsetsListener(bottomPanel) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight,
                bottomBase + bars.bottom)
            topPanel.setPadding(topPanel.paddingLeft, topBase + bars.top,
                topPanel.paddingRight, topPanel.paddingBottom)
            val params = settingsPanelView.layoutParams as FrameLayout.LayoutParams
            params.bottomMargin = settingsMarginBase + bars.bottom
            settingsPanelView.layoutParams = params
            insets
        }

        meshName = findViewById(R.id.meshName)
        meshInfo = findViewById(R.id.meshInfo)
        animInfo = findViewById(R.id.animInfo)
        btnPause = findViewById(R.id.btnPause)
        btnAuto = findViewById(R.id.btnAuto)
        btnSettings = findViewById(R.id.btnSettings)
        btnPalette = findViewById(R.id.btnPalette)
        btnPalette.setOnClickListener { showPaletteDialog() }
        btnSave = findViewById(R.id.btnSave)
        btnVideo = findViewById(R.id.btnVideo)
        btnVideoLength = findViewById(R.id.btnVideoLength)
        updateVideoLengthLabel()
        btnVideoLength.setOnClickListener { showVideoLengthDialog() }
        speedValue = findViewById(R.id.speedValue)
        settingsPanel = findViewById(R.id.settingsPanel)
        settingsList = findViewById(R.id.settingsList)
        settingsTitle = findViewById(R.id.settingsTitle)

        glView = findViewById(R.id.glView)
        glView.setEGLContextClientVersion(2)
        // Pin a 16-bit depth buffer so hidden triangles stay hidden.
        glView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        renderer = MeshRenderer(this)
        glView.setRenderer(renderer)
        glView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        // Tap the 3D view for a new random mesh.
        glView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                newMesh()
            }
            true
        }

        findViewById<Button>(R.id.btnNew).setOnClickListener { newMesh() }

        btnSave.setOnClickListener {
            val snap = renderer.snapshot()
            if (snap == null) {
                Toast.makeText(this, "No mesh yet", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Toast.makeText(this, "Saving mesh…", Toast.LENGTH_SHORT).show()
            Thread {
                try {
                    val path = MeshSaver.save(this, snap)
                    runOnUiThread { Toast.makeText(this, "Saved: $path", Toast.LENGTH_LONG).show() }
                } catch (e: Exception) {
                    runOnUiThread { Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show() }
                }
            }.start()
        }

        btnVideo.setOnClickListener {
            val snap = renderer.snapshot()
            if (snap == null) {
                Toast.makeText(this, "No mesh yet", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            btnVideo.isEnabled = false
            Toast.makeText(this, "Rendering portrait video… ${formatLength(videoLengthSec)} at 1080×1920", Toast.LENGTH_LONG).show()
            Thread {
                val path = VideoExporter.export(this, snap, videoLengthSec)
                runOnUiThread {
                    btnVideo.isEnabled = true
                    if (path != null) Toast.makeText(this, "Video saved: $path", Toast.LENGTH_LONG).show()
                    else Toast.makeText(this, "Video failed: ${VideoExporter.lastError ?: "see Downloads/RandomMesh/video_export_error.txt"}", Toast.LENGTH_LONG).show()
                }
            }.start()
        }

        btnPause.setOnClickListener {
            isPaused = !isPaused
            renderer.paused = isPaused
            btnPause.text = if (isPaused) getString(R.string.play) else getString(R.string.pause)
        }

        btnAuto.setOnClickListener {
            autoOn = !autoOn
            renderer.autoChange = autoOn
            btnAuto.text = if (autoOn) getString(R.string.auto_on) else getString(R.string.auto_off)
        }

        btnSettings.setOnClickListener {
            settingsPanel.visibility = if (settingsPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        findViewById<Button>(R.id.btnCloseSettings).setOnClickListener {
            settingsPanel.visibility = View.GONE
        }

        findViewById<Button>(R.id.btnUnlockAll).setOnClickListener {
            renderer.lockedKeys.clear()
            displaySettings()
        }

        findViewById<SeekBar>(R.id.speedBar).setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    val speed = 0.25f + (progress / 100f) * 2.75f
                    renderer.speedMultiplier = speed
                    speedValue.text = String.format("%.2fx", speed)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) {}
            }
        )

        rotationValue = findViewById(R.id.rotationValue)
        findViewById<SeekBar>(R.id.rotationBar).setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    renderer.rotationOffsetY = progress.toFloat()
                    rotationValue.text = "${progress}°"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) {}
            }
        )

        zoomValue = findViewById(R.id.zoomValue)
        findViewById<SeekBar>(R.id.zoomBar).setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    val distance = (25 + progress) / 10f
                    renderer.zoomDistance = distance
                    zoomValue.text = String.format("%.1f", distance)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) {}
            }
        )

        rotSpeedValue = findViewById(R.id.rotSpeedValue)
        findViewById<SeekBar>(R.id.rotSpeedBar).setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    val speed = progress / 10f
                    renderer.rotationSpeed = speed
                    rotSpeedValue.text = String.format("%.1fx", speed)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) {}
            }
        )
    }

    private fun newMesh() {
        glView.queueEvent { renderer.requestNewMesh() }
    }

    private fun formatLength(totalSec: Int): String {
        return "${totalSec / 60}m ${totalSec % 60}s"
    }

    private fun updateVideoLengthLabel() {
        btnVideoLength.text = formatLength(videoLengthSec)
    }

    /** Minutes + seconds, no cap: type any length you want. */
    private fun showVideoLengthDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }
        val minInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "Minutes"
            setText((videoLengthSec / 60).toString())
        }
        val secInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "Seconds"
            setText((videoLengthSec % 60).toString())
        }
        val hint = TextView(this).apply {
            text = "No limit. Long videos take a while to render and run about 2.5 MB per second."
            textSize = 13f
            setPadding(0, 16, 0, 0)
        }
        layout.addView(minInput)
        layout.addView(secInput)
        layout.addView(hint)
        AlertDialog.Builder(this)
            .setTitle("Video length")
            .setView(layout)
            .setPositiveButton("OK") { _, _ ->
                val mins = (minInput.text.toString().toIntOrNull() ?: 0).coerceAtLeast(0)
                val secs = (secInput.text.toString().toIntOrNull() ?: 0).coerceAtLeast(0)
                videoLengthSec = maxOf(1, mins * 60 + secs)
                updateVideoLengthLabel()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Colour palette picker: presets with swatches, Random, and Custom.
     *  A picked palette sticks for every mesh and recolours the one on
     *  screen immediately; Random rolls fresh hues for every mesh. */
    private fun showPaletteDialog() {
        val density = resources.displayMetrics.density
        val current = renderer.palette?.name ?: "Random"
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), (8 * density).toInt(), (24 * density).toInt(), (8 * density).toInt())
        }
        val hint = TextView(this).apply {
            text = "Random rolls fresh hues for every mesh. A palette sticks until you change it."
            textSize = 13f
            setPadding(0, 0, 0, (8 * density).toInt())
        }
        layout.addView(hint)
        lateinit var dialog: AlertDialog
        fun swatch(hue: Float): View {
            val size = (28 * density).toInt()
            return View(this).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginEnd = (8 * density).toInt()
                }
                setBackgroundColor(Color.HSVToColor(floatArrayOf(
                    ((hue % 1f) + 1f) % 1f * 360f, 0.85f, 0.95f)))
            }
        }
        fun addRow(name: String, hueA: Float?, hueB: Float?, onPick: () -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (12 * density).toInt(), 0, (12 * density).toInt())
                isClickable = true
            }
            if (hueA != null && hueB != null) {
                row.addView(swatch(hueA))
                row.addView(swatch(hueB))
            }
            val label = TextView(this).apply {
                text = (if (name == current) "✓ " else "") + name
                textSize = 16f
                setPadding((8 * density).toInt(), 0, 0, 0)
            }
            row.addView(label)
            row.setOnClickListener { onPick(); dialog.dismiss() }
            layout.addView(row)
        }
        addRow("Random", null, null) {
            glView.queueEvent { renderer.applyPalette(null) }
        }
        for (p in PALETTES) {
            addRow(p.name, p.hueA, p.hueB) {
                glView.queueEvent { renderer.applyPalette(p) }
            }
        }
        renderer.palette?.let { p ->
            if (p.name == "Custom") {
                addRow("Custom", p.hueA, p.hueB) {
                    glView.queueEvent { renderer.applyPalette(p) }
                }
            }
        }
        addRow("Custom…", null, null) {
            showCustomPaletteDialog()
        }

        val bgHeader = TextView(this).apply {
            text = "BACKGROUND"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFFBB86FC.toInt())
            setPadding(0, (18 * density).toInt(), 0, (4 * density).toInt())
        }
        layout.addView(bgHeader)
        fun addColorRow(name: String, color: Int, checked: Boolean, onPick: () -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (12 * density).toInt(), 0, (12 * density).toInt())
                isClickable = true
            }
            val size = (28 * density).toInt()
            val sw = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginEnd = (8 * density).toInt()
                }
                setBackgroundColor(color)
            }
            row.addView(sw)
            val label = TextView(this).apply {
                text = (if (checked) "✓ " else "") + name
                textSize = 16f
                setPadding((8 * density).toInt(), 0, 0, 0)
            }
            row.addView(label)
            row.setOnClickListener { onPick(); dialog.dismiss() }
            layout.addView(row)
        }
        fun addTextRow(name: String, checked: Boolean = false, onPick: () -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (12 * density).toInt(), 0, (12 * density).toInt())
                isClickable = true
            }
            val label = TextView(this).apply {
                text = (if (checked) "✓ " else "") + name
                textSize = 16f
                setPadding((8 * density).toInt(), 0, 0, 0)
            }
            row.addView(label)
            row.setOnClickListener { onPick(); dialog.dismiss() }
            layout.addView(row)
        }
        if (renderer.backgroundMode == MeshRenderer.BG_SOLID) {
            for ((name, color) in BACKGROUND_PRESETS) {
                addColorRow(name, color, color == renderer.backgroundColor) {
                    glView.queueEvent { renderer.backgroundColor = color }
                }
            }
            addColorRow("Custom…", renderer.backgroundColor, false) {
                showBackgroundColorDialog()
            }
            addTextRow("Texture background…") {
                glView.queueEvent { renderer.backgroundMode = MeshRenderer.BG_TEXTURE }
                showBackgroundTextureDialog()
            }
        } else {
            addTextRow("Texture: ${renderer.backgroundTexturePattern}", true) {
                showBackgroundTextureDialog()
            }
            addTextRow("Solid colour…") {
                glView.queueEvent { renderer.backgroundMode = MeshRenderer.BG_SOLID }
                dialog.dismiss()
                showPaletteDialog()
            }
        }

        val scroll = ScrollView(this).apply { addView(layout) }
        dialog = AlertDialog.Builder(this)
            .setTitle("Colour palette")
            .setView(scroll)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.show()
    }

    /** Exact background colour: red / green / blue sliders with a preview. */
    private fun showBackgroundColorDialog() {
        val density = resources.displayMetrics.density
        val bg = renderer.backgroundColor
        var r = Color.red(bg)
        var g = Color.green(bg)
        var b = Color.blue(bg)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), (8 * density).toInt(), (24 * density).toInt(), (8 * density).toInt())
        }
        val preview = View(this).apply {
            val size = (40 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size)
            setBackgroundColor(Color.rgb(r, g, b))
        }
        val previewRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, (12 * density).toInt())
            addView(preview)
        }
        layout.addView(previewRow)
        fun channelSlider(label: String, start: Int, onChange: (Int) -> Unit) {
            val title = TextView(this).apply { text = label; textSize = 14f }
            layout.addView(title)
            val bar = SeekBar(this).apply { max = 255; progress = start }
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    onChange(progress)
                    preview.setBackgroundColor(Color.rgb(r, g, b))
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) {}
            })
            layout.addView(bar)
        }
        channelSlider("Red", r) { r = it }
        channelSlider("Green", g) { g = it }
        channelSlider("Blue", b) { b = it }
        AlertDialog.Builder(this)
            .setTitle("Background colour")
            .setView(layout)
            .setPositiveButton("Apply") { _, _ ->
                glView.queueEvent { renderer.backgroundColor = Color.rgb(r, g, b) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Scrolling texture background: pattern + scroll speed / direction /
     *  tile scale. Applies live; the texture follows the palette hues. */
    private fun showBackgroundTextureDialog() {
        val density = resources.displayMetrics.density
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), (8 * density).toInt(), (24 * density).toInt(), (8 * density).toInt())
        }
        lateinit var dialog: AlertDialog
        val patterns = MeshRenderer.BACKGROUND_PATTERNS
        for (pattern in patterns) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (12 * density).toInt(), 0, (12 * density).toInt())
                isClickable = true
            }
            val label = TextView(this).apply {
                text = (if (pattern == renderer.backgroundTexturePattern) "✓ " else "") + pattern
                textSize = 16f
                setPadding((8 * density).toInt(), 0, 0, 0)
            }
            row.addView(label)
            row.setOnClickListener {
                glView.queueEvent { renderer.applyBackgroundTexturePattern(pattern) }
                dialog.dismiss()
            }
            layout.addView(row)
        }

        fun sliderRow(labelText: String, max: Int, start: Int, format: (Int) -> String, onChange: (Int) -> Unit) {
            val title = TextView(this).apply { text = labelText; textSize = 14f }
            layout.addView(title)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val bar = SeekBar(this).apply {
                this.max = max
                progress = start
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val value = TextView(this).apply {
                text = format(start)
                textSize = 13f
                setPadding((8 * density).toInt(), 0, 0, 0)
            }
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    onChange(progress)
                    value.text = format(progress)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) {}
            })
            row.addView(bar)
            row.addView(value)
            layout.addView(row)
        }

        val colorsHeader = TextView(this).apply {
            text = "COLOURS"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFFBB86FC.toInt())
            setPadding(0, (18 * density).toInt(), 0, (4 * density).toInt())
        }
        layout.addView(colorsHeader)
        val wrapHue = { h: Float -> ((h % 1f) + 1f) % 1f }
        fun bgColorFor(hue: Float, sat: Float, bright: Float) =
            Color.HSVToColor(floatArrayOf(hue * 360f, sat, 0.95f * bright))
        fun effectiveBgColor(which: Char): Int {
            return if (which == 'A') bgColorFor(wrapHue(renderer.backgroundHueA),
                renderer.backgroundSaturationA, renderer.backgroundBrightnessA)
            else bgColorFor(wrapHue(renderer.backgroundHueB),
                renderer.backgroundSaturationB, renderer.backgroundBrightnessB)
        }
        val bgSwatchA = View(this).apply {
            val size = (40 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = (8 * density).toInt()
            }
            setBackgroundColor(effectiveBgColor('A'))
        }
        val bgSwatchB = View(this).apply {
            val size = (40 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size)
            setBackgroundColor(effectiveBgColor('B'))
        }
        fun refreshBgSwatches() {
            bgSwatchA.setBackgroundColor(effectiveBgColor('A'))
            bgSwatchB.setBackgroundColor(effectiveBgColor('B'))
        }
        fun colorRow(which: Char, swatch: View) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
                isClickable = true
            }
            row.addView(swatch)
            val label = TextView(this).apply {
                text = "Colour $which — tap to pick"
                textSize = 16f
                setPadding((8 * density).toInt(), 0, 0, 0)
            }
            row.addView(label)
            row.setOnClickListener {
                showBackgroundColorPickerDialog(which) { refreshBgSwatches() }
            }
            layout.addView(row)
        }
        colorRow('A', bgSwatchA)
        sliderRow("Contrast A", 30, (renderer.backgroundContrastA * 20f).toInt().coerceIn(0, 30),
            { p -> String.format("%.2f", p / 20f) }) { p ->
            renderer.backgroundContrastA = p / 20f
            renderer.backgroundTextureDirty = true
        }
        colorRow('B', bgSwatchB)
        sliderRow("Contrast B", 30, (renderer.backgroundContrastB * 20f).toInt().coerceIn(0, 30),
            { p -> String.format("%.2f", p / 20f) }) { p ->
            renderer.backgroundContrastB = p / 20f
            renderer.backgroundTextureDirty = true
        }
        val matchButton = Button(this).apply {
            text = "Match mesh palette"
            setOnClickListener {
                renderer.matchMeshPalette()
                refreshBgSwatches()
            }
        }
        layout.addView(matchButton)

        sliderRow("Horizontal scroll", 80, (renderer.backgroundScrollX * 20f + 40f).toInt().coerceIn(0, 80),
            { p -> String.format("%.2f", (p - 40) / 20f) }) { p -> renderer.backgroundScrollX = (p - 40) / 20f }
        sliderRow("Vertical scroll", 80, (renderer.backgroundScrollY * 20f + 40f).toInt().coerceIn(0, 80),
            { p -> String.format("%.2f", (p - 40) / 20f) }) { p -> renderer.backgroundScrollY = (p - 40) / 20f }
        sliderRow("Texture scale", 50, ((renderer.backgroundTextureScale - 0.5f) * 20f).toInt().coerceIn(0, 50),
            { p -> String.format("%.1f", 0.5f + p / 20f) }) { p -> renderer.backgroundTextureScale = 0.5f + p / 20f }
        sliderRow("Pattern detail", 75, ((renderer.backgroundTextureDetailScale - 0.5f) * 10f).toInt().coerceIn(0, 75),
            { p -> String.format("%.1f", 0.5f + p / 10f) }) { p ->
            renderer.backgroundTextureDetailScale = 0.5f + p / 10f
            renderer.backgroundTextureDirty = true
        }
        val qualityHeader = TextView(this).apply {
            text = "QUALITY"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFFBB86FC.toInt())
            setPadding(0, (18 * density).toInt(), 0, (4 * density).toInt())
        }
        layout.addView(qualityHeader)
        val qualityOptions = listOf("Standard — 256px" to 0, "High — 512px" to 1, "Ultra — 1024px" to 2)
        for ((label, q) in qualityOptions) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (12 * density).toInt(), 0, (12 * density).toInt())
                isClickable = true
            }
            val text = TextView(this).apply {
                text = (if (q == renderer.backgroundTextureQuality) "✓ " else "") + label
                textSize = 16f
                setPadding((8 * density).toInt(), 0, 0, 0)
            }
            row.addView(text)
            row.setOnClickListener {
                renderer.backgroundTextureQuality = q
                renderer.backgroundTextureDirty = true
                dialog.dismiss()
            }
            layout.addView(row)
        }
        val aaRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (12 * density).toInt(), 0, (12 * density).toInt())
        }
        val aaLabel = TextView(this).apply {
            text = "Antialiasing"
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val aaSwitch = Switch(this).apply {
            isChecked = renderer.backgroundAntialias
            setOnCheckedChangeListener { _, on ->
                renderer.backgroundAntialias = on
                renderer.backgroundTextureDirty = true
            }
        }
        aaRow.addView(aaLabel)
        aaRow.addView(aaSwitch)
        layout.addView(aaRow)

        val randomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (12 * density).toInt(), 0, (12 * density).toInt())
        }
        val randomLabel = TextView(this).apply {
            text = "Randomize with each mesh"
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val randomSwitch = Switch(this).apply {
            isChecked = renderer.backgroundRandomize
            setOnCheckedChangeListener { _, on -> renderer.backgroundRandomize = on }
        }
        randomRow.addView(randomLabel)
        randomRow.addView(randomSwitch)
        layout.addView(randomRow)
        val randomButton = Button(this).apply {
            text = "🎲 Random background"
            setOnClickListener {
                glView.queueEvent { renderer.randomizeBackground() }
                dialog.dismiss()
            }
        }
        layout.addView(randomButton)

        val solidHeader = TextView(this).apply {
            text = "SOLID"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFFBB86FC.toInt())
            setPadding(0, (18 * density).toInt(), 0, (4 * density).toInt())
        }
        layout.addView(solidHeader)
        fun solidRow(label: String, color: Int) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (12 * density).toInt(), 0, (12 * density).toInt())
                isClickable = true
            }
            val swatch = View(this).apply {
                val size = (40 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginEnd = (8 * density).toInt()
                }
                setBackgroundColor(color)
            }
            row.addView(swatch)
            val text = TextView(this).apply {
                this.text = label
                textSize = 16f
                setPadding((8 * density).toInt(), 0, 0, 0)
            }
            row.addView(text)
            row.setOnClickListener {
                renderer.backgroundMode = MeshRenderer.BG_SOLID
                renderer.backgroundColor = color
                dialog.dismiss()
            }
            layout.addView(row)
        }
        solidRow("Solid black", 0xFF000000.toInt())
        solidRow("Solid white", 0xFFFFFFFF.toInt())

        val scroll = ScrollView(this).apply { addView(layout) }
        dialog = AlertDialog.Builder(this)
            .setTitle("Texture background")
            .setView(scroll)
            .setPositiveButton("Done") { _, _ -> }
            .create()
        dialog.show()
    }

    /** Colour picker for one end of the background texture gradient: tap
     *  the hue x saturation plane to pick, with a preview swatch and hex.
     *  Apply sets that end's hue plus the shared saturation, live. */
    private fun showBackgroundColorPickerDialog(which: Char, onApplied: () -> Unit) {
        val density = resources.displayMetrics.density
        val isA = which == 'A'
        val wrapHue = { h: Float -> ((h % 1f) + 1f) % 1f }
        var hue = wrapHue(if (isA) renderer.backgroundHueA else renderer.backgroundHueB)
        var sat = if (isA) renderer.backgroundSaturationA else renderer.backgroundSaturationB
        var bright = if (isA) renderer.backgroundBrightnessA else renderer.backgroundBrightnessB
        fun pickedColor() = Color.HSVToColor(floatArrayOf(hue * 360f, sat, 0.95f * bright))
        fun hexOf(color: Int) = String.format("#%06X", 0xFFFFFF and color)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), (8 * density).toInt(), (24 * density).toInt(), (8 * density).toInt())
        }
        val preview = View(this).apply {
            val size = (48 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = (12 * density).toInt()
            }
            setBackgroundColor(pickedColor())
        }
        val hexLabel = TextView(this).apply {
            text = hexOf(pickedColor())
            textSize = 16f
        }
        val previewRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, (12 * density).toInt())
            addView(preview)
            addView(hexLabel)
        }
        layout.addView(previewRow)
        lateinit var plane: HSPlaneView
        lateinit var satBar: SeekBar
        lateinit var brightBar: SeekBar
        plane = HSPlaneView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (220 * density).toInt())
            selectedHue = hue
            selectedSat = sat
            brightness = bright
            onPick = { h, s ->
                hue = h
                sat = s
                satBar.progress = (s * 100f).toInt()
                preview.setBackgroundColor(pickedColor())
                hexLabel.text = hexOf(pickedColor())
            }
        }
        layout.addView(plane)
        val satTitle = TextView(this).apply {
            text = "Saturation"
            textSize = 14f
            setPadding(0, (12 * density).toInt(), 0, 0)
        }
        layout.addView(satTitle)
        satBar = SeekBar(this).apply { max = 100; progress = (sat * 100f).toInt() }
        satBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                sat = progress / 100f
                plane.selectedSat = sat
                plane.invalidate()
                preview.setBackgroundColor(pickedColor())
                hexLabel.text = hexOf(pickedColor())
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        layout.addView(satBar)
        val brightTitle = TextView(this).apply {
            text = "Brightness"
            textSize = 14f
            setPadding(0, (12 * density).toInt(), 0, 0)
        }
        layout.addView(brightTitle)
        brightBar = SeekBar(this).apply { max = 100; progress = (bright * 100f).toInt() }
        brightBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                bright = progress / 100f
                plane.brightness = bright
                plane.invalidate()
                preview.setBackgroundColor(pickedColor())
                hexLabel.text = hexOf(pickedColor())
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        layout.addView(brightBar)
        val hint = TextView(this).apply {
            text = "Tap the plane to pick a colour. Left to right is the hue, top to bottom is how vivid it is. Brightness 0 is black — or use the sliders for fine control."
            textSize = 13f
            setPadding(0, (12 * density).toInt(), 0, 0)
        }
        layout.addView(hint)
        AlertDialog.Builder(this)
            .setTitle("Background colour $which")
            .setView(layout)
            .setPositiveButton("Apply") { _, _ ->
                if (isA) {
                    renderer.backgroundHueA = hue
                    renderer.backgroundSaturationA = sat
                    renderer.backgroundBrightnessA = bright
                } else {
                    renderer.backgroundHueB = hue
                    renderer.backgroundSaturationB = sat
                    renderer.backgroundBrightnessB = bright
                }
                renderer.backgroundTextureDirty = true
                onApplied()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Hue A / Hue B / Saturation sliders with a live preview; applies as
     *  a "Custom" palette, so the exact colours you set are the ones every
     *  mesh uses. */
    private fun showCustomPaletteDialog() {
        val density = resources.displayMetrics.density
        val wrap = { h: Float -> ((h % 1f) + 1f) % 1f }
        var hueA = wrap(renderer.palette?.hueA ?: currentSettings?.hueA ?: 0.6f)
        var hueB = wrap(renderer.palette?.hueB ?: currentSettings?.hueB ?: 0.8f)
        var saturation = renderer.palette?.saturation ?: currentSettings?.saturation ?: 0.85f
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), (8 * density).toInt(), (24 * density).toInt(), (8 * density).toInt())
        }
        fun colorFor(hue: Float, sat: Float) = Color.HSVToColor(floatArrayOf(hue * 360f, sat, 0.95f))
        val previewA = View(this).apply {
            val size = (40 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = (8 * density).toInt()
            }
            setBackgroundColor(colorFor(hueA, saturation))
        }
        val previewB = View(this).apply {
            val size = (40 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size)
            setBackgroundColor(colorFor(hueB, saturation))
        }
        val previewRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, (12 * density).toInt())
            addView(previewA)
            addView(previewB)
        }
        layout.addView(previewRow)
        fun hueSlider(label: String, start: Float, onChange: (Float) -> Unit) {
            val title = TextView(this).apply { text = label; textSize = 14f }
            layout.addView(title)
            val bar = SeekBar(this).apply {
                max = 360
                progress = (start * 360f).toInt()
            }
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    onChange(progress / 360f)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) {}
            })
            layout.addView(bar)
        }
        hueSlider("Hue A", hueA) { hueA = it; previewA.setBackgroundColor(colorFor(it, saturation)) }
        hueSlider("Hue B", hueB) { hueB = it; previewB.setBackgroundColor(colorFor(it, saturation)) }
        val satTitle = TextView(this).apply { text = "Saturation"; textSize = 14f }
        layout.addView(satTitle)
        val satBar = SeekBar(this).apply { max = 100; progress = (saturation * 100f).toInt() }
        satBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                saturation = progress / 100f
                previewA.setBackgroundColor(colorFor(hueA, saturation))
                previewB.setBackgroundColor(colorFor(hueB, saturation))
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        layout.addView(satBar)
        AlertDialog.Builder(this)
            .setTitle("Custom palette")
            .setView(layout)
            .setPositiveButton("Apply") { _, _ ->
                glView.queueEvent { renderer.applyPalette(Palette("Custom", hueA, hueB, saturation)) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onMeshChanged(mesh: MeshData, anim: AnimParams) {
        currentSettings = mesh.settings
        meshName.text = mesh.name
        meshInfo.text = "${mesh.kind}  ·  ${mesh.vertexCount} verts  ·  ${mesh.triangleCount} tris"
        animInfo.text = "Animation: ${anim.mode}  ·  wobble ${String.format("%.2f", anim.wobbleAmp)}" +
            if (autoOn) "  ·  next mesh in ~${String.format("%.0f", mesh.settings.holdSeconds)}s  ·  grows from simple, morphs smoothly" else "  ·  grows from simple, morphs smoothly"
        displaySettings()
    }

    /** Rebuilds the exhaustive settings list. Tap a row to lock/unlock it. */
    private fun displaySettings() {
        val s = currentSettings ?: return
        val entries = s.entries()
        settingsTitle.text = "Mesh settings (${entries.size}) — seed ${s.seed}"
        btnSettings.text = "⚙ Settings (${entries.size})"
        settingsList.removeAllViews()
        var lastGroup = ""
        for (row in entries) {
            if (row.group != lastGroup) {
                lastGroup = row.group
                val header = TextView(this).apply {
                    text = row.group.uppercase()
                    textSize = 13f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(0xFFBB86FC.toInt())
                    setPadding(0, 18, 0, 4)
                }
                settingsList.addView(header)
            }
            val locked = renderer.lockedKeys.contains(row.key)
            val item = TextView(this).apply {
                text = (if (locked) "🔒 " else "") + "${row.label}: ${row.value}"
                textSize = 14f
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(8, 6, 8, 6)
                setBackgroundColor(if (locked) 0xFF332255.toInt() else 0x00000000)
                isClickable = true
                setOnClickListener {
                    if (renderer.lockedKeys.contains(row.key)) renderer.lockedKeys.remove(row.key)
                    else renderer.lockedKeys.add(row.key)
                    displaySettings()
                }
            }
            settingsList.addView(item)
        }
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
    }

    override fun onPause() {
        super.onPause()
        glView.onPause()
    }
}
