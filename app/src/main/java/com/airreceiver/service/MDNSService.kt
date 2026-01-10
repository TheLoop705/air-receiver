package com.airreceiver.service

import android.content.Context
import android.net.wifi.WifiManager
import com.airreceiver.protocol.AirPlayConstants
import com.airreceiver.protocol.AirPlayConstants.Features
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/**
 * Handles mDNS/Bonjour service registration for AirPlay discovery
 */
class MDNSService(private val context: Context) {

    private var jmdns: JmDNS? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var airplayServiceInfo: ServiceInfo? = null
    private var raopServiceInfo: ServiceInfo? = null

    val deviceId: String by lazy { generateDeviceId() }
    val deviceName: String = "Air Receiver"

    /**
     * Start mDNS service and register AirPlay services
     */
    suspend fun start(airplayPort: Int, raopPort: Int) = withContext(Dispatchers.IO) {
        try {
            Timber.d("Starting mDNS service...")

            // Acquire multicast lock for WiFi
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifiManager.createMulticastLock("airreceiver_mdns").apply {
                setReferenceCounted(true)
                acquire()
            }

            val localAddress = getLocalIpAddress()
            Timber.d("Local IP address: ${localAddress.hostAddress}")

            jmdns = JmDNS.create(localAddress, localAddress.hostAddress)

            // Register AirPlay service (_airplay._tcp)
            registerAirPlayService(airplayPort)

            // Register RAOP service (_raop._tcp) for audio
            registerRAOPService(raopPort)

            Timber.i("mDNS services registered successfully")
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to start mDNS service")
            false
        }
    }

    /**
     * Register the main AirPlay service
     */
    private fun registerAirPlayService(port: Int) {
        val features = Features.DEFAULT_FEATURES

        // TXT record properties for AirPlay
        val txtRecord = mapOf(
            "deviceid" to deviceId,
            "features" to "0x${features.toString(16)}",
            "flags" to "0x44",
            "model" to AirPlayConstants.MODEL,
            "protovers" to AirPlayConstants.PROTOCOL_VERSION,
            "srcvers" to AirPlayConstants.SOURCE_VERSION,
            "vv" to "2",
            "pk" to generatePublicKey(),
            "pi" to generatePairingId()
        )

        airplayServiceInfo = ServiceInfo.create(
            AirPlayConstants.AIRPLAY_SERVICE_TYPE,
            deviceName,
            port,
            0,
            0,
            txtRecord
        )

        jmdns?.registerService(airplayServiceInfo)
        Timber.d("Registered AirPlay service on port $port")
    }

    /**
     * Register RAOP (Remote Audio Output Protocol) service for audio streaming
     */
    private fun registerRAOPService(port: Int) {
        val features = Features.DEFAULT_FEATURES

        // RAOP service name format: <device_id>@<device_name>
        val raopName = "${deviceId.replace(":", "")}@$deviceName"

        // TXT record for RAOP
        val txtRecord = mapOf(
            "ch" to "2",                    // Audio channels
            "cn" to "0,1,2,3",              // Audio codecs (PCM, ALAC, AAC, AAC-ELD)
            "da" to "true",                 // Digest authentication
            "et" to "0,3,5",                // Encryption types
            "ft" to "0x${features.toString(16)}",
            "md" to "0,1,2",                // Metadata types
            "pw" to "false",                // Password required
            "sm" to "false",                // Supports mirroring
            "sr" to "44100",                // Sample rate
            "ss" to "16",                   // Sample size
            "sv" to "false",                // Supports volume
            "tp" to "UDP",                  // Transport protocol
            "txtvers" to "1",
            "sf" to "0x44",                 // Status flags
            "am" to AirPlayConstants.MODEL,
            "vs" to AirPlayConstants.SOURCE_VERSION,
            "vn" to "65537",
            "pk" to generatePublicKey()
        )

        raopServiceInfo = ServiceInfo.create(
            AirPlayConstants.RAOP_SERVICE_TYPE,
            raopName,
            port,
            0,
            0,
            txtRecord
        )

        jmdns?.registerService(raopServiceInfo)
        Timber.d("Registered RAOP service on port $port")
    }

    /**
     * Stop mDNS service and unregister all services
     */
    suspend fun stop() = withContext(Dispatchers.IO) {
        try {
            Timber.d("Stopping mDNS service...")

            airplayServiceInfo?.let { jmdns?.unregisterService(it) }
            raopServiceInfo?.let { jmdns?.unregisterService(it) }

            jmdns?.unregisterAllServices()
            jmdns?.close()
            jmdns = null

            multicastLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
            multicastLock = null

            Timber.i("mDNS service stopped")
        } catch (e: Exception) {
            Timber.e(e, "Error stopping mDNS service")
        }
    }

    /**
     * Get the local IP address of the device
     */
    fun getLocalIpAddress(): InetAddress {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (networkInterface in interfaces) {
                // Skip loopback and down interfaces
                if (networkInterface.isLoopback || !networkInterface.isUp) continue

                val addresses = Collections.list(networkInterface.inetAddresses)
                for (addr in addresses) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error getting local IP address")
        }

        return InetAddress.getLocalHost()
    }

    /**
     * Generate a unique device ID (MAC address format)
     */
    private fun generateDeviceId(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (networkInterface in interfaces) {
                if (networkInterface.isLoopback || !networkInterface.isUp) continue

                val mac = networkInterface.hardwareAddress
                if (mac != null && mac.isNotEmpty()) {
                    return mac.joinToString(":") { byte ->
                        String.format("%02X", byte)
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error generating device ID")
        }

        // Fallback to a random ID if we can't get MAC
        return "AA:BB:CC:DD:EE:FF"
    }

    /**
     * Generate a public key placeholder (Ed25519)
     * In a real implementation, this would be generated and stored securely
     */
    private fun generatePublicKey(): String {
        // 32-byte Ed25519 public key encoded as hex
        // This is a placeholder - real implementation needs proper key generation
        return "b07727d6f6cd6e08b58c846d9b855b0b9a8b8f6a3c7c8d9e0f1a2b3c4d5e6f70"
    }

    /**
     * Generate a pairing ID (UUID format)
     */
    private fun generatePairingId(): String {
        return java.util.UUID.randomUUID().toString().uppercase()
    }

}
