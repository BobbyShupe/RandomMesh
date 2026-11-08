package com.robert.randommesh

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Saves the committed mesh to Downloads/RandomMesh as .glb (Bobby's Fab
 *  pipeline format), .obj (universal) and a _settings.json of the 75
 *  settings that generated it. No permission needed on API 29+ (MediaStore)
 *  or in the app-specific dir on older versions. */
object MeshSaver {

    fun save(context: Context, snap: MeshRenderer.Snapshot): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val base = "RandomMesh_" + snap.mesh.name.replace(" ", "") + "_" + stamp
        writeFile(context, "$base.obj", "text/plain", buildObj(snap.mesh).toByteArray(Charsets.UTF_8))
        writeFile(context, "$base.glb", "model/gltf-binary", buildGlb(snap.mesh))
        writeFile(context, "${base}_settings.json", "application/json", buildSettingsJson(snap).toByteArray(Charsets.UTF_8))
        return "Download/RandomMesh/$base.obj (+ .glb, _settings.json)"
    }

    private fun buildObj(m: MeshData): String {
        val sb = StringBuilder()
        sb.append("# RandomMesh ").append(m.name).append("  ").append(m.vertexCount)
            .append(" verts  ").append(m.triangleCount).append(" tris\n")
        var i = 0
        while (i < m.vertexCount) {
            sb.append(String.format(Locale.US, "v %.6f %.6f %.6f\n", m.vertices[i * 3], m.vertices[i * 3 + 1], m.vertices[i * 3 + 2]))
            i++
        }
        i = 0
        while (i < m.vertexCount) {
            sb.append(String.format(Locale.US, "vt %.6f %.6f\n", m.uvs[i * 2], m.uvs[i * 2 + 1]))
            i++
        }
        i = 0
        while (i < m.vertexCount) {
            sb.append(String.format(Locale.US, "vn %.6f %.6f %.6f\n", m.normals[i * 3], m.normals[i * 3 + 1], m.normals[i * 3 + 2]))
            i++
        }
        var t = 0
        while (t < m.indices.size) {
            val a = (m.indices[t].toInt() and 0xFFFF) + 1
            val b = (m.indices[t + 1].toInt() and 0xFFFF) + 1
            val c = (m.indices[t + 2].toInt() and 0xFFFF) + 1
            sb.append("f ").append(a).append('/').append(a).append('/').append(a).append(' ')
                .append(b).append('/').append(b).append('/').append(b).append(' ')
                .append(c).append('/').append(c).append('/').append(c).append('\n')
            t += 3
        }
        return sb.toString()
    }

    private fun buildSettingsJson(snap: MeshRenderer.Snapshot): String {
        val json = JSONObject()
        json.put("mesh", snap.mesh.name)
        json.put("kind", snap.mesh.kind)
        json.put("vertexCount", snap.mesh.vertexCount)
        json.put("triangleCount", snap.mesh.triangleCount)
        val settings = JSONObject()
        for (row in snap.settings.entries()) settings.put(row.key, row.value)
        json.put("settings", settings)
        return json.toString(2)
    }

    /** Minimal glTF 2.0 binary (.glb): one mesh, POSITION/NORMAL/TEXCOORD_0
     *  + UNSIGNED_SHORT indices, one untextured PBR material. */
    private fun buildGlb(m: MeshData): ByteArray {
        val posBytes = m.vertices.size * 4
        val nrmBytes = m.normals.size * 4
        val uvBytes = m.uvs.size * 4
        val idxBytes = m.indices.size * 2
        val binLen = posBytes + nrmBytes + uvBytes + idxBytes
        val binPad = (4 - binLen % 4) % 4
        val bin = ByteBuffer.allocate(binLen + binPad).order(ByteOrder.LITTLE_ENDIAN)
        bin.asFloatBuffer().put(m.vertices)
        bin.position(posBytes)
        bin.asFloatBuffer().put(m.normals)
        bin.position(posBytes + nrmBytes)
        bin.asFloatBuffer().put(m.uvs)
        bin.position(posBytes + nrmBytes + uvBytes)
        bin.asShortBuffer().put(m.indices)

        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        var i = 0
        while (i < m.vertexCount) {
            minX = minOf(minX, m.vertices[i * 3]); maxX = maxOf(maxX, m.vertices[i * 3])
            minY = minOf(minY, m.vertices[i * 3 + 1]); maxY = maxOf(maxY, m.vertices[i * 3 + 1])
            minZ = minOf(minZ, m.vertices[i * 3 + 2]); maxZ = maxOf(maxZ, m.vertices[i * 3 + 2])
            i++
        }

        fun accessor(bufferView: Int, componentType: Int, count: Int, type: String, min: JSONArray?, max: JSONArray?): JSONObject {
            val o = JSONObject()
            o.put("bufferView", bufferView)
            o.put("componentType", componentType)
            o.put("count", count)
            o.put("type", type)
            if (min != null) o.put("min", min)
            if (max != null) o.put("max", max)
            return o
        }
        fun bufferView(offset: Int, length: Int): JSONObject {
            val o = JSONObject()
            o.put("buffer", 0)
            o.put("byteOffset", offset)
            o.put("byteLength", length)
            return o
        }
        val json = JSONObject()
        json.put("asset", JSONObject().put("version", "2.0").put("generator", "RandomMesh"))
        json.put("scene", 0)
        json.put("scenes", JSONArray().put(JSONObject().put("nodes", JSONArray().put(0))))
        json.put("nodes", JSONArray().put(JSONObject().put("mesh", 0).put("name", m.name)))
        val prim = JSONObject()
        val attrs = JSONObject()
        attrs.put("POSITION", 0); attrs.put("NORMAL", 1); attrs.put("TEXCOORD_0", 2)
        prim.put("attributes", attrs)
        prim.put("indices", 3)
        prim.put("material", 0)
        json.put("meshes", JSONArray().put(JSONObject().put("name", m.name).put("primitives", JSONArray().put(prim))))
        val mat = JSONObject()
        mat.put("name", "RandomMesh")
        mat.put("pbrMetallicRoughness", JSONObject()
            .put("baseColorFactor", JSONArray().put(1.0).put(1.0).put(1.0).put(1.0))
            .put("metallicFactor", 0.0).put("roughnessFactor", 0.85))
        json.put("materials", JSONArray().put(mat))
        val accessors = JSONArray()
        accessors.put(accessor(0, 5126, m.vertexCount, "VEC3",
            JSONArray().put(minX.toDouble()).put(minY.toDouble()).put(minZ.toDouble()),
            JSONArray().put(maxX.toDouble()).put(maxY.toDouble()).put(maxZ.toDouble())))
        accessors.put(accessor(1, 5126, m.vertexCount, "VEC3", null, null))
        accessors.put(accessor(2, 5126, m.vertexCount, "VEC2", null, null))
        accessors.put(accessor(3, 5123, m.indices.size, "SCALAR", null, null))
        json.put("accessors", accessors)
        val views = JSONArray()
        views.put(bufferView(0, posBytes))
        views.put(bufferView(posBytes, nrmBytes))
        views.put(bufferView(posBytes + nrmBytes, uvBytes))
        views.put(bufferView(posBytes + nrmBytes + uvBytes, idxBytes))
        json.put("bufferViews", views)
        json.put("buffers", JSONArray().put(JSONObject().put("byteLength", binLen + binPad)))

        val jsonBytes = json.toString().toByteArray(Charsets.UTF_8)
        val jsonPad = (4 - jsonBytes.size % 4) % 4
        val total = 12 + 8 + jsonBytes.size + jsonPad + 8 + binLen + binPad
        val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(0x46546C67) // 'glTF'
        out.putInt(2)
        out.putInt(total)
        out.putInt(jsonBytes.size + jsonPad)
        out.putInt(0x4E4F534A) // 'JSON'
        out.put(jsonBytes)
        repeat(jsonPad) { out.put(0x20.toByte()) }
        out.putInt(binLen + binPad)
        out.putInt(0x004E4942) // 'BIN'
        out.put(bin.array(), 0, binLen + binPad)
        return out.array()
    }

    fun writeFile(context: Context, displayName: String, mime: String, bytes: ByteArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues()
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            values.put(MediaStore.MediaColumns.MIME_TYPE, mime)
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/RandomMesh")
            val uri: Uri? = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Could not create $displayName in Downloads")
            context.contentResolver.openOutputStream(uri!!)?.use { it.write(bytes) }
                ?: throw IllegalStateException("Could not write $displayName")
        } else {
            val dir = File(context.getExternalFilesDir(null), "RandomMesh")
            dir.mkdirs()
            File(dir, displayName).writeBytes(bytes)
        }
    }
}
