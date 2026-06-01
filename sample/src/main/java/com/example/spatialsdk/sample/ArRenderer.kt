package com.example.spatialsdk.sample

import android.location.Location
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import com.google.ar.core.Anchor
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.exceptions.SessionPausedException
import com.nimpu.spatial.sdk.CreateDiagnostics
import com.nimpu.spatial.sdk.CreatePinPhase
import com.nimpu.spatial.sdk.CreatePinState
import com.nimpu.spatial.sdk.CreateSession
import com.nimpu.spatial.sdk.DebugSessionLog
import com.nimpu.spatial.sdk.DefaultResolvedPinMarkerRenderer
import com.nimpu.spatial.sdk.GeospatialAccuracy
import com.nimpu.spatial.sdk.GeospatialPinMetadata
import com.nimpu.spatial.sdk.ResolveDiagnostics
import com.nimpu.spatial.sdk.ResolveGuidanceIndicatorState
import com.nimpu.spatial.sdk.ResolveHandoffInput
import com.nimpu.spatial.sdk.ResolvedPinMarkerRenderer
import com.nimpu.spatial.sdk.ResolvePinState
import com.nimpu.spatial.sdk.ResolveSession
import com.nimpu.spatial.sdk.ResolveWorkflowState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.acos
import kotlin.math.abs
import kotlin.math.sqrt
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * GLSurfaceView.Renderer that draws the ARCore camera background,
 * manages pin placement, and orchestrates the guided mapping flow.
 */
class ArRenderer(
    private val onFirstPlaneDetected: () -> Unit,
    private val createSession: CreateSession,
    private val onUiStateChanged: (CreatePinState) -> Unit,
    private val onCreateGeospatialInfoChanged: (CreateDiagnostics) -> Unit = { _ -> },
    private val pinRenderer: ResolvedPinMarkerRenderer = DefaultResolvedPinMarkerRenderer()
) : GLSurfaceView.Renderer {

    companion object {
        private const val TAG = "ArRenderer"
        private const val WARMUP_MIN_ROTATION_DEG = 15f
        private const val WARMUP_MIN_TRANSLATION_M = 0.10f
        private const val ANCHOR_SETTLE_DRIFT_M = 0.02f
        private const val ANCHOR_SETTLE_STABLE_MS = 900L
        private const val RELIABLE_HORIZONTAL_ACCURACY_M = 15.0
        private const val RELIABLE_VERTICAL_ACCURACY_M = 12.0

        // Coarse geospatial pin scaling
        private const val COARSE_PIN_SCALE_MIN = 8f
        private const val COARSE_PIN_SCALE_MAX = 45f
        private const val COARSE_PIN_SCALE_DEFAULT = 10f
        private const val COARSE_PIN_SCALE_PER_METER = 0.15f

        fun makeDirectFloatBuffer(data: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(data.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(data)
                    position(0)
                }
    }

    var mappingState: CreatePinPhase = CreatePinPhase.WARMING_UP
        private set

    var session: Session? = null
    @Volatile var displayRotation: Int = 0
    @Volatile var paused: Boolean = true

    var latestFrame: Frame? = null
        private set

    @Volatile var pendingTap: FloatArray? = null

    private var anchor: Anchor? = null
    var resolveSession: ResolveSession? = null

    private var cameraTextureId = -1
    private var shaderProgram = 0
    private var planeNotified = false
    private var viewportWidth = 0
    private var viewportHeight = 0
    private var viewportChanged = true

    private var warmupStartTranslation: FloatArray? = null
    private var warmupStartRotation: FloatArray? = null
    private var placementReferenceAngleDeg = 0.0
    private var placementGeospatialMetadata: GeospatialPinMetadata? = null
    private var lastSettlingAnchorTranslation: FloatArray? = null
    private var settlingStableSinceMs = 0L
    private var lastUiState: CreatePinState? = null
    private var coarseGeospatialAnchor: Anchor? = null
    private var resolvePinId: String? = null
    private var resolveTargetMetadata: GeospatialPinMetadata? = null
    private var resolveWorkflowState: ResolveWorkflowState = ResolveWorkflowState.IDLE
    private var resolveStateMessage: String = "Waiting for target metadata..."
    private var resolveEarthStatus: String = "Earth status unavailable."
    private var lastKnownResolveDistanceMeters: Float? = null
    private var lastLoggedResolveDistanceBucket: Int? = null
    private var lastLoggedAltitudeBucket: Int? = null
    private var lastCoarseIndicatorVisible: Boolean? = null
    private var lastCreateGeospatialInfo: CreateDiagnostics? = null
    private var lastLoggedCreateStateSummary: String? = null
    private var lastLoggedCreateGeospatialSummary: String? = null
    private var forcePreciseSearchRequested = false
    private var forcePreciseSearchLogged = false
    private var lastPreciseAnchorTrackingState: TrackingState? = null
    private var pinRendererAvailable = true

    // Reusable arrays for coarse indicator projection (avoid per-frame allocation)
    private val projTempVec4 = FloatArray(4)
    private val projTempResult = FloatArray(4)
    private val projTempMvp = FloatArray(16)

    private val QUAD_COORDS = floatArrayOf(
        -1f, -1f,
        +1f, -1f,
        -1f, +1f,
        +1f, +1f
    )
    private val quadVertices: FloatBuffer = makeDirectFloatBuffer(QUAD_COORDS)
    private val ndcQuadInput: FloatBuffer = makeDirectFloatBuffer(QUAD_COORDS)
    private val transformedTexCoords: FloatBuffer = makeDirectFloatBuffer(FloatArray(8))

    init {
        createSession.observeCreateState(::dispatchUiState)
        createSession.observeCreateDiagnostics(::dispatchCreateGeospatialInfo)
    }

    private val VERTEX_SHADER = """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = aPosition;
            vTexCoord = aTexCoord;
        }
    """.trimIndent()

    private val FRAGMENT_SHADER = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTexCoord;
        uniform samplerExternalOES uTexture;
        void main() {
            gl_FragColor = texture2D(uTexture, vTexCoord);
        }
    """.trimIndent()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)

        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        cameraTextureId = texIds[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        session?.setCameraTextureName(cameraTextureId)
        shaderProgram = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        initPinRenderer()
        publishUiState(
            CreatePinState(
                phase = CreatePinPhase.WARMING_UP,
                progressMessage = "Finding a stable surface",
                instructionMessage = "Move your phone slowly."
            )
        )
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewportWidth = width
        viewportHeight = height
        viewportChanged = true
    }

    override fun onDrawFrame(gl: GL10?) {
        PerfLog.begin("onDrawFrame")
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        if (paused) {
            PerfLog.end("onDrawFrame")
            return
        }
        val sess = session ?: run {
            PerfLog.end("onDrawFrame")
            return
        }

        if (viewportChanged) {
            sess.setDisplayGeometry(displayRotation, viewportWidth, viewportHeight)
            viewportChanged = false
        }

        val frame: Frame = try {
            sess.update()
        } catch (e: CameraNotAvailableException) {
            PerfLog.end("onDrawFrame")
            return
        } catch (e: SessionPausedException) {
            PerfLog.end("onDrawFrame")
            return
        }
        latestFrame = frame
        publishCreateGeospatialInfo(buildCreateGeospatialInfo())

        if (frame.camera.trackingState != TrackingState.TRACKING) {
            publishUiState(
                CreatePinState(
                    phase = CreatePinPhase.WARMING_UP,
                    progressMessage = "Finding a stable surface",
                    instructionMessage = "Move your phone slowly."
                )
            )
            PerfLog.end("onDrawFrame")
            return
        }

        drawCameraBackground(frame)

        if (!planeNotified) {
            val planes = sess.getAllTrackables(Plane::class.java)
            if (planes.any { it.trackingState == TrackingState.TRACKING }) {
                planeNotified = true
                onFirstPlaneDetected()
            }
        }

        if (anchor == null) {
            updateWarmupState(frame, sess)
            processPendingTap(frame)
        }

        val currentAnchor = anchor
        var viewMatrix: FloatArray? = null
        var projMatrix: FloatArray? = null
        if (currentAnchor != null && currentAnchor.trackingState == TrackingState.TRACKING) {
            if (lastPreciseAnchorTrackingState != TrackingState.TRACKING) {
                val t = currentAnchor.pose.translation
                Log.d(
                    TAG,
                    "Precise anchor tracking; drawing pin at [${"%.3f".format(t[0])}, ${"%.3f".format(t[1])}, ${"%.3f".format(t[2])}]"
                )
                lastPreciseAnchorTrackingState = TrackingState.TRACKING
            }
            val camera = frame.camera
            viewMatrix = FloatArray(16)
            projMatrix = FloatArray(16)
            camera.getViewMatrix(viewMatrix, 0)
            camera.getProjectionMatrix(projMatrix, 0, 0.01f, 100f)
            drawPin(currentAnchor.pose, viewMatrix, projMatrix)
        } else if (viewMatrix == null || projMatrix == null) {
            currentAnchor?.let {
                if (lastPreciseAnchorTrackingState != it.trackingState) {
                    Log.d(TAG, "Precise anchor not drawing; trackingState=${it.trackingState}")
                    lastPreciseAnchorTrackingState = it.trackingState
                }
            }
            val camera = frame.camera
            viewMatrix = FloatArray(16)
            projMatrix = FloatArray(16)
            camera.getViewMatrix(viewMatrix, 0)
            camera.getProjectionMatrix(projMatrix, 0, 0.01f, 100f)
        }

        val currentCoarseAnchor = coarseGeospatialAnchor
        if (anchor == null && currentCoarseAnchor != null && currentCoarseAnchor.trackingState == TrackingState.TRACKING &&
            viewMatrix != null && projMatrix != null
        ) {
            val coarseModelMatrix = FloatArray(16)
            currentCoarseAnchor.pose.toMatrix(coarseModelMatrix, 0)
            val scale = computeCoarsePinScale(lastKnownResolveDistanceMeters)
            Matrix.scaleM(coarseModelMatrix, 0, scale, scale, scale)
            drawPinWithMatrix(coarseModelMatrix, viewMatrix, projMatrix)

            // Compute and publish off-screen indicator state
            val indicatorState = computeCoarseIndicatorState(
                currentCoarseAnchor.pose, viewMatrix, projMatrix
            )
            publishCoarseIndicator(indicatorState)
        } else if (anchor != null || currentCoarseAnchor == null ||
            currentCoarseAnchor.trackingState != TrackingState.TRACKING
        ) {
            // Coarse anchor not active or precise pin locked — hide indicator
            publishCoarseIndicator(ResolveGuidanceIndicatorState(visible = false))
        }

        if (currentAnchor != null && currentAnchor.trackingState == TrackingState.TRACKING) {
            when (mappingState) {
                CreatePinPhase.ANCHOR_SETTLING -> updateAnchorSettling(currentAnchor)
                CreatePinPhase.GUIDED_SCANNING, CreatePinPhase.SCAN_READY -> {
                    if (viewMatrix != null && projMatrix != null) {
                        val state = createSession.processFrame(
                            frame = frame,
                            anchorPose = currentAnchor.pose,
                            viewMatrix = viewMatrix,
                            projMatrix = projMatrix,
                            currentPhase = mappingState
                        )
                        state?.let { mappingState = it.phase }
                    }
                }
                else -> Unit
            }
        }

        if (resolveSession != null) {
            updateResolveWorkflow()
        }

        val activeResolveSession = resolveSession
        if (activeResolveSession != null && anchor == null && resolveWorkflowState == ResolveWorkflowState.LOCAL_RESOLVE_SEARCHING) {
            try {
                val image = frame.acquireCameraImage()
                val intrinsics = frame.camera.imageIntrinsics
                val fl = intrinsics.focalLength
                val pp = intrinsics.principalPoint
                activeResolveSession.processFrame(
                    image,
                    frame.camera.pose.translation,
                    frame.camera.pose.rotationQuaternion,
                    liveFx = fl[0], liveFy = fl[1],
                    liveCx = pp[0], liveCy = pp[1]
                )
            } catch (_: NotYetAvailableException) {
            } catch (_: Exception) {
            }
        }

        PerfLog.tickFrame()
        PerfLog.end("onDrawFrame")
    }

    fun getAnchor(): Anchor? = anchor

    fun getPlacementGeospatialMetadata(): GeospatialPinMetadata? = placementGeospatialMetadata

    fun configureResolveTarget(
        pinId: String,
        metadata: GeospatialPinMetadata?,
        session: ResolveSession
    ) {
        val effectiveMetadata = if (session.isGeospatialGuidanceEnabled) {
            metadata
        } else {
            if (metadata != null) {
                DebugSessionLog.append(
                    "RESOLVE",
                    "Ignoring saved geospatial metadata because guidance is disabled by configuration"
                )
            }
            null
        }
        coarseGeospatialAnchor?.detach()
        coarseGeospatialAnchor = null
        resolvePinId = pinId
        resolveTargetMetadata = effectiveMetadata
        resolveSession = session
        resolveWorkflowState = ResolveWorkflowState.IDLE
        lastKnownResolveDistanceMeters = null
        forcePreciseSearchRequested = false
        forcePreciseSearchLogged = false
        resolveStateMessage = if (effectiveMetadata == null) {
            "No coarse spatial metadata. Preparing local AR pin search."
        } else {
            "Preparing coarse guidance..."
        }
        resolveEarthStatus = buildEarthStatus(null, effectiveMetadata)
        DebugSessionLog.append(
            "RESOLVE",
            "Configured target ${pinId} with ${if (effectiveMetadata == null) "precise local cache" else "coarse geospatial"} guidance"
        )
        if (effectiveMetadata == null) {
            Log.d(TAG, "Resolve target $pinId has no geospatial metadata")
        } else {
            Log.d(
                TAG,
                "Loaded resolve metadata for $pinId: " +
                    "lat=${"%.6f".format(effectiveMetadata.latitude)}, " +
                    "lng=${"%.6f".format(effectiveMetadata.longitude)}, " +
                    "alt=${effectiveMetadata.altitude?.let { "%.2f".format(it) } ?: "n/a"}, " +
                    "heading=${effectiveMetadata.headingDegrees?.let { "%.1f".format(it) } ?: "n/a"}, " +
                    "hAcc=${effectiveMetadata.horizontalAccuracyMeters?.let { "%.1f".format(it) } ?: "n/a"}, " +
                    "vAcc=${effectiveMetadata.verticalAccuracyMeters?.let { "%.1f".format(it) } ?: "n/a"}, " +
                    "reliable=${effectiveMetadata.reliableLocalization}"
            )
        }
        resolveSession?.publishResolveState(
            ResolvePinState(
                phase = resolveWorkflowState,
                message = resolveStateMessage,
                earthStatus = resolveEarthStatus,
                showStartPreciseSearch = true
            )
        )
    }

    fun onResolveFailed(message: String) {
        DebugSessionLog.append("RESOLVE", "Local resolve failed: $message")
        publishResolveState(
            ResolveWorkflowState.LOCAL_RESOLVE_FAILED_FALLBACK,
            if (resolveTargetMetadata == null) {
                "Precise AR pin resolve failed: $message"
            } else {
                "Precise AR pin not found. Falling back to coarse guidance."
            },
            buildEarthStatus(session?.earth?.takeIf { it.trackingState == TrackingState.TRACKING }?.cameraGeospatialPose, resolveTargetMetadata)
        )
    }

    fun setResolvedAnchor(resolvedAnchor: Anchor) {
        this.anchor?.detach()
        this.anchor = resolvedAnchor
        coarseGeospatialAnchor?.detach()
        coarseGeospatialAnchor = null
        resolveWorkflowState = ResolveWorkflowState.LOCAL_RESOLVE_LOCKED
        resolveStateMessage = "AR pin resolved precisely."
        resolveEarthStatus = "Precise local pin locked. Coarse geospatial anchor detached."
        lastPreciseAnchorTrackingState = null
        val t = resolvedAnchor.pose.translation
        Log.d(
            TAG,
            "Resolved anchor set at [${"%.3f".format(t[0])}, ${"%.3f".format(t[1])}, ${"%.3f".format(t[2])}] trackingState=${resolvedAnchor.trackingState}"
        )
        DebugSessionLog.append("RESOLVE", "Precise AR pin locked")
        resolveSession?.publishResolveState(
            ResolvePinState(
                phase = resolveWorkflowState,
                message = resolveStateMessage,
                earthStatus = resolveEarthStatus
            )
        )
    }

    fun retryResolve() {
        if (anchor != null) return
        val metadata = resolveTargetMetadata
        resolveSession?.stopPreciseSearch()
        forcePreciseSearchRequested = false
        forcePreciseSearchLogged = false
        DebugSessionLog.append("RESOLVE", "Retry requested by user")
        publishResolveState(
            ResolveWorkflowState.IDLE,
            if (metadata == null) {
                "Preparing local AR pin search..."
            } else {
                "Preparing coarse guidance..."
            },
            buildEarthStatus(
                session?.earth?.takeIf { it.trackingState == TrackingState.TRACKING }?.cameraGeospatialPose,
                metadata
            )
        )
    }

    fun requestPreciseSearch() {
        if (anchor != null) return
        resolveSession?.restartPreciseSearch()
        forcePreciseSearchRequested = true
        forcePreciseSearchLogged = false
        DebugSessionLog.append("RESOLVE", "Precise search restarted by user")
        updateResolveWorkflow()
    }

    private fun updateWarmupState(frame: Frame, sess: Session) {
        val camPose = frame.camera.pose
        if (warmupStartTranslation == null) {
            warmupStartTranslation = camPose.translation.copyOf()
            warmupStartRotation = camPose.rotationQuaternion.copyOf()
        }

        val trackedPlaneExists = sess.getAllTrackables(Plane::class.java)
            .any { it.trackingState == TrackingState.TRACKING }
        val translationMoved = distance(camPose.translation, warmupStartTranslation!!) >= WARMUP_MIN_TRANSLATION_M
        val rotationMoved = quaternionAngleDegrees(warmupStartRotation!!, camPose.rotationQuaternion) >= WARMUP_MIN_ROTATION_DEG
        val ready = trackedPlaneExists && (translationMoved || rotationMoved)

        mappingState = if (ready) CreatePinPhase.READY_TO_PLACE else CreatePinPhase.WARMING_UP
        publishUiState(
            CreatePinState(
                phase = mappingState,
                progressMessage = if (ready) "Ready to place pin" else "Finding a stable surface",
                instructionMessage = if (ready) "Tap a plane to place the pin." else "Move your phone slowly."
            )
        )
    }

    private fun processPendingTap(frame: Frame) {
        val tap = pendingTap ?: return
        if (mappingState != CreatePinPhase.READY_TO_PLACE) return

        pendingTap = null
        PerfLog.begin("hitTest")
        val hits = frame.hitTest(tap[0], tap[1])
        val planeHit = hits.firstOrNull { hit ->
            val trackable = hit.trackable
            trackable is Plane &&
                trackable.isPoseInPolygon(hit.hitPose) &&
                trackable.trackingState == TrackingState.TRACKING
        }

        if (planeHit != null) {
            anchor = planeHit.createAnchor()
            val anchorPos = planeHit.hitPose.translation
            val camPos = frame.camera.pose.translation
            placementReferenceAngleDeg = Math.toDegrees(
                kotlin.math.atan2((camPos[2] - anchorPos[2]).toDouble(), (camPos[0] - anchorPos[0]).toDouble())
            )
            placementGeospatialMetadata = buildPlacementGeospatialMetadata(planeHit.hitPose)
            lastSettlingAnchorTranslation = anchorPos.copyOf()
            settlingStableSinceMs = System.currentTimeMillis()
            createSession.clearCapture()
            mappingState = CreatePinPhase.ANCHOR_SETTLING
            publishUiState(
                CreatePinState(
                    phase = CreatePinPhase.ANCHOR_SETTLING,
                    progressMessage = "Placing pin",
                    instructionMessage = "Hold steady."
                )
            )
            PerfLog.log("hitTest", "Anchor placed at ${planeHit.hitPose.translation.contentToString()}")
        } else {
            publishUiState(
                CreatePinState(
                    phase = CreatePinPhase.READY_TO_PLACE,
                    progressMessage = "Ready to place pin",
                    instructionMessage = "Couldn't place the pin there. Try another surface."
                )
            )
            PerfLog.log("hitTest", "No plane hit")
        }
        PerfLog.end("hitTest")
    }

    private fun updateAnchorSettling(currentAnchor: Anchor) {
        val now = System.currentTimeMillis()
        val currentTranslation = currentAnchor.pose.translation
        val lastTranslation = lastSettlingAnchorTranslation
        if (lastTranslation != null) {
            val drift = distance(currentTranslation, lastTranslation)
            if (drift > ANCHOR_SETTLE_DRIFT_M) {
                settlingStableSinceMs = now
            }
        }
        lastSettlingAnchorTranslation = currentTranslation.copyOf()

        if (now - settlingStableSinceMs >= ANCHOR_SETTLE_STABLE_MS) {
            val frame = latestFrame ?: return
            mappingState = CreatePinPhase.GUIDED_SCANNING
            createSession.startGuidedScan(
                frame = frame,
                anchorPose = currentAnchor.pose,
                placementReferenceAngleDeg = placementReferenceAngleDeg
            )
        } else {
            publishUiState(
                CreatePinState(
                    phase = CreatePinPhase.ANCHOR_SETTLING,
                    progressMessage = "Placing pin",
                    instructionMessage = "Hold steady."
                )
            )
        }
    }

    private fun updateResolveWorkflow() {
        val activeResolveSession = resolveSession ?: return
        val metadata = resolveTargetMetadata

        if (resolveWorkflowState == ResolveWorkflowState.LOCAL_RESOLVE_FAILED_FALLBACK &&
            !forcePreciseSearchRequested
        ) {
            return
        }

        if (anchor != null) {
            publishResolveState(
                ResolveWorkflowState.LOCAL_RESOLVE_LOCKED,
                "AR pin resolved precisely.",
                "Precise local pin locked. Coarse geospatial anchor detached."
            )
            return
        }

        if (metadata == null) {
            maybeStartResolveEngine()
            publishResolveState(
                ResolveWorkflowState.LOCAL_RESOLVE_SEARCHING,
                "Searching for local AR pin features...",
                buildEarthStatus(null, null)
            )
            return
        }

        ensureCoarseGeospatialAnchor(metadata)

        val earth = session?.earth
        val cameraGeoPose = if (earth != null && earth.trackingState == TrackingState.TRACKING) {
            earth.cameraGeospatialPose
        } else {
            null
        }
        val distanceMeters = cameraGeoPose?.let {
            computeDistanceMeters(it.latitude, it.longitude, metadata.latitude, metadata.longitude)
        } ?: lastKnownResolveDistanceMeters ?: Float.POSITIVE_INFINITY
        if (cameraGeoPose != null) {
            lastKnownResolveDistanceMeters = distanceMeters
        }
        val targetAltitude = metadata.altitude
        val altitudeDelta = if (cameraGeoPose != null && targetAltitude != null) {
            (cameraGeoPose.altitude - targetAltitude).toFloat()
        } else {
            null
        }
        maybeLogResolveSnapshot(distanceMeters, altitudeDelta, metadata, cameraGeoPose != null)

        val newState = activeResolveSession.evaluateWorkflowState(
            ResolveHandoffInput(
                distanceMeters = distanceMeters,
                altitudeDeltaMeters = altitudeDelta,
                coarseAnchorAvailable = coarseGeospatialAnchor?.trackingState == TrackingState.TRACKING,
                localResolveLocked = anchor != null,
                manualArModeActive = forcePreciseSearchRequested
            )
        )

        val message = when (newState) {
            ResolveWorkflowState.TARGET_TOO_FAR ->
                "Target is far away. Coarse geospatial pin shown; use normal maps for navigation."
            ResolveWorkflowState.COARSE_GEOSPATIAL_AVAILABLE ->
                if (isLiveGeospatialReliable(cameraGeoPose, metadata)) {
                    "Coarse AR guidance active for ${resolvePinId ?: "target"}."
                } else {
                    "Rough AR guidance active. Move for better VPS accuracy."
                }
            ResolveWorkflowState.APPROACHING_TARGET ->
                if (isLiveGeospatialReliable(cameraGeoPose, metadata)) {
                    "Approaching target. Keep following the coarse pin."
                } else {
                    "Approaching target. Coarse pin is rough until VPS improves."
                }
            ResolveWorkflowState.LOCAL_RESOLVE_SEARCHING ->
                "Searching for the precise AR pin..."
            ResolveWorkflowState.LOCAL_RESOLVE_LOCKED ->
                "AR pin resolved precisely."
            ResolveWorkflowState.LOCAL_RESOLVE_FAILED_FALLBACK ->
                "Precise AR pin not found. Falling back to coarse guidance."
            ResolveWorkflowState.SESSION_COMPLETE ->
                "Navigation complete."
            ResolveWorkflowState.IDLE ->
                "Preparing coarse guidance..."
        }
        val earthStatus = buildEarthStatus(cameraGeoPose, metadata)

        if (newState == ResolveWorkflowState.LOCAL_RESOLVE_SEARCHING) {
            if (forcePreciseSearchRequested && !forcePreciseSearchLogged) {
                DebugSessionLog.append("RESOLVE", "Forced transition into LOCAL_RESOLVE_SEARCHING")
                forcePreciseSearchLogged = true
            } else if (resolveWorkflowState != ResolveWorkflowState.LOCAL_RESOLVE_SEARCHING) {
                DebugSessionLog.append(
                    "RESOLVE",
                    "Automatic local search started at ${"%.1f".format(distanceMeters)}m"
                )
            }
            maybeStartResolveEngine()
        }
        publishResolveState(
            newState,
            message,
            earthStatus,
            distanceMeters = distanceMeters,
            altitudeDeltaMeters = altitudeDelta,
            coarseAnchorAvailable = coarseGeospatialAnchor?.trackingState == TrackingState.TRACKING
        )
    }

    private fun ensureCoarseGeospatialAnchor(metadata: GeospatialPinMetadata) {
        val sess = session ?: return
        if (coarseGeospatialAnchor != null) return
        val earth = sess.earth ?: return
        if (earth.trackingState != TrackingState.TRACKING) return

        val altitude = metadata.altitude ?: earth.cameraGeospatialPose.altitude
        coarseGeospatialAnchor = try {
            val anchor = earth.createAnchor(
                metadata.latitude,
                metadata.longitude,
                altitude,
                0f,
                0f,
                0f,
                1f
            )
            Log.d(
                TAG,
                "Created coarse WGS84 anchor for ${resolvePinId ?: "target"}: " +
                    "lat=${"%.6f".format(metadata.latitude)}, " +
                    "lng=${"%.6f".format(metadata.longitude)}, " +
                    "alt=${"%.2f".format(altitude)}, " +
                    "savedReliable=${metadata.reliableLocalization}"
            )
            DebugSessionLog.append(
                "RESOLVE",
                "Created coarse WGS84 anchor for ${resolvePinId ?: "target"}"
            )
            anchor
        } catch (_: Exception) {
            null
        }
    }

    private fun maybeStartResolveEngine() {
        resolveSession?.startPreciseSearch()
    }

    private fun computeCoarsePinScale(distanceMeters: Float?): Float {
        if (distanceMeters == null || distanceMeters.isInfinite() || distanceMeters.isNaN()) {
            return COARSE_PIN_SCALE_DEFAULT
        }
        val raw = COARSE_PIN_SCALE_MIN + distanceMeters * COARSE_PIN_SCALE_PER_METER
        return raw.coerceIn(COARSE_PIN_SCALE_MIN, COARSE_PIN_SCALE_MAX)
    }

    /**
     * Projects the coarse anchor into screen space and returns an indicator state.
     * Uses reusable arrays (projTempVec4, projTempResult, projTempMvp) to avoid allocation.
     */
    private fun computeCoarseIndicatorState(
        coarsePose: com.google.ar.core.Pose,
        viewMatrix: FloatArray,
        projMatrix: FloatArray
    ): ResolveGuidanceIndicatorState {
        // Build MVP matrix
        Matrix.multiplyMM(projTempMvp, 0, projMatrix, 0, viewMatrix, 0)

        // World-space position of the coarse anchor
        val pos = coarsePose.translation
        projTempVec4[0] = pos[0]
        projTempVec4[1] = pos[1]
        projTempVec4[2] = pos[2]
        projTempVec4[3] = 1f

        // Project to clip space
        Matrix.multiplyMV(projTempResult, 0, projTempMvp, 0, projTempVec4, 0)

        val clipW = projTempResult[3]

        // Behind camera: w <= 0 means the point is behind or at the camera plane
        val behindCamera = clipW <= 0.001f

        // NDC coordinates (flip direction for behind-camera so the arrow points correctly)
        val ndcX: Float
        val ndcY: Float
        if (behindCamera) {
            // Invert so the edge arrow points away from the actual direction (i.e., behind you)
            ndcX = -(projTempResult[0] / 0.001f).coerceIn(-2f, 2f)
            ndcY = -(projTempResult[1] / 0.001f).coerceIn(-2f, 2f)
        } else {
            ndcX = projTempResult[0] / clipW
            ndcY = projTempResult[1] / clipW
        }

        // On-screen check: NDC in [-1, 1] with a small margin
        val onScreen = !behindCamera && ndcX in -0.95f..0.95f && ndcY in -0.95f..0.95f

        if (onScreen) {
            return ResolveGuidanceIndicatorState(visible = false)
        }

        // Convert NDC to screen-space pixels
        val vw = viewportWidth.toFloat()
        val vh = viewportHeight.toFloat()
        val screenCenterX = vw / 2f
        val screenCenterY = vh / 2f

        // Target point in screen space (unclamped)
        val targetScreenX = screenCenterX + ndcX * screenCenterX
        val targetScreenY = screenCenterY - ndcY * screenCenterY  // Y is flipped in screen coords

        // Direction from screen center to target
        val dirX = targetScreenX - screenCenterX
        val dirY = targetScreenY - screenCenterY
        val dirLen = sqrt((dirX * dirX + dirY * dirY).toDouble()).toFloat()

        if (dirLen < 1f) {
            // Degenerate case — target is at screen center but somehow off-screen
            return ResolveGuidanceIndicatorState(visible = false)
        }

        val normDirX = dirX / dirLen
        val normDirY = dirY / dirLen

        // Only keep away from the physical screen edges here.
        // Activity owns collision avoidance against real overlay views.
        val marginTop = 24f
        val marginBottom = 24f
        val marginSide = 24f

        val safeLeft = marginSide
        val safeRight = vw - marginSide
        val safeTop = marginTop
        val safeBottom = vh - marginBottom

        // Cast ray from screen center along direction, find first intersection with safe rect edge
        val tLeft   = if (normDirX < 0f) (safeLeft - screenCenterX) / normDirX else Float.MAX_VALUE
        val tRight  = if (normDirX > 0f) (safeRight - screenCenterX) / normDirX else Float.MAX_VALUE
        val tTop    = if (normDirY < 0f) (safeTop - screenCenterY) / normDirY else Float.MAX_VALUE
        val tBottom = if (normDirY > 0f) (safeBottom - screenCenterY) / normDirY else Float.MAX_VALUE
        val t = minOf(tLeft, tRight, tTop, tBottom)

        val clampedX = (screenCenterX + normDirX * t).coerceIn(safeLeft, safeRight)
        val clampedY = (screenCenterY + normDirY * t).coerceIn(safeTop, safeBottom)

        // Arrow rotation: point in direction from clamped position toward the actual target
        val rotationDeg = Math.toDegrees(
            kotlin.math.atan2(normDirY.toDouble(), normDirX.toDouble())
        ).toFloat()

        return ResolveGuidanceIndicatorState(
            visible = true,
            screenX = clampedX,
            screenY = clampedY,
            rotationDegrees = rotationDeg
        )
    }

    private fun publishCoarseIndicator(state: ResolveGuidanceIndicatorState) {
        val wasVisible = lastCoarseIndicatorVisible
        if (wasVisible == state.visible && !state.visible) return // both hidden, skip
        if (wasVisible != state.visible) {
            Log.d(TAG, "Coarse indicator ${if (state.visible) "shown" else "hidden"}")
            lastCoarseIndicatorVisible = state.visible
        }
        resolveSession?.publishResolveGuidance(state)
    }

    private fun publishResolveState(
        newState: ResolveWorkflowState,
        message: String,
        earthStatus: String,
        distanceMeters: Float? = lastKnownResolveDistanceMeters,
        altitudeDeltaMeters: Float? = null,
        coarseAnchorAvailable: Boolean = coarseGeospatialAnchor?.trackingState == TrackingState.TRACKING
    ) {
        if (resolveWorkflowState == newState && resolveStateMessage == message && resolveEarthStatus == earthStatus) return
        resolveWorkflowState = newState
        resolveStateMessage = message
        resolveEarthStatus = earthStatus
        Log.d(TAG, "Resolve state -> $newState: $message")
        DebugSessionLog.append("RESOLVE", "$newState | $message | $earthStatus")
        resolveSession?.publishResolveState(
            ResolvePinState(
                phase = newState,
                message = message,
                earthStatus = earthStatus,
                distanceMeters = distanceMeters?.takeIf { it.isFinite() },
                altitudeDeltaMeters = altitudeDeltaMeters,
                coarseAnchorAvailable = coarseAnchorAvailable,
                preciseSearchActive = newState == ResolveWorkflowState.LOCAL_RESOLVE_SEARCHING,
                localResolveLocked = newState == ResolveWorkflowState.LOCAL_RESOLVE_LOCKED,
                showTryAgain = newState == ResolveWorkflowState.LOCAL_RESOLVE_FAILED_FALLBACK,
                showStartPreciseSearch = newState == ResolveWorkflowState.IDLE ||
                    newState == ResolveWorkflowState.COARSE_GEOSPATIAL_AVAILABLE ||
                    newState == ResolveWorkflowState.APPROACHING_TARGET ||
                    newState == ResolveWorkflowState.LOCAL_RESOLVE_SEARCHING ||
                    newState == ResolveWorkflowState.LOCAL_RESOLVE_FAILED_FALLBACK,
                diagnostics = ResolveDiagnostics(
                    lastDistanceMeters = distanceMeters?.takeIf { it.isFinite() },
                    lastAltitudeDeltaMeters = altitudeDeltaMeters
                )
            )
        )
    }

    private fun computeDistanceMeters(
        latA: Double,
        lonA: Double,
        latB: Double,
        lonB: Double
    ): Float {
        val result = FloatArray(1)
        Location.distanceBetween(latA, lonA, latB, lonB, result)
        return result[0]
    }

    private fun buildPlacementGeospatialMetadata(anchorPose: com.google.ar.core.Pose): GeospatialPinMetadata? {
        if (!createSession.isGeospatialGuidanceEnabled) return null
        val earth = session?.earth ?: return null
        if (earth.trackingState != TrackingState.TRACKING) return null

        return try {
            val geospatialPose = earth.getGeospatialPose(anchorPose)
            GeospatialPinMetadata(
                latitude = geospatialPose.latitude,
                longitude = geospatialPose.longitude,
                altitude = geospatialPose.altitude,
                headingDegrees = geospatialPose.heading,
                horizontalAccuracyMeters = geospatialPose.horizontalAccuracy,
                verticalAccuracyMeters = geospatialPose.verticalAccuracy,
                headingAccuracyDegrees = geospatialPose.headingAccuracy,
                reliableLocalization = isReliablePlacementQuality(
                    geospatialPose.horizontalAccuracy,
                    geospatialPose.verticalAccuracy
                )
            ).also { metadata ->
                Log.d(
                    TAG,
                    "Captured pin geospatial metadata: " +
                        "lat=${"%.6f".format(metadata.latitude)}, " +
                        "lng=${"%.6f".format(metadata.longitude)}, " +
                        "alt=${metadata.altitude?.let { "%.2f".format(it) } ?: "n/a"}, " +
                        "heading=${metadata.headingDegrees?.let { "%.1f".format(it) } ?: "n/a"}, " +
                        "hAcc=${metadata.horizontalAccuracyMeters?.let { "%.1f".format(it) } ?: "n/a"}, " +
                        "vAcc=${metadata.verticalAccuracyMeters?.let { "%.1f".format(it) } ?: "n/a"}, " +
                        "headingAcc=${metadata.headingAccuracyDegrees?.let { "%.1f".format(it) } ?: "n/a"}, " +
                        "reliable=${metadata.reliableLocalization}"
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun isLiveGeospatialReliable(
        cameraGeoPose: com.google.ar.core.GeospatialPose?,
        metadata: GeospatialPinMetadata
    ): Boolean {
        if (!metadata.reliableLocalization) return false
        if (cameraGeoPose == null) return false
        return isReliablePlacementQuality(
            cameraGeoPose.horizontalAccuracy,
            cameraGeoPose.verticalAccuracy
        )
    }

    private fun isReliablePlacementQuality(
        horizontalAccuracy: Double,
        verticalAccuracy: Double
    ): Boolean {
        return horizontalAccuracy <= RELIABLE_HORIZONTAL_ACCURACY_M &&
            verticalAccuracy <= RELIABLE_VERTICAL_ACCURACY_M
    }

    private fun buildEarthStatus(
        cameraGeoPose: com.google.ar.core.GeospatialPose?,
        metadata: GeospatialPinMetadata?
    ): String {
        val sess = session
        val earth = sess?.earth
        if (metadata == null) {
            return "No saved geospatial metadata. Resolve is using precise local cache."
        }
        if (sess == null) {
            return "AR session unavailable."
        }
        if (earth == null) {
            return "Earth object unavailable. Geospatial guidance inactive."
        }
        if (earth.trackingState != TrackingState.TRACKING) {
            return "Earth configured, waiting for geospatial tracking."
        }
        if (coarseGeospatialAnchor == null) {
            return "Earth tracking active. Waiting to create coarse WGS84 anchor."
        }
        val liveReliable = cameraGeoPose?.let {
            isReliablePlacementQuality(it.horizontalAccuracy, it.verticalAccuracy)
        } ?: false
        return if (liveReliable && metadata.reliableLocalization) {
            "Earth tracking active. Coarse WGS84 anchor shown with VPS-quality localization."
        } else {
            "Earth tracking active. Coarse WGS84 anchor shown with rough localization."
        }
    }

    private fun maybeLogResolveSnapshot(
        distanceMeters: Float,
        altitudeDelta: Float?,
        metadata: GeospatialPinMetadata,
        livePoseAvailable: Boolean
    ) {
        val distanceBucket = (distanceMeters / 25f).toInt()
        val altitudeBucket = altitudeDelta?.let { (it / 3f).toInt() }
        if (distanceBucket == lastLoggedResolveDistanceBucket && altitudeBucket == lastLoggedAltitudeBucket) return
        lastLoggedResolveDistanceBucket = distanceBucket
        lastLoggedAltitudeBucket = altitudeBucket
        Log.d(
            TAG,
            "Resolve snapshot for ${resolvePinId ?: "target"}: " +
                "distance=${"%.1f".format(distanceMeters)}m, " +
                "altDelta=${altitudeDelta?.let { "%.1f".format(it) } ?: "n/a"}m, " +
                "livePose=$livePoseAvailable, " +
                "savedReliable=${metadata.reliableLocalization}, " +
                "savedHAcc=${metadata.horizontalAccuracyMeters?.let { "%.1f".format(it) } ?: "n/a"}, " +
                "savedVAcc=${metadata.verticalAccuracyMeters?.let { "%.1f".format(it) } ?: "n/a"}"
        )
    }

    private fun publishUiState(newState: CreatePinState) {
        createSession.publishCreateState(newState)
    }

    private fun dispatchUiState(newState: CreatePinState) {
        if (lastUiState == newState) return
        lastUiState = newState
        val visibleSummary = "${newState.phase} | ${newState.progressMessage} | ${newState.instructionMessage}"
        if (lastLoggedCreateStateSummary != visibleSummary) {
            lastLoggedCreateStateSummary = visibleSummary
            DebugSessionLog.append("CREATE", visibleSummary)
        }
        onUiStateChanged(newState)
    }

    private fun publishCreateGeospatialInfo(info: CreateDiagnostics) {
        createSession.publishCreateDiagnostics(info)
    }

    private fun dispatchCreateGeospatialInfo(info: CreateDiagnostics) {
        if (lastCreateGeospatialInfo == info) return
        lastCreateGeospatialInfo = info
        val accuracy = info.geospatialAccuracy ?: return
        val logSummary = createGeospatialLogSummary(accuracy)
        if (lastLoggedCreateGeospatialSummary != logSummary) {
            lastLoggedCreateGeospatialSummary = logSummary
            DebugSessionLog.append(
                "GEO",
                "${accuracy.sourceLabel} | ${accuracy.status} | H=${accuracy.horizontalAccuracyText} V=${accuracy.verticalAccuracyText}"
            )
        }
        onCreateGeospatialInfoChanged(info)
    }

    private fun createGeospatialLogSummary(accuracy: GeospatialAccuracy): String =
        listOf(
            accuracy.sourceLabel,
            accuracy.status,
            accuracy.localizationQualityText,
            accuracy.vpsQualityText,
            accuracy.horizontalAccuracyText.toAccuracyBucketMeters(),
            accuracy.verticalAccuracyText.toAccuracyBucketMeters()
        ).joinToString("|")

    private fun String.toAccuracyBucketMeters(): String {
        val meters = removeSuffix("m").toFloatOrNull() ?: return this
        return when {
            meters < 1f -> "<1m"
            meters < 2f -> "1-2m"
            meters < 5f -> "2-5m"
            meters < 10f -> "5-10m"
            meters < 20f -> "10-20m"
            else -> "20m+"
        }
    }

    private fun buildCreateGeospatialInfo(): CreateDiagnostics {
        if (!createSession.isGeospatialGuidanceEnabled) {
            return unavailableCreateGeospatialInfo("Geospatial guidance disabled by configuration.")
        }
        val sess = session
        val earth = sess?.earth
        if (sess == null) {
            return unavailableCreateGeospatialInfo("AR session unavailable.")
        }
        if (earth == null) {
            return unavailableCreateGeospatialInfo("Geospatial guidance unavailable on this session.")
        }
        if (earth.trackingState != TrackingState.TRACKING) {
            return unavailableCreateGeospatialInfo("Waiting for geospatial tracking.")
        }

        val metadata = placementGeospatialMetadata
        return if (metadata != null && anchor != null) {
            createDiagnostics(
                status = if (metadata.reliableLocalization) {
                    "Placed pin geospatial quality is strong."
                } else {
                    "Placed pin geospatial quality is rough."
                },
                sourceLabel = "Placed pin",
                latitudeText = formatCoordinate(metadata.latitude, 5),
                longitudeText = formatCoordinate(metadata.longitude, 5),
                altitudeText = formatMeters(metadata.altitude),
                horizontalAccuracyText = formatMeters(metadata.horizontalAccuracyMeters),
                verticalAccuracyText = formatMeters(metadata.verticalAccuracyMeters),
                headingAccuracyText = formatDegrees(metadata.headingAccuracyDegrees),
                localizationQualityText = if (metadata.reliableLocalization) "Strong" else "Rough",
                vpsQualityText = if (metadata.reliableLocalization) "Yes" else "No"
            )
        } else {
            val pose = earth.cameraGeospatialPose
            val reliable = isReliablePlacementQuality(pose.horizontalAccuracy, pose.verticalAccuracy)
            createDiagnostics(
                status = if (reliable) {
                    "Live geospatial quality is strong."
                } else {
                    "Live geospatial quality is rough. Move slowly for better accuracy."
                },
                sourceLabel = "Device location",
                latitudeText = formatCoordinate(pose.latitude, 5),
                longitudeText = formatCoordinate(pose.longitude, 5),
                altitudeText = formatMeters(pose.altitude),
                horizontalAccuracyText = formatMeters(pose.horizontalAccuracy),
                verticalAccuracyText = formatMeters(pose.verticalAccuracy),
                headingAccuracyText = formatDegrees(pose.headingAccuracy),
                localizationQualityText = if (reliable) "Strong" else "Rough",
                vpsQualityText = if (reliable) "Yes" else "No"
            )
        }
    }

    private fun unavailableCreateGeospatialInfo(status: String): CreateDiagnostics =
        createDiagnostics(
            status = status,
            sourceLabel = "Unavailable",
            latitudeText = "Unavailable",
            longitudeText = "Unavailable",
            altitudeText = "Unavailable",
            horizontalAccuracyText = "Unavailable",
            verticalAccuracyText = "Unavailable",
            headingAccuracyText = "Unavailable",
            localizationQualityText = "Unavailable",
            vpsQualityText = "Unavailable"
        )

    private fun createDiagnostics(
        status: String,
        sourceLabel: String,
        latitudeText: String,
        longitudeText: String,
        altitudeText: String,
        horizontalAccuracyText: String,
        verticalAccuracyText: String,
        headingAccuracyText: String,
        localizationQualityText: String,
        vpsQualityText: String
    ): CreateDiagnostics =
        CreateDiagnostics(
            geospatialAccuracy = GeospatialAccuracy(
                status = status,
                sourceLabel = sourceLabel,
                latitudeText = latitudeText,
                longitudeText = longitudeText,
                altitudeText = altitudeText,
                horizontalAccuracyText = horizontalAccuracyText,
                verticalAccuracyText = verticalAccuracyText,
                headingAccuracyText = headingAccuracyText,
                localizationQualityText = localizationQualityText,
                vpsQualityText = vpsQualityText
            )
        )

    private fun formatCoordinate(value: Double?, decimals: Int): String =
        value?.let { "%.${decimals}f".format(it) } ?: "Unavailable"

    private fun formatMeters(value: Double?): String =
        value?.let { "%.1f m".format(it) } ?: "Unavailable"

    private fun formatDegrees(value: Double?): String =
        value?.let { "%.1f deg".format(it) } ?: "Unavailable"

    private fun drawCameraBackground(frame: Frame) {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)

        GLES20.glUseProgram(shaderProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(shaderProgram, "uTexture"), 0)

        transformedTexCoords.clear()
        frame.transformCoordinates2d(
            Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
            ndcQuadInput,
            Coordinates2d.TEXTURE_NORMALIZED,
            transformedTexCoords
        )
        transformedTexCoords.position(0)

        val posHandle = GLES20.glGetAttribLocation(shaderProgram, "aPosition")
        GLES20.glEnableVertexAttribArray(posHandle)
        quadVertices.position(0)
        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 0, quadVertices)

        val texHandle = GLES20.glGetAttribLocation(shaderProgram, "aTexCoord")
        GLES20.glEnableVertexAttribArray(texHandle)
        GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 0, transformedTexCoords)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(posHandle)
        GLES20.glDisableVertexAttribArray(texHandle)
        GLES20.glDepthMask(true)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    private fun initPinRenderer() {
        try {
            pinRenderer.init()
            pinRendererAvailable = true
        } catch (e: Exception) {
            pinRendererAvailable = false
            Log.e(TAG, "Pin marker renderer failed to initialize", e)
            DebugSessionLog.append("RESOLVE", "Pin marker renderer unavailable; resolve logic continues")
        }
    }

    private fun drawPin(pose: com.google.ar.core.Pose, viewMatrix: FloatArray, projMatrix: FloatArray) {
        if (!pinRendererAvailable) return
        try {
            pinRenderer.draw(pose, viewMatrix, projMatrix)
        } catch (e: Exception) {
            pinRendererAvailable = false
            Log.e(TAG, "Pin marker renderer failed while drawing pose", e)
            DebugSessionLog.append("RESOLVE", "Pin marker renderer failed; marker hidden")
        }
    }

    private fun drawPinWithMatrix(modelMatrix: FloatArray, viewMatrix: FloatArray, projMatrix: FloatArray) {
        if (!pinRendererAvailable) return
        try {
            pinRenderer.drawWithMatrix(modelMatrix, viewMatrix, projMatrix)
        } catch (e: Exception) {
            pinRendererAvailable = false
            Log.e(TAG, "Pin marker renderer failed while drawing matrix", e)
            DebugSessionLog.append("RESOLVE", "Pin marker renderer failed; marker hidden")
        }
    }

    private fun createProgram(vertSrc: String, fragSrc: String): Int {
        val vert = loadShader(GLES20.GL_VERTEX_SHADER, vertSrc)
        val frag = loadShader(GLES20.GL_FRAGMENT_SHADER, fragSrc)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vert)
        GLES20.glAttachShader(prog, frag)
        GLES20.glLinkProgram(prog)
        return prog
    }

    private fun loadShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        return shader
    }

    private fun distance(a: FloatArray, b: FloatArray): Float {
        val dx = a[0] - b[0]
        val dy = a[1] - b[1]
        val dz = a[2] - b[2]
        return sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
    }

    private fun quaternionAngleDegrees(a: FloatArray, b: FloatArray): Float {
        val dot = (a[0] * b[0]) + (a[1] * b[1]) + (a[2] * b[2]) + (a[3] * b[3])
        val clamped = kotlin.math.max(-1f, kotlin.math.min(1f, abs(dot)))
        return Math.toDegrees((2.0 * acos(clamped.toDouble()))).toFloat()
    }
}

