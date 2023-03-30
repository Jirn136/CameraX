package com.example.cameraxintegration.fragment

import android.annotation.SuppressLint
import android.content.ContentValues
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.concurrent.futures.await
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import androidx.lifecycle.lifecycleScope
import com.example.cameraxintegration.activities.BaseViewPagerActivity.Companion.lensFacing
import com.example.cameraxintegration.databinding.FragmentVideoBinding
import com.example.cameraxintegration.utils.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class VideoFragment : BaseFragment<FragmentVideoBinding>() {

    private lateinit var videoCapture: VideoCapture<Recorder>
    private var currentRecording: Recording? = null
    private var stopped = false

    /** Blocking camera operations are performed using this executor */
    private val cameraExecutor by lazy {
        ContextCompat.getMainExecutor(requireContext())
    }

    override fun getViewBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
    ): FragmentVideoBinding = FragmentVideoBinding.inflate(inflater, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.apply {
            // Added Delay for binding the videoCapture useCase
            Handler(Looper.getMainLooper()).postDelayed({
                lifecycleScope.launch {
                    bindCameraUseCase()
                }
            }, 200)
        }
    }

    private suspend fun bindCameraUseCase() {
        binding.apply {
            val cameraProvider = ProcessCameraProvider.getInstance(requireContext()).await()
            val metrics = windowManager.getCurrentWindowMetrics().bounds
            Log.d(TAG, "Screen metrics: ${metrics.width()} x ${metrics.height()}")

            val screenAspectRatio = aspectRatio(metrics.width(), metrics.height())
            Log.d(TAG, "Preview aspect ratio: $screenAspectRatio")

            val rotation = cameraPreviewView.display?.rotation

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
        cameraProvider?.unbind(videoCapture)
        stopped = true
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        binding.cameraPreviewView.invalidate()
        viewModel.onProgressValueUpdate(recordingDuration)
        viewLifecycleOwner.lifecycleScope.launch {
            bindCameraUseCase()
        }
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
        ).setContentValues(contentValues)
            .build()

        // configure Recorder and Start recording to the mediaStoreOutput.
        /* currentRecording?.let {
             it.stop()
             currentRecording = null
         }*/
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

        Log.i("TAG", "event: $event recDuration: $recordingDuration")

        updateUI(event)

        if (event is VideoRecordEvent.Finalize) {
            // display the captured video
            Log.i("TAG", "captureListener: ${event.outputResults.outputUri}")
            runOnUiThread {
                viewModel.onProgressValueUpdate(recordingDuration)
            }
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
        runOnUiThread {
            viewModel.onProgressValueUpdate(time)
        }
        if (time >= recordingDuration) stopRecording()
    }

    @SuppressLint("RestrictedApi")
    private fun stopRecording() {
        val recording = currentRecording
        if (recording != null) {
            videoCapture.camera?.cameraControl?.enableTorch(false)
            runOnUiThread {
                viewModel.onVideoRecording(true)
            }
            recording.stop()
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
            viewLifecycleOwner.lifecycleScope.launch {
                bindCameraUseCase()
            }
        }
    }

    override fun onCaptureCallback() =
        (currentRecording != null).ifElse({ stopRecording() }, { startRecording() })

}