package com.camera.cameraX.callbacks

import android.net.Uri

interface ImageVideoResultCallback {
    fun onImageVideoResult(uri: Uri)
}


interface CameraXAccessListener {
    fun canAccessCamera():Pair<Boolean,String?>
}