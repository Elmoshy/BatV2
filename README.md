# BatV2

AMOLED v2ray client. Trojan / VLESS / VMess / Shadowsocks, links, QR, files, subscriptions, full edit screen.

## Build (no Android Studio needed)
1. Put these files in your GitHub repo
2. Actions tab -> "Build APK" -> Run workflow
3. Download the artifact `BatV2-apk`

## One time setup: stable signing key (updates install over the old app)
Make the key once on your computer and keep the file somewhere safe:

    keytool -genkeypair -keystore bat.keystore -alias bat -keyalg RSA -keysize 2048 -validity 10000

Add 4 secrets in GitHub: Settings > Secrets and variables > Actions
- KEYSTORE_B64      the output of: base64 -w0 bat.keystore  (PowerShell: [Convert]::ToBase64String([IO.File]::ReadAllBytes("bat.keystore")))
- KEYSTORE_PASSWORD the store password you typed
- KEY_ALIAS         bat (or what you chose)
- KEY_PASSWORD      the key password

Without the secrets the workflow still builds with a temporary key (then every update needs an uninstall).

## Freeze the engine version
The workflow prints the engine commit in the run summary. Put that commit in the file `engine.ref`
and every build uses exactly that engine instead of the newest main.

## What the app does
- Paste a link and press Power: the app tests the server first (3 different test urls), pins the server
  certificate by itself when only the certificate is wrong (fake SNI, renewed cert), and connects. No dialogs.
- Health check every 25 seconds through the running tunnel. When it stops answering the app restarts it and,
  if that does not help, moves to the best other server. Also reacts to wifi/mobile changes.
- Diagnose (menu): DNS, TCP, TLS and tunnel step by step with a plain verdict. Copy the text to share it.
- Log (menu): what the app did plus the core's own log. Detailed level in Settings.
- TLS fragment switch in the edit screen (splits the TLS hello against filtering).
- Subscriptions: paste a subscription link, updates itself every 12 hours, keeps your pins.
- Apps that bypass the VPN, speed and time on the main screen.

## Limits you should know
- A restart from the background needs the VPN permission granted once (it is after the first connect). Android 12+
  may refuse starting the service from the background; the log says so.
- BatV2 itself always bypasses the VPN so its tests and downloads are direct.
- Core log needs the "Detailed" setting to show normal traffic lines.

## Layout
- core/      plain Kotlin without Android, tested by tests/CoreTest.kt
- Engine.kt  the only file touching the engine module
- Guard.kt   state, health check, restart and failover
- tests/     kotlinc app/src/main/java/com/bat/v2/core/*.kt tests/CoreTest.kt -include-runtime -d t.jar && java -cp t.jar CoreTestKt
