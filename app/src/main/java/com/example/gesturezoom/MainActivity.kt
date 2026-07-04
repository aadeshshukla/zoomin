package com.example.gesturezoom

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The single screen of the gesture-zoom camera app. Hosts a full-screen
 * [PreviewView] underneath an overlay that shows the current zoom factor and
 * a "Pinch to zoom" hint.
 *
 * Responsibilities:
 *  * CAMERA permission handshake (with a friendly denial panel)
 *  * Wires the CameraX pipeline (Preview + ImageAnalysis)
 *  * Owns the [HandLandmarkAnalyzer] / [ZoomGestureController] lifetimes
 *  * Drives the zoom-overlay text in response to the controller's StateFlow
 *
 * Threading:
 *  * Hand detection runs on a dedicated single-thread background [ExecutorService]
 *  * CameraX control state is collected on the main thread via [lifecycleScope]
 */
class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var zoomLabel: TextView
    private lateinit var hintLabel: TextView
    private lateinit var permissionPanel: LinearLayout
    private lateinit var permissionRetryButton: Button

    private var cameraExecutor: ExecutorService? = null
    private var handAnalyzer: HandLandmarkAnalyzer? = null
    private var zoomController: ZoomGestureController? = null
    private var cameraProvider: ProcessCameraProvider? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            hidePermissionPanel()
            startCameraPipeline()
        } else {
            showPermissionPanel()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView            = findViewById(R.id.previewView)
        zoomLabel              = findViewById(R.id.zoomLabel)
        hintLabel              = findViewById(R.id.hintLabel)
        permissionPanel        = findViewById(R.id.permissionPanel)
        permissionRetryButton  = findViewById(R.id.permissionRetryButton)

        permissionRetryButton.setOnClickListener {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        if (hasCameraPermission()) {
            startCameraPipeline()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        // We tear down in this order: controller (no more zoom calls) →
        // analyzer (ML Kit model release) → executor → camera provider.
        zoomController?.stop()
        zoomController = null

        handAnalyzer?.close()
        handAnalyzer = null

        cameraExecutor?.shutdown()
        cameraExecutor = null

        cameraProvider?.unbindAll()
        cameraProvider = null

        super.onDestroy()
    }

    // --------------------------------------------------------------------- //
    //                            Pipeline setup                             //
    // --------------------------------------------------------------------- //

    private fun startCameraPipeline() {
        val executor = Executors.newSingleThreadExecutor().also { cameraExecutor = it }

        val analyzer = HandLandmarkAnalyzer().also { handAnalyzer = it }

        lifecycleScope.launch {
            try {
                val provider = ProcessCameraProvider.getInstance(this@MainActivity).await()
                cameraProvider = provider

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    android.util.Size(TARGET_WIDTH, TARGET_HEIGHT),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                                )
                            )
                            .build()
                    )
                    .build()
                    .also { it.setAnalyzer(executor, analyzer) }

                val camera = provider.bindToLifecycle(
                    this@MainActivity,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )

                val controller = ZoomGestureController(
                    cameraInfo = camera.cameraInfo,
                    cameraControl = camera.cameraControl,
                    handFrames = analyzer.handFrames,
                    lifecycleOwner = this@MainActivity,
                )
                zoomController = controller
                controller.start()

                observeZoomOverlay(controller)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to bind CameraX pipeline", t)
            }
        }
    }

    /** Push the controller's current zoom ratio onto the on-screen overlay. */
    private fun observeZoomOverlay(controller: ZoomGestureController) {
        lifecycleScope.launch {
            controller.currentZoom
                .distinctUntilChanged()
                .collectLatest { zoom ->
                    val text = getString(R.string.zoom_label_format, zoom)
                    zoomLabel.text = text
                }
        }
    }

    // --------------------------------------------------------------------- //
    //                        Permission UI plumbing                        //
    // --------------------------------------------------------------------- //

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun showPermissionPanel() {
        permissionPanel.visibility = View.VISIBLE
        previewView.visibility = View.GONE
    }

    private fun hidePermissionPanel() {
        permissionPanel.visibility = View.GONE
        previewView.visibility = View.VISIBLE
    }

    private companion object {
        private const val TAG = "MainActivity"

        // ImageAnalysis target resolution — 640x480 keeps ML Kit fast on mid-tier
        // devices while giving us enough resolution to localize hand landmarks.
        private const val TARGET_WIDTH = 640
        private const val TARGET_HEIGHT = 480
    }
}
