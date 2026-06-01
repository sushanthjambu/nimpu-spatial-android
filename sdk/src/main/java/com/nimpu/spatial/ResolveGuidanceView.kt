package com.nimpu.spatial.sdk

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs

class ResolveGuidanceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val arrowSizePx = 40f * density
    private val arrowHalfSizePx = arrowSizePx / 2f
    private val edgeInsetPx = 16f * density
    private val obstaclePaddingPx = 8f * density
    private val smoothFactor = 0.35f

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFFD700.toInt()
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xCC1A1A1A.toInt()
        strokeWidth = 2f * density
    }
    private val arrowPath = Path().apply {
        moveTo(-18f, -12f)
        lineTo(6f, -12f)
        lineTo(6f, -20f)
        lineTo(20f, 0f)
        lineTo(6f, 20f)
        lineTo(6f, 12f)
        lineTo(-18f, 12f)
        lineTo(-8f, 0f)
        close()
    }

    private val blockedViews = mutableListOf<View>()
    private val blockedRects = mutableListOf<RectF>()

    private var indicatorVisible = false
    private var desiredCenterX = 0f
    private var desiredCenterY = 0f
    private var desiredRotation = 0f
    private var renderedCenterX = Float.NaN
    private var renderedCenterY = Float.NaN
    private var renderedRotation = Float.NaN

    fun setBlockedViews(vararg views: View) {
        blockedViews.clear()
        blockedViews.addAll(views)
        invalidate()
    }

    fun bind(state: ResolvePinState) {
        updateIndicator(state.guidanceIndicator)
    }

    fun updateIndicator(state: ResolveGuidanceIndicatorState) {
        indicatorVisible = state.visible
        if (!state.visible) {
            renderedCenterX = Float.NaN
            renderedCenterY = Float.NaN
            renderedRotation = Float.NaN
            invalidate()
            return
        }

        desiredCenterX = state.screenX
        desiredCenterY = state.screenY
        desiredRotation = state.rotationDegrees
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!indicatorVisible || width <= 0 || height <= 0) return

        val target = computeResolvedPlacement(desiredCenterX, desiredCenterY)
        if (!renderedCenterX.isFinite() || !renderedCenterY.isFinite()) {
            renderedCenterX = target.first
            renderedCenterY = target.second
            renderedRotation = desiredRotation
        } else {
            renderedCenterX = lerp(renderedCenterX, target.first, smoothFactor)
            renderedCenterY = lerp(renderedCenterY, target.second, smoothFactor)
            renderedRotation = lerpAngle(renderedRotation, desiredRotation, smoothFactor)
        }

        canvas.save()
        canvas.translate(renderedCenterX, renderedCenterY)
        canvas.rotate(renderedRotation)
        val scale = arrowSizePx / 40f
        canvas.scale(scale, scale)
        canvas.drawPath(arrowPath, fillPaint)
        canvas.drawPath(arrowPath, strokePaint)
        canvas.restore()

        if (abs(renderedCenterX - target.first) > 0.5f ||
            abs(renderedCenterY - target.second) > 0.5f ||
            abs(shortestAngleDelta(renderedRotation, desiredRotation)) > 0.5f
        ) {
            postInvalidateOnAnimation()
        }
    }

    private fun computeResolvedPlacement(rawCenterX: Float, rawCenterY: Float): Pair<Float, Float> {
        val minCenterX = edgeInsetPx + arrowHalfSizePx
        val maxCenterX = width - edgeInsetPx - arrowHalfSizePx
        val minCenterY = edgeInsetPx + arrowHalfSizePx
        val maxCenterY = height - edgeInsetPx - arrowHalfSizePx

        var centerX = rawCenterX.coerceIn(minCenterX, maxCenterX)
        var centerY = rawCenterY.coerceIn(minCenterY, maxCenterY)

        collectBlockedRects()

        when (determineEdge(centerX, centerY, minCenterX, maxCenterX, minCenterY, maxCenterY)) {
            ScreenEdge.LEFT -> {
                centerX = minCenterX
                centerY = resolveCenterOnVerticalEdge(centerY, centerX, minCenterY, maxCenterY, true)
            }
            ScreenEdge.RIGHT -> {
                centerX = maxCenterX
                centerY = resolveCenterOnVerticalEdge(centerY, centerX, minCenterY, maxCenterY, false)
            }
            ScreenEdge.TOP -> {
                centerY = minCenterY
                centerX = resolveCenterOnHorizontalEdge(centerX, centerY, minCenterX, maxCenterX, true)
            }
            ScreenEdge.BOTTOM -> {
                centerY = maxCenterY
                centerX = resolveCenterOnHorizontalEdge(centerX, centerY, minCenterX, maxCenterX, false)
            }
        }

        return centerX to centerY
    }

    private fun collectBlockedRects() {
        blockedRects.clear()
        for (view in blockedViews) {
            if (view.visibility != VISIBLE || view.width <= 0 || view.height <= 0) continue
            blockedRects += RectF(view.x, view.y, view.x + view.width, view.y + view.height)
        }
    }

    private fun resolveCenterOnVerticalEdge(
        desired: Float,
        edgeX: Float,
        minCenter: Float,
        maxCenter: Float,
        leftEdge: Boolean
    ): Float {
        val blockedIntervals = mutableListOf<ClosedFloatingPointRange<Float>>()
        val arrowLeft = edgeX - arrowHalfSizePx
        val arrowRight = edgeX + arrowHalfSizePx

        for (rect in blockedRects) {
            val overlapsEdge = if (leftEdge) {
                rect.left <= arrowRight + obstaclePaddingPx
            } else {
                rect.right >= arrowLeft - obstaclePaddingPx
            }
            if (!overlapsEdge) continue
            blockedIntervals += (rect.top - arrowHalfSizePx - obstaclePaddingPx)..(rect.bottom + arrowHalfSizePx + obstaclePaddingPx)
        }
        return nearestAllowedValue(desired, minCenter, maxCenter, blockedIntervals)
    }

    private fun resolveCenterOnHorizontalEdge(
        desired: Float,
        edgeY: Float,
        minCenter: Float,
        maxCenter: Float,
        topEdge: Boolean
    ): Float {
        val blockedIntervals = mutableListOf<ClosedFloatingPointRange<Float>>()
        val arrowTop = edgeY - arrowHalfSizePx
        val arrowBottom = edgeY + arrowHalfSizePx

        for (rect in blockedRects) {
            val overlapsEdge = if (topEdge) {
                rect.top <= arrowBottom + obstaclePaddingPx
            } else {
                rect.bottom >= arrowTop - obstaclePaddingPx
            }
            if (!overlapsEdge) continue
            blockedIntervals += (rect.left - arrowHalfSizePx - obstaclePaddingPx)..(rect.right + arrowHalfSizePx + obstaclePaddingPx)
        }
        return nearestAllowedValue(desired, minCenter, maxCenter, blockedIntervals)
    }

    private fun nearestAllowedValue(
        desired: Float,
        minValue: Float,
        maxValue: Float,
        blockedIntervals: List<ClosedFloatingPointRange<Float>>
    ): Float {
        val clampedDesired = desired.coerceIn(minValue, maxValue)
        if (blockedIntervals.isEmpty()) return clampedDesired

        val merged = mergeIntervals(
            blockedIntervals.map {
                it.start.coerceIn(minValue, maxValue)..it.endInclusive.coerceIn(minValue, maxValue)
            }.filter { it.start <= it.endInclusive }
        )

        if (merged.none { clampedDesired in it }) return clampedDesired

        var bestValue = clampedDesired
        var bestDistance = Float.MAX_VALUE
        for (interval in merged) {
            if (clampedDesired !in interval) continue
            val candidateBefore = interval.start - 1f
            val candidateAfter = interval.endInclusive + 1f
            if (candidateBefore >= minValue) {
                val distance = abs(clampedDesired - candidateBefore)
                if (distance < bestDistance) {
                    bestDistance = distance
                    bestValue = candidateBefore
                }
            }
            if (candidateAfter <= maxValue) {
                val distance = abs(clampedDesired - candidateAfter)
                if (distance < bestDistance) {
                    bestDistance = distance
                    bestValue = candidateAfter
                }
            }
        }
        return bestValue.coerceIn(minValue, maxValue)
    }

    private fun mergeIntervals(intervals: List<ClosedFloatingPointRange<Float>>): List<ClosedFloatingPointRange<Float>> {
        if (intervals.isEmpty()) return emptyList()
        val sorted = intervals.sortedBy { it.start }
        val merged = mutableListOf<ClosedFloatingPointRange<Float>>()
        var current = sorted.first()
        for (next in sorted.drop(1)) {
            current = if (next.start <= current.endInclusive) {
                current.start..maxOf(current.endInclusive, next.endInclusive)
            } else {
                merged += current
                next
            }
        }
        merged += current
        return merged
    }

    private fun determineEdge(
        centerX: Float,
        centerY: Float,
        minCenterX: Float,
        maxCenterX: Float,
        minCenterY: Float,
        maxCenterY: Float
    ): ScreenEdge {
        val leftDistance = abs(centerX - minCenterX)
        val rightDistance = abs(centerX - maxCenterX)
        val topDistance = abs(centerY - minCenterY)
        val bottomDistance = abs(centerY - maxCenterY)
        return listOf(
            ScreenEdge.LEFT to leftDistance,
            ScreenEdge.RIGHT to rightDistance,
            ScreenEdge.TOP to topDistance,
            ScreenEdge.BOTTOM to bottomDistance
        ).minBy { it.second }.first
    }

    private fun lerp(start: Float, end: Float, factor: Float): Float =
        start + (end - start) * factor

    private fun lerpAngle(start: Float, end: Float, factor: Float): Float =
        start + shortestAngleDelta(start, end) * factor

    private fun shortestAngleDelta(from: Float, to: Float): Float {
        var delta = (to - from) % 360f
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        return delta
    }

    private enum class ScreenEdge {
        LEFT,
        RIGHT,
        TOP,
        BOTTOM
    }
}
