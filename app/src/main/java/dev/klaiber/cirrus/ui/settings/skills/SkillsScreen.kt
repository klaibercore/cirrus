package dev.klaiber.cirrus.ui.settings.skills

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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.klaiber.cirrus.data.skills.SkillLibrary
import dev.klaiber.cirrus.domain.skills.Skill
import dev.klaiber.cirrus.ui.components.Hairline
import dev.klaiber.cirrus.ui.components.HelpBadge
import dev.klaiber.cirrus.ui.components.OutlinedPanel
import dev.klaiber.cirrus.ui.components.PillButton
import dev.klaiber.cirrus.ui.theme.ContainerShape
import dev.klaiber.cirrus.ui.theme.LargeContainerShape

/**
 * The skills the user has installed, and how to install more.
 *
 * Grouped by the library they came from rather than listed flat, because that is how they arrive
 * and how they are removed: `npx skills add anthropics/skills` is one action here as it is there,
 * and a screen that turned it into nine unrelated rows would make undoing it nine actions.
 *
 * The field takes what the CLI takes — `owner/repo`, an address, a path to one skill inside a big
 * library — so an instruction written for a laptop can be typed here unchanged. What it will not
 * take is a git URL that is not GitHub: cloning needs git, and Android will not run a binary an app
 * downloaded, so there is none. Better to say so than to fail obscurely.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsScreen(
    onBack: () -> Unit,
    viewModel: SkillsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val source by viewModel.source.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var deleteTarget by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.notice, state.error) {
        val message = state.error ?: state.notice
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.dismissMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Skills") },
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
                    text = "A skill is a page of instructions for one kind of work — writing a " +
                        "particular sort of document, reviewing an interface, following a " +
                        "house style. Cirrus keeps every installed skill's name and one-line " +
                        "description in front of the model, and the model opens the full page " +
                        "when one applies. Same SKILL.md files as npx skills installs.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }

            item(key = "switch") {
                SkillsSwitchRow(
                    enabled = state.skillsEnabled,
                    count = state.count,
                    onCheckedChange = viewModel::setSkillsEnabled,
                )
            }

            item(key = "install") {
                InstallField(
                    value = source,
                    busy = state.installing != null,
                    onValueChange = viewModel::onSourceChange,
                    onInstall = viewModel::installTyped,
                )
            }

            if (state.isEmpty) {
                item(key = "empty") { EmptySkillsCard() }
            }

            state.libraries.forEach { library ->
                item(key = "library-${library.repository}") {
                    LibraryHeader(
                        repository = library.repository,
                        count = library.skills.size,
                        onRemove = { deleteTarget = library.repository },
                    )
                }
                items(library.skills, key = { it.id }) { skill ->
                    SkillRow(
                        skill = skill,
                        busy = state.installing == skill.id,
                        onToggle = { viewModel.setEnabled(skill.id, it) },
                        onUpdate = { viewModel.update(skill.id) },
                        onRemove = { viewModel.remove(skill.id) },
                    )
                }
            }

            if (state.suggestions.isNotEmpty()) {
                item(key = "catalog-header") {
                    Text(
                        text = "Known libraries",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 18.dp, bottom = 2.dp),
                    )
                }
                item(key = "catalog-note") {
                    Text(
                        text = "Starting points, not endorsements. Cirrus shows you what it " +
                            "found before anything is used.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                items(state.suggestions, key = { it.repository }) { entry ->
                    LibrarySuggestionRow(
                        entry = entry,
                        busy = state.installing == entry.repository,
                        onAdd = { viewModel.install(entry.repository) },
                    )
                }
            }

            item(key = "tail") { Spacer(Modifier.height(24.dp)) }
        }
    }

    deleteTarget?.let { repository ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Remove everything from $repository?") },
            text = { Text("Its skills stop being offered to the model. You can install it again.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.removeLibrary(repository)
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
private fun SkillsSwitchRow(enabled: Boolean, count: Int, onCheckedChange: (Boolean) -> Unit) {
    OutlinedPanel(shape = LargeContainerShape, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Offer skills to the model", style = MaterialTheme.typography.titleSmall)
                    HelpBadge(
                        title = "Skills",
                        text = "With this on, every installed skill's name and description go " +
                            "into the system message, and the model can call use_skill to read " +
                            "the whole page. Nothing leaves the phone — a skill is a file Cirrus " +
                            "already has — so this works whether or not the conversation's tools " +
                            "switch is on. With nothing installed it costs nothing at all.",
                    )
                }
                Text(
                    text = when (count) {
                        0 -> "Nothing installed yet."
                        1 -> "1 skill installed."
                        else -> "$count skills installed."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun InstallField(
    value: String,
    busy: Boolean,
    onValueChange: (String) -> Unit,
    onInstall: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text("owner/repo") },
            placeholder = { Text("anthropics/skills") },
            singleLine = true,
            enabled = !busy,
            shape = ContainerShape,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onInstall() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "A GitHub repository, an address, or a path to one skill inside a library.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                PillButton(
                    label = "Install",
                    onClick = onInstall,
                    enabled = value.isNotBlank(),
                    icon = Icons.Outlined.Add,
                )
            }
        }
    }
}

@Composable
private fun LibraryHeader(repository: String, count: Int, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = repository,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (count == 1) "1 skill" else "$count skills",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRemove, modifier = Modifier.minimumInteractiveComponentSize()) {
            Icon(
                imageVector = Icons.Outlined.Delete,
                contentDescription = "Remove everything from $repository",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * One skill.
 *
 * The description is the row's subtitle rather than something behind a tap, because it is the same
 * sentence the model reads when it decides whether the skill applies — if it does not make sense
 * here it will not work there either, and that is worth being able to see.
 */
@Composable
private fun SkillRow(
    skill: Skill,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
    onUpdate: () -> Unit,
    onRemove: () -> Unit,
) {
    OutlinedPanel(shape = LargeContainerShape, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(17.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = skill.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = skill.enabled, onCheckedChange = onToggle)
            }

            if (skill.description.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = skill.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(4.dp))
            Hairline()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${skill.instructions.length.approximateWords()} words · " +
                        skill.origin.directory,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(14.dp))
                } else {
                    IconButton(
                        onClick = onUpdate,
                        modifier = Modifier.minimumInteractiveComponentSize(),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = "Re-read ${skill.name} from GitHub",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                }
                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.minimumInteractiveComponentSize(),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = "Remove ${skill.name}",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun LibrarySuggestionRow(entry: SkillLibrary, busy: Boolean, onAdd: () -> Unit) {
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
                imageVector = Icons.Outlined.AutoAwesome,
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
                Text(
                    text = entry.repository,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = "Install ${entry.label}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun EmptySkillsCard() {
    OutlinedPanel(shape = LargeContainerShape, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.padding(20.dp)) {
            Text(
                text = "No skills installed. Pick a library below, or type any GitHub repository " +
                    "that has a SKILL.md in it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Length as a word count, because "5,800 characters" means nothing and "900 words" means "this
 * will take a real bite out of the context window when it is loaded".
 */
private fun Int.approximateWords(): Int = (this / AVERAGE_WORD_CHARS).coerceAtLeast(1)

private const val AVERAGE_WORD_CHARS = 6
