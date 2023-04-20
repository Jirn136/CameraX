package com.example.camerX.utils

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

object Permissions {
    private fun isPermissionAllowed(context: Context?, permission: String?): Boolean =
        ContextCompat.checkSelfPermission(
            context!!, permission!!
        ) == PackageManager.PERMISSION_GRANTED

    fun requestStorageAccess(
        activity: Activity,
        permissionsLauncher: ActivityResultLauncher<Array<String>>,
    ) {
        val hasReadPermission =
            isPermissionAllowed(activity, Manifest.permission.READ_EXTERNAL_STORAGE)
        val hasWritePermission =
            isPermissionAllowed(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val writePermissionGranted = hasWritePermission || isMinSdk30()

        val cameraPermission = isPermissionAllowed(activity,Manifest.permission.CAMERA)
        val audioPermission = isPermissionAllowed(activity,Manifest.permission.RECORD_AUDIO)

        val permissionsToRequest = mutableListOf<String>()
        if (!writePermissionGranted) {
            permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (!hasReadPermission) {
            permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        if(!cameraPermission){
            permissionsToRequest.add(Manifest.permission.CAMERA)
        }
        if(!audioPermission) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }

        if (permissionsToRequest.isNotEmpty()) {
            when {
                ActivityCompat.shouldShowRequestPermissionRationale(
                    activity,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ) -> {
                    Toast.makeText(activity,"Enable permission",Toast.LENGTH_SHORT).show()
                }
                else -> {
                    showPermissionPopUpForStorage(permissionsLauncher, permissionsToRequest)
                }
            }
        }
    }

    private fun showPermissionPopUpForStorage(
        permissionsLauncher: ActivityResultLauncher<Array<String>>,
        permissionsToRequest: MutableList<String>
    ) {
        permissionsLauncher.launch(permissionsToRequest.toTypedArray())
    }

    fun checkWritePermission(context: Context, permission: String): Boolean =
        isPermissionAllowed(context, permission) || isMinSdk30()


    fun isMinSdk30() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
}