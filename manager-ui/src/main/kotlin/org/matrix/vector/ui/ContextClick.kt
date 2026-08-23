package org.matrix.vector.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput

/**
 * A row's whole gesture set: tap it, hold it — and, from a mouse or a touchpad, right-click it.
 *
 * A tablet with a keyboard case is a pointer device, and the way to ask a row for its menu with a
 * pointer is the secondary button: a right click, or a two-finger tap on the touchpad. Compose has
 * no notion of that gesture. `combinedClickable` reads a press from any button as the beginning of
 * an ordinary tap and reaches [onLongClick] only by timing a held finger, so on a touchpad every
 * row here answered a right click by doing whatever a left click does, and whatever the hold was
 * for could not be reached at all. The View-based manager never had to say any of this: a `View`
 * opens its registered context menu on a secondary press by itself, which is why the gesture worked
 * before the manager was written in Compose.
 *
 * So the secondary press is caught here and handed to [onLongClick] — the same action, because the
 * two gestures mean the same thing, one asked with a finger and one asked with a pointer.
 */
fun Modifier.contextClickable(onClick: () -> Unit, onLongClick: (() -> Unit)? = null): Modifier =
    combinedClickable(onClick = onClick, onLongClick = onLongClick)
        .then(if (onLongClick == null) Modifier else Modifier.onSecondaryPress(onLongClick))

/**
 * The secondary button's press, taken before anything underneath can read it as a tap.
 *
 * On [Initial][PointerEventPass.Initial], the pass that runs before the one `combinedClickable`
 * listens on — the same way [org.matrix.vector.ui.navigation.PanelBar] gets ahead of the item it
 * wraps — and every event of the press is consumed, which is what keeps the click from landing:
 * foundation asks for a press nothing else has taken, and for a release nothing else has taken.
 *
 * The action runs on the way down rather than on the release, matching the platform: a right click
 * on a `View` opens its context menu the moment the button goes down. It runs on the first event
 * that reports the button held rather than on the press event alone, because a pointer that
 * announces its buttons a moment after it announces the touch would otherwise be missed.
 */
private fun Modifier.onSecondaryPress(action: () -> Unit): Modifier =
    pointerInput(action) {
        awaitPointerEventScope {
            var fired = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (!event.buttons.isSecondaryPressed) {
                    fired = false
                    continue
                }
                event.changes.forEach { it.consume() }
                if (!fired) {
                    fired = true
                    action()
                }
            }
        }
    }
