package tokyo.meson.moviecapturetest

import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.WindowManager
import android.view.WindowMetrics
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File

class CameraMicRecordingActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CameraMicRecording"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO)
        private const val SAMPLE_RATE = 44100
    }

    private lateinit var viewFinder: PreviewView
    private lateinit var recordButton: Button
    private lateinit var cameraMicRecorder: CameraMicRecorder
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContentView(R.layout.activity_camera_mic_recording)
        
        viewFinder = findViewById(R.id.viewFinder)
        
        recordButton = findViewById(R.id.recordButton)
        recordButton.setOnClickListener {
            if (cameraMicRecorder.isRecording) {
                stopRecording()
            }
            else {
                startRecording()
            }
        }
        
        if (allPermissionsGranted())
        {
            val outputFile = File(externalMediaDirs.first(), "test.mp4")
            cameraMicRecorder = CameraMicRecorder(this, this, SAMPLE_RATE, outputFile.absolutePath)
        }
        else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
    }

    private fun startRecording() {
        recordButton.text = "Stop Recording"

        cameraMicRecorder.startRecording()
    }

    private fun stopRecording() {
        recordButton.text = "Start Recording"
        
        cameraMicRecorder.stopRecording()
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (!allPermissionsGranted()) {
                Log.d(TAG, "Permissions not granted by the user.")
                finish()
            }
        }
    }

    private fun getScreenResolution(): Size {
        val metrics: WindowMetrics = getSystemService(WindowManager::class.java).currentWindowMetrics
        return Size(metrics.bounds.width(), metrics.bounds.height())
    }

    
    override fun onDestroy() {
        super.onDestroy()
        
        cameraMicRecorder.dispose()
    }
}