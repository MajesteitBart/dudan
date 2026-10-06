package nl.bartvandermeeren.dudan.chat

import nl.bartvandermeeren.dudan.openui.OpenUiPrompt

/**
 * Where a question was asked. Every dudan turn reaches Hermes as a plain API server request, and
 * Hermes' prompt for those says the display is unknown and asks for plain text. Without this the
 * agent can't tell that the user is on their phone or that the reply will be heard.
 */
data class TurnOrigin(
    val surface: Surface = Surface.App,
    /** The reply is read aloud: a spoken question in the assistant overlay, or Live mode. */
    val spoken: Boolean = false,
    /** The attached image is a screenshot of the phone's screen ("Ask about screen"). */
    val screenshot: Boolean = false,
) {
    enum class Surface { App, Assistant, Live }
}

/**
 * The instructions sent with every turn. Hermes adds them to the end of the system prompt for that
 * turn only and doesn't keep them in the transcript. The OpenUI prompt goes first because it never
 * changes, which keeps the cached part of the prompt as long as possible; the turn's context follows.
 */
object TurnInstructions {
    /** The tool names of dudan's phone MCP server (device/PhoneTools). Hermes shows them as mcp__<server>__<name>. */
    private const val PHONE_TOOLS = "open_app, list_apps, open_link, set_timer, set_alarm, media, phone_status"

    /**
     * Everything a turn sends. A reply that is read aloud gets no OpenUI prompt, since a card can't be heard.
     * [phoneControl] is null when the turn shouldn't depend on this phone's setting; see [context].
     */
    fun build(origin: TurnOrigin, phoneControl: Boolean?, richReplies: Boolean): String =
        listOfNotNull(
            OpenUiPrompt.instructions.takeIf { richReplies && !origin.spoken },
            "## This turn\n\n" + context(origin, phoneControl),
        ).joinToString("\n\n")

    /** The turn's context. Without [phoneControl] it says nothing about phone tools. */
    fun context(origin: TurnOrigin, phoneControl: Boolean?): String = buildList {
        add("The user is talking to you through dudan, the Hermes app on their Android phone.")
        when (origin.surface) {
            TurnOrigin.Surface.App -> Unit
            TurnOrigin.Surface.Assistant -> add(
                "They opened you as the phone's assistant, with the side button, in a panel over whatever they were doing. " +
                    "Keep the answer short.",
            )
            TurnOrigin.Surface.Live -> add("This is Live mode, a hands-free voice conversation.")
        }
        if (origin.spoken) {
            add("Your reply is read aloud, so write it to be heard: short spoken sentences, no tables, code blocks, links or emoji.")
        } else {
            add(
                "dudan renders Markdown, including headings, lists, tables, code blocks and links, so use it where it helps, " +
                    "even though the API server note earlier in this prompt asks for plain text.",
            )
        }
        if (origin.screenshot) add("The attached image is a screenshot of their screen from the moment they opened you.")
        when (phoneControl) {
            true -> add(
                "If you have the phone tools ($PHONE_TOOLS), they act on this same phone. When they ask for something the phone does, " +
                    "such as a timer or alarm, opening an app, directions or music, do it with those tools " +
                    "instead of explaining how or scheduling a Hermes task.",
            )
            false -> add(
                "Phone control is off in dudan, so you can't act on this phone. If they ask you to, tell them to turn on Phone control in dudan's settings.",
            )
            null -> Unit
        }
    }.joinToString(" ")
}
