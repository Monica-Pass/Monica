package takagi.ru.monica.update

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import java.net.URI
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text as MarkdownText
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

/** Public release prose only: no HTML execution, image loading or automatic link navigation. */
@Composable
fun ReleaseNotesMarkdown(
    markdown: String,
    modifier: Modifier = Modifier,
    onTruncated: (Boolean) -> Unit = {},
    onOpenLink: (String) -> Unit,
) {
    // A replacement gets fresh state immediately; an older parse cannot publish into that state.
    var document by remember(markdown) { mutableStateOf<ReleaseNotesDocument?>(null) }
    val reportTruncation by rememberUpdatedState(onTruncated)
    val openLink by rememberUpdatedState(onOpenLink)
    LaunchedEffect(markdown) {
        reportTruncation(false)
        val parsed = withContext(Dispatchers.Default) {
            parseReleaseNotesMarkdown(markdown) { coroutineContext.ensureActive() }
        }
        ensureActive()
        document = parsed
        reportTruncation(parsed.truncated)
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        document?.let { parsed ->
            ReleaseNotesBlocks(parsed.blocks, onOpenLink = { url ->
                releaseNotesHttpUrl(url)?.let(openLink)
            })
            if (parsed.truncated) Text("…", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Immutable
internal data class ReleaseNotesDocument(val blocks: List<ReleaseNotesBlock>, val truncated: Boolean)

@Immutable
internal sealed interface ReleaseNotesBlock {
    data class Prose(val spans: List<ReleaseNotesSpan>, val heading: Int = 0) : ReleaseNotesBlock
    data class CodeBlock(val text: String) : ReleaseNotesBlock
    data class Quote(val blocks: List<ReleaseNotesBlock>) : ReleaseNotesBlock
    data class Items(val items: List<ReleaseNotesItem>, val start: Int?) : ReleaseNotesBlock
    data class Table(val rows: List<List<ReleaseNotesCell>>) : ReleaseNotesBlock
    data object Divider : ReleaseNotesBlock
}

@Immutable
internal data class ReleaseNotesSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val strike: Boolean = false,
    val link: String? = null,
)

@Immutable
internal data class ReleaseNotesItem(val blocks: List<ReleaseNotesBlock>, val checked: Boolean? = null)

@Immutable
internal data class ReleaseNotesCell(
    val spans: List<ReleaseNotesSpan>,
    val header: Boolean,
    val alignment: ReleaseNotesAlignment,
)

internal enum class ReleaseNotesAlignment { LEFT, CENTER, RIGHT }

internal const val RELEASE_NOTES_MAX_CHARS = 65_536
private const val MAX_NODES = 2_000
private const val MAX_BLOCKS = 256
private const val MAX_DEPTH = 12
private const val MAX_TABLE_COLUMNS = 16

/** Strict absolute web links; URI parsing here is JVM-only and never opens a connection. */
internal fun releaseNotesHttpUrl(value: String): String? {
    if (value.length > 2_048 || value.any { it.isWhitespace() || it.isISOControl() }) return null
    val uri = try { URI(value) } catch (_: IllegalArgumentException) { return null }
        catch (_: java.net.URISyntaxException) { return null }
    if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("https", "http")) return null
    if (uri.isOpaque || uri.host.isNullOrEmpty() || uri.rawUserInfo != null) return null
    if (uri.port != -1 && uri.port !in 1..65_535) return null
    return uri.toASCIIString()
}

/** Bounded parse and model conversion. The caller supplies its coroutine cancellation checkpoint. */
internal fun parseReleaseNotesMarkdown(
    markdown: String,
    checkCancellation: () -> Unit = {},
): ReleaseNotesDocument {
    checkCancellation()
    var end = minOf(markdown.length, RELEASE_NOTES_MAX_CHARS)
    // Do not split a Unicode surrogate pair when bounding a release body.
    if (end < markdown.length && end > 0 && markdown[end - 1].isHighSurrogate()) end--
    val bounded = markdown.substring(0, end)
    val parser = Parser.builder().extensions(listOf(
        TablesExtension.create(), StrikethroughExtension.create(),
    )).build()
    // Task-list-items' postprocessor recursively visits unbounded AST depth. Recognize task
    // markers during our own bounded conversion instead; no recursive extension is installed.
    val root = parser.parse(bounded)
    checkCancellation()
    val reader = ReleaseNotesReader(checkCancellation, truncated = end < markdown.length)
    return ReleaseNotesDocument(reader.blocks(root, 0), reader.truncated)
}

private class ReleaseNotesReader(
    private val checkpoint: () -> Unit,
    var truncated: Boolean,
) {
    private var nodes = 0
    private var blockCount = 0

    private fun accept(depth: Int): Boolean {
        checkpoint()
        if (depth > MAX_DEPTH || nodes >= MAX_NODES) {
            truncated = true
            return false
        }
        nodes++
        return true
    }

    fun blocks(parent: Node, depth: Int): List<ReleaseNotesBlock> = buildList {
        var child = parent.firstChild
        while (child != null) {
            if (!accept(depth) || blockCount >= MAX_BLOCKS) {
                truncated = true
                break
            }
            blockCount++
            when (child) {
                is Paragraph -> add(ReleaseNotesBlock.Prose(inlines(child, depth + 1)))
                is Heading -> add(ReleaseNotesBlock.Prose(inlines(child, depth + 1), child.level))
                is FencedCodeBlock -> add(ReleaseNotesBlock.CodeBlock(child.literal.removeSuffix("\n")))
                is IndentedCodeBlock -> add(ReleaseNotesBlock.CodeBlock(child.literal.removeSuffix("\n")))
                is HtmlBlock -> add(ReleaseNotesBlock.Prose(listOf(ReleaseNotesSpan(child.literal))))
                is BlockQuote -> add(ReleaseNotesBlock.Quote(blocks(child, depth + 1)))
                is BulletList -> add(ReleaseNotesBlock.Items(items(child, depth + 1), null))
                is OrderedList -> add(ReleaseNotesBlock.Items(items(child, depth + 1), child.markerStartNumber ?: 1))
                is TableBlock -> add(table(child, depth + 1))
                is ThematicBreak -> add(ReleaseNotesBlock.Divider)
                else -> addAll(blocks(child, depth + 1))
            }
            child = child.next
        }
    }

    private fun items(parent: Node, depth: Int): List<ReleaseNotesItem> = buildList {
        var item = parent.firstChild
        while (item != null) {
            if (blockCount >= MAX_BLOCKS) {
                truncated = true
                break
            }
            if (!accept(depth)) break
            if (item is ListItem) {
                val first = (item.firstChild as? Paragraph)?.firstChild as? MarkdownText
                val marker = first?.literal?.let { TASK_MARKER.find(it) }
                val checked = marker?.groupValues?.get(1)?.let { it.equals("x", ignoreCase = true) }
                val body = blocks(item, depth + 1)
                // Only the first text node can carry the GFM task prefix. Parsing emphasis in
                // the remaining text is already complete, so remove that prefix from its span.
                val cleanBody = if (marker != null && body.firstOrNull() is ReleaseNotesBlock.Prose) {
                    val paragraph = body.first() as ReleaseNotesBlock.Prose
                    val spans = paragraph.spans.toMutableList()
                    if (spans.isNotEmpty()) spans[0] = spans[0].copy(text = spans[0].text.drop(marker.value.length))
                    listOf(paragraph.copy(spans = spans.toList())) + body.drop(1)
                } else body
                add(ReleaseNotesItem(cleanBody, checked))
            }
            item = item.next
        }
    }

    private fun table(parent: TableBlock, depth: Int): ReleaseNotesBlock.Table {
        val rows = buildList {
            var section = parent.firstChild
            while (section != null) {
                if (!accept(depth)) break
                var row = section.firstChild
                while (row != null) {
                    if (!accept(depth + 1)) break
                    val currentRow = row
                    if (currentRow is TableRow) {
                        add(buildList {
                            var cell = currentRow.firstChild
                            while (cell != null) {
                                if (size >= MAX_TABLE_COLUMNS || !accept(depth + 2)) {
                                    truncated = true
                                    break
                                }
                                if (cell is TableCell) add(ReleaseNotesCell(
                                    inlines(cell, depth + 3), cell.isHeader,
                                    when (cell.alignment) {
                                        TableCell.Alignment.CENTER -> ReleaseNotesAlignment.CENTER
                                        TableCell.Alignment.RIGHT -> ReleaseNotesAlignment.RIGHT
                                        else -> ReleaseNotesAlignment.LEFT
                                    },
                                ))
                                cell = cell.next
                            }
                        })
                    }
                    row = row.next
                }
                section = section.next
            }
        }
        return ReleaseNotesBlock.Table(rows)
    }

    private fun inlines(
        parent: Node,
        depth: Int,
        style: ReleaseNotesSpan = ReleaseNotesSpan(""),
    ): List<ReleaseNotesSpan> = buildList {
        var node = parent.firstChild
        while (node != null) {
            if (!accept(depth)) break
            when (node) {
                is MarkdownText -> add(style.copy(text = node.literal))
                is Code -> add(style.copy(text = node.literal, code = true))
                is HtmlInline -> add(style.copy(text = node.literal))
                is SoftLineBreak -> add(style.copy(text = " "))
                is HardLineBreak -> add(style.copy(text = "\n"))
                is StrongEmphasis -> addAll(inlines(node, depth + 1, style.copy(bold = true)))
                is Emphasis -> addAll(inlines(node, depth + 1, style.copy(italic = true)))
                is Strikethrough -> addAll(inlines(node, depth + 1, style.copy(strike = true)))
                is Link -> addAll(inlines(node, depth + 1, style.copy(link = releaseNotesHttpUrl(node.destination))))
                // Keep alt text only. Image destinations never enter the model or a loader.
                is Image -> addAll(inlines(node, depth + 1, style.copy(link = null)))
                else -> addAll(inlines(node, depth + 1, style))
            }
            node = node.next
        }
    }

    private companion object {
        val TASK_MARKER = Regex("^\\[([ xX])]\\s+")
    }
}

@Composable
private fun ReleaseNotesBlocks(blocks: List<ReleaseNotesBlock>, onOpenLink: (String) -> Unit) {
    blocks.forEach { block ->
        when (block) {
            is ReleaseNotesBlock.Prose -> ReleaseNotesText(
                block.spans,
                style = when (block.heading) {
                    1 -> MaterialTheme.typography.titleLarge
                    2 -> MaterialTheme.typography.titleMedium
                    in 3..6 -> MaterialTheme.typography.titleSmall
                    else -> MaterialTheme.typography.bodyMedium
                },
                onOpenLink = onOpenLink,
            )
            is ReleaseNotesBlock.CodeBlock -> Column(
                Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(12.dp))
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp),
            ) {
                Text(block.text, Modifier.widthIn(max = 1_200.dp),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
            }
            is ReleaseNotesBlock.Quote -> Column(
                Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) { ReleaseNotesBlocks(block.blocks, onOpenLink) }
            is ReleaseNotesBlock.Items -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                block.items.forEachIndexed { index, item ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (item.checked != null) {
                            Checkbox(item.checked, onCheckedChange = null)
                        } else {
                            Text(block.start?.let { "${it.toLong() + index}." } ?: "•",
                                style = MaterialTheme.typography.bodyMedium)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            ReleaseNotesBlocks(item.blocks, onOpenLink)
                        }
                    }
                }
            }
            is ReleaseNotesBlock.Table -> Column(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            ) {
                block.rows.forEach { row ->
                    Row {
                        row.forEach { cell ->
                            ReleaseNotesText(
                                cell.spans,
                                Modifier.width(168.dp)
                                    .background(if (cell.header) MaterialTheme.colorScheme.surfaceContainerHighest
                                        else MaterialTheme.colorScheme.surfaceContainerLow)
                                    .padding(10.dp),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontWeight = if (cell.header) FontWeight.SemiBold else FontWeight.Normal),
                                alignment = when (cell.alignment) {
                                    ReleaseNotesAlignment.CENTER -> TextAlign.Center
                                    ReleaseNotesAlignment.RIGHT -> TextAlign.Right
                                    else -> TextAlign.Left
                                },
                                onOpenLink = onOpenLink,
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
            ReleaseNotesBlock.Divider -> HorizontalDivider()
        }
    }
}

@Composable
private fun ReleaseNotesText(
    spans: List<ReleaseNotesSpan>,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    alignment: TextAlign = TextAlign.Start,
    onOpenLink: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val annotated = buildAnnotatedString {
        spans.forEach { span ->
            withStyle(SpanStyle(
                fontWeight = if (span.bold) FontWeight.Bold else null,
                fontStyle = if (span.italic) FontStyle.Italic else null,
                fontFamily = if (span.code) FontFamily.Monospace else null,
                background = if (span.code) colors.surfaceContainerHighest else androidx.compose.ui.graphics.Color.Unspecified,
                textDecoration = if (span.strike) TextDecoration.LineThrough else null,
            )) {
                val target = span.link
                if (target != null) {
                    withLink(LinkAnnotation.Clickable(
                        tag = target,
                        styles = TextLinkStyles(SpanStyle(color = colors.primary,
                            textDecoration = if (span.strike) TextDecoration.combine(listOf(
                                TextDecoration.Underline, TextDecoration.LineThrough)) else TextDecoration.Underline)),
                        linkInteractionListener = { releaseNotesHttpUrl(target)?.let(onOpenLink) },
                    )) { append(span.text) }
                } else append(span.text)
            }
        }
    }
    Text(annotated, modifier, style = style, textAlign = alignment)
}
