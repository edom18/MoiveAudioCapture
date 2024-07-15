package tokyo.meson.moviecapturetest

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CircularAudioVideoBuffer(outputPath: String, width: Int, height: Int, sampleRate: Int, channelCount: Int) {
    private val videoBufferSize: Int = 300    // 10秒（30FPS 想定）
    private val audioBufferSize: Int = 441000 // 10秒（44.1kHz を想定）
    private val videoBuffer: ArrayBlockingQueue<MediaChunk> = ArrayBlockingQueue(videoBufferSize)
    private val audioBuffer: ArrayBlockingQueue<MediaChunk> = ArrayBlockingQueue(audioBufferSize)
    private val videoEncoder: MediaCodec
    private val audioEncoder: MediaCodec
    private val muxer: MediaMuxer
    private var videoTrackIndex: Int = -1
    private var audioTrackIndex: Int = -1
    private val scheduler = Executors.newScheduledThreadPool(1)
    
    init {
        // ビデオエンコーダーの設定
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 2000000) // ビットレートの設定
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)    // フレームレートの設定
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)     // キーフレーム間隔の設定
        }
        
        videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply { 
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
        
        // オーディオエンコーダの設定
        val audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount).apply { 
            setInteger(MediaFormat.KEY_BIT_RATE, 128000)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        }
        
        audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
        
        muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        
        // 定期的なバッファクリーナップのスケジューリング
        scheduler.scheduleAtFixedRate(::cleanupBuffer, 1, 1, TimeUnit.SECONDS)
    }

    /// presentationTimeUs は、フレームの位置を示している
    fun addVideoFrame(data: ByteArray, presentationTimeUs: Long) {
        addMediaData(videoEncoder, videoBuffer, data, presentationTimeUs, videoBufferSize)
    }

    /// presentationTimeUs は、フレームの位置を示している
    fun addAudioSample(data: ByteArray, presentationTimeUs: Long) {
        addMediaData(audioEncoder, audioBuffer, data, presentationTimeUs, audioBufferSize)
    }
    
    private fun addMediaData(encoder: MediaCodec, buffer: ArrayBlockingQueue<MediaChunk>, data: ByteArray, presentationTimeUs: Long, maxSize: Int) {
        // フレームをエンコード
        // dequeueInputBuffer は、エンコーダにデータを送るためのバッファ確保依頼を意味する
        // また、引数に渡している数値はタイムアウト時間（マイクロ秒単位）を指定し、-1 の場合は無期限に待つ
        val inputBufferIndex = encoder.dequeueInputBuffer(-1)
        
        // 返された inputBufferIndex は 0 以上の値で利用可能。
        // なお、-1 はタイムアウト、-2 は即座に利用可能な入力バッファがない場合
        if (inputBufferIndex >= 0) {
            encoder.getInputBuffer(inputBufferIndex)?.apply { 
                clear()
                put(data)
                encoder.queueInputBuffer(inputBufferIndex, 0, data.size, presentationTimeUs, 0)
            }
        }
        
        // エンコードされたデータを取得するためのコンテナ
        val bufferInfo = MediaCodec.BufferInfo()
        
        // dequeueOutputBuffer は、エンコード済みのデータを取得する。
        // ※ 上記の dequeueInputBuffer とは異なるので注意。
        var outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)
        while (outputBufferIndex >= 0) {
            encoder.getOutputBuffer(outputBufferIndex)?.let { outputBuffer ->
                val encodedData = ByteArray(bufferInfo.size)
                outputBuffer.get(encodedData)
                
                val chunk = MediaChunk(encodedData, bufferInfo)
                
                if (buffer.size >= maxSize) {
                    buffer.poll() // 最も古いチャンクを削除
                }
                buffer.offer(chunk)
                
                encoder.releaseOutputBuffer(outputBufferIndex, false)
            }
            outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)
        }
    }
    
    private fun cleanupBuffer() {
        val currentTime = System.currentTimeMillis()
        cleanupSpecificBuffer(videoBuffer, currentTime)
        cleanupSpecificBuffer(audioBuffer, currentTime)
    }
    
    private fun cleanupSpecificBuffer(buffer: ArrayBlockingQueue<MediaChunk>, currentTime: Long) {
        val threshold = currentTime - 10000
        while (buffer.isNotEmpty() && buffer.peek().timestamp < threshold) {
            buffer.poll()
        }
    }
    
    @Throws(IOException::class)
    fun saveBuffer() {
        videoTrackIndex = muxer.addTrack(videoEncoder.outputFormat)
        audioTrackIndex = muxer.addTrack(audioEncoder.outputFormat)
        muxer.start()
        
        val combineBuffer = mutableListOf<MediaChunk>()
        combineBuffer.addAll(videoBuffer)
        combineBuffer.addAll(audioBuffer)
        combineBuffer.sortBy { it.info.presentationTimeUs }

        combineBuffer.forEach { chunk ->
            val trackIndex = if (chunk in videoBuffer) videoTrackIndex else audioTrackIndex
            muxer.writeSampleData(trackIndex, ByteBuffer.wrap(chunk.data), chunk.info)
        }
        
        muxer.stop()
        muxer.release()
    }
    
    fun release() {
        scheduler.shutdown()
        videoEncoder.release()
        audioEncoder.release()
        muxer.release()
    }
    
    private data class MediaChunk(
        val data: ByteArray,
        val info: MediaCodec.BufferInfo,
        val timestamp: Long = System.currentTimeMillis(),
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as MediaChunk

            if (!data.contentEquals(other.data)) return false
            if (info != other.info) return false
            if (timestamp != other.timestamp) return false

            return true
        }

        override fun hashCode(): Int {
            var result = data.contentHashCode()
            result = 31 * result + info.hashCode()
            result = 31 * result + timestamp.hashCode()
            return result
        }
    }
}