package tokyo.meson.moviecapturetest

import android.Manifest
import android.content.pm.PackageManager
import android.content.ContentValues
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.VideoRecordEvent
import androidx.camera.video.VideoCapture
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import androidx.core.util.Consumer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraMicRecordingActivity : AppCompatActivity() {
    private lateinit var viewFinder: PreviewView
    private lateinit var recordButton: Button
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var audioExecutor: ExecutorService

    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    private lateinit var audioRecord: AudioRecord
    private var isRecording = false

    private lateinit var circularBuffer: CircularAudioVideoBuffer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera_mic_recording)

        viewFinder = findViewById(R.id.viewFinder)
        recordButton = findViewById(R.id.camera_capture_button)

        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        }
        else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        // Set up the record button listener
        recordButton.setOnClickListener {
            if (isRecording) {
                stopRecording()
            }
            else {
                startRecording()
            }
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
        audioExecutor = Executors.newSingleThreadExecutor()

        // Initialize the circular buffer
        val outputFile = File(externalMediaDirs.first(), "${System.currentTimeMillis()}.mp4")
        println("Save to $outputFile")
        circularBuffer = CircularAudioVideoBuffer(
            outputPath = outputFile.absolutePath,
            width = 1280,
            height = 720,
            sampleRate = SAMPLE_RATE,
            channelCount = CHANNEL_COUNT
        )
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(viewFinder.surfaceProvider)
                }

            imageCapture = ImageCapture.Builder().build()

            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture, videoCapture)
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun startRecording() {
        
        println("-----------------> Start Recording")
        
        isRecording = true
        recordButton.text = "Stop Recording"

        startVideoRecording()
        startAudioRecording()
    }
    
    private fun startVideoRecording() {
        
        cameraExecutor.execute {
            val videoCapture = this.videoCapture ?: return@execute

            val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
                .format(System.currentTimeMillis())
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CameraX-Video")
                }
            }
            val mediaStoreOutputOptions = MediaStoreOutputOptions
                .Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                .setContentValues(contentValues)
                .build()
            
            recording = videoCapture.output
                .prepareRecording(this, mediaStoreOutputOptions)
                .apply {
                    if (PermissionChecker.checkSelfPermission(this@CameraMicRecordingActivity,
                            Manifest.permission.RECORD_AUDIO) == PermissionChecker.PERMISSION_GRANTED)
                    {
                        withAudioEnabled()
                    }
                }
                .start(ContextCompat.getMainExecutor(this), captureListener)
        }
    }
    
    private val captureListener = Consumer<VideoRecordEvent> { recordEvent ->
        when(recordEvent) {
            is VideoRecordEvent.Start -> {
                Log.d(TAG, "Video recording started")
                
                runOnUiThread {
                    recordButton.apply {
                        text = "Stop Capture"
                        isEnabled = true
                    }
                }
            }
            is VideoRecordEvent.Status -> {
                val stats = recordEvent.recordingStats
                val bytes = stats.numBytesRecorded
                val time = stats.recordedDurationNanos / 1000000 // convert to milliseconds
                Log.i(TAG, "Recording stats: $bytes bytes, $time ms")
                
                circularBuffer.addVideoFrame(bytes.to(), time)
            }
            is VideoRecordEvent.Finalize -> {
                Log.d(TAG, "Video capture finalized")
                
                if (!recordEvent.hasError()) {
                    val message = "Video capture succeeded: ${recordEvent.outputResults.outputUri}"
                    Log.d(TAG, message)
                    
                    runOnUiThread {
                        Toast.makeText(baseContext, message, Toast.LENGTH_SHORT).show()
                    }
                }
                else {
                    recording?.close()
                    recording = null
                    Log.e(TAG, "Video recording failed: ${recordEvent.error}")
                }
                
                runOnUiThread {
                    recordButton.apply { 
                        text = "Start Capture"
                        isEnabled = true
                    }
                }
            }
        }
    }

    private fun startAudioRecording() {
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )

        audioRecord.startRecording()
        
        audioExecutor.execute {
            val buffer = ShortArray(bufferSize / 2)
            while (isRecording) {
                val readSize = audioRecord.read(buffer, 0, buffer.size)
                if (readSize > 0) {
                    if (!isRecording) return@execute
                    val byteBuffer = buffer.foldIndexed(ByteArray(readSize * 2)) { i, acc, short ->
                        acc[i * 2] = (short.toInt() and 0xFF).toByte()
                        acc[i * 2 + 1] = (short.toInt() shr 8 and 0xFF).toByte()
                        acc
                    }
                    circularBuffer.addAudioSample(byteBuffer, System.nanoTime() / 1000)
                }
            }
        }
    }

    private fun stopRecording() {
        isRecording = false
        recordButton.text = "Start Recording"

        // Stop video recording
        recording?.stop()
        recording = null

        // Stop audio recording
        audioRecord.stop()
        audioRecord.release()

        // Save the circular buffer
        circularBuffer.saveBuffer()
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        audioExecutor.shutdown()
        circularBuffer.release()
    }

    companion object {
        private const val TAG = "CameraMicRecording"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_COUNT = 2
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_STEREO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
    }
}