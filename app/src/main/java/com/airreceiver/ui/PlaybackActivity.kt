package com.airreceiver.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.airreceiver.R
import com.airreceiver.databinding.ActivityPlaybackBinding
import com.airreceiver.service.AirPlayService
import timber.log.Timber

/**
 * Activity for video playback and screen mirroring display
 */
class PlaybackActivity : AppCompatActivity(), AirPlayService.AirPlayEventListener {

    private lateinit var binding: ActivityPlaybackBinding

    private var airPlayService: AirPlayService? = null
    private var serviceBound = false
    private var isMirroring = false

    private val handler = Handler(Looper.getMainLooper())
    private val hideOverlayRunnable = Runnable { hideOverlay() }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as AirPlayService.AirPlayBinder
            airPlayService = binder.getService()
            airPlayService?.setEventListener(this@PlaybackActivity)
            serviceBound = true

            setupPlayback()
            Timber.d("Service connected in PlaybackActivity")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            airPlayService?.setEventListener(null)
            airPlayService = null
            serviceBound = false
            Timber.d("Service disconnected in PlaybackActivity")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlaybackBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Get intent extras
        isMirroring = intent.getBooleanExtra(EXTRA_MIRRORING, false)

        // Hide system UI
        hideSystemUI()

        // Setup surface for mirroring
        if (isMirroring) {
            setupMirroringSurface()
        }
    }

    override fun onStart() {
        super.onStart()
        Intent(this, AirPlayService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (serviceBound) {
            airPlayService?.setEventListener(null)
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                finish()
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                toggleOverlay()
                true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                // Toggle play/pause
                true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                // Play
                true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                // Pause
                true
            }
            KeyEvent.KEYCODE_MEDIA_STOP -> {
                finish()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun setupPlayback() {
        if (isMirroring) {
            // For mirroring, use SurfaceView
            binding.playerView.visibility = View.GONE
            binding.videoSurface.visibility = View.VISIBLE
        } else {
            // For video playback, use PlayerView
            binding.videoSurface.visibility = View.GONE
            binding.playerView.visibility = View.VISIBLE
            airPlayService?.setPlayerView(binding.playerView)
        }

        showLoading(false)
    }

    private fun setupMirroringSurface() {
        binding.videoSurface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Timber.d("Mirroring surface created")
                airPlayService?.setMirroringSurface(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                Timber.d("Mirroring surface changed: ${width}x$height")
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Timber.d("Mirroring surface destroyed")
                airPlayService?.setMirroringSurface(null)
            }
        })
    }

    private fun hideSystemUI() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        )
    }

    private fun showLoading(show: Boolean) {
        runOnUiThread {
            binding.loadingIndicator.visibility = if (show) View.VISIBLE else View.GONE
        }
    }

    private fun showOverlay() {
        runOnUiThread {
            binding.nowPlayingOverlay.visibility = View.VISIBLE
            handler.removeCallbacks(hideOverlayRunnable)
            handler.postDelayed(hideOverlayRunnable, OVERLAY_HIDE_DELAY)
        }
    }

    private fun hideOverlay() {
        runOnUiThread {
            binding.nowPlayingOverlay.visibility = View.GONE
        }
    }

    private fun toggleOverlay() {
        if (binding.nowPlayingOverlay.visibility == View.VISIBLE) {
            hideOverlay()
        } else {
            showOverlay()
        }
    }

    private fun updateProgress(position: Double, duration: Double) {
        runOnUiThread {
            if (duration > 0) {
                val progress = ((position / duration) * 100).toInt()
                binding.progressBar.progress = progress
            }
        }
    }

    // AirPlayService.AirPlayEventListener implementation

    override fun onServiceStarted(ipAddress: String) {
        // Not relevant here
    }

    override fun onServiceStopped() {
        finish()
    }

    override fun onClientConnected(clientName: String) {
        // Client connected
    }

    override fun onClientDisconnected() {
        // Return to main activity when client disconnects
        finish()
    }

    override fun onPlaybackStarted(url: String) {
        Timber.d("Playback started in PlaybackActivity")
        showLoading(false)
    }

    override fun onPlaybackStopped() {
        Timber.d("Playback stopped in PlaybackActivity")
        finish()
    }

    override fun onMirroringStarted(width: Int, height: Int) {
        Timber.d("Mirroring started in PlaybackActivity: ${width}x$height")
        showLoading(false)
    }

    override fun onMirroringStopped() {
        Timber.d("Mirroring stopped in PlaybackActivity")
        finish()
    }

    override fun onError(message: String) {
        Timber.e("Error in PlaybackActivity: $message")
        // Could show error dialog here
    }

    override fun onPositionUpdate(position: Double, duration: Double) {
        updateProgress(position, duration)
    }

    companion object {
        const val EXTRA_MIRRORING = "mirroring"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"

        private const val OVERLAY_HIDE_DELAY = 5000L
    }
}
