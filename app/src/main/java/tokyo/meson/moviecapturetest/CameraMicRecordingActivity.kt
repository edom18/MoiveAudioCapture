package tokyo.meson.moviecapturetest

import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.util.Size
import android.view.Window
import android.view.WindowManager
import android.view.WindowMetrics
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraMicRecordingActivity : AppCompatActivity() {
    private lateinit var viewFinder: PreviewView
    private lateinit var recordButton: Button
    private lateinit var imageAnalysis: ImageAnalysis
    private lateinit var audioRecord: AudioRecord
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var audioExecutor: ExecutorService
    private lateinit var mediaEncoder: MediaEncoder
    
    private val videoBufferSize: Int = 300    // 10秒（30FPS 想定）
    private val audioBufferSize: Int = 441000 // 10秒（44.1kHz を想定）
    private val videoBuffer: ArrayBlockingQueue<FrameData> = ArrayBlockingQueue(videoBufferSize)
    private val audioBuffer: ArrayBlockingQueue<AudioData> = ArrayBlockingQueue(audioBufferSize)
    
    private var isRecording = false
    private var outputPath: String? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContentView(R.layout.activity_camera_mic_recording)
        
        viewFinder = findViewById(R.id.viewFinder)
        
        recordButton = findViewById(R.id.recordButton)
        recordButton.setOnClickListener {
            if (isRecording) {
                stopRecording()
            }
            else {
                startRecording()
            }
        }
        
        if (allPermissionsGranted())
        {
//            startCamera()
        }
        else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
        
        cameraExecutor = Executors.newSingleThreadExecutor()
        audioExecutor = Executors.newSingleThreadExecutor()
        
        val outputFile = File(externalMediaDirs.first(), "${System.currentTimeMillis()}.mp4")
        outputPath = outputFile.absolutePath
        
//        val windowSize: Size = getScreenResolution()
        val windowSize: Size = Size(1920, 1080)
        mediaEncoder = MediaEncoder(windowSize.width, windowSize.height, 30, 2_000_000, outputPath!!)
        
        // ---------
        
        val numCodecs = MediaCodecList.getCodecCount()
        for (i in 0 until numCodecs) {
            val codecInfo = MediaCodecList.getCodecInfoAt(i)
            if (!codecInfo.isEncoder) continue
            
            val types = codecInfo.supportedTypes
            Log.d(TAG, codecInfo.name)
            for (j in types.indices) {
                Log.d(TAG, types[j])
            }
        }
    }
    
    private fun getScreenResolution(): Size {
        val metrics: WindowMetrics = getSystemService(WindowManager::class.java).currentWindowMetrics
        return Size(metrics.bounds.width(), metrics.bounds.height())
    }
    
    private fun startRecording() {
        
        println("-------------> Start Recording")
        
        isRecording = true
        recordButton.text = "Stop Recording"
        
        startCamera()
        startAudioRecording()
    }

    private fun stopRecording() {
        
        println("------------> Stop Recording")
        
        isRecording = false
        recordButton.text = "Start Recording"
        
        imageAnalysis.clearAnalyzer()
        audioRecord.stop()
        audioRecord.release()
        
        saveCurrentBuffer()
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
            
            imageAnalysis = ImageAnalysis.Builder()
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
            
            imageAnalysis.setAnalyzer(cameraExecutor) { image ->
                val buffer = image.planes[0].buffer
                val data = ByteArray(buffer.remaining())
                buffer.get(data)
                
                // バッファがいっぱいの場合、古いデータを削除
                if (videoBuffer.size >= videoBufferSize) {
                    videoBuffer.poll()
                }
                
                videoBuffer.offer(FrameData(data, image.imageInfo.timestamp))
                
                image.close()
            }
            
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
            }
            catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startAudioRecording() {
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        
        audioRecord.startRecording()
        
        audioExecutor.execute {
            val buffer = ShortArray(bufferSize / 2)
            while (isRecording) {
                val readSize = audioRecord.read(buffer, 0, buffer.size)
                if (readSize > 0) {
                    val audioData = AudioData(buffer.copyOf(), System.nanoTime())
                    if (audioBuffer.size >= audioBufferSize) {
                        audioBuffer.poll()
                    }
                    
                    audioBuffer.offer(audioData)
                }
            }
        }
    }
    
    private fun saveCurrentBuffer() {
        
        println("=========== Will save current buffer.")
        
        mediaEncoder.startEncoding()
        
        // ビデオフレームと音声サンプルを交互にエンコード
        val videoIterator = videoBuffer.iterator()
        val audioIterator = audioBuffer.iterator()
        
        while (videoIterator.hasNext() || audioIterator.hasNext()) {
            if (videoIterator.hasNext()) {
                val frameData = videoIterator.next()
                mediaEncoder.encodeVideoFrame(frameData.data, frameData.timestamp)
            }
            if (audioIterator.hasNext()) {
                val audioData = audioIterator.next()
                mediaEncoder.encodeAudioSample(audioData.data, audioData.timestamp)
            }
        }
        
        mediaEncoder.stopEncoding()
        
        Log.d(TAG, "Saving complete: ${outputPath!!}")
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
            if (allPermissionsGranted()) {
                startCamera()
            }
            else {
                Log.d(TAG, "Permissions not granted by the user.")
                finish()
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdownNow()
        audioExecutor.shutdownNow()
    }
    
    data class FrameData(val data: ByteArray, val timestamp: Long)
    data class AudioData(val data: ShortArray, val timestamp: Long)

    companion object {
        private const val TAG = "CameraMicRecording"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO)
        private const val SAMPLE_RATE = 44100
    }
}