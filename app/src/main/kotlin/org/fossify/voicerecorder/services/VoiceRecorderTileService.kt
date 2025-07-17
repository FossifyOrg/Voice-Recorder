package org.fossify.voicerecorder.services

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService 
import org.fossify.voicerecorder.helpers.RECORDING_RUNNING
import org.fossify.voicerecorder.helpers.RECORDING_STOPPED
import org.fossify.voicerecorder.models.Events
import org.fossify.voicerecorder.R
import org.fossify.voicerecorder.services.RecorderService
import org.greenrobot.eventbus.EventBus

class VoiceRecorderTileService: TileService() {
  private var status = RECORDING_STOPPED

  // Called when the user taps on your tile in an active or inactive state.
  override fun onClick() {
    super.onClick()
    Intent(this@VoiceRecorderTileService, RecorderService::class.java).apply {
        // try {
            if (status == RECORDING_STOPPED) {
              if (!RecorderService.isRunning) {
                  startService(this)
              }
              qsTile.state = Tile.STATE_ACTIVE
              qsTile.label = "stop recording"
              status = RECORDING_RUNNING
            }
            else {
              if (RecorderService.isRunning) {
                  stopService(this)
              }
              qsTile.state = Tile.STATE_INACTIVE
              qsTile.label = getString(R.string.recording)
              status = RECORDING_STOPPED
            }
            qsTile.updateTile()
            EventBus.getDefault().post(Events.RecordingStatus(status))
    //     } catch (ignored: Exception) {
    //     }
    }
  }
}
