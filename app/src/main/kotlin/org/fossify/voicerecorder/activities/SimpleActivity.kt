package org.fossify.voicerecorder.activities

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.voicerecorder.R
import org.fossify.voicerecorder.helpers.REPOSITORY_NAME

open class SimpleActivity : BaseSimpleActivity() {
    companion object {
        private const val PERMISSIONS_REQUEST_CODE = 10001
    }

    private var permissionCallback: ((Boolean?) -> Unit)? = null

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
    // because this app invokes the permission request from multiple places and `BaseSimpleActivity` doesn't handle it correctly? The only thing we do
    // differently here is that we invoke the callback even when the request gets cancelled (passing `null` to it).
    fun handleExternalStoragePermissions(externalStoragePermission: ExternalStoragePermission, callback: (Boolean?) -> Unit) {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
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

        permissionCallback = callback

        ActivityCompat.requestPermissions(
            this, arrayOf(permission), PERMISSIONS_REQUEST_CODE
        );
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode != PERMISSIONS_REQUEST_CODE) {
            return
        }

        val callback = permissionCallback
        permissionCallback = null

        callback?.invoke(if (grantResults.isNotEmpty()) grantResults[0] == PackageManager.PERMISSION_GRANTED else null)
    }
}

enum class ExternalStoragePermission {
    READ, WRITE

}
