package com.robert.randommesh

import kotlin.random.Random

/** Every value that can change the generated mesh. A fresh random set is
 *  rolled for every new mesh; tapping a row in the Settings panel locks
 *  that one setting so it survives the next roll. */
data class MeshSettings(
    // --- Identity ---
    val seed: Long,
    val shape: String,
    // --- Resolution ---
    val segmentsU: Int,
    val segmentsV: Int,
    val detailSubdiv: Int,
    // --- Size ---
    val baseRadius: Float,
    val scaleX: Float,
    val scaleY: Float,
    val scaleZ: Float,
    // --- FBM noise ---
    val noiseType: String,
    val noiseOctaves: Int,
    val noiseFrequency: Float,
    val noiseAmplitude: Float,
    val noiseLacunarity: Float,
    val noisePersistence: Float,
    val noisePhase1: Float,
    val noisePhase2: Float,
    val noisePhase3: Float,
    val detailFrequency: Float,
    val detailAmplitude: Float,
    // --- Displacement passes ---
    val ridgeAmount: Float,
    val spikeCount: Int,
    val spikeHeight: Float,
    val spikeSharpness: Float,
    val craterCount: Int,
    val craterDepth: Float,
    val craterRadius: Float,
    val bumpCount: Int,
    val bumpHeight: Float,
    val grooveCount: Int,
    val grooveDepth: Float,
    val terraceSteps: Int,
    // --- Form ---
    val twistDeg: Float,
    val taper: Float,
    val bend: Float,
    val bulge: Float,
    val pinch: Float,
    val shear: Float,
    val symmetryFolds: Int,
    val vertexJitter: Float,
    // --- Shape-specific ---
    val torusMajor: Float,
    val torusMinor: Float,
    val knotP: Int,
    val knotQ: Int,
    val gearTeeth: Int,
    val gearDepth: Float,
    val terrainSize: Float,
    val columnHeight: Float,
    // --- Colour ---
    val hueA: Float,
    val hueB: Float,
    val saturation: Float,
    val brightnessLow: Float,
    val brightnessHigh: Float,
    val gradientMode: String,
    val colourNoise: Float,
    // --- Surface ---
    val flatShading: Boolean,
    val edgeSharpness: Float,
    // --- Growing extremities ---
    val extremityCount: Int,
    val extremityLength: Float,
    val extremityThickness: Float,
    val extremityGrowthSeconds: Float,
    val extremityPulse: Float,
    // --- Moving parts ---
    val partCount: Int,
    val partMoveAmount: Float,
    val partMoveSpeed: Float,
    // --- Procedural texture ---
    val texturePattern: String,
    val textureScale: Float,
    val textureContrast: Float,
    val textureScrollSpeed: Float,
    // --- Light & time ---
    val holdSeconds: Float,
    val spotlightIntensity: Float,
    val ambientLevel: Float,
    val morphIntervalSeconds: Float,
    val morphDurationSeconds: Float,
    val complexityStep: Float
) {
    /** Grouped (group, key, label, value) rows for the Settings panel. */
    fun entries(): List<SettingRow> {
        fun f(v: Float) = String.format("%.2f", v)
        fun f3(v: Float) = String.format("%.3f", v)
        return listOf(
            SettingRow("Identity", "seed", "Seed", seed.toString()),
            SettingRow("Identity", "shape", "Shape", shape),
            SettingRow("Resolution", "segmentsU", "Segments U", segmentsU.toString()),
            SettingRow("Resolution", "segmentsV", "Segments V", segmentsV.toString()),
            SettingRow("Resolution", "detailSubdiv", "Detail subdiv", detailSubdiv.toString()),
            SettingRow("Size", "baseRadius", "Base radius", f(baseRadius)),
            SettingRow("Size", "scaleX", "Scale X", f(scaleX)),
            SettingRow("Size", "scaleY", "Scale Y", f(scaleY)),
            SettingRow("Size", "scaleZ", "Scale Z", f(scaleZ)),
            SettingRow("FBM noise", "noiseType", "Noise type", noiseType),
            SettingRow("FBM noise", "noiseOctaves", "Noise octaves", noiseOctaves.toString()),
            SettingRow("FBM noise", "noiseFrequency", "Noise frequency", f(noiseFrequency)),
            SettingRow("FBM noise", "noiseAmplitude", "Noise amplitude", f(noiseAmplitude)),
            SettingRow("FBM noise", "noiseLacunarity", "Noise lacunarity", f(noiseLacunarity)),
            SettingRow("FBM noise", "noisePersistence", "Noise persistence", f(noisePersistence)),
            SettingRow("FBM noise", "noisePhase1", "Noise phase 1", f(noisePhase1)),
            SettingRow("FBM noise", "noisePhase2", "Noise phase 2", f(noisePhase2)),
            SettingRow("FBM noise", "noisePhase3", "Noise phase 3", f(noisePhase3)),
            SettingRow("FBM noise", "detailFrequency", "Detail frequency", f(detailFrequency)),
            SettingRow("FBM noise", "detailAmplitude", "Detail amplitude", f3(detailAmplitude)),
            SettingRow("Displacement", "ridgeAmount", "Ridge amount", f(ridgeAmount)),
            SettingRow("Displacement", "spikeCount", "Spike count", spikeCount.toString()),
            SettingRow("Displacement", "spikeHeight", "Spike height", f(spikeHeight)),
            SettingRow("Displacement", "spikeSharpness", "Spike sharpness", f(spikeSharpness)),
            SettingRow("Displacement", "craterCount", "Crater count", craterCount.toString()),
            SettingRow("Displacement", "craterDepth", "Crater depth", f(craterDepth)),
            SettingRow("Displacement", "craterRadius", "Crater radius", f(craterRadius)),
            SettingRow("Displacement", "bumpCount", "Bump count", bumpCount.toString()),
            SettingRow("Displacement", "bumpHeight", "Bump height", f(bumpHeight)),
            SettingRow("Displacement", "grooveCount", "Groove count", grooveCount.toString()),
            SettingRow("Displacement", "grooveDepth", "Groove depth", f(grooveDepth)),
            SettingRow("Displacement", "terraceSteps", "Terrace steps (0=off)", terraceSteps.toString()),
            SettingRow("Form", "twistDeg", "Twist (deg)", String.format("%.0f", twistDeg)),
            SettingRow("Form", "taper", "Taper", f(taper)),
            SettingRow("Form", "bend", "Bend", f(bend)),
            SettingRow("Form", "bulge", "Bulge", f(bulge)),
            SettingRow("Form", "pinch", "Pinch", f(pinch)),
            SettingRow("Form", "shear", "Shear", f(shear)),
            SettingRow("Form", "symmetryFolds", "Symmetry folds", symmetryFolds.toString()),
            SettingRow("Form", "vertexJitter", "Vertex jitter", f3(vertexJitter)),
            SettingRow("Shape-specific", "torusMajor", "Torus major R", f(torusMajor)),
            SettingRow("Shape-specific", "torusMinor", "Torus minor r", f(torusMinor)),
            SettingRow("Shape-specific", "knotP", "Knot P", knotP.toString()),
            SettingRow("Shape-specific", "knotQ", "Knot Q", knotQ.toString()),
            SettingRow("Shape-specific", "gearTeeth", "Gear teeth", gearTeeth.toString()),
            SettingRow("Shape-specific", "gearDepth", "Gear depth", f(gearDepth)),
            SettingRow("Shape-specific", "terrainSize", "Terrain size", f(terrainSize)),
            SettingRow("Shape-specific", "columnHeight", "Column height", f(columnHeight)),
            SettingRow("Colour", "hueA", "Hue A", f(hueA)),
            SettingRow("Colour", "hueB", "Hue B", f(hueB)),
            SettingRow("Colour", "saturation", "Saturation", f(saturation)),
            SettingRow("Colour", "brightnessLow", "Brightness low", f(brightnessLow)),
            SettingRow("Colour", "brightnessHigh", "Brightness high", f(brightnessHigh)),
            SettingRow("Colour", "gradientMode", "Gradient mode", gradientMode),
            SettingRow("Colour", "colourNoise", "Colour noise", f(colourNoise)),
            SettingRow("Surface", "flatShading", "Flat shading", if (flatShading) "on" else "off"),
            SettingRow("Surface", "edgeSharpness", "Edge sharpness", f(edgeSharpness)),
            SettingRow("Extremities", "extremityCount", "Extremity count", extremityCount.toString()),
            SettingRow("Extremities", "extremityLength", "Extremity length", f(extremityLength)),
            SettingRow("Extremities", "extremityThickness", "Extremity thickness", f(extremityThickness)),
            SettingRow("Extremities", "extremityGrowthSeconds", "Extremity growth (s)", f(extremityGrowthSeconds)),
            SettingRow("Extremities", "extremityPulse", "Extremity pulse", f(extremityPulse)),
            SettingRow("Moving parts", "partCount", "Part count", partCount.toString()),
            SettingRow("Moving parts", "partMoveAmount", "Part move amount", f(partMoveAmount)),
            SettingRow("Moving parts", "partMoveSpeed", "Part move speed", f(partMoveSpeed)),
            SettingRow("Texture", "texturePattern", "Texture pattern", texturePattern),
            SettingRow("Texture", "textureScale", "Texture scale", f(textureScale)),
            SettingRow("Texture", "textureContrast", "Texture contrast", f(textureContrast)),
            SettingRow("Texture", "textureScrollSpeed", "Texture scroll speed", f(textureScrollSpeed)),
            SettingRow("Light & time", "holdSeconds", "Hold before next mesh (s)", f(holdSeconds)),
            SettingRow("Light & time", "spotlightIntensity", "Spotlight intensity", f(spotlightIntensity)),
            SettingRow("Light & time", "ambientLevel", "Ambient light", f(ambientLevel)),
            SettingRow("Light & time", "morphIntervalSeconds", "Live morph interval (s)", f(morphIntervalSeconds)),
            SettingRow("Light & time", "morphDurationSeconds", "Smooth morph duration (s)", f(morphDurationSeconds)),
            SettingRow("Displacement", "complexityStep", "Complexity step per morph", f(complexityStep))
        )
    }

    companion object {
        val SHAPES = listOf(
            "Noisy Sphere", "Wobbly Blob", "Spiky Star", "Twisted Torus",
            "Wave Terrain", "Twisted Column", "Torus Knot", "Gear",
            "Crystal", "Spiral Shell", "Helix Coil", "Möbius Strip"
        )
        val NOISE_TYPES = listOf("FBM", "Ridged", "Billow", "Sine")
        val GRADIENT_MODES = listOf("Height", "Radius", "Noise", "Bands", "Random")
        val TEXTURE_PATTERNS = listOf("Marble", "Stripes", "Checker", "Speckle", "Gradient", "Camo")

        fun random(rnd: Random, previous: MeshSettings?, locked: Set<String>): MeshSettings {
            val shape = SHAPES.random(rnd)
            val fresh = MeshSettings(
                seed = rnd.nextLong(),
                shape = shape,
                segmentsU = 32 + rnd.nextInt(49),
                segmentsV = 16 + rnd.nextInt(33),
                detailSubdiv = 1 + rnd.nextInt(3),
                baseRadius = 0.8f + rnd.nextFloat() * 0.5f,
                scaleX = 0.65f + rnd.nextFloat() * 0.75f,
                scaleY = 0.65f + rnd.nextFloat() * 0.75f,
                scaleZ = 0.65f + rnd.nextFloat() * 0.75f,
                noiseType = NOISE_TYPES.random(rnd),
                noiseOctaves = 3 + rnd.nextInt(4),
                noiseFrequency = 1f + rnd.nextFloat() * 5f,
                noiseAmplitude = 0.05f + rnd.nextFloat() * 0.55f,
                noiseLacunarity = 1.5f + rnd.nextFloat() * 1f,
                noisePersistence = 0.3f + rnd.nextFloat() * 0.4f,
                noisePhase1 = rnd.nextFloat() * 6.28f,
                noisePhase2 = rnd.nextFloat() * 6.28f,
                noisePhase3 = rnd.nextFloat() * 6.28f,
                detailFrequency = 6f + rnd.nextFloat() * 8f,
                detailAmplitude = 0.02f + rnd.nextFloat() * 0.13f,
                ridgeAmount = rnd.nextFloat() * 0.8f,
                spikeCount = rnd.nextInt(9),
                spikeHeight = rnd.nextFloat() * 0.9f,
                spikeSharpness = 1f + rnd.nextFloat() * 3f,
                craterCount = rnd.nextInt(13),
                craterDepth = rnd.nextFloat() * 0.35f,
                craterRadius = 0.15f + rnd.nextFloat() * 0.35f,
                bumpCount = rnd.nextInt(17),
                bumpHeight = rnd.nextFloat() * 0.3f,
                grooveCount = rnd.nextInt(13),
                grooveDepth = rnd.nextFloat() * 0.25f,
                terraceSteps = if (rnd.nextFloat() < 0.3f) 2 + rnd.nextInt(7) else 0,
                twistDeg = (rnd.nextFloat() - 0.5f) * 360f,
                taper = (rnd.nextFloat() - 0.5f) * 1.2f,
                bend = (rnd.nextFloat() - 0.5f) * 1.6f,
                bulge = (rnd.nextFloat() - 0.5f) * 1f,
                pinch = (rnd.nextFloat() - 0.5f) * 0.8f,
                shear = (rnd.nextFloat() - 0.5f) * 0.6f,
                symmetryFolds = listOf(1, 2, 3, 4, 5, 6, 8).random(rnd),
                vertexJitter = rnd.nextFloat() * 0.05f,
                torusMajor = 0.7f + rnd.nextFloat() * 0.35f,
                torusMinor = 0.2f + rnd.nextFloat() * 0.25f,
                knotP = listOf(2, 3, 4, 5).random(rnd),
                knotQ = listOf(2, 3, 4, 5, 7).random(rnd),
                gearTeeth = 6 + rnd.nextInt(19),
                gearDepth = 0.1f + rnd.nextFloat() * 0.35f,
                terrainSize = 2.2f + rnd.nextFloat() * 1.2f,
                columnHeight = 2f + rnd.nextFloat() * 1.2f,
                hueA = rnd.nextFloat(),
                hueB = rnd.nextFloat(),
                saturation = 0.5f + rnd.nextFloat() * 0.5f,
                brightnessLow = 0.35f + rnd.nextFloat() * 0.3f,
                brightnessHigh = 0.6f + rnd.nextFloat() * 0.4f,
                gradientMode = GRADIENT_MODES.random(rnd),
                colourNoise = rnd.nextFloat() * 0.4f,
                flatShading = rnd.nextFloat() < 0.25f,
                edgeSharpness = 0.5f + rnd.nextFloat(),
                extremityCount = rnd.nextInt(9),
                extremityLength = 0.2f + rnd.nextFloat() * 0.8f,
                extremityThickness = 0.1f + rnd.nextFloat() * 0.4f,
                extremityGrowthSeconds = 2f + rnd.nextFloat() * 4f,
                extremityPulse = rnd.nextFloat() * 0.3f,
                partCount = 2 + rnd.nextInt(7),
                partMoveAmount = 0.02f + rnd.nextFloat() * 0.23f,
                partMoveSpeed = 0.3f + rnd.nextFloat() * 1.2f,
                texturePattern = TEXTURE_PATTERNS.random(rnd),
                textureScale = 1f + rnd.nextFloat() * 7f,
                textureContrast = 0.5f + rnd.nextFloat(),
                textureScrollSpeed = (rnd.nextFloat() - 0.5f) * 0.2f,
                holdSeconds = 15f + rnd.nextFloat() * 10f,
                spotlightIntensity = 1.2f + rnd.nextFloat() * 1.8f,
                ambientLevel = 0.45f + rnd.nextFloat() * 0.25f,
                morphIntervalSeconds = 1.5f + rnd.nextFloat() * 1.5f,
                morphDurationSeconds = 1.2f + rnd.nextFloat() * 1.6f,
                complexityStep = 0.15f + rnd.nextFloat() * 0.2f
            )
            if (previous == null || locked.isEmpty()) return fresh
            var out = fresh
            for (key in locked) {
                out = when (key) {
                    "seed" -> out.copy(seed = previous.seed)
                    "shape" -> out.copy(shape = previous.shape)
                    "segmentsU" -> out.copy(segmentsU = previous.segmentsU)
                    "segmentsV" -> out.copy(segmentsV = previous.segmentsV)
                    "detailSubdiv" -> out.copy(detailSubdiv = previous.detailSubdiv)
                    "baseRadius" -> out.copy(baseRadius = previous.baseRadius)
                    "scaleX" -> out.copy(scaleX = previous.scaleX)
                    "scaleY" -> out.copy(scaleY = previous.scaleY)
                    "scaleZ" -> out.copy(scaleZ = previous.scaleZ)
                    "noiseType" -> out.copy(noiseType = previous.noiseType)
                    "noiseOctaves" -> out.copy(noiseOctaves = previous.noiseOctaves)
                    "noiseFrequency" -> out.copy(noiseFrequency = previous.noiseFrequency)
                    "noiseAmplitude" -> out.copy(noiseAmplitude = previous.noiseAmplitude)
                    "noiseLacunarity" -> out.copy(noiseLacunarity = previous.noiseLacunarity)
                    "noisePersistence" -> out.copy(noisePersistence = previous.noisePersistence)
                    "noisePhase1" -> out.copy(noisePhase1 = previous.noisePhase1)
                    "noisePhase2" -> out.copy(noisePhase2 = previous.noisePhase2)
                    "noisePhase3" -> out.copy(noisePhase3 = previous.noisePhase3)
                    "detailFrequency" -> out.copy(detailFrequency = previous.detailFrequency)
                    "detailAmplitude" -> out.copy(detailAmplitude = previous.detailAmplitude)
                    "ridgeAmount" -> out.copy(ridgeAmount = previous.ridgeAmount)
                    "spikeCount" -> out.copy(spikeCount = previous.spikeCount)
                    "spikeHeight" -> out.copy(spikeHeight = previous.spikeHeight)
                    "spikeSharpness" -> out.copy(spikeSharpness = previous.spikeSharpness)
                    "craterCount" -> out.copy(craterCount = previous.craterCount)
                    "craterDepth" -> out.copy(craterDepth = previous.craterDepth)
                    "craterRadius" -> out.copy(craterRadius = previous.craterRadius)
                    "bumpCount" -> out.copy(bumpCount = previous.bumpCount)
                    "bumpHeight" -> out.copy(bumpHeight = previous.bumpHeight)
                    "grooveCount" -> out.copy(grooveCount = previous.grooveCount)
                    "grooveDepth" -> out.copy(grooveDepth = previous.grooveDepth)
                    "terraceSteps" -> out.copy(terraceSteps = previous.terraceSteps)
                    "twistDeg" -> out.copy(twistDeg = previous.twistDeg)
                    "taper" -> out.copy(taper = previous.taper)
                    "bend" -> out.copy(bend = previous.bend)
                    "bulge" -> out.copy(bulge = previous.bulge)
                    "pinch" -> out.copy(pinch = previous.pinch)
                    "shear" -> out.copy(shear = previous.shear)
                    "symmetryFolds" -> out.copy(symmetryFolds = previous.symmetryFolds)
                    "vertexJitter" -> out.copy(vertexJitter = previous.vertexJitter)
                    "torusMajor" -> out.copy(torusMajor = previous.torusMajor)
                    "torusMinor" -> out.copy(torusMinor = previous.torusMinor)
                    "knotP" -> out.copy(knotP = previous.knotP)
                    "knotQ" -> out.copy(knotQ = previous.knotQ)
                    "gearTeeth" -> out.copy(gearTeeth = previous.gearTeeth)
                    "gearDepth" -> out.copy(gearDepth = previous.gearDepth)
                    "terrainSize" -> out.copy(terrainSize = previous.terrainSize)
                    "columnHeight" -> out.copy(columnHeight = previous.columnHeight)
                    "hueA" -> out.copy(hueA = previous.hueA)
                    "hueB" -> out.copy(hueB = previous.hueB)
                    "saturation" -> out.copy(saturation = previous.saturation)
                    "brightnessLow" -> out.copy(brightnessLow = previous.brightnessLow)
                    "brightnessHigh" -> out.copy(brightnessHigh = previous.brightnessHigh)
                    "gradientMode" -> out.copy(gradientMode = previous.gradientMode)
                    "colourNoise" -> out.copy(colourNoise = previous.colourNoise)
                    "flatShading" -> out.copy(flatShading = previous.flatShading)
                    "edgeSharpness" -> out.copy(edgeSharpness = previous.edgeSharpness)
                    "extremityCount" -> out.copy(extremityCount = previous.extremityCount)
                    "extremityLength" -> out.copy(extremityLength = previous.extremityLength)
                    "extremityThickness" -> out.copy(extremityThickness = previous.extremityThickness)
                    "extremityGrowthSeconds" -> out.copy(extremityGrowthSeconds = previous.extremityGrowthSeconds)
                    "extremityPulse" -> out.copy(extremityPulse = previous.extremityPulse)
                    "partCount" -> out.copy(partCount = previous.partCount)
                    "partMoveAmount" -> out.copy(partMoveAmount = previous.partMoveAmount)
                    "partMoveSpeed" -> out.copy(partMoveSpeed = previous.partMoveSpeed)
                    "texturePattern" -> out.copy(texturePattern = previous.texturePattern)
                    "textureScale" -> out.copy(textureScale = previous.textureScale)
                    "textureContrast" -> out.copy(textureContrast = previous.textureContrast)
                    "textureScrollSpeed" -> out.copy(textureScrollSpeed = previous.textureScrollSpeed)
                    "holdSeconds" -> out.copy(holdSeconds = previous.holdSeconds)
                    "spotlightIntensity" -> out.copy(spotlightIntensity = previous.spotlightIntensity)
                    "ambientLevel" -> out.copy(ambientLevel = previous.ambientLevel)
                    "morphIntervalSeconds" -> out.copy(morphIntervalSeconds = previous.morphIntervalSeconds)
                    "morphDurationSeconds" -> out.copy(morphDurationSeconds = previous.morphDurationSeconds)
                    "complexityStep" -> out.copy(complexityStep = previous.complexityStep)
                    else -> out
                }
            }
            return out
        }
    }
}

data class SettingRow(val group: String, val key: String, val label: String, val value: String)
