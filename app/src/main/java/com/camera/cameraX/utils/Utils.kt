package com.camera.cameraX.utils

import android.app.Activity
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.View
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.camera.cameraX.callbacks.CameraXAccessListener
import com.camera.cameraX.callbacks.ImageVideoResultCallback
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min


const val TAG = "CAMERAX_"
const val FILENAME = "yyyy-MM-dd-HH-mm-ss-SSS"
private const val RATIO_4_3_VALUE = 4.0 / 3.0
private const val RATIO_16_9_VALUE = 16.0 / 9.0
const val FILEPATH = "filepath"
const val MAX_REC_DURATION = "max_rec_duration"
private const val POST_DELAY_DURATION = 600L
const val emptyString = ""
var listener: ImageVideoResultCallback? = null
var accessListener: CameraXAccessListener? = null

fun imageVideoCallbackListener(newListener: ImageVideoResultCallback) {
    listener = newListener
}
fun canCameraAccessListener(newListener: CameraXAccessListener) {
    accessListener = newListener
}

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

fun makeViewsGone(vararg view: View) = view.forEach {
    it.visibility = View.GONE
}

fun showViews(vararg view: View) = view.forEach {
    it.visibility = View.VISIBLE
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

fun defaultPostDelay(action: () -> Unit) {
    Handler(Looper.getMainLooper()).postDelayed({
        action()
    }, POST_DELAY_DURATION)
}

fun copy(src: File?, dst: File?) {
    var inStream: FileInputStream? = null
    var outStream: FileOutputStream? = null
    val inChannel: FileChannel
    val outChannel: FileChannel
    try {
        inStream = FileInputStream(src)
        inChannel = inStream.channel
        try {
            outStream = FileOutputStream(dst)
            outChannel = outStream.channel
            inChannel.transferTo(0, inChannel.size(), outChannel)
        } finally {
            outStream?.close()
        }
    } catch (e: Exception) {
        Log.e(TAG, e.message.toString())
    } finally {
        try {
            inStream?.close()
        } catch (e: IOException) {
            Log.e(TAG, e.message.toString())
        }
    }
}

fun getRealPathFromUri(context: Context, contentUri: Uri?): String? {
    var cursor: Cursor? = null
    return try {
        val proj = arrayOf(MediaStore.Video.Media.DATA)
        cursor = context.contentResolver.query(contentUri!!, proj, null,
            null, null)
        val columnIndex = cursor!!.getColumnIndexOrThrow(proj[0])
        cursor.moveToFirst()
        cursor.getString(columnIndex)
    } finally {
        cursor?.close()
    }
}