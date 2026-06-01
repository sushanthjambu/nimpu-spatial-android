package com.nimpu.spatial.sdk

import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal class CreateSnapshotBuffer(
    val imageWidth: Int,
    val imageHeight: Int,
    val depthWidth: Int,
    val depthHeight: Int
) {
    val yPlane: ByteBuffer = ByteBuffer.allocateDirect(imageWidth * imageHeight)
    val depthBytes: ByteArray = ByteArray(depthWidth * depthHeight * 2)

    @Volatile
    private var inUse = false

    fun tryAcquire(): Boolean {
        if (inUse) return false
        inUse = true
        yPlane.clear()
        return true
    }

    fun release() {
        inUse = false
        yPlane.clear()
    }
}

internal class CreateSnapshotBufferPool(
    private val capacity: Int = 2
) {
    private var imageWidth = 0
    private var imageHeight = 0
    private var depthWidth = 0
    private var depthHeight = 0
    private var buffers: List<CreateSnapshotBuffer> = emptyList()

    fun acquire(
        imageWidth: Int,
        imageHeight: Int,
        depthWidth: Int,
        depthHeight: Int
    ): CreateSnapshotBuffer? {
        ensureShape(imageWidth, imageHeight, depthWidth, depthHeight)
        return buffers.firstOrNull { it.tryAcquire() }
    }

    fun reset() {
        buffers.forEach { it.release() }
    }

    fun clear() {
        buffers = emptyList()
        imageWidth = 0
        imageHeight = 0
        depthWidth = 0
        depthHeight = 0
    }

    private fun ensureShape(
        newImageWidth: Int,
        newImageHeight: Int,
        newDepthWidth: Int,
        newDepthHeight: Int
    ) {
        if (buffers.isNotEmpty() &&
            newImageWidth == imageWidth &&
            newImageHeight == imageHeight &&
            newDepthWidth == depthWidth &&
            newDepthHeight == depthHeight
        ) {
            return
        }

        imageWidth = newImageWidth
        imageHeight = newImageHeight
        depthWidth = newDepthWidth
        depthHeight = newDepthHeight
        buffers = List(capacity) {
            CreateSnapshotBuffer(
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                depthWidth = depthWidth,
                depthHeight = depthHeight
            )
        }
        PerfLog.log(
            "createSnapshotPool",
            "allocated buffers=$capacity image=${imageWidth}x$imageHeight depth=${depthWidth}x$depthHeight"
        )
    }
}

internal data class CreateFrameSnapshot(
    val candidateId: Long,
    val buffer: CreateSnapshotBuffer,
    val imageWidth: Int,
    val imageHeight: Int,
    val depthWidth: Int,
    val depthHeight: Int,
    val cameraPoseMatrix: FloatArray,
    val cameraPoseVector: FloatArray,
    val cameraIntrinsics: FloatArray,
    val anchorPose: FloatArray,
    val currentSector: Int,
    val distanceState: KeyframeCapture.DistanceState,
    val anchorVisible: Boolean,
    val timestampMs: Long,
    val lutGeneration: Int,
    val uvLut: CreateDepthUvLut
) {
    val yPlane: ByteBuffer
        get() = buffer.yPlane

    val depthBytes: ByteArray
        get() = buffer.depthBytes

    fun releaseBuffer() {
        buffer.release()
    }
}

internal sealed class CreateKeyframeProcessingResult {
    data class ResolvedCandidate(
        val snapshot: CreateFrameSnapshot,
        val featureResult: CreateFrameProcessingResult,
        val resolvedPoints: List<SpatialResolver.ResolvedPoint>,
        val processingMs: Double
    ) : CreateKeyframeProcessingResult()

    data class Failed(
        val snapshot: CreateFrameSnapshot?,
        val reason: String
    ) : CreateKeyframeProcessingResult()
}

internal class CreateKeyframeProcessor : AutoCloseable {
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "NimpuCreateKeyframeProcessor").apply {
            isDaemon = true
        }
    }
    private val inFlight = AtomicBoolean(false)

    @Volatile
    private var closed = false

    @Volatile
    private var generation = 0

    fun isBusy(): Boolean = inFlight.get()

    fun reset() {
        generation++
        inFlight.set(false)
    }

    fun pause() {
        generation++
    }

    fun submit(
        snapshot: CreateFrameSnapshot,
        task: (CreateFrameSnapshot) -> Pair<CreateFrameProcessingResult, List<SpatialResolver.ResolvedPoint>>,
        onResult: (CreateKeyframeProcessingResult) -> Unit
    ): Boolean {
        if (closed) return false
        if (!inFlight.compareAndSet(false, true)) return false
        val token = generation
        worker.execute {
            val startNs = System.nanoTime()
            val result = try {
                val (featureResult, resolvedPoints) = task(snapshot)
                val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0
                CreateKeyframeProcessingResult.ResolvedCandidate(
                    snapshot = snapshot,
                    featureResult = featureResult,
                    resolvedPoints = resolvedPoints,
                    processingMs = elapsedMs
                )
            } catch (e: Exception) {
                CreateKeyframeProcessingResult.Failed(
                    snapshot = snapshot,
                    reason = e.message ?: e.javaClass.simpleName
                )
            } finally {
                inFlight.set(false)
            }
            if (!closed && token == generation) {
                onResult(result)
            } else {
                snapshot.releaseBuffer()
            }
        }
        return true
    }

    override fun close() {
        closed = true
        generation++
        inFlight.set(false)
        worker.shutdownNow()
    }
}
