package com.nimpu.spatial.sdk

import android.media.Image
import android.opengl.Matrix
import android.util.Log
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException

/**
 * Converts SDK frame-processing output into world-space points used by Create Pin.
 */
internal object SpatialResolver {
    data class ResolvedPoint(
        val worldX: Float,
        val worldY: Float,
        val worldZ: Float,
        val descriptorIndex: Int
    )
    private const val DEPTH_CONFIDENCE_MIN = 50
    fun resolve(
        featureResult: CreateFrameProcessingResult,
        frame: Frame
    ): List<ResolvedPoint> {
        if (featureResult.count == 0) return emptyList()

        val resolved = mutableListOf<ResolvedPoint>()
        var discarded = 0
        val imagePixels = FloatArray(featureResult.count * 2)
        for (i in 0 until featureResult.count) {
            imagePixels[i * 2] = featureResult.keypointX[i]
            imagePixels[i * 2 + 1] = featureResult.keypointY[i]
        }

        var depthImage: Image? = null
        PerfLog.begin("spatialDepthAcquire")
        try {
            depthImage = frame.acquireDepthImage16Bits()
        } catch (e: NotYetAvailableException) {
        } catch (e: Exception) {
        } finally {
            PerfLog.end("spatialDepthAcquire", minLogMs = 1.0)
        }

        if (depthImage != null) {
            Log.d("NimpuDebug", "SpatialResolver: Using primary spatial estimate path")
            val intrinsics = frame.camera.imageIntrinsics
            val fx = intrinsics.focalLength[0]
            val fy = intrinsics.focalLength[1]
            val cx = intrinsics.principalPoint[0]
            val cy = intrinsics.principalPoint[1]

            val cameraPose = FloatArray(16)
            frame.camera.pose.toMatrix(cameraPose, 0)
            
            val depthPlane = depthImage.planes[0]
            val depthBuffer = depthPlane.buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val depthRowStride = depthPlane.rowStride
            val depthPixelStride = depthPlane.pixelStride
            val depthWidth = depthImage.width
            val depthHeight = depthImage.height
            var confidenceBuffer: java.nio.ByteBuffer? = null
            var confidenceRowStride = 0
            var confidencePixelStride = 1
            var confidenceImage: android.media.Image? = null
            PerfLog.begin("spatialConfidenceAcquire")
            try {
                confidenceImage = frame.acquireRawDepthConfidenceImage()
                val confPlane = confidenceImage.planes[0]
                confidenceBuffer = confPlane.buffer
                confidenceRowStride = confPlane.rowStride
                confidencePixelStride = confPlane.pixelStride
            } catch (e: Exception) {
            } finally {
                PerfLog.end("spatialConfidenceAcquire", minLogMs = 1.0)
            }
            val depthUvs = FloatArray(featureResult.count * 2)
            PerfLog.begin("spatialDepthTransform")
            frame.transformCoordinates2d(
                Coordinates2d.IMAGE_PIXELS, imagePixels,
                Coordinates2d.TEXTURE_NORMALIZED, depthUvs
            )
            PerfLog.end("spatialDepthTransform", minLogMs = 1.0)

            PerfLog.begin("spatialDepthUnproject")
            for (i in 0 until featureResult.count) {
                val u = imagePixels[i * 2]
                val v = imagePixels[i * 2 + 1]
                val depthU = depthUvs[i * 2]
                val depthV = depthUvs[i * 2 + 1]
                if (depthU < 0f || depthV < 0f || depthU > 1f || depthV > 1f) {
                    discarded++
                    continue
                }
                val dx = (depthU * depthWidth).toInt().coerceIn(0, depthWidth - 1)
                val dy = (depthV * depthHeight).toInt().coerceIn(0, depthHeight - 1)
                if (confidenceBuffer != null) {
                    val confByteIndex = dy * confidenceRowStride + dx * confidencePixelStride
                    val confidence = confidenceBuffer.get(confByteIndex).toInt() and 0xFF
                    if (confidence < DEPTH_CONFIDENCE_MIN) {
                        discarded++
                        continue
                    }
                }

                val depthByteIndex = dy * depthRowStride + dx * depthPixelStride
                val depthMm = depthBuffer.getShort(depthByteIndex).toInt() and 0xFFFF
                
                if (depthMm > 0) {
                    val zMeters = depthMm / 1000.0f
                    val localXCv = ((u - cx) * zMeters) / fx
                    val localYCv = ((v - cy) * zMeters) / fy
                    val localX = localXCv
                    val localY = -localYCv
                    val localZ = -zMeters
                    val localPoint = floatArrayOf(localX, localY, localZ, 1.0f)
                    val worldPoint = FloatArray(4)
                    Matrix.multiplyMV(worldPoint, 0, cameraPose, 0, localPoint, 0)
                    
                    resolved.add(
                        ResolvedPoint(
                            worldX = worldPoint[0],
                            worldY = worldPoint[1],
                            worldZ = worldPoint[2],
                            descriptorIndex = i
                        )
                    )
                } else {
                    discarded++
                }
            }
            PerfLog.end("spatialDepthUnproject", minLogMs = 3.0)
            confidenceImage?.close()
            depthImage.close()
        } else {
            Log.d("NimpuDebug", "SpatialResolver: Using alternate spatial estimate path")
            val viewPixels = FloatArray(featureResult.count * 2)
            PerfLog.begin("spatialPlaneTransform")
            frame.transformCoordinates2d(
                Coordinates2d.IMAGE_PIXELS, imagePixels,
                Coordinates2d.VIEW, viewPixels
            )
            PerfLog.end("spatialPlaneTransform", minLogMs = 1.0)
            
            PerfLog.begin("spatialPlaneHitTest")
            for (i in 0 until featureResult.count) {
                val screenX = viewPixels[i * 2]
                val screenY = viewPixels[i * 2 + 1]
                val hits = frame.hitTest(screenX, screenY)
                val planeHit = hits.firstOrNull { hit ->
                    val trackable = hit.trackable
                    trackable is Plane && trackable.trackingState == TrackingState.TRACKING
                }
                
                if (planeHit != null) {
                    val pos = planeHit.hitPose.translation
                    resolved.add(
                        ResolvedPoint(
                            worldX = pos[0],
                            worldY = pos[1],
                            worldZ = pos[2],
                            descriptorIndex = i
                        )
                    )
                } else {
                    discarded++
                }
            }
            PerfLog.end("spatialPlaneHitTest", minLogMs = 3.0)
        }

        PerfLog.log("spatialResolve",
            "total=${featureResult.count}, resolved=${resolved.size}, discarded=$discarded")

        return resolved
    }

    internal fun resolveFromSnapshot(
        featureResult: CreateFrameProcessingResult,
        snapshot: CreateFrameSnapshot
    ): List<ResolvedPoint> {
        if (featureResult.count == 0) return emptyList()

        val resolved = mutableListOf<ResolvedPoint>()
        var discarded = 0

        val fx = snapshot.cameraIntrinsics[0]
        val fy = snapshot.cameraIntrinsics[1]
        val cx = snapshot.cameraIntrinsics[2]
        val cy = snapshot.cameraIntrinsics[3]

        for (i in 0 until featureResult.count) {
            val u = featureResult.keypointX[i]
            val v = featureResult.keypointY[i]
            val textureUv = snapshot.uvLut.interpolateTextureUv(u, v)
            if (textureUv == null) {
                discarded++
                continue
            }

            val depthX = (textureUv[0] * snapshot.depthWidth)
                .toInt()
                .coerceIn(0, snapshot.depthWidth - 1)
            val depthY = (textureUv[1] * snapshot.depthHeight)
                .toInt()
                .coerceIn(0, snapshot.depthHeight - 1)
            val depthMm = readUnsignedLittleEndianShort(snapshot.depthBytes, depthX, depthY, snapshot.depthWidth)
            if (depthMm <= 0) {
                discarded++
                continue
            }

            val zMeters = depthMm / 1000.0f
            val localXCv = ((u - cx) * zMeters) / fx
            val localYCv = ((v - cy) * zMeters) / fy

            val localPoint = floatArrayOf(
                localXCv,
                -localYCv,
                -zMeters,
                1.0f
            )
            val worldPoint = FloatArray(4)
            Matrix.multiplyMV(worldPoint, 0, snapshot.cameraPoseMatrix, 0, localPoint, 0)

            resolved.add(
                ResolvedPoint(
                    worldX = worldPoint[0],
                    worldY = worldPoint[1],
                    worldZ = worldPoint[2],
                    descriptorIndex = i
                )
            )
        }

        PerfLog.log(
            "spatialSnapshotResolve",
            "total=${featureResult.count}, resolved=${resolved.size}, discarded=$discarded"
        )
        return resolved
    }

    private fun readUnsignedLittleEndianShort(
        bytes: ByteArray,
        x: Int,
        y: Int,
        width: Int
    ): Int {
        val index = ((y * width) + x) * 2
        if (index < 0 || index + 1 >= bytes.size) return 0
        val low = bytes[index].toInt() and 0xFF
        val high = bytes[index + 1].toInt() and 0xFF
        return low or (high shl 8)
    }
}
