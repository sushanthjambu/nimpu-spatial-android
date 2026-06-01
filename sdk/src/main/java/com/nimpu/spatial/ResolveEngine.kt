package com.nimpu.spatial.sdk

import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.util.Log

/**
 * Background resolve worker. Runs off the GL render thread.
 */
internal class ResolveEngine(
    private val payload: PointCloudPayload,
    private val onResult: (ResolveEngineResult) -> Unit
) {

    companion object {
        private const val TAG = "ResolveEngine"
        private const val DBG = "NimpuDebug"
        private const val INLIER_ACCEPT_IMMEDIATELY = 20
        private const val INLIER_FALLBACK_MIN = 12
        private const val BEST_OF_N_WINDOW_MS = 5000L
        private const val TOTAL_TIMEOUT_MS = 15000L
        private const val MAX_PLAUSIBLE_DISTANCE_M = 12.0
    }

    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    
    @Volatile var isProcessing = false
        private set

    @Volatile
    private var isRunning = false

    @Volatile
    private var searchGeneration = 0

    fun isRunning(): Boolean = isRunning

    private var bestResult: ResolveFrameProcessingResult? = null
    private var bestCamTranslation: FloatArray? = null
    private var bestCamRotation: FloatArray? = null
    private var resolveStartTime = 0L
    private var attemptCount = 0
    private var lastShareLogAttempt = 0

    private lateinit var flatDescriptors: ByteArray
    private lateinit var flatPoints3D: FloatArray

    init {
        val points = payload.points3D
        val descs = payload.descriptors
        val dSize = payload.descriptorSize
        
        flatPoints3D = FloatArray(points.size * 3)
        for (i in points.indices) {
            flatPoints3D[i * 3 + 0] = points[i][0]
            flatPoints3D[i * 3 + 1] = points[i][1]
            flatPoints3D[i * 3 + 2] = points[i][2]
        }

        flatDescriptors = ByteArray(descs.size * dSize)
        for (i in descs.indices) {
            System.arraycopy(descs[i], 0, flatDescriptors, i * dSize, dSize)
        }
    }

    /** Start the background resolving loop. */
    fun start() {
        if (isRunning) return
        if (!hasNativeResolveEntitlement()) {
            DebugSessionLog.append("ENTITLEMENT", "Resolve blocked: SDK core has no resolve entitlement")
            onResult(
                ResolveEngineResult.Failed(
                    reason = "SDK activation is required for Resolve.",
                    stats = ResolveEngineStats(
                        attemptCount = 0,
                        bestInlierCount = 0,
                        bestMatchCount = 0,
                        elapsedMs = 0L,
                        payloadHash = PayloadIntegrity.compute(payload).payloadHash
                    )
                )
            )
            return
        }
        isRunning = true
        resetSearchWindow(incrementGeneration = true)

        handlerThread = HandlerThread("ResolveWorker").apply { start() }
        handler = Handler(handlerThread!!.looper)
        PerfLog.log("ResolveEngine", "Started background worker")
        val payloadHash = PayloadIntegrity.compute(payload).shortHash
        DebugSessionLog.append(
            "RESOLVE",
            "Precise search started: points=${flatPoints3D.size / 3} hash=$payloadHash"
        )
        Log.d(DBG, "=== RESOLVE START ===")
        Log.d(DBG, "Spatial payload profile loaded")
        Log.d(DBG, "Payload ready: ${flatPoints3D.size / 3} points")
        Log.d(DBG, "Saved pinPose: [${payload.pinPose.joinToString(", ") { "%.4f".format(it) }}]")
        Log.d(DBG, "Saved intrinsics (mapping device): fx=${payload.cameraIntrinsics[0]} fy=${payload.cameraIntrinsics[1]} cx=${payload.cameraIntrinsics[2]} cy=${payload.cameraIntrinsics[3]}")
        Log.d(DBG, "Precise search policy loaded")
    }

    private fun hasNativeResolveEntitlement(): Boolean =
        NativeBridge.hasEntitlementFeature(EntitlementFeature.OFFLINE_CACHE.wireValue) ||
            NativeBridge.hasEntitlementFeature(EntitlementFeature.STANDALONE_LOCAL_RESOLVE.wireValue)

    /** Reset evidence gathered so far while keeping the worker alive. */
    fun restartSearchWindow() {
        if (!isRunning) {
            start()
            return
        }
        resetSearchWindow(incrementGeneration = true)
        PerfLog.log("ResolveEngine", "Search window restarted")
        DebugSessionLog.append("RESOLVE", "Precise search window restarted")
        Log.d(DBG, "=== RESOLVE SEARCH WINDOW RESTARTED ===")
    }

    /** Stop the background loop. */
    fun stop() {
        if (!isRunning) return
        isRunning = false
        handler?.removeCallbacksAndMessages(null)
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
        isProcessing = false
        PerfLog.log("ResolveEngine", "Stopped background worker")
    }

    private fun resetSearchWindow(incrementGeneration: Boolean) {
        if (incrementGeneration) {
            searchGeneration += 1
        }
        bestResult = null
        bestCamTranslation = null
        bestCamRotation = null
        attemptCount = 0
        lastShareLogAttempt = 0
        resolveStartTime = android.os.SystemClock.elapsedRealtime()
    }

    /** Called by the renderer to hand a frame to the background worker. */
    fun processFrame(
        image: android.media.Image,
        camTranslation: FloatArray,
        camRotation: FloatArray,
        liveFx: Float,
        liveFy: Float,
        liveCx: Float,
        liveCy: Float
    ) {
        if (!isRunning || isProcessing) {
            image.close()
            return
        }
        
        isProcessing = true
        val frameGeneration = searchGeneration
        handler?.post {
            try {
                PerfLog.begin("resolveFrame")
                
                val yPlane = image.planes[0]
                val yBuffer = yPlane.buffer
                val width = image.width
                val height = image.height
                val rowStride = yPlane.rowStride

                val fx = liveFx
                val fy = liveFy
                val cx = liveCx
                val cy = liveCy

                if (attemptCount == 0) {
                    Log.d(DBG, "Live intrinsics (this device): fx=$fx fy=$fy cx=$cx cy=$cy")
                }

                PerfLog.begin("resolveFrameProcess")
                val result = NativeBridge.processResolveFrame(
                    algo = payload.algorithmId,
                    descSize = payload.descriptorSize,
                    yPlane = yBuffer,
                    width = width,
                    height = height,
                    rowStride = rowStride,
                    savedDescriptors = flatDescriptors,
                    savedPoints3D = flatPoints3D,
                    fx = fx, fy = fy, cx = cx, cy = cy
                )
                PerfLog.end("resolveFrameProcess")
                
                image.close()

                if (frameGeneration != searchGeneration) {
                    Log.d(DBG, "Frame ignored after search restart")
                    PerfLog.end("resolveFrame")
                    return@post
                }

                if (result.success) {
                    val tx = result.tvec[0].toDouble()
                    val ty = result.tvec[1].toDouble()
                    val tz = result.tvec[2].toDouble()
                    val dist = Math.sqrt(tx*tx + ty*ty + tz*tz)
                    if (dist > MAX_PLAUSIBLE_DISTANCE_M) {
                        Log.d(DBG, "Frame rejected: tvec distance ${"%.2f".format(dist)}m > max ${MAX_PLAUSIBLE_DISTANCE_M}m (hallucinated pose)")
                        PerfLog.end("resolveFrame")
                        return@post
                    }

                    attemptCount++
                    val elapsed = android.os.SystemClock.elapsedRealtime() - resolveStartTime
                    if (attemptCount == 1 || attemptCount % 5 == 0 || result.inlierCount >= INLIER_FALLBACK_MIN) {
                        Log.d(DBG, "Attempt #$attemptCount: quality=${result.inlierCount}, matches=${result.matchCount}, dist=${"%+.2f".format(dist)}m, ${elapsed}ms elapsed")
                    }
                    
                    val currentBest = bestResult
                    if (currentBest == null || result.inlierCount > currentBest.inlierCount) {
                        bestResult = result
                        bestCamTranslation = camTranslation.copyOf()
                        bestCamRotation = camRotation.copyOf()
                        Log.d(DBG, "New best candidate: quality=${result.inlierCount}")
                        if (attemptCount == 1 || attemptCount >= lastShareLogAttempt + 5 || result.inlierCount >= INLIER_FALLBACK_MIN) {
                            DebugSessionLog.append(
                                "RESOLVE",
                                "Attempt #$attemptCount: bestQuality=${result.inlierCount}, matches=${result.matchCount}, elapsed=${elapsed}ms"
                            )
                            lastShareLogAttempt = attemptCount
                        }
                    }
                    
                    if (result.inlierCount >= INLIER_ACCEPT_IMMEDIATELY) {
                        Log.d(DBG, "--- PRECISE POSE ACCEPTED ---")
                        acceptResult(result, camTranslation, camRotation)
                        PerfLog.end("resolveFrame")
                        return@post
                    }
                    
                    if (elapsed >= BEST_OF_N_WINDOW_MS) {
                        val best = bestResult
                        if (best != null && best.inlierCount >= INLIER_FALLBACK_MIN) {
                            Log.d(DBG, "--- PRECISE POSE ACCEPTED ---")
                            acceptResult(best, bestCamTranslation!!, bestCamRotation!!)
                            PerfLog.end("resolveFrame")
                            return@post
                        }
                    }
                    
                    if (elapsed >= TOTAL_TIMEOUT_MS) {
                        Log.d(DBG, "--- TIMEOUT after ${elapsed}ms, bestQuality=${bestResult?.inlierCount ?: 0} ---")
                        Log.d(DBG, "=== RESOLVE FAILED ===")
                        DebugSessionLog.append(
                            "RESOLVE",
                            "Precise search failed: timeout attempts=$attemptCount bestQuality=${bestResult?.inlierCount ?: 0} elapsed=${elapsed}ms"
                        )
                        onResult(
                            ResolveEngineResult.Failed(
                                reason = "Timeout: could not find enough matching features",
                                stats = buildStats(elapsed)
                            )
                        )
                        stop()
                        PerfLog.end("resolveFrame")
                        return@post
                    }
                }
                
                PerfLog.end("resolveFrame")
            } catch (e: Exception) {
                Log.e(TAG, "Resolve cycle failed", e)
                PerfLog.log("ResolveEngine", "Error: ${e.message}")
                try { image.close() } catch (ignored: Exception) {}
            } finally {
                isProcessing = false
            }
        }
    }

    private fun acceptResult(result: ResolveFrameProcessingResult, camTranslation: FloatArray, camRotation: FloatArray) {
        PerfLog.log("ResolveEngine", "ACCEPTED! quality=${result.inlierCount}, matches=${result.matchCount}")
        Log.d(DBG, "rvec: [${result.rvec.joinToString(", ") { "%.6f".format(it) }}]")
        Log.d(DBG, "tvec: [${result.tvec.joinToString(", ") { "%.6f".format(it) }}]")
        Log.d(DBG, "Live camera pos: [${camTranslation.joinToString(", ") { "%.4f".format(it) }}]")
        Log.d(DBG, "Live camera rot: [${camRotation.joinToString(", ") { "%.4f".format(it) }}]")

        val pinModelMatrix = computePinModelMatrix(result, camTranslation, camRotation)

        Log.d(DBG, "Final pin model matrix:")
        Log.d(DBG, "  row0: [${pinModelMatrix.slice(0..3).joinToString(", ") { "%.4f".format(it) }}]")
        Log.d(DBG, "  row1: [${pinModelMatrix.slice(4..7).joinToString(", ") { "%.4f".format(it) }}]")
        Log.d(DBG, "  row2: [${pinModelMatrix.slice(8..11).joinToString(", ") { "%.4f".format(it) }}]")
        Log.d(DBG, "  row3: [${pinModelMatrix.slice(12..15).joinToString(", ") { "%.4f".format(it) }}]")
        Log.d(DBG, "Final pin XYZ: [${"%.4f".format(pinModelMatrix[12])}, ${"%.4f".format(pinModelMatrix[13])}, ${"%.4f".format(pinModelMatrix[14])}]")
        
        val pose = matrixToPose(pinModelMatrix)
        Log.d(DBG, "Final ARCore Pose: T=[${pose.translation.joinToString(", "){"%.4f".format(it)}}] R=[${pose.rotationQuaternion.joinToString(", "){"%.4f".format(it)}}]")
        Log.d(DBG, "=== RESOLVE COMPLETE ===")

        val stats = buildStats(android.os.SystemClock.elapsedRealtime() - resolveStartTime)
        DebugSessionLog.append(
            "RESOLVE",
            "Precise search resolved: attempts=${stats.attemptCount} bestQuality=${stats.bestInlierCount} " +
                "bestMatches=${stats.bestMatchCount} elapsed=${stats.elapsedMs}ms hash=${stats.shortPayloadHash}"
        )
        onResult(
            ResolveEngineResult.Resolved(
                pose = pose,
                stats = stats
            )
        )
        stop()
    }

    private fun buildStats(elapsedMs: Long): ResolveEngineStats {
        val best = bestResult
        return ResolveEngineStats(
            attemptCount = attemptCount,
            bestInlierCount = best?.inlierCount ?: 0,
            bestMatchCount = best?.matchCount ?: 0,
            elapsedMs = elapsedMs,
            payloadHash = PayloadIntegrity.compute(payload).payloadHash
        )
    }

    private fun matrixToPose(m: FloatArray): com.google.ar.core.Pose {
        val t = floatArrayOf(m[12], m[13], m[14])
        
        val m00 = m[0];  val m01 = m[4];  val m02 = m[8]
        val m10 = m[1];  val m11 = m[5];  val m12 = m[9]
        val m20 = m[2];  val m21 = m[6];  val m22 = m[10]

        val tr = m00 + m11 + m22
        var qw = 0f; var qx = 0f; var qy = 0f; var qz = 0f

        if (tr > 0) {
            val s = Math.sqrt((tr + 1.0).toDouble()).toFloat() * 2f
            qw = 0.25f * s
            qx = (m21 - m12) / s
            qy = (m02 - m20) / s
            qz = (m10 - m01) / s
        } else if (m00 > m11 && m00 > m22) {
            val s = Math.sqrt((1.0 + m00 - m11 - m22).toDouble()).toFloat() * 2f
            qw = (m21 - m12) / s
            qx = 0.25f * s
            qy = (m01 + m10) / s
            qz = (m02 + m20) / s
        } else if (m11 > m22) {
            val s = Math.sqrt((1.0 + m11 - m00 - m22).toDouble()).toFloat() * 2f
            qw = (m02 - m20) / s
            qx = (m01 + m10) / s
            qy = 0.25f * s
            qz = (m12 + m21) / s
        } else {
            val s = Math.sqrt((1.0 + m22 - m00 - m11).toDouble()).toFloat() * 2f
            qw = (m10 - m01) / s
            qx = (m02 + m20) / s
            qy = (m12 + m21) / s
            qz = 0.25f * s
        }
        return com.google.ar.core.Pose(t, floatArrayOf(qx, qy, qz, qw))
    }

    private fun computePinModelMatrix(result: ResolveFrameProcessingResult, currCamTranslation: FloatArray, currCamRotation: FloatArray): FloatArray {
        val rvec = result.rvec
        val rx = rvec[0].toDouble()
        val ry = rvec[1].toDouble()
        val rz = rvec[2].toDouble()
        
        val theta = Math.sqrt(rx*rx + ry*ry + rz*rz)
        val oldWorldToCvCamera = FloatArray(16)
        Matrix.setIdentityM(oldWorldToCvCamera, 0)
        
        if (theta > 0.0001) {
            val axisX = (rx / theta).toFloat()
            val axisY = (ry / theta).toFloat()
            val axisZ = (rz / theta).toFloat()
            Matrix.rotateM(oldWorldToCvCamera, 0, Math.toDegrees(theta).toFloat(), axisX, axisY, axisZ)
        }

        oldWorldToCvCamera[12] = result.tvec[0]
        oldWorldToCvCamera[13] = result.tvec[1]
        oldWorldToCvCamera[14] = result.tvec[2]

        val cvToGl = FloatArray(16)
        Matrix.setIdentityM(cvToGl, 0)
        cvToGl[5] = -1f
        cvToGl[10] = -1f
        
        val glCameraToNewWorld = FloatArray(16)
        com.google.ar.core.Pose(currCamTranslation, currCamRotation).toMatrix(glCameraToNewWorld, 0)
        
        val pinLocalToOldWorld = FloatArray(16)
        val pTr = floatArrayOf(payload.pinPose[0], payload.pinPose[1], payload.pinPose[2])
        val pQt = floatArrayOf(payload.pinPose[3], payload.pinPose[4], payload.pinPose[5], payload.pinPose[6])
        com.google.ar.core.Pose(pTr, pQt).toMatrix(pinLocalToOldWorld, 0)
        val oldWorldToGlCamera = FloatArray(16)
        Matrix.multiplyMM(oldWorldToGlCamera, 0, cvToGl, 0, oldWorldToCvCamera, 0)
        val pinLocalToGlCamera = FloatArray(16)
        Matrix.multiplyMM(pinLocalToGlCamera, 0, oldWorldToGlCamera, 0, pinLocalToOldWorld, 0)
        val pinModelMatrix = FloatArray(16)
        Matrix.multiplyMM(pinModelMatrix, 0, glCameraToNewWorld, 0, pinLocalToGlCamera, 0)

        return pinModelMatrix
    }
}

internal class ResolveSearchController(
    private val payload: PointCloudPayload,
    private val onResult: (ResolveEngineResult) -> Unit
) {
    private var generation = 0
    private var engine = createEngine(generation)

    fun isPreciseSearchRunning(): Boolean = engine.isRunning()

    fun startPreciseSearch() {
        if (!engine.isRunning()) {
            engine.start()
        }
    }

    fun restartPreciseSearch() {
        if (engine.isRunning()) {
            engine.restartSearchWindow()
        } else {
            generation += 1
            engine = createEngine(generation)
            engine.start()
        }
        PerfLog.log("ResolveEngine", "Precise search restarted")
    }

    fun stopPreciseSearch() {
        generation += 1
        engine.stop()
        engine = createEngine(generation)
    }

    fun processFrame(
        image: android.media.Image,
        camTranslation: FloatArray,
        camRotation: FloatArray,
        liveFx: Float,
        liveFy: Float,
        liveCx: Float,
        liveCy: Float
    ) {
        engine.processFrame(
            image = image,
            camTranslation = camTranslation,
            camRotation = camRotation,
            liveFx = liveFx,
            liveFy = liveFy,
            liveCx = liveCx,
            liveCy = liveCy
        )
    }

    private fun createEngine(token: Int): ResolveEngine {
        return ResolveEngine(payload) { result ->
            if (token == generation) {
                onResult(result)
            } else {
                PerfLog.log("ResolveEngine", "Ignored stale resolve result from generation $token")
            }
        }
    }
}

sealed class ResolveEngineResult {
    data class Resolved(
        val pose: com.google.ar.core.Pose,
        val stats: ResolveEngineStats
    ) : ResolveEngineResult()

    data class Failed(
        val reason: String,
        val stats: ResolveEngineStats?
    ) : ResolveEngineResult()
}

data class ResolveEngineStats(
    val attemptCount: Int,
    val bestInlierCount: Int,
    val bestMatchCount: Int,
    val elapsedMs: Long,
    val payloadHash: String?
) {
    val shortPayloadHash: String
        get() = PayloadIntegrity.shortHash(payloadHash)
}
