package dev.klaiber.cirrus.ui.conversations

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.klaiber.cirrus.domain.model.ConversationSummary
import dev.klaiber.cirrus.ui.theme.ContainerShape
import dev.klaiber.cirrus.ui.theme.Pill
import dev.klaiber.cirrus.ui.util.bucketFor

/**
 * Conversation list.
 *
 * Grouped by recency rather than shown as one flat list, because the useful question when
 * reopening the app is "what was I doing recently", not "what exists".
 */
@Composable
fun ConversationDrawer(
    activeConversationId: String?,
    onSelectConversation: (String) -> Unit,
    onNewChat: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAgents: () -> Unit,
    onOpenMemory: () -> Unit,
    viewModel: ConversationsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var renameTarget by remember { mutableStateOf<ConversationSummary?>(null) }
    var deleteTarget by remember { mutableStateOf<ConversationSummary?>(null) }

    ModalDrawerSheet(
        drawerShape = RoundedCornerShape(0.dp, 16.dp, 16.dp, 0.dp),
        drawerContainerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The wordmark, set the way the reference site sets its own: mark, then name in
                // the rounded display face, at the top-left of everything.
                Icon(
                    imageVector = Icons.Outlined.Cloud,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "Cirrus",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = viewModel::toggleArchivedView) {
                    Icon(
                        imageVector = Icons.Outlined.Inventory2,
                        contentDescription = if (state.showArchived) {
                            "Show active conversations"
                        } else {
                            "Show archived conversations"
                        },
                        tint = if (state.showArchived) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                IconButton(onClick = onNewChat) {
                    Icon(Icons.Outlined.Add, contentDescription = "New chat")
                }
            }

            // A pill, like every search field on the reference site. The border is the light
            // `outline` step rather than Material's accent-on-focus, so the field does not change
            // colour the moment the keyboard opens.
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                placeholder = { Text("Search conversations") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Search,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
                singleLine = true,
                shape = Pill,
                textStyle = MaterialTheme.typography.bodyMedium,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            )

            Spacer(Modifier.height(4.dp))

            if (state.conversations.isEmpty()) {
                EmptyDrawerState(
                    query = state.query,
                    showArchived = state.showArchived,
                    modifier = Modifier.weight(1f),
                )
            } else {
                ConversationList(
                    conversations = state.conversations,
                    activeConversationId = activeConversationId,
                    onSelect = onSelectConversation,
                    onRename = { renameTarget = it },
                    onTogglePin = { viewModel.setPinned(it.conversation.id, !it.conversation.pinned) },
                    onToggleArchive = {
                        viewModel.setArchived(it.conversation.id, !it.conversation.archived)
                    },
                    onDelete = { deleteTarget = it },
                    modifier = Modifier.weight(1f),
                )
            }

            HorizontalDivider()
            // Agents and memory are the two things Cirrus does while you are not looking, and both
            // used to be two taps deep inside Settings — which reads as configuration rather than
            // as somewhere with content in it. An agent's answers live behind this row.
            DrawerAction(
                icon = Icons.Outlined.Schedule,
                label = if (state.agentCount > 0) "Agents · ${state.agentCount}" else "Agents",
                onClick = onOpenAgents,
            )
            DrawerAction(
                icon = Icons.Outlined.Psychology,
                label = "Memory",
                onClick = onOpenMemory,
            )
            DrawerAction(
                icon = Icons.Outlined.Settings,
                label = "Settings",
                onClick = onOpenSettings,
            )
            Spacer(Modifier.height(8.dp))
        }
    }

    renameTarget?.let { target ->
        RenameDialog(
            initialTitle = target.conversation.title,
            onConfirm = { title ->
                viewModel.rename(target.conversation.id, title)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete conversation?") },
            text = {
                Text("\"${target.conversation.title}\" and its ${target.messageCount} messages will be permanently removed.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(target.conversation.id)
                        deleteTarget = null
                    },
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            },
        )
    }
}

/** A destination at the foot of the drawer: one glyph, one word, the whole row tappable. */
@Composable
private fun DrawerAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(14.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ConversationList(
    conversations: List<ConversationSummary>,
    activeConversationId: String?,
    onSelect: (String) -> Unit,
    onRename: (ConversationSummary) -> Unit,
    onTogglePin: (ConversationSummary) -> Unit,
    onToggleArchive: (ConversationSummary) -> Unit,
    onDelete: (ConversationSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Pinned items stay at the top; the rest fall into recency buckets.
    val pinned = conversations.filter { it.conversation.pinned }
    val grouped = remember(conversations) {
        conversations
            .filterNot { it.conversation.pinned }
            .groupBy { bucketFor(it.conversation.updatedAt) }
            .toSortedMap(compareBy { it.ordinal })
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
    ) {
        if (pinned.isNotEmpty()) {
            item(key = "header-pinned") { SectionHeader("Pinned") }
            items(pinned, key = { it.conversation.id }) { summary ->
                ConversationRow(
                    summary = summary,
                    selected = summary.conversation.id == activeConversationId,
                    onClick = { onSelect(summary.conversation.id) },
                    onRename = { onRename(summary) },
                    onTogglePin = { onTogglePin(summary) },
                    onToggleArchive = { onToggleArchive(summary) },
                    onDelete = { onDelete(summary) },
                )
            }
        }

        grouped.forEach { (bucket, items) ->
            item(key = "header-${bucket.name}") { SectionHeader(bucket.label) }
            items(items, key = { it.conversation.id }) { summary ->
                ConversationRow(
                    summary = summary,
                    selected = summary.conversation.id == activeConversationId,
                    onClick = { onSelect(summary.conversation.id) },
                    onRename = { onRename(summary) },
                    onTogglePin = { onTogglePin(summary) },
                    onToggleArchive = { onToggleArchive(summary) },
                    onDelete = { onDelete(summary) },
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 12.dp, top = 18.dp, bottom = 6.dp),
    )
}

@Composable
private fun ConversationRow(
    summary: ConversationSummary,
    selected: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    // Selection is a half-step change of background and nothing else — no accent, no border, no
    // leading bar. On a monochrome ramp that step is quiet but unambiguous, which is the right
    // weight for "this is the thread you are already looking at".
    Surface(
        color = if (selected) {
            MaterialTheme.colorScheme.surfaceContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        shape = ContainerShape,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
    ) {
        Row(
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(start = 12.dp, end = 2.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (summary.conversation.pinned) {
                        Icon(
                            imageVector = Icons.Outlined.PushPin,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(13.dp),
                        )
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(
                        text = summary.conversation.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                summary.lastMessagePreview?.let { preview ->
                    Text(
                        text = preview.replace('\n', ' ').trim(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Box {
                IconButton(onClick = { menuExpanded = true }, modifier = Modifier.minimumInteractiveComponentSize()) {
                    Icon(
                        imageVector = Icons.Outlined.MoreVert,
                        contentDescription = "Options for ${summary.conversation.title}",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(17.dp),
                    )
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(if (summary.conversation.pinned) "Unpin" else "Pin") },
                        leadingIcon = { Icon(Icons.Outlined.PushPin, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onTogglePin()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(if (summary.conversation.archived) "Unarchive" else "Archive") },
                        leadingIcon = {
                            Icon(
                                imageVector = if (summary.conversation.archived) {
                                    Icons.Outlined.Unarchive
                                } else {
                                    Icons.Outlined.Archive
                                },
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onToggleArchive()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun RenameDialog(
    initialTitle: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf(initialTitle) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename conversation") },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                singleLine = true,
                shape = ContainerShape,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(title) }, enabled = title.isNotBlank()) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun EmptyDrawerState(query: String, showArchived: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = when {
                query.isNotBlank() -> "No conversations match \"$query\"."
                showArchived -> "Nothing archived yet."
                else -> "No conversations yet."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(24.dp),
        )
    }
}
