package com.airreceiver.server

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import com.airreceiver.protocol.AirPlayConstants
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.ByteBuf
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.LengthFieldBasedFrameDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Server for AirPlay screen mirroring
 * Receives H.264 encoded video data and decodes it for display
 */
class MirroringServer(
    private val eventListener: MirroringEventListener
) {
    private var bossGroup: NioEventLoopGroup? = null
    private var workerGroup: NioEventLoopGroup? = null
    private var channel: Channel? = null

    private var videoDecoder: VideoDecoder? = null
    private var outputSurface: Surface? = null

    interface MirroringEventListener {
        fun onMirroringStarted(width: Int, height: Int)
        fun onMirroringStopped()
        fun onMirroringError(error: String)
    }

    /**
     * Set the output surface for video rendering
     */
    fun setOutputSurface(surface: Surface?) {
        outputSurface = surface
        videoDecoder?.setOutputSurface(surface)
    }

    /**
     * Start the mirroring server
     */
    suspend fun start(port: Int = AirPlayConstants.AIRPLAY_MIRROR_PORT): Boolean = withContext(Dispatchers.IO) {
        try {
            Timber.d("Starting mirroring server on port $port...")

            bossGroup = NioEventLoopGroup(1)
            workerGroup = NioEventLoopGroup()

            val bootstrap = ServerBootstrap()
            bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel::class.java)
                .childHandler(object : ChannelInitializer<SocketChannel>() {
                    override fun initChannel(ch: SocketChannel) {
                        ch.pipeline().apply {
                            // Frame decoder: 4-byte length prefix
                            addLast("frameDecoder", LengthFieldBasedFrameDecoder(
                                10 * 1024 * 1024, // Max 10MB frame
                                0, 4, 0, 4
                            ))
                            addLast("mirroringHandler", MirroringHandler(this@MirroringServer))
                        }
                    }
                })
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.TCP_NODELAY, true)

            val channelFuture = bootstrap.bind(port).sync()
            channel = channelFuture.channel()

            Timber.i("Mirroring server started on port $port")
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to start mirroring server")
            false
        }
    }

    /**
     * Stop the mirroring server
     */
    suspend fun stop() = withContext(Dispatchers.IO) {
        try {
            Timber.d("Stopping mirroring server...")

            videoDecoder?.stop()
            videoDecoder = null

            channel?.close()?.sync()
            workerGroup?.shutdownGracefully()
            bossGroup?.shutdownGracefully()

            channel = null
            workerGroup = null
            bossGroup = null

            Timber.i("Mirroring server stopped")
        } catch (e: Exception) {
            Timber.e(e, "Error stopping mirroring server")
        }
    }

    /**
     * Initialize video decoder with given parameters
     */
    fun initializeDecoder(width: Int, height: Int, sps: ByteArray?, pps: ByteArray?) {
        try {
            videoDecoder?.stop()
            videoDecoder = VideoDecoder(width, height, sps, pps).also { decoder ->
                outputSurface?.let { decoder.setOutputSurface(it) }
                decoder.start()
            }
            eventListener.onMirroringStarted(width, height)
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize video decoder")
            eventListener.onMirroringError("Failed to initialize decoder: ${e.message}")
        }
    }

    /**
     * Feed video data to decoder
     */
    fun feedVideoData(data: ByteArray, timestamp: Long) {
        videoDecoder?.queueInputData(data, timestamp)
    }

    fun isRunning(): Boolean = channel?.isActive == true

    /**
     * Handler for mirroring data
     */
    private class MirroringHandler(
        private val server: MirroringServer
    ) : SimpleChannelInboundHandler<ByteBuf>() {

        private var initialized = false
        private var sps: ByteArray? = null
        private var pps: ByteArray? = null
        private var width = 1920
        private var height = 1080

        override fun channelActive(ctx: ChannelHandlerContext) {
            Timber.d("Mirroring client connected: ${ctx.channel().remoteAddress()}")
            super.channelActive(ctx)
        }

        override fun channelInactive(ctx: ChannelHandlerContext) {
            Timber.d("Mirroring client disconnected")
            server.eventListener.onMirroringStopped()
            super.channelInactive(ctx)
        }

        override fun channelRead0(ctx: ChannelHandlerContext, msg: ByteBuf) {
            val data = ByteArray(msg.readableBytes())
            msg.readBytes(data)

            if (data.size < 4) return

            // Parse the mirroring packet
            // First packet contains stream info, subsequent packets contain H.264 NAL units
            if (!initialized) {
                if (parseStreamInfo(data)) {
                    initialized = true
                    server.initializeDecoder(width, height, sps, pps)
                }
            } else {
                // H.264 NAL unit
                val timestamp = System.currentTimeMillis() * 1000 // microseconds
                server.feedVideoData(data, timestamp)
            }
        }

        /**
         * Parse stream info packet (contains codec config)
         */
        private fun parseStreamInfo(data: ByteArray): Boolean {
            try {
                // Look for SPS/PPS NAL units in the data
                var i = 0
                while (i < data.size - 4) {
                    // Check for start code (0x00 0x00 0x00 0x01 or 0x00 0x00 0x01)
                    if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                        val startCodeLen = if (data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                            4
                        } else if (data[i + 2] == 1.toByte()) {
                            3
                        } else {
                            i++
                            continue
                        }

                        val nalStart = i + startCodeLen
                        if (nalStart >= data.size) break

                        val nalType = data[nalStart].toInt() and 0x1F

                        // Find next start code to determine NAL unit length
                        var nalEnd = data.size
                        for (j in nalStart + 1 until data.size - 3) {
                            if (data[j] == 0.toByte() && data[j + 1] == 0.toByte() &&
                                (data[j + 2] == 1.toByte() || (data[j + 2] == 0.toByte() && data[j + 3] == 1.toByte()))) {
                                nalEnd = j
                                break
                            }
                        }

                        val nalUnit = data.copyOfRange(nalStart, nalEnd)

                        when (nalType) {
                            7 -> { // SPS
                                sps = nalUnit
                                parseSps(nalUnit)
                                Timber.d("Found SPS: ${sps?.size} bytes, resolution: ${width}x$height")
                            }
                            8 -> { // PPS
                                pps = nalUnit
                                Timber.d("Found PPS: ${pps?.size} bytes")
                            }
                        }

                        i = nalEnd
                    } else {
                        i++
                    }
                }

                return sps != null && pps != null
            } catch (e: Exception) {
                Timber.e(e, "Error parsing stream info")
                return false
            }
        }

        /**
         * Parse SPS to extract resolution
         */
        private fun parseSps(sps: ByteArray) {
            try {
                // Simple SPS parsing - extract width/height
                // This is a simplified version; full parsing requires Exp-Golomb decoding
                // For now, use default resolution
                width = 1920
                height = 1080
            } catch (e: Exception) {
                Timber.e(e, "Error parsing SPS")
            }
        }

        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            Timber.e(cause, "Mirroring handler error")
            server.eventListener.onMirroringError(cause.message ?: "Unknown error")
            ctx.close()
        }
    }
}

/**
 * H.264 Video Decoder using MediaCodec
 */
class VideoDecoder(
    private val width: Int,
    private val height: Int,
    private val sps: ByteArray?,
    private val pps: ByteArray?
) {
    private var decoder: MediaCodec? = null
    private var outputSurface: Surface? = null
    private val inputQueue = LinkedBlockingQueue<VideoFrame>(100)
    private val running = AtomicBoolean(false)
    private var decoderThread: Thread? = null

    data class VideoFrame(val data: ByteArray, val timestamp: Long)

    fun setOutputSurface(surface: Surface?) {
        outputSurface = surface
    }

    fun start() {
        if (running.get()) return

        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)

            // Add codec-specific data (SPS/PPS)
            sps?.let {
                val spsBuffer = ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1) + it)
                format.setByteBuffer("csd-0", spsBuffer)
            }
            pps?.let {
                val ppsBuffer = ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1) + it)
                format.setByteBuffer("csd-1", ppsBuffer)
            }

            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, width * height)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)

            decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            decoder?.configure(format, outputSurface, null, 0)
            decoder?.start()

            running.set(true)

            // Start decoder thread
            decoderThread = Thread {
                runDecoderLoop()
            }.also { it.start() }

            Timber.d("Video decoder started: ${width}x$height")
        } catch (e: Exception) {
            Timber.e(e, "Failed to start video decoder")
            throw e
        }
    }

    fun stop() {
        running.set(false)
        decoderThread?.interrupt()
        decoderThread?.join(1000)
        decoderThread = null

        try {
            decoder?.stop()
            decoder?.release()
        } catch (e: Exception) {
            Timber.e(e, "Error stopping decoder")
        }
        decoder = null
        inputQueue.clear()

        Timber.d("Video decoder stopped")
    }

    fun queueInputData(data: ByteArray, timestamp: Long) {
        if (!running.get()) return

        // Drop frames if queue is full (to prevent memory issues)
        if (inputQueue.size >= 100) {
            inputQueue.poll()
        }
        inputQueue.offer(VideoFrame(data, timestamp))
    }

    private fun runDecoderLoop() {
        val bufferInfo = MediaCodec.BufferInfo()

        while (running.get()) {
            try {
                // Get input buffer and queue data
                val inputIndex = decoder?.dequeueInputBuffer(10000) ?: -1
                if (inputIndex >= 0) {
                    val frame = inputQueue.poll()
                    if (frame != null) {
                        val inputBuffer = decoder?.getInputBuffer(inputIndex)
                        inputBuffer?.clear()
                        inputBuffer?.put(frame.data)
                        decoder?.queueInputBuffer(inputIndex, 0, frame.data.size, frame.timestamp, 0)
                    } else {
                        // No data available, release buffer
                        decoder?.queueInputBuffer(inputIndex, 0, 0, 0, 0)
                    }
                }

                // Get output buffer and render
                val outputIndex = decoder?.dequeueOutputBuffer(bufferInfo, 10000) ?: -1
                when {
                    outputIndex >= 0 -> {
                        // Render the frame to surface
                        decoder?.releaseOutputBuffer(outputIndex, true)
                    }
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = decoder?.outputFormat
                        Timber.d("Output format changed: $newFormat")
                    }
                    outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // No output available yet
                    }
                }
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                Timber.e(e, "Decoder loop error")
            }
        }
    }
}
