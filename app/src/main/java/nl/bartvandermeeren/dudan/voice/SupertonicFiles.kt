package nl.bartvandermeeren.dudan.voice

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Turns Supertonic's published JSON files into the binary files sherpa-onnx loads, the same way
 * sherpa's generate_indexer_bin.py and generate_voices_bin.py do. The output matches the files in
 * sherpa's own Supertonic package byte for byte.
 */
object SupertonicFiles {
    const val INDEXER = "unicode_indexer.bin"
    const val VOICES = "voice.bin"
    const val STYLES_DIR = "voice_styles"

    /** Writes both binary files into [dir] and deletes the JSON they came from. */
    fun prepare(dir: File) {
        val indexerJson = File(dir, "unicode_indexer.json")
        File(dir, INDEXER).writeBytes(indexer(indexerJson.readText()))
        indexerJson.delete()
        val styles = File(dir, STYLES_DIR)
        val files = styles.listFiles { f -> f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }
        check(files.isNotEmpty()) { "no voice styles" }
        File(dir, VOICES).writeBytes(voices(files.map { it.readText() }))
        styles.deleteRecursively()
    }

    /** A JSON array of code point indexes, as little-endian int32. */
    fun indexer(json: String): ByteArray {
        val values = Json.parseToJsonElement(json).jsonArray
        val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { buffer.putInt(it.jsonPrimitive.int) }
        return buffer.array()
    }

    /**
     * Voice styles, in the order given, as one file: the text-to-latent dims [n, d1, d2] and duration dims
     * [n, d1, d2] as int64, then every voice's text-to-latent floats, then every voice's duration floats.
     */
    fun voices(jsons: List<String>): ByteArray {
        val styles = jsons.map { Json.parseToJsonElement(it).jsonObject }
        val ttl = styles.map { Style.of(it, "style_ttl") }
        val dp = styles.map { Style.of(it, "style_dp") }
        check(ttl.all { it.dims == ttl[0].dims } && dp.all { it.dims == dp[0].dims }) { "voice styles differ in shape" }
        val floats = ttl.sumOf { it.values.size } + dp.sumOf { it.values.size }
        val buffer = ByteBuffer.allocate(6 * 8 + floats * 4).order(ByteOrder.LITTLE_ENDIAN)
        listOf(ttl[0], dp[0]).forEach { first ->
            buffer.putLong(styles.size.toLong()).putLong(first.dims[1]).putLong(first.dims[2])
        }
        (ttl + dp).forEach { style -> style.values.forEach { buffer.putFloat(it) } }
        return buffer.array()
    }

    private class Style(val dims: List<Long>, val values: FloatArray) {
        companion object {
            fun of(voice: JsonObject, key: String): Style {
                val style = voice.getValue(key).jsonObject
                val dims = style.getValue("dims").jsonArray.map { it.jsonPrimitive.long }
                check(dims.size == 3 && dims[0] == 1L) { "$key must be [1, d1, d2], got $dims" }
                val values = mutableListOf<Float>().also { flatten(style.getValue("data"), it) }
                check(values.size.toLong() == dims[1] * dims[2]) { "$key has ${values.size} values for $dims" }
                check(values.all { it.isFinite() }) { "$key has values that aren't finite" }
                return Style(dims, values.toFloatArray())
            }

            // Through double, as numpy does: parsing straight to float can round differently in rare cases.
            private fun flatten(element: JsonElement, into: MutableList<Float>) {
                if (element is JsonArray) element.forEach { flatten(it, into) } else into += element.jsonPrimitive.double.toFloat()
            }
        }
    }
}
