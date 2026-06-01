package com.nimpu.spatial.sdk

import android.os.SystemClock
import android.util.Log

object PerfLog {
    private const val TAG = "NimpuPerf"
    private const val SLOW_FRAME_MS = 20.0

    private val timers = ThreadLocal.withInitial { HashMap<String, Long>(16) }
    private val frameTimes = LongArray(30)
    private var frameIndex = 0
    private var frameCount = 0

    fun begin(tag: String) {
        currentTimers()[tag] = SystemClock.elapsedRealtimeNanos()
    }

    fun end(tag: String) {
        end(tag, minLogMs = if (tag == "onDrawFrame") SLOW_FRAME_MS else 0.0)
    }

    fun end(tag: String, minLogMs: Double) {
        val start = currentTimers().remove(tag) ?: run {
            Log.w(TAG, "[$tag] end() called without begin()")
            return
        }
        val elapsedNs = SystemClock.elapsedRealtimeNanos() - start
        val elapsedMs = elapsedNs / 1_000_000.0
        if (elapsedMs < minLogMs) {
            return
        }
        Log.d(TAG, "[$tag] %.2fms".format(elapsedMs))
    }

    fun log(tag: String, message: String) {
        Log.d(TAG, "[$tag] $message")
    }

    fun tickFrame() {
        val now = SystemClock.elapsedRealtimeNanos()
        frameTimes[frameIndex] = now
        frameIndex = (frameIndex + 1) % frameTimes.size
        frameCount++

        if (frameCount >= frameTimes.size && frameCount % 300 == 0) {
            val oldest = frameTimes[frameIndex]
            val spanMs = (now - oldest) / 1_000_000.0
            val fps = (frameTimes.size - 1) * 1000.0 / spanMs
            Log.d(TAG, "[fps] %.1f".format(fps))
        }
    }

    private fun currentTimers(): HashMap<String, Long> {
        val existing = timers.get()
        if (existing != null) return existing
        val created = HashMap<String, Long>(16)
        timers.set(created)
        return created
    }
}
