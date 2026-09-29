package nl.bartvandermeeren.dudan.device

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** One thing Hermes can do on the phone, offered as an MCP tool. [run] returns a sentence for the agent. */
class PhoneTool(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val readOnly: Boolean = false,
    val run: (JsonObject) -> String,
)

/** A failure the agent should read and act on, such as an app name that matches nothing. */
class ToolFailure(message: String) : Exception(message)

/**
 * The MCP side of phone control: JSON-RPC messages as Streamable HTTP delivers them, answered with
 * plain JSON. It keeps no sessions, so Hermes needs no new handshake after the app restarts or the
 * phone was off the tailnet for a while.
 */
class McpHandler(private val tools: List<PhoneTool>, private val serverVersion: String) {
    private val byName = tools.associateBy { it.name }

    /** The response body for [body], or null when it held only notifications (HTTP 202, no body). */
    fun handle(body: String): String? {
        val message = try {
            Json.parseToJsonElement(body)
        } catch (_: SerializationException) {
            return error(JsonNull, PARSE_ERROR, "Parse error").toString()
        }
        if (message !is JsonArray) return respond(message)?.toString()
        if (message.isEmpty()) return error(JsonNull, INVALID_REQUEST, "Empty batch").toString()
        return message.mapNotNull(::respond).takeIf { it.isNotEmpty() }?.let { JsonArray(it).toString() }
    }

    private fun respond(message: JsonElement): JsonObject? {
        val o = message as? JsonObject ?: return error(JsonNull, INVALID_REQUEST, "Invalid request")
        val method = (o["method"] as? JsonPrimitive)?.contentOrNull
            ?: return null // a response to a server request; this server never sends any
        val id = o["id"] ?: return null // notifications, such as notifications/initialized
        val params = o["params"] as? JsonObject ?: JsonObject(emptyMap())
        return when (method) {
            "initialize" -> result(id, initialize(params))
            "ping" -> result(id, JsonObject(emptyMap()))
            "tools/list" -> result(id, buildJsonObject { put("tools", JsonArray(tools.map(::describe))) })
            "tools/call" -> call(id, params)
            else -> error(id, METHOD_NOT_FOUND, "Method not found: $method")
        }
    }

    private fun initialize(params: JsonObject): JsonObject {
        val requested = (params["protocolVersion"] as? JsonPrimitive)?.contentOrNull
        return buildJsonObject {
            put("protocolVersion", requested?.takeIf { it in PROTOCOL_VERSIONS } ?: PROTOCOL_VERSIONS.first())
            putJsonObject("capabilities") { putJsonObject("tools") { put("listChanged", false) } }
            putJsonObject("serverInfo") {
                put("name", "dudan-phone")
                put("version", serverVersion)
            }
            put("instructions", "These tools act on the user's Android phone, the device running the dudan app.")
        }
    }

    private fun describe(tool: PhoneTool) = buildJsonObject {
        put("name", tool.name)
        put("description", tool.description)
        put("inputSchema", tool.inputSchema)
        putJsonObject("annotations") {
            put("readOnlyHint", tool.readOnly)
            put("destructiveHint", false)
            put("openWorldHint", false)
        }
    }

    private fun call(id: JsonElement, params: JsonObject): JsonObject {
        val name = (params["name"] as? JsonPrimitive)?.contentOrNull
            ?: return error(id, INVALID_PARAMS, "Missing tool name")
        val tool = byName[name] ?: return error(id, INVALID_PARAMS, "Unknown tool: $name")
        val arguments = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
        val (text, failed) = try {
            tool.run(arguments) to false
        } catch (e: ToolFailure) {
            e.message.orEmpty() to true
        } catch (e: Exception) {
            "$name failed on the phone: ${e.message ?: e.javaClass.simpleName}" to true
        }
        return result(id, buildJsonObject {
            putJsonArray("content") {
                addJsonObject {
                    put("type", "text")
                    put("text", text)
                }
            }
            put("isError", failed)
        })
    }

    private fun result(id: JsonElement, result: JsonObject) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", result)
    }

    private fun error(id: JsonElement, code: Int, message: String) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        putJsonObject("error") {
            put("code", code)
            put("message", message)
        }
    }

    companion object {
        /** Newest first; the first is the answer to a client that asks for a version not listed. */
        val PROTOCOL_VERSIONS = listOf("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05")

        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602
    }
}

// ---- Tool schemas and arguments -------------------------------------------------------------------

internal fun objectSchema(vararg properties: Pair<String, JsonObject>, required: List<String> = emptyList()) = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") { properties.forEach { (name, schema) -> put(name, schema) } }
    if (required.isNotEmpty()) putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
}

internal fun stringProperty(description: String, options: List<String>? = null) = buildJsonObject {
    put("type", "string")
    put("description", description)
    if (options != null) putJsonArray("enum") { options.forEach { add(JsonPrimitive(it)) } }
}

internal fun intProperty(description: String, min: Int, max: Int) = buildJsonObject {
    put("type", "integer")
    put("description", description)
    put("minimum", min)
    put("maximum", max)
}

internal fun stringArrayProperty(description: String, options: List<String>) = buildJsonObject {
    put("type", "array")
    put("description", description)
    putJsonObject("items") {
        put("type", "string")
        putJsonArray("enum") { options.forEach { add(JsonPrimitive(it)) } }
    }
}

internal fun JsonObject.optionalString(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.ifEmpty { null }

internal fun JsonObject.requireString(name: String): String =
    optionalString(name) ?: throw ToolFailure("Missing argument: $name")

/** Models sometimes send numbers as strings or with a decimal part; both are fine here. */
internal fun JsonObject.optionalInt(name: String, min: Int, max: Int): Int? {
    val raw = this[name]?.takeIf { it !is JsonNull } ?: return null
    val value = (raw as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toInt() }
        ?: throw ToolFailure("$name must be a whole number")
    if (value !in min..max) throw ToolFailure("$name must be between $min and $max")
    return value
}

internal fun JsonObject.requireInt(name: String, min: Int, max: Int): Int =
    optionalInt(name, min, max) ?: throw ToolFailure("Missing argument: $name")

internal fun JsonObject.stringList(name: String): List<String> =
    (this[name] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase() }.orEmpty()
