package com.airreceiver.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.airreceiver.R
import com.airreceiver.databinding.ActivityMainBinding
import com.airreceiver.service.AirPlayService
import timber.log.Timber

/**
 * Main activity showing the idle/waiting screen
 * Displays device name, IP address, and connection status
 */
class MainActivity : AppCompatActivity(), AirPlayService.AirPlayEventListener {

    private lateinit var binding: ActivityMainBinding

    private var airPlayService: AirPlayService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as AirPlayService.AirPlayBinder
            airPlayService = binder.getService()
            airPlayService?.setEventListener(this@MainActivity)
            serviceBound = true

            // Update UI with current state
            airPlayService?.getIpAddress()?.let { ip ->
                updateIpAddress(ip)
            }

            Timber.d("Service connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            airPlayService?.setEventListener(null)
            airPlayService = null
            serviceBound = false
            Timber.d("Service disconnected")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Hide system UI for full-screen TV experience
        hideSystemUI()

        // Start the AirPlay service
        startAirPlayService()
    }

    override fun onStart() {
        super.onStart()
        // Bind to the service
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
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Handle remote control input
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                // Show exit confirmation or minimize
                moveTaskToBack(true)
                true
            }
            KeyEvent.KEYCODE_MENU -> {
                // Show settings
                // TODO: Implement settings screen
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun startAirPlayService() {
        val serviceIntent = Intent(this, AirPlayService::class.java).apply {
            action = AirPlayService.ACTION_START
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
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

    private fun updateIpAddress(ip: String) {
        runOnUiThread {
            binding.ipAddressText.text = getString(R.string.ip_address, ip)
        }
    }

    private fun updateStatus(status: String) {
        runOnUiThread {
            binding.statusText.text = status
        }
    }

    private fun showWaiting() {
        runOnUiThread {
            binding.waitingIndicator.visibility = View.VISIBLE
            binding.statusText.text = getString(R.string.waiting_for_connection)
        }
    }

    private fun hideWaiting() {
        runOnUiThread {
            binding.waitingIndicator.visibility = View.GONE
        }
    }

    // AirPlayService.AirPlayEventListener implementation

    override fun onServiceStarted(ipAddress: String) {
        Timber.d("Service started, IP: $ipAddress")
        updateIpAddress(ipAddress)
        showWaiting()
    }

    override fun onServiceStopped() {
        Timber.d("Service stopped")
        updateStatus(getString(R.string.service_stopped))
        hideWaiting()
    }

    override fun onClientConnected(clientName: String) {
        Timber.d("Client connected: $clientName")
        updateStatus(getString(R.string.connected_to, clientName))
        hideWaiting()
    }

    override fun onClientDisconnected() {
        Timber.d("Client disconnected")
        showWaiting()
    }

    override fun onPlaybackStarted(url: String) {
        Timber.d("Playback started: $url")
        // Launch playback activity
        val intent = Intent(this, PlaybackActivity::class.java)
        startActivity(intent)
    }

    override fun onPlaybackStopped() {
        Timber.d("Playback stopped")
    }

    override fun onMirroringStarted(width: Int, height: Int) {
        Timber.d("Mirroring started: ${width}x$height")
        updateStatus(getString(R.string.mirroring_active))

        // Launch playback activity for mirroring
        val intent = Intent(this, PlaybackActivity::class.java).apply {
            putExtra(PlaybackActivity.EXTRA_MIRRORING, true)
            putExtra(PlaybackActivity.EXTRA_WIDTH, width)
            putExtra(PlaybackActivity.EXTRA_HEIGHT, height)
        }
        startActivity(intent)
    }

    override fun onMirroringStopped() {
        Timber.d("Mirroring stopped")
        showWaiting()
    }

    override fun onError(message: String) {
        Timber.e("Error: $message")
        updateStatus("Error: $message")
    }

    override fun onPositionUpdate(position: Double, duration: Double) {
        // Not needed in main activity
    }
}
