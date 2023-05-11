package com.camera.cameraX.viewmodel

import android.graphics.Bitmap
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class CameraViewModel : ViewModel() {
    private val _flashState = MutableLiveData<Int>()
    private val _progressValue = MutableLiveData<Int>()
    private val _isVideoRecording = MutableLiveData<Boolean>()
    private val _isLensFacingBack = MutableLiveData<Boolean>()
    private val _isGettingCall = MutableLiveData<Boolean>()
    private val _previewBitmap = MutableLiveData<Bitmap>()

    val flashState: LiveData<Int>
        get() = _flashState

    val progressValue: LiveData<Int>
        get() = _progressValue

    val isVideoRecording:LiveData<Boolean>
        get() = _isVideoRecording

    val isLensFacingBack:LiveData<Boolean>
        get() = _isLensFacingBack

    val isGettingCall:LiveData<Boolean>
        get() = _isGettingCall

    val previewBitmap:LiveData<Bitmap>
        get() = _previewBitmap

    fun onFlashState(state: Int) = _flashState.postValue(state)

    fun onProgressValueUpdate(value: Int) = _progressValue.postValue(value)
    fun onVideoRecording(videoStopped: Boolean) = _isVideoRecording.postValue(videoStopped)

    fun onLensFacingBack(isLensFacing: Boolean) = _isLensFacingBack.postValue(isLensFacing)

    fun onPreviewBitmap(bitmap: Bitmap) = _previewBitmap.postValue(bitmap)

    fun stopRecording(stopRecording:Boolean) = _isGettingCall.postValue(stopRecording)

}