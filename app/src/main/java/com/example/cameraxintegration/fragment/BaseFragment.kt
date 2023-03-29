package com.example.cameraxintegration.fragment

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.concurrent.futures.await
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.viewbinding.ViewBinding
import androidx.window.WindowManager
import com.example.cameraxintegration.callbacks.CameraActionCallback
import com.example.cameraxintegration.callbacks.ImageVideoResultCallback
import com.example.cameraxintegration.utils.hasBackCamera
import com.example.cameraxintegration.utils.hasFrontCamera
import com.example.cameraxintegration.viewmodel.CameraViewModel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

abstract class BaseFragment<VB : ViewBinding> : Fragment(), CameraActionCallback {

    private var _binding: VB? = null
    protected val binding get() = _binding!!

    var displayId: Int = -1
    var camera: Camera? = null
    var cameraProvider: ProcessCameraProvider? = null
    var listener: ImageVideoResultCallback? = null
    lateinit var windowManager: WindowManager
    val viewModel by lazy {
        ViewModelProvider(requireActivity()).get(CameraViewModel::class.java)
    }
    var preview: Preview? = null
    var flashMode = ImageCapture.FLASH_MODE_AUTO


    /** Blocking camera operations are performed using this executor */
    lateinit var cameraExecutor: ExecutorService

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        _binding = getViewBinding(inflater, container)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        windowManager = WindowManager(view.context)
        viewModel.onFlashState(flashMode)
    }

    suspend fun setupCamera() {
        cameraProvider = ProcessCameraProvider.getInstance(requireContext()).await()

        cameraProvider?.let {
            Log.i("kanaku", "setupCamera: 66")
            lensFacing = when {
                hasBackCamera(it) -> CameraSelector.LENS_FACING_BACK
                hasFrontCamera(it) -> CameraSelector.LENS_FACING_FRONT
                else -> throw IllegalStateException("Back and front camera are unavailable")
            }
        }
    }

    protected abstract fun getViewBinding(inflater: LayoutInflater, container: ViewGroup?): VB

    fun imageVideoCallbackListener(listener: ImageVideoResultCallback) {
        this.listener = listener
    }

    override fun onPause() {
        cameraProvider?.unbindAll()
        super.onPause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cameraExecutor.shutdown()
    }

    override fun onLensSwapCallback() {
        lensFacing =
            if (CameraSelector.LENS_FACING_FRONT == lensFacing)
                CameraSelector.LENS_FACING_BACK else CameraSelector.LENS_FACING_FRONT
        viewModel.onLensFacingBack(lensFacing != CameraSelector.LENS_FACING_FRONT)
    }

    override fun onFlashChangeCallback() {
        val currentModeIndex = flashMode
        val nextMode = if (currentModeIndex < 2) currentModeIndex + 1 else 0
        flashMode = when (nextMode) {
            1 -> ImageCapture.FLASH_MODE_ON
            2 -> ImageCapture.FLASH_MODE_OFF
            else -> ImageCapture.FLASH_MODE_AUTO
        }
        viewModel.onFlashState(flashMode)
    }

    override fun onCaptureCallback() {
//        TODO("Not yet implemented")
    }

    companion object {
        var lensFacing = CameraSelector.LENS_FACING_BACK
    }
}