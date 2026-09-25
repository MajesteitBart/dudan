package nl.bartvandermeeren.aight.ui.chat

private val dateSuffix = Regex("-\\d{8}$")
private val paramSize = Regex("^\\d+(\\.\\d+)?[bm]$")
private val acronyms = setOf("gpt", "glm", "oss", "llm", "qwq")

/**
 * Short display name for a provider model id, so the top bar reads like Gemini's "Flash" instead of
 * "anthropic/claude-opus-4.6": "Opus 4.6", "GPT-5.5", "Gemini 3 Flash", "Hermes 4 405B".
 */
fun prettyModelName(id: String): String {
    var name = id.substringAfterLast('/').substringAfterLast(':').replace(dateSuffix, "")
    if (name.isBlank()) return id
    var tokens = name.split('-', '_').filter { it.isNotEmpty() }
    if (tokens.size > 1 && tokens.first().equals("claude", ignoreCase = true)) tokens = tokens.drop(1)
    val out = StringBuilder()
    tokens.forEachIndexed { index, raw ->
        val token = raw.lowercase()
        val pretty = when {
            token in acronyms -> token.uppercase()
            paramSize.matches(token) -> token.uppercase()
            token.first().isLetter() -> token.replaceFirstChar { it.uppercase() }
            else -> token
        }
        if (index > 0) out.append(if (tokens[index - 1].lowercase() == "gpt") '-' else ' ')
        out.append(pretty)
    }
    name = out.toString()
    return name
}
