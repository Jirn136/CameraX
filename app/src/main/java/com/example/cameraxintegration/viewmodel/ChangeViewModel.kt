package com.example.cameraxintegration.viewmodel

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class ChangeViewModel : ViewModel() {
    var flashState = MutableLiveData<Int>()
    var progressValue = MutableLiveData<Int>()
    var isVideoRecording = MutableLiveData<Boolean>()

    fun onFlashState(state: Int) {
        flashState.value = state
    }

    fun onProgressValueUpdate(value: Int) {
        progressValue.value = value
    }

    fun onVideoRecording(videoStopped: Boolean) {
        isVideoRecording.value = videoStopped
    }
}