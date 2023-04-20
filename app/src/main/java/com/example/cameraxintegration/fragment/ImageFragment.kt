package com.example.cameraxintegration.fragment

import android.content.ContentValues
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.lifecycle.lifecycleScope
import androidx.window.layout.WindowMetricsCalculator
import com.example.cameraxintegration.R
import com.example.cameraxintegration.activities.BaseViewPagerActivity.Companion.flashMode
import com.example.cameraxintegration.activities.BaseViewPagerActivity.Companion.lensFacing
import com.example.cameraxintegration.databinding.FragmentCameraBinding
import com.example.cameraxintegration.utils.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ImageFragment : BaseFragment<FragmentCameraBinding>() {
    private var imageCapture: ImageCapture? = null
    private var stopped = false

    /** Blocking camera operations are performed using this executor */
    private lateinit var cameraExecutor: ExecutorService

    override fun getViewBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
    ): FragmentCameraBinding = FragmentCameraBinding.inflate(inflater, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()

        binding.apply {
            cameraPreviewView.post {
                lifecycleScope.launch {
                    setupCamera()
                    bindCameraUseCase()
                }
            }
        }
    }

    private fun bindCameraUseCase() {
        binding.apply {
            val metrics = WindowMetricsCalculator.getOrCreate()
                .computeCurrentWindowMetrics(requireActivity()).bounds
            Log.d(TAG, "Screen metrics: ${metrics.width()} x ${metrics.height()}")

            val screenAspectRatio = aspectRatio(metrics.width(), metrics.height())
            Log.d(TAG, "Preview aspect ratio: $screenAspectRatio")

            val rotation = cameraPreviewView.display?.rotation

            // CameraProvider
            val cameraProvider =
                cameraProvider ?: throw IllegalStateException("Camera initialization failed.")

            // Resets CameraController from the previous PreviewView
            binding.cameraPreviewView.controller = null

            val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

            // Preview
            preview = rotation?.let {
                Preview.Builder()
                    // We request aspect ratio but no resolution
                    .setTargetAspectRatio(screenAspectRatio)
                    // Set initial target rotation
                    .setTargetRotation(it).build()
            }

            // ImageCapture
            imageCapture =
                rotation?.let {
                    ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        // We request aspect ratio but no resolution to match preview config, but letting
                        // CameraX optimize for whatever specific resolution best fits our use cases
                        .setTargetAspectRatio(screenAspectRatio).setFlashMode(flashMode)
                        // Set initial target rotation, we will have to call this again if rotation changes
                        // during the lifecycle of this use case
                        .setTargetRotation(it).build()
                }

            // Must unbind the use-cases before rebinding them
            cameraProvider.unbindAll()

            try {
                // A variable number of use-cases can be passed here -
                // camera provides access to CameraControl & CameraInfo
                camera = cameraProvider.bindToLifecycle(
                    this@ImageFragment, cameraSelector, preview, imageCapture
                )
                // Attach the viewfinder's surface provider to preview use case
                preview?.setSurfaceProvider(cameraPreviewView.surfaceProvider)

            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }
    }

    override fun onPause() {
        binding.cameraPreviewView.bitmap?.let {
            viewModel.onPreviewBitmap(it)
        }
        cameraProvider?.unbind(imageCapture)
        stopped = true
        super.onPause()
    }

    override fun onResume() {
        if (stopped) {
            binding.cameraPreviewView.invalidate()
            viewLifecycleOwner.lifecycleScope.launch{
            bindCameraUseCase()
            }
            stopped = false
        }
        super.onResume()
    }

    companion object {
        fun newInstance(): ImageFragment {
            return ImageFragment()
        }
    }

    override fun onLensSwapCallback() {
        binding.cameraPreviewView.bitmap?.let {
            viewModel.onPreviewBitmap(it)
        }
        defaultPostDelay {
            super.onLensSwapCallback()
            bindCameraUseCase()
        }
    }


    override fun onFlashChangeCallback() {
        super.onFlashChangeCallback()
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
                        requireActivity().finish()
                    }
                })
        }
    }
}