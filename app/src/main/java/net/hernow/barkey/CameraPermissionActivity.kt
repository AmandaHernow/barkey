package net.hernow.barkey

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.TextView

/** The system requires an Activity context to show the runtime permission dialog. */
class CameraPermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = getString(R.string.camera_permission_activity_message)
            textSize = 18f
            setPadding(32, 32, 32, 32)
        })
        if (savedInstanceState == null) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
        }
    }

    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != CAMERA_PERMISSION_REQUEST) return

        val granted = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        PermissionState.recordResult(
            context = this,
            granted = granted,
            settingsRequired = !granted && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA),
        )
        sendBroadcast(Intent(PermissionState.ACTION_PERMISSION_RESULT).setPackage(packageName))
        finish()
    }

    private companion object {
        const val CAMERA_PERMISSION_REQUEST = 7401
    }
}
