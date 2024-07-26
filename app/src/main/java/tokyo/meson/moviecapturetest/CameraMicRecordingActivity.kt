package tokyo.meson.moviecapturetest

import android.Manifest
import android.content.pm.PackageManager
import android.media.*
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.WindowManager
import android.view.WindowMetrics
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
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

    companion object {
        private const val TAG = "CameraMicRecording"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO)
        private const val SAMPLE_RATE = 44100
    }

    private lateinit var viewFinder: PreviewView
    private lateinit var recordButton: Button
    private lateinit var imageAnalysis: ImageAnalysis
    private lateinit var audioRecord: AudioRecord
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var audioExecutor: ExecutorService
    private lateinit var mediaEncoder: MediaEncoder
    
    private val videoBufferSize: Int = 300    // 10秒（30FPS 想定）
    private val audioBufferSize: Int = SAMPLE_RATE * 10 // 10秒（44.1kHz を想定）
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
        
//        val outputFile = File(externalMediaDirs.first(), "${System.currentTimeMillis()}.mp4")
        val outputFile = File(externalMediaDirs.first(), "test.mp4")
        outputPath = outputFile.absolutePath
        
//        val windowSize: Size = getScreenResolution()
        val windowSize: Size = Size(640, 480)
        mediaEncoder = MediaEncoder(windowSize.width, windowSize.height, 30, SAMPLE_RATE, 1_000_000, outputPath!!)
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
    
    private fun getYuvByteArray(image: ImageProxy): ByteArray {
        val yBuffer = image.planes[0].buffer // Y
        val uBuffer = image.planes[1].buffer // U
        val vBuffer = image.planes[2].buffer // V
        
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        
        val uvBuffer = ByteArray(uSize + 1)
        uBuffer.get(uvBuffer, 0, uSize)
        for (i in 1..uSize+1 step 2) {
            uvBuffer[i] = vBuffer.get(i - 1)
        }

        val result = ByteArray(ySize + uSize + 1)
        yBuffer.get(result, 0, ySize)
        uvBuffer.copyInto(result, ySize, 0, uvBuffer.size - 1)
        
        return result
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
                
                val data = getYuvByteArray(image)
                
                // バッファがいっぱいの場合、古いデータを削除
                if (videoBuffer.size >= videoBufferSize) {
                    videoBuffer.poll()
                }
                
//                videoBuffer.offer(FrameData(data, image.imageInfo.timestamp))
                videoBuffer.offer(FrameData(data, System.nanoTime() / 1_000))

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
        var bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        bufferSize = (2048).coerceAtMost(bufferSize)

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return
        }

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

                    if (!isRecording) return@execute

                    val byteBuffer = buffer.foldIndexed(ByteArray(readSize * 2)) { i, acc, short ->
                        acc[i * 2] = (short.toInt() and 0xFF).toByte()
                        acc[i * 2 + 1] = (short.toInt() shr 8 and 0xFF).toByte()
                        acc
                    }
//                    val audioData = AudioData(byteBuffer, System.nanoTime())
                    val audioData = AudioData(byteBuffer, System.nanoTime() / 1_000)
                    if (audioBuffer.size >= audioBufferSize) {
                        audioBuffer.poll()
                    }
                    
                    audioBuffer.offer(audioData)
                }
            }
        }
    }
    
    private fun saveCurrentBuffer() {
        
        Log.d(TAG, "=========== Will save current buffer to ${outputPath!!}.")

        videoBuffer.sortedBy { it.timestamp }
        audioBuffer.sortedBy { it.timestamp }

        val videoFirstData = videoBuffer.peek()
        val audioFirstData = audioBuffer.peek()

        if (videoFirstData == null) return
        if (audioFirstData == null) return

//        if (videoFirstData.timestamp < audioFirstData.timestamp) {
//
//        }
        val delta = Math.abs(videoFirstData.timestamp - audioFirstData.timestamp)

        mediaEncoder.setDelta(delta)
        mediaEncoder.startEncoding(videoBuffer, audioBuffer)
        mediaEncoder.start()

        Log.d(TAG, "!!! Saving complete !!!")
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
}