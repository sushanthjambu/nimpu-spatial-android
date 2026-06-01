package com.nimpu.spatial.sdk

enum class CreatePinPhase {
    WARMING_UP,
    READY_TO_PLACE,
    ANCHOR_SETTLING,
    GUIDED_SCANNING,
    SCAN_READY,
    TIMED_OUT
}

data class CreatePinState(
    val phase: CreatePinPhase,
    val progressMessage: String,
    val instructionMessage: String,
    val coveredSectors: Int = 0,
    val totalSectors: Int = 0,
    val currentSector: Int? = null,
    val acceptedSectorMask: List<Boolean> = emptyList(),
    val attemptedSectorMask: List<Boolean> = emptyList(),
    val canSave: Boolean = false,
    val timedOut: Boolean = false,
    val diagnostics: CreateDiagnostics? = null
)

data class ResolvePinState(
    val phase: ResolveWorkflowState,
    val message: String,
    val earthStatus: String,
    val distanceMeters: Float? = null,
    val altitudeDeltaMeters: Float? = null,
    val coarseAnchorAvailable: Boolean = false,
    val preciseSearchActive: Boolean = false,
    val localResolveLocked: Boolean = false,
    val showTryAgain: Boolean = false,
    val showStartPreciseSearch: Boolean = false,
    val guidanceIndicator: ResolveGuidanceIndicatorState = ResolveGuidanceIndicatorState(),
    val diagnostics: ResolveDiagnostics? = null
)

data class CreateDiagnostics(
    val geospatialAccuracy: GeospatialAccuracy? = null,
    val keyframeQuality: KeyframeQuality? = null,
    val worker: CreateWorkerDiagnostics? = null,
    val payloadHash: String? = null,
    val payloadHashAlgorithm: String? = null,
    val payloadCanonicalization: String? = null
)

data class ResolveDiagnostics(
    val geospatialAccuracy: GeospatialAccuracy? = null,
    val lastDistanceMeters: Float? = null,
    val lastAltitudeDeltaMeters: Float? = null,
    val payloadHash: String? = null,
    val payloadHashAlgorithm: String? = null,
    val payloadCanonicalization: String? = null
)

data class GeospatialAccuracy(
    val status: String,
    val sourceLabel: String,
    val latitudeText: String,
    val longitudeText: String,
    val altitudeText: String,
    val horizontalAccuracyText: String,
    val verticalAccuracyText: String,
    val headingAccuracyText: String,
    val localizationQualityText: String,
    val vpsQualityText: String
)

data class KeyframeQuality(
    val featureCount: Int? = null,
    val resolvedPointCount: Int? = null,
    val score: Float? = null,
    val rejectionReason: String? = null
)

data class CreateWorkerDiagnostics(
    val processingMode: String,
    val workerBusy: Boolean,
    val lastKeyframeProcessingMs: Double? = null,
    val droppedCandidateCount: Int = 0,
    val skippedCandidateCount: Int = 0
)

data class ResolveGuidanceIndicatorState(
    val visible: Boolean = false,
    val screenX: Float = 0f,
    val screenY: Float = 0f,
    val rotationDegrees: Float = 0f
)
