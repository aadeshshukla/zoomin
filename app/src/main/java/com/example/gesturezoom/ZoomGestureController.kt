package com.example.gesturezoom

import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Consumes [HandFrame]s from a [HandLandmarkAnalyzer] and translates the
 * user's thumb-to-index pinch into a CameraX `zoomRatio` via linear
 * interpolation, with EMA smoothing and timeout-driven decay.
 *
 * ### Pinch → zoom mapping
 *
 * `pinchNorm = distance(thumbTip, indexTip) / handSize` is roughly:
 *  * `~0.05` for a closed fist
 *  * `~0.60` for a wide stretch
 *
 * We linearly remap that range onto `[minZoom, maxZoom]`. The bounds are
 * exposed as constants so they can be tuned for a future "calibration" UI.
 *
 * ### Smoothing
 *
 * The raw target zoom goes through a single-pole EMA with alpha 0.15 to
 * keep the camera from snapping when the detector jitters between frames.
 *
 * ### Decay
 *
 * When no hand has been reported for more than [HAND_LOST_TIMEOUT_MS], the
 * smoothed zoom decays back toward `minZoom` at [DECAY_PER_FRAME] zoom units
 * per detection cycle.
 *
 * Threading: call [start] from the main thread (`lifecycleScope` is bound to
 * the lifecycle). CameraX's `setZoomRatio` posts its underlying command to
 * the camera thread, so this class is safe to use from a coroutine running
 * on `Dispatchers.Main.immediate`.
 */
class ZoomGestureController(
    private val cameraInfo: CameraInfo,
    private val cameraControl: CameraControl,
    private val handFrames: StateFlow<HandFrame?>,
    private val lifecycleOwner: LifecycleOwner,
) {

    /** Minimum supported zoom — usually 1.0x. */
    val minZoom: Double = run {
        // CameraInfo.zoomState is a live StateFlow; we read the snapshot.
        cameraInfo.zoomState.value?.minZoomRatio?.toDouble() ?: FALLBACK_MIN_ZOOM
    }

    /** Maximum supported zoom for this device. */
    val maxZoom: Double = run {
        cameraInfo.zoomState.value?.maxZoomRatio?.toDouble()?.takeIf { it > 0.0 } ?: FALLBACK_MAX_ZOOM
    }

    private val _currentZoom = MutableStateFlow(minZoom)
    /** Smoothed zoom ratio currently held by the camera, with EMA applied. */
    val currentZoom: StateFlow<Double> get() = _currentZoom.asStateFlow()

    private var lastHandSeenMs: Long = 0L
    private var smoothedZoom: Double = minZoom
    private var subscriptionJob: Job? = null
    private var decayJob: Job? = null

    /** Start consuming the hand-frame stream. Idempotent. */
    fun start() {
        if (subscriptionJob?.isActive == true) return

        // Job 1 — react to newly detected hand frames.
        subscriptionJob = lifecycleOwner.lifecycleScope.launch {
            handFrames.collectLatest { frame ->
                if (frame != null) handleHandFrame(frame)
                // Null frames are handled by the decay ticker below —
                // StateFlow dedups successive nulls so we drive decay from a clock.
            }
        }

        // Job 2 — periodic decay tick. Runs until [stop] cancels it.
        decayJob = lifecycleOwner.lifecycleScope.launch {
            while (true) {
                delay(DECAY_TICK_MS)
                handleNoHand(System.currentTimeMillis())
            }
        }
    }

    /** Stop consuming the stream. Drops the smoothed zoom back to `minZoom`. */
    fun stop() {
        subscriptionJob?.cancel(); subscriptionJob = null
        decayJob?.cancel(); decayJob = null
        smoothedZoom = minZoom
        _currentZoom.value = minZoom
        // Best effort — the camera may already be unbound.
        runCatching { cameraControl.setZoomRatio(minZoom) }
    }

    // ---------- private ----------

    private fun handleHandFrame(frame: HandFrame) {
        lastHandSeenMs = frame.timestampMs

        // Euclidean thumb ↔ index distance, normalized by hand size.
        val pinchNorm = run {
            val dx = frame.indexTipX - frame.thumbTipX
            val dy = frame.indexTipY - frame.thumbTipY
            (hypot(dx, dy) / frame.handSize).coerceAtLeast(MIN_PINCH_NORM)
        }

        val target = lerp(minZoom, maxZoom, PINCH_MIN, PINCH_MAX, pinchNorm).coerceIn(minZoom, maxZoom)

        // EMA — start from `smoothedZoom` so a transient rebuild still feels natural.
        smoothedZoom += EMA_ALPHA * (target - smoothedZoom)
        _currentZoom.value = smoothedZoom
        applyZoom(smoothedZoom)
    }

    private fun handleNoHand(now: Long) {
        val since = now - lastHandSeenMs
        if (since <= HAND_LOST_TIMEOUT_MS) return

        if (smoothedZoom > minZoom) {
            smoothedZoom = max(minZoom, smoothedZoom - DECAY_PER_FRAME)
            _currentZoom.value = smoothedZoom
            applyZoom(smoothedZoom)
        }
    }

    private fun applyZoom(zoom: Double) {
        val clamped = max(minZoom, min(maxZoom, zoom))
        runCatching { cameraControl.setZoomRatio(clamped) }
    }

    /** Linear interpolation from the input range `[x0, x1]` to `[y0, y1]`. */
    private fun lerp(y0: Double, y1: Double, x0: Double, x1: Double, x: Double): Double {
        if (x1 == x0) return y0
        val t = ((x - x0) / (x1 - x0)).coerceIn(0.0, 1.0)
        return y0 + t * (y1 - y0)
    }

    private companion object {
        // --- Calibration constants (see KDoc) ------------------------------------
        /** Closed-fist pinch ratio. Below this we hold minZoom. */
        private const val PINCH_MIN = 0.05

        /** Wide-stretch pinch ratio. At/above this we hold maxZoom. */
        private const val PINCH_MAX = 0.60

        /** EMA smoothing factor — 0.15 ≈ filter time constant of ~6 frames. */
        private const val EMA_ALPHA = 0.15

        /** No-hand timeout in milliseconds before decay starts. */
        private const val HAND_LOST_TIMEOUT_MS = 1000L

        /** Per-tick decay toward minZoom when no hand is detected. */
        private const val DECAY_PER_FRAME = 0.05

        /** Cadence at which the decay loop wakes up. */
        private const val DECAY_TICK_MS = 50L

        /** Guard against very tiny/numerically-noisy pinchNorm values. */
        private const val MIN_PINCH_NORM = 1e-3

        // --- Fallback zoom bounds if the camera can't report zoomState. ---
        private const val FALLBACK_MIN_ZOOM = 1.0
        private const val FALLBACK_MAX_ZOOM = 4.0
    }
}
