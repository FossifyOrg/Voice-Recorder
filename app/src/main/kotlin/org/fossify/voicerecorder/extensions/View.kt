package org.fossify.voicerecorder.extensions

import android.view.View

/**
 * Sets a click listener that prevents quick repeated clicks.
 */
fun View.setDebouncedClickListener(
    debounceInterval: Long = 500,
    onClick: (View) -> Unit
) {
    var lastClickTime = 0L
    setOnClickListener {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastClickTime >= debounceInterval) {
            lastClickTime = currentTime
            onClick(it)
        }
    }
}
