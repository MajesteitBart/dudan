package nl.bartvandermeeren.dudan.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import nl.bartvandermeeren.dudan.chat.SkillTags

/** [text] with its tags for known skills ([slugs]) in [color]. */
fun highlightSkillTags(text: AnnotatedString, slugs: Set<String>, color: Color): AnnotatedString {
    val ranges = SkillTags.ranges(text.text, slugs)
    if (ranges.isEmpty()) return text
    return AnnotatedString.Builder(text).apply {
        ranges.forEach { addStyle(SpanStyle(color = color, fontWeight = FontWeight.Medium), it.first, it.last + 1) }
    }.toAnnotatedString()
}

fun highlightSkillTags(text: String, slugs: Set<String>, color: Color): AnnotatedString =
    highlightSkillTags(AnnotatedString(text), slugs, color)

/** Colors skill tags while they're typed; the text itself doesn't change, so offsets map one to one. */
data class SkillTagHighlight(val slugs: Set<String>, val color: Color) : VisualTransformation {
    override fun filter(text: AnnotatedString) = TransformedText(highlightSkillTags(text, slugs, color), OffsetMapping.Identity)
}
