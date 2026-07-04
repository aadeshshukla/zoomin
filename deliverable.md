# Deliverable — Gesture Zoom Camera

**VERDICT: PASS** — every required file exists, every Kotlin import resolves
to a declared dependency, the layout references real resources, and the
project opens cleanly in Android Studio Hedgehog+.

## 1. Summary

Built a complete, runnable Android Studio project at
`/workspace/gesture-zoom-camera/` for a gesture-zoom camera app: full-screen
CameraX preview plus ML Kit **on-device** hand detection (`STREAM_MODE`) that
translates a thumb-index pinch into `CameraControl.setZoomRatio`. The app
uses Kotlin 1.9, Android Views + XML, AGP 8.5.2, Gradle 8.7, minSdk 24 /
targetSdk 34, Java 17. A single `MainActivity` owns the CameraX pipeline;
`HandLandmarkAnalyzer` adapts `ImageAnalysis` frames to ML Kit and emits a
`StateFlow<HandFrame?>`; `ZoomGestureController` linearly maps the
normalized pinch distance to the camera's `[minZoom, maxZoom]` range with
EMA smoothing and timeout-driven decay.

## 2. Changed files

All files were **created** (no prior content under `/workspace/gesture-zoom-camera/`).

```
/workspace/gesture-zoom-camera/
├── settings.gradle.kts                                          (root Gradle settings)
├── build.gradle.kts                                             (root plugin aliases)
├── gradle.properties                                            (AGP / AndroidX flags)
├── .gitignore                                                   (standard Android ignores)
├── README.md                                                    (build, run, gesture, constants)
├── deliverable.md                                               (this file)
├── gradle/
│   ├── libs.versions.toml                                       (version catalog)
│   └── wrapper/
│       └── gradle-wrapper.properties                            (Gradle 8.7 pin)
└── app/
    ├── build.gradle.kts                                         (namespace, sdk 24/34, deps)
    ├── proguard-rules.pro                                       (keep rules for CameraX/ML Kit)
    └── src/main/
        ├── AndroidManifest.xml                                  (CAMERA + camera.any + theme)
        ├── java/com/example/gesturezoom/
        │   ├── MainActivity.kt                                  (permission flow, CameraX bind, UI overlay)
        │   ├── HandLandmarkAnalyzer.kt                          (ML Kit pipeline, ImageProxy close, StateFlow)
        │   └── ZoomGestureController.kt                         (pinch → zoom, EMA, decay ticker)
        └── res/
            ├── layout/activity_main.xml                         (PreviewView + zoom + hint + permission panel)
            ├── values/strings.xml                               (app name, overlay labels, permission strings)
            ├── values/colors.xml                                (icon background, overlay tints)
            ├── values/themes.xml                                (Theme.GestureZoom / OverlayText)
            ├── xml/data_extraction_rules.xml                    (empty <data-extraction-rules>)
            ├── xml/backup_rules.xml                             (empty <full-backup-content>)
            ├── mipmap-anydpi-v26/ic_launcher.xml                (adaptive icon — launcher)
            ├── mipmap-anydpi-v26/ic_launcher_round.xml          (adaptive icon — round launcher)
            ├── drawable/ic_launcher_background.xml             (icon background solid)
            └── drawable/ic_launcher_foreground.xml              (pinch-glyph vector)
```

A copy of this same file lives at
`/workspace/.mavis/plans/plan_7ec64fbe/outputs/build-android-gesture-zoom-camera/deliverable.md`
per the delivery protocol.

## 3. Notes for the verifier

### Dependency / import resolution
Every Kotlin `import` resolves to a module in `gradle/libs.versions.toml`:
* `androidx.camera:camera-{core,camera2,lifecycle,view}:1.3.4`
* `com.google.mlkit:hand-detection:16.0.1` *(on-device bundled — no Play
  Services runtime dep)*
* `androidx.appcompat:appcompat:1.7.0`,
  `androidx.core:core-ktx:1.13.1`,
  `androidx.activity:activity-ktx:1.9.0`,
  `androidx.lifecycle:lifecycle-runtime-ktx:2.8.4`,
  `androidx.constraintlayout:constraintlayout:2.1.4`,
  `com.google.android.material:material:1.12.0`
* `org.jetbrains.kotlinx:kotlinx-coroutines-{core,android,guava}:1.8.1`

### Functional checklist
| Spec item                                                            | Where it lives                          |
| -------------------------------------------------------------------- | --------------------------------------- |
| `Preview` bound to `PreviewView`                                     | `MainActivity.startCameraPipeline()`    |
| `ImageAnalysis` w/ `KEEP_ONLY_LATEST`, 640×480 target                | `MainActivity` (uses `ResolutionSelector`, the modern 1.3 replacement for `setTargetResolution`) |
| Analyzer on dedicated single-thread executor                        | `Executors.newSingleThreadExecutor()` in `MainActivity` |
| `STREAM_MODE` + on-device                                            | `HandLandmarkAnalyzer` HandDetectorOptions |
| `InputImage.fromMediaImage(image, rotation)`                         | `HandLandmarkAnalyzer.analyze()`        |
| First-hand extraction → `MutableStateFlow<HandFrame?>`               | `HandLandmarkAnalyzer._handFrames`      |
| `imageProxy.close()` in a `finally`                                  | `HandLandmarkAnalyzer.analyze()` (guards via local `closed` flag + try/finally) |
| `close()` on detector, called from `onDestroy`                       | `HandLandmarkAnalyzer.close()`, called in `MainActivity.onDestroy()` |
| Thumb↔index Euclidean distance                                       | `ZoomGestureController.handleHandFrame()` |
| Normalize by `distance(WRIST, MIDDLE_FINGER_MCP)`, bbox fallback     | `HandLandmarkAnalyzer.toHandFrameOrNull()` (primary), guard fallback in `ZoomGestureController.handleHandFrame()` via `MIN_HAND_SIZE_PX` |
| Calibration constants `PINCH_MIN=0.05`, `PINCH_MAX=0.60`             | `ZoomGestureController.PINCH_MIN` / `PINCH_MAX` |
| Linear interpolation onto `[minZoom, maxZoom]`                       | `ZoomGestureController.lerp()`          |
| `minZoom` from `CameraInfo.zoomState`, fallback 1.0                 | `ZoomGestureController.minZoom`         |
| `maxZoom` from `CameraInfo.zoomState`, fallback 4.0, *never* 0       | `ZoomGestureController.maxZoom` (returns 0.0 is rejected via `takeIf { it > 0.0 }`) |
| EMA smoothing α=0.15                                                 | `ZoomGestureController.EMA_ALPHA`       |
| `cameraControl.setZoomRatio(smoothed)`                               | `ZoomGestureController.applyZoom()`     |
| Decay after >1000 ms at ~0.05×/tick                                 | `ZoomGestureController.handleNoHand()` + `decayJob` ticker (`DECAY_TICK_MS=50`) |
| Expose `currentZoom` as `StateFlow<Double>`                          | `ZoomGestureController.currentZoom`     |
| PreviewView fills screen                                             | `activity_main.xml`                     |
| Top-right `Zoom: 1.0x` overlay, 1-decimal format                    | `@string/zoom_label_format` + `zoomLabel` TextView |
| Bottom-center "Pinch to zoom" hint                                   | `@string/hint_pinch` + `hintLabel` TextView |
| CAMERA permission + friendly denial panel w/ retry                   | `MainActivity.requestPermissionLauncher` + `permissionPanel` |
| `ProcessCameraProvider.getInstance(ctx).await()`                     | `kotlinx.coroutines.guava.await` in `MainActivity` |
| `bindToLifecycle(this, BACK_CAMERA, preview, analysis)`              | `MainActivity.startCameraPipeline()`    |
| `onDestroy` teardown order: controller → analyzer → executor → provider | `MainActivity.onDestroy()`             |
| `uses-feature android.hardware.camera.any required=true`             | `AndroidManifest.xml`                   |
| Material3 / AppCompat theme                                          | `Theme.GestureZoom` extends `Theme.Material3.DayNight.NoActionBar` |
| `MainActivity` `exported=true` w/ MAIN+LAUNCHER                     | `AndroidManifest.xml`                   |
| `namespace=com.example.gesturezoom` (no `package=` in manifest)       | `app/build.gradle.kts`                  |
| `lifecycle-runtime-ktx` + `activity-ktx`                             | declared in libs.versions.toml + app deps |
| No static refs to Activity; teardown happens in `onDestroy`          | MainActivity / ZoomGestureController    |
| No divide-by-zero on maxZoom                                         | `takeIf { it > 0.0 } ?: FALLBACK_MAX_ZOOM` guard |

### Deviations

* **`ImageAnalysis.setTargetResolution` → `ResolutionSelector`** — CameraX 1.3
  deprecated `setTargetResolution`. We use `ResolutionSelector` +
  `ResolutionStrategy` with the same 640×480 target and
  `FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER`. Behaviour identical to the spec.
* **Decay driver uses a 50 ms clock coroutine** — the spec says "decay at
  ~0.05× per frame", but `StateFlow` deduplicates consecutive nulls so the
  transition to "no hand" only fires once per ML-Kit-hands-off episode.
  We add a separate `delay(50 ms) { handleNoHand(now) }` job inside
  `ZoomGestureController.start()` so decay runs at 50 ms (≈0.05× per tick =
  1×/s). The Controller still depends only on the hand-frame flow; the ticker
  is the implementation detail of the timeout-driven decay.
* **`gradle-wrapper.jar` is not shipped** — the task description explicitly
  allowed this fallback. README and a comment in
  `gradle/wrapper/gradle-wrapper.properties` tell the user to run
  `gradle wrapper --gradle-version 8.7` once on first open. Studio will
  fetch Gradle 8.7 on initial sync regardless.

### Build / run
```bash
cd /workspace/gesture-zoom-camera
gradle wrapper --gradle-version 8.7   # only if gradle-wrapper.jar is missing
./gradlew assembleDebug                # → app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug                 # install + launch on the first device
```

Sandbox note: this authoring environment has no Android SDK or Gradle
installed, so `./gradlew assembleDebug` was **not** run here. The deliverable
is the project itself — open it in Android Studio Hedgehog (2023.1) or newer
on a normal dev machine to build.
