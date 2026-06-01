package com.nimpu.spatial.sdk

import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal class CreateDepthUvLut private constructor(
    val generation: Int,
    val imageWidth: Int,
    val imageHeight: Int,
    val step: Int,
    private val gridWidth: Int,
    private val gridHeight: Int,
    private val textureUvs: FloatArray
) {
    fun interpolateTextureUv(imageX: Float, imageY: Float): FloatArray? {
        if (!imageX.isFinite() || !imageY.isFinite()) return null
        if (imageX < 0f || imageY < 0f || imageX > imageWidth - 1f || imageY > imageHeight - 1f) {
            return null
        }

        val gx = imageX / step.toFloat()
        val gy = imageY / step.toFloat()
        val x0 = floor(gx).toInt().coerceIn(0, gridWidth - 1)
        val y0 = floor(gy).toInt().coerceIn(0, gridHeight - 1)
        val x1 = min(x0 + 1, gridWidth - 1)
        val y1 = min(y0 + 1, gridHeight - 1)
        val tx = (gx - x0).coerceIn(0f, 1f)
        val ty = (gy - y0).coerceIn(0f, 1f)

        val uv00 = uvAt(x0, y0) ?: return null
        val uv10 = uvAt(x1, y0) ?: return null
        val uv01 = uvAt(x0, y1) ?: return null
        val uv11 = uvAt(x1, y1) ?: return null

        val u0 = lerp(uv00[0], uv10[0], tx)
        val v0 = lerp(uv00[1], uv10[1], tx)
        val u1 = lerp(uv01[0], uv11[0], tx)
        val v1 = lerp(uv01[1], uv11[1], tx)
        val u = lerp(u0, u1, ty)
        val v = lerp(v0, v1, ty)

        if (!u.isFinite() || !v.isFinite() || u < 0f || v < 0f || u > 1f || v > 1f) {
            return null
        }
        return floatArrayOf(u, v)
    }

    private fun uvAt(gridX: Int, gridY: Int): FloatArray? {
        val index = (gridY * gridWidth + gridX) * 2
        val u = textureUvs[index]
        val v = textureUvs[index + 1]
        if (!u.isFinite() || !v.isFinite() || u < 0f || v < 0f || u > 1f || v > 1f) {
            return null
        }
        return floatArrayOf(u, v)
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + ((b - a) * t)

    data class ValidationStats(
        val count: Int,
        val averageDepthPixelError: Float,
        val p90DepthPixelError: Float,
        val p95DepthPixelError: Float,
        val maxDepthPixelError: Float
    )

    fun validateAgainstFrame(
        frame: Frame,
        depthWidth: Int,
        depthHeight: Int,
        sampleCount: Int = 96
    ): ValidationStats? {
        if (sampleCount <= 0 || imageWidth <= 1 || imageHeight <= 1) return null

        val points = FloatArray(sampleCount * 2)
        val columns = max(1, sqrt(sampleCount.toDouble()).roundToInt())
        val rows = ceil(sampleCount / columns.toDouble()).toInt()
        var filled = 0
        for (row in 0 until rows) {
            for (col in 0 until columns) {
                if (filled >= sampleCount) break
                val x = ((col + 0.5f) / columns.toFloat()) * (imageWidth - 1)
                val y = ((row + 0.5f) / rows.toFloat()) * (imageHeight - 1)
                points[filled * 2] = x
                points[filled * 2 + 1] = y
                filled++
            }
        }

        val directUvs = FloatArray(filled * 2)
        frame.transformCoordinates2d(
            Coordinates2d.IMAGE_PIXELS,
            points.copyOf(filled * 2),
            Coordinates2d.TEXTURE_NORMALIZED,
            directUvs
        )

        val errors = mutableListOf<Float>()
        for (i in 0 until filled) {
            val directU = directUvs[i * 2]
            val directV = directUvs[i * 2 + 1]
            if (!directU.isFinite() || !directV.isFinite() ||
                directU < 0f || directV < 0f || directU > 1f || directV > 1f
            ) {
                continue
            }
            val lutUv = interpolateTextureUv(points[i * 2], points[i * 2 + 1]) ?: continue
            val duPixels = (directU - lutUv[0]) * depthWidth
            val dvPixels = (directV - lutUv[1]) * depthHeight
            errors.add(sqrt((duPixels * duPixels + dvPixels * dvPixels).toDouble()).toFloat())
        }
        if (errors.isEmpty()) return null
        errors.sort()
        return ValidationStats(
            count = errors.size,
            averageDepthPixelError = errors.average().toFloat(),
            p90DepthPixelError = percentile(errors, 0.90f),
            p95DepthPixelError = percentile(errors, 0.95f),
            maxDepthPixelError = errors.last()
        )
    }

    private fun percentile(sortedValues: List<Float>, percentile: Float): Float {
        if (sortedValues.isEmpty()) return 0f
        val index = ((sortedValues.size - 1) * percentile).roundToInt()
            .coerceIn(0, sortedValues.size - 1)
        return sortedValues[index]
    }

    companion object {
        const val DEFAULT_STEP = 8

        fun build(
            frame: Frame,
            imageWidth: Int,
            imageHeight: Int,
            generation: Int,
            step: Int = DEFAULT_STEP
        ): CreateDepthUvLut {
            val gridWidth = ((imageWidth - 1) / step) + 1
            val gridHeight = ((imageHeight - 1) / step) + 1
            val imagePixels = FloatArray(gridWidth * gridHeight * 2)
            var index = 0
            for (gy in 0 until gridHeight) {
                for (gx in 0 until gridWidth) {
                    imagePixels[index++] = min(gx * step, imageWidth - 1).toFloat()
                    imagePixels[index++] = min(gy * step, imageHeight - 1).toFloat()
                }
            }
            val textureUvs = FloatArray(imagePixels.size)
            frame.transformCoordinates2d(
                Coordinates2d.IMAGE_PIXELS,
                imagePixels,
                Coordinates2d.TEXTURE_NORMALIZED,
                textureUvs
            )
            return CreateDepthUvLut(
                generation = generation,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                step = step,
                gridWidth = gridWidth,
                gridHeight = gridHeight,
                textureUvs = textureUvs
            )
        }
    }
}
