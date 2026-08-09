package dev.klaiber.cirrus.ui.chat.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.klaiber.cirrus.domain.model.ModelCapability
import dev.klaiber.cirrus.domain.model.ModelFilter
import dev.klaiber.cirrus.domain.model.ModelInfo
import dev.klaiber.cirrus.ui.components.HelpBadge

/**
 * Model chooser.
 *
 * One card per model, carrying the facts that actually decide the pick — what it can do, how
 * big it is, how much context it has — because a bare list of tags makes every model look alike
 * until you have memorised the catalogue.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(
    models: List<ModelInfo>,
    selectedModel: String,
    isRefreshing: Boolean,
    isLoadingDetails: Boolean,
    onSelect: (String) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(ModelFilter.ALL) }

    val filtered = remember(models, query, filter) {
        ModelFilter.apply(models, filter)
            .filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
    }
    val availableFilters = remember(models) {
        ModelFilter.available(models)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(text = "Model", style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = when {
                            isLoadingDetails -> "Reading capabilities…"
                            models.isEmpty() -> "Nothing loaded yet"
                            else -> "${models.size} available on this host"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isRefreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Refresh model list")
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Filter models") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
            )

            if (availableFilters.size > 1) {
                Spacer(Modifier.height(12.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp),
                ) {
                    items(availableFilters, key = { it.name }) { candidate ->
                        FilterChip(
                            selected = filter == candidate,
                            onClick = { filter = candidate },
                            label = { Text(candidate.label) },
                            leadingIcon = if (filter == candidate) {
                                {
                                    Icon(
                                        imageVector = Icons.Outlined.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                                    )
                                }
                            } else {
                                null
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            if (filtered.isEmpty()) {
                EmptyModelState(
                    isRefreshing = isRefreshing,
                    isFiltered = query.isNotBlank() || filter != ModelFilter.ALL,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 520.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(filtered, key = { it.name }) { model ->
                        ModelCard(
                            model = model,
                            selected = model.name == selectedModel,
                            onClick = {
                                onSelect(model.name)
                                onDismiss()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelCard(model: ModelInfo, selected: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
        border = if (selected) {
            BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = model.baseName,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (selected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                        model.tag?.let { tag ->
                            Spacer(Modifier.size(6.dp))
                            Text(
                                text = tag,
                                style = MaterialTheme.typography.labelSmall
                                    .copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    metadataLine(model)?.let { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }

                HelpBadge(title = model.displayName, text = capabilitySummary(model))

                if (selected) {
                    Icon(
                        imageVector = Icons.Outlined.Check,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .size(20.dp),
                    )
                }
            }

            val badges = model.badges
            if (badges.isNotEmpty() || model.isCloudHosted) {
                Spacer(Modifier.height(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (model.isCloudHosted) {
                        CapabilityChip(
                            icon = Icons.Outlined.Cloud,
                            label = "Cloud",
                            selected = selected,
                        )
                    }
                    badges.forEach { capability ->
                        CapabilityChip(
                            icon = capability.icon,
                            label = capability.label,
                            selected = selected,
                        )
                    }
                }
            }
        }
    }
}

/** Parameter count, quantization, on-disk size and context window, whichever the host reported. */
private fun metadataLine(model: ModelInfo): String? = listOfNotNull(
    model.parameterSize,
    model.quantization,
    model.displaySize,
    model.displayContextLength,
).joinToString(" · ").takeIf { it.isNotEmpty() }

/**
 * The tooltip body for a card: what each capability means, plus an honest note when the list is
 * a guess from the model's name because the host never answered `/api/show`.
 */
private fun capabilitySummary(model: ModelInfo): String {
    val badges = model.badges
    val lines = if (badges.isEmpty()) {
        listOf("Text in, text out. No extra capabilities reported.")
    } else {
        badges.map { "${it.label} — ${it.help}" }
    }
    val provenance = if (model.hasVerifiedCapabilities) {
        "Reported by the host."
    } else {
        "Inferred from the model name; this host did not answer /api/show."
    }
    return (lines + provenance).joinToString("\n\n")
}

@Composable
private fun CapabilityChip(icon: ImageVector, label: String, selected: Boolean) {
    val container: Color
    val content: Color
    if (selected) {
        container = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        content = MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        container = MaterialTheme.colorScheme.surfaceContainerHighest
        content = MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(color = container, shape = RoundedCornerShape(8.dp)) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(13.dp),
            )
            Text(text = label, style = MaterialTheme.typography.labelSmall, color = content)
        }
    }
}

private val ModelCapability.icon: ImageVector
    get() = when (this) {
        ModelCapability.COMPLETION -> Icons.Outlined.ChatBubbleOutline
        ModelCapability.THINKING -> Icons.Outlined.Psychology
        ModelCapability.VISION -> Icons.Outlined.Visibility
        ModelCapability.TOOLS -> Icons.Outlined.Build
        ModelCapability.AUDIO -> Icons.Outlined.GraphicEq
        ModelCapability.IMAGE -> Icons.Outlined.Image
        ModelCapability.EMBEDDING -> Icons.Outlined.Hub
        ModelCapability.INSERT -> Icons.Outlined.Code
    }

@Composable
private fun EmptyModelState(isRefreshing: Boolean, isFiltered: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!isRefreshing && !isFiltered) {
                Icon(
                    imageVector = Icons.Outlined.Storage,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .size(18.dp),
                )
            }
            Text(
                text = when {
                    isRefreshing -> "Loading models…"
                    isFiltered -> "No models match that filter."
                    else -> "No models found. Check your connection and refresh."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
