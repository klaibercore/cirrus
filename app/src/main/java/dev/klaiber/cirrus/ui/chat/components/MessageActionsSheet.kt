package dev.klaiber.cirrus.ui.chat.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AltRoute
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.klaiber.cirrus.domain.model.ChatMessage
import dev.klaiber.cirrus.domain.model.Role
import dev.klaiber.cirrus.ui.markdown.MonospaceBlock
import dev.klaiber.cirrus.ui.util.formatDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageActionsSheet(
    message: ChatMessage,
    onCopy: () -> Unit,
    onEdit: (String) -> Unit,
    onDelete: () -> Unit,
    onBranch: () -> Unit,
    onDismiss: () -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    var draft by remember(message.id) { mutableStateOf(message.content) }
    var showRaw by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            Text(
                text = when (message.role) {
                    Role.USER -> "Your message"
                    Role.ASSISTANT -> "Assistant response"
                    else -> "Message"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = formatDateTime(message.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))

            if (editing) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    shape = RoundedCornerShape(14.dp),
                    minLines = 3,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onEdit(draft) },
                        enabled = draft.isNotBlank() && draft != message.content,
                    ) {
                        Text("Save and resend")
                    }
                    TextButton(onClick = { editing = false }) { Text("Cancel") }
                }
            } else {
                ActionRow(Icons.Outlined.ContentCopy, "Copy text", onClick = onCopy)
                if (message.role == Role.USER) {
                    ActionRow(Icons.Outlined.Edit, "Edit and resend") { editing = true }
                }
                ActionRow(Icons.Outlined.AltRoute, "Branch from here", onClick = onBranch)
                ActionRow(
                    icon = Icons.Outlined.Delete,
                    label = "Delete message",
                    tint = MaterialTheme.colorScheme.error,
                    onClick = onDelete,
                )

                message.rawRequestJson?.let { raw ->
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { showRaw = !showRaw }) {
                        Text(if (showRaw) "Hide request JSON" else "Inspect request JSON")
                    }
                    if (showRaw) {
                        MonospaceBlock(raw, Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    label: String,
    tint: Color = Color.Unspecified,
    onClick: () -> Unit,
) {
    val resolvedTint = if (tint == Color.Unspecified) MaterialTheme.colorScheme.onSurface else tint
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = resolvedTint,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = resolvedTint,
        )
    }
}
