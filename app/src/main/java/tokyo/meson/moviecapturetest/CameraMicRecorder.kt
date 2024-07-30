package tokyo.meson.moviecapturetest

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraMicRecorder(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val sampleRate: Int,
    private val outputPath: String
) {
    
    companion object {
        private const val TAG = "MediaRecorder"
    }
    
    private lateinit var imageAnalysis: ImageAnalysis
    private lateinit var audioRecord: AudioRecord
    private lateinit var mediaEncoder: MediaEncoder
    
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val audioExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private val videoBufferSize: Int = 30 * 10    // 10秒（30FPS 想定）
    private val audioBufferSize: Int = sampleRate * 10 // 10秒（44.1kHz を想定）
    private val videoBuffer: ArrayBlockingQueue<FrameData> = ArrayBlockingQueue(videoBufferSize)
    private val audioBuffer: ArrayBlockingQueue<AudioData> = ArrayBlockingQueue(audioBufferSize)

    var isRecording: Boolean = false

    fun startRecording() {

        Log.d(TAG, "-------------> Start Recording")
        Log.d(TAG, "Clear buffers.")

        videoBuffer.clear()
        audioBuffer.clear()

        // val windowSize: Size = getScreenResolution()
        val windowSize: Size = Size(640, 480)
        mediaEncoder = MediaEncoder(windowSize.width, windowSize.height, 30, sampleRate, 1_000_000, outputPath!!)

        isRecording = true

        startCamera()
        startAudioRecording()
    }

    fun stopRecording() {

        println("------------> Stop Recording")

        isRecording = false

        imageAnalysis.clearAnalyzer()
        audioRecord.stop()
        audioRecord.release()

        saveCurrentBuffer()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            imageAnalysis = ImageAnalysis.Builder()
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { image ->

                val data = getYuvByteArray(image)

                // バッファがいっぱいの場合、古いデータを削除
                if (videoBuffer.size >= videoBufferSize) {
                    videoBuffer.poll()
                }

                videoBuffer.offer(FrameData(data, System.nanoTime() / 1_000))

                image.close()
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, imageAnalysis)
            }
            catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun startAudioRecording() {
        var bufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        bufferSize = (2048).coerceAtMost(bufferSize)

        if (ActivityCompat.checkSelfPermission(
                context,
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
            sampleRate,
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

        Log.d(TAG, "=========== Will save current buffer to ${outputPath}.")

        videoBuffer.sortedBy { it.timestamp }
        audioBuffer.sortedBy { it.timestamp }

        val videoFirstData = videoBuffer.peek()
        val audioFirstData = audioBuffer.peek()

        if (videoFirstData == null) return
        if (audioFirstData == null) return

        val delta = Math.abs(videoFirstData.timestamp - audioFirstData.timestamp)

        mediaEncoder.setDelta(delta)
        mediaEncoder.startEncoding(videoBuffer, audioBuffer)
        mediaEncoder.start()

        Log.d(TAG, "!!! Saving complete !!!")
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
    
    fun dispose() {
        cameraExecutor.shutdownNow()
        audioExecutor.shutdownNow()
    }
}