package com.example.spatialsdk.sample

import android.os.SystemClock
import android.util.Log

/**
 * Lightweight performance-timing utility.
 *
 * Usage:
 *   PerfLog.begin("renderFrame")
 *   // … work …
 *   PerfLog.end("renderFrame")   // prints: NimpuPerf: [renderFrame] 12.34ms
 *
 * Filter in logcat:  adb logcat -s NimpuPerf
 */
object PerfLog {

    private const val TAG = "NimpuPerf"
    private const val SLOW_FRAME_MS = 20.0

    /** Active timers keyed by tag. */
    private val timers = HashMap<String, Long>(16)

    /** Rolling frame-time tracker for FPS calculation. */
    private val frameTimes = LongArray(30)
    private var frameIndex = 0
    private var frameCount = 0

    /** Start a named timer. */
    fun begin(tag: String) {
        timers[tag] = SystemClock.elapsedRealtimeNanos()
    }

    /** End a named timer and log the elapsed milliseconds. */
    fun end(tag: String) {
        val start = timers.remove(tag) ?: run {
            Log.w(TAG, "[$tag] end() called without begin()")
            return
        }
        val elapsedNs = SystemClock.elapsedRealtimeNanos() - start
        val elapsedMs = elapsedNs / 1_000_000.0
        if (tag == "onDrawFrame" && elapsedMs < SLOW_FRAME_MS) {
            return
        }
        Log.d(TAG, "[$tag] %.2fms".format(elapsedMs))
    }

    /** Log a one-shot value (count, ratio, etc.). */
    fun log(tag: String, message: String) {
        Log.d(TAG, "[$tag] $message")
    }

    /**
     * Call once per frame (inside onDrawFrame) to track effective FPS.
     * Logs a rolling-average FPS every 30 frames.
     */
    fun tickFrame() {
        val now = SystemClock.elapsedRealtimeNanos()
        frameTimes[frameIndex] = now
        frameIndex = (frameIndex + 1) % frameTimes.size
        frameCount++

        // Only log FPS every 300 frames (~5 seconds) to reduce log spam
        if (frameCount >= frameTimes.size && frameCount % 300 == 0) {
            val oldest = frameTimes[frameIndex] // next slot is the oldest
            val spanMs = (now - oldest) / 1_000_000.0
            val fps = (frameTimes.size - 1) * 1000.0 / spanMs
            Log.d(TAG, "[fps] %.1f".format(fps))
        }
    }
}
