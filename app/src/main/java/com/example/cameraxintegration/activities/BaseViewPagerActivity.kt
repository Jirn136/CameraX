package com.example.cameraxintegration.activities

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.viewpager2.widget.ViewPager2.*
import com.example.cameraxintegration.R
import com.example.cameraxintegration.adapter.ViewPagerAdapter
import com.example.cameraxintegration.callbacks.CameraActionCallback
import com.example.cameraxintegration.databinding.ActivityBaseViewPagerBinding
import com.example.cameraxintegration.fragment.ImageFragment
import com.example.cameraxintegration.fragment.VideoFragment
import com.example.cameraxintegration.utils.*
import com.example.cameraxintegration.viewmodel.CameraViewModel
import com.google.android.material.tabs.TabLayoutMediator

class BaseViewPagerActivity : AppCompatActivity() {
    private lateinit var _binding: ActivityBaseViewPagerBinding
    private val binding get() = _binding

    private var permissions = mutableListOf<String>()

    private val imageFragment: ImageFragment by lazy {
        ImageFragment.newInstance()
    }
    private val videoFragment: VideoFragment by lazy {
        VideoFragment.newInstance(videoDuration)
    }

    private val viewModel by lazy {
        ViewModelProvider(this).get(CameraViewModel::class.java)
    }

    private var videoDuration = 0

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

        if (allPermissionsGranted()) setupViewPager()
        else ActivityCompat.requestPermissions(
            this,
            permissions.toTypedArray(),
            REQUEST_CODE_PERMISSIONS
        )

        videoDuration = intent.getIntExtra(MAX_REC_DURATION, 5)

        handleObservers()

        with(binding) {
            cameraUi.apply {
                imgFlash.apply {
                    setOnClickListener {
                        (surfaceViewPager.currentItem == 0).ifElse(
                            { (imageFragment as CameraActionCallback).onFlashChangeCallback() },
                            { (videoFragment as CameraActionCallback).onFlashChangeCallback() })
                    }
                }

                imgCapture.apply {
                    setOnClickListener {
                        (surfaceViewPager.currentItem == 0).ifElse(
                            { (imageFragment as CameraActionCallback).onCaptureCallback() },
                            { (videoFragment as CameraActionCallback).onCaptureCallback() })
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
                        }
                    }
                }

                isVideoRecording.observe(this@BaseViewPagerActivity) { videoRecState ->
                    tabLayout.apply {
                        if (videoRecState) show() else gone()
                    }
                    imgFlash.isEnabled = videoRecState
                    imgSwap.isEnabled = videoRecState
                }

                isLensFacingBack.observe(this@BaseViewPagerActivity) { lensFacingBack ->
                    transitionPreview.show()
                    imgFlash.apply {
                        if (lensFacingBack) {
                            imgCameraType.apply {
                                setImageResource(R.drawable.ic_outdoor)
                                show()
                                defaultPostDelay { this.gone() }
                            }
                            show()

                        } else {
                            imgCameraType.apply {
                                setImageResource(R.drawable.ic_person)
                                show()
                                defaultPostDelay { this.gone() }
                            }
                            gone()
                        }
                    }
                    defaultPostDelay { transitionPreview.gone() }
                }

                previewBitmap.observe(this@BaseViewPagerActivity) { bitmap ->
                    Log.i("kanaku", "handleObservers: $bitmap")
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
                Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT)
                    .show()
                this.finish()
            }
        }
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 1001
        private val MANDATORY_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        private const val IMMERSIVE_FLAG_TIMEOUT = 500L
    }
}