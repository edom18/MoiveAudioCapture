package tokyo.meson.moviecapturetest

import android.media.MediaCodec
import android.media.MediaCodec.BufferInfo
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.util.Log
import kotlinx.coroutines.delay

class MediaEncoder(
    private val width: Int,
    private val height: Int,
    private val frameRate: Int,
    private val bitRate: Int,
    private val outputPath: String
) {
    private var videoEncoder: MediaCodec? = null
    private var audioEncoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    
    fun startEncoding() {
        setupVideoEncoder()
        setupAudioEncoder()
        setupMuxer()
        
        videoEncoder?.let { encoder ->
            videoTrackIndex = muxer?.addTrack(encoder.outputFormat) ?: -1
        }
        
        audioEncoder?.let { encoder ->
            audioTrackIndex = muxer?.addTrack(encoder.outputFormat) ?: -1
        }
        
        if (videoTrackIndex != -1 && audioTrackIndex != -1)
        {
            muxer?.start()
        }
    }

    private fun setupVideoEncoder() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
//            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
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
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 2).apply { 
            setInteger(MediaFormat.KEY_BIT_RATE, 128000)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        }

        audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)?.apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
    }

    private fun setupMuxer() {
        muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }
    
    suspend fun encodeVideoFrame(frameData: ByteArray, presentationTimeUs: Long) {
        videoEncoder?.let { encoder ->
            var encoderInputBufferIndex: Int
            
            while (true) {
                val inputBufferIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                if (inputBufferIndex >= 0) {
                    encoderInputBufferIndex = inputBufferIndex
                    break
                }
                delay(500)
            }
            
            encoder.getInputBuffer(encoderInputBufferIndex)?.apply {
                clear()
                put(frameData)
                encoder.queueInputBuffer(encoderInputBufferIndex, 0, frameData.size, presentationTimeUs, 0)
            }
            
            val bufferInfo = BufferInfo()
            var encoderOutputBufferIndex: Int
            while (true) {
                val outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (outputBufferIndex >= 0) {
                    encoderOutputBufferIndex = outputBufferIndex
                    break
                }
                delay(500)
            }
            
            val encodedData = encoder.getOutputBuffer(encoderOutputBufferIndex) ?: error { "Failed to get a buffer of video frame." }
            muxer?.let { muxer ->
                muxer.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
            }
            encoder.releaseOutputBuffer(encoderOutputBufferIndex, false)
        }
    }

    suspend fun encodeAudioSample(sampleData: ShortArray, presentationTimeUs: Long) {
        audioEncoder?.let { encoder ->
            var encoderInputBufferIndex: Int
            
            while (true) {
                val inputBufferIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                if (inputBufferIndex >= 0) {
                    encoderInputBufferIndex = inputBufferIndex
                    break
                }
                delay(500)
            }
            
            encoder.getInputBuffer(encoderInputBufferIndex)?.apply {
                clear()
                asShortBuffer()?.put(sampleData)
                encoder.queueInputBuffer(encoderInputBufferIndex, 0, sampleData.size * 2, presentationTimeUs, 0)
            }
            
            val bufferInfo = BufferInfo()
            var encoderOutputBufferIndex: Int
            while (true) {
                val outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (outputBufferIndex >= 0) {
                    encoderOutputBufferIndex = outputBufferIndex
                    break
                }
                delay(500)
            }
            
            val encodedData = encoder.getOutputBuffer(encoderOutputBufferIndex) ?: error { "Failed to get a buffer of audio frame. "}
            muxer?.let { muxer ->
                muxer.writeSampleData(audioTrackIndex, encodedData, bufferInfo)
            }
            encoder.releaseOutputBuffer(encoderOutputBufferIndex, false)
        }
    }

//    private fun drainEncoder(encoder: MediaCodec, endOfStream: Boolean, isVideo: Boolean) {
//        while (true) {
//            val bufferInfo = MediaCodec.BufferInfo()
//            val encoderStatus = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
//            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
//                if (!endOfStream) break
//            }
//            else if (encoderStatus >= 0) {
//                val encodedData = encoder.getOutputBuffer(encoderStatus)
//                if (encodedData != null && bufferInfo.size != 0) {
//                    if (encoder == videoEncoder) {
//                        muxer?.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
//                    }
//                    else if (encoder == audioEncoder) {
//                        muxer?.writeSampleData(audioTrackIndex, encodedData, bufferInfo)
//                    }
//                }
//                
//                encoder.releaseOutputBuffer(encoderStatus, false)
//                
//                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
//                    break
//                }
//            }
//        }
//        
//        if (endOfStream) {
//            encoder.signalEndOfInputStream()
//        }
//    }    
    
    fun stopEncoding() {
        videoEncoder?.let { encoder -> closeEncoder(encoder) }
        audioEncoder?.let { encoder -> closeEncoder(encoder) }
        releaseEncoders()
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
    
    companion object {
        private const val TAG: String = "MediaEncoder"
        private const val TIMEOUT_US: Long = 10000
    }
}