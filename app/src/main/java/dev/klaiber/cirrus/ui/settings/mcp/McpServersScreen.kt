package dev.klaiber.cirrus.ui.settings.mcp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
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
import dev.klaiber.cirrus.data.mcp.McpCatalogEntry
import dev.klaiber.cirrus.data.mcp.McpServerConfig
import dev.klaiber.cirrus.data.repository.McpServerState
import dev.klaiber.cirrus.ui.components.HelpBadge
import dev.klaiber.cirrus.ui.components.OutlinedPanel
import dev.klaiber.cirrus.ui.theme.ContainerShape
import dev.klaiber.cirrus.ui.theme.LargeContainerShape

/**
 * The MCP servers the user has attached.
 *
 * A server is only useful if it can be reached, so every row leads with its live state rather
 * than its configuration — "7 tools" and "could not be reached" are the two facts that decide
 * what to do next, and neither is knowable from the URL.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpServersScreen(
    onBack: () -> Unit,
    viewModel: McpViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val editor by viewModel.editor.collectAsStateWithLifecycle()
    var deleteTarget by remember { mutableStateOf<McpServerConfig?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MCP servers") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = viewModel::startAdding,
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                text = { Text("Add server") },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "intro") {
                Text(
                    text = "An MCP server lends its tools to the model for the length of a turn. " +
                        "Cirrus reads each server's tool list when you attach it, and offers only " +
                        "what the server actually reported.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }

            if (state.servers.isEmpty()) {
                item(key = "empty") { EmptyServersCard() }
            } else {
                items(state.servers, key = { it.id }) { server ->
                    ServerCard(
                        server = server,
                        state = state.states[server.id] ?: McpServerState.Idle,
                        onToggle = { viewModel.setEnabled(server.id, it) },
                        onEdit = { viewModel.startEditing(server) },
                        onRefresh = { viewModel.refresh(server.id) },
                        onDelete = { deleteTarget = server },
                    )
                }
            }

            val catalog = state.unattachedCatalog
            if (catalog.isNotEmpty()) {
                item(key = "catalog-header") {
                    Text(
                        text = "Known servers",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 18.dp, bottom = 2.dp),
                    )
                }
                item(key = "catalog-note") {
                    Text(
                        text = "Starting points, not endorsements. Each one still has to be " +
                            "reached before Cirrus will save it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                items(catalog, key = { it.url }) { entry ->
                    CatalogRow(entry = entry, onAdd = { viewModel.startAdding(entry) })
                }
            }

            // Clears the FAB so the last row is reachable.
            item(key = "fab-spacer") { Spacer(Modifier.height(72.dp)) }
        }
    }

    editor?.let { editorState ->
        McpServerEditorSheet(
            state = editorState,
            onLabelChange = viewModel::onLabelChange,
            onUrlChange = viewModel::onUrlChange,
            onTokenChange = viewModel::onTokenChange,
            onProbe = viewModel::probe,
            onSave = viewModel::save,
            onDismiss = viewModel::dismissEditor,
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Remove ${target.label}?") },
            text = {
                Text("Its tools stop being offered to the model. The stored token is deleted.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.remove(target.id)
                        deleteTarget = null
                    },
                ) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ServerCard(
    server: McpServerConfig,
    state: McpServerState,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
) {
    OutlinedPanel(shape = LargeContainerShape, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = server.label,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = server.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Switch(
                    checked = server.enabled,
                    onCheckedChange = onToggle,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            Spacer(Modifier.height(8.dp))
            ServerStatusRow(server = server, state = state)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onRefresh,
                    modifier = Modifier.minimumInteractiveComponentSize(),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = "Re-read ${server.label}'s tools",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(19.dp),
                    )
                }
                IconButton(
                    onClick = onEdit,
                    modifier = Modifier.minimumInteractiveComponentSize(),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Edit,
                        contentDescription = "Edit ${server.label}",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(19.dp),
                    )
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.minimumInteractiveComponentSize(),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = "Remove ${server.label}",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
        }
    }
}

/** The one line that says whether this server is doing anything for you. */
@Composable
private fun ServerStatusRow(server: McpServerConfig, state: McpServerState) {
    if (!server.enabled) {
        StatusLine(
            text = "Switched off — its tools are not offered.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    when (state) {
        McpServerState.Idle -> StatusLine(
            text = "Not contacted yet.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        McpServerState.Connecting -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Reading tools…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        is McpServerState.Ready -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = when (state.tools.size) {
                    0 -> "Reached, but it offers no tools."
                    1 -> "1 tool offered to the model."
                    else -> "${state.tools.size} tools offered to the model."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (state.tools.isNotEmpty()) {
                HelpBadge(
                    title = "${server.label} tools",
                    text = state.tools.joinToString("\n\n") { tool ->
                        val summary = tool.description.take(160).ifBlank { "No description given." }
                        "${tool.name} — $summary"
                    },
                )
            }
        }

        is McpServerState.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = state.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun StatusLine(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = color)
}

@Composable
private fun CatalogRow(entry: McpCatalogEntry, onAdd: () -> Unit) {
    OutlinedPanel(
        onClick = onAdd,
        shape = ContainerShape,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Hub,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(text = entry.label, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = entry.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.Outlined.Add,
                contentDescription = "Add ${entry.label}",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun EmptyServersCard() {
    OutlinedPanel(shape = LargeContainerShape, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.padding(20.dp)) {
            Text(
                text = "No servers attached. Add one below, or paste a URL with the button.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
