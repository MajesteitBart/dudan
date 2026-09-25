package nl.bartvandermeeren.aight.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Icons Material doesn't have in the Gemini style: two-line menu, panel toggle, new chat, Live. */
object AightIcons {
    private fun icon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply(block).build()

    private val stroke = SolidColor(Color.White)

    val Menu: ImageVector by lazy {
        icon("Menu") {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
                moveTo(4f, 9f); lineTo(20f, 9f)
                moveTo(4f, 15f); lineTo(20f, 15f)
            }
        }
    }

    val SidePanel: ImageVector by lazy {
        icon("SidePanel") {
            path(stroke = stroke, strokeLineWidth = 1.8f, strokeLineJoin = StrokeJoin.Round) {
                moveTo(7f, 3.5f); lineTo(17f, 3.5f)
                arcTo(3.5f, 3.5f, 0f, false, true, 20.5f, 7f)
                lineTo(20.5f, 17f)
                arcTo(3.5f, 3.5f, 0f, false, true, 17f, 20.5f)
                lineTo(7f, 20.5f)
                arcTo(3.5f, 3.5f, 0f, false, true, 3.5f, 17f)
                lineTo(3.5f, 7f)
                arcTo(3.5f, 3.5f, 0f, false, true, 7f, 3.5f)
                close()
            }
            path(stroke = stroke, strokeLineWidth = 2.2f, strokeLineCap = StrokeCap.Round) {
                moveTo(8.5f, 7.5f); lineTo(8.5f, 16.5f)
            }
        }
    }

    val NewChat: ImageVector by lazy {
        icon("NewChat") {
            // Open circle with a gap at the top right, pencil entering the gap.
            path(stroke = stroke, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round) {
                moveTo(20f, 12.6f)
                arcTo(8f, 8f, 0f, true, true, 11.4f, 4f)
            }
            path(stroke = stroke, strokeLineWidth = 1.7f, strokeLineJoin = StrokeJoin.Round) {
                moveTo(10.6f, 13.4f)
                lineTo(11.3f, 11.0f)
                lineTo(16.3f, 6.0f)
                lineTo(18.0f, 7.7f)
                lineTo(13.0f, 12.7f)
                close()
                moveTo(17.3f, 5.0f)
                lineTo(18.1f, 4.2f)
                arcTo(1.2f, 1.2f, 0f, false, true, 19.8f, 5.9f)
                lineTo(19.0f, 6.7f)
                close()
            }
        }
    }

    val Live: ImageVector by lazy {
        icon("Live") {
            path(fill = stroke) {
                moveTo(12.2f, 12f)
                arcTo(3.4f, 3.4f, 0f, true, true, 5.4f, 12f)
                arcTo(3.4f, 3.4f, 0f, true, true, 12.2f, 12f)
                close()
            }
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
                moveTo(15f, 8.2f)
                arcTo(5.2f, 5.2f, 0f, false, true, 15f, 15.8f)
                moveTo(18f, 5.4f)
                arcTo(8.8f, 8.8f, 0f, false, true, 18f, 18.6f)
            }
        }
    }
}
