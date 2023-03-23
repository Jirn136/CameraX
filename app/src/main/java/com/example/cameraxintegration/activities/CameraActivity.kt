package com.example.cameraxintegration.activities

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.cameraxintegration.R
import com.example.cameraxintegration.databinding.ActivityMainBinding
import com.example.cameraxintegration.fragment.CameraCaptureFragment
import com.example.cameraxintegration.utils.MAX_REC_DURATION
import com.example.cameraxintegration.utils.hideStatusBar
import com.example.cameraxintegration.utils.setupScreen

class CameraActivity : AppCompatActivity() {
    private var _binding: ActivityMainBinding? = null
    private val binding get() = _binding
    private var permissions = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding?.root)
        setupScreen()
        hideStatusBar()
        supportActionBar?.hide()
        for (permission in MANDATORY_PERMISSIONS) {
            permissions.add(permission)
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P)
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val recDuration = intent.getIntExtra(MAX_REC_DURATION, 10)
        val captureFragment = CameraCaptureFragment.newInstance(recDuration)

        if (allPermissionsGranted()) supportFragmentManager.beginTransaction()
            .add(R.id.fragment_container, captureFragment)
            .commit()
        else
            ActivityCompat.requestPermissions(
                this,
                permissions.toTypedArray(),
                REQUEST_CODE_PERMISSIONS
            )
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

    override fun onResume() {
        super.onResume()

        // Before setting full screen flags, we must wait a bit to let UI settle; otherwise, we may
        // be trying to set app to immersive mode before it's ready and the flags do not stick
        binding?.fragmentContainer?.postDelayed({
            hideSystemUI()
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
                supportFragmentManager.beginTransaction()
                    .add(R.id.fragment_container, CameraCaptureFragment()).commit()
            } else {
                Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT)
                    .show()
                this.finish()
            }
        }
    }

    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding?.fragmentContainer?.let {
            WindowInsetsControllerCompat(window, it).let { controller ->
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
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