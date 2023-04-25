package com.camera.cameraX.fragment

import android.annotation.SuppressLint
import android.content.ContentValues
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.concurrent.futures.await
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.window.layout.WindowMetricsCalculator
import com.camera.cameraX.activities.BaseViewPagerActivity.Companion.flashMode
import com.camera.cameraX.activities.BaseViewPagerActivity.Companion.lensFacing
import com.camera.cameraX.activities.BaseViewPagerActivity.Companion.telephoneCallReceiver
import com.camera.cameraX.utils.FILENAME_FORMAT
import com.camera.cameraX.utils.TAG
import com.camera.cameraX.utils.aspectRatio
import com.camera.cameraX.utils.defaultPostDelay
import com.camera.cameraX.utils.gone
import com.camera.cameraX.utils.ifElse
import com.camera.cameraX.utils.listener
import com.camera.cameraX.utils.runOnUiThread
import com.camera.cameraX.utils.show
import com.example.cameraxintegration.databinding.FragmentVideoBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
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
                viewLifecycleOwner.lifecycleScope.launch {
                    bindCameraUseCase()
                }
            }, 200)
        }

        viewModel.isGettingCall.observe(viewLifecycleOwner) {
            if(it) stopRecording()
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private suspend fun bindCameraUseCase() {
        binding.apply {
            val cameraProvider = ProcessCameraProvider.getInstance(requireContext()).await()
            val metrics = WindowMetricsCalculator.getOrCreate()
                .computeCurrentWindowMetrics(requireActivity()).bounds
            Log.d(TAG, "Screen metrics: ${metrics.width()} x ${metrics.height()}")

            val screenAspectRatio = aspectRatio(metrics.width(), metrics.height())
            Log.d(TAG, "Preview aspect ratio: $screenAspectRatio")

            val rotation = cameraPreviewView.display?.rotation

            // Resets CameraController from the previous PreviewView
            binding.cameraPreviewView.controller = null

            val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

            val cameraMetaData = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                CameraMetadata.LENS_FACING_BACK else CameraMetadata.LENS_FACING_FRONT
            Log.i(TAG, "lensFacing $lensFacing metadata:$cameraMetaData")

            val cameraInfo = cameraProvider?.availableCameraInfos?.filter {
                Camera2CameraInfo.from(it).getCameraCharacteristic(
                    CameraCharacteristics.LENS_FACING
                ) == cameraMetaData
            }

            val supportedQualities = QualitySelector.getSupportedQualities(cameraInfo!![0])

            val filteredQualities = arrayListOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD)
                .filter { supportedQualities.contains(it) }

            val qualitySelector = filteredQualities.let {
                QualitySelector.fromOrderedList(
                    it,
                    FallbackStrategy.higherQualityOrLowerThan(Quality.HD)
                )
            }
            Log.i(TAG, "qualitySelector $qualitySelector")

            // build a recorder, which can:
            //   - record video/audio to MediaStore(only shown here), File, ParcelFileDescriptor
            //   - be used create recording(s) (the recording performs recording)
            val recorder = Recorder.Builder().setExecutor(cameraExecutor)
                .setQualitySelector(qualitySelector)
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            Log.i(TAG, "selected quality ${recorder.qualitySelector}")

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
        binding.cameraPreviewView.bitmap?.let {
            viewModel.onPreviewBitmap(it)
        }
        stopRecording()
        cameraProvider?.unbind(videoCapture)
        stopped = true
        super.onPause()
    }

    override fun onResume() {
        if (stopped) {
            binding.cameraPreviewView.invalidate()
            viewModel.onProgressValueUpdate(recordingDuration)
            viewLifecycleOwner.lifecycleScope.launch {
                bindCameraUseCase()
            }
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
        ).setContentValues(contentValues)
            .build()

        // configure Recorder and Start recording to the mediaStoreOutput.
        videoCapture.camera?.cameraControl?.enableTorch(
            flashMode ==
                    ImageCapture.FLASH_MODE_AUTO || flashMode == ImageCapture.FLASH_MODE_ON
        )
        currentRecording = videoCapture.output
            .prepareRecording(requireActivity(), mediaStoreOutput)
            .apply { withAudioEnabled() }
            .start(cameraExecutor, captureListener)

        runOnUiThread {
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
                listener?.onImageVideoResult(event.outputResults.outputUri)
                if(this.isAdded) {
                    requireActivity().finish()
                    binding.progressBar.gone()
                }
                telephoneCallReceiver?.let {
                    context?.let {context ->
                        LocalBroadcastManager.getInstance(context).unregisterReceiver(it)
                    }
                }
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
        val recording = currentRecording
        if (recording != null) {
            binding.progressBar.show()
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