package nl.bartvandermeeren.dudan.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import java.util.Locale
import nl.bartvandermeeren.dudan.ui.components.GlassBackdrop
import nl.bartvandermeeren.dudan.ui.theme.DudanTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** The tray icon's menu, drawn as in its own window on the default sky, and what each item does. */
@OptIn(ExperimentalTestApi::class)
class TrayMenuTest {
    @Before
    fun english() {
        Locale.setDefault(Locale.US)
    }

    @Test
    fun eachItemDoesWhatItSays() = runDesktopComposeUiTest(width = 410, height = 230) {
        val chosen = mutableListOf<String>()
        setContent {
            DudanTheme {
                Box(Modifier.fillMaxSize()) {
                    GlassBackdrop(Modifier.fillMaxSize(), dim = true)
                    TrayMenu(
                        quickKeys = "Ctrl+Alt+Space",
                        onOpen = { chosen += "open" },
                        onQuickAsk = { chosen += "quick" },
                        onQuit = { chosen += "quit" },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(14.dp).width(336.dp),
                    )
                }
            }
        }
        shot("tray-menu")
        onNodeWithText("Open dudan").performClick()
        onNodeWithText("Ask a quick question").performClick()
        onNodeWithText("Quit dudan").performClick()
        assertEquals(listOf("open", "quick", "quit"), chosen)
    }
}
