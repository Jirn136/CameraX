package com.example.cameraxintegration.viewmodel

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class ChangeViewModel : ViewModel() {
    var flashState = MutableLiveData<Int>()

    fun onFlashState(state:Int) {
        flashState.value = state
    }
}