package com.example.spatialsdk.sample

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.text.InputType
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.nimpu.spatial.sdk.CloudPinListResult
import com.nimpu.spatial.sdk.CreateDiagnostics
import com.nimpu.spatial.sdk.CreateObservation
import com.nimpu.spatial.sdk.CreatePinCoverageView
import com.nimpu.spatial.sdk.CreatePinPhase
import com.nimpu.spatial.sdk.CreatePinState
import com.nimpu.spatial.sdk.CreateSession
import com.nimpu.spatial.sdk.DebugSessionLog
import com.nimpu.spatial.sdk.GeospatialGuidanceMode
import com.nimpu.spatial.sdk.CreatePinResult
import com.nimpu.spatial.sdk.NimpuPin
import com.nimpu.spatial.sdk.ResolveTarget
import com.nimpu.spatial.sdk.NimpuSpatialMode
import com.nimpu.spatial.sdk.NimpuSpatialSdk
import com.nimpu.spatial.sdk.NimpuSpatialSession
import com.nimpu.spatial.sdk.PrepareResolveTargetProgress
import com.nimpu.spatial.sdk.PrepareResolveTargetResult
import com.nimpu.spatial.sdk.ResolveEngineResult
import com.nimpu.spatial.sdk.ResolveGuidanceView
import com.nimpu.spatial.sdk.ResolveObservation
import com.nimpu.spatial.sdk.ResolvePinState
import com.nimpu.spatial.sdk.ResolveSession
import com.nimpu.spatial.sdk.ResolveWorkflowState

/**
 * AR session activity.
 * Manages the ARCore lifecycle, camera permission, GL rendering,
 * and the Create Pin / Resolve Pin flows.
 */
class ArActivity : AppCompatActivity() {

    companion object {
        private const val AR_PERMISSIONS_CODE = 100
        private var createPinIntroShownThisLaunch = false

        const val EXTRA_MODE = "ar_mode"
        const val MODE_CREATE_PIN = "create_pin"
        const val MODE_RESOLVE_PIN = "resolve_pin"
    }

    private var arSession: Session? = null
    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var renderer: ArRenderer
    private lateinit var statusCard: View
    private lateinit var tvStatus: TextView
    private lateinit var tvInstructionStatus: TextView
    private lateinit var tvResolveStage: TextView
    private lateinit var tvEarthStatus: TextView
    private lateinit var resolveDebugCard: View
    private lateinit var resolveDebugContent: View
    private lateinit var createGeoCard: View
    private lateinit var createDebugContent: View
    private lateinit var tvCreateGeoStatus: TextView
    private lateinit var tvCreateGeoDetails: TextView
    private lateinit var btnToggleResolveDebug: MaterialButton
    private lateinit var btnToggleCreateDebug: MaterialButton
    private lateinit var btnTryAgain: MaterialButton
    private lateinit var btnStartPreciseSearch: MaterialButton
    private lateinit var btnShareResolveLog: MaterialButton
    private lateinit var btnShareCreateLog: MaterialButton
    private lateinit var btnSavePin: MaterialButton
    private lateinit var savePinActions: View
    private lateinit var progressSavePin: ProgressBar
    private lateinit var coverageView: CreatePinCoverageView
    private lateinit var introOverlay: FrameLayout
    private lateinit var btnIntroOk: MaterialButton
    private lateinit var resolveHudOverlay: ResolveGuidanceView
    private var installRequested = false
    private var locationPermissionRequested = false
    private var mode: String = MODE_CREATE_PIN
    private var activeResolveSession: ResolveSession? = null
    private lateinit var activeCreateSession: CreateSession
    private var createCoverageObservation: CreateObservation? = null
    private var resolveStateObservation: ResolveObservation? = null
    private var resolveResultObservation: ResolveObservation? = null
    private var resolveGuidanceObservation: ResolveObservation? = null
    private var lastMappingUiState: CreatePinState? = null
    private var spatialSession: NimpuSpatialSession? = null
    private var resolveLoadStarted = false
    private var isSavingPin = false
    private var isResolveDebugExpanded = false
    private var isCreateDebugExpanded = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ar)

        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_CREATE_PIN
        DebugSessionLog.startSession(
            if (mode == MODE_CREATE_PIN) "Create Pin Session" else "Resolve Pin Session"
        )
        spatialSession = NimpuSpatialSdk.newSession(
            mode = if (mode == MODE_CREATE_PIN) NimpuSpatialMode.CREATE_PIN else NimpuSpatialMode.RESOLVE_PIN
        )

        glSurfaceView = findViewById(R.id.gl_surface_view)
        statusCard = findViewById(R.id.status_card)
        tvStatus = findViewById(R.id.tv_status)
        tvInstructionStatus = findViewById(R.id.tv_instruction_status)
        tvResolveStage = findViewById(R.id.tv_resolve_stage)
        tvEarthStatus = findViewById(R.id.tv_earth_status)
        resolveDebugCard = findViewById(R.id.resolve_debug_card)
        resolveDebugContent = findViewById(R.id.resolve_debug_content)
        createGeoCard = findViewById(R.id.create_geo_card)
        createDebugContent = findViewById(R.id.create_debug_content)
        tvCreateGeoStatus = findViewById(R.id.tv_create_geo_status)
        tvCreateGeoDetails = findViewById(R.id.tv_create_geo_details)
        btnToggleResolveDebug = findViewById(R.id.btn_toggle_resolve_debug)
        btnToggleCreateDebug = findViewById(R.id.btn_toggle_create_debug)
        btnTryAgain = findViewById(R.id.btn_try_again)
        btnStartPreciseSearch = findViewById(R.id.btn_start_precise_search)
        btnShareResolveLog = findViewById(R.id.btn_share_resolve_log)
        btnShareCreateLog = findViewById(R.id.btn_share_create_log)
        btnSavePin = findViewById(R.id.btn_save_pin)
        savePinActions = findViewById(R.id.save_pin_actions)
        progressSavePin = findViewById(R.id.progress_save_pin)
        coverageView = findViewById(R.id.coverage_view)
        introOverlay = findViewById(R.id.intro_overlay)
        btnIntroOk = findViewById(R.id.btn_intro_ok)
        resolveHudOverlay = findViewById(R.id.resolve_hud_overlay)
        resolveHudOverlay.setBlockedViews(statusCard, resolveDebugCard, savePinActions)

        activeCreateSession = requireNotNull(spatialSession).newCreateSession()
        createCoverageObservation = activeCreateSession.attachCreateCoverageView(coverageView)
        renderer = ArRenderer(
            onFirstPlaneDetected = {
                runOnUiThread { }
            },
            createSession = activeCreateSession,
            onUiStateChanged = { uiState ->
                runOnUiThread {
                    if (mode == MODE_CREATE_PIN) {
                        renderMappingUi(uiState)
                    }
                }
            },
            onCreateGeospatialInfoChanged = { geoInfo ->
                runOnUiThread {
                    if (mode == MODE_CREATE_PIN) {
                        renderCreateGeospatialInfo(geoInfo)
                    }
                }
            }
        )

        glSurfaceView.apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }

        glSurfaceView.setOnTouchListener { _, event ->
            if (mode == MODE_CREATE_PIN && event.action == MotionEvent.ACTION_DOWN) {
                if (renderer.mappingState == CreatePinPhase.READY_TO_PLACE) {
                    renderer.pendingTap = floatArrayOf(event.x, event.y)
                }
            }
            true
        }

        btnSavePin.setOnClickListener { showSavePinDialog() }
        btnIntroOk.setOnClickListener { dismissIntroOverlay() }
        introOverlay.setOnClickListener { dismissIntroOverlay() }
        btnTryAgain.setOnClickListener {
            DebugSessionLog.append("RESOLVE", "Try Again tapped")
            renderer.retryResolve()
        }
        btnStartPreciseSearch.setOnClickListener {
            DebugSessionLog.append("RESOLVE", "Start precise search tapped")
            renderer.requestPreciseSearch()
        }
        btnToggleResolveDebug.setOnClickListener {
            isResolveDebugExpanded = !isResolveDebugExpanded
            updateDebugPanelVisibility()
        }
        btnToggleCreateDebug.setOnClickListener {
            isCreateDebugExpanded = !isCreateDebugExpanded
            updateDebugPanelVisibility()
        }
        btnShareResolveLog.setOnClickListener { shareDebugLog() }
        btnShareCreateLog.setOnClickListener { shareDebugLog() }

        if (mode == MODE_CREATE_PIN) {
            maybeShowIntroOverlay()
            resolveDebugCard.visibility = View.GONE
            createGeoCard.visibility = View.VISIBLE
        } else {
            introOverlay.visibility = View.GONE
            tvInstructionStatus.visibility = View.GONE
            resolveDebugCard.visibility = View.VISIBLE
            createGeoCard.visibility = View.GONE
            tvResolveStage.text = "Stage: ${ResolveWorkflowState.IDLE.name}"
            tvEarthStatus.text = "Waiting for Earth/geospatial status."
            updateResolveActionButtons(
                ResolvePinState(
                    phase = ResolveWorkflowState.IDLE,
                    message = "",
                    earthStatus = "Waiting for Earth/geospatial status.",
                    showStartPreciseSearch = true
                )
            )
        }
        updateDebugPanelVisibility()
    }

    override fun onResume() {
        super.onResume()

        when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
            ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                installRequested = true
                return
            }
            ArCoreApk.InstallStatus.INSTALLED -> Unit
        }

        if (!hasCameraPermission()) {
            ActivityCompat.requestPermissions(
                this,
                buildArPermissionsRequest(),
                AR_PERMISSIONS_CODE
            )
            return
        }
        if (shouldUseGeospatialGuidance() && !hasLocationPermission() && !locationPermissionRequested) {
            locationPermissionRequested = true
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                AR_PERMISSIONS_CODE
            )
            return
        }

        resumeArSession()
    }

    private fun resumeArSession() {
        if (isFinishing || isDestroyed) return

        if (arSession == null) {
            try {
                arSession = Session(this).also { session ->
                    val config = Config(session).apply {
                        planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                        updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                        focusMode = Config.FocusMode.AUTO
                    }
                    if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                        config.setDepthMode(Config.DepthMode.AUTOMATIC)
                        android.util.Log.d("NimpuDebug", "ARCore Depth API Enabled")
                    }
                    if (!shouldUseGeospatialGuidance()) {
                        android.util.Log.d("NimpuDebug", "ARCore Geospatial API disabled by SDK config")
                    } else if (hasLocationPermission() && session.isGeospatialModeSupported(Config.GeospatialMode.ENABLED)) {
                        config.geospatialMode = Config.GeospatialMode.ENABLED
                        android.util.Log.d("NimpuDebug", "ARCore Geospatial API Enabled")
                    } else {
                        android.util.Log.d("NimpuDebug", "ARCore Geospatial API unavailable or location permission denied")
                    }
                    session.configure(config)
                }
            } catch (e: UnavailableArcoreNotInstalledException) {
                showError("ARCore is not installed.")
                return
            } catch (e: UnavailableApkTooOldException) {
                showError("Please update ARCore.")
                return
            } catch (e: UnavailableSdkTooOldException) {
                showError("Please update this app.")
                return
            } catch (e: UnavailableDeviceNotCompatibleException) {
                showError("This device does not support AR.")
                return
            } catch (e: Exception) {
                showError("Failed to create AR session: ${e.message}")
                return
            }
        }

        try {
            arSession?.resume()
        } catch (e: CameraNotAvailableException) {
            showError("Camera not available. Try restarting the app.")
            arSession = null
            return
        }

        @Suppress("DEPRECATION")
        renderer.displayRotation = windowManager.defaultDisplay.rotation
        renderer.session = arSession
        renderer.paused = false
        glSurfaceView.onResume()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (mode == MODE_RESOLVE_PIN && activeResolveSession == null && !resolveLoadStarted) {
            checkAndLoadPins()
        }
    }

    override fun onPause() {
        super.onPause()
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        clearActiveResolveSession()
        resolveLoadStarted = false
        renderer.paused = true
        arSession?.pause()
        glSurfaceView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        createCoverageObservation?.dispose()
        createCoverageObservation = null
        arSession?.close()
        arSession = null
    }

    private fun clearActiveResolveSession() {
        resolveStateObservation?.dispose()
        resolveStateObservation = null
        resolveResultObservation?.dispose()
        resolveResultObservation = null
        resolveGuidanceObservation?.dispose()
        resolveGuidanceObservation = null
        activeResolveSession?.stopPreciseSearch()
        activeResolveSession = null
        renderer.resolveSession = null
        resolveHudOverlay.bind(ResolvePinState(ResolveWorkflowState.IDLE, "", ""))
    }

    private fun renderMappingUi(uiState: CreatePinState) {
        lastMappingUiState = uiState
        if (!isSavingPin) {
            tvInstructionStatus.visibility = View.VISIBLE
            tvStatus.text = uiState.progressMessage
            tvInstructionStatus.text = uiState.instructionMessage
            savePinActions.visibility = if (uiState.canSave && introOverlay.visibility != View.VISIBLE) View.VISIBLE else View.GONE
            btnSavePin.text = "Save Pin"
            btnSavePin.isEnabled = true
            progressSavePin.visibility = View.GONE
        }
    }

    private fun renderCreateGeospatialInfo(info: CreateDiagnostics) {
        val accuracy = info.geospatialAccuracy ?: return
        tvCreateGeoStatus.text = accuracy.status
        tvCreateGeoDetails.text = buildString {
            appendLine("Source: ${accuracy.sourceLabel}")
            appendLine("Latitude: ${accuracy.latitudeText}")
            appendLine("Longitude: ${accuracy.longitudeText}")
            appendLine("Altitude: ${accuracy.altitudeText}")
            appendLine("Horizontal accuracy: ${accuracy.horizontalAccuracyText}")
            appendLine("Vertical accuracy: ${accuracy.verticalAccuracyText}")
            appendLine("Heading accuracy: ${accuracy.headingAccuracyText}")
            appendLine("Localization quality: ${accuracy.localizationQualityText}")
            append("VPS-quality localization: ${accuracy.vpsQualityText}")
        }
    }

    private fun renderResolveState(state: ResolvePinState) {
        tvInstructionStatus.visibility = View.GONE
        tvStatus.text = state.message
        resolveDebugCard.visibility = View.VISIBLE
        updateDebugPanelVisibility()
        tvResolveStage.text = "Stage: ${state.phase.name}"
        tvEarthStatus.text = state.earthStatus
        updateResolveActionButtons(state)
        if (state.phase == ResolveWorkflowState.LOCAL_RESOLVE_LOCKED) {
            Toast.makeText(this, "Pin found!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun maybeShowIntroOverlay() {
        if (!createPinIntroShownThisLaunch) {
            createPinIntroShownThisLaunch = true
            introOverlay.visibility = View.VISIBLE
            savePinActions.visibility = View.GONE
        }
    }

    private fun dismissIntroOverlay() {
        introOverlay.visibility = View.GONE
        lastMappingUiState?.let(::renderMappingUi)
    }

    private fun updateDebugPanelVisibility() {
        resolveDebugContent.visibility = if (isResolveDebugExpanded) View.VISIBLE else View.GONE
        createDebugContent.visibility = if (isCreateDebugExpanded) View.VISIBLE else View.GONE
        btnToggleResolveDebug.text = if (isResolveDebugExpanded) "Hide" else "Show"
        btnToggleCreateDebug.text = if (isCreateDebugExpanded) "Hide" else "Show"
    }

    private fun showSavePinDialog() {
        if (isSavingPin) return

        activeCreateSession.pauseCaptureScanning("save dialog opened")
        savePinActions.visibility = View.GONE
        val defaultName = defaultPinName()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
        }
        val nameInput = EditText(this).apply {
            setText(defaultName)
            selectAll()
            hint = "Pin name"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        val statusText = TextView(this).apply {
            text = "Name this pin before saving."
            setPadding(0, 20, 0, 0)
        }
        val progress = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        container.addView(nameInput)
        container.addView(statusText)
        container.addView(progress)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Save Pin")
            .setView(container)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Share Log", null)
            .setPositiveButton("Save", null)
            .create()

        dialog.setOnDismissListener {
            if (!isSavingPin) {
                activeCreateSession.resumeCaptureScanning("save dialog dismissed")
                lastMappingUiState?.let(::renderMappingUi)
            }
        }
        dialog.setOnShowListener {
            val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            val cancelButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
            val shareButton = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
            shareButton.visibility = View.GONE
            saveButton.setOnClickListener {
                val displayName = nameInput.text?.toString()?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: defaultName
                nameInput.isEnabled = false
                saveButton.isEnabled = false
                cancelButton.visibility = View.GONE
                progress.visibility = View.VISIBLE
                statusText.text = "Saving pin locally..."

                container.post {
                    savePin(displayName, statusText, progress, dialog)
                }
            }
        }
        dialog.show()
    }

    private fun savePin(
        displayName: String,
        dialogStatus: TextView,
        dialogProgress: ProgressBar,
        dialog: AlertDialog
    ) {
        if (isSavingPin) return

        isSavingPin = true
        savePinActions.visibility = View.GONE
        progressSavePin.visibility = View.GONE
        DebugSessionLog.append("CREATE", "Save Pin tapped; local save started")
        PerfLog.begin("payloadSave")

        val anchor = renderer.getAnchor()
        if (anchor == null) {
            showError("No anchor to save.")
            dialogStatus.text = "No anchor to save."
            dialogProgress.visibility = View.GONE
            PerfLog.end("payloadSave")
            resetCreateSaveUi()
            showSaveDialogDone(dialog)
            return
        }

        dialogStatus.text = "Saving pin locally..."
        dialogProgress.visibility = View.VISIBLE
        activeCreateSession.savePin(
            context = this,
            anchorPose = anchor.pose,
            geospatialMetadata = renderer.getPlacementGeospatialMetadata(),
            displayName = displayName,
            uploadToCloud = hasCloudConfig()
        ) { result ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                handleCreatePinSaveResult(result, dialogStatus, dialogProgress, dialog)
            }
        }
    }

    private fun handleCreatePinSaveResult(
        result: CreatePinResult,
        dialogStatus: TextView,
        dialogProgress: ProgressBar,
        dialog: AlertDialog
    ) {
        when (result) {
            is CreatePinResult.SavedLocal -> {
                PerfLog.end("payloadSave")
                progressSavePin.visibility = View.GONE
                DebugSessionLog.append(
                    "CREATE",
                    "Local pin saved: ${result.localPinId} (${result.pointCount} points)"
                )
                if (hasCloudConfig()) {
                    dialogStatus.text = "Pin saved locally. Uploading to cloud..."
                    dialogProgress.visibility = View.VISIBLE
                    DebugSessionLog.append("CREATE", "Uploading local pin ${result.localPinId} to cloud")
                } else {
                    dialogStatus.text = "Pin saved locally."
                    dialogProgress.visibility = View.GONE
                    Toast.makeText(
                        this,
                        "Pin saved locally! (${result.localPinId}, ${result.pointCount} points)",
                        Toast.LENGTH_LONG
                    ).show()
                    showSaveDialogDone(dialog) {
                        finish()
                    }
                }
            }
            is CreatePinResult.Uploaded -> {
                progressSavePin.visibility = View.GONE
                dialogProgress.visibility = View.GONE
                dialogStatus.text = "Upload complete."
                DebugSessionLog.append("CREATE", "Cloud upload complete: ${result.cloudPinId}")
                Toast.makeText(
                    this,
                    "Pin saved and uploaded! Cloud pin: ${result.cloudPinId}",
                    Toast.LENGTH_LONG
                ).show()
                showSaveDialogDone(dialog) {
                    finish()
                }
            }
            is CreatePinResult.Failed -> {
                progressSavePin.visibility = View.GONE
                dialogProgress.visibility = View.GONE
                if (result.localPinId == null) {
                    PerfLog.end("payloadSave")
                    showError(result.error)
                    dialogStatus.text = result.error
                    DebugSessionLog.append("CREATE", "Local save failed: ${result.error}")
                    Toast.makeText(this, "Pin save failed.", Toast.LENGTH_LONG).show()
                    resetCreateSaveUi()
                    showSaveDialogDone(dialog)
                } else {
                    dialogStatus.text = "Saved locally. Cloud upload failed."
                    DebugSessionLog.append(
                        "CREATE",
                        "Cloud upload failed for ${result.localPinId}: ${result.error}"
                    )
                    Toast.makeText(
                        this,
                        "Pin saved locally, but cloud upload failed.",
                        Toast.LENGTH_LONG
                    ).show()
                    showSaveDialogDone(dialog) {
                        finish()
                    }
                }
            }
        }
    }

    private fun checkAndLoadPins() {
        resolveLoadStarted = true
        if (hasCloudConfig()) {
            loadCloudPinList()
            return
        }
        loadLocalPinList()
    }

    private fun loadCloudPinList() {
        tvStatus.text = "Fetching cloud pins..."
        DebugSessionLog.append("RESOLVE", "Fetching cloud pin list")

        NimpuSpatialSdk.listCloudPins(this) { result ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val cloudPins = when (result) {
                    is CloudPinListResult.Success -> result.pins
                    is CloudPinListResult.Failed -> {
                        DebugSessionLog.append(
                            "RESOLVE",
                            "Cloud pin list unavailable; falling back to local pins: ${result.error}"
                        )
                        Toast.makeText(this, "Cloud pins unavailable. Showing local pins.", Toast.LENGTH_LONG).show()
                        loadLocalPinList()
                        return@runOnUiThread
                    }
                }
                if (cloudPins.isEmpty()) {
                    DebugSessionLog.append("RESOLVE", "Cloud pin list loaded: empty project")
                    showEmptyCloudPinListDialog()
                    return@runOnUiThread
                }

                val names = cloudPins.map { pin ->
                    "${pin.displayName} (${pin.pointCount} points)"
                }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("Select a Cloud Pin")
                    .setItems(names) { _, which ->
                        prepareSelectedPinForResolve(cloudPins[which], fallbackToLocalOnFailure = true)
                    }
                    .setOnCancelListener {
                        resolveLoadStarted = false
                        tvStatus.text = "Cloud pin selection cancelled."
                        DebugSessionLog.append("RESOLVE", "Cloud pin selection cancelled")
                    }
                    .show()
            }
        }
    }

    private fun showEmptyCloudPinListDialog() {
        tvStatus.text = "No cloud pins in this project."
        AlertDialog.Builder(this)
            .setTitle("No cloud pins")
            .setMessage("This project does not have any cloud pins yet.")
            .setPositiveButton("View saved pins") { _, _ ->
                DebugSessionLog.append("RESOLVE", "User chose local pins after empty cloud list")
                loadLocalPinList()
            }
            .setNegativeButton("Cancel") { _, _ ->
                resolveLoadStarted = false
                tvStatus.text = "No cloud pins in this project."
                DebugSessionLog.append("RESOLVE", "Empty cloud list dismissed")
            }
            .setOnCancelListener {
                resolveLoadStarted = false
                tvStatus.text = "No cloud pins in this project."
                DebugSessionLog.append("RESOLVE", "Empty cloud list cancelled")
            }
            .show()
    }

    private fun prepareSelectedPinForResolve(
        pin: NimpuPin,
        fallbackToLocalOnFailure: Boolean
    ) {
        val pinId = pin.cloudPinId ?: pin.localPinId ?: pin.primaryId
        tvStatus.text = "Preparing $pinId..."
        DebugSessionLog.append("RESOLVE", "Preparing resolve target for $pinId")

        NimpuSpatialSdk.prepareResolveTarget(
            context = this,
            pin = pin,
            onProgress = { progress ->
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    tvStatus.text = resolvePreparationStatus(progress)
                }
            }
        ) { result ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when (result) {
                    is PrepareResolveTargetResult.Success -> {
                        loadPinPayload(result.target)
                    }
                    is PrepareResolveTargetResult.Failed -> {
                        showError(result.error)
                        tvStatus.text = result.error
                        DebugSessionLog.append(
                            "RESOLVE",
                            "Resolve target preparation failed for $pinId: ${result.error}"
                        )
                        if (fallbackToLocalOnFailure) {
                            loadLocalPinList()
                        }
                    }
                }
            }
        }
    }

    private fun resolvePreparationStatus(progress: PrepareResolveTargetProgress): String =
        when (progress) {
            is PrepareResolveTargetProgress.LoadingLocal -> "Loading saved pin..."
            is PrepareResolveTargetProgress.CheckingCloud -> "Checking cloud pin..."
            is PrepareResolveTargetProgress.UsingCachedPayload -> "Using saved cloud copy..."
            is PrepareResolveTargetProgress.DownloadingPayload -> "Downloading pin..."
        }

    private fun loadLocalPinList() {
        val savedPins = LocalPinDisplayGrouper.group(NimpuSpatialSdk.listLocalPins(this))

        if (savedPins.isEmpty()) {
            showError("No saved pins found in memory.")
            tvStatus.text = "No saved pins."
            DebugSessionLog.append("RESOLVE", "Top status (setup): No saved pins.")
            resolveLoadStarted = false
            return
        }

        val names = savedPins.map { it.displayName }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Select a Pin to Resolve")
            .setItems(names) { _, which ->
                prepareSelectedPinForResolve(savedPins[which], fallbackToLocalOnFailure = false)
            }
            .setOnCancelListener {
                resolveLoadStarted = false
                tvStatus.text = "Pin selection cancelled."
                DebugSessionLog.append("RESOLVE", "Local pin selection cancelled")
            }
            .show()
    }

    private fun loadPinPayload(target: ResolveTarget) {
        val pinId = target.pinId
        val session = arSession ?: run {
            resolveLoadStarted = false
            return
        }
        val hasCoarseGuidance = shouldUseGeospatialGuidance() && target.geospatialMetadata != null
        val setupStatus = if (hasCoarseGuidance) {
            "Loaded $pinId, preparing guidance..."
        } else {
            "Loaded $pinId, preparing local search..."
        }
        tvStatus.text = setupStatus
        DebugSessionLog.append("RESOLVE", "Top status (setup): $setupStatus")

        clearActiveResolveSession()
        val sdkSession = spatialSession
            ?: NimpuSpatialSdk.newSession(NimpuSpatialMode.RESOLVE_PIN, targetPinId = pinId)
        val resolveSession = sdkSession.newResolveSession(target)
        activeResolveSession = resolveSession

        resolveStateObservation = resolveSession.observeResolveState { state ->
            runOnUiThread {
                if (mode == MODE_RESOLVE_PIN) {
                    renderResolveState(state)
                }
            }
        }
        resolveResultObservation = resolveSession.observeResolveResult { result ->
            runOnUiThread {
                when (result) {
                    is ResolveEngineResult.Resolved -> {
                        val anchor = session.createAnchor(result.pose)
                        renderer.setResolvedAnchor(anchor)
                        NimpuSpatialSdk.reportCloudResolveResult(target, result)
                    }
                    is ResolveEngineResult.Failed -> {
                        renderer.onResolveFailed(result.reason)
                        NimpuSpatialSdk.reportCloudResolveResult(target, result)
                    }
                }
            }
        }
        resolveGuidanceObservation = resolveSession.attachResolveGuidanceView(resolveHudOverlay)

        renderer.configureResolveTarget(pinId, target.geospatialMetadata, resolveSession)
    }

    private fun showSaveDialogDone(dialog: AlertDialog, onDone: (() -> Unit)? = null) {
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).apply {
            visibility = View.VISIBLE
            isEnabled = true
            setOnClickListener {
                shareDebugLog()
            }
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
            text = "Done"
            isEnabled = true
            setOnClickListener {
                dialog.dismiss()
                onDone?.invoke()
            }
        }
    }

    private fun resetCreateSaveUi() {
        isSavingPin = false
        activeCreateSession.resumeCaptureScanning("save flow reset")
        btnSavePin.isEnabled = true
        btnSavePin.text = "Save Pin"
        progressSavePin.visibility = View.GONE
        lastMappingUiState?.let(::renderMappingUi)
    }

    private fun defaultPinName(): String {
        return NimpuSpatialSdk.defaultPinDisplayName(this)
    }

    private fun hasCloudConfig(): Boolean {
        val config = NimpuSpatialSdk.currentConfig()
        return config.isCloudEnabled
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun shouldUseGeospatialGuidance(): Boolean =
        NimpuSpatialSdk.currentConfig().geospatialGuidanceMode == GeospatialGuidanceMode.ENABLED

    private fun buildArPermissionsRequest(): Array<String> = buildList {
        if (!hasCameraPermission()) add(Manifest.permission.CAMERA)
        if (shouldUseGeospatialGuidance() && !hasLocationPermission()) add(Manifest.permission.ACCESS_FINE_LOCATION)
    }.toTypedArray()

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == AR_PERMISSIONS_CODE) {
            if (!hasCameraPermission()) {
                showError("Camera permission is required for AR.")
                finish()
            } else if (shouldUseGeospatialGuidance() && !hasLocationPermission()) {
                Toast.makeText(this, "Location denied. Geospatial guidance will be unavailable.", Toast.LENGTH_LONG).show()
                resumeArSession()
            } else {
                arSession?.close()
                arSession = null
                resumeArSession()
            }
        }
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun updateResolveActionButtons(state: ResolvePinState) {
        btnTryAgain.visibility = if (state.showTryAgain) View.VISIBLE else View.GONE
        btnStartPreciseSearch.visibility = if (state.showStartPreciseSearch) View.VISIBLE else View.GONE
        btnStartPreciseSearch.text = if (
            state.preciseSearchActive ||
            state.phase == ResolveWorkflowState.LOCAL_RESOLVE_FAILED_FALLBACK
        ) {
            "Restart Search"
        } else {
            "Start Precise Search"
        }
    }

    private fun shareDebugLog() {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Spatial SDK Sample Debug Log")
            putExtra(Intent.EXTRA_TEXT, DebugSessionLog.snapshot())
        }
        startActivity(Intent.createChooser(shareIntent, "Share debug log"))
    }
}
