package com.airreceiver.server.handlers

import com.airreceiver.protocol.AirPlayConstants
import com.airreceiver.protocol.AirPlayConstants.Features
import com.airreceiver.server.AirPlayServer
import com.dd.plist.NSDictionary
import com.dd.plist.NSNumber
import com.dd.plist.NSString
import com.dd.plist.PropertyListParser
import com.dd.plist.BinaryPropertyListWriter
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.http.*
import io.netty.handler.timeout.IdleState
import io.netty.handler.timeout.IdleStateEvent
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

/**
 * Handles incoming AirPlay HTTP requests
 */
class AirPlayHandler(
    private val deviceId: String,
    private val deviceName: String,
    private val eventListener: AirPlayServer.AirPlayEventListener
) : SimpleChannelInboundHandler<FullHttpRequest>() {

    private var currentSession: String? = null
    private var playbackState: Int = AirPlayConstants.Status.IDLE
    private var currentPosition: Double = 0.0
    private var duration: Double = 0.0

    override fun channelActive(ctx: ChannelHandlerContext) {
        Timber.d("Client connected: ${ctx.channel().remoteAddress()}")
        super.channelActive(ctx)
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        Timber.d("Client disconnected: ${ctx.channel().remoteAddress()}")
        eventListener.onClientDisconnected()
        super.channelInactive(ctx)
    }

    override fun channelRead0(ctx: ChannelHandlerContext, request: FullHttpRequest) {
        val uri = request.uri()
        val method = request.method()

        Timber.d("AirPlay request: $method $uri")

        // Log headers for debugging
        request.headers().forEach { entry ->
            Timber.v("Header: ${entry.key} = ${entry.value}")
        }

        try {
            when {
                // Device info
                uri == AirPlayConstants.Endpoints.INFO || uri == AirPlayConstants.Endpoints.SERVER_INFO -> {
                    handleServerInfo(ctx, request)
                }

                // Playback control
                uri.startsWith(AirPlayConstants.Endpoints.PLAY) && method == HttpMethod.POST -> {
                    handlePlay(ctx, request)
                }

                uri.startsWith(AirPlayConstants.Endpoints.SCRUB) -> {
                    handleScrub(ctx, request, method)
                }

                uri.startsWith(AirPlayConstants.Endpoints.RATE) -> {
                    handleRate(ctx, request)
                }

                uri == AirPlayConstants.Endpoints.STOP -> {
                    handleStop(ctx, request)
                }

                uri == AirPlayConstants.Endpoints.PLAYBACK_INFO -> {
                    handlePlaybackInfo(ctx, request)
                }

                // Photo handling
                uri == AirPlayConstants.Endpoints.PHOTO && method == HttpMethod.PUT -> {
                    handlePhoto(ctx, request)
                }

                // Pairing endpoints (for AirPlay 2)
                uri == AirPlayConstants.Endpoints.PAIR_SETUP -> {
                    handlePairSetup(ctx, request)
                }

                uri == AirPlayConstants.Endpoints.PAIR_VERIFY -> {
                    handlePairVerify(ctx, request)
                }

                uri == AirPlayConstants.Endpoints.FP_SETUP || uri == AirPlayConstants.Endpoints.FP_SETUP2 -> {
                    handleFairPlaySetup(ctx, request)
                }

                // Feedback endpoint (keep-alive)
                uri == AirPlayConstants.Endpoints.FEEDBACK -> {
                    handleFeedback(ctx, request)
                }

                // Volume control
                uri == AirPlayConstants.Endpoints.VOLUME -> {
                    handleVolume(ctx, request)
                }

                // Stream setup (mirroring)
                uri == AirPlayConstants.Endpoints.STREAM -> {
                    handleStream(ctx, request)
                }

                // Properties
                uri.startsWith(AirPlayConstants.Endpoints.GETPROPERTY) -> {
                    handleGetProperty(ctx, request)
                }

                uri.startsWith(AirPlayConstants.Endpoints.SETPROPERTY) -> {
                    handleSetProperty(ctx, request)
                }

                // Events reverse HTTP
                uri == AirPlayConstants.Endpoints.EVENTS -> {
                    handleEvents(ctx, request)
                }

                // Command handler
                uri == AirPlayConstants.Endpoints.COMMAND -> {
                    handleCommand(ctx, request)
                }

                else -> {
                    Timber.w("Unknown endpoint: $method $uri")
                    sendResponse(ctx, HttpResponseStatus.NOT_FOUND)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error handling request: $uri")
            sendResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR)
        }
    }

    /**
     * Handle /info or /server-info request
     */
    private fun handleServerInfo(ctx: ChannelHandlerContext, request: FullHttpRequest) {
        Timber.d("Handling server info request")

        val features = Features.DEFAULT_FEATURES

        val dict = NSDictionary()
        dict["deviceid"] = NSString(deviceId)
        dict["features"] = NSNumber(features)
        dict["model"] = NSString(AirPlayConstants.MODEL)
        dict["protovers"] = NSString(AirPlayConstants.PROTOCOL_VERSION)
        dict["srcvers"] = NSString(AirPlayConstants.SOURCE_VERSION)
        dict["vv"] = NSNumber(2)
        dict["osBuildVersion"] = NSString(AirPlayConstants.OS_BUILD_VERSION)
        dict["name"] = NSString(deviceName)
        dict["statusFlags"] = NSNumber(68) // 0x44

        // Additional fields for AirPlay 2
        dict["pi"] = NSString(java.util.UUID.randomUUID().toString())
        dict["pk"] = NSString("b07727d6f6cd6e08b58c846d9b855b0b9a8b8f6a3c7c8d9e0f1a2b3c4d5e6f70")

        val plistBytes = BinaryPropertyListWriter.writeToArray(dict)
        sendPlistResponse(ctx, plistBytes)
    }

    /**
     * Handle /play request - start video playback
     */
    private fun handlePlay(ctx: ChannelHandlerContext, request: FullHttpRequest) {
        Timber.d("Handling play request")

        val content = request.content()
        val contentType = request.headers().get(HttpHeaderNames.CONTENT_TYPE) ?: ""

        try {
            val playInfo: Map<String, Any?> = if (contentType.contains("binary")) {
                // Binary plist
                val bytes = ByteArray(content.readableBytes())
                content.readBytes(bytes)
                val plist = PropertyListParser.parse(bytes) as? NSDictionary
                parsePlistToMap(plist)
            } else {
                // Text plist or parameters
                val body = content.toString(StandardCharsets.UTF_8)
                parseTextPlist(body)
            }

            val contentLocation = playInfo["Content-Location"] as? String
                ?: playInfo["location"] as? String
            val startPosition = (playInfo["Start-Position"] as? Number)?.toDouble() ?: 0.0

            Timber.d("Play URL: $contentLocation, Start: $startPosition")

            if (contentLocation != null) {
                eventListener.onClientConnected("AirPlay Client")
                eventListener.onPlayUrl(contentLocation, startPosition)
                playbackState = AirPlayConstants.Status.PLAYING
            }

            sendResponse(ctx, HttpResponseStatus.OK)
        } catch (e: Exception) {
            Timber.e(e, "Error parsing play request")
            sendResponse(ctx, HttpResponseStatus.BAD_REQUEST)
        }
    }

    /**
     * Handle /scrub request - seek or get position
     */
    private fun handleScrub(ctx: ChannelHandlerContext, request: FullHttpRequest, method: HttpMethod) {
        if (method == HttpMethod.GET) {
            // Return current position
            val response = "duration: $duration\nposition: $currentPosition\n"
            sendTextResponse(ctx, response)
        } else {
            // POST - seek to position
            val uri = request.uri()
            val positionParam = uri.substringAfter("position=", "0").substringBefore("&")
            val position = positionParam.toDoubleOrNull() ?: 0.0

            Timber.d("Seek to position: $position")
            currentPosition = position
            eventListener.onSeek(position)

            sendResponse(ctx, HttpResponseStatus.OK)
        }
    }

    /**
     * Handle /rate request - play/pause
     */
    private fun handleRate(ctx: ChannelHandlerContext, request: FullHttpRequest) {
        val uri = request.uri()
        val rateParam = uri.substringAfter("value=", "1").substringBefore("&")
        val rate = rateParam.toFloatOrNull() ?: 1.0f

        Timber.d("Rate changed: $rate")

        playbackState = if (rate == 0f) {
            AirPlayConstants.Status.PAUSED
        } else {
            AirPlayConstants.Status.PLAYING
        }

        eventListener.onPlaybackStateChanged(playbackState)
        sendResponse(ctx, HttpResponseStatus.OK)
    }

    /**
     * Handle /stop request
     */
    private fun handleStop(ctx: ChannelHandlerContext, request: FullHttpRequest) {
        Timber.d("Stop playback")
        playbackState = AirPlayConstants.Status.STOPPED
        currentPosition = 0.0
        eventListener.onStop()
        sendResponse(ctx, HttpResponseStatus.OK)
    }

    /**
     * Handle /playback-info request
     */
    private fun handlePlaybackInfo(ctx: ChannelHandlerContext, request: FullHttpRequest) {
        val dict = NSDictionary()

        when (playbackState) {
            AirPlayConstants.Status.PLAYING, AirPlayConstants.Status.PAUSED -> {
                dict["duration"] = NSNumber(duration)
                dict["position"] = NSNumber(currentPosition)
                dict["rate"] = NSNumber(if (playbackState == AirPlayConstants.Status.PLAYING) 1.0 else 0.0)
                dict["readyToPlay"] = NSNumber(true)
                dict["playbackBufferEmpty"] = NSNumber(false)
                dict["playbackBufferFull"] = NSNumber(true)
                dict["playbackLikelyToKeepUp"] = NSNumber(true)
                dict["loadedTimeRanges"] = createTimeRangesArray(0.0, duration)
                dict["seekableTimeRanges"] = createTimeRangesArray(0.0, duration)
            }
            else -> {
                dict["readyToPlay"] = NSNumber(false)
            }
        }

        val plistBytes = BinaryPropertyListWriter.writeToArray(dict)
        sendPlistResponse(ctx, plistBytes)
    }

    /**
     * Handle /photo request - display photo
     */
    private fun handlePhoto(ctx: ChannelHandlerContext, request: FullHttpRequest) {
        Timber.d("Handling photo request")

        val content = request.content()
        val photoData = ByteArray(content.readableBytes())
        content.readBytes(photoData)

        eventListener.onPhotoReceived(photoData)
        sendResponse(ctx, HttpResponseStatus.OK)
    }

    /**
     * Handle pairing setup (AirPlay 2)
     */
    private fun handlePairSetup(ctx: ChannelHandlerContext, request: FullHttpRequest) {
        Timber.d("Handling pair-setup request")

        // For now, we'll implement a minimal response
        // Full AirPlay 2 requires SRP (Secure Remote Password) protocol
        val content = request.content()
        val data = ByteArray(content.readableBytes())
        content.readBytes(data)

        // Respond with minimal acknowledgment
        // In a full implementation, this would involve cryptographic operations
        sendResponse(ctx, HttpResponseStatus.OK, data)
    }

    /**
     * Handle pair verify (AirPlay 2)
     */
    private fun handlePairVerify(ctx: ChannelHandlerContext, request: FullHttpRequest) {
        Timber.d("Handling pair-verify request")

        val content = request.content()
        val data = ByteArray(content.readableBytes())
        content.readBytes(data)

        // Minimal response - full implementation needs Ed25519/X25519
        sendResponse(ctx, HttpResponseStatus.OK, data)
    }

    /**
     * Handle FairPlay setup (for encrypted content)
     */
    private fun handleFairPlaySetup(ctx: ChannelHandlerContext, request: FullHttpRequest) {
        Timber.d("Handling FairPlay setup request")

        val content = request.content()
        val data = ByteArray(content.readableBytes())
        content.readBytes(data)

        // FairPlay requires specific key exchange
        // This is a placeholder - full implementation needs FairPlay SAP
        sendResponse(ctx, HttpResponseStatus.OK, ByteArray(0))
    }

    /**
     * Handle /feedback - keep-alive endpoint
     */
    private fun handleFeedback(ctx: ChannelHandlerContext, request: FullHttpRequest) {
        sendResponse(ctx, HttpResponseStatus.OK)
    }

    /**
     * Handle volume control
     */
    private fun handleVolume(ctx: ChannelHandlerContext, request: FullHttpRequest) {
        val content = request.content()
        val body = content.toString(StandardCharsets.UTF_8)

        val volumeMatch = "volume: ([\\d.-]+)".toRegex().find(body)
        val volume = volumeMatch?.groupValues?.get(1)?.toFloatOrNull() ?: 1.0f

        // AirPlay volume is in dB, convert to linear (0-1)
        val linearVolume = if (volume <= -144.0f) 0f else Math.pow(10.0, volume / 20.0).toFloat()

        Timber.d("Volume changed: $volume dB ($linearVolume linear)")
        eventListener.onVolumeChanged(linearVolume)

        sendResponse(ctx, HttpResponseStatus.OK)
    }

    /**
     * Handle stream setup for screen mirroring
     */
    private fun handleStream(ctx: ChannelHandlerContext, request: FullHttpRequest) {
        Timber.d("Handling stream setup request")

        val content = request.content()
        val bytes = ByteArray(content.readableBytes())
        content.readBytes(bytes)

        try {
            val plist = PropertyListParser.parse(bytes) as? NSDictionary
            Timber.d("Stream setup plist: $plist")

            // Parse stream info for mirroring
            eventListener.onMirroringStarted()

            // Respond with stream configuration
            val responseDict = NSDictionary()
            responseDict["eventPort"] = NSNumber(AirPlayConstants.AIRPLAY_MIRROR_PORT)

            val plistBytes = BinaryPropertyListWriter.writeToArray(responseDict)
            sendPlistResponse(ctx, plistBytes)
        } catch (e: Exception) {
            Timber.e(e, "Error parsing stream setup")
            sendResponse(ctx, HttpResponseStatus.BAD_REQUEST)
        }
    }

    /**
     * Handle GET property
     */
    private fun handleGetProperty(ctx: ChannelHandlerContext, request: FullHttpRequest) {
        val uri = request.uri()
        val property = uri.substringAfter("?")

        Timber.d("Get property: $property")

        val dict = NSDictionary()
        dict["value"] = NSNumber(0)

        val plistBytes = BinaryPropertyListWriter.writeToArray(dict)
        sendPlistResponse(ctx, plistBytes)
    }

    /**
     * Handle SET property
     */
    private fun handleSetProperty(ctx: ChannelHandlerContext, request: FullHttpRequest) {
        val uri = request.uri()
        val property = uri.substringAfter("?").substringBefore("=")

        Timber.d("Set property: $property")
        sendResponse(ctx, HttpResponseStatus.OK)
    }

    /**
     * Handle reverse events connection
     */
    private fun handleEvents(ctx: ChannelHandlerContext, request: FullHttpRequest) {
        Timber.d("Events connection established")

        // This is a long-lived connection for server-initiated events
        // Don't close the connection, just acknowledge
        val response = DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus.OK
        )
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0)
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE)
        ctx.writeAndFlush(response)
    }

    /**
     * Handle command requests
     */
    private fun handleCommand(ctx: ChannelHandlerContext, request: FullHttpRequest) {
        Timber.d("Handling command request")

        val content = request.content()
        val bytes = ByteArray(content.readableBytes())
        content.readBytes(bytes)

        try {
            val plist = PropertyListParser.parse(bytes) as? NSDictionary
            val type = (plist?.get("type") as? NSString)?.content

            Timber.d("Command type: $type")

            when (type) {
                "wirestart", "wireStartSession" -> eventListener.onMirroringStarted()
                "wireend", "wireEndSession" -> eventListener.onMirroringStopped()
            }

            sendResponse(ctx, HttpResponseStatus.OK)
        } catch (e: Exception) {
            Timber.e(e, "Error parsing command")
            sendResponse(ctx, HttpResponseStatus.OK)
        }
    }

    // Helper methods

    private fun parsePlistToMap(plist: NSDictionary?): Map<String, Any?> {
        if (plist == null) return emptyMap()

        return plist.allKeys().associateWith { key ->
            when (val value = plist[key]) {
                is NSString -> value.content
                is NSNumber -> value.doubleValue()
                else -> value?.toString()
            }
        }
    }

    private fun parseTextPlist(text: String): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        text.lines().forEach { line ->
            val parts = line.split(": ", limit = 2)
            if (parts.size == 2) {
                map[parts[0].trim()] = parts[1].trim()
            }
        }
        return map
    }

    private fun createTimeRangesArray(start: Double, duration: Double): com.dd.plist.NSArray {
        val array = com.dd.plist.NSArray(1)
        val range = NSDictionary()
        range["start"] = NSNumber(start)
        range["duration"] = NSNumber(duration)
        array.setValue(0, range)
        return array
    }

    private fun sendResponse(
        ctx: ChannelHandlerContext,
        status: HttpResponseStatus,
        content: ByteArray = ByteArray(0)
    ) {
        val response = DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            status,
            Unpooled.wrappedBuffer(content)
        )
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.size)
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE)
    }

    private fun sendTextResponse(ctx: ChannelHandlerContext, text: String) {
        val content = text.toByteArray(StandardCharsets.UTF_8)
        val response = DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus.OK,
            Unpooled.wrappedBuffer(content)
        )
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/parameters")
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.size)
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE)
    }

    private fun sendPlistResponse(ctx: ChannelHandlerContext, plistBytes: ByteArray) {
        val response = DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus.OK,
            Unpooled.wrappedBuffer(plistBytes)
        )
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/x-apple-binary-plist")
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, plistBytes.size)
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE)
    }

    override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
        if (evt is IdleStateEvent) {
            when (evt.state()) {
                IdleState.READER_IDLE -> {
                    Timber.d("Client idle, closing connection")
                    ctx.close()
                }
                else -> {}
            }
        }
        super.userEventTriggered(ctx, evt)
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        Timber.e(cause, "Error in AirPlay handler")
        ctx.close()
    }

    // Update playback state (called by service)
    fun updatePlaybackPosition(position: Double, totalDuration: Double) {
        currentPosition = position
        duration = totalDuration
    }
}
