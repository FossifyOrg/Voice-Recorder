package org.fossify.voicerecorder.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager

class BecomingNoisyReceiver(
    private val onBecomingNoisy: () -> Unit
) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
            onBecomingNoisy()
        }
    }
}
