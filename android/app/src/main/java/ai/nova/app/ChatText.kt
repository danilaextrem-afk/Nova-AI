package ai.nova.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * Light formatting for chat replies, so models' markup is never shown as raw symbols:
 * **bold** → bold, *action* → italic and softer, "# heading" → plain line, "- item" → "• item".
 * Unmatched asterisks are kept as typed.
 */
fun chatText(raw: String, actionColor: Color): AnnotatedString {
    val text = raw.lines().joinToString("\n") { line ->
        line.replace(Regex("^\\s{0,3}#{1,6}\\s+"), "").replace(Regex("^(\\s*)[-*•]\\s+"), "$1• ")
    }
    val pattern = Regex("\\*\\*(.+?)\\*\\*|\\*([^*\\n]+?)\\*")
    return buildAnnotatedString {
        var at = 0
        for (m in pattern.findAll(text)) {
            append(text.substring(at, m.range.first))
            val bold = m.groups[1]?.value
            if (bold != null) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(bold) }
            else withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = actionColor)) { append(m.groups[2]!!.value) }
            at = m.range.last + 1
        }
        append(text.substring(at))
    }
}
