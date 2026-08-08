package org.fossify.voicerecorder.views

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import org.fossify.commons.views.MySeekBar

class PlayerSeekBar : MySeekBar {
    private var isParentInterceptionDisallowed = false

    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)

    constructor(context: Context, attrs: AttributeSet, defStyle: Int) : super(context, attrs, defStyle)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            setParentInterceptionDisallowed(true)
        }

        val handled = super.onTouchEvent(event)
        if (action == MotionEvent.ACTION_UP && handled) {
            performClick()
        }

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL || !handled) {
            setParentInterceptionDisallowed(false)
        }

        return handled
    }

    override fun performClick() = super.performClick()

    override fun onDetachedFromWindow() {
        setParentInterceptionDisallowed(false)
        super.onDetachedFromWindow()
    }

    private fun setParentInterceptionDisallowed(disallowed: Boolean) {
        if (isParentInterceptionDisallowed != disallowed) {
            parent?.requestDisallowInterceptTouchEvent(disallowed)
            isParentInterceptionDisallowed = disallowed
        }
    }
}
