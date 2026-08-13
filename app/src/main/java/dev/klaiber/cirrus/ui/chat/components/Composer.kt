package dev.klaiber.cirrus.ui.chat.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.klaiber.cirrus.domain.model.Attachment
import dev.klaiber.cirrus.domain.model.ThinkMode
import dev.klaiber.cirrus.ui.components.Hairline
import dev.klaiber.cirrus.ui.components.OutlinedPanel
import dev.klaiber.cirrus.ui.components.Tag
import dev.klaiber.cirrus.ui.theme.LocalTagColors
import dev.klaiber.cirrus.ui.theme.Pill

/**
 * The message composer.
 *
 * The send affordance doubles as the stop control while a response streams, so the primary
 * button never moves and interrupting is always one tap away in the same place.
 */
@Composable
fun Composer(
    input: String,
    attachments: List<Attachment>,
    isGenerating: Boolean,
    canSend: Boolean,
    toolsEnabled: Boolean,
    thinkMode: ThinkMode,
    sendOnEnter: Boolean,
    voiceAvailable: Boolean,
    isListening: Boolean,
    voiceLevel: Float,
    isVoiceOnDevice: Boolean,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAttach: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onToggleTools: () -> Unit,
    onToggleVoice: () -> Unit,
    onOpenParameters: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column {
        // The transcript is separated from the controls by a rule, matching the header. Without it
        // a reply that scrolls to the last line appears to run straight into the input box.
        Hairline()
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            if (attachments.isNotEmpty()) {
                AttachmentStrip(
                    attachments = attachments,
                    modifier = Modifier.padding(bottom = 8.dp),
                    onRemove = onRemoveAttachment,
                )
            }

            if (isListening) {
                ListeningBanner(
                    level = voiceLevel,
                    isOnDevice = isVoiceOnDevice,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            // Outlined on plain surface rather than a filled slab. The reference design's inputs
            // are bordered, and on a white page a filled composer is a grey band across the bottom
            // of every screenshot. 24dp is as close to a pill as a box that grows to eight lines
            // can honestly get: at the one-line height it reads as fully rounded, and it stays
            // sane when the field is tall.
            OutlinedPanel(
                shape = RoundedCornerShape(24.dp),
                borderColor = MaterialTheme.colorScheme.outline,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 4.dp),
                    ) {
                        if (input.isEmpty()) {
                            Text(
                                text = if (isListening) "Listening…" else "Message Cirrus…",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        BasicTextField(
                            value = input,
                            onValueChange = onInputChange,
                            textStyle = LocalTextStyle.current.merge(
                                MaterialTheme.typography.bodyLarge.copy(
                                    color = MaterialTheme.colorScheme.onSurface,
                                ),
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Sentences,
                                imeAction = if (sendOnEnter) ImeAction.Send else ImeAction.Default,
                            ),
                            keyboardActions = KeyboardActions(
                                onSend = { if (canSend) onSend() },
                            ),
                            // Multi-line by default; the cap keeps the transcript visible.
                            singleLine = false,
                            maxLines = 8,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 24.dp),
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 6.dp, end = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        ComposerIcon(
                            icon = Icons.Outlined.AttachFile,
                            description = "Attach a file",
                            onClick = onAttach,
                        )
                        if (voiceAvailable) {
                            ComposerIcon(
                                icon = if (isListening) Icons.Filled.Mic else Icons.Outlined.Mic,
                                description = if (isListening) {
                                    "Stop dictation"
                                } else {
                                    "Dictate a message"
                                },
                                onClick = onToggleVoice,
                                active = isListening,
                            )
                        }
                        ComposerIcon(
                            icon = Icons.Outlined.TravelExplore,
                            // This one switch governs every tool offered this turn — web, GitHub
                            // and any attached MCP server — so it cannot claim to be about search.
                            description = if (toolsEnabled) {
                                "Disable tools for this conversation"
                            } else {
                                "Enable tools for this conversation"
                            },
                            onClick = onToggleTools,
                            active = toolsEnabled,
                        )
                        ComposerIcon(
                            icon = Icons.Outlined.Tune,
                            description = "Generation parameters",
                            onClick = onOpenParameters,
                            active = thinkMode != ThinkMode.OFF,
                        )

                        Spacer(Modifier.weight(1f))

                        if (thinkMode != ThinkMode.OFF) {
                            // The same indigo the model picker uses for the "thinking" capability.
                            // Reusing the badge here rather than inventing a second treatment means
                            // the colour keeps meaning one thing across the whole app.
                            val tags = LocalTagColors.current
                            Tag(
                                label = thinkMode.label.lowercase(),
                                background = tags.indigoBackground,
                                contentColor = tags.indigoText,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                        }

                        SendButton(
                            isGenerating = isGenerating,
                            enabled = canSend || isGenerating,
                            onClick = { if (isGenerating) onStop() else onSend() },
                        )
                    }
                }
            }
        }
        }
    }
}

/**
 * Confirms that the microphone really is open, and says where the audio is going.
 *
 * The bars follow the recogniser's own loudness readings, which is the only honest way to show
 * that speech is being picked up — a static "listening" label looks identical to a dead mic.
 */
@Composable
private fun ListeningBanner(
    level: Float,
    isOnDevice: Boolean,
    modifier: Modifier = Modifier,
) {
    OutlinedPanel(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LevelMeter(level = level)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Listening",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (isOnDevice) {
                        "Transcribed on this device"
                    } else {
                        "Transcribed by your device's speech service"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "Tap the mic to stop",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LevelMeter(level: Float) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        // Staggered thresholds so quiet speech moves the inner bars before the outer ones.
        BAR_WEIGHTS.forEach { weight ->
            val target = (MIN_BAR_HEIGHT + level * weight * MAX_BAR_GROWTH).dp
            val height by animateDpAsState(targetValue = target, label = "voice-level-bar")
            Box(
                modifier = Modifier
                    .size(width = 3.dp, height = height)
                    .clip(Pill)
                    .background(MaterialTheme.colorScheme.onSurface),
            )
        }
    }
}

private val BAR_WEIGHTS = listOf(0.45f, 0.8f, 1f, 0.8f, 0.45f)
private const val MIN_BAR_HEIGHT = 4f
private const val MAX_BAR_GROWTH = 18f

/**
 * One control in the composer's icon row.
 *
 * The glyph stays 20dp so four of them fit next to the send button; the target is restored to the
 * 48dp minimum, which `Modifier.size(40.dp)` on an `IconButton` would otherwise clip along with
 * the bounds.
 */
@Composable
private fun ComposerIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
    active: Boolean = false,
) {
    IconButton(onClick = onClick, modifier = Modifier.minimumInteractiveComponentSize()) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (active) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * Send, or stop while a response streams.
 *
 * The filled circle stays 38dp because it is a visual anchor and a bigger one crowds the row, but
 * the button inside it claims the full 48dp target.
 */
@Composable
private fun SendButton(
    isGenerating: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val containerColor = when {
        isGenerating -> MaterialTheme.colorScheme.surfaceContainerHighest
        enabled -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val contentColor = when {
        isGenerating -> MaterialTheme.colorScheme.onSurface
        enabled -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = Modifier.minimumInteractiveComponentSize(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(Pill)
                .background(containerColor),
        )
        IconButton(
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                onClick()
            },
            enabled = enabled,
            modifier = Modifier.minimumInteractiveComponentSize(),
        ) {
            Icon(
                imageVector = if (isGenerating) Icons.Outlined.Stop else Icons.Filled.ArrowUpward,
                contentDescription = if (isGenerating) "Stop generating" else "Send message",
                tint = contentColor,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}
