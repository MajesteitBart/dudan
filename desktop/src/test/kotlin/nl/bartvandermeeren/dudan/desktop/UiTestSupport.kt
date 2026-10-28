package nl.bartvandermeeren.dudan.desktop

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import java.io.File
import javax.imageio.ImageIO

/** Where the UI tests save their pictures; build.gradle.kts points it at build/ui-shots. */
private val shots = File(System.getProperty("dudan.shots") ?: "build/ui-shots")

/** The prompt bar: the text field on a chat besides the chat list's search. */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.composer() = onAllNodes(hasSetTextAction() and !hasContentDescription("Search chats")).onFirst()

/** A form field, by the label above it. */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.field(label: String) = onNode(hasSetTextAction() and hasContentDescription(label))

/** The chat list's search field. */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.chatSearch() = onNode(hasSetTextAction() and hasContentDescription("Search chats"))

/** Scrolls whichever lazy list holds a node matching [target] until that node is composed. */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.scrollListTo(target: SemanticsMatcher) {
    val lists = onAllNodes(hasScrollToNodeAction())
    val scrolled = lists.fetchSemanticsNodes().indices.any { i -> runCatching { lists[i].performScrollToNode(target) }.isSuccess }
    check(scrolled) { "No list holds $target" }
}

@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.waitForText(text: String, substring: Boolean = true, timeout: Long = 10_000) =
    waitUntil(timeoutMillis = timeout) { onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }

@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.shot(file: String) {
    waitForIdle()
    shots.mkdirs()
    ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(shots, "$file.png"))
}
