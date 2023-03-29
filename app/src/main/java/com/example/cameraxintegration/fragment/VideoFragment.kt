package com.example.cameraxintegration.fragment

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.video.*
import androidx.core.util.Consumer
import androidx.lifecycle.lifecycleScope
import com.example.cameraxintegration.databinding.FragmentVideoBinding
import com.example.cameraxintegration.utils.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class VideoFragment : BaseFragment<FragmentVideoBinding>() {

    private lateinit var videoCapture: VideoCapture<Recorder>
    private var currentRecording: Recording? = null
    private lateinit var recordingState: VideoRecordEvent
    private var startTime = 0L

    override fun getViewBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
    ): FragmentVideoBinding = FragmentVideoBinding.inflate(inflater, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel.lensFacing.observe(viewLifecycleOwner){ lens ->
            Log.i("kanaku", "onResume:44 $lens")
            lensFacing = lens

        }

        binding.apply {
            // Added Delay for binding the videoCapture useCase
            Handler(Looper.getMainLooper()).postDelayed({
                cameraPreviewView.post {
                    displayId = cameraPreviewView.display.displayId

                    lifecycleScope.launch {
                        setupCamera()
                        bindCameraUseCase()
                    }
                }
            }, 200)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        bindCameraUseCase()
    }

    private fun bindCameraUseCase() {
        binding.apply {
            val metrics = windowManager.getCurrentWindowMetrics().bounds
            Log.d(TAG, "Screen metrics: ${metrics.width()} x ${metrics.height()}")

            val screenAspectRatio = aspectRatio(metrics.width(), metrics.height())
            Log.d(TAG, "Preview aspect ratio: $screenAspectRatio")

            val rotation = cameraPreviewView.display?.rotation

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
            preview = rotation?.let {
                Preview.Builder()
                    // We request aspect ratio but no resolution
                    .setTargetAspectRatio(screenAspectRatio)
                    // Set initial target rotation
                    .setTargetRotation(it).build()
            }

            cameraProvider.unbindAll()

            try {
                // A variable number of use-cases can be passed here -
                // camera provides access to CameraControl & CameraInfo
                camera = cameraProvider.bindToLifecycle(
                    this@VideoFragment, cameraSelector, videoCapture, preview
                )
                // Attach the viewfinder's surface provider to preview use case
                preview?.setSurfaceProvider(cameraPreviewView.surfaceProvider)
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }
    }


    override fun onPause() {
        binding.cameraPreviewView.bitmap?.apply {
            viewModel.onPreviewBitmap(this)
        }
        stopRecording()
        super.onPause()
    }

    override fun onResume() {
        Log.i("kanaku", "onResume:video $lensFacing")
        if (stopped) {
            binding.cameraPreviewView.invalidate()
            if (currentRecording == null) viewModel.onProgressValueUpdate(recordingDuration)
            viewModel.lensFacing.observe(this){ lens ->
                Log.i("kanaku", "onResume:132 $lens")
                lensFacing = lens

            }
            bindCameraUseCase()
            stopped = false
        }
        super.onResume()
    }

    @SuppressLint("RestrictedApi", "MissingPermission")
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
            .apply { withAudioEnabled() }
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

        Log.i("TAG", "event: $event")

        updateUI(event)

        if (event is VideoRecordEvent.Start) startTime = System.currentTimeMillis()


        if (event is VideoRecordEvent.Finalize) {
            if ((System.currentTimeMillis() - startTime) < 1000) {
                runOnUiThread {
                    Toast.makeText(
                        requireContext(),
                        "Recording time difference is lesser than 1 second",
                        Toast.LENGTH_SHORT
                    ).show()
                    viewModel.onProgressValueUpdate(recordingDuration)
                }
                stopRecording()

            } else {
                // display the captured video
                Log.i("TAG", "captureListener: ${event.outputResults.outputUri}")
                runOnUiThread {
                    viewModel.onProgressValueUpdate(recordingDuration)
                }
                listener?.onImageVideoResult(event.outputResults.outputUri)
//            requireActivity().finish()
            }
        }
    }


    private fun updateUI(event: VideoRecordEvent?) {
        var time = 0
        event?.recordingStats?.apply {
            time = TimeUnit.NANOSECONDS.toSeconds(recordedDurationNanos).toInt()
            Log.i(TAG, "updateUI: $time")
        }
        runOnUiThread {
            viewModel.onProgressValueUpdate(time)
        }
        if (time >= recordingDuration) stopRecording()
    }

    @SuppressLint("RestrictedApi")
    private fun stopRecording() {
        currentRecording?.let {
            videoCapture.camera?.cameraControl?.enableTorch(false)
            runOnUiThread {
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
        binding.cameraPreviewView.bitmap?.let {
            viewModel.onPreviewBitmap(it)
        }
        defaultPostDelay {
            super.onLensSwapCallback()
            bindCameraUseCase()
        }
    }

    override fun onCaptureCallback() =
        (currentRecording != null).ifElse({ stopRecording() }, { startRecording() })

}