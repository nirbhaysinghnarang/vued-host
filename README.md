# Vued Host Android Tablet

Android room tablet for the Vued office recorder stack.

The tablet is intentionally narrow:

- Records UMA-8 room audio and keeps an ambient rolling buffer.
- Queues meeting creates, meeting slice uploads, and ambient slice uploads with room/microphone metadata.
- Provides speaker-enrollment capture from the same room audio source.
- Provides a narrow ambient decrypt assist so the server can run ambient judgment on encrypted transcript windows.

It is not the desktop decrypt gateway and should not become a general-purpose plaintext bridge.

## Runtime Flow

1. User signs in and selects an organization room.
2. Room/microphone IDs are stored in `RoomConfig`.
3. Meeting start queues `POST /api/v1/meetings` with `roomId` and `microphoneId`.
4. Meeting stop exports the window from the rolling buffer and uploads it as an audio slice.
5. The backend transcribes, speaker-identifies, summarizes, updates ACLs, and syncs encrypted payloads to desktop clients.

Ambient capture runs alongside meetings. Ambient audio slices are uploaded normally; ambient segmentation can request decrypted transcript windows from the tablet through the narrow assist described below.

## Ambient Decrypt Assist

`AmbientProcessor` only handles ambient segmentation support:

- Polls `GET /api/v1/ambient/window?roomId=...`.
- Fetches encrypted ambient transcript events for that requested window.
- Decrypts locally after the tablet is unlocked with the ambient passphrase.
- Posts plaintext window events to `POST /api/v1/ambient/process`.

This is deliberately scoped to ambient candidates. It does not provide desktop record sync, file browsing, or broad decryption services.

## Configuration

Backend and Supabase constants live in:

```text
app/src/main/java/com/nsn8/vued/VuedConfig.kt
```

Current dev API:

```text
https://vued-office-api-dev.onrender.com
```

The Supabase anon key is public by design; RLS and backend auth protect data. Never put a service-role key in the app.

## Build / Verify

```bash
./gradlew :app:compileDebugKotlin
```

For a debug APK:

```bash
./gradlew :app:assembleDebug
```

## Kiosk Mode

Device-owner kiosk mode can be enabled on a freshly provisioned device:

```bash
adb shell dpm set-device-owner com.nsn8.vued/.DeviceAdminReceiver
```

The app exposes start/stop lock-task controls once device owner is configured.
