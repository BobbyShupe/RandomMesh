package com.robert.randommesh

/** A colour palette: the two hues the mesh gradient (and its procedural
 *  texture) run between. Hues are 0..1 but may run past 1 to cross the red
 *  boundary — the generator wraps hue mod 1, so 0.98 -> 1.08 sweeps
 *  pink-red through red to orange instead of across the whole wheel.
 *  Saturation still rolls per mesh unless a palette sets it (the Custom
 *  palette does), so two meshes on the same preset are related but never
 *  identical. */
data class Palette(val name: String, val hueA: Float, val hueB: Float, val saturation: Float? = null)

val PALETTES = listOf(
    Palette("Sunset", 0.98f, 1.08f),
    Palette("Ocean", 0.52f, 0.66f),
    Palette("Forest", 0.25f, 0.38f),
    Palette("Neon", 0.83f, 0.55f),
    Palette("Ember", 0.0f, 0.09f),
    Palette("Ice", 0.55f, 0.62f),
    Palette("Royal", 0.72f, 0.82f),
    Palette("Candy", 0.90f, 0.78f),
    Palette("Mono", 0.60f, 0.60f)
)

/** Background presets for the 3D view (and the video). */
val BACKGROUND_PRESETS = listOf(
    "Black" to 0xFF000000.toInt(),
    "Dark navy" to 0xFF05050D.toInt(),
    "Charcoal" to 0xFF1A1A1A.toInt(),
    "Slate" to 0xFF2E3440.toInt(),
    "White" to 0xFFFFFFFF.toInt()
)
