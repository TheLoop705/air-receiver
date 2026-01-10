package com.airreceiver.server

import com.airreceiver.protocol.AirPlayConstants
import com.airreceiver.server.handlers.AirPlayHandler
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.timeout.IdleStateHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Main AirPlay HTTP server that handles all AirPlay protocol communications
 */
class AirPlayServer(
    private val eventListener: AirPlayEventListener
) {
    private var bossGroup: NioEventLoopGroup? = null
    private var workerGroup: NioEventLoopGroup? = null
    private var channel: Channel? = null

    private var deviceId: String = "AA:BB:CC:DD:EE:FF"
    private var deviceName: String = "Air Receiver"

    interface AirPlayEventListener {
        fun onClientConnected(clientInfo: String)
        fun onClientDisconnected()
        fun onPlayUrl(url: String, position: Double)
        fun onPlaybackStateChanged(state: Int)
        fun onSeek(position: Double)
        fun onStop()
        fun onMirroringStarted()
        fun onMirroringStopped()
        fun onPhotoReceived(photoData: ByteArray)
        fun onVolumeChanged(volume: Float)
        fun onMetadataReceived(title: String?, artist: String?, album: String?)
    }

    fun setDeviceInfo(id: String, name: String) {
        deviceId = id
        deviceName = name
    }

    /**
     * Start the AirPlay HTTP server
     */
    suspend fun start(port: Int = AirPlayConstants.AIRPLAY_PORT): Boolean = withContext(Dispatchers.IO) {
        try {
            Timber.d("Starting AirPlay server on port $port...")

            bossGroup = NioEventLoopGroup(1)
            workerGroup = NioEventLoopGroup()

            val bootstrap = ServerBootstrap()
            bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel::class.java)
                .childHandler(object : ChannelInitializer<SocketChannel>() {
                    override fun initChannel(ch: SocketChannel) {
                        ch.pipeline().apply {
                            // Idle state handler for connection timeout
                            addLast("idleStateHandler", IdleStateHandler(60, 30, 0, TimeUnit.SECONDS))

                            // HTTP codec
                            addLast("httpCodec", HttpServerCodec())

                            // Aggregate HTTP messages (for large POSTs like photos)
                            addLast("httpAggregator", HttpObjectAggregator(10 * 1024 * 1024)) // 10MB max

                            // Our custom AirPlay handler
                            addLast("airplayHandler", AirPlayHandler(
                                deviceId = deviceId,
                                deviceName = deviceName,
                                eventListener = eventListener
                            ))
                        }
                    }
                })
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.TCP_NODELAY, true)

            val channelFuture = bootstrap.bind(port).sync()
            channel = channelFuture.channel()

            Timber.i("AirPlay server started on port $port")
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to start AirPlay server")
            false
        }
    }

    /**
     * Stop the AirPlay server
     */
    suspend fun stop() = withContext(Dispatchers.IO) {
        try {
            Timber.d("Stopping AirPlay server...")

            channel?.close()?.sync()
            workerGroup?.shutdownGracefully()
            bossGroup?.shutdownGracefully()

            channel = null
            workerGroup = null
            bossGroup = null

            Timber.i("AirPlay server stopped")
        } catch (e: Exception) {
            Timber.e(e, "Error stopping AirPlay server")
        }
    }

    fun isRunning(): Boolean = channel?.isActive == true
}
