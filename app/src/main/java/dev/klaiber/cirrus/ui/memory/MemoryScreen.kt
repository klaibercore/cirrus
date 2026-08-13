package dev.klaiber.cirrus.ui.memory

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import dev.klaiber.cirrus.domain.model.Memory
import dev.klaiber.cirrus.domain.model.MemoryKind
import dev.klaiber.cirrus.ui.components.EmptyState
import dev.klaiber.cirrus.ui.components.SectionLabel
import dev.klaiber.cirrus.ui.util.formatRelative

/**
 * Everything Cirrus remembers, in one readable list.
 *
 * The screen exists because memory that cannot be inspected cannot be trusted. Every line here was
 * written by a model, and being able to read it, correct it, pin it or throw it away is what makes
 * the feature something you would leave switched on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(
    onBack: () -> Unit,
    viewModel: MemoryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Memory?>(null) }
    var creating by remember { mutableStateOf(false) }
    var showRetired by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Memory") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { creating = true }) {
                Icon(Icons.Outlined.Add, contentDescription = "Add a memory")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                MemorySummary(
                    total = state.total,
                    lastConsolidationAt = state.lastConsolidationAt,
                    isConsolidating = state.isConsolidating,
                    onConsolidate = viewModel::consolidateNow,
                )
            }

            item {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    placeholder = { Text("Search memories") },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item {
                KindFilterRow(
                    selected = state.kindFilter,
                    onSelect = viewModel::setKindFilter,
                )
            }

            if (state.isEmpty) {
                item {
                    EmptyState(
                        icon = Icons.Outlined.Psychology,
                        title = "Nothing remembered yet",
                        body = "Cirrus writes something down when it learns a fact worth keeping " +
                            "— a preference, a project, how you like to work. Ask it to remember " +
                            "something, or add one yourself.",
                        modifier = Modifier.padding(top = 32.dp),
                    )
                }
            }

            if (state.pinned.isNotEmpty()) {
                item { SectionLabel("Always in context") }
                items(state.pinned, key = { it.id }) { memory ->
                    MemoryCard(
                        memory = memory,
                        onEdit = { editing = memory },
                        onTogglePin = { viewModel.togglePin(memory) },
                        onArchive = { viewModel.archive(memory) },
                    )
                }
            }

            if (state.others.isNotEmpty()) {
                item { SectionLabel(if (state.query.isBlank()) "Remembered" else "Best matches") }
                items(state.others, key = { it.id }) { memory ->
                    MemoryCard(
                        memory = memory,
                        onEdit = { editing = memory },
                        onTogglePin = { viewModel.togglePin(memory) },
                        onArchive = { viewModel.archive(memory) },
                    )
                }
            }

            if (state.retired.isNotEmpty()) {
                item {
                    TextButton(onClick = { showRetired = !showRetired }) {
                        Text(
                            if (showRetired) {
                                "Hide retired (${state.retired.size})"
                            } else {
                                "Show retired (${state.retired.size})"
                            },
                        )
                    }
                }
                if (showRetired) {
                    items(state.retired, key = { it.id }) { memory ->
                        RetiredCard(
                            memory = memory,
                            onRestore = { viewModel.restore(memory) },
                            onDelete = { viewModel.delete(memory) },
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(72.dp)) }
        }
    }

    if (creating) {
        MemoryEditorSheet(
            memory = null,
            onSave = { content, kind, pinned ->
                viewModel.add(content, kind, pinned)
                creating = false
            },
            onDismiss = { creating = false },
        )
    }

    editing?.let { memory ->
        MemoryEditorSheet(
            memory = memory,
            onSave = { content, kind, pinned ->
                viewModel.save(memory, content, kind, pinned)
                editing = null
            },
            onDelete = {
                viewModel.delete(memory)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun MemorySummary(
    total: Int,
    lastConsolidationAt: Long,
    isConsolidating: Boolean,
    onConsolidate: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = when (total) {
                        0 -> "Nothing remembered"
                        1 -> "1 memory"
                        else -> "$total memories"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    text = if (lastConsolidationAt > 0) {
                        "Last tidied ${formatRelative(lastConsolidationAt)}"
                    } else {
                        "Tidied overnight, once there is something to tidy"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Spacer(Modifier.width(12.dp))
            Button(
                onClick = onConsolidate,
                enabled = !isConsolidating && total > 0,
            ) {
                if (isConsolidating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text("Tidy now")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KindFilterRow(selected: MemoryKind?, onSelect: (MemoryKind?) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        FilterChip(
            selected = selected == null,
            onClick = { onSelect(null) },
            label = { Text("All") },
        )
        // Only the kinds that fit; the rest are reachable by searching, which is what people do.
        MemoryKind.entries.take(3).forEach { kind ->
            FilterChip(
                selected = selected == kind,
                onClick = { onSelect(if (selected == kind) null else kind) },
                label = { Text(kind.label) },
            )
        }
    }
}

@Composable
private fun MemoryCard(
    memory: Memory,
    onEdit: () -> Unit,
    onTogglePin: () -> Unit,
    onArchive: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 14.dp, bottom = 10.dp)) {
            Text(
                text = memory.content,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = buildString {
                        append(memory.kind.label)
                        append(" · ")
                        append(formatRelative(memory.updatedAt))
                        if (memory.recallCount > 0) {
                            append(" · recalled ")
                            append(memory.recallCount)
                            append(if (memory.recallCount == 1) " time" else " times")
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = onTogglePin,
                    modifier = Modifier.minimumInteractiveComponentSize(),
                ) {
                    Icon(
                        imageVector = if (memory.pinned) {
                            Icons.Outlined.Bookmark
                        } else {
                            Icons.Outlined.BookmarkBorder
                        },
                        contentDescription = if (memory.pinned) "Unpin" else "Keep in context",
                        tint = if (memory.pinned) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(
                    onClick = onArchive,
                    modifier = Modifier.minimumInteractiveComponentSize(),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = "Retire this memory",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun RetiredCard(memory: Memory, onRestore: () -> Unit, onDelete: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = memory.content,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onRestore, modifier = Modifier.minimumInteractiveComponentSize()) {
                Icon(
                    imageVector = Icons.Outlined.Restore,
                    contentDescription = "Restore",
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.minimumInteractiveComponentSize()) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = "Delete for good",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** Shared by the "add" and "edit" paths, which differ only in whether delete is offered. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun MemoryEditorSheet(
    memory: Memory?,
    onSave: (String, MemoryKind, Boolean) -> Unit,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var content by remember(memory?.id) { mutableStateOf(memory?.content.orEmpty()) }
    var kind by remember(memory?.id) { mutableStateOf(memory?.kind ?: MemoryKind.FACT) }
    var pinned by remember(memory?.id) { mutableStateOf(memory?.pinned ?: false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            Text(
                text = if (memory == null) "Remember something" else "Edit memory",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "One self-contained sentence. It will be read without any conversation " +
                    "around it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = content,
                onValueChange = { content = it.take(Memory.MAX_CONTENT_CHARS) },
                placeholder = { Text("Prefers Kotlin, and dislikes Java's ceremony") },
                minLines = 2,
                maxLines = 6,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(14.dp))
            Text(
                text = kind.hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MemoryKind.entries.forEach { option ->
                    FilterChip(
                        selected = option == kind,
                        onClick = { kind = option },
                        label = { Text(option.label) },
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { pinned = !pinned }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (pinned) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
                    contentDescription = null,
                    tint = if (pinned) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Always in context", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "Sent with every message, without being looked up",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onSave(content, kind, pinned) },
                    enabled = content.isNotBlank(),
                ) {
                    Text("Save")
                }
                onDelete?.let {
                    TextButton(onClick = it) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}
