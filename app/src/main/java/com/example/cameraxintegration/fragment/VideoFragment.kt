package com.example.cameraxintegration.fragment

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.concurrent.futures.await
import androidx.core.app.ActivityCompat
import androidx.core.util.Consumer
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.window.WindowManager
import com.example.cameraxintegration.callbacks.CameraActionCallback
import com.example.cameraxintegration.callbacks.ImageVideoResultCallback
import com.example.cameraxintegration.databinding.FragmentVideoBinding
import com.example.cameraxintegration.utils.*
import com.example.cameraxintegration.viewmodel.ChangeViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class VideoFragment : Fragment(), CameraActionCallback {
    private var _binding: FragmentVideoBinding? = null
    private val binding get() = _binding

    private var displayId: Int = -1
    private var lensFacing: Int = CameraSelector.LENS_FACING_BACK
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var listener: ImageVideoResultCallback? = null
    private lateinit var windowManager: WindowManager
    private val displayManager by lazy {
        requireActivity().getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    }

    private lateinit var videoCapture: VideoCapture<Recorder>
    var currentRecording: Recording? = null
    private lateinit var recordingState: VideoRecordEvent

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
    ): View? {
        _binding = FragmentVideoBinding.inflate(inflater, container, false)
        return _binding?.root
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
                    setupCamera()
                }
            }
        }
    }

    private suspend fun setupCamera() {
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

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
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

            val qualitySelector = QualitySelector.fromOrderedList(
                listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD),
                FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
            )

            // build a recorder, which can:
            //   - record video/audio to MediaStore(only shown here), File, ParcelFileDescriptor
            //   - be used create recording(s) (the recording performs recording)
            val recorder = Recorder.Builder()
                .setQualitySelector(qualitySelector)
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            // Preview
            preview = Preview.Builder()
                // We request aspect ratio but no resolution
                .setTargetAspectRatio(screenAspectRatio)
                // Set initial target rotation
                .setTargetRotation(rotation).build().apply {
                    setSurfaceProvider(cameraPreviewView.surfaceProvider)
                }

            cameraProvider.unbindAll()

            try {
                // A variable number of use-cases can be passed here -
                // camera provides access to CameraControl & CameraInfo
                camera = cameraProvider.bindToLifecycle(
                    this@VideoFragment, cameraSelector, videoCapture, preview
                )
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }
    }


    override fun onPause() {
        stopRecording()
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

    override fun onDestroyView() {
        super.onDestroyView()
        cameraExecutor.shutdown()
        displayManager.unregisterDisplayListener(displayListener)
    }

    /*    *
* We need a display listener for orientation changes that do not trigger a configuration
* change, for example if we choose to override config change in manifest or for 180-degree
* orientation changes.*/

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        @SuppressLint("RestrictedApi")
        override fun onDisplayChanged(displayId: Int) = view?.let { view ->
            if (displayId == this@VideoFragment.displayId) {
                videoCapture.targetRotation = view.display.rotation
            }
        } ?: Unit
    }


    @SuppressLint("RestrictedApi")
    private fun startRecording() {
        // create MediaStoreOutputOptions for our recorder: resulting our recording!
        val name = "CameraX-recording-" +
                SimpleDateFormat(FILENAME_FORMAT, Locale.US)
                    .format(System.currentTimeMillis()) + ".mp4"
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
        }
        val mediaStoreOutput = MediaStoreOutputOptions.Builder(
            requireActivity().contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        )
            .setContentValues(contentValues)
            .build()

        // configure Recorder and Start recording to the mediaStoreOutput.
        currentRecording?.let {
            it.stop()
            currentRecording = null
        }
        videoCapture.camera?.cameraControl?.enableTorch(
            (flashMode == ImageCapture.FLASH_MODE_AUTO || flashMode == ImageCapture.FLASH_MODE_ON)
        )
        currentRecording = videoCapture.output
            .prepareRecording(requireActivity(), mediaStoreOutput)
            .apply {
                if (ActivityCompat.checkSelfPermission(
                        requireContext(),
                        Manifest.permission.RECORD_AUDIO
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    return
                }
                withAudioEnabled()
            }
            .start(cameraExecutor, captureListener)
        requireActivity().runOnUiThread {
            viewModel.onVideoRecording(false)
        }

        Log.i(TAG, "Recording started")
    }

    /**
     * CaptureEvent listener.
     */
    private val captureListener = Consumer<VideoRecordEvent> { event ->
        // cache the recording state
        if (event !is VideoRecordEvent.Status) recordingState = event

        updateUI(event)

        if (event is VideoRecordEvent.Finalize) {
            // display the captured video
            Log.i("TAG", "captureListener: ${event.outputResults.outputUri}")
            listener?.onImageVideoResult(event.outputResults.outputUri)
//            requireActivity().finish()
        }
    }


    private fun updateUI(event: VideoRecordEvent?) {
        var time = 0
        event?.recordingStats?.apply {
            time = TimeUnit.NANOSECONDS.toSeconds(recordedDurationNanos).toInt()
            Log.i(TAG, "updateUI: $time")
        }
        requireActivity().runOnUiThread {
            viewModel.onProgressValueUpdate(time)
        }
        if (time >= recordingDuration) stopRecording()
    }

    @SuppressLint("RestrictedApi")
    private fun stopRecording() {
        currentRecording?.let {
            videoCapture.camera?.cameraControl?.enableTorch(false)
            requireActivity().runOnUiThread {
                viewModel.onVideoRecording(true)
            }
            it.stop()
            currentRecording = null
        }
    }

    companion object {

        private var recordingDuration: Int = 0
        fun newInstance(duration: Int): VideoFragment {
            recordingDuration = duration
            return VideoFragment()
        }
    }

    override fun onLensSwapCallback() {
        lensFacing =
            if (CameraSelector.LENS_FACING_FRONT == lensFacing) CameraSelector.LENS_FACING_BACK
            else CameraSelector.LENS_FACING_FRONT
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
    }

    override fun onCaptureCallback() =
        (currentRecording != null).ifElse({ stopRecording() }, { startRecording() })

}