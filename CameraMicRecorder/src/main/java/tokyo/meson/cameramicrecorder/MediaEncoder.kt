package tokyo.meson.cameramicrecorder

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodec.BufferInfo
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.util.Log
import java.util.concurrent.ArrayBlockingQueue

class MediaEncoder(
    private val width: Int,
    private val height: Int,
    private val frameRate: Int,
    private val sampleRate: Int,
    private val bitRate: Int,
    private val outputPath: String
) : Thread() {

    companion object {
        private const val TAG: String = "MediaEncoder"
        private const val TIMEOUT_US: Long = 100000
    }

    private var videoEncoder: MediaCodec? = null
    private var audioEncoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    
    private var videoBuffer: ArrayBlockingQueue<FrameData>? = null
    private var audioBuffer: ArrayBlockingQueue<AudioData>? = null
    
    private var previousAudioTimestamp: Long = 0
    private var deltaTimestamp: Long = 0

    fun setDelta(delta: Long) {
        deltaTimestamp = delta
    }
    
    fun startEncoding(videoBuffer: ArrayBlockingQueue<FrameData>, audioBuffer: ArrayBlockingQueue<AudioData>) {
        this.videoBuffer = videoBuffer
        this.audioBuffer = audioBuffer
        
        setupVideoEncoder()
        setupAudioEncoder()
        setupMuxer()
    }

    private fun stopEncoding() {
        videoEncoder?.let { encoder -> closeEncoder(encoder) }
        audioEncoder?.let { encoder -> closeEncoder(encoder) }
        releaseEncoders()
        
        Log.d(TAG, "Completed encoding.")
    }

    private fun setupVideoEncoder() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41)

            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.LOLLIPOP) {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar);
            }
            else {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            }
        }

        videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)?.apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
    }

    private fun setupAudioEncoder() {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
            setInteger(MediaFormat.KEY_CHANNEL_MASK, AudioFormat.CHANNEL_IN_MONO)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        }

        audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
    }

    private fun setupMuxer() {
        muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }
    
    private fun encodeVideoFrame() {
        var frameIndex: Int = 1
        val frameDurationUs: Long = 1_000_000L / frameRate // 1秒 = 1,000,000 マイクロ秒
        
        videoEncoder?.let { encoder ->
            videoBuffer?.let { buffer ->
                buffer.forEach { chunk ->
                    
                    var encoderInputBufferIndex: Int
                
                    while (true) {
                        val inputBufferIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                        if (inputBufferIndex >= 0) {
                            encoderInputBufferIndex = inputBufferIndex
                            break
                        }
                        sleep(10)
                    }

                    // フレームインデックスに基づいてタイムスタンプを計算
                    val presentationTimeUs: Long = frameIndex * frameDurationUs + deltaTimestamp
                    encoder.getInputBuffer(encoderInputBufferIndex)?.apply {
                        clear()
                        put(chunk.data)
                        encoder.queueInputBuffer(encoderInputBufferIndex, 0, chunk.data.size, presentationTimeUs, 0)
                    }
                    
                    val bufferInfo = BufferInfo()
                    var encoderOutputBufferIndex: Int
                    while (true) {
                        val outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                        if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            // NOTE: 先にセットアップしているので、ここの分岐にはこない想定
                            Log.d(TAG, "!?!?!?!? Video format changed.")
                        }
                        else if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                            sleep(10)
                            continue
                        }
                        else if (outputBufferIndex >= 0)
                        {
                            encoderOutputBufferIndex = outputBufferIndex
                            break
                        }
                        
                        sleep(10)
                    }
                    
                    val encodedData = encoder.getOutputBuffer(encoderOutputBufferIndex) ?: error { "Failed to get a buffer of video frame." }
                    muxer?.let { muxer ->
                        muxer.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
                    }
                    encoder.releaseOutputBuffer(encoderOutputBufferIndex, false)

                    frameIndex++
                }
            }
        }
    }

    private fun encodeAudioSample() {
        var frameIndex: Int = 1 // セットアップで事前に最初のフレーム分は書き込んでいるので 1 からスタート
//        val microSecUnit = 1_000_000L
//        var presentationTimeUs: Long = 0
        
        audioEncoder?.let { encoder ->
            audioBuffer?.let { buffer ->
                
                Log.d(TAG, "Audio buffer size: ${buffer.size}")
                
                buffer.forEach { chunk ->
                    Log.d(TAG, "----------------> Frame Index: $frameIndex")
                    
                    frameIndex++
                    
                    var encoderInputBufferIndex: Int

                    while (true) {
                        val inputBufferIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                        if (inputBufferIndex >= 0) {
                            encoderInputBufferIndex = inputBufferIndex
                            break
                        }
                        sleep(10)
                    }

                    // フレームインデックスに基づいてタイムスタンプを計算
//                    val sample: Long = chunk.data.size.toLong() / 2
//                    val timeIntervalMicros = microSecUnit * sample / sampleRate
//                    val frameDurationUs = (chunk.data.size.toDouble() / (2 * sampleRate) * microSecUnit).toLong()
//                    presentationTimeUs += frameDurationUs
                    val presentationTimeUs = (chunk.timestamp - previousAudioTimestamp) / 1000L
                    previousAudioTimestamp = chunk.timestamp
                    encoder.getInputBuffer(encoderInputBufferIndex)?.apply {
                        clear()
                        put(chunk.data)
                        encoder.queueInputBuffer(encoderInputBufferIndex, 0, chunk.data.size, presentationTimeUs, 0)
                    }
                    
                    val bufferInfo = BufferInfo()
                    var encoderOutputBufferIndex: Int
                    while (true) {
                        val outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                        if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            // NOTE: 先にセットアップしているので、ここの分岐にはこない想定
                            Log.d(TAG, "!?!?!?!? Audio format changed.")
                        }
                        else if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                            sleep(10)

                            if (frameIndex == buffer.size) {
                                continue
                            }

                            return@forEach
                        }
                        else if (outputBufferIndex >= 0) {
                            encoderOutputBufferIndex = outputBufferIndex
                            break
                        }
                        
                        sleep(10)
                    }
                    
                    val encodedData = encoder.getOutputBuffer(encoderOutputBufferIndex) ?: error { "Failed to get a buffer of audio frame. "}
                    muxer?.writeSampleData(audioTrackIndex, encodedData, bufferInfo)
                    encoder.releaseOutputBuffer(encoderOutputBufferIndex, false)
                }
            }
        }
    }
    
    override fun run() {
        Log.d(TAG, "Running MediaEncoder in ${Thread.currentThread().name}")

        setupFormat()

        encodeVideoFrame()
        encodeAudioSample()
        
        Log.d(TAG, "Ended encoding")
        
        stopEncoding()
    }
    
    private fun setupFormat() {
        videoEncoder?.let { encoder ->
            while (true) {
                val inputBufferIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                if (inputBufferIndex < 0)
                {
                    sleep(100)
                    continue
                }
                
                val frameData = videoBuffer?.poll() ?: return
                encoder.getInputBuffer(inputBufferIndex)?.apply { 
                    clear()
                    put(frameData.data)
                    val firstPresentationTimeUs: Long = deltaTimestamp
                    encoder.queueInputBuffer(inputBufferIndex, 0, frameData.data.size, firstPresentationTimeUs, 0)
                }
                
                break
            }

            val bufferInfo = BufferInfo()
            while (true) {
                val outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    sleep(100)
                    continue
                }

                if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // MediaMuxer へ映像トラックを追加するのはこのタイミングで行う
                    // このタイミングだと、固有のパラメータがセットされた MediaFormat が手に入る（csd-0 とか）
                    muxer?.let { muxer ->
                        val format = encoder.outputFormat
                        videoTrackIndex = muxer.addTrack(format)
                    }
                    break
                }
            }
        }

        audioEncoder?.let { encoder ->
            while (true) {
                val inputBufferIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                if (inputBufferIndex < 0)
                {
                    sleep(100)
                    continue
                }

                val frameData = audioBuffer?.poll() ?: return
                previousAudioTimestamp = frameData.timestamp
                encoder.getInputBuffer(inputBufferIndex)?.apply {
                    clear()
                    put(frameData.data)
                    val firstPresentationTimeUs: Long = 0
                    encoder.queueInputBuffer(inputBufferIndex, 0, frameData.data.size, firstPresentationTimeUs, 0)
                }

                break
            }

            val bufferInfo = BufferInfo()
            while (true) {
                val outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    sleep(100)
                    continue
                }

                if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // MediaMuxer へ映像トラックを追加するのはこのタイミングで行う
                    // このタイミングだと、固有のパラメータがセットされた MediaFormat が手に入る（csd-0 とか）
                    muxer?.let { muxer ->
                        val format = encoder.outputFormat
                        audioTrackIndex = muxer.addTrack(format)
                    }
                    break
                }
            }
        }
        
        // 動画・音声ともに addTrack 後に Muxer を開始する
        muxer?.start()

        // VideoEncoder の最初のフレームを書き込み
        videoEncoder?.let { encoder ->
            val bufferInfo = BufferInfo()
            while (true) {
                val outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    sleep(100)
                    continue
                }

                if (outputBufferIndex >= 0) {
                    val encodedData = encoder.getOutputBuffer(outputBufferIndex) ?: error { "Failed to get a buffer of video frame." }
                    muxer?.let { muxer ->
                        muxer.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
                    }
                    encoder.releaseOutputBuffer(outputBufferIndex, false)
                    break
                }
            }
        }

        // AudioEncoder の最初のフレームを書き込み
        audioEncoder?.let { encoder ->
            val bufferInfo = BufferInfo()
            while (true) {
                val outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    sleep(100)
                    continue
                }

                if (outputBufferIndex >= 0) {
                    val encodedData = encoder.getOutputBuffer(outputBufferIndex) ?: error { "Failed to get a buffer of video frame." }
                    muxer?.let { muxer ->
                        muxer.writeSampleData(audioTrackIndex, encodedData, bufferInfo)
                    }
                    encoder.releaseOutputBuffer(outputBufferIndex, false)
                    break
                }
            }
        }
    }

    private fun closeEncoder(encoder: MediaCodec) {
        try {
            val inputBufferIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
            if (inputBufferIndex >= 0) {
                encoder.queueInputBuffer(inputBufferIndex, 0, 0, 1000, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
        }
        catch (exc: Exception) {
            Log.e(TAG, "Failed to close the stream.")
        }
    }
    
    private fun releaseEncoders() {
        videoEncoder?.stop()
        videoEncoder?.release()
        audioEncoder?.stop()
        audioEncoder?.release()
        muxer?.stop()
        muxer?.release()
    }
}