package dev.klaiber.cirrus.ui.chat.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Whether a self-closing section of a turn — the reasoning trace, the tool group — is open.
 *
 * The rule these sections want is "open while there is nothing else to watch, shut once there is",
 * and the obvious way to write it is a `remember(active)` keyed on whether the work is running. That
 * is what was here, and it is wrong in two ways that only show up on a turn with more than one round
 * in it.
 *
 * The first: the key changes in *both* directions. A turn that thinks, answers a little, calls a
 * tool, thinks again — which is every agentic turn — flips `active` back to true, the key changes,
 * and the section the reader watched close reopens underneath them. Mid-answer, that is a panel of
 * reasoning shoving the sentence they were reading down the screen.
 *
 * The second: it throws away the reader's own decision. Someone who opens the reasoning to follow it
 * loses it at the next tool call; someone who shuts it gets it back. A collapse is a choice about
 * this section, not about this round of it.
 *
 * So closing is a **latch**. It falls once, when the work it was covering lands, and nothing reopens
 * it — not another tool call, not another burst of thinking. And a tap sets [userChoice], which from
 * then on is the only thing that decides: the section does what it was last told, forever.
 */
@Stable
internal class SectionExpansion(initiallyExpanded: Boolean) {

    /** What the reader last asked for, or null if they have not asked. Outranks everything. */
    private var userChoice by mutableStateOf<Boolean?>(null)

    /** The automatic answer. One-way: true only until the first [settle]. */
    private var auto by mutableStateOf(initiallyExpanded)

    val expanded: Boolean get() = userChoice ?: auto

    fun toggle() {
        userChoice = !expanded
    }

    /** The work this section was covering has landed. Shuts it, once and for good. */
    fun settle() {
        auto = false
    }
}

/**
 * Remembers a [SectionExpansion] for a section whose work is [active].
 *
 * [openWhileActive] is the "is this worth watching?" question, asked once. It is separate from
 * [active] because a section can appear part-finished — a tool group's second call starting while
 * its first has already returned — and something that arrives with results in it is history, not
 * something to watch happen. Opening it would move the answer down the screen to show the reader
 * work they did not ask to see.
 */
@Composable
internal fun rememberSectionExpansion(
    active: Boolean,
    openWhileActive: Boolean = true,
): SectionExpansion {
    // No key: this state has to outlive every change to the turn it belongs to. That is the fix.
    val expansion = remember { SectionExpansion(active && openWhileActive) }
    LaunchedEffect(active) { if (!active) expansion.settle() }
    return expansion
}
