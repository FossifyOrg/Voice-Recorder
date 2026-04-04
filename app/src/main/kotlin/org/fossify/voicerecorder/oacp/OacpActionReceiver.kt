package org.fossify.voicerecorder.oacp

import android.content.Context
import org.oacp.android.OacpParams
import org.oacp.android.OacpReceiver
import org.oacp.android.OacpResult

/**
 * Handles background OACP actions for Voice Recorder.
 *
 * Only background (broadcast) actions live here:
 * - pause, resume, stop, discard
 *
 * The foreground action (start_recording) is handled by
 * MainActivity via an activity intent filter — Hark launches
 * it directly with startActivity().
 */
class OacpActionReceiver : OacpReceiver() {

    override fun onAction(
        context: Context,
        action: String,
        params: OacpParams,
        requestId: String?
    ): OacpResult? {
        return when {
            action.endsWith(".oacp.ACTION_PAUSE_RECORDING") ->
                OacpResult.success("Recording paused")
            action.endsWith(".oacp.ACTION_RESUME_RECORDING") ->
                OacpResult.success("Recording resumed")
            action.endsWith(".oacp.ACTION_STOP_RECORDING") ->
                OacpResult.success("Recording stopped and saved")
            action.endsWith(".oacp.ACTION_DISCARD_RECORDING") ->
                OacpResult.success("Recording discarded")
            else -> null
        }
    }
}
