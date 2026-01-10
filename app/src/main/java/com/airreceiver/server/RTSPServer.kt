package com.airreceiver.server

import com.airreceiver.protocol.AirPlayConstants
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.LineBasedFrameDecoder
import io.netty.handler.codec.string.StringDecoder
import io.netty.handler.codec.string.StringEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * RTSP Server for RAOP (Remote Audio Output Protocol)
 * Handles AirPlay audio streaming
 */
class RTSPServer(
    private val eventListener: RTSPEventListener
) {
    private var bossGroup: NioEventLoopGroup? = null
    private var workerGroup: NioEventLoopGroup? = null
    private var channel: Channel? = null
    private val sessions = ConcurrentHashMap<String, AudioSession>()

    // UDP ports for audio/timing/control
    private var dataPort: Int = AirPlayConstants.DATA_PORT
    private var controlPort: Int = AirPlayConstants.CONTROL_PORT
    private var timingPort: Int = AirPlayConstants.TIMING_PORT

    interface RTSPEventListener {
        fun onAudioSessionStarted(session: AudioSession)
        fun onAudioSessionStopped(sessionId: String)
        fun onAudioData(sessionId: String, data: ByteArray, timestamp: Long)
        fun onAudioMetadata(title: String?, artist: String?, album: String?, artwork: ByteArray?)
    }

    data class AudioSession(
        val sessionId: String,
        val clientAddress: InetSocketAddress,
        val codec: AudioCodec,
        val sampleRate: Int = 44100,
        val channels: Int = 2,
        val sampleSize: Int = 16
    )

    enum class AudioCodec {
        PCM, ALAC, AAC, AAC_ELD, OPUS
    }

    suspend fun start(port: Int = AirPlayConstants.RAOP_PORT): Boolean = withContext(Dispatchers.IO) {
        try {
            Timber.d("Starting RTSP server on port $port...")

            bossGroup = NioEventLoopGroup(1)
            workerGroup = NioEventLoopGroup()

            val bootstrap = ServerBootstrap()
            bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel::class.java)
                .childHandler(object : ChannelInitializer<SocketChannel>() {
                    override fun initChannel(ch: SocketChannel) {
                        ch.pipeline().apply {
                            addLast("rtspDecoder", RTSPDecoder())
                            addLast("rtspEncoder", RTSPEncoder())
                            addLast("rtspHandler", RTSPHandler(this@RTSPServer))
                        }
                    }
                })
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true)

            val channelFuture = bootstrap.bind(port).sync()
            channel = channelFuture.channel()

            Timber.i("RTSP server started on port $port")
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to start RTSP server")
            false
        }
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        try {
            Timber.d("Stopping RTSP server...")

            sessions.forEach { (id, _) ->
                eventListener.onAudioSessionStopped(id)
            }
            sessions.clear()

            channel?.close()?.sync()
            workerGroup?.shutdownGracefully()
            bossGroup?.shutdownGracefully()

            channel = null
            workerGroup = null
            bossGroup = null

            Timber.i("RTSP server stopped")
        } catch (e: Exception) {
            Timber.e(e, "Error stopping RTSP server")
        }
    }

    fun createSession(clientAddress: InetSocketAddress, codec: AudioCodec): AudioSession {
        val sessionId = java.util.UUID.randomUUID().toString()
        val session = AudioSession(
            sessionId = sessionId,
            clientAddress = clientAddress,
            codec = codec
        )
        sessions[sessionId] = session
        eventListener.onAudioSessionStarted(session)
        return session
    }

    fun removeSession(sessionId: String) {
        sessions.remove(sessionId)
        eventListener.onAudioSessionStopped(sessionId)
    }

    fun getDataPort(): Int = dataPort
    fun getControlPort(): Int = controlPort
    fun getTimingPort(): Int = timingPort

    fun isRunning(): Boolean = channel?.isActive == true

    /**
     * RTSP Request decoder
     */
    private class RTSPDecoder : ChannelInboundHandlerAdapter() {
        private val buffer = StringBuilder()
        private var contentLength = 0
        private var headersDone = false

        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            if (msg is ByteBuf) {
                val data = msg.toString(StandardCharsets.UTF_8)
                buffer.append(data)
                msg.release()

                // Parse RTSP message
                while (buffer.isNotEmpty()) {
                    if (!headersDone) {
                        val headerEnd = buffer.indexOf("\r\n\r\n")
                        if (headerEnd == -1) break

                        val headers = buffer.substring(0, headerEnd)
                        buffer.delete(0, headerEnd + 4)
                        headersDone = true

                        // Parse Content-Length
                        val clMatch = "Content-Length:\\s*(\\d+)".toRegex(RegexOption.IGNORE_CASE)
                            .find(headers)
                        contentLength = clMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0

                        if (contentLength == 0) {
                            ctx.fireChannelRead(RTSPMessage.parse(headers, ""))
                            headersDone = false
                        }
                    }

                    if (headersDone && buffer.length >= contentLength) {
                        val body = buffer.substring(0, contentLength)
                        buffer.delete(0, contentLength)
                        headersDone = false

                        // We need to reconstruct headers here - simplified for now
                        ctx.fireChannelRead(RTSPMessage("", "", emptyMap(), body))
                    } else if (headersDone) {
                        break
                    }
                }
            }
        }
    }

    /**
     * RTSP Response encoder
     */
    private class RTSPEncoder : ChannelOutboundHandlerAdapter() {
        override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
            if (msg is RTSPResponse) {
                val response = buildString {
                    append("RTSP/1.0 ${msg.statusCode} ${msg.statusText}\r\n")
                    msg.headers.forEach { (key, value) ->
                        append("$key: $value\r\n")
                    }
                    append("\r\n")
                    append(msg.body)
                }
                ctx.write(Unpooled.copiedBuffer(response, StandardCharsets.UTF_8), promise)
            } else {
                ctx.write(msg, promise)
            }
        }
    }

    /**
     * RTSP Message data class
     */
    data class RTSPMessage(
        val method: String,
        val uri: String,
        val headers: Map<String, String>,
        val body: String
    ) {
        companion object {
            fun parse(headerSection: String, body: String): RTSPMessage {
                val lines = headerSection.split("\r\n")
                if (lines.isEmpty()) return RTSPMessage("", "", emptyMap(), body)

                val requestLine = lines[0].split(" ")
                val method = requestLine.getOrElse(0) { "" }
                val uri = requestLine.getOrElse(1) { "" }

                val headers = mutableMapOf<String, String>()
                for (i in 1 until lines.size) {
                    val line = lines[i]
                    val colonIndex = line.indexOf(':')
                    if (colonIndex > 0) {
                        val key = line.substring(0, colonIndex).trim()
                        val value = line.substring(colonIndex + 1).trim()
                        headers[key] = value
                    }
                }

                return RTSPMessage(method, uri, headers, body)
            }
        }
    }

    /**
     * RTSP Response data class
     */
    data class RTSPResponse(
        val statusCode: Int,
        val statusText: String,
        val headers: Map<String, String>,
        val body: String = ""
    )

    /**
     * RTSP Handler for RAOP
     */
    private class RTSPHandler(private val server: RTSPServer) : SimpleChannelInboundHandler<RTSPMessage>() {

        private var cseq: Int = 0
        private var sessionId: String? = null

        override fun channelRead0(ctx: ChannelHandlerContext, msg: RTSPMessage) {
            Timber.d("RTSP: ${msg.method} ${msg.uri}")

            cseq = msg.headers["CSeq"]?.toIntOrNull() ?: 0

            when (msg.method) {
                AirPlayConstants.RTSPMethods.OPTIONS -> handleOptions(ctx, msg)
                AirPlayConstants.RTSPMethods.ANNOUNCE -> handleAnnounce(ctx, msg)
                AirPlayConstants.RTSPMethods.SETUP -> handleSetup(ctx, msg)
                AirPlayConstants.RTSPMethods.RECORD -> handleRecord(ctx, msg)
                AirPlayConstants.RTSPMethods.PAUSE -> handlePause(ctx, msg)
                AirPlayConstants.RTSPMethods.FLUSH -> handleFlush(ctx, msg)
                AirPlayConstants.RTSPMethods.TEARDOWN -> handleTeardown(ctx, msg)
                AirPlayConstants.RTSPMethods.SET_PARAMETER -> handleSetParameter(ctx, msg)
                AirPlayConstants.RTSPMethods.GET_PARAMETER -> handleGetParameter(ctx, msg)
                AirPlayConstants.RTSPMethods.POST -> handlePost(ctx, msg)
                else -> {
                    Timber.w("Unknown RTSP method: ${msg.method}")
                    sendResponse(ctx, 501, "Not Implemented")
                }
            }
        }

        private fun handleOptions(ctx: ChannelHandlerContext, msg: RTSPMessage) {
            val headers = mapOf(
                "Public" to "ANNOUNCE, SETUP, RECORD, PAUSE, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER, POST"
            )
            sendResponse(ctx, 200, "OK", headers)
        }

        private fun handleAnnounce(ctx: ChannelHandlerContext, msg: RTSPMessage) {
            // Parse SDP for codec info
            Timber.d("ANNOUNCE body: ${msg.body}")

            // Extract codec from SDP
            val codec = when {
                msg.body.contains("AppleLossless") -> AudioCodec.ALAC
                msg.body.contains("mpeg4-generic") -> AudioCodec.AAC
                msg.body.contains("L16") -> AudioCodec.PCM
                else -> AudioCodec.ALAC
            }

            val clientAddr = ctx.channel().remoteAddress() as InetSocketAddress
            val session = server.createSession(clientAddr, codec)
            sessionId = session.sessionId

            sendResponse(ctx, 200, "OK")
        }

        private fun handleSetup(ctx: ChannelHandlerContext, msg: RTSPMessage) {
            // Parse transport header
            val transport = msg.headers["Transport"] ?: ""
            Timber.d("Setup transport: $transport")

            // Extract client ports
            val clientPortMatch = "control_port=(\\d+)".toRegex().find(transport)
            val timingPortMatch = "timing_port=(\\d+)".toRegex().find(transport)

            val responseHeaders = mutableMapOf<String, String>()
            responseHeaders["Session"] = sessionId ?: java.util.UUID.randomUUID().toString()

            // Build transport response with our server ports
            val serverTransport = buildString {
                append("RTP/AVP/UDP;unicast;mode=record")
                append(";server_port=${server.getDataPort()}")
                append(";control_port=${server.getControlPort()}")
                append(";timing_port=${server.getTimingPort()}")
            }
            responseHeaders["Transport"] = serverTransport

            sendResponse(ctx, 200, "OK", responseHeaders)
        }

        private fun handleRecord(ctx: ChannelHandlerContext, msg: RTSPMessage) {
            Timber.d("Starting audio recording/playback")

            val headers = mapOf(
                "Audio-Latency" to "0"
            )
            sendResponse(ctx, 200, "OK", headers)
        }

        private fun handlePause(ctx: ChannelHandlerContext, msg: RTSPMessage) {
            Timber.d("Pausing audio")
            sendResponse(ctx, 200, "OK")
        }

        private fun handleFlush(ctx: ChannelHandlerContext, msg: RTSPMessage) {
            Timber.d("Flushing audio buffer")
            sendResponse(ctx, 200, "OK")
        }

        private fun handleTeardown(ctx: ChannelHandlerContext, msg: RTSPMessage) {
            Timber.d("Tearing down session")
            sessionId?.let { server.removeSession(it) }
            sendResponse(ctx, 200, "OK")
        }

        private fun handleSetParameter(ctx: ChannelHandlerContext, msg: RTSPMessage) {
            val contentType = msg.headers["Content-Type"] ?: ""

            when {
                contentType.contains("text/parameters") -> {
                    // Parse text parameters like volume
                    Timber.d("Set parameters: ${msg.body}")
                }
                contentType.contains("image/") -> {
                    // Album artwork
                    Timber.d("Received album artwork")
                }
                contentType.contains("application/x-dmap-tagged") -> {
                    // DAAP metadata
                    Timber.d("Received DAAP metadata: ${msg.body}")
                }
            }

            sendResponse(ctx, 200, "OK")
        }

        private fun handleGetParameter(ctx: ChannelHandlerContext, msg: RTSPMessage) {
            sendResponse(ctx, 200, "OK")
        }

        private fun handlePost(ctx: ChannelHandlerContext, msg: RTSPMessage) {
            when {
                msg.uri.contains("/fp-setup") -> handleFairPlaySetup(ctx, msg)
                else -> sendResponse(ctx, 200, "OK")
            }
        }

        private fun handleFairPlaySetup(ctx: ChannelHandlerContext, msg: RTSPMessage) {
            // FairPlay audio encryption setup
            Timber.d("FairPlay setup for audio")
            sendResponse(ctx, 200, "OK")
        }

        private fun sendResponse(
            ctx: ChannelHandlerContext,
            statusCode: Int,
            statusText: String,
            headers: Map<String, String> = emptyMap(),
            body: String = ""
        ) {
            val responseHeaders = mutableMapOf<String, String>()
            responseHeaders["CSeq"] = cseq.toString()
            responseHeaders["Server"] = "AirReceiver/1.0"
            responseHeaders.putAll(headers)

            if (body.isNotEmpty()) {
                responseHeaders["Content-Length"] = body.length.toString()
            }

            val response = RTSPResponse(statusCode, statusText, responseHeaders, body)
            ctx.writeAndFlush(response)
        }

        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            Timber.e(cause, "RTSP handler error")
            ctx.close()
        }
    }
}
