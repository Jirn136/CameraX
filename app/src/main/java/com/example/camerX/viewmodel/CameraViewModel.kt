package com.example.camerX.viewmodel

import android.graphics.Bitmap
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class CameraViewModel : ViewModel() {
    var flashState = MutableLiveData<Int>()
    var progressValue = MutableLiveData<Int>()
    var isVideoRecording = MutableLiveData<Boolean>()
    var isLensFacingBack = MutableLiveData<Boolean>()
    var previewBitmap = MutableLiveData<Bitmap>()


    fun onFlashState(state: Int) {
        flashState.value = state
    }

    fun onProgressValueUpdate(value: Int) {
        progressValue.value = value
    }

    fun onVideoRecording(videoStopped: Boolean) {
        isVideoRecording.value = videoStopped
    }

    fun onLensFacingBack(isLensFacing: Boolean) {
        isLensFacingBack.postValue(isLensFacing)
    }

    fun onPreviewBitmap(bitmap: Bitmap) {
        previewBitmap.value = bitmap
    }

}