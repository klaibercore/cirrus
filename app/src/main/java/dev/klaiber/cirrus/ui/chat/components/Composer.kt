package dev.klaiber.cirrus.ui.chat.components

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
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.klaiber.cirrus.domain.model.Attachment
import dev.klaiber.cirrus.domain.model.ThinkMode

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
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAttach: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onToggleTools: () -> Unit,
    onOpenParameters: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (attachments.isNotEmpty()) {
                AttachmentStrip(
                    attachments = attachments,
                    modifier = Modifier.padding(bottom = 8.dp),
                    onRemove = onRemoveAttachment,
                )
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(24.dp),
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
                                text = "Message Cirrus…",
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
                        ComposerIcon(
                            icon = Icons.Outlined.TravelExplore,
                            description = if (toolsEnabled) "Disable web tools" else "Enable web tools",
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
                            Text(
                                text = thinkMode.label.lowercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(end = 6.dp),
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

@Composable
private fun ComposerIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
    active: Boolean = false,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
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

@Composable
private fun SendButton(
    isGenerating: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
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
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(50))
            .background(containerColor),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(38.dp),
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
