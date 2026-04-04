# OACP Integration — Fossify Voice Recorder

How voice control was added to Fossify Voice Recorder using the OACP Kotlin SDK.

## Overview

This integration allows users to control Voice Recorder via the Hark voice assistant. Five capabilities are exposed:

| Capability | Dispatch | What it does |
|-----------|----------|-------------|
| `start_recording` | **activity** | Opens the app and starts recording |
| `pause_recording` | broadcast | Pauses active recording (background) |
| `resume_recording` | broadcast | Resumes paused recording (background) |
| `stop_recording` | broadcast | Stops and saves recording (background) |
| `discard_recording` | broadcast | Discards recording without saving (background) |

## Why two dispatch types?

Android 14+ blocks `startActivity()` from BroadcastReceivers (Background Activity Launch restrictions). So:

- **`start_recording`** needs to open the app UI, so it uses `type=activity`. Hark calls `startActivity()` directly from the foreground — no BAL restrictions.
- **`pause/resume/stop/discard`** don't need UI — they work in the background via `type=broadcast`. The `OacpReceiver` from the SDK handles these.

## Files added

| File | Purpose |
|------|---------|
| `app/libs/oacp-android-release.aar` | OACP Kotlin SDK |
| `app/src/main/assets/oacp.json` | Capability manifest (5 capabilities with rich metadata for embedding-based matching) |
| `app/src/main/assets/OACP.md` | LLM context for disambiguation |
| `app/src/main/kotlin/.../oacp/OacpActionReceiver.kt` | Broadcast handler for background actions |

## Files modified

### `app/build.gradle.kts`
```kotlin
dependencies {
    implementation(files("libs/oacp-android-release.aar"))
    implementation("androidx.annotation:annotation:1.7.1")
}
```

### `app/src/main/AndroidManifest.xml`

Activity intent filter on MainActivity (for `start_recording`):
```xml
<activity android:name=".activities.MainActivity"
    android:launchMode="singleTask" android:exported="true">
    <!-- existing filters -->
    <intent-filter>
        <action android:name="${applicationId}.oacp.ACTION_START_RECORDING" />
        <category android:name="android.intent.category.DEFAULT" />
    </intent-filter>
</activity>
```

Receiver registration (for background actions):
```xml
<receiver android:name=".oacp.OacpActionReceiver" android:exported="true">
    <intent-filter>
        <action android:name="${applicationId}.oacp.ACTION_PAUSE_RECORDING" />
        <action android:name="${applicationId}.oacp.ACTION_RESUME_RECORDING" />
        <action android:name="${applicationId}.oacp.ACTION_STOP_RECORDING" />
        <action android:name="${applicationId}.oacp.ACTION_DISCARD_RECORDING" />
    </intent-filter>
</receiver>
```

### `app/src/main/kotlin/.../activities/MainActivity.kt`

Handle OACP intent in `onCreate()` (fresh launch) and `onNewIntent()` (singleTask redelivery):

```kotlin
// In onCreate(), after permission check:
val shouldAutoRecord = config.recordAfterLaunch ||
    intent?.action?.endsWith(".oacp.ACTION_START_RECORDING") == true

if (shouldAutoRecord && !RecorderService.isRunning) {
    Intent(this, RecorderService::class.java).apply {
        try { startService(this) } catch (_: Exception) { }
    }
}
```

```kotlin
override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handleOacpIntent()
}

private fun handleOacpIntent() {
    if (intent?.action?.endsWith(".oacp.ACTION_START_RECORDING") != true) return
    intent?.action = null  // consume so it doesn't re-trigger
    binding.viewPager.currentItem = 0  // switch to recorder tab
    if (RecorderService.isRunning) return
    Intent(this, RecorderService::class.java).apply {
        try { startService(this) } catch (_: Exception) { }
    }
}
```

**Key detail:** `onNewIntent()` is critical for `singleTask` activities. Without it, the OACP intent is silently dropped when the activity is already in memory.

## Testing with adb

```bash
# Verify capabilities are served
adb shell content read --uri "content://org.fossify.voicerecorder.debug.oacp/manifest"

# Test activity dispatch (opens app)
adb shell am start -a org.fossify.voicerecorder.debug.oacp.ACTION_START_RECORDING \
  -n org.fossify.voicerecorder.debug/org.fossify.voicerecorder.activities.MainActivity

# Test broadcast dispatch (background)
adb shell am broadcast -a org.fossify.voicerecorder.debug.oacp.ACTION_PAUSE_RECORDING \
  -p org.fossify.voicerecorder.debug
```

## SDK auto-registration

The `OacpProvider` ContentProvider is auto-registered via Android manifest merger from the SDK. No manual registration needed. It serves `oacp.json` and `OACP.md` at `content://${applicationId}.oacp/manifest` and `content://${applicationId}.oacp/context`.
