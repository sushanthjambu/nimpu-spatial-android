package com.nimpu.spatial.sdk

import java.nio.ByteBuffer

internal const val DEFAULT_FEATURE_ALGORITHM_ID = 1

internal enum class VisualProfile(val id: Int, val descSize: Int, val label: String) {
    LEGACY(0, 32, "Legacy profile"),
    DEFAULT(DEFAULT_FEATURE_ALGORITHM_ID, 61, "Default profile");

    companion object {
        fun fromId(id: Int): VisualProfile =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/**
 * Internal selection used by the demo/test build while the public SDK stays on
 * the production default feature pipeline.
 */
internal object TrackingConfig {
    @Volatile
    var algorithm: VisualProfile = VisualProfile.DEFAULT
}

/**
 * JNI bridge to the native CV runtime. The actual native library is still built
 * by the demo app module for now, but the shared API surface lives in the SDK.
 */
internal object NativeBridge {

    init {
        System.loadLibrary("spatial")
    }

    external fun processCreateFrame(
        algo: Int,
        yPlane: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int
    ): CreateFrameProcessingResult

    external fun processResolveFrame(
        algo: Int,
        descSize: Int,
        yPlane: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        savedDescriptors: ByteArray,
        savedPoints3D: FloatArray,
        fx: Float,
        fy: Float,
        cx: Float,
        cy: Float
    ): ResolveFrameProcessingResult

    external fun prepareCloudPayload(
        payloadEncoding: String,
        payloadFormat: String,
        downloadedBytes: ByteArray,
        expectedPayloadHash: String?,
        envelopeHash: String?,
        encryptionAlgorithm: String?,
        dataKeyBase64: String?,
        nonceBase64: String?,
        aadBase64: String?
    ): ByteArray

    external fun installEntitlementToken(token: String): NativeEntitlementInstallResult

    external fun hasEntitlementFeature(feature: String): Boolean

    external fun entitlementExpiresAtEpochSeconds(): Long

    external fun clearEntitlementForTests()
}

internal data class CreateFrameProcessingResult(
    val keypointX: FloatArray,
    val keypointY: FloatArray,
    val descriptors: ByteArray,
    val count: Int,
    val descSize: Int
)

internal data class ResolveFrameProcessingResult(
    val success: Boolean,
    val rvec: FloatArray,
    val tvec: FloatArray,
    val inlierCount: Int,
    val matchCount: Int
)

internal data class NativeEntitlementInstallResult(
    val success: Boolean,
    val message: String,
    val expiresAtEpochSeconds: Long
)
