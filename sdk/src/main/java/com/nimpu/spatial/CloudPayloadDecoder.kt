package com.nimpu.spatial.sdk

import org.json.JSONObject
import java.io.IOException

internal data class CloudPayloadPackage(
    val session: CloudResolveSession,
    val downloadedBytes: ByteArray
)

internal object CloudPayloadDecoder {
    fun decode(payloadPackage: CloudPayloadPackage): ResolveSessionPayload {
        val plaintextBytes = prepareCloudPayload(payloadPackage)
        val payload = decodePayload(plaintextBytes, payloadPackage.session.payloadFormat)
        return ResolveSessionPayload(
            session = payloadPackage.session,
            payload = payload,
            payloadBytes = plaintextBytes
        )
    }

    private fun decodePayload(bytes: ByteArray, payloadFormat: String): PointCloudPayload =
        when (payloadFormat) {
            PayloadFormat.JSON_V1 -> PointCloudPayload.fromJson(JSONObject(bytes.toString(Charsets.UTF_8)))
            PayloadFormat.PROTOBUF_V1 -> PointCloudPayloadProtobuf.decode(bytes)
            else -> throw IOException("Unsupported payload format: $payloadFormat")
        }

    private fun prepareCloudPayload(payloadPackage: CloudPayloadPackage): ByteArray =
        NativeBridge.prepareCloudPayload(
            payloadEncoding = payloadPackage.session.payloadEncoding,
            payloadFormat = payloadPackage.session.payloadFormat,
            downloadedBytes = payloadPackage.downloadedBytes,
            expectedPayloadHash = payloadPackage.session.payloadHash,
            envelopeHash = payloadPackage.session.payloadEncryption?.envelopeHash,
            encryptionAlgorithm = payloadPackage.session.payloadEncryption?.algorithm,
            dataKeyBase64 = payloadPackage.session.payloadEncryption?.dataKeyBase64,
            nonceBase64 = payloadPackage.session.payloadEncryption?.nonceBase64,
            aadBase64 = payloadPackage.session.payloadEncryption?.aadBase64
        )
}
