package com.camera.cameraX.activities

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.telephony.TelephonyManager
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.viewpager2.widget.ViewPager2.ORIENTATION_HORIZONTAL
import androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback
import androidx.viewpager2.widget.ViewPager2.SCROLL_STATE_IDLE
import androidx.viewpager2.widget.ViewPager2.SCROLL_STATE_SETTLING
import com.camera.cameraX.adapter.ViewPagerAdapter
import com.camera.cameraX.callbacks.CameraActionCallback
import com.camera.cameraX.fragment.ImageFragment
import com.camera.cameraX.fragment.VideoFragment
import com.camera.cameraX.utils.MAX_REC_DURATION
import com.camera.cameraX.utils.Permissions
import com.camera.cameraX.utils.TAG
import com.camera.cameraX.utils.TelephonyServiceReceiver
import com.camera.cameraX.utils.accessListener
import com.camera.cameraX.utils.counterText
import com.camera.cameraX.utils.defaultPostDelay
import com.camera.cameraX.utils.gone
import com.camera.cameraX.utils.hideStatusBar
import com.camera.cameraX.utils.hideSystemUI
import com.camera.cameraX.utils.ifElse
import com.camera.cameraX.utils.makeViewsGone
import com.camera.cameraX.utils.setupScreen
import com.camera.cameraX.utils.show
import com.camera.cameraX.viewmodel.CameraViewModel
import com.example.cameraxintegration.R
import com.example.cameraxintegration.databinding.ActivityBaseViewPagerBinding
import com.google.android.material.tabs.TabLayoutMediator

class BaseViewPagerActivity : AppCompatActivity() {
    private lateinit var _binding: ActivityBaseViewPagerBinding
    private val binding get() = _binding

    private var permissions = mutableListOf<String>()

    private lateinit var imageFragment: ImageFragment
    private lateinit var videoFragment: VideoFragment

    private val viewModel by lazy {
        ViewModelProvider(this).get(CameraViewModel::class.java)
    }

    private var videoDuration = DEFAULT_DURATION

    private var tabPosition = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _binding = ActivityBaseViewPagerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupScreen()
        hideStatusBar()
        supportActionBar?.hide()

        for (permission in MANDATORY_PERMISSIONS) {
            permissions.add(permission)
        }

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P)
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)


        with(binding) {
            cameraUi.apply {
                imgFlash.apply {
                    setImageResource(R.drawable.ic_flash_auto)
                    setOnClickListener {
                        (surfaceViewPager.currentItem == 0).ifElse(
                            { (imageFragment as CameraActionCallback).onFlashChangeCallback() },
                            { (videoFragment as CameraActionCallback).onFlashChangeCallback() })
                    }
                }

                imgCapture.apply {
                    setOnClickListener {
                        if(accessListener?.canAccessCamera()?.first == false){
                            Toast.makeText(
                                this@BaseViewPagerActivity,
                                accessListener?.canAccessCamera()?.second?: getString(R.string.user_on_call),
                                Toast.LENGTH_SHORT
                            ).show()
                            viewModel.stopRecording(true)
                            finish()
                        } else {
                            (surfaceViewPager.currentItem == 0).ifElse(
                                { (imageFragment as CameraActionCallback).onCaptureCallback() },
                                { (videoFragment as CameraActionCallback).onCaptureCallback() })
                        }

                    }
                }

                imgSwap.apply {
                    setOnClickListener {
                        (surfaceViewPager.currentItem == 0).ifElse(
                            { (imageFragment as CameraActionCallback).onLensSwapCallback() },
                            { (videoFragment as CameraActionCallback).onLensSwapCallback() })
                    }
                }
            }
        }

        if(Permissions.isPermissionAllowed(this,Manifest.permission.READ_PHONE_STATE))
            registerTelephonyCallListener()

    }

    private fun registerTelephonyCallListener() {
        telephoneCallReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val callState = intent.getStringExtra(TelephonyServiceReceiver.CALL_STATE)
                Log.d(TAG, "handleCallState() callState -> $callState")
                when (callState) {
                    TelephonyManager.EXTRA_STATE_RINGING -> Log.d(TAG, "CALL_STATE_RINGING -> $callState")
                    TelephonyManager.EXTRA_STATE_IDLE ->
                        Log.d(TAG, "CALL_STATE_IDLE -> $callState")

                    TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                        Log.d(TAG, "CALL_STATE_OFF_HOOK -> $callState")
                        Toast.makeText(
                            this@BaseViewPagerActivity,
                            getString(R.string.user_on_call),
                            Toast.LENGTH_SHORT
                        ).show()
                        viewModel.stopRecording(true)
                    }
                }
            }
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(
            telephoneCallReceiver!!, IntentFilter(
                TelephonyServiceReceiver.ACTION_PHONE_CALL_STATE_CHANGED)
        )
    }

    private fun handleObservers() = binding.apply {
        cameraUi.apply {

            viewModel.apply {
                flashState.observe(this@BaseViewPagerActivity) {
                    val flashMode = when (it) {
                        1 -> R.drawable.ic_flash
                        2 -> R.drawable.ic_flash_off
                        else -> R.drawable.ic_flash_auto
                    }
                    imgFlash.setImageResource(flashMode)
                }

                // Progress value has been updated to max when user stops/fullFilled the video recording time
                progressValue.observe(this@BaseViewPagerActivity) { progress ->
                    progressLayout.apply {
                        videoCounterLayout.apply {
                            if (progress >= videoDuration) gone() else show()
                        }
                        videoCounter.text = progress.counterText
                        videoPbr.apply {
                            this.progress = progress
                            max = videoDuration
                            invalidate()
                            show()
                        }
                    }
                }

                isVideoRecording.observe(this@BaseViewPagerActivity) { videoRecState ->
                    tabLayout.apply {
                        this.isEnabled = false
                        if (videoRecState) show() else gone()
                    }
                    if (videoRecState) imgCapture.isEnabled = false
                    makeViewsGone(imgFlash, imgSwap)
                    imgFlash.isEnabled = videoRecState
                    imgSwap.isEnabled = videoRecState
                }

                isLensFacingBack.observe(this@BaseViewPagerActivity) { lensFacingBack ->
                    transitionPreview.show()
                    if (lensFacingBack) {
                        imgCameraType.apply {
                            setImageResource(R.drawable.ic_outdoor)
                            show()
                            defaultPostDelay { this.gone() }
                        }
                        imgFlash.show()

                    } else {
                        imgCameraType.apply {
                            setImageResource(R.drawable.ic_person)
                            show()
                            defaultPostDelay { this.gone() }
                        }
                        imgFlash.gone()
                    }
                    defaultPostDelay { transitionPreview.gone() }
                }

                previewBitmap.observe(this@BaseViewPagerActivity) { bitmap ->
                    transitionPreview.setImageBitmap(bitmap)
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT)
            Log.i("TAG", "onConfigurationChanged: portrait")
        else
            Log.i("TAG", "onConfigurationChanged: landscape")
    }


    private fun setupViewPager() {
        val adapter = ViewPagerAdapter(this)
        adapter.apply {
            addFragment(imageFragment, getString(R.string.title_camera))
            addFragment(videoFragment, getString(R.string.title_video))
        }
        with(binding) {
            tabLayout.setTabTextColors(
                resources.getColor(R.color.white, null),
                resources.getColor(R.color.yellow, null)
            )
            surfaceViewPager.apply {
                isUserInputEnabled = false
                this.adapter = adapter
                orientation = ORIENTATION_HORIZONTAL
                TabLayoutMediator(tabLayout, this) { tab, position ->
                    tab.text = adapter.getTabTitle(position)
                }.attach()
                registerOnPageChangeCallback(object : OnPageChangeCallback() {
                    override fun onPageSelected(position: Int) {
                        super.onPageSelected(position)
                        if (position == 0) {
                            cameraUi.apply {
                                progressLayout.videoCounterLayout.gone()
                            }
                        }
                    }

                    override fun onPageScrollStateChanged(state: Int) {
                        super.onPageScrollStateChanged(state)
                        if (state == SCROLL_STATE_SETTLING) {
                            if (tabPosition == 0) setCameraType(R.drawable.ic_video)
                            else setCameraType(R.drawable.ic_camera)
                        } else if (state == SCROLL_STATE_IDLE)
                            defaultPostDelay {
                                transitionPreview.gone()
                                imgCameraType.gone()
                            }
                    }

                    override fun onPageScrolled(
                        position: Int,
                        positionOffset: Float,
                        positionOffsetPixels: Int,
                    ) {
                        super.onPageScrolled(position, positionOffset, positionOffsetPixels)
                        tabPosition = position
                    }

                })
            }
        }
    }

    private fun setCameraType(id: Int) = binding.imgCameraType.apply {
        if (tabPosition == 0) imageFragment.onPause() else videoFragment.onPause()
        binding.transitionPreview.show()
        show()
        setImageResource(id)
    }

    private fun allPermissionsGranted(): Boolean {
        for (permission in MANDATORY_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission)
                != PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
        }
        return true
    }

    override fun onResume() {
        super.onResume()
        videoDuration = intent.getIntExtra(MAX_REC_DURATION, DEFAULT_DURATION)

        if (!::imageFragment.isInitialized) imageFragment = ImageFragment.newInstance()
        if (!::videoFragment.isInitialized) videoFragment = VideoFragment.newInstance(videoDuration)

        binding.transitionPreview.gone()

        if (allPermissionsGranted()) setupViewPager()
        else ActivityCompat.requestPermissions(
            this,
            permissions.toTypedArray(),
            REQUEST_CODE_PERMISSIONS
        )

        handleObservers()

        binding.container.postDelayed({
            hideSystemUI(this, binding.container)
        }, IMMERSIVE_FLAG_TIMEOUT)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                setupViewPager()
            } else {
                Toast.makeText(this, getString(R.string.permission_not_granted), Toast.LENGTH_SHORT)
                    .show()
                this.finish()
            }
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        telephoneCallReceiver?.let {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(it)
        }
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 1001
        private val MANDATORY_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE
        )
        private const val IMMERSIVE_FLAG_TIMEOUT = 500L
        private const val DEFAULT_DURATION = 5

        var telephoneCallReceiver: BroadcastReceiver? = null

        var lensFacing = CameraSelector.LENS_FACING_BACK
        var flashMode = ImageCapture.FLASH_MODE_AUTO
    }
}