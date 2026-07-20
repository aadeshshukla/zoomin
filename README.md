# Gesture Zoom Camera

A minimal Android camera app that lets the user zoom in and out by pinching
their fingers in front of the back camera. Powered by **CameraX** for the
preview / analysis pipeline and **Google ML Kit's on-device Hand Detection**
(`STREAM_MODE`) for landmark tracking — both bundled so the app is fully
self-contained and works offline.

> Pinch fingers **apart** to zoom **in**.  
> Pinch fingers **together** to zoom **out**.

The pinch distance is normalized by the user's hand size (wrist ↔
middle-finger-MCP) so the gesture stays consistent regardless of how close
or far the hand is from the camera.

---

## Tech stack

| Component       | Choice                                           |
| --------------- | ------------------------------------------------ |
| Language        | Kotlin 1.9.24                                    |
| UI              | Android Views + XML layouts (no Compose)         |
| Camera pipeline | CameraX 1.3.4 (core / camera2 / lifecycle / view)|
| Hand tracking   | ML Kit 16.0.1 on-device `hand-detection`         |
| Build           | AGP 8.5.2, Gradle 8.7, Kotlin DSL                |
| Min/Target SDK  | 24 / 34                                          |
| Java target     | 17                                               |

---

## Building

### Prereqs
* **Android Studio Hedgehog (2023.1) or newer**
* An Android device or emulator running **API 24 (Nougat) or higher**
* The Android SDK Platform 34 installed (Studio offers this during onboarding)

### From Android Studio
1. `File → Open… → select the project root` (`gesture-zoom-camera/`)
2. If `gradlew` is missing the binary, run `gradle wrapper --gradle-version 8.7`
   from a terminal in the project root, or simply let Studio sync — it will
   fetch the missing Gradle distribution on first sync.
3. Hit `Run ▶` with a connected device selected.

### From the command line
```bash
# 1. (only first time) make sure gradle wrapper jar exists
gradle wrapper --gradle-version 8.7

# 2. build
./gradlew assembleDebug

# 3. install on the first connected device
./gradlew installDebug
```

The signed-debug APK lands at
`app/build/outputs/apk/debug/app-debug.apk`.

---

## How the gesture is mapped to zoom

1. ML Kit returns 21 hand landmarks per detected hand.
2. We compute  
   `pinchDist  = distance(THUMB_TIP[4], INDEX_FINGER_TIP[8])`  
   `handSize   = distance(WRIST[0], MIDDLE_FINGER_MCP[9])`  
   `pinchNorm  = pinchDist / handSize` (typically **0.05** for a closed fist,
   up to **≈0.60** for a wide stretch).
3. `pinchNorm` is linearly mapped onto the device's reported zoom range  
   `[minZoom, maxZoom]` (typically **1.0× → 4.0×**).
4. The raw target is filtered through a single-pole **EMA with α = 0.15**
   (~6-frame time constant at 30 fps) so the camera doesn't snap on jitter.
5. If no hand is detected for **>1000 ms** the smoothed zoom decays back
   toward `minZoom` at **0.05× per 50 ms** tick.

| Symbol             | Value | Where                                            |
| ------------------ | ----- | ------------------------------------------------ |
| `PINCH_MIN`        | 0.05  | `ZoomGestureController.kt`                       |
| `PINCH_MAX`        | 0.60  | `ZoomGestureController.kt`                       |
| `EMA_ALPHA`        | 0.15  | `ZoomGestureController.kt`                       |
| `HAND_LOST_TIMEOUT_MS` | 1000 | `ZoomGestureController.kt`                    |
| `DECAY_PER_FRAME`  | 0.05  | `ZoomGestureController.kt`                       |
| `DECAY_TICK_MS`    | 50    | `ZoomGestureController.kt`                       |

---

## Permissions

* **`CAMERA`** — requested at first launch via the new AndroidX
  `ActivityResultContracts.RequestPermission` API.
* **`uses-feature android.hardware.camera.any required="true"`** — filters the
  app out of Play Store listings for camera-less devices.

If the user denies, a full-screen "Camera permission required" panel is
shown with a **Grant permission** retry button. The app never crashes.

---

## Project layout

```
gesture-zoom-camera/
├── settings.gradle.kts            # pluginManagement + repositories
├── build.gradle.kts               # root, plugins block only
├── gradle.properties              # JVM args / AndroidX / parallel
├── gradle/
│   ├── libs.versions.toml         # version catalog
│   └── wrapper/gradle-wrapper.properties
├── app/
│   ├── build.gradle.kts           # namespace, SDK, deps
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/example/gesturezoom/
│       │   ├── MainActivity.kt            # permission flow, CameraX bind, UI overlay
│       │   ├── HandLandmarkAnalyzer.kt    # ImageAnalysis.Analyzer + ML Kit pipeline
│       │   └── ZoomGestureController.kt   # pinch ratio → CameraX zoomRatio
│       └── res/                           # layout/values/xml/drawable/mipmap
└── README.md
```

---

## Known limitations

* **Requires Android 7.0 (API 24) or newer.** ML Kit's `hand-detection`
  bundled model is large and the API surface assumes a modern Android.
* **Back camera required** — front cameras usually lack a `zoomRatio`
  range we can read from `CameraInfo.zoomState`. Devices without a reported
  max zoom fall back to `4.0×` so the gesture still does something.
* **Decent lighting needed for ML Kit** — the on-device model relies on
  enough contrast between the hand and the background. In dim light the
  detector will silently emit zero-hand frames; after 1 s of silence the
  zoom decays back to 1.0× automatically.
* **One hand at a time** — when multiple hands are visible we always
  operate on `hands.first()`, because mixing two pinch signals is
  unpredictable.
* **Portrait only** — `AndroidManifest` locks the activity to portrait so
  the camera sensor orientation stays stable. Switching to landscape would
  require reworking the rotation we pass to `InputImage.fromMediaImage`.

---


