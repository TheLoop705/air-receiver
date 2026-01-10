# Air Receiver

An open-source AirPlay receiver for Amazon Fire TV Stick and Android TV devices.

## Features

- **Video Streaming**: Play videos from iPhone/iPad via AirPlay
- **Screen Mirroring**: Mirror your iOS device screen to your TV
- **Audio Streaming**: Stream music with metadata and album art
- **Auto-Discovery**: Appears automatically in iOS AirPlay menu via Bonjour/mDNS

## Requirements

- Amazon Fire TV Stick (2nd gen or later) or Android TV device
- Android 5.1+ (API level 22+)
- iOS device on the same WiFi network

## Installation

### Sideloading on Fire TV Stick

1. On your Fire TV, go to **Settings > My Fire TV > Developer Options**
2. Enable **ADB Debugging** and **Apps from Unknown Sources**
3. Install a sideloading app like "Downloader" from the Amazon App Store
4. Download the APK or use ADB to install:

```bash
adb connect <fire-tv-ip>:5555
adb install air-receiver.apk
```

### Building from Source

```bash
# Clone the repository
git clone https://github.com/yourusername/air-receiver.git
cd air-receiver

# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease
```

## Usage

1. Launch "Air Receiver" on your Fire TV
2. The app will display its IP address and wait for connections
3. On your iPhone/iPad:
   - Open Control Center
   - Tap "Screen Mirroring"
   - Select "Air Receiver" from the list
4. For video streaming:
   - Play a video in Safari or any app
   - Tap the AirPlay icon
   - Select "Air Receiver"

## Architecture

The app implements the AirPlay protocol stack:

- **mDNS/Bonjour**: Service discovery using JmDNS
- **HTTP Server (Port 7000)**: Main AirPlay control protocol
- **RTSP Server (Port 5000)**: RAOP audio streaming
- **Mirroring Server (Port 7100)**: H.264 screen mirroring data

## Technology Stack

- **Language**: Kotlin
- **Networking**: Netty
- **Media Playback**: ExoPlayer (Media3)
- **Service Discovery**: JmDNS
- **Crypto**: Bouncy Castle

## Known Limitations

- Some DRM-protected content may not play (FairPlay encryption)
- AirPlay 2 features (multi-room audio) not fully supported
- Performance depends on network quality and device capabilities

## Troubleshooting

### Device not appearing in AirPlay list

1. Ensure both devices are on the same WiFi network
2. Check that your network allows multicast/mDNS traffic
3. Restart the app on Fire TV
4. Restart WiFi on your iOS device

### Video playback issues

1. Try a different video source
2. Check network bandwidth
3. Some streaming services block AirPlay to non-Apple devices

### Screen mirroring lag

1. Reduce distance between devices and router
2. Use 5GHz WiFi if available
3. Close other apps using network bandwidth

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Acknowledgments

- [airplay2-receiver](https://github.com/openairplay/airplay2-receiver) - Protocol research
- [JmDNS](https://github.com/jmdns/jmdns) - mDNS implementation
- [ExoPlayer](https://github.com/google/ExoPlayer) - Media playback

## Disclaimer

This project is not affiliated with or endorsed by Apple Inc. AirPlay is a trademark of Apple Inc. This implementation is based on publicly available protocol documentation and reverse engineering for interoperability purposes.
