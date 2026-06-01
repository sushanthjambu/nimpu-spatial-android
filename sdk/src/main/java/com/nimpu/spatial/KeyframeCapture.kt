package com.nimpu.spatial.sdk

import android.media.Image
import android.opengl.Matrix
import android.util.Log
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Guided keyframe capture for Create Pin.
 *
 * Tracks coverage around the placed anchor, keeps only the best keyframe per sector,
 * and continues improving the map after the minimum ready threshold is reached.
 */
internal class KeyframeCapture {

    companion object {
        private const val TAG = "KeyframeCapture"
        private const val DBG = "NimpuDebug"

        private const val MIN_CAPTURE_DISTANCE_M = 0.15f
        private const val MIN_INTERVAL_MS = 250L
        private const val MAX_IMPROVEMENT_DURATION_MS = 30_000L

        private const val MIN_DIST_FROM_ANCHOR_M = 0.3f
        private const val MAX_DIST_FROM_ANCHOR_M = 3.0f

        private const val NUM_SECTORS = 8
        private const val ARC_SWEEP_DEGREES = 240.0
        private const val REQUIRED_SECTORS = 4
        private const val READY_SCAN_INSTRUCTION = "Ready to save. Scan more angles for better accuracy."
        private const val INNER_NDC_LIMIT = 0.7f

        private const val MIN_FEATURE_COUNT = 35
        private const val MIN_RESOLVED_POINTS = 12
        private const val MIN_RESOLVED_FRACTION = 0.18f
        private const val MIN_DEGENERATE_SPREAD_M = 0.03f
        private const val MIN_STRONG_SPREAD_M = 0.15f
        private const val MIN_GOOD_SCORE = 0.55f
    }

    enum class TickResult {
        IDLE,
        FRAME_REJECTED,
        KEYFRAME_ACCEPTED,
        SECTOR_REPLACED,
        SCAN_READY,
        TIMEOUT
    }

    enum class DistanceState { GOOD, TOO_CLOSE, TOO_FAR }

    data class KeyframeResult(
        val featureResult: CreateFrameProcessingResult,
        val resolvedPoints: List<SpatialResolver.ResolvedPoint>,
        val cameraPose: FloatArray,
        val cameraIntrinsics: FloatArray
    )

    data class ScanStatus(
        val progressMessage: String,
        val instructionMessage: String,
        val coveredSectors: Int,
        val totalSectors: Int,
        val currentSector: Int?,
        val sectorMask: List<Boolean>,
        val attemptedSectorMask: List<Boolean>,
        val ready: Boolean,
        val timedOut: Boolean,
        val distanceState: DistanceState,
        val anchorVisible: Boolean
    )

    private data class SectorCapture(
        val result: KeyframeResult,
        val score: Float
    )

    private var lastCapturePos = FloatArray(3) { 0f }
    private var lastCaptureTimeMs = 0L
    private var scanStartTimeMs = 0L
    private var readyTimeMs: Long? = null
    private var started = false
    private var ready = false
    private var referenceAngleDeg = 0.0
    private var lastAcceptedSector: Int? = null

    private val sectorBest = arrayOfNulls<SectorCapture>(NUM_SECTORS)
    private val attemptedSectors = BooleanArray(NUM_SECTORS)
    private val keyframeProcessor = CreateKeyframeProcessor()
    private val snapshotBufferPool = CreateSnapshotBufferPool(capacity = 2)
    private val completedCandidates = ConcurrentLinkedQueue<CreateKeyframeProcessingResult>()
    private var lastDebugMessage: String? = null
    private var lastDebugMessageTimeMs = 0L
    private var depthUvLut: CreateDepthUvLut? = null
    private var lutGeneration = 0
    private var lutImageWidth = 0
    private var lutImageHeight = 0
    private var lutDepthWidth = 0
    private var lutDepthHeight = 0
    private var candidateIdCounter = 0L
    private var lastWorkerProcessingMs: Double? = null
    private var droppedCandidateCount = 0
    private var skippedCandidateCount = 0

    var scanStatus: ScanStatus = emptyStatus("Move around the pin to start scanning.")
        private set

    val results: List<KeyframeResult>
        get() = sectorBest.filterNotNull().map { it.result }

    val isWorkerBusy: Boolean
        get() = keyframeProcessor.isBusy()

    fun start(frame: Frame, anchorPose: Pose, placementReferenceAngleDeg: Double) {
        keyframeProcessor.reset()
        snapshotBufferPool.reset()
        completedCandidates.clear()
        depthUvLut = null
        lutGeneration = 0
        lutImageWidth = 0
        lutImageHeight = 0
        lutDepthWidth = 0
        lutDepthHeight = 0
        lastWorkerProcessingMs = null
        droppedCandidateCount = 0
        skippedCandidateCount = 0
        val t = frame.camera.pose.translation
        lastCapturePos = floatArrayOf(t[0], t[1], t[2])
        lastCaptureTimeMs = System.currentTimeMillis()
        scanStartTimeMs = lastCaptureTimeMs
        readyTimeMs = null
        referenceAngleDeg = placementReferenceAngleDeg
        lastAcceptedSector = null
        started = true
        ready = false
        sectorBest.fill(null)
        attemptedSectors.fill(false)
        scanStatus = emptyStatus("Move left and right around the pin.")
        updateStatus(
            instructionMessage = "Move left and right around the pin.",
            currentSector = computeSector(frame.camera.pose.translation, anchorPose.translation),
            distanceState = computeDistanceState(frame.camera.pose.translation, anchorPose.translation),
            anchorVisible = false
        )
        PerfLog.log("keyframeCapture", "Guided scanning started")
    }

    fun pauseProcessing() {
        keyframeProcessor.pause()
        completedCandidates.clear()
    }

    fun close() {
        keyframeProcessor.close()
        snapshotBufferPool.clear()
        completedCandidates.clear()
    }

    fun workerDiagnostics(): CreateWorkerDiagnostics =
        CreateWorkerDiagnostics(
            processingMode = "snapshot_worker",
            workerBusy = keyframeProcessor.isBusy(),
            lastKeyframeProcessingMs = lastWorkerProcessingMs,
            droppedCandidateCount = droppedCandidateCount,
            skippedCandidateCount = skippedCandidateCount
        )

    fun tick(
        frame: Frame,
        anchorPose: Pose,
        viewMatrix: FloatArray,
        projMatrix: FloatArray
    ): TickResult {
        if (!started) return TickResult.IDLE
        applyCompletedCandidate()?.let { return it }
        if (frame.camera.trackingState != TrackingState.TRACKING) {
            updateStatus(
                instructionMessage = "Tracking unstable. Hold steady.",
                currentSector = null,
                distanceState = DistanceState.GOOD,
                anchorVisible = false
            )
            return TickResult.IDLE
        }

        val now = System.currentTimeMillis()
        val readyAt = readyTimeMs
        if (ready && readyAt != null && now - readyAt > MAX_IMPROVEMENT_DURATION_MS) {
            started = false
            updateStatus(
                instructionMessage = "Scan is good. You can save now.",
                currentSector = scanStatus.currentSector,
                distanceState = scanStatus.distanceState,
                anchorVisible = scanStatus.anchorVisible
            )
            PerfLog.log(
                "keyframeCapture",
                "Background scan improvement finished after ${now - readyAt}ms from ready threshold"
            )
            DebugSessionLog.append(
                "CREATE",
                "Scan improvement window complete: covered=${scanStatus.coveredSectors}/${scanStatus.totalSectors}"
            )
            return TickResult.IDLE
        }

        val camPos = frame.camera.pose.translation
        val anchorPos = anchorPose.translation
        val distanceState = computeDistanceState(camPos, anchorPos)
        val anchorVisible = isAnchorVisible(anchorPos, viewMatrix, projMatrix)
        val currentSector = computeSector(camPos, anchorPos)

        if (distanceState == DistanceState.TOO_CLOSE) {
            updateStatus("Too close to the pin. Step back a little.", currentSector, distanceState, anchorVisible)
            return TickResult.FRAME_REJECTED
        }
        if (distanceState == DistanceState.TOO_FAR) {
            updateStatus("Move closer to the pin.", currentSector, distanceState, anchorVisible)
            return TickResult.FRAME_REJECTED
        }
        if (currentSector == null) {
            updateStatus("Move left or right around the pin.", null, distanceState, anchorVisible)
            return TickResult.FRAME_REJECTED
        }
        attemptedSectors[currentSector] = true
        if (!anchorVisible) {
            updateStatus("Keep the pin centered on screen.", currentSector, distanceState, false)
            return TickResult.FRAME_REJECTED
        }
        if (now - lastCaptureTimeMs < MIN_INTERVAL_MS) {
            updateStatus(
                if (ready) READY_SCAN_INSTRUCTION else "Move to a new angle around the pin.",
                currentSector,
                distanceState,
                true
            )
            return TickResult.IDLE
        }

        val dx = camPos[0] - lastCapturePos[0]
        val dy = camPos[1] - lastCapturePos[1]
        val dz = camPos[2] - lastCapturePos[2]
        val movedSinceLast = sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
        val angularNovelty = currentSector != lastAcceptedSector
        if (movedSinceLast < MIN_CAPTURE_DISTANCE_M && !angularNovelty) {
            updateStatus(if (ready) READY_SCAN_INSTRUCTION else "Move to a new angle around the pin.", currentSector, distanceState, true)
            return TickResult.FRAME_REJECTED
        }

        PerfLog.begin("keyframeCapture")
        try {
            if (!keyframeProcessor.isBusy()) {
                val snapshot = captureDepthSnapshot(
                    frame = frame,
                    anchorPose = anchorPose,
                    currentSector = currentSector,
                    distanceState = distanceState,
                    anchorVisible = anchorVisible,
                    timestampMs = now
                )
                if (snapshot != null) {
                    val submitted = keyframeProcessor.submit(
                        snapshot = snapshot,
                        task = { candidate ->
                            PerfLog.begin("createWorkerExtract")
                            val yBuffer = candidate.yPlane.duplicate()
                            yBuffer.position(0)
                            val featureResult = NativeBridge.processCreateFrame(
                                TrackingConfig.algorithm.id,
                                yBuffer,
                                candidate.imageWidth,
                                candidate.imageHeight,
                                candidate.imageWidth
                            )
                            PerfLog.end("createWorkerExtract", minLogMs = 3.0)
                            PerfLog.begin("createWorkerResolve")
                            val resolvedPoints = SpatialResolver.resolveFromSnapshot(featureResult, candidate)
                            PerfLog.end("createWorkerResolve", minLogMs = 3.0)
                            featureResult to resolvedPoints
                        },
                        onResult = { result ->
                            completedCandidates.offer(result)
                        }
                    )
                    if (submitted) {
                        lastCaptureTimeMs = now
                        updateStatus(
                            if (ready) READY_SCAN_INSTRUCTION else "Move to a new angle around the pin.",
                            currentSector,
                            distanceState,
                            true
                        )
                        PerfLog.end("keyframeCapture", minLogMs = 8.0)
                        return TickResult.IDLE
                    }
                    snapshot.releaseBuffer()
                    skippedCandidateCount++
                }
            } else {
                skippedCandidateCount++
                updateStatus(
                    if (ready) READY_SCAN_INSTRUCTION else "Move to a new angle around the pin.",
                    currentSector,
                    distanceState,
                    true
                )
                PerfLog.end("keyframeCapture", minLogMs = 8.0)
                return TickResult.IDLE
            }
            if (keyframeProcessor.isBusy()) {
                skippedCandidateCount++
                updateStatus(
                    if (ready) READY_SCAN_INSTRUCTION else "Move to a new angle around the pin.",
                    currentSector,
                    distanceState,
                    true
                )
                PerfLog.end("keyframeCapture", minLogMs = 8.0)
                return TickResult.IDLE
            }

            val keyframeResult = captureSynchronously(frame)
            val tickResult = applyKeyframeCandidate(
                keyframeResult = keyframeResult,
                currentSector = currentSector,
                distanceState = distanceState,
                anchorVisible = true,
                cameraPosition = camPos,
                now = now
            )
            PerfLog.end("keyframeCapture", minLogMs = 8.0)
            return tickResult
        } catch (e: NotYetAvailableException) {
            updateStatus(
                if (ready) READY_SCAN_INSTRUCTION else "Move to a new angle around the pin.",
                currentSector,
                distanceState,
                anchorVisible
            )
            PerfLog.end("keyframeCapture", minLogMs = 8.0)
            PerfLog.log("keyframeCapture", "Image not yet available, skipping")
            return TickResult.IDLE
        } catch (e: Exception) {
            Log.e(TAG, "Keyframe capture failed", e)
            updateStatus("Scan error. Move slowly and keep the pin centered.", currentSector, distanceState, anchorVisible)
            PerfLog.end("keyframeCapture", minLogMs = 8.0)
            PerfLog.log("keyframeCapture", "ERROR: ${e.message}")
            return TickResult.FRAME_REJECTED
        }
    }

    private fun applyCompletedCandidate(): TickResult? {
        val result = completedCandidates.poll() ?: return null
        val now = System.currentTimeMillis()
        return when (result) {
            is CreateKeyframeProcessingResult.Failed -> {
                droppedCandidateCount++
                PerfLog.log("createWorker", "candidate failed: ${result.reason}")
                result.snapshot?.releaseBuffer()
                null
            }
            is CreateKeyframeProcessingResult.ResolvedCandidate -> {
                val snapshot = result.snapshot
                lastWorkerProcessingMs = result.processingMs
                if (snapshot.lutGeneration != depthUvLut?.generation && depthUvLut != null) {
                    droppedCandidateCount++
                    PerfLog.log("createWorker", "Dropped stale candidate: lut=${snapshot.lutGeneration}")
                    snapshot.releaseBuffer()
                    return null
                }
                val keyframeResult = KeyframeResult(
                    featureResult = result.featureResult,
                    resolvedPoints = result.resolvedPoints,
                    cameraPose = snapshot.cameraPoseVector,
                    cameraIntrinsics = snapshot.cameraIntrinsics
                )
                PerfLog.log(
                    "createWorker",
                    "candidate=${snapshot.candidateId} processed=${"%.2f".format(result.processingMs)}ms " +
                        "features=${result.featureResult.count} resolved=${result.resolvedPoints.size}"
                )
                val tickResult = applyKeyframeCandidate(
                    keyframeResult = keyframeResult,
                    currentSector = snapshot.currentSector,
                    distanceState = snapshot.distanceState,
                    anchorVisible = snapshot.anchorVisible,
                    cameraPosition = floatArrayOf(
                        snapshot.cameraPoseVector[0],
                        snapshot.cameraPoseVector[1],
                        snapshot.cameraPoseVector[2]
                    ),
                    now = now
                )
                snapshot.releaseBuffer()
                tickResult
            }
        }
    }

    private fun captureSynchronously(frame: Frame): KeyframeResult {
        PerfLog.begin("acquireCameraImage")
        val image = frame.acquireCameraImage()
        PerfLog.end("acquireCameraImage", minLogMs = 1.0)

        var imageClosed = false
        try {
            val yPlane = image.planes[0]
            val yBuffer = yPlane.buffer
            val width = image.width
            val height = image.height
            val rowStride = yPlane.rowStride

            val algo = TrackingConfig.algorithm
            PerfLog.begin("featExtract")
            val featureResult = NativeBridge.processCreateFrame(algo.id, yBuffer, width, height, rowStride)
            PerfLog.end("featExtract", minLogMs = 3.0)
            image.close()
            imageClosed = true

            PerfLog.begin("spatialResolve")
            val resolvedPoints = SpatialResolver.resolve(featureResult, frame)
            PerfLog.end("spatialResolve", minLogMs = 3.0)

            return KeyframeResult(
                featureResult = featureResult,
                resolvedPoints = resolvedPoints,
                cameraPose = frame.camera.pose.toVector(),
                cameraIntrinsics = frame.camera.imageIntrinsics.toVector()
            )
        } finally {
            if (!imageClosed) image.close()
        }
    }

    private fun captureDepthSnapshot(
        frame: Frame,
        anchorPose: Pose,
        currentSector: Int,
        distanceState: DistanceState,
        anchorVisible: Boolean,
        timestampMs: Long
    ): CreateFrameSnapshot? {
        var image: Image? = null
        var depthImage: Image? = null
        var acquiredBuffer: CreateSnapshotBuffer? = null
        PerfLog.begin("createSnapshotCapture")
        try {
            image = frame.acquireCameraImage()
            depthImage = frame.acquireDepthImage16Bits()
            val buffer = snapshotBufferPool.acquire(
                imageWidth = image.width,
                imageHeight = image.height,
                depthWidth = depthImage.width,
                depthHeight = depthImage.height
            ) ?: run {
                PerfLog.log("createSnapshotPool", "No free buffer; skipped candidate")
                return null
            }
            acquiredBuffer = buffer
            copyYPlaneCompact(image, buffer.yPlane)
            copyDepthPlaneCompact(depthImage, buffer.depthBytes)
            val lut = ensureDepthUvLut(
                frame = frame,
                imageWidth = image.width,
                imageHeight = image.height,
                depthWidth = depthImage.width,
                depthHeight = depthImage.height
            )
            val cameraPoseMatrix = FloatArray(16)
            frame.camera.pose.toMatrix(cameraPoseMatrix, 0)
            val snapshot = CreateFrameSnapshot(
                candidateId = ++candidateIdCounter,
                buffer = buffer,
                imageWidth = image.width,
                imageHeight = image.height,
                depthWidth = depthImage.width,
                depthHeight = depthImage.height,
                cameraPoseMatrix = cameraPoseMatrix,
                cameraPoseVector = frame.camera.pose.toVector(),
                cameraIntrinsics = frame.camera.imageIntrinsics.toVector(),
                anchorPose = anchorPose.toVector(),
                currentSector = currentSector,
                distanceState = distanceState,
                anchorVisible = anchorVisible,
                timestampMs = timestampMs,
                lutGeneration = lut.generation,
                uvLut = lut
            )
            acquiredBuffer = null
            PerfLog.end("createSnapshotCapture", minLogMs = 3.0)
            return snapshot
        } catch (e: NotYetAvailableException) {
            acquiredBuffer?.release()
            PerfLog.end("createSnapshotCapture", minLogMs = 3.0)
            return null
        } catch (e: Exception) {
            acquiredBuffer?.release()
            PerfLog.end("createSnapshotCapture", minLogMs = 3.0)
            PerfLog.log("createSnapshotCapture", "failed: ${e.message ?: e.javaClass.simpleName}")
            return null
        } finally {
            depthImage?.close()
            image?.close()
        }
    }

    private fun applyKeyframeCandidate(
        keyframeResult: KeyframeResult,
        currentSector: Int,
        distanceState: DistanceState,
        anchorVisible: Boolean,
        cameraPosition: FloatArray,
        now: Long
    ): TickResult {
        PerfLog.begin("keyframeScore")
        val score = scoreKeyframe(keyframeResult, currentSector)
        PerfLog.end("keyframeScore", minLogMs = 1.0)
        if (score == null) {
            lastCaptureTimeMs = now
            return TickResult.FRAME_REJECTED
        }

        val existing = sectorBest[currentSector]
        val tickResult = if (existing == null) {
            sectorBest[currentSector] = SectorCapture(keyframeResult, score)
            logAcceptedKeyframe("Accepted", currentSector, score, keyframeResult)
            TickResult.KEYFRAME_ACCEPTED
        } else if (score > existing.score) {
            sectorBest[currentSector] = SectorCapture(keyframeResult, score)
            logAcceptedKeyframe("Improved", currentSector, score, keyframeResult)
            TickResult.SECTOR_REPLACED
        } else {
            updateStatus(
                if (ready) READY_SCAN_INSTRUCTION else "Move to a new angle around the pin.",
                currentSector,
                distanceState,
                anchorVisible
            )
            lastCaptureTimeMs = now
            return TickResult.FRAME_REJECTED
        }

        lastCapturePos = floatArrayOf(cameraPosition[0], cameraPosition[1], cameraPosition[2])
        lastCaptureTimeMs = now
        lastAcceptedSector = currentSector

        val covered = sectorBest.count { it != null }
        if (!ready && covered >= REQUIRED_SECTORS) {
            ready = true
            readyTimeMs = now
            updateStatus(READY_SCAN_INSTRUCTION, currentSector, distanceState, anchorVisible)
            scanStatus = scanStatus.copy(ready = true)
            DebugSessionLog.append("CREATE", "Scan ready: $covered/$NUM_SECTORS accepted sectors")
            PerfLog.log(
                "keyframeCapture",
                "Minimum ready threshold reached with $covered/$NUM_SECTORS sectors after ${now - scanStartTimeMs}ms"
            )
            return TickResult.SCAN_READY
        }

        if (covered == NUM_SECTORS) {
            started = false
            updateStatus("All sectors are mapped. You can save now.", currentSector, distanceState, anchorVisible)
            scanStatus = scanStatus.copy(ready = true)
            DebugSessionLog.append("CREATE", "All $NUM_SECTORS sectors accepted")
            PerfLog.log("keyframeCapture", "All $NUM_SECTORS sectors mapped")
            return tickResult
        }

        updateStatus(
            instructionMessage = if (ready) READY_SCAN_INSTRUCTION else "Move to a new angle around the pin.",
            currentSector = currentSector,
            distanceState = distanceState,
            anchorVisible = anchorVisible
        )
        return tickResult
    }

    private fun scoreKeyframe(result: KeyframeResult, sectorIndex: Int): Float? {
        val featureCount = result.featureResult.count
        val resolvedCount = result.resolvedPoints.size
        val resolvedFraction = if (featureCount == 0) 0f else resolvedCount.toFloat() / featureCount.toFloat()

        val spreads = computeSpreads(result.resolvedPoints)

        val sortedSpreads = spreads.sorted()
        val obviouslyPlanar = sortedSpreads[0] < MIN_DEGENERATE_SPREAD_M &&
                sortedSpreads[1] > MIN_STRONG_SPREAD_M &&
                sortedSpreads[2] > MIN_STRONG_SPREAD_M

        if (featureCount < MIN_FEATURE_COUNT) {
            Log.d(DBG, "Rejected sector=$sectorIndex: low feature count ($featureCount)")
            logScanDebug("Rejected sector=$sectorIndex: low feature count ($featureCount)")
            updateStatus("Need more visual detail near the pin.", sectorIndex, scanStatus.distanceState, scanStatus.anchorVisible)
            return null
        }
        if (resolvedCount < MIN_RESOLVED_POINTS || resolvedFraction < MIN_RESOLVED_FRACTION) {
            Log.d(DBG, "Rejected sector=$sectorIndex: weak 3D resolve ($resolvedCount points, ${"%.2f".format(resolvedFraction)} fraction)")
            logScanDebug(
                "Rejected sector=$sectorIndex: weak 3D resolve " +
                    "resolved=$resolvedCount features=$featureCount fraction=${"%.2f".format(resolvedFraction)}"
            )
            updateStatus("Need a more textured angle around the pin.", sectorIndex, scanStatus.distanceState, scanStatus.anchorVisible)
            return null
        }
        if (obviouslyPlanar) {
            Log.d(DBG, "Rejected sector=$sectorIndex: planar spread x=${"%.3f".format(spreads[0])} y=${"%.3f".format(spreads[1])} z=${"%.3f".format(spreads[2])}")
            logScanDebug(
                "Rejected sector=$sectorIndex: planar spread " +
                    "x=${"%.3f".format(spreads[0])} y=${"%.3f".format(spreads[1])} z=${"%.3f".format(spreads[2])}"
            )
            updateStatus("Need more depth variation around the pin.", sectorIndex, scanStatus.distanceState, scanStatus.anchorVisible)
            return null
        }

        val featureScore = (featureCount / 80f).coerceIn(0f, 1f)
        val resolveScore = (resolvedFraction / 0.4f).coerceIn(0f, 1f)
        val spreadScore = (min(sortedSpreads[1], 0.25f) / 0.25f).coerceIn(0f, 1f)
        val noveltyScore = if (sectorBest[sectorIndex] == null) 1f else 0.75f
        val score = (featureScore * 0.35f) + (resolveScore * 0.35f) + (spreadScore * 0.20f) + (noveltyScore * 0.10f)
        return if (score >= MIN_GOOD_SCORE) {
            score
        } else {
            logScanDebug(
                "Rejected sector=$sectorIndex: low score=${"%.2f".format(score)} " +
                    "features=$featureCount resolved=$resolvedCount fraction=${"%.2f".format(resolvedFraction)}"
            )
            null
        }
    }

    private fun logAcceptedKeyframe(
        action: String,
        sectorIndex: Int,
        score: Float,
        keyframe: KeyframeResult
    ) {
        val featureCount = keyframe.featureResult.count
        val resolvedCount = keyframe.resolvedPoints.size
        val resolvedFraction = if (featureCount > 0) resolvedCount.toFloat() / featureCount.toFloat() else 0f
        logScanDebug(
            "$action sector=$sectorIndex: score=${"%.2f".format(score)} " +
                "features=$featureCount resolved=$resolvedCount fraction=${"%.2f".format(resolvedFraction)}"
        )
    }

    private fun ensureDepthUvLut(
        frame: Frame,
        imageWidth: Int,
        imageHeight: Int,
        depthWidth: Int,
        depthHeight: Int
    ): CreateDepthUvLut {
        val displayChanged = frame.hasDisplayGeometryChanged()
        val shouldRebuild = depthUvLut == null ||
            displayChanged ||
            imageWidth != lutImageWidth ||
            imageHeight != lutImageHeight ||
            depthWidth != lutDepthWidth ||
            depthHeight != lutDepthHeight

        if (shouldRebuild) {
            lutGeneration++
            depthUvLut = CreateDepthUvLut.build(
                frame = frame,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                generation = lutGeneration
            )
            lutImageWidth = imageWidth
            lutImageHeight = imageHeight
            lutDepthWidth = depthWidth
            lutDepthHeight = depthHeight

            val stats = depthUvLut?.validateAgainstFrame(frame, depthWidth, depthHeight)
            PerfLog.log(
                "createDepthUvLut",
                if (stats != null) {
                    "generation=$lutGeneration image=${imageWidth}x$imageHeight depth=${depthWidth}x$depthHeight " +
                        "step=${CreateDepthUvLut.DEFAULT_STEP} samples=${stats.count} " +
                        "avg=${"%.2f".format(stats.averageDepthPixelError)}px " +
                        "p90=${"%.2f".format(stats.p90DepthPixelError)}px " +
                        "p95=${"%.2f".format(stats.p95DepthPixelError)}px " +
                        "max=${"%.2f".format(stats.maxDepthPixelError)}px"
                } else {
                    "generation=$lutGeneration validation unavailable"
                }
            )
        }
        return depthUvLut ?: error("Depth UV LUT was not created")
    }

    private fun copyYPlaneCompact(image: Image, target: ByteBuffer) {
        val plane = image.planes[0]
        val source = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val width = image.width
        val height = image.height

        target.clear()
        for (y in 0 until height) {
            val rowStart = y * rowStride
            for (x in 0 until width) {
                target.put(source.get(rowStart + x * pixelStride))
            }
        }
        target.position(0)
    }

    private fun copyDepthPlaneCompact(image: Image, target: ByteArray) {
        val plane = image.planes[0]
        val source = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val width = image.width
        val height = image.height

        for (y in 0 until height) {
            val rowStart = y * rowStride
            for (x in 0 until width) {
                val sourceIndex = rowStart + x * pixelStride
                val targetIndex = ((y * width) + x) * 2
                target[targetIndex] = source.get(sourceIndex)
                target[targetIndex + 1] = source.get(sourceIndex + 1)
            }
        }
    }

    private fun Pose.toVector(): FloatArray =
        floatArrayOf(tx(), ty(), tz(), qx(), qy(), qz(), qw())

    private fun com.google.ar.core.CameraIntrinsics.toVector(): FloatArray {
        val focalLength = focalLength
        val principalPoint = principalPoint
        return floatArrayOf(
            focalLength[0], focalLength[1],
            principalPoint[0], principalPoint[1]
        )
    }

    private fun computeSpreads(resolvedPoints: List<SpatialResolver.ResolvedPoint>): FloatArray {
        if (resolvedPoints.isEmpty()) return floatArrayOf(0f, 0f, 0f)

        var minX = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var minZ = Float.POSITIVE_INFINITY
        var maxZ = Float.NEGATIVE_INFINITY

        for (point in resolvedPoints) {
            minX = min(minX, point.worldX)
            maxX = max(maxX, point.worldX)
            minY = min(minY, point.worldY)
            maxY = max(maxY, point.worldY)
            minZ = min(minZ, point.worldZ)
            maxZ = max(maxZ, point.worldZ)
        }

        return floatArrayOf(maxX - minX, maxY - minY, maxZ - minZ)
    }

    private fun computeDistanceState(camPos: FloatArray, anchorPos: FloatArray): DistanceState {
        val dx = camPos[0] - anchorPos[0]
        val dy = camPos[1] - anchorPos[1]
        val dz = camPos[2] - anchorPos[2]
        val distance = sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
        return when {
            distance < MIN_DIST_FROM_ANCHOR_M -> DistanceState.TOO_CLOSE
            distance > MAX_DIST_FROM_ANCHOR_M -> DistanceState.TOO_FAR
            else -> DistanceState.GOOD
        }
    }

    private fun computeSector(camPos: FloatArray, anchorPos: FloatArray): Int? {
        val angleDeg = Math.toDegrees(Math.atan2((camPos[2] - anchorPos[2]).toDouble(), (camPos[0] - anchorPos[0]).toDouble()))
        val relative = normalizeAngleDeg(angleDeg - referenceAngleDeg)
        val halfSweep = ARC_SWEEP_DEGREES / 2.0
        if (abs(relative) > halfSweep) return null

        val sectorWidth = ARC_SWEEP_DEGREES / NUM_SECTORS.toDouble()
        val normalized = relative + halfSweep
        return (normalized / sectorWidth).toInt().coerceIn(0, NUM_SECTORS - 1)
    }

    private fun isAnchorVisible(anchorPos: FloatArray, viewMatrix: FloatArray, projMatrix: FloatArray): Boolean {
        val worldPoint = floatArrayOf(anchorPos[0], anchorPos[1], anchorPos[2], 1f)
        val viewPoint = FloatArray(4)
        val clipPoint = FloatArray(4)
        Matrix.multiplyMV(viewPoint, 0, viewMatrix, 0, worldPoint, 0)
        Matrix.multiplyMV(clipPoint, 0, projMatrix, 0, viewPoint, 0)

        if (clipPoint[3] <= 0f) return false
        val ndcX = clipPoint[0] / clipPoint[3]
        val ndcY = clipPoint[1] / clipPoint[3]
        val ndcZ = clipPoint[2] / clipPoint[3]
        return abs(ndcX) <= INNER_NDC_LIMIT && abs(ndcY) <= INNER_NDC_LIMIT && ndcZ in -1f..1f
    }

    private fun updateStatus(
        instructionMessage: String,
        currentSector: Int?,
        distanceState: DistanceState,
        anchorVisible: Boolean
    ) {
        val mask = sectorBest.map { it != null }
        scanStatus = ScanStatus(
            progressMessage = progressMessage(),
            instructionMessage = instructionMessage,
            coveredSectors = mask.count { it },
            totalSectors = NUM_SECTORS,
            currentSector = currentSector,
            sectorMask = mask,
            attemptedSectorMask = attemptedSectors.toList(),
            ready = ready,
            timedOut = false,
            distanceState = distanceState,
            anchorVisible = anchorVisible
        )
    }

    private fun progressMessage(): String {
        val covered = sectorBest.count { it != null }
        return if (covered == 0) {
            "0/$REQUIRED_SECTORS good angles mapped"
        } else if (ready) {
            "$covered/$NUM_SECTORS sectors mapped"
        } else {
            "$covered/$REQUIRED_SECTORS good angles mapped"
        }
    }

    private fun emptyStatus(message: String): ScanStatus =
        ScanStatus(
            progressMessage = "0/$REQUIRED_SECTORS good angles mapped",
            instructionMessage = message,
            coveredSectors = 0,
            totalSectors = NUM_SECTORS,
            currentSector = null,
            sectorMask = List(NUM_SECTORS) { false },
            attemptedSectorMask = List(NUM_SECTORS) { false },
            ready = false,
            timedOut = false,
            distanceState = DistanceState.GOOD,
            anchorVisible = false
        )

    private fun logScanDebug(message: String) {
        val now = System.currentTimeMillis()
        if (message != lastDebugMessage || now - lastDebugMessageTimeMs >= 1_500L) {
            DebugSessionLog.append("CREATE", message)
            lastDebugMessage = message
            lastDebugMessageTimeMs = now
        }
    }

    private fun normalizeAngleDeg(angleDeg: Double): Double {
        var value = angleDeg
        while (value > 180.0) value -= 360.0
        while (value < -180.0) value += 360.0
        return value
    }
}
