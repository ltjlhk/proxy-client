# Proxy Client

Cross-platform proxy client built with Flutter.

## Features
- Shadowrocket-like UI (dark theme)
- Trojan/TLS encrypted proxy support
- Real-time traffic statistics
- Node management
- Android VPN service support

## Build

### Android APK
```bash
flutter pub get
flutter build apk --release
```

### iOS IPA
```bash
cd ios && pod install
flutter build ipa --release
```

## Auto Build
Push to `main` branch to trigger GitHub Actions build.
Download artifacts from Actions tab.
