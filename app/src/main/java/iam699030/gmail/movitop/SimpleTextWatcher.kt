package iam699030.gmail.movitop

import android.text.Editable
import android.text.TextWatcher

/** Minimal [TextWatcher] that only forwards the current text on each change. */
class SimpleTextWatcher(
    private val onChanged: (String) -> Unit
) : TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
    override fun afterTextChanged(s: Editable?) {
        onChanged(s?.toString().orEmpty())
    }
}
