package com.airreceiver.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.media3.ui.PlayerView
import com.airreceiver.AirReceiverApplication
import com.airreceiver.R
import com.airreceiver.crypto.AirPlayAuth
import com.airreceiver.media.AudioSessionPlayer
import com.airreceiver.media.MediaPlayerManager
import com.airreceiver.protocol.AirPlayConstants
import com.airreceiver.server.AirPlayServer
import com.airreceiver.server.MirroringServer
import com.airreceiver.server.RTSPServer
import kotlinx.coroutines.*
import timber.log.Timber

/**
 * Main AirPlay foreground service
 * Manages all AirPlay protocol servers and media playback
 */
class AirPlayService : Service() {

    private val binder = AirPlayBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Protocol servers
    private lateinit var mdnsService: MDNSService
    private lateinit var airplayServer: AirPlayServer
    private lateinit var rtspServer: RTSPServer
    private lateinit var mirroringServer: MirroringServer

    // Crypto/Auth
    private lateinit var airplayAuth: AirPlayAuth

    // Media playback
    private lateinit var mediaPlayer: MediaPlayerManager
    private var audioPlayer: AudioSessionPlayer? = null

    // State
    private var isRunning = false
    private var eventListener: AirPlayEventListener? = null

    interface AirPlayEventListener {
        fun onServiceStarted(ipAddress: String)
        fun onServiceStopped()
        fun onClientConnected(clientName: String)
        fun onClientDisconnected()
        fun onPlaybackStarted(url: String)
        fun onPlaybackStopped()
        fun onMirroringStarted(width: Int, height: Int)
        fun onMirroringStopped()
        fun onError(message: String)
        fun onPositionUpdate(position: Double, duration: Double)
    }

    inner class AirPlayBinder : Binder() {
        fun getService(): AirPlayService = this@AirPlayService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Timber.d("AirPlayService onCreate")

        // Initialize components
        mdnsService = MDNSService(this)
        airplayAuth = AirPlayAuth().also { it.initialize() }
        mediaPlayer = MediaPlayerManager(this)

        airplayServer = AirPlayServer(airplayEventListener)
        rtspServer = RTSPServer(rtspEventListener)
        mirroringServer = MirroringServer(mirroringEventListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.d("AirPlayService onStartCommand")

        when (intent?.action) {
            ACTION_START -> startAirPlayService()
            ACTION_STOP -> stopAirPlayService()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.d("AirPlayService onDestroy")
        stopAirPlayService()
        serviceScope.cancel()
    }

    /**
     * Start all AirPlay services
     */
    private fun startAirPlayService() {
        if (isRunning) {
            Timber.d("Service already running")
            return
        }

        // Start as foreground service
        startForeground(NOTIFICATION_ID, createNotification())

        serviceScope.launch {
            try {
                // Set device info on server
                airplayServer.setDeviceInfo(
                    mdnsService.deviceId,
                    mdnsService.deviceName
                )

                // Start servers
                val airplayStarted = airplayServer.start(AirPlayConstants.AIRPLAY_PORT)
                val rtspStarted = rtspServer.start(AirPlayConstants.RAOP_PORT)
                val mirroringStarted = mirroringServer.start(AirPlayConstants.AIRPLAY_MIRROR_PORT)

                if (!airplayStarted) {
                    throw Exception("Failed to start AirPlay server")
                }

                // Start mDNS advertisement
                val mdnsStarted = mdnsService.start(
                    AirPlayConstants.AIRPLAY_PORT,
                    AirPlayConstants.RAOP_PORT
                )

                if (!mdnsStarted) {
                    throw Exception("Failed to start mDNS service")
                }

                isRunning = true
                val ipAddress = mdnsService.getLocalIpAddress().hostAddress ?: "Unknown"
                eventListener?.onServiceStarted(ipAddress)

                Timber.i("AirPlay service started on $ipAddress")

            } catch (e: Exception) {
                Timber.e(e, "Failed to start AirPlay service")
                eventListener?.onError("Failed to start: ${e.message}")
                stopSelf()
            }
        }
    }

    /**
     * Stop all AirPlay services
     */
    private fun stopAirPlayService() {
        if (!isRunning) return

        serviceScope.launch {
            try {
                mdnsService.stop()
                airplayServer.stop()
                rtspServer.stop()
                mirroringServer.stop()

                mediaPlayer.release()
                audioPlayer?.release()

                isRunning = false
                eventListener?.onServiceStopped()

                Timber.i("AirPlay service stopped")

            } catch (e: Exception) {
                Timber.e(e, "Error stopping AirPlay service")
            }

            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /**
     * Set event listener
     */
    fun setEventListener(listener: AirPlayEventListener?) {
        eventListener = listener
    }

    /**
     * Set PlayerView for video output
     */
    fun setPlayerView(view: PlayerView?) {
        mediaPlayer.setPlayerView(view)
    }

    /**
     * Set Surface for mirroring output
     */
    fun setMirroringSurface(surface: Surface?) {
        mirroringServer.setOutputSurface(surface)
    }

    /**
     * Get current playback position
     */
    fun getPlaybackPosition(): Double = mediaPlayer.getPosition()

    /**
     * Get current playback duration
     */
    fun getPlaybackDuration(): Double = mediaPlayer.getDuration()

    /**
     * Check if service is running
     */
    fun isRunning(): Boolean = isRunning

    /**
     * Get local IP address
     */
    fun getIpAddress(): String? {
        return if (isRunning) {
            mdnsService.getLocalIpAddress().hostAddress
        } else null
    }

    private fun createNotification(): Notification {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, AirReceiverApplication.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_airplay)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    // Event listeners for protocol servers

    private val airplayEventListener = object : AirPlayServer.AirPlayEventListener {
        override fun onClientConnected(clientInfo: String) {
            Timber.d("Client connected: $clientInfo")
            serviceScope.launch {
                eventListener?.onClientConnected(clientInfo)
            }
        }

        override fun onClientDisconnected() {
            Timber.d("Client disconnected")
            serviceScope.launch {
                eventListener?.onClientDisconnected()
            }
        }

        override fun onPlayUrl(url: String, position: Double) {
            Timber.d("Play URL: $url at $position")
            serviceScope.launch {
                mediaPlayer.play(url, position)
                eventListener?.onPlaybackStarted(url)
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            Timber.d("Playback state: $state")
            serviceScope.launch {
                when (state) {
                    AirPlayConstants.Status.PLAYING -> mediaPlayer.resume()
                    AirPlayConstants.Status.PAUSED -> mediaPlayer.pause()
                    AirPlayConstants.Status.STOPPED -> mediaPlayer.stop()
                }
            }
        }

        override fun onSeek(position: Double) {
            Timber.d("Seek to: $position")
            serviceScope.launch {
                mediaPlayer.seekTo(position)
            }
        }

        override fun onStop() {
            Timber.d("Stop playback")
            serviceScope.launch {
                mediaPlayer.stop()
                eventListener?.onPlaybackStopped()
            }
        }

        override fun onMirroringStarted() {
            Timber.d("Mirroring started from AirPlay server")
        }

        override fun onMirroringStopped() {
            Timber.d("Mirroring stopped from AirPlay server")
            serviceScope.launch {
                eventListener?.onMirroringStopped()
            }
        }

        override fun onPhotoReceived(photoData: ByteArray) {
            Timber.d("Photo received: ${photoData.size} bytes")
            // TODO: Display photo
        }

        override fun onVolumeChanged(volume: Float) {
            Timber.d("Volume changed: $volume")
            serviceScope.launch {
                mediaPlayer.setVolume(volume)
                audioPlayer?.setVolume(volume)
            }
        }

        override fun onMetadataReceived(title: String?, artist: String?, album: String?) {
            Timber.d("Metadata: $title - $artist - $album")
            // TODO: Update UI with metadata
        }
    }

    private val rtspEventListener = object : RTSPServer.RTSPEventListener {
        override fun onAudioSessionStarted(session: RTSPServer.AudioSession) {
            Timber.d("Audio session started: ${session.sessionId}")
            serviceScope.launch {
                audioPlayer = AudioSessionPlayer(this@AirPlayService).also {
                    it.initialize()
                    it.start()
                }
            }
        }

        override fun onAudioSessionStopped(sessionId: String) {
            Timber.d("Audio session stopped: $sessionId")
            serviceScope.launch {
                audioPlayer?.release()
                audioPlayer = null
            }
        }

        override fun onAudioData(sessionId: String, data: ByteArray, timestamp: Long) {
            audioPlayer?.writeAudioData(data)
        }

        override fun onAudioMetadata(
            title: String?,
            artist: String?,
            album: String?,
            artwork: ByteArray?
        ) {
            Timber.d("Audio metadata: $title - $artist - $album")
        }
    }

    private val mirroringEventListener = object : MirroringServer.MirroringEventListener {
        override fun onMirroringStarted(width: Int, height: Int) {
            Timber.d("Mirroring started: ${width}x$height")
            serviceScope.launch {
                eventListener?.onMirroringStarted(width, height)
            }
        }

        override fun onMirroringStopped() {
            Timber.d("Mirroring stopped")
            serviceScope.launch {
                eventListener?.onMirroringStopped()
            }
        }

        override fun onMirroringError(error: String) {
            Timber.e("Mirroring error: $error")
            serviceScope.launch {
                eventListener?.onError(error)
            }
        }
    }

    companion object {
        const val ACTION_START = "com.airreceiver.START"
        const val ACTION_STOP = "com.airreceiver.STOP"
        private const val NOTIFICATION_ID = 1001
    }
}
