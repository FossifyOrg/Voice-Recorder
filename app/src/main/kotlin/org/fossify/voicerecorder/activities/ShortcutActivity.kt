package org.fossify.voicerecorder.activities

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class ShortcutActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val shortcutIntent = Intent(this, BackgroundRecordActivity::class.java).apply {
            action = "RECORD_ACTION"
        }

        setResult(RESULT_OK, shortcutIntent)
        finish()
    }
}
