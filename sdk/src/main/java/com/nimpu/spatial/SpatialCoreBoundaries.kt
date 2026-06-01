package com.nimpu.spatial.sdk

/**
 * Internal contracts behind the public SDK/session API.
 *
 * These interfaces keep host-app integration stable while implementation
 * details stay SDK-owned.
 */
internal interface PayloadSecurityCore {
    fun decode(payloadPackage: CloudPayloadPackage): ResolveSessionPayload
}

internal object DefaultPayloadSecurityCore : PayloadSecurityCore {
    override fun decode(payloadPackage: CloudPayloadPackage): ResolveSessionPayload =
        CloudPayloadDecoder.decode(payloadPackage)
}

internal interface CreateCaptureCore {
    fun newKeyframeCapture(): KeyframeCapture
}

internal object DefaultCreateCaptureCore : CreateCaptureCore {
    override fun newKeyframeCapture(): KeyframeCapture = KeyframeCapture()
}

internal interface ResolveMatchingCore {
    fun newSearchController(
        payload: PointCloudPayload,
        onResult: (ResolveEngineResult) -> Unit
    ): ResolveSearchController
}

internal object DefaultResolveMatchingCore : ResolveMatchingCore {
    override fun newSearchController(
        payload: PointCloudPayload,
        onResult: (ResolveEngineResult) -> Unit
    ): ResolveSearchController = ResolveSearchController(payload, onResult)
}

internal interface ResolveAttestationCore {
    fun onResolveResult(
        target: ResolveTarget,
        result: ResolveEngineResult
    )
}

internal object NoOpResolveAttestationCore : ResolveAttestationCore {
    override fun onResolveResult(
        target: ResolveTarget,
        result: ResolveEngineResult
    ) = Unit
}
