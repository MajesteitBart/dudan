package nl.bartvandermeeren.dudan.chat

import nl.bartvandermeeren.dudan.data.SkillInfo

/**
 * `$skill` tags in a message. A tag is the skill's name in lowercase with spaces as hyphens. Hermes'
 * API server doesn't expand skills in a message, so the tagged ones go along as per-turn instructions
 * that tell the agent to load them with skill_view.
 */
object SkillTags {
    // A `$` that doesn't follow a word character or another `$`, then the characters a slug can have,
    // so a skill whose name starts with `_` or `-` can be tagged too. "a$b" isn't a tag, and "$5" stays
    // plain text because no skill is called 5.
    private val TAG = Regex("""(?<![\p{L}\p{N}_$])\$([\p{L}\p{N}_-]+)""")
    private val WHITESPACE = Regex("""\s+""")
    private val NOT_TAG_CHAR = Regex("""[^\p{L}\p{N}_-]""")

    /** How [name] is written after the `$`. */
    fun slug(name: String): String = name.trim().lowercase().replace(WHITESPACE, "-").replace(NOT_TAG_CHAR, "")

    /** Names of the known [skills] tagged in [text], in order and without repeats. Unknown tags are plain text. */
    fun find(text: String, skills: Collection<SkillInfo>): List<String> {
        if ('$' !in text || skills.isEmpty()) return emptyList()
        val bySlug = skills.associateBy { slug(it.name) }
        return TAG.findAll(text).mapNotNull { resolve(it.groupValues[1], bySlug.keys)?.let(bySlug::get)?.name }.distinct().toList()
    }

    /** Where the known tags in [text] sit, `$` included, for highlighting. */
    fun ranges(text: String, slugs: Set<String>): List<IntRange> {
        if ('$' !in text || slugs.isEmpty()) return emptyList()
        return TAG.findAll(text).mapNotNull { match ->
            resolve(match.groupValues[1], slugs)?.let { slug -> match.range.first..match.range.first + slug.length }
        }.toList()
    }

    // "$todoist-task-operator-" at the end of a sentence still counts; the trailing hyphen is punctuation.
    private fun resolve(token: String, slugs: Set<String>): String? {
        val lower = token.lowercase()
        return lower.takeIf { it in slugs } ?: lower.trimEnd('-', '_').takeIf { it in slugs }
    }

    /** A tag being typed: the `$` at [start], the tag running to [end], and what was typed before the cursor. */
    data class Query(val start: Int, val end: Int, val text: String)

    /** The tag the cursor is in or right after, or null when it isn't in one. */
    fun queryAt(text: String, cursor: Int): Query? {
        if (cursor !in 0..text.length) return null
        var dollar = cursor - 1
        while (dollar >= 0 && isTagChar(text[dollar])) dollar--
        if (dollar < 0 || text[dollar] != '$') return null
        if (dollar > 0 && (isTagChar(text[dollar - 1]) || text[dollar - 1] == '$')) return null
        var end = cursor
        while (end < text.length && isTagChar(text[end])) end++
        return Query(dollar, end, text.substring(dollar + 1, cursor))
    }

    private fun isTagChar(c: Char) = c.isLetterOrDigit() || c == '-' || c == '_'

    /** [text] with the tag at [query] replaced by [skill]'s, followed by a space; returns the text and the new cursor. */
    fun complete(text: String, query: Query, skill: SkillInfo): Pair<String, Int> {
        val tag = "\$" + slug(skill.name)
        val after = text.substring(query.end)
        val spaced = if (after.startsWith(" ")) tag else "$tag "
        return (text.substring(0, query.start) + spaced + after) to query.start + tag.length + 1
    }

    /** Skills for what was typed after the `$`: names that start with it, then names and descriptions that contain it. */
    fun suggest(query: String, skills: List<SkillInfo>): List<SkillInfo> {
        val q = query.lowercase()
        val sorted = skills.sortedBy { slug(it.name) }
        if (q.isEmpty()) return sorted
        val starts = sorted.filter { slug(it.name).startsWith(q) }
        val inName = sorted.filter { q in slug(it.name) && it !in starts }
        val inDescription = sorted.filter { it.description.orEmpty().lowercase().contains(q) && it !in starts && it !in inName }
        return starts + inName + inDescription
    }

    /** What Hermes gets as instructions for a message that tags [names], or null when it tags none. */
    fun instructions(names: List<String>): String? {
        if (names.isEmpty()) return null
        return "The user tagged these skills in their message with a \$ sign: ${names.joinToString(", ")}. " +
            "Load each one with skill_view before you answer, and follow its instructions."
    }
}
