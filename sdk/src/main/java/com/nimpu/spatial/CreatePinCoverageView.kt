package com.nimpu.spatial.sdk

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class CreatePinCoverageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val arcBounds = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = resources.displayMetrics.density * 8f
    }

    private var sectorMask: List<Boolean> = List(8) { false }
    private var attemptedSectorMask: List<Boolean> = List(8) { false }
    private var currentSector: Int? = null
    private var ready = false

    fun bind(state: CreatePinState) {
        sectorMask = if (state.acceptedSectorMask.isEmpty() && state.totalSectors > 0) {
            List(state.totalSectors) { false }
        } else {
            state.acceptedSectorMask
        }
        attemptedSectorMask = if (state.attemptedSectorMask.isEmpty() && state.totalSectors > 0) {
            List(state.totalSectors) { false }
        } else {
            state.attemptedSectorMask
        }
        currentSector = state.currentSector
        ready = state.canSave
        invalidate()
    }

    fun updateSectors(
        mask: List<Boolean>,
        attemptedMask: List<Boolean>,
        current: Int?,
        isReady: Boolean
    ) {
        sectorMask = mask
        attemptedSectorMask = attemptedMask
        currentSector = current
        ready = isReady
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (sectorMask.isEmpty()) return

        val padding = paint.strokeWidth
        arcBounds.set(padding, padding, width - padding, height - padding)

        val startAngle = 150f
        val sweepAngle = 240f
        val gap = 6f
        val sectorSweep = sweepAngle / sectorMask.size

        for (index in sectorMask.indices) {
            val attempted = attemptedSectorMask.getOrNull(index) == true
            paint.color = when {
                sectorMask[index] -> 0xFF4CAF50.toInt()
                attempted -> 0xFFFFB300.toInt()
                currentSector == index -> 0xFFFFB300.toInt()
                else -> 0x55FFFFFF
            }
            if (ready && sectorMask[index]) {
                paint.color = 0xFF2E7D32.toInt()
            }
            val segmentStart = startAngle + (index * sectorSweep) + gap / 2f
            val segmentSweep = sectorSweep - gap
            canvas.drawArc(arcBounds, segmentStart, segmentSweep, false, paint)
        }
    }
}
