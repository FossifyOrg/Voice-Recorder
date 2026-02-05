package org.fossify.voicerecorder.activities

import android.Manifest
import android.app.AuthenticationRequiredException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.voicerecorder.R
import org.fossify.voicerecorder.helpers.REPOSITORY_NAME

open class SimpleActivity : BaseSimpleActivity() {
    private var permissionCallbacks = mutableMapOf<Int, (Boolean?) -> Unit>()
    private var permissionNextRequestCode: Int = 10000

    private val authLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        // TODO: retry the action that triggered this intent
    }

    override fun getAppIconIDs() = arrayListOf(
        R.mipmap.ic_launcher_red,
        R.mipmap.ic_launcher_pink,
        R.mipmap.ic_launcher_purple,
        R.mipmap.ic_launcher_deep_purple,
        R.mipmap.ic_launcher_indigo,
        R.mipmap.ic_launcher_blue,
        R.mipmap.ic_launcher_light_blue,
        R.mipmap.ic_launcher_cyan,
        R.mipmap.ic_launcher_teal,
        R.mipmap.ic_launcher,
        R.mipmap.ic_launcher_light_green,
        R.mipmap.ic_launcher_lime,
        R.mipmap.ic_launcher_yellow,
        R.mipmap.ic_launcher_amber,
        R.mipmap.ic_launcher_orange,
        R.mipmap.ic_launcher_deep_orange,
        R.mipmap.ic_launcher_brown,
        R.mipmap.ic_launcher_blue_grey,
        R.mipmap.ic_launcher_grey_black
    )

    override fun getAppLauncherName() = getString(R.string.app_launcher_name)

    override fun getRepositoryName() = REPOSITORY_NAME

    // NOTE: Need this instead of using `BaseSimpleActivity.handlePermission` because it doesn't always work correctly (particularly on old SDKs). Possibly
    // because this app invokes the permission request from multiple places and `BaseSimpleActivity` doesn't handle it well?
    fun handleExternalStoragePermission(externalStoragePermission: ExternalStoragePermission, callback: (Boolean?) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // External storage permissions to access MediaStore are no longer needed
            callback(true)
            return
        }

        val permission = when (externalStoragePermission) {
            ExternalStoragePermission.READ -> Manifest.permission.READ_EXTERNAL_STORAGE
            ExternalStoragePermission.WRITE -> Manifest.permission.WRITE_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            callback(true)
            return
        }


        val requestCode = permissionNextRequestCode++
        permissionCallbacks[requestCode] = callback

        ActivityCompat.requestPermissions(
            this, arrayOf(permission), requestCode
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        val callback = permissionCallbacks.remove(requestCode)
        val result = grantResults.firstOrNull()?.let { it == PackageManager.PERMISSION_GRANTED }

        callback?.invoke(result)
    }

    open fun handleRecordingStoreError(exception: Exception) {
        Log.w(this::class.simpleName, "recording store error", exception)

        if (exception is AuthenticationRequiredException) {
            authLauncher.launch(IntentSenderRequest.Builder(exception.userAction).build())
            return
        }

        runOnUiThread {
            getAlertDialogBuilder().setTitle(getString(R.string.recording_store_error_title)).setMessage(getString(R.string.recording_store_error_message))
                .setPositiveButton(org.fossify.commons.R.string.go_to_settings) { _, _ ->
                    startActivity(Intent(applicationContext, SettingsActivity::class.java).apply {
                        putExtra(SettingsActivity.EXTRA_FOCUS_SAVE_RECORDINGS_FOLDER, true)
                    })
                }.setNegativeButton(org.fossify.commons.R.string.cancel, null).create().show()
        }
    }

}

enum class ExternalStoragePermission {
    READ, WRITE

}
