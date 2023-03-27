package com.example.cameraxintegration.fragment

import androidx.fragment.app.Fragment
import com.example.cameraxintegration.callbacks.CameraActionCallback

class VideoFragment : Fragment(),CameraActionCallback {
    companion object {
        fun newInstance(): VideoFragment {
            return VideoFragment()
        }
    }

    override fun onLensSwapCallback() {
        TODO("Not yet implemented")
    }

    override fun onFlashChangeCallback() {
        TODO("Not yet implemented")
    }

    override fun onCaptureCallback() {
        TODO("Not yet implemented")
    }
}