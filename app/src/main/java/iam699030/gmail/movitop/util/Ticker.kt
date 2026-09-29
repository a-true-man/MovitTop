package iam699030.gmail.movitop.util

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Runs [action] every [intervalMillis] while this owner is at least STARTED —
 * for re-formatting an already-fetched "leaves in X" style countdown live
 * (see RelativeTime) without a fresh network/engine round trip. Pauses
 * automatically when backgrounded and resumes on return, same as any other
 * `repeatOnLifecycle(STARTED)` collector.
 */
fun LifecycleOwner.tickEvery(intervalMillis: Long, action: () -> Unit) {
    lifecycleScope.launch {
        repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(intervalMillis)
                action()
            }
        }
    }
}
