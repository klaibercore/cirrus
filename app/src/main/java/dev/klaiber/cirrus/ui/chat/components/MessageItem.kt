package dev.klaiber.cirrus.ui.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AltRoute
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.klaiber.cirrus.domain.model.Attachment
import dev.klaiber.cirrus.domain.model.ChatMessage
import dev.klaiber.cirrus.domain.model.GenerationStats
import dev.klaiber.cirrus.domain.model.Role
import dev.klaiber.cirrus.ui.components.CirrusActivity
import dev.klaiber.cirrus.ui.components.MarkActivity
import dev.klaiber.cirrus.ui.components.OutlinedPanel
import dev.klaiber.cirrus.ui.markdown.MarkdownText
import dev.klaiber.cirrus.ui.markdown.highlighting
import dev.klaiber.cirrus.ui.markdown.MonospaceBlock
import dev.klaiber.cirrus.ui.theme.ContainerShape
import dev.klaiber.cirrus.ui.theme.LargeContainerShape
import dev.klaiber.cirrus.ui.theme.LocalTagColors
import dev.klaiber.cirrus.ui.util.formatBytes
import dev.klaiber.cirrus.ui.util.formatNanos
import dev.klaiber.cirrus.ui.util.rememberClipboard

/**
 * One turn in the transcript.
 *
 * User turns are bubbles so the eye can find them when scrolling back; assistant turns run the
 * full width like a document, which is what long answers with code and tables need.
 */
@Composable
fun MessageItem(
    message: ChatMessage,
    showStats: Boolean,
    renderMarkdown: Boolean,
    developerMode: Boolean,
    speech: SpeechButtonState,
    highlight: String,
    onCopy: (String) -> Unit,
    onRegenerate: () -> Unit,
    onBranch: () -> Unit,
    onSpeak: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (message.role) {
        Role.USER -> UserMessage(message, highlight, onMore, modifier)
        Role.ASSISTANT -> AssistantMessage(
            message = message,
            showStats = showStats,
            renderMarkdown = renderMarkdown,
            developerMode = developerMode,
            speech = speech,
            highlight = highlight,
            onCopy = onCopy,
            onRegenerate = onRegenerate,
            onBranch = onBranch,
            onSpeak = onSpeak,
            onMore = onMore,
            modifier = modifier,
        )
        // Tool results are folded into the assistant turn that requested them.
        Role.TOOL, Role.SYSTEM -> Unit
    }
}

/**
 * Your own turn.
 *
 * Long-press opens the same actions sheet the assistant turns use — copy, edit and resend, branch,
 * delete. A bubble with no visible affordance is the platform convention for this; putting an icon
 * row under every message you sent would double the chrome in the transcript.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserMessage(
    message: ChatMessage,
    highlight: String,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    // A uniform radius rather than the usual clipped "tail" corner. The reference design resolves
    // every corner to the same answer, and the bubble does not need a tail to be legible: it is the
    // only right-aligned, filled thing in a column of full-width unboxed prose.
    val bubbleShape = LargeContainerShape

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
    ) {
        if (message.attachments.isNotEmpty()) {
            AttachmentStrip(message.attachments, Modifier.padding(bottom = 6.dp))
        }
        if (message.content.isNotBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = bubbleShape,
                // A fraction rather than a fixed cap: 320dp is most of a compact phone's width
                // anyway, and a postage stamp on a tablet.
                modifier = Modifier
                    .fillMaxWidth(USER_BUBBLE_WIDTH_FRACTION)
                    .wrapContentWidth(Alignment.End)
                    .clip(bubbleShape)
                    .combinedClickable(
                        onClick = {},
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onMore()
                        },
                        onLongClickLabel = "Message actions",
                    ),
            ) {
                Text(
                    text = AnnotatedString(message.content).highlighting(highlight, highlightColor()),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun AssistantMessage(
    message: ChatMessage,
    showStats: Boolean,
    renderMarkdown: Boolean,
    developerMode: Boolean,
    speech: SpeechButtonState,
    highlight: String,
    onCopy: (String) -> Unit,
    onRegenerate: () -> Unit,
    onBranch: () -> Unit,
    onSpeak: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The body is selectable, so long-press belongs to the text: it starts a selection rather than
    // opening the actions sheet. Nothing is lost — every action is one tap away in the row below,
    // and picking a sentence out of an answer is worth more than a second route to a menu.
    SelectionContainer(modifier) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Announce the finished answer, not every token: TalkBack reading a response as it
            // streams is unusable, and silence when one arrives is worse. There is no "off" mode,
            // so the region is only declared once the turn has landed — which is also the moment
            // the modifier changes and the announcement fires.
            .then(
                if (message.isStreaming) {
                    Modifier
                } else {
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                },
            ),
    ) {
        message.thinking?.takeIf { it.isNotBlank() }?.let { thinking ->
            ThinkingSection(
                thinking = thinking,
                isStreaming = message.isStreaming && message.content.isEmpty(),
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }

        // One panel for the lot, rather than one card per call: see ToolActivity for why the
        // provenance of an answer must not out-weigh the answer.
        ToolActivity(message.toolInvocations, Modifier.padding(bottom = 8.dp))

        if (message.content.isNotBlank()) {
            if (renderMarkdown) {
                MarkdownText(markdown = message.content, highlight = highlight)
            } else {
                Text(
                    text = AnnotatedString(message.content).highlighting(highlight, highlightColor()),
                    style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                )
            }
        }

        if (message.isStreaming) {
            StreamingIndicator(hasContent = message.content.isNotBlank())
        }

        message.errorMessage?.let { error ->
            ErrorCard(error, onRetry = onRegenerate, modifier = Modifier.padding(top = 10.dp))
        }

        if (!message.isStreaming && message.content.isNotBlank()) {
            MessageActions(
                speech = speech,
                onCopy = { onCopy(message.content) },
                onRegenerate = onRegenerate,
                onBranch = onBranch,
                onSpeak = onSpeak,
                onMore = onMore,
            )
        }

        if (showStats && !message.isStreaming) {
            message.stats?.let { StatsRow(it, message.model) }
        }

        if (developerMode && message.rawRequestJson != null) {
            RawRequestSection(message.rawRequestJson)
        }
    }
    }
}

/**
 * Collapsible reasoning trace.
 *
 * Expanded while it is the only thing streaming so there is something to watch, then collapsed
 * once the answer starts — the trace is for auditing, not for reading every time.
 */
@Composable
private fun ThinkingSection(
    thinking: String,
    isStreaming: Boolean,
    modifier: Modifier = Modifier,
) {
    var expanded by remember(isStreaming) { mutableStateOf(isStreaming) }

    // Outlined rather than filled. A tinted panel inside a reply competes with the code blocks
    // below it for the reader's "this part is different" signal; a hairline box says the same thing
    // and costs nothing, which is the trade the reference design makes everywhere.
    OutlinedPanel(modifier = modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Psychology,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (isStreaming) "Thinking…" else "Reasoning",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "Collapse reasoning" else "Expand reasoning",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            AnimatedVisibility(visible = expanded) {
                Text(
                    text = thinking.trim(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                )
            }
        }
    }
}

/** What the read-aloud button should show for this message. */
enum class SpeechButtonState { HIDDEN, IDLE, PREPARING, SPEAKING }

@Composable
private fun MessageActions(
    speech: SpeechButtonState,
    onCopy: () -> Unit,
    onRegenerate: () -> Unit,
    onBranch: () -> Unit,
    onSpeak: () -> Unit,
    onMore: () -> Unit,
) {
    // Icons in a selectable region would otherwise be swept up by a selection drag.
    DisableSelection {
        Row(
            modifier = Modifier.padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            ActionIcon(Icons.Outlined.ContentCopy, "Copy message", onCopy)
            if (speech != SpeechButtonState.HIDDEN) {
                ActionIcon(
                    icon = when (speech) {
                        SpeechButtonState.SPEAKING -> Icons.Outlined.StopCircle
                        SpeechButtonState.PREPARING -> Icons.Outlined.HourglassEmpty
                        else -> Icons.Outlined.RecordVoiceOver
                    },
                    description = when (speech) {
                        SpeechButtonState.SPEAKING -> "Stop reading aloud"
                        SpeechButtonState.PREPARING -> "Preparing audio, tap to cancel"
                        else -> "Read aloud"
                    },
                    tint = if (speech == SpeechButtonState.IDLE) null else MaterialTheme.colorScheme.primary,
                    onClick = onSpeak,
                )
            }
            ActionIcon(Icons.Outlined.Refresh, "Regenerate response", onRegenerate)
            ActionIcon(Icons.Outlined.AltRoute, "Branch from here", onBranch)
            ActionIcon(Icons.Outlined.MoreHoriz, "More actions", onMore)
        }
    }
}

/**
 * The icon stays small; the target does not.
 *
 * `Modifier.size(36.dp)` on an `IconButton` shrinks its hit rectangle along with its bounds, which
 * is how a row of 17dp glyphs ends up below the 48dp minimum. [minimumInteractiveComponentSize]
 * restores the target without making the row look heavier.
 */
@Composable
private fun ActionIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
    tint: androidx.compose.ui.graphics.Color? = null,
) {
    IconButton(onClick = onClick, modifier = Modifier.minimumInteractiveComponentSize()) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = tint ?: MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(17.dp),
        )
    }
}

@Composable
private fun StatsRow(stats: GenerationStats, model: String?) {
    val parts = remember(stats, model) {
        buildList {
            model?.let { add(it) }
            stats.tokensPerSecond?.let { add("%.1f tok/s".format(it)) }
            stats.evalCount?.let { add("$it out") }
            stats.promptEvalCount?.let { add("$it in") }
            stats.timeToFirstTokenMs?.let { add("${it} ms first token") }
            stats.totalDurationNs?.let { add(formatNanos(it)) }
        }
    }
    if (parts.isEmpty()) return

    Text(
        text = parts.joinToString(" · "),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp, start = 4.dp),
    )
}

@Composable
private fun RawRequestSection(requestJson: String) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.padding(top = 6.dp)) {
        Text(
            text = if (expanded) "Hide request JSON" else "Show request JSON",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(MaterialTheme.shapes.extraSmall)
                .clickable { expanded = !expanded }
                .padding(horizontal = 4.dp, vertical = 4.dp),
        )
        AnimatedVisibility(visible = expanded) {
            MonospaceBlock(requestJson, Modifier.fillMaxWidth().padding(top = 4.dp))
        }
    }
}

@Composable
private fun ErrorCard(error: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = ContainerShape,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onRetry, modifier = Modifier.minimumInteractiveComponentSize()) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = "Retry",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * The gap between sending a prompt and the first token coming back.
 *
 * 20dp gets the stream glyph rather than the braced mark, which is the right way round: what has
 * to be legible here is three sweeps moving, and braces at this size would spend most of the box
 * on a frame around something too small to read.
 */
@Composable
private fun StreamingIndicator(hasContent: Boolean) {
    if (hasContent) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        CirrusActivity(activity = MarkActivity.Thinking, size = 20.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            text = "Working…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun AttachmentStrip(
    attachments: List<Attachment>,
    modifier: Modifier = Modifier,
    onRemove: ((String) -> Unit)? = null,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(attachments.size) { index ->
            AttachmentChip(attachments[index], onRemove)
        }
    }
}

@Composable
private fun AttachmentChip(attachment: Attachment, onRemove: ((String) -> Unit)?) {
    OutlinedPanel(shape = ContainerShape) {
        Row(
            modifier = Modifier.padding(
                start = if (attachment.kind == Attachment.Kind.IMAGE) 0.dp else 10.dp,
                end = if (onRemove != null) 2.dp else 10.dp,
                top = if (attachment.kind == Attachment.Kind.IMAGE) 0.dp else 8.dp,
                bottom = if (attachment.kind == Attachment.Kind.IMAGE) 0.dp else 8.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (attachment.kind == Attachment.Kind.IMAGE) {
                AsyncImage(
                    model = "file://${attachment.localPath}",
                    contentDescription = attachment.displayName,
                    modifier = Modifier
                        .size(72.dp)
                        .clip(ContainerShape),
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        text = attachment.displayName,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        modifier = Modifier.widthIn(max = 160.dp),
                    )
                    Text(
                        text = formatBytes(attachment.sizeBytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (onRemove != null) {
                IconButton(
                    onClick = { onRemove(attachment.id) },
                    modifier = Modifier.minimumInteractiveComponentSize(),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "Remove ${attachment.displayName}",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

/** The same tint the markdown renderer uses, so a match looks the same wherever it is found. */
@Composable
private fun highlightColor() = LocalTagColors.current.searchHighlight

/** How much of the row a user bubble may take before it wraps. */
private const val USER_BUBBLE_WIDTH_FRACTION = 0.85f
