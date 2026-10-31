package com.dsh.remote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A deliberately small Markdown renderer.
 *
 * DSH replies are Markdown, and the desktop GUI renders them as such. Showing the raw text
 * (with `**bold**`, `- bullets`, fenced code) is what made the phone transcript hard to read,
 * so this covers the constructs the agent actually emits: headings, fenced code, bullet and
 * ordered lists, block quotes, rules, and inline bold/italic/code/strike/links.
 */
sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Code(val lang: String, val code: String) : MdBlock
    data class Bullet(val text: String, val ordered: Boolean, val marker: String, val indent: Int) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
    data object Rule : MdBlock
}

private val FENCE = Regex("^\\s*```(.*)$")
private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
private val BULLET = Regex("^(\\s*)([-*+])\\s+(.*)$")
private val ORDERED = Regex("^(\\s*)(\\d+[.)])\\s+(.*)$")
private val RULE = Regex("^\\s*([-*_])\\1{2,}\\s*$")
private val TABLE_ROW = Regex("^\\s*\\|.*\\|\\s*$")
private val TABLE_SEP = Regex("^\\s*\\|[\\s:|-]+\\|\\s*$")

private fun splitRow(line: String): List<String> =
    line.trim().trim('|').split('|').map { it.trim() }

fun parseMarkdown(source: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = source.replace("\r\n", "\n").split('\n')
    var i = 0
    val paragraph = StringBuilder()

    fun flush() {
        if (paragraph.isNotEmpty()) {
            blocks += MdBlock.Paragraph(paragraph.toString().trim())
            paragraph.setLength(0)
        }
    }

    while (i < lines.size) {
        val line = lines[i]

        // Fenced code spans until the closing fence (or EOF).
        val fence = FENCE.find(line)
        if (fence != null) {
            flush()
            val lang = fence.groupValues[1].trim()
            val body = StringBuilder()
            i++
            while (i < lines.size && !FENCE.matches(lines[i])) {
                if (body.isNotEmpty()) body.append('\n')
                body.append(lines[i])
                i++
            }
            i++ // consume closing fence
            blocks += MdBlock.Code(lang, body.toString())
            continue
        }

        if (line.isBlank()) {
            flush()
            i++
            continue
        }

        val heading = HEADING.find(line)
        if (heading != null) {
            flush()
            blocks += MdBlock.Heading(heading.groupValues[1].length, heading.groupValues[2].trim())
            i++
            continue
        }

        if (RULE.matches(line)) {
            flush()
            blocks += MdBlock.Rule
            i++
            continue
        }

        // GitHub-style table: header row, separator row, then body rows.
        if (TABLE_ROW.matches(line) && i + 1 < lines.size && TABLE_SEP.matches(lines[i + 1])) {
            flush()
            val header = splitRow(line)
            val rows = mutableListOf<List<String>>()
            i += 2
            while (i < lines.size && TABLE_ROW.matches(lines[i])) {
                rows += splitRow(lines[i])
                i++
            }
            blocks += MdBlock.Table(header, rows)
            continue
        }

        val bullet = BULLET.find(line)
        if (bullet != null) {
            flush()
            blocks += MdBlock.Bullet(
                text = bullet.groupValues[3],
                ordered = false,
                marker = "•",
                indent = bullet.groupValues[1].length / 2,
            )
            i++
            continue
        }

        val ordered = ORDERED.find(line)
        if (ordered != null) {
            flush()
            blocks += MdBlock.Bullet(
                text = ordered.groupValues[3],
                ordered = true,
                marker = ordered.groupValues[2],
                indent = ordered.groupValues[1].length / 2,
            )
            i++
            continue
        }

        if (line.trimStart().startsWith(">")) {
            flush()
            blocks += MdBlock.Quote(line.trimStart().removePrefix(">").trim())
            i++
            continue
        }

        if (paragraph.isNotEmpty()) paragraph.append(' ')
        paragraph.append(line.trim())
        i++
    }
    flush()
    return blocks
}

@Composable
fun MarkdownView(
    source: String,
    fontSize: TextUnit,
    color: Color,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(source) { parseMarkdown(source) }
    val lineHeight = (fontSize.value * 1.55f).sp

    Column(modifier) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> {
                    Text(
                        text = styled(block.text, fontSize, color, accent),
                        fontSize = when (block.level) {
                            1 -> (fontSize.value * 1.35f).sp
                            2 -> (fontSize.value * 1.2f).sp
                            else -> (fontSize.value * 1.08f).sp
                        },
                        lineHeight = (fontSize.value * 1.5f).sp,
                        fontWeight = FontWeight.SemiBold,
                        color = color,
                        modifier = Modifier.padding(top = 10.dp, bottom = 3.dp),
                    )
                }

                is MdBlock.Code -> Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp)
                        .background(Color(0x14808080), RoundedCornerShape(8.dp)),
                ) {
                    Column(Modifier.padding(10.dp)) {
                        if (block.lang.isNotBlank()) {
                            Text(
                                block.lang,
                                fontSize = (fontSize.value * 0.72f).sp,
                                color = color.copy(alpha = 0.5f),
                                modifier = Modifier.padding(bottom = 5.dp),
                            )
                        }
                        Text(
                            text = block.code,
                            fontFamily = FontFamily.Monospace,
                            fontSize = (fontSize.value * 0.88f).sp,
                            lineHeight = (fontSize.value * 1.35f).sp,
                            color = color,
                        )
                    }
                }

                is MdBlock.Bullet -> Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = (block.indent * 14).dp, top = 1.dp, bottom = 1.dp),
                ) {
                    Text(
                        text = block.marker,
                        fontSize = fontSize,
                        lineHeight = lineHeight,
                        color = color.copy(alpha = 0.65f),
                        modifier = Modifier.width(if (block.ordered) 26.dp else 15.dp),
                    )
                    Text(
                        text = styled(block.text, fontSize, color, accent),
                        fontSize = fontSize,
                        lineHeight = lineHeight,
                        color = color,
                    )
                }

                is MdBlock.Quote -> Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                ) {
                    Box(
                        Modifier
                            .width(2.dp)
                            .padding(end = 0.dp)
                            .background(accent.copy(alpha = 0.5f)),
                    ) {
                        Text("", fontSize = fontSize)
                    }
                    Text(
                        text = styled(block.text, fontSize, color.copy(alpha = 0.85f), accent),
                        fontSize = fontSize,
                        lineHeight = lineHeight,
                        color = color.copy(alpha = 0.85f),
                        modifier = Modifier.padding(start = 9.dp),
                    )
                }

                MdBlock.Rule -> Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .background(color.copy(alpha = 0.15f)),
                ) {
                    Text("", fontSize = 1.sp)
                }

                is MdBlock.Table -> TableView(block, fontSize, color, accent)

                is MdBlock.Paragraph -> Text(
                    text = styled(block.text, fontSize, color, accent),
                    fontSize = fontSize,
                    lineHeight = lineHeight,
                    color = color,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }
}

/** Tables render as an even-weight grid with hairline separators. */
@Composable
private fun TableView(table: MdBlock.Table, fontSize: TextUnit, color: Color, accent: Color) {
    val columns = maxOf(table.header.size, table.rows.maxOfOrNull { it.size } ?: 0).coerceAtLeast(1)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .background(Color(0x0D808080), RoundedCornerShape(8.dp))
            .padding(vertical = 4.dp),
    ) {
        TableRowCells(table.header, columns, fontSize, color, accent, header = true)
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(color.copy(alpha = 0.12f))
        )
        table.rows.forEach { row ->
            TableRowCells(row, columns, fontSize, color, accent, header = false)
        }
    }
}

@Composable
private fun TableRowCells(
    cells: List<String>,
    columns: Int,
    fontSize: TextUnit,
    color: Color,
    accent: Color,
    header: Boolean,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 9.dp, vertical = 5.dp)
    ) {
        for (c in 0 until columns) {
            Text(
                text = styled(cells.getOrNull(c).orEmpty(), fontSize, color, accent),
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 6.dp),
                fontSize = (fontSize.value * 0.92f).sp,
                lineHeight = (fontSize.value * 1.35f).sp,
                fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
                color = color,
            )
        }
    }
}

/** Inline spans: `code`, **bold**, *italic*, ~~strike~~, [label](url) → label. */private fun styled(source: String, fontSize: TextUnit, color: Color, accent: Color): AnnotatedString =
    buildAnnotatedString { appendInline(source, fontSize, color, accent) }

private fun androidx.compose.ui.text.AnnotatedString.Builder.appendInline(
    source: String,
    fontSize: TextUnit,
    color: Color,
    accent: Color,
) {
    var i = 0
    val plain = StringBuilder()

    fun flushPlain() {
        if (plain.isNotEmpty()) {
            append(plain.toString())
            plain.setLength(0)
        }
    }

    while (i < source.length) {
        val ch = source[i]

        // `inline code`
        if (ch == '`') {
            val end = source.indexOf('`', i + 1)
            if (end > i) {
                flushPlain()
                withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = (fontSize.value * 0.94f).sp,
                        background = Color(0x16808080),
                        color = accent,
                    )
                ) { append(source.substring(i + 1, end)) }
                i = end + 1
                continue
            }
        }

        // **bold**
        if (ch == '*' && i + 1 < source.length && source[i + 1] == '*') {
            val end = source.indexOf("**", i + 2)
            if (end > i) {
                flushPlain()
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                    appendInline(source.substring(i + 2, end), fontSize, color, accent)
                }
                i = end + 2
                continue
            }
        }

        // ~~strike~~
        if (ch == '~' && i + 1 < source.length && source[i + 1] == '~') {
            val end = source.indexOf("~~", i + 2)
            if (end > i) {
                flushPlain()
                withStyle(SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough)) {
                    append(source.substring(i + 2, end))
                }
                i = end + 2
                continue
            }
        }

        // *italic*
        if (ch == '*') {
            val end = source.indexOf('*', i + 1)
            if (end > i + 1) {
                flushPlain()
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    appendInline(source.substring(i + 1, end), fontSize, color, accent)
                }
                i = end + 1
                continue
            }
        }

        // [label](url) -> label (tappable links are out of scope on a phone bubble)
        if (ch == '[') {
            val close = source.indexOf(']', i + 1)
            if (close > i && close + 1 < source.length && source[close + 1] == '(') {
                val paren = source.indexOf(')', close + 2)
                if (paren > close) {
                    flushPlain()
                    withStyle(SpanStyle(color = accent)) {
                        appendInline(source.substring(i + 1, close), fontSize, color, accent)
                    }
                    i = paren + 1
                    continue
                }
            }
        }

        plain.append(ch)
        i++
    }
    flushPlain()
}
