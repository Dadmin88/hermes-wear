# Build and install

## Prerequisites

Use JDK 17, Android SDK platform 35 and build tools 35.0.0, Gradle 8.11.1, and Node.js 22.13 or newer (Node 26 was used for relay validation). The relay uses built-in `node:sqlite`; Node 20 is insufficient. Watch minimum API is 30, phone minimum is 26. AGSL rendering requires API 33; older watches use a Canvas fallback. The APK includes ARMv7 and ARM64 native wake libraries, not x86.

Set `ANDROID_HOME` to your SDK and put Gradle on PATH. In a checkout:

```sh
gradle :wear:testDebugUnitTest :mobile:testDebugUnitTest :wear:assembleDebug :mobile:assembleDebug :wear:lintDebug :mobile:lintDebug
(cd relay && npm test)
```

Outputs:

- Watch: `wear/build/outputs/apk/debug/wear-debug.apk`
- Phone: `mobile/build/outputs/apk/debug/mobile-debug.apk`

Android generates a local debug signing key. Preserve that key for future updates. Both apps must use the same signing certificate for Data Layer communication. Never commit signing keys.

## Install with a computer

Enable developer options and wireless debugging on the watch. Keep watch and computer on the same reachable Wi-Fi network. The pairing port shown under **Pair new device** differs from the connection port on the main wireless debugging screen.

```sh
adb pair WATCH_IP:PAIRING_PORT
adb connect WATCH_IP:CONNECTION_PORT
adb -s WATCH_IP:CONNECTION_PORT install -r wear/build/outputs/apk/debug/wear-debug.apk
adb -s PHONE_SERIAL install -r mobile/build/outputs/apk/debug/mobile-debug.apk
```

Use the current addresses displayed by the device. Do not assume port 5555. Enter the temporary pairing code interactively.

## Install using an Android phone

Install the phone APK normally on the phone. Transfer the watch APK using [Wear Installer 2](https://freepoc.org/wear-installer-2-help-page/), installed on the phone only. It still uses wireless ADB: pair with the watch, enter the current connection port, select the watch APK from Downloads, and install. A phone APK is not the watch APK. Never publish or send your pairing codes.

`INSTALL_FAILED_UPDATE_INCOMPATIBLE` means the installed app and new APK use different signing keys. Rebuild using the original key. Uninstalling loses app data and should not be the first response. A connection timeout is a separate issue; confirm address, connection port and local-network reachability before changing router settings.

## Configure

In the companion, enter your relay's HTTPS URL, enter a fresh one-time setup code generated on the server, and tap **Pair automatically**. Test the relay, then **Sync connection to watch**. Credentials are encrypted with Android Keystore on each device. Neither a provider key nor the Hermes API key belongs in either APK.

Tailscale is optional for a publicly reachable authenticated HTTPS relay. A private tailnet-only relay requires a reachable network path from the selected transport. Installing Tailscale on a phone does not by itself prove the watch can reach a private tailnet address.
