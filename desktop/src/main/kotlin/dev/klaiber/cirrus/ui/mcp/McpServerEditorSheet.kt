package dev.klaiber.cirrus.ui.mcp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.klaiber.cirrus.data.mcp.McpToolDescriptor
import dev.klaiber.cirrus.data.mcp.McpTransportKind
import dev.klaiber.cirrus.data.repository.McpProbeResult
import dev.klaiber.cirrus.ui.theme.ContainerShape

/**
 * Add or edit one server.
 *
 * The shape of this sheet is the argument: you cannot save a server you have not reached. A URL
 * that parses proves nothing, and a server that is misconfigured fails silently later — mid-turn,
 * as a tool call the model cannot explain. Probing first turns that into a visible answer here,
 * and has the side benefit of showing exactly what the model is about to be handed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpServerEditorSheet(
    state: McpEditorState,
    onLabelChange: (String) -> Unit,
    onUrlChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onProbe: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    var tokenVisible by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = if (state.isNew) "Add an MCP server" else "Edit server",
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Cirrus connects, asks what tools the server offers, and shows you before " +
                    "anything is saved.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(18.dp))

            OutlinedTextField(
                value = state.url,
                onValueChange = onUrlChange,
                label = { Text("Server URL") },
                placeholder = { Text("https://mcp.example.com/mcp") },
                singleLine = true,
                shape = ContainerShape,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = state.token,
                onValueChange = onTokenChange,
                label = { Text("Token") },
                placeholder = { Text("Optional") },
                supportingText = {
                    Text("Sent as a bearer token to this server only, and stored encrypted.")
                },
                singleLine = true,
                shape = ContainerShape,
                visualTransformation = if (tokenVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(
                        onClick = { tokenVisible = !tokenVisible },
                        modifier = Modifier.minimumInteractiveComponentSize(),
                    ) {
                        Icon(
                            imageVector = if (tokenVisible) {
                                Icons.Outlined.VisibilityOff
                            } else {
                                Icons.Outlined.Visibility
                            },
                            contentDescription = if (tokenVisible) "Hide token" else "Show token",
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(14.dp))

            OutlinedButton(
                onClick = onProbe,
                enabled = state.canProbe,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.isProbing) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Connecting…")
                } else {
                    Text("Test connection")
                }
            }

            state.probe?.let { probe ->
                Spacer(Modifier.height(14.dp))
                if (state.isProbeStale) {
                    StaleProbeNotice()
                } else {
                    when (probe) {
                        is McpProbeResult.Success -> ProbeSuccess(probe)
                        is McpProbeResult.Failure -> ProbeFailure(probe.message)
                    }
                }
            }

            // The name is only worth asking for once there is something to name.
            if (state.probe is McpProbeResult.Success) {
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = state.label,
                    onValueChange = onLabelChange,
                    label = { Text("Name") },
                    supportingText = {
                        Text("Shown to the model so it can tell this server's tools from another's.")
                    },
                    singleLine = true,
                    shape = ContainerShape,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onSave, enabled = state.canSave) {
                    Text(if (state.isNew) "Add server" else "Save")
                }
            }
        }
    }
}

@Composable
private fun ProbeSuccess(probe: McpProbeResult.Success) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = ContainerShape,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = when (probe.tools.size) {
                        0 -> "Connected, but this server offers no tools"
                        1 -> "Connected — 1 tool"
                        else -> "Connected — ${probe.tools.size} tools"
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Text(
                text = when (probe.transport) {
                    McpTransportKind.STREAMABLE_HTTP -> "Streamable HTTP"
                    McpTransportKind.SSE -> "SSE (older transport)"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 26.dp),
            )

            if (probe.tools.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Column(
                    modifier = Modifier
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    probe.tools.forEach { ToolPreviewRow(it) }
                }
            }
        }
    }
}

/**
 * One tool the server said it has.
 *
 * The description is shown because it is what the *model* reads when deciding to call the tool,
 * so it is the only reliable place a write-capable tool announces itself. Cirrus cannot tell
 * reads from writes on a remote server the way it can for its own GitHub tools — MCP has no such
 * flag — so the honest thing is to show the text and let the user judge.
 */
@Composable
private fun ToolPreviewRow(tool: McpToolDescriptor) {
    Column {
        Text(
            text = tool.name,
            style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
        )
        if (tool.description.isNotBlank()) {
            Text(
                text = tool.description.take(MAX_DESCRIPTION_CHARS),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProbeFailure(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = ContainerShape,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun StaleProbeNotice() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = ContainerShape,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "You have changed the URL or token since testing. Test again to save.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private const val MAX_DESCRIPTION_CHARS = 200
