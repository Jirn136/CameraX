package com.example.cameraxintegration.utils

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

const val TAG = "Kanaku"
const val FILENAME = "yyyy-MM-dd-HH-mm-ss-SSS"
const val PHOTO_TYPE = "image/jpeg"
private const val RATIO_4_3_VALUE = 4.0 / 3.0
private const val RATIO_16_9_VALUE = 16.0 / 9.0
const val ANIMATION_FAST_MILLIS = 50L
const val ANIMATION_SLOW_MILLIS = 100L
const val MAX_REC_DURATION = "max_rec_duration"

fun aspectRatio(width: Int, height: Int): Int {
    val previewRatio = max(width, height).toDouble() / min(width, height)
    if (abs(previewRatio - RATIO_4_3_VALUE) <= abs(previewRatio - RATIO_16_9_VALUE)) {
        return AspectRatio.RATIO_4_3
    }
    return AspectRatio.RATIO_16_9
}

const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"


val Int.counterText: String
    get() {
        var min = 0
        var sec = "" + this
        if (this > 59) {
            min = this / 60
            sec = "" + (this - 60 * min)
        }
        if (sec.length == 1) {
            sec = "0$sec"
        }
        return "$min:$sec"
    }

fun View.gone() {
    visibility = View.GONE
}

fun View.show() {
    visibility = View.VISIBLE
}

fun hideSystemUI(activity: Activity, id: View) {
    WindowCompat.setDecorFitsSystemWindows(activity.window, false)
    WindowInsetsControllerCompat(activity.window, id).let { controller ->
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}

fun Boolean.ifElse(functionOne: () -> Unit, functionTwo: () -> Unit) {
    if (this) functionOne() else functionTwo()
}


/** Returns true if the device has an available back camera. False otherwise */
fun hasBackCamera(cameraProvider: ProcessCameraProvider): Boolean =
    cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)


/** Returns true if the device has an available front camera. False otherwise */
fun hasFrontCamera(cameraProvider: ProcessCameraProvider): Boolean =
    cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)

private object ContextHandler {
    val handler = Handler(Looper.getMainLooper())
    val mainThread = Looper.getMainLooper().thread
}
fun runOnUiThread(action: () -> Unit) {
    if (ContextHandler.mainThread == Thread.currentThread()) action() else ContextHandler.handler.post { action() }
}