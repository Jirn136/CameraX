package com.example.cameraxintegration.activities

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.viewpager2.widget.ViewPager2
import com.example.cameraxintegration.R
import com.example.cameraxintegration.adapter.ViewPagerAdapter
import com.example.cameraxintegration.callbacks.CameraActionCallback
import com.example.cameraxintegration.databinding.ActivityBaseViewPagerBinding
import com.example.cameraxintegration.fragment.CameraFragment
import com.example.cameraxintegration.fragment.VideoFragment
import com.example.cameraxintegration.utils.hideStatusBar
import com.example.cameraxintegration.utils.hideSystemUI
import com.example.cameraxintegration.utils.ifElse
import com.example.cameraxintegration.utils.setupScreen
import com.example.cameraxintegration.viewmodel.ChangeViewModel
import com.google.android.material.tabs.TabLayoutMediator

class BaseViewPagerActivity : AppCompatActivity() {
    private lateinit var _binding: ActivityBaseViewPagerBinding
    private val binding get() = _binding

    private var permissions = mutableListOf<String>()

    private val cameraFragment: CameraFragment by lazy {
        CameraFragment.newInstance()
    }
    private val videoFragment: VideoFragment by lazy {
        VideoFragment.newInstance()
    }

    private val viewModel by lazy {
        ViewModelProvider(this).get(ChangeViewModel::class.java)
    }

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


        with(binding) {
            cameraUi.apply {
                viewModel.flashState.observe(this@BaseViewPagerActivity) {
                    val flashMode = when (it) {
                        1 -> R.drawable.ic_flash
                        2 -> R.drawable.ic_flash_off
                        else -> R.drawable.ic_flash_auto
                    }
                    imgFlash.setImageResource(flashMode)
                }

                imgFlash.setOnClickListener {
                    (surfaceViewPager.currentItem == 0).ifElse(
                        { (cameraFragment as CameraActionCallback).onFlashChangeCallback() },
                        { (videoFragment as CameraActionCallback).onFlashChangeCallback() })
                }

                imgCapture.apply {
                    setOnClickListener {
                        (surfaceViewPager.currentItem == 0).ifElse(
                            { (cameraFragment as CameraActionCallback).onCaptureCallback() },
                            { (videoFragment as CameraActionCallback).onCaptureCallback() })
                    }
                }

                imgSwap.setOnClickListener {
                    (surfaceViewPager.currentItem == 0).ifElse(
                        { (cameraFragment as CameraActionCallback).onLensSwapCallback() },
                        { (videoFragment as CameraActionCallback).onLensSwapCallback() })
                }
            }
        }
    }


    private fun setupViewPager() {
        val adapter = ViewPagerAdapter(this)
        adapter.apply {
            addFragment(cameraFragment, getString(R.string.title_camera))
            addFragment(videoFragment, getString(R.string.title_video))
        }
        with(binding) {
            surfaceViewPager.adapter = adapter
            surfaceViewPager.orientation = ViewPager2.ORIENTATION_HORIZONTAL
            TabLayoutMediator(tabLayout, surfaceViewPager) { tab, position ->
                tab.text = adapter.getTabTitle(position)
            }.attach()
        }
    }

    private fun allPermissionsGranted(): Boolean {
        for (permission in MANDATORY_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    permission
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
        }
        return true
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 1001
        private val MANDATORY_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    }
}