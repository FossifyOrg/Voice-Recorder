package org.fossify.voicerecorder.services

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService 
import org.fossify.voicerecorder.services.RecorderService

class VoiceRecorderTileService: TileService() {
  // Called when the user taps on your tile in an active or inactive state.
  override fun onClick() {
    super.onClick()
    Intent(this@VoiceRecorderTileService, RecorderService::class.java).apply {
        // try {
            if (qsTile.state == Tile.STATE_INACTIVE) {
              // RecorderService.startRecording()
              if (!RecorderService.isRunning) {
                  startService(this)
              }
              qsTile.state = Tile.STATE_ACTIVE
              qsTile.label = "stop recording"
            }
            else {
              // RecorderService.stopRecording()
              if (RecorderService.isRunning) {
                  stopService(this)
              }
              qsTile.state = Tile.STATE_INACTIVE
              qsTile.label = "start recording"
            }
            qsTile.updateTile()
    //     } catch (ignored: Exception) {
    //     }
    }
  }
}
