package com.example.gesturezoom

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.hand.detection.Hand
import com.google.mlkit.vision.hand.detection.HandDetection
import com.google.mlkit.vision.hand.detection.HandDetector
import com.google.mlkit.vision.hand.detection.HandDetectorOptions
import com.google.mlkit.vision.hand.detection.HandLandmark
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.hypot

/**
 * Lightweight DTO that carries everything the zoom controller needs from a
 * single detected hand. Keeping this lean means we only allocate a handful of
 * floats per frame and the controller doesn't have to walk the full ML Kit
 * landmark list.
 *
 * Coordinates are in the same space as the input image (unscaled pixels of
 * the buffer fed by CameraX) and are valid only for `timestampMs`.
 *
 * @param thumbTipX X coordinate of THUMB_TIP (landmark #4)
 * @param thumbTipY Y coordinate of THUMB_TIP
 * @param indexTipX X coordinate of INDEX_FINGER_TIP (landmark #8)
 * @param indexTipY Y coordinate of INDEX_FINGER_TIP
 * @param handSize Distance between WRIST and MIDDLE_FINGER_MCP, or the
 *                 bounding-box width when those landmarks are unavailable.
 *                 Used to normalize the pinch distance so the mapping works
 *                 regardless of how close the hand is to the camera.
 * @param timestampMs Wall-clock time the frame was emitted (System.nanoTime/1_000_000)
 */
data class HandFrame(
    val thumbTipX: Float,
    val thumbTipY: Float,
    val indexTipX: Float,
    val indexTipY: Float,
    val handSize: Float,
    val timestampMs: Long,
)

/**
 * CameraX [ImageAnalysis.Analyzer] backed by ML Kit's on-device hand
 * detector. Each detected hand is reduced to a [HandFrame] and pushed onto a
 * hot [StateFlow] that the [ZoomGestureController] collects.
 *
 * Designed to run on a dedicated single-thread executor (see [MainActivity]).
 * The ML Kit [HandDetector] runs its inference off-thread internally; we just
 * make sure the ImageProxy is closed once ML Kit is done with the frame, even
 * on failure.
 *
 * Lifetime: construct once in `onCreate`, hand the same instance to the
 * CameraX pipeline, then call [close] from `onDestroy` to release the
 * detector's underlying model.
 */
class HandLandmarkAnalyzer(
    /**
     * Minimum confidence the ML Kit detector accepts before returning a hand.
     * Defaults to 0.5 — sufficient for our pinch-distance signal.
     */
    minHandConfidence: Float = 0.5f,
) : ImageAnalysis.Analyzer {

    private val detector: HandDetector = HandDetection.getClient(
        HandDetectorOptions.Builder()
            // Live-stream mode — much faster on average than the still-image
            // path because the model reuses temporal context.
            .setDetectorMode(HandDetectorOptions.STREAM_MODE)
            // Tune detection thresholds (defaults are already sane, but we
            // set them explicitly so behaviour is predictable).
            .setMinHandPresenceConfidence(minHandConfidence)
            // We don't track a hand across frames, so we don't need
            // HAND_TRACKING_MODE explicitly — the default (NONE) is what we
            // want here.
            .build()
    )

    private val _handFrames = MutableStateFlow<HandFrame?>(null)

    /** Hot stream of the most-recently detected hand, or `null` when no hand was seen. */
    val handFrames: StateFlow<HandFrame?> get() = _handFrames.asStateFlow()

    override fun analyze(imageProxy: ImageProxy) {
        // The ImageProxy close is guarded by [closed] so we can call it from
        // either the happy/failure listeners *or* the `finally` safety net
        // without double-closing the underlying camera buffer.
        var closed = false
        val closeSafely = {
            if (!closed) {
                closed = true
                runCatching { imageProxy.close() }
            }
            Unit
        }

        // `try`/`finally` guarantees [ImageProxy.close] even if anything throws
        // before the ML Kit Task attaches its callbacks. Even though
        // `addOnCompleteListener` below does the close in the normal path,
        // the `finally` is the spec-required safety net.
        try {
            val mediaImage = imageProxy.image
            if (mediaImage == null) {
                // Nothing we can do; release the frame and bail.
                closeSafely()
                return
            }

            val rotation = imageProxy.imageInfo.rotationDegrees
            val inputImage = InputImage.fromMediaImage(mediaImage, rotation)

            detector.process(inputImage)
                .addOnSuccessListener { hands ->
                    _handFrames.value = hands.firstOrNull()?.toHandFrameOrNull()
                }
                .addOnFailureListener {
                    // ML Kit uses on-device inference; failures are usually out-of-memory
                    // or unrecognised formats. Don't crash — just skip this frame and
                    // let the next one retry.
                }
                .addOnCompleteListener {
                    // MUST close the proxy or CameraX will stop delivering new frames.
                    closeSafely()
                }
        } finally {
            // Belt-and-braces — if `detector.process(inputImage)` for some reason
            // never completes (extremely unlikely), this still releases the frame.
            closeSafely()
        }
    }

    /** Releases the ML Kit detector. Idempotent. Safe to call from `onDestroy`. */
    fun close() {
        runCatching { detector.close() }
        _handFrames.value = null
    }
}

/**
 * Projects the landmarks the zoom controller needs into a [HandFrame], or
 * returns `null` when a required landmark (thumb tip or index tip) is missing.
 *
 * Hand size is computed as the wrist → middle-finger-MCP distance, falling
 * back to the bounding-box width. We divide by it to make the pinch ratio
 * distance-invariant.
 */
private fun Hand.toHandFrameOrNull(): HandFrame? {
    val landmarks = landmarks
    // We need indices 0 (WRIST), 4 (THUMB_TIP), 8 (INDEX_FINGER_TIP), 9 (MIDDLE_FINGER_MCP).
    if (landmarks.size < HandLandmark.MIDDLE_FINGER_MCP + 1) return null

    val thumbTip = landmarks[HandLandmark.THUMB_TIP].position
    val indexTip = landmarks[HandLandmark.INDEX_FINGER_TIP].position

    val wrist = landmarks[HandLandmark.WRIST].position
    val middleMcp = landmarks[HandLandmark.MIDDLE_FINGER_MCP].position

    val handSize: Float = run {
        // We validated the size above, so wrist/middleMcp are always non-null here,
        // but we fall back to bbox width defensively in case ML Kit ever drifts.
        hypot(middleMcp.x - wrist.x, middleMcp.y - wrist.y)
            .takeIf { it > 0f }
            ?: boundingBox.width().toFloat()
    }.coerceAtLeast(MIN_HAND_SIZE_PX)

    return HandFrame(
        thumbTipX = thumbTip.x,
        thumbTipY = thumbTip.y,
        indexTipX = indexTip.x,
        indexTipY = indexTip.y,
        handSize = handSize,
        timestampMs = System.currentTimeMillis(),
    )
}

/** Guard against tiny bboxes producing division-by-zero in the controller. */
private const val MIN_HAND_SIZE_PX: Float = 1f
