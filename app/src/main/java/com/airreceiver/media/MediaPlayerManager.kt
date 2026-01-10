package com.airreceiver.media

import android.content.Context
import android.net.Uri
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import timber.log.Timber

/**
 * Manages media playback for AirPlay video streaming
 * Supports HLS, MP4, and other common formats via ExoPlayer
 */
class MediaPlayerManager(private val context: Context) {

    private var exoPlayer: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var surface: Surface? = null

    private var listener: MediaPlayerListener? = null
    private var positionUpdateJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    interface MediaPlayerListener {
        fun onPlaybackStarted()
        fun onPlaybackPaused()
        fun onPlaybackStopped()
        fun onPlaybackCompleted()
        fun onPlaybackError(error: String)
        fun onPositionUpdate(position: Double, duration: Double)
        fun onVideoSizeChanged(width: Int, height: Int)
        fun onBuffering(isBuffering: Boolean)
    }

    /**
     * Initialize the player
     */
    fun initialize() {
        if (exoPlayer != null) return

        exoPlayer = ExoPlayer.Builder(context).build().apply {
            addListener(playerEventListener)
            playWhenReady = true
        }

        Timber.d("Media player initialized")
    }

    /**
     * Set the PlayerView for video output
     */
    fun setPlayerView(view: PlayerView?) {
        playerView = view
        playerView?.player = exoPlayer
    }

    /**
     * Set the surface for video output (alternative to PlayerView)
     */
    fun setSurface(surface: Surface?) {
        this.surface = surface
        exoPlayer?.setVideoSurface(surface)
    }

    /**
     * Set the event listener
     */
    fun setListener(listener: MediaPlayerListener?) {
        this.listener = listener
    }

    /**
     * Play media from URL
     */
    fun play(url: String, startPosition: Double = 0.0) {
        Timber.d("Playing URL: $url, start: $startPosition")

        try {
            initialize()

            val mediaItem = MediaItem.fromUri(Uri.parse(url))

            // Check if it's HLS
            if (url.contains(".m3u8") || url.contains("hls")) {
                val dataSourceFactory = DefaultHttpDataSource.Factory()
                    .setUserAgent("AppleCoreMedia/1.0.0.19B74 (Apple TV; U; CPU OS 15_1 like Mac OS X)")
                    .setConnectTimeoutMs(30000)
                    .setReadTimeoutMs(30000)
                    .setAllowCrossProtocolRedirects(true)

                val hlsMediaSource = HlsMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(mediaItem)

                exoPlayer?.setMediaSource(hlsMediaSource)
            } else {
                exoPlayer?.setMediaItem(mediaItem)
            }

            exoPlayer?.prepare()

            if (startPosition > 0) {
                exoPlayer?.seekTo((startPosition * 1000).toLong())
            }

            exoPlayer?.play()

            startPositionUpdates()

        } catch (e: Exception) {
            Timber.e(e, "Error playing URL: $url")
            listener?.onPlaybackError(e.message ?: "Unknown error")
        }
    }

    /**
     * Pause playback
     */
    fun pause() {
        exoPlayer?.pause()
    }

    /**
     * Resume playback
     */
    fun resume() {
        exoPlayer?.play()
    }

    /**
     * Stop playback
     */
    fun stop() {
        stopPositionUpdates()
        exoPlayer?.stop()
        exoPlayer?.clearMediaItems()
    }

    /**
     * Seek to position (in seconds)
     */
    fun seekTo(position: Double) {
        exoPlayer?.seekTo((position * 1000).toLong())
    }

    /**
     * Set playback rate
     */
    fun setRate(rate: Float) {
        if (rate == 0f) {
            pause()
        } else {
            exoPlayer?.setPlaybackSpeed(rate)
            if (exoPlayer?.isPlaying == false) {
                resume()
            }
        }
    }

    /**
     * Set volume (0.0 to 1.0)
     */
    fun setVolume(volume: Float) {
        exoPlayer?.volume = volume.coerceIn(0f, 1f)
    }

    /**
     * Get current position in seconds
     */
    fun getPosition(): Double {
        return (exoPlayer?.currentPosition ?: 0L) / 1000.0
    }

    /**
     * Get total duration in seconds
     */
    fun getDuration(): Double {
        val duration = exoPlayer?.duration ?: 0L
        return if (duration > 0) duration / 1000.0 else 0.0
    }

    /**
     * Check if currently playing
     */
    fun isPlaying(): Boolean {
        return exoPlayer?.isPlaying == true
    }

    /**
     * Release resources
     */
    fun release() {
        stopPositionUpdates()
        exoPlayer?.release()
        exoPlayer = null
        playerView = null
        surface = null
        Timber.d("Media player released")
    }

    private fun startPositionUpdates() {
        stopPositionUpdates()
        positionUpdateJob = scope.launch {
            while (true) {
                delay(500)
                val position = getPosition()
                val duration = getDuration()
                listener?.onPositionUpdate(position, duration)
            }
        }
    }

    private fun stopPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = null
    }

    private val playerEventListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_IDLE -> {
                    Timber.d("Player state: IDLE")
                }
                Player.STATE_BUFFERING -> {
                    Timber.d("Player state: BUFFERING")
                    listener?.onBuffering(true)
                }
                Player.STATE_READY -> {
                    Timber.d("Player state: READY")
                    listener?.onBuffering(false)
                }
                Player.STATE_ENDED -> {
                    Timber.d("Player state: ENDED")
                    listener?.onPlaybackCompleted()
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            Timber.d("Is playing: $isPlaying")
            if (isPlaying) {
                listener?.onPlaybackStarted()
            } else {
                listener?.onPlaybackPaused()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Timber.e(error, "Player error")
            listener?.onPlaybackError(error.message ?: "Playback error")
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            Timber.d("Video size: ${videoSize.width}x${videoSize.height}")
            listener?.onVideoSizeChanged(videoSize.width, videoSize.height)
        }
    }
}

/**
 * Audio session player for RAOP audio streaming
 * Handles real-time audio decoding and playback
 */
class AudioSessionPlayer(private val context: Context) {

    private var audioTrack: android.media.AudioTrack? = null
    private val sampleRate = 44100
    private val channels = android.media.AudioFormat.CHANNEL_OUT_STEREO
    private val encoding = android.media.AudioFormat.ENCODING_PCM_16BIT

    private var isPlaying = false
    private var volume = 1.0f

    /**
     * Initialize audio playback
     */
    fun initialize() {
        val bufferSize = android.media.AudioTrack.getMinBufferSize(
            sampleRate, channels, encoding
        ) * 4

        audioTrack = android.media.AudioTrack.Builder()
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                android.media.AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channels)
                    .setEncoding(encoding)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(android.media.AudioTrack.MODE_STREAM)
            .build()

        Timber.d("Audio session player initialized")
    }

    /**
     * Start playback
     */
    fun start() {
        audioTrack?.play()
        isPlaying = true
    }

    /**
     * Write PCM audio data
     */
    fun writeAudioData(data: ByteArray) {
        if (!isPlaying) return
        audioTrack?.write(data, 0, data.size)
    }

    /**
     * Pause playback
     */
    fun pause() {
        audioTrack?.pause()
        isPlaying = false
    }

    /**
     * Stop playback
     */
    fun stop() {
        audioTrack?.stop()
        isPlaying = false
    }

    /**
     * Set volume (0.0 to 1.0)
     */
    fun setVolume(vol: Float) {
        volume = vol.coerceIn(0f, 1f)
        audioTrack?.setVolume(volume)
    }

    /**
     * Flush audio buffer
     */
    fun flush() {
        audioTrack?.flush()
    }

    /**
     * Release resources
     */
    fun release() {
        stop()
        audioTrack?.release()
        audioTrack = null
        Timber.d("Audio session player released")
    }
}
