package com.nimpu.spatial.sdk

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class ResolveSession internal constructor(
    private val payload: PointCloudPayload,
    private val config: NimpuSpatialConfig,
    private val resolveMatchingCore: ResolveMatchingCore = DefaultResolveMatchingCore
) {
    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val searchController = resolveMatchingCore.newSearchController(payload, ::publishResolveResult)
    private val stateObservers = linkedSetOf<(ResolvePinState) -> Unit>()
    private val guidanceObservers = linkedSetOf<(ResolveGuidanceIndicatorState) -> Unit>()
    private val resultObservers = linkedSetOf<(ResolveEngineResult) -> Unit>()
    private var lastState: ResolvePinState? = null
    private var lastGuidanceIndicator: ResolveGuidanceIndicatorState = ResolveGuidanceIndicatorState()

    private val resolvePinController: ResolvePinController = ResolvePinController(config)

    val isGeospatialGuidanceEnabled: Boolean
        get() = config.isGeospatialGuidanceEnabled

    fun startPreciseSearch() {
        searchController.startPreciseSearch()
    }

    fun restartPreciseSearch() {
        searchController.restartPreciseSearch()
    }

    fun stopPreciseSearch() {
        searchController.stopPreciseSearch()
    }

    fun isPreciseSearchRunning(): Boolean = searchController.isPreciseSearchRunning()

    fun evaluateWorkflowState(input: ResolveHandoffInput): ResolveWorkflowState =
        resolvePinController.evaluateState(input)

    fun processFrame(
        image: android.media.Image,
        camTranslation: FloatArray,
        camRotation: FloatArray,
        liveFx: Float,
        liveFy: Float,
        liveCx: Float,
        liveCy: Float
    ) {
        searchController.processFrame(
            image = image,
            camTranslation = camTranslation,
            camRotation = camRotation,
            liveFx = liveFx,
            liveFy = liveFy,
            liveCx = liveCx,
            liveCy = liveCy
        )
    }

    fun observeResolveState(observer: (ResolvePinState) -> Unit): ResolveObservation {
        val currentState = synchronized(lock) {
            stateObservers.add(observer)
            lastState
        }
        currentState?.let(observer)
        return ResolveObservation {
            synchronized(lock) {
                stateObservers.remove(observer)
            }
        }
    }

    fun observeResolveGuidance(
        observer: (ResolveGuidanceIndicatorState) -> Unit
    ): ResolveObservation {
        val currentIndicator = synchronized(lock) {
            guidanceObservers.add(observer)
            lastGuidanceIndicator
        }
        observer(currentIndicator)
        return ResolveObservation {
            synchronized(lock) {
                guidanceObservers.remove(observer)
            }
        }
    }

    fun observeResolveResult(observer: (ResolveEngineResult) -> Unit): ResolveObservation {
        synchronized(lock) {
            resultObservers.add(observer)
        }
        return ResolveObservation {
            synchronized(lock) {
                resultObservers.remove(observer)
            }
        }
    }

    fun resolveStateFlow(): Flow<ResolvePinState> =
        callbackFlow {
            val observation = observeResolveState { state ->
                trySend(state)
            }
            awaitClose { observation.dispose() }
        }

    fun resolveGuidanceFlow(): Flow<ResolveGuidanceIndicatorState> =
        callbackFlow {
            val observation = observeResolveGuidance { indicator ->
                trySend(indicator)
            }
            awaitClose { observation.dispose() }
        }

    fun resolveResultFlow(): Flow<ResolveEngineResult> =
        callbackFlow {
            val observation = observeResolveResult { result ->
                trySend(result)
            }
            awaitClose { observation.dispose() }
        }

    fun attachResolveGuidanceView(view: ResolveGuidanceView): ResolveObservation {
        val stateObservation = observeResolveState { state ->
            mainHandler.post { view.bind(state) }
        }
        val guidanceObservation = observeResolveGuidance { indicator ->
            mainHandler.post { view.updateIndicator(indicator) }
        }
        return ResolveObservation {
            stateObservation.dispose()
            guidanceObservation.dispose()
        }
    }

    fun publishResolveState(state: ResolvePinState) {
        val observers = synchronized(lock) {
            lastState = state
            stateObservers.toList()
        }
        observers.forEach { it(state) }
    }

    fun publishResolveGuidance(indicatorState: ResolveGuidanceIndicatorState) {
        val observers = synchronized(lock) {
            lastGuidanceIndicator = indicatorState
            guidanceObservers.toList()
        }
        observers.forEach { it(indicatorState) }
    }

    private fun publishResolveResult(result: ResolveEngineResult) {
        val observers = synchronized(lock) {
            resultObservers.toList()
        }
        observers.forEach { it(result) }
    }
}

class ResolveObservation internal constructor(
    private val onDispose: () -> Unit
) {
    private var disposed = false

    fun dispose() {
        if (disposed) return
        disposed = true
        onDispose()
    }
}
