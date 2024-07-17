package tokyo.meson.moviecapturetest

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.util.Log
import android.util.Size
import java.nio.ByteBuffer

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
    private var muxerStarted = false
    
    private val videoBuffer = ArrayList<EncodedData>()
    private val audioBuffer = ArrayList<EncodedData>()
    
    fun startEncoding() {
        setupVideoEncoder()
        setupAudioEncoder()
        setupMuxer()
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

        videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        videoEncoder?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        videoEncoder?.start()
    }

    private fun setupAudioEncoder() {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 2).apply { 
            setInteger(MediaFormat.KEY_BIT_RATE, 128000)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        }

        audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        audioEncoder?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        audioEncoder?.start()
    }

    private fun setupMuxer() {
        muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }
    
    fun encodeVideoFrame(frameData: ByteArray, presentationTimeUs: Long) {
        val inputBufferIndex = videoEncoder?.dequeueInputBuffer(TIMEOUT_US) ?: return
        if (inputBufferIndex >= 0) {
            val inputBuffer = videoEncoder?.getInputBuffer(inputBufferIndex)
            inputBuffer?.clear()
            inputBuffer?.put(frameData)
            videoEncoder?.queueInputBuffer(inputBufferIndex, 0, frameData.size, presentationTimeUs, 0)
        }

        drainEncoder(videoEncoder!!, false, isVideo = true)
    }    
    
    fun encodeAudioSample(sampleData: ShortArray, presentationTimeUs: Long) {
        val inputBufferIndex = audioEncoder?.dequeueInputBuffer(TIMEOUT_US) ?: return
        if (inputBufferIndex >= 0) {
            val inputBuffer = audioEncoder?.getInputBuffer(inputBufferIndex)
            inputBuffer?.clear()
            inputBuffer?.asShortBuffer()?.put(sampleData)
            audioEncoder?.queueInputBuffer(inputBufferIndex, 0, sampleData.size * 2, presentationTimeUs, 0)
        }
        
        drainEncoder(audioEncoder!!, false, isVideo = false)
    }

    private fun drainEncoder(encoder: MediaCodec, endOfStream: Boolean, isVideo: Boolean) {
        if (endOfStream) {
            encoder.signalEndOfInputStream()
        }

        while (true) {
            val bufferInfo = MediaCodec.BufferInfo()
            val encoderStatus = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break
            }
            else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (isVideo) {
                    videoTrackIndex = muxer?.addTrack(encoder.outputFormat) ?: -1
                }
                else {
                    audioTrackIndex = muxer?.addTrack(encoder.outputFormat) ?: -1
                }
                
                if (videoTrackIndex != -1 && audioTrackIndex != -1 && !muxerStarted) {
                    muxer?.start()
                    muxerStarted = true
                    writeBufferData()
                }
            } 
            else if (encoderStatus >= 0) {
                val encodedData = encoder.getOutputBuffer(encoderStatus)
                if (encodedData != null && bufferInfo.size != 0) {
                    if (muxerStarted) {
                        if (encoder == videoEncoder) {
                            muxer?.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
                        }
                        else if (encoder == audioEncoder) {
                            muxer?.writeSampleData(audioTrackIndex, encodedData, bufferInfo)
                        }
                    }
                    else {
                        val data = ByteArray(bufferInfo.size)
                        encodedData.get(data)
                        encodedData.clear()
                        
                        if (isVideo) {
                            videoBuffer.add(EncodedData(data, MediaCodec.BufferInfo().also {
                                it.set(bufferInfo.offset, bufferInfo.size, bufferInfo.presentationTimeUs, bufferInfo.flags)
                            }))
                        }
                        else {
                            audioBuffer.add(EncodedData(data, MediaCodec.BufferInfo().also {
                                it.set(bufferInfo.offset, bufferInfo.size, bufferInfo.presentationTimeUs, bufferInfo.flags)
                            }))
                        }
                    }
                }
                encoder.releaseOutputBuffer(encoderStatus, false)
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break
                }
            }
        }
    }    
    
    private fun writeBufferData() {
        // バッファリングされたデータをタイムスタンプ順にソート
        val allData = (videoBuffer + audioBuffer).sortedBy { it.bufferInfo.presentationTimeUs }
        
        for (data in allData) {
            val trackIndex = if (data in videoBuffer) videoTrackIndex else audioTrackIndex
            val byteBuffer = ByteBuffer.wrap(data.data)
            muxer?.writeSampleData(trackIndex, byteBuffer, data.bufferInfo)
        }
        
        videoBuffer.clear()
        audioBuffer.clear()
    }
    
    fun stopEncoding() {
        drainEncoder(videoEncoder!!, true, isVideo = true)
        drainEncoder(audioEncoder!!, true, isVideo = false)
        releaseEncoders()
    }
    
    private fun releaseEncoders() {
        videoEncoder?.stop()
        videoEncoder?.release()
        audioEncoder?.stop()
        audioEncoder?.release()
        muxer?.stop()
        muxer?.release()
    }
    
    private data class EncodedData(val data: ByteArray, val bufferInfo: MediaCodec.BufferInfo)
    
    companion object {
        private const val TAG: String = "MediaEncoder"
        private const val TIMEOUT_US: Long = 10000
    }
}