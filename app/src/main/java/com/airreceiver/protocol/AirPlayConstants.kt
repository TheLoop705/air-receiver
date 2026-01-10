package com.airreceiver.protocol

/**
 * AirPlay protocol constants and feature flags
 * Based on reverse-engineered AirPlay 2 protocol from airplay2-receiver
 */
object AirPlayConstants {

    // Default ports
    const val AIRPLAY_PORT = 7000
    const val RAOP_PORT = 5000
    const val AIRPLAY_MIRROR_PORT = 7100
    const val TIMING_PORT = 6002
    const val CONTROL_PORT = 6001
    const val DATA_PORT = 6000

    // Service types for mDNS
    const val AIRPLAY_SERVICE_TYPE = "_airplay._tcp.local."
    const val RAOP_SERVICE_TYPE = "_raop._tcp.local."

    // AirPlay feature flags (64-bit bitmask)
    object Features {
        const val VIDEO = 1L shl 0                    // 0x01
        const val PHOTO = 1L shl 1                    // 0x02
        const val VIDEO_FAIR_PLAY = 1L shl 2          // 0x04
        const val VIDEO_VOLUME_CONTROL = 1L shl 3    // 0x08
        const val VIDEO_HTTP_LIVE_STREAMS = 1L shl 4 // 0x10
        const val SLIDESHOW = 1L shl 5               // 0x20
        const val SCREEN = 1L shl 7                   // 0x80
        const val SCREEN_ROTATE = 1L shl 8           // 0x100
        const val AUDIO = 1L shl 9                    // 0x200
        const val AUDIO_REDUNDANT = 1L shl 11        // 0x800
        const val FPS_LOW_LATENCY = 1L shl 12        // 0x1000
        const val AUDIO_META_COVERS = 1L shl 15      // 0x8000
        const val AUDIO_META_PROGRESS = 1L shl 16    // 0x10000
        const val AUDIO_META_TXT_DAAP = 1L shl 17    // 0x20000
        const val AUTHENTICATION_4 = 1L shl 14       // 0x4000
        const val AUTHENTICATION_1 = 1L shl 23       // 0x800000
        const val HAS_UNIFIED_ADVERTISER = 1L shl 30 // 0x40000000
        const val IS_CARRIER_PLAY = 1L shl 32        // 0x100000000
        const val SUPPORTS_CACHING = 1L shl 33       // 0x200000000
        const val SUPPORTS_LEGACY_PAIRING = 1L shl 27 // 0x8000000
        const val SUPPORTS_SYSTEM_PAIRING = 1L shl 38 // 0x4000000000
        const val SUPPORTS_AIRPLAY_VIDEO_V2 = 1L shl 45 // For FairPlay

        // Combined feature set for our receiver
        val DEFAULT_FEATURES = VIDEO or PHOTO or VIDEO_VOLUME_CONTROL or
            VIDEO_HTTP_LIVE_STREAMS or SCREEN or SCREEN_ROTATE or
            AUDIO or AUDIO_META_COVERS or AUDIO_META_PROGRESS or
            AUTHENTICATION_4 or SUPPORTS_LEGACY_PAIRING
    }

    // Status flags
    object Status {
        const val IDLE = 0
        const val LOADING = 1
        const val PLAYING = 2
        const val PAUSED = 3
        const val STOPPED = 4
    }

    // Device model identifiers
    const val MODEL = "AppleTV5,3"
    const val SOURCE_VERSION = "377.40.00"
    const val PROTOCOL_VERSION = "1.1"
    const val OS_BUILD_VERSION = "18L203"

    // PList keys
    object PlistKeys {
        const val DEVICE_ID = "deviceid"
        const val FEATURES = "features"
        const val MODEL = "model"
        const val PROTOCOL_VERSION = "protovers"
        const val SOURCE_VERSION = "srcvers"
        const val VV = "vv"
        const val OS_BUILD_VERSION = "osBuildVersion"
        const val PI = "pi"
        const val PK = "pk"
        const val FLAGS = "flags"
        const val NAME = "name"
        const val DEVICE_ID_KEY = "deviceId"
        const val SEED = "seed"
        const val STATUS_FLAGS = "statusFlags"
    }

    // HTTP Endpoints
    object Endpoints {
        const val INFO = "/info"
        const val PLAY = "/play"
        const val SCRUB = "/scrub"
        const val RATE = "/rate"
        const val STOP = "/stop"
        const val PLAYBACK_INFO = "/playback-info"
        const val SERVER_INFO = "/server-info"
        const val SLIDESHOW_FEATURES = "/slideshow-features"
        const val PHOTO = "/photo"
        const val GETPROPERTY = "/getProperty"
        const val SETPROPERTY = "/setProperty"
        const val PAIR_SETUP = "/pair-setup"
        const val PAIR_VERIFY = "/pair-verify"
        const val PAIR_PIN_START = "/pair-pin-start"
        const val FP_SETUP = "/fp-setup"
        const val FP_SETUP2 = "/fp-setup2"
        const val FEEDBACK = "/feedback"
        const val COMMAND = "/command"
        const val STREAM = "/stream"
        const val EVENTS = "/events"
        const val AUDIO_INIT = "/audioInit"
        const val VOLUME = "/volume"
    }

    // RTSP Methods
    object RTSPMethods {
        const val OPTIONS = "OPTIONS"
        const val ANNOUNCE = "ANNOUNCE"
        const val SETUP = "SETUP"
        const val RECORD = "RECORD"
        const val PAUSE = "PAUSE"
        const val FLUSH = "FLUSH"
        const val TEARDOWN = "TEARDOWN"
        const val GET_PARAMETER = "GET_PARAMETER"
        const val SET_PARAMETER = "SET_PARAMETER"
        const val POST = "POST"
        const val GET = "GET"
    }

    // TXT record keys for mDNS
    object TxtRecordKeys {
        const val DEVICE_ID = "deviceid"
        const val FEATURES = "features"
        const val FLAGS = "flags"
        const val MODEL = "model"
        const val PROTOCOL_VERSION = "protovers"
        const val SOURCE_VERSION = "srcvers"
        const val VV = "vv"
        const val PK = "pk"
        const val PI = "pi"
        const val GCGL = "gcgl"
        const val GCDID = "gid"
        const val PI_ENCODED = "pi"
    }

    // Pairing/Auth related
    object Auth {
        const val PAIRING_GUID_LENGTH = 16
        const val ED25519_KEY_LENGTH = 32
        const val X25519_KEY_LENGTH = 32
        const val CHACHA20_POLY1305_TAG_LENGTH = 16
    }
}
