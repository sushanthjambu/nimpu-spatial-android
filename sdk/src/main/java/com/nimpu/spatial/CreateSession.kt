package com.nimpu.spatial.sdk

import android.content.Context
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.view.View
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.math.sqrt

class CreateSession internal constructor(
    private val config: NimpuSpatialConfig,
    private val createCaptureCore: CreateCaptureCore = DefaultCreateCaptureCore
) {
    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val stateObservers = linkedSetOf<(CreatePinState) -> Unit>()
    private val diagnosticsObservers = linkedSetOf<(CreateDiagnostics) -> Unit>()
    private var keyframeCapture: KeyframeCapture? = null
    private var lastState: CreatePinState? = null
    private var lastDiagnostics: CreateDiagnostics? = null
    @Volatile
    private var capturePaused = false

    private val keyframeResults: List<KeyframeCapture.KeyframeResult>
        get() = keyframeCapture?.results ?: emptyList()

    val isGeospatialGuidanceEnabled: Boolean
        get() = config.isGeospatialGuidanceEnabled

    fun clearCapture() {
        keyframeCapture?.close()
        keyframeCapture = null
        capturePaused = false
    }

    fun pauseCaptureScanning(reason: String? = null) {
        if (capturePaused) return
        capturePaused = true
        keyframeCapture?.pauseProcessing()
        DebugSessionLog.append(
            "CREATE",
            "Capture scanning paused${reason?.let { ": $it" } ?: ""}"
        )
    }

    fun resumeCaptureScanning(reason: String? = null) {
        if (!capturePaused) return
        capturePaused = false
        DebugSessionLog.append(
            "CREATE",
            "Capture scanning resumed${reason?.let { ": $it" } ?: ""}"
        )
    }

    fun isCaptureScanningPaused(): Boolean = capturePaused

    fun startGuidedScan(
        frame: Frame,
        anchorPose: Pose,
        placementReferenceAngleDeg: Double
    ): CreatePinState {
        if (!hasNativeCreateEntitlement()) {
            DebugSessionLog.append("ENTITLEMENT", "Create scan blocked: SDK core has no create entitlement")
            return publishCreateState(
                CreatePinState(
                    phase = CreatePinPhase.TIMED_OUT,
                    progressMessage = "SDK activation required.",
                    instructionMessage = "Connect to Nimpu Spatial Cloud with a valid API key.",
                    coveredSectors = 0,
                    totalSectors = 0,
                    canSave = false,
                    timedOut = true
                )
            )
        }
        capturePaused = false
        val capture = createCaptureCore.newKeyframeCapture().apply {
            start(frame, anchorPose, placementReferenceAngleDeg)
        }
        keyframeCapture?.close()
        keyframeCapture = capture
        return publishCreateState(
            CreatePinState(
                phase = CreatePinPhase.GUIDED_SCANNING,
                progressMessage = capture.scanStatus.progressMessage,
                instructionMessage = "Move left and right around the pin.",
                coveredSectors = 0,
                totalSectors = capture.scanStatus.totalSectors
            )
        )
    }

    fun processFrame(
        frame: Frame,
        anchorPose: Pose,
        viewMatrix: FloatArray,
        projMatrix: FloatArray,
        currentPhase: CreatePinPhase
    ): CreatePinState? {
        val capture = keyframeCapture ?: return null
        if (capturePaused) return lastState
        PerfLog.begin("createProcessFrame")
        return try {
            val tickResult = capture.tick(frame, anchorPose, viewMatrix, projMatrix)
            val status = capture.scanStatus
            val state = when (tickResult) {
                KeyframeCapture.TickResult.SCAN_READY -> {
                    CreatePinState(
                        phase = CreatePinPhase.SCAN_READY,
                        progressMessage = status.progressMessage,
                        instructionMessage = "Good scan. You can save now.",
                        coveredSectors = status.coveredSectors,
                        totalSectors = status.totalSectors,
                        currentSector = status.currentSector,
                        acceptedSectorMask = status.sectorMask,
                        attemptedSectorMask = status.attemptedSectorMask,
                        canSave = true,
                        timedOut = false,
                        diagnostics = createWorkerDiagnostics(capture)
                    )
                }
                KeyframeCapture.TickResult.TIMEOUT -> {
                    CreatePinState(
                        phase = CreatePinPhase.TIMED_OUT,
                        progressMessage = status.progressMessage,
                        instructionMessage = status.instructionMessage,
                        coveredSectors = status.coveredSectors,
                        totalSectors = status.totalSectors,
                        currentSector = status.currentSector,
                        acceptedSectorMask = status.sectorMask,
                        attemptedSectorMask = status.attemptedSectorMask,
                        canSave = false,
                        timedOut = true,
                        diagnostics = createWorkerDiagnostics(capture)
                    )
                }
                else -> {
                    val showSave = status.ready
                    CreatePinState(
                        phase = if (showSave) CreatePinPhase.SCAN_READY else currentPhase,
                        progressMessage = status.progressMessage,
                        instructionMessage = status.instructionMessage,
                        coveredSectors = status.coveredSectors,
                        totalSectors = status.totalSectors,
                        currentSector = status.currentSector,
                        acceptedSectorMask = status.sectorMask,
                        attemptedSectorMask = status.attemptedSectorMask,
                        canSave = showSave,
                        timedOut = false,
                        diagnostics = createWorkerDiagnostics(capture)
                    )
                }
            }
            publishCreateState(state)
        } finally {
            PerfLog.end("createProcessFrame", minLogMs = 8.0)
        }
    }

    private fun hasNativeCreateEntitlement(): Boolean =
        NativeBridge.hasEntitlementFeature(EntitlementFeature.OFFLINE_CLOUD_CREATE.wireValue) ||
            NativeBridge.hasEntitlementFeature(EntitlementFeature.STANDALONE_LOCAL_CREATE.wireValue)

    fun savePin(
        context: Context,
        anchorPose: Pose,
        geospatialMetadata: GeospatialPinMetadata?,
        displayName: String,
        uploadToCloud: Boolean = true,
        callback: (CreatePinResult) -> Unit
    ) {
        when (val buildResult = buildPayload(anchorPose, geospatialMetadata)) {
            is CreatePayloadBuildResult.Success -> {
                NimpuSpatialSdk.saveCreatedPin(
                    context = context,
                    payload = buildResult.payload,
                    displayName = displayName,
                    uploadToCloud = uploadToCloud,
                    captureTelemetry = buildResult.captureTelemetry,
                    callback = callback
                )
            }
            is CreatePayloadBuildResult.Failed -> {
                callback(CreatePinResult.Failed(localPinId = null, error = buildResult.message))
            }
        }
    }

    internal fun buildPayload(
        anchorPose: Pose,
        geospatialMetadata: GeospatialPinMetadata?
    ): CreatePayloadBuildResult {
        val keyframes = keyframeResults
        if (keyframes.isEmpty()) {
            DebugSessionLog.append("CREATE", "Payload build failed: no keyframes captured")
            return CreatePayloadBuildResult.Failed("No keyframes captured.")
        }
        DebugSessionLog.append("CREATE", "Payload build started: keyframes=${keyframes.size}")

        val allPoints3D = mutableListOf<FloatArray>()
        val allDescriptors = mutableListOf<ByteArray>()

        for (keyframe in keyframes) {
            val descriptorSize = keyframe.featureResult.descSize
            for (resolvedPoint in keyframe.resolvedPoints) {
                allPoints3D.add(
                    floatArrayOf(
                        resolvedPoint.worldX,
                        resolvedPoint.worldY,
                        resolvedPoint.worldZ
                    )
                )
                val descriptorStart = resolvedPoint.descriptorIndex * descriptorSize
                val descriptorEnd = descriptorStart + descriptorSize
                if (descriptorEnd <= keyframe.featureResult.descriptors.size) {
                    allDescriptors.add(
                        keyframe.featureResult.descriptors.copyOfRange(
                            descriptorStart,
                            descriptorEnd
                        )
                    )
                }
            }
        }

        PerfLog.log("payloadSave", "points=${allPoints3D.size}")
        Log.d("NimpuDebug", "=== CREATE PIN SAVE ===")
        val activeProfile = TrackingConfig.algorithm
        Log.d("NimpuDebug", "Spatial payload profile loaded")
        Log.d("NimpuDebug", "Candidate point count before filtering: ${allPoints3D.size}")

        filterOutlierPoints(allPoints3D, allDescriptors)

        if (allPoints3D.isEmpty()) {
            DebugSessionLog.append("CREATE", "Payload build failed: no resolved 3D points")
            return CreatePayloadBuildResult.Failed(
                "No 3D points resolved. Try scanning in a more textured area."
            )
        }

        val pinPose = floatArrayOf(
            anchorPose.tx(), anchorPose.ty(), anchorPose.tz(),
            anchorPose.qx(), anchorPose.qy(), anchorPose.qz(), anchorPose.qw()
        )

        val payload = PointCloudPayload(
            pinPose = pinPose,
            points3D = allPoints3D,
            descriptors = allDescriptors,
            cameraIntrinsics = keyframes.last().cameraIntrinsics,
            timestamp = System.currentTimeMillis(),
            algorithmId = activeProfile.id,
            descriptorSize = activeProfile.descSize,
            geospatialMetadata = geospatialMetadata,
            captureMetadata = CaptureMetadata(
                coveredSectors = keyframes.size,
                pointCount = allPoints3D.size,
                schemaVersion = 2
            )
        )
        val scanStatus = keyframeCapture?.scanStatus
        val captureTelemetry = CreateCaptureTelemetry(
            coveredSectors = keyframes.size,
            acceptedSectorMask = scanStatus?.sectorMask?.toBitMask(),
            attemptedSectorMask = scanStatus?.attemptedSectorMask?.toBitMask()
        )

        val integrity = PayloadIntegrity.compute(payload)
        DebugSessionLog.append(
            "CREATE",
            "Payload ready: points=${payload.points3D.size} " +
                "sectors=${payload.captureMetadata.coveredSectors} hash=${integrity.shortHash}"
        )
        return CreatePayloadBuildResult.Success(payload, captureTelemetry)
    }

    fun observeCreateState(observer: (CreatePinState) -> Unit): CreateObservation {
        val currentState = synchronized(lock) {
            stateObservers.add(observer)
            lastState
        }
        currentState?.let(observer)
        return CreateObservation {
            synchronized(lock) {
                stateObservers.remove(observer)
            }
        }
    }

    fun observeCreateDiagnostics(observer: (CreateDiagnostics) -> Unit): CreateObservation {
        val currentDiagnostics = synchronized(lock) {
            diagnosticsObservers.add(observer)
            lastDiagnostics
        }
        currentDiagnostics?.let(observer)
        return CreateObservation {
            synchronized(lock) {
                diagnosticsObservers.remove(observer)
            }
        }
    }

    fun createStateFlow(): Flow<CreatePinState> =
        callbackFlow {
            val observation = observeCreateState { state ->
                trySend(state)
            }
            awaitClose { observation.dispose() }
        }

    fun createDiagnosticsFlow(): Flow<CreateDiagnostics> =
        callbackFlow {
            val observation = observeCreateDiagnostics { diagnostics ->
                trySend(diagnostics)
            }
            awaitClose { observation.dispose() }
        }

    fun attachCreateCoverageView(view: CreatePinCoverageView): CreateObservation {
        return observeCreateState { state ->
            mainHandler.post {
                if (state.totalSectors > 0) {
                    view.visibility = View.VISIBLE
                    view.bind(state)
                } else {
                    view.visibility = View.GONE
                }
            }
        }
    }

    fun publishCreateState(state: CreatePinState): CreatePinState {
        val observers = synchronized(lock) {
            lastState = state
            stateObservers.toList()
        }
        observers.forEach { it(state) }
        return state
    }

    fun publishCreateDiagnostics(diagnostics: CreateDiagnostics): CreateDiagnostics {
        val observers = synchronized(lock) {
            lastDiagnostics = diagnostics
            diagnosticsObservers.toList()
        }
        observers.forEach { it(diagnostics) }
        return diagnostics
    }

    private fun createWorkerDiagnostics(capture: KeyframeCapture): CreateDiagnostics =
        CreateDiagnostics(worker = capture.workerDiagnostics())

    private fun filterOutlierPoints(
        points3D: MutableList<FloatArray>,
        descriptors: MutableList<ByteArray>
    ) {
        val outlierRadiusM = 3.0f
        if (points3D.size <= 3) return

        val xs = points3D.map { it[0] }.sorted()
        val ys = points3D.map { it[1] }.sorted()
        val zs = points3D.map { it[2] }.sorted()
        val mid = points3D.size / 2
        val medX = xs[mid]
        val medY = ys[mid]
        val medZ = zs[mid]

        val filtered = points3D.zip(descriptors).filter { (point, _) ->
            val dx = point[0] - medX
            val dy = point[1] - medY
            val dz = point[2] - medZ
            sqrt((dx * dx + dy * dy + dz * dz).toDouble()) <= outlierRadiusM
        }
        points3D.clear()
        descriptors.clear()
        filtered.forEach { (point, descriptor) ->
            points3D.add(point)
            descriptors.add(descriptor)
        }
        DebugSessionLog.append(
            "CREATE",
            "Outlier filter: before=${xs.size} after=${points3D.size} radius=${"%.1f".format(outlierRadiusM)}m"
        )
        Log.d(
            "NimpuDebug",
            "Points after outlier filter (radius=${outlierRadiusM}m): ${points3D.size} " +
                "(median=[${"%+.2f".format(medX)}, ${"%+.2f".format(medY)}, ${"%+.2f".format(medZ)}])"
        )
    }
}

private fun List<Boolean>.toBitMask(): Int =
    foldIndexed(0) { index, mask, isSet ->
        if (isSet) mask or (1 shl index) else mask
    }

internal sealed class CreatePayloadBuildResult {
    data class Success(
        val payload: PointCloudPayload,
        val captureTelemetry: CreateCaptureTelemetry
    ) : CreatePayloadBuildResult()
    data class Failed(val message: String) : CreatePayloadBuildResult()
}

internal data class CreateCaptureTelemetry(
    val coveredSectors: Int? = null,
    val acceptedSectorMask: Int? = null,
    val attemptedSectorMask: Int? = null
)

class CreateObservation internal constructor(
    private val onDispose: () -> Unit
) {
    private var disposed = false

    fun dispose() {
        if (disposed) return
        disposed = true
        onDispose()
    }
}
