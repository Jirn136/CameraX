package com.example.cameraxintegration

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.*
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.video.VideoCapture
import androidx.concurrent.futures.await
import androidx.core.util.Consumer
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.window.WindowManager
import com.example.cameraxintegration.databinding.CameraUiContainerBinding
import com.example.cameraxintegration.databinding.FragmentCameraCaptureBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CameraCaptureFragment : Fragment() {
    private var _binding: FragmentCameraCaptureBinding? = null
    private val binding get() = _binding!!

    private var cameraBinding: CameraUiContainerBinding? =
        null

    private var displayId: Int = -1
    private var lensFacing: Int = CameraSelector.LENS_FACING_BACK
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var windowManager: WindowManager
    private val displayManager by lazy {
        requireActivity().getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    }

    /** Blocking camera operations are performed using this executor */
    private lateinit var cameraExecutor: ExecutorService

    private lateinit var videoCapture: VideoCapture<Recorder>
    private var currentRecording: Recording? = null
    private lateinit var recordingState: VideoRecordEvent
    private var listener: ImageVideoResultCallback? = null
    private var flashMode = ImageCapture.FLASH_MODE_AUTO
    private var isCaptureTypeImage = true


    private var clickedTime = 0L
    private lateinit var mHandler: Handler
    private lateinit var mRunnable: Runnable

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentCameraCaptureBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Initialize our background executor
        cameraExecutor = Executors.newSingleThreadExecutor()
        // Every time the orientation of device changes, update rotation for use cases
        displayManager.registerDisplayListener(displayListener, null)

        // Initialize WindowManager to retrieve display metrics
        windowManager = WindowManager(view.context)

        binding.previewView.post {
            displayId = binding.previewView.display.displayId
            // Build UI controls
            updateCameraUi()
            updateFlashMode()

            // Set up the camera and its use cases
            lifecycleScope.launch {
                setUpCamera()
            }
        }
    }

    private fun updateFlashMode() {
        cameraBinding?.apply {
            when (flashMode) {
                1 -> imgFlash.setImageResource(R.drawable.ic_flash)
                2 -> imgFlash.setImageResource(R.drawable.ic_flash_off)
                else -> imgFlash.setImageResource(R.drawable.ic_flash_auto)
            }
        }
    }

    fun imageVideoCallbackListener(listener: ImageVideoResultCallback) {
        this.listener = listener
    }

    /** Method used to re-draw the camera UI controls, called every time configuration changes. */
    @SuppressLint("ClickableViewAccessibility")
    private fun updateCameraUi() {

        // Remove previous UI if any
        cameraBinding?.root?.let {
            binding.root.removeView(it)
        }

        cameraBinding =
            CameraUiContainerBinding.inflate(
                LayoutInflater.from(requireContext()),
                binding.root,
                true
            )


        cameraBinding?.apply {
            imgCapture.apply {
                setOnTouchListener { _, event ->
                    when (event.action) {
                        MotionEvent.ACTION_UP -> {
                            mHandler.removeCallbacks(mRunnable)
                            if (System.currentTimeMillis() - clickedTime < 500) {
                                if (!isCaptureTypeImage) bindCameraUseCases(isFirstTime = false)
                                isCaptureTypeImage = true
                                takePicture()
                            } else {
                                if (currentRecording != null) stopRecording()
                            }
                            Log.i(TAG, "updateCameraUi: up $clickedTime")

                        }
                        MotionEvent.ACTION_DOWN -> {
                            clickedTime = System.currentTimeMillis()
                            mHandler = Handler(Looper.getMainLooper())
                            mRunnable = Runnable {
                                isCaptureTypeImage = false
                                bindCameraUseCases(true, isFirstTime = false)
                                startRecording()
                            }
                            mHandler.postDelayed(mRunnable, 500)
                            Log.i(TAG, "updateCameraUi:down $clickedTime")
                        }
                    }
                    false
                }
            }

            imgFlash.setOnClickListener {
                val currentModeIndex = flashMode
                val nextMode = if (currentModeIndex < 2) currentModeIndex + 1 else 0
                flashMode = when (nextMode) {
                    1 -> ImageCapture.FLASH_MODE_ON
                    2 -> ImageCapture.FLASH_MODE_OFF
                    else -> ImageCapture.FLASH_MODE_AUTO
                }
                updateFlashMode()

                imageCapture?.flashMode = flashMode
            }

            // Setup for button used to switch cameras
            imgSwap.let {

                // Disable the button until the camera is set up
                it.isEnabled = false

                // Listener for button used to switch cameras. Only called if the button is enabled
                it.setOnClickListener {
                    lensFacing = if (CameraSelector.LENS_FACING_FRONT == lensFacing)
                        CameraSelector.LENS_FACING_BACK
                    else CameraSelector.LENS_FACING_FRONT

                    // Re-bind use cases to update selected camera
                    bindCameraUseCases()
                }
            }
        }


    }

    private fun takePicture() {

        // Create time stamped name and MediaStore entry.
        val name = SimpleDateFormat(FILENAME, Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, PHOTO_TYPE)
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                val appName = requireContext().resources.getString(R.string.app_name)
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/${appName}")
            }
        }

        // Create output options object which contains file + metadata
        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(
                requireContext().contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            )
            .build()

        // Get a stable reference of the modifiable image capture use case
        imageCapture?.let { imageCapture ->
            // Setup image capture listener which is triggered after photo has been taken
            imageCapture.takePicture(
                outputOptions, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
                    override fun onError(exc: ImageCaptureException) {
                        Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                    }

                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        val savedUri = output.savedUri
                        Log.d(TAG, "Photo capture succeeded: $savedUri")
                        savedUri?.let {
                            listener?.onImageVideoResult(savedUri)
                        }
                        requireActivity().runOnUiThread {
                            cameraBinding?.progressLayout?.videoCounterLayout?.hide()
                        }
//                        requireActivity().finish()
                    }
                })

            // We can only change the foreground Drawable using API level 23+ API

            // Display flash animation to indicate that photo was captured
            binding.root.apply {
                postDelayed({
                    foreground = ColorDrawable(Color.WHITE)
                    postDelayed(
                        { foreground = null }, ANIMATION_FAST_MILLIS
                    )
                }, ANIMATION_SLOW_MILLIS)
            }
        }
    }

    @SuppressLint("RestrictedApi")
    private fun stopRecording() {
        currentRecording?.let {
            videoCapture.camera?.cameraControl?.enableTorch(false)
            it.stop()
            currentRecording = null
        }
        cameraBinding?.progressLayout?.videoCounterLayout?.hide()

    }

    @SuppressLint("MissingPermission", "RestrictedApi")
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
            cameraBinding?.apply {
                progressLayout.apply {
                    videoCounterLayout.show()
                    videoCounter.text = time.counterText
                    videoPbr.apply {
                        progress = time
                        max = recordingDuration
                        invalidate()
                    }
                }
                if (time >= recordingDuration) stopRecording()
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        // Rebind the camera with the updated display metrics
        bindCameraUseCases()

        // Enable or disable switching between cameras
        updateCameraSwitchButton()
    }

    /** Initialize CameraX, and prepare to bind the camera use cases  */
    private suspend fun setUpCamera() {
        cameraProvider = ProcessCameraProvider.getInstance(requireContext()).await()

        // Select lensFacing depending on the available cameras
        lensFacing = when {
            hasBackCamera() -> CameraSelector.LENS_FACING_BACK
            hasFrontCamera() -> CameraSelector.LENS_FACING_FRONT
            else -> throw IllegalStateException("Back and front camera are unavailable")
        }

        // Enable or disable switching between cameras
        updateCameraSwitchButton()

        // Build and bind the camera use cases
        bindCameraUseCases()
    }


    /** Returns true if the device has an available back camera. False otherwise */
    private fun hasBackCamera(): Boolean =
        cameraProvider?.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) ?: false


    /** Returns true if the device has an available front camera. False otherwise */
    private fun hasFrontCamera(): Boolean =
        cameraProvider?.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) ?: false


    /** Enabled or disabled a button to switch cameras depending on the available cameras */
    private fun updateCameraSwitchButton() =
        cameraBinding?.imgSwap?.apply {
            isEnabled = try {
                hasBackCamera() && hasFrontCamera()
            } catch (exception: CameraInfoUnavailableException) {
                false
            }
        }


    private fun bindCameraUseCases(
        isVideo: Boolean = false,
        isFirstTime: Boolean = true
    ) {
        // Get screen metrics used to setup camera for full screen resolution
        val metrics = windowManager.getCurrentWindowMetrics().bounds
        Log.d(TAG, "Screen metrics: ${metrics.width()} x ${metrics.height()}")

        val screenAspectRatio = aspectRatio(metrics.width(), metrics.height())
        Log.d(TAG, "Preview aspect ratio: $screenAspectRatio")

        requireActivity().runOnUiThread {
            if (!isFirstTime) {
                val toast = Toast.makeText(
                    requireContext(), String.format(
                        requireContext().getString(R.string.switching),
                        if (isVideo && !isCaptureTypeImage)
                            requireContext().getString(R.string.video)
                        else requireContext().getString(R.string.image)
                    ), Toast.LENGTH_SHORT
                )
                toast.setGravity(Gravity.CENTER, 0, 0)
                toast.show()
            }

        }

        if (isVideo)
            isCaptureTypeImage = false

        val rotation = binding.previewView.display.rotation

        // CameraProvider
        val cameraProvider = cameraProvider
            ?: throw IllegalStateException("Camera initialization failed.")

        // CameraSelector
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
        // Attach the viewfinder's surface provider to preview use case
        val preview = Preview.Builder()
            // We request aspect ratio but no resolution
            .setTargetAspectRatio(screenAspectRatio)
            // Set initial target rotation
            .setTargetRotation(rotation)
            .build().apply {
                setSurfaceProvider(binding.previewView.surfaceProvider)
            }

        // ImageCapture
        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            // We request aspect ratio but no resolution to match preview config, but letting
            // CameraX optimize for whatever specific resolution best fits our use cases
            .setTargetAspectRatio(screenAspectRatio)
            .setFlashMode(flashMode)
            // Set initial target rotation, we will have to call this again if rotation changes
            // during the lifecycle of this use case
            .setTargetRotation(rotation)
            .build()

        // Must unbind the use-cases before rebinding them
        cameraProvider.unbindAll()

        val captureType = if (isVideo) videoCapture else imageCapture

        try {
            // A variable number of use-cases can be passed here -
            // camera provides access to CameraControl & CameraInfo
            camera = cameraProvider.bindToLifecycle(
                this, cameraSelector, captureType, preview
            )


        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
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
        override fun onDisplayChanged(displayId: Int) = view?.let { view ->
            if (displayId == this@CameraCaptureFragment.displayId) {
                Log.d(TAG, "Rotation changed: ${view.display.rotation}")
                imageCapture?.targetRotation = view.display.rotation
            }
        } ?: Unit
    }


    companion object {
        private var recordingDuration: Int = 0

        fun newInstance(duration: Int): CameraCaptureFragment {
            this.recordingDuration = duration
            return CameraCaptureFragment()
        }
    }

}

