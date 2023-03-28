package com.example.cameraxintegration.fragment

import android.content.ContentValues
import android.content.Context
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.concurrent.futures.await
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.window.WindowManager
import com.example.cameraxintegration.R
import com.example.cameraxintegration.callbacks.CameraActionCallback
import com.example.cameraxintegration.callbacks.ImageVideoResultCallback
import com.example.cameraxintegration.databinding.FragmentCameraBinding
import com.example.cameraxintegration.utils.*
import com.example.cameraxintegration.viewmodel.ChangeViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraFragment : Fragment(), CameraActionCallback {
    private var _binding: FragmentCameraBinding? = null
    private val binding get() = _binding

    private var displayId: Int = -1
    private var lensFacing: Int = CameraSelector.LENS_FACING_BACK
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var listener: ImageVideoResultCallback? = null
    private lateinit var windowManager: WindowManager
    private val displayManager by lazy {
        requireActivity().getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    }

    /** Blocking camera operations are performed using this executor */
    private lateinit var cameraExecutor: ExecutorService

    private var flashMode = ImageCapture.FLASH_MODE_AUTO

    private val viewModel by lazy {
        ViewModelProvider(requireActivity()).get(ChangeViewModel::class.java)
    }

    private var preview: Preview? = null
    private var stopped = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentCameraBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        cameraExecutor = Executors.newSingleThreadExecutor()
        displayManager.registerDisplayListener(displayListener, null)
        windowManager = WindowManager(view.context)
        viewModel.onFlashState(flashMode)

        binding?.apply {
            cameraPreviewView.post {
                displayId = cameraPreviewView.display.displayId

                lifecycleScope.launch {
                    setUpCamera()
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        bindCameraUseCase()
    }

    fun imageVideoCallbackListener(listener: ImageVideoResultCallback) {
        this.listener = listener
    }

    private suspend fun setUpCamera() {
        cameraProvider = ProcessCameraProvider.getInstance(requireContext()).await()

        cameraProvider?.let {
            lensFacing = when {
                hasBackCamera(it) -> CameraSelector.LENS_FACING_BACK
                hasFrontCamera(it) -> CameraSelector.LENS_FACING_FRONT
                else -> throw IllegalStateException("Back and front camera are unavailable")
            }
        }
        bindCameraUseCase()
    }

    private fun bindCameraUseCase() {
        binding?.apply {
            val metrics = windowManager.getCurrentWindowMetrics().bounds
            Log.d(TAG, "Screen metrics: ${metrics.width()} x ${metrics.height()}")

            val screenAspectRatio = aspectRatio(metrics.width(), metrics.height())
            Log.d(TAG, "Preview aspect ratio: $screenAspectRatio")

            val rotation = cameraPreviewView.display.rotation

            // CameraProvider
            val cameraProvider =
                cameraProvider ?: throw IllegalStateException("Camera initialization failed.")

            val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

            // Preview
            preview = Preview.Builder()
                // We request aspect ratio but no resolution
                .setTargetAspectRatio(screenAspectRatio)
                // Set initial target rotation
                .setTargetRotation(rotation).build()

            // ImageCapture
            imageCapture =
                ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    // We request aspect ratio but no resolution to match preview config, but letting
                    // CameraX optimize for whatever specific resolution best fits our use cases
                    .setTargetAspectRatio(screenAspectRatio).setFlashMode(flashMode)
                    // Set initial target rotation, we will have to call this again if rotation changes
                    // during the lifecycle of this use case
                    .setTargetRotation(rotation).build()

            // Must unbind the use-cases before rebinding them
            cameraProvider.unbindAll()

            try {
                // A variable number of use-cases can be passed here -
                // camera provides access to CameraControl & CameraInfo
                camera = cameraProvider.bindToLifecycle(
                    this@CameraFragment, cameraSelector, preview, imageCapture
                )
                // Attach the viewfinder's surface provider to preview use case
                preview?.setSurfaceProvider(cameraPreviewView.surfaceProvider)

            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }
    }


    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
        cameraExecutor.shutdown()
        displayManager.unregisterDisplayListener(displayListener)
    }

    override fun onPause() {
        cameraProvider?.unbindAll()
        stopped = true
        super.onPause()
    }

    override fun onResume() {
        if (stopped) {
            binding?.cameraPreviewView?.invalidate()
            bindCameraUseCase()
            stopped = false
        }
        super.onResume()
    }

    /*    *
   * We need a display listener for orientation changes that do not trigger a configuration
   * change, for example if we choose to override config change in manifest or for 180-degree
   * orientation changes.*/

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) = view?.let { view ->
            if (displayId == this@CameraFragment.displayId) {
                imageCapture?.targetRotation = view.display.rotation
            }
        } ?: Unit
    }

    companion object {
        fun newInstance(): CameraFragment {
            return CameraFragment()
        }
    }

    override fun onLensSwapCallback() {
        lensFacing =
            if (CameraSelector.LENS_FACING_FRONT == lensFacing) CameraSelector.LENS_FACING_BACK else CameraSelector.LENS_FACING_FRONT
        bindCameraUseCase()
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

        imageCapture?.flashMode = flashMode
    }

    override fun onCaptureCallback() = takePicture()

    private fun takePicture() {
// Create time stamped name and MediaStore entry.
        val name = SimpleDateFormat(FILENAME, Locale.US).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, PHOTO_TYPE)
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                val appName = requireContext().resources.getString(R.string.app_name)
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/${appName}")
            }
        }

        // Create output options object which contains file + metadata
        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            requireContext().contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()

        // Get a stable reference of the modifiable image capture use case
        imageCapture?.let { imageCapture ->
            // Setup image capture listener which is triggered after photo has been taken
            imageCapture.takePicture(outputOptions,
                cameraExecutor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onError(exc: ImageCaptureException) {
                        Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                    }

                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        val savedUri = output.savedUri
                        Log.d(TAG, "Photo capture succeeded: $savedUri")
                        savedUri?.let {
                            listener?.onImageVideoResult(savedUri)
                        }
//                        requireActivity().finish()
                    }
                })
        }
    }
}