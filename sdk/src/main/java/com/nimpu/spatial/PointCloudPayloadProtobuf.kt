package com.nimpu.spatial.sdk

import com.google.protobuf.ByteString
import com.nimpu.spatial.proto.CaptureMetadata
import com.nimpu.spatial.proto.GeospatialMetadata
import com.nimpu.spatial.proto.PointCloudPayloadV1
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

internal object PointCloudPayloadProtobuf {
    const val CANONICALIZATION = "nimpu-pointcloud-protobuf-v1"

    fun encode(payload: PointCloudPayload): ByteArray =
        payload.toProto().toByteArray()

    fun decode(bytes: ByteArray): PointCloudPayload =
        PointCloudPayloadV1.parseFrom(bytes).toPayload()

    fun computeIntegrity(bytes: ByteArray): PayloadIntegrityMetadata {
        val digest = MessageDigest.getInstance(PayloadIntegrity.HASH_ALGORITHM)
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
        return PayloadIntegrityMetadata(
            payloadHash = "sha256:$digest",
            payloadHashAlgorithm = PayloadIntegrity.HASH_ALGORITHM,
            payloadCanonicalization = CANONICALIZATION
        )
    }

    fun computeIntegrity(payload: PointCloudPayload): PayloadIntegrityMetadata =
        computeIntegrity(encode(payload))

    private fun PointCloudPayload.toProto(): PointCloudPayloadV1 {
        val builder = PointCloudPayloadV1.newBuilder()
            .setSchemaVersion(captureMetadata.schemaVersion.coerceAtLeast(1))
            .setAlgorithmId(algorithmId)
            .setDescriptorSize(descriptorSize)
            .setTimestampMs(timestamp)
            .addAllPinPose(pinPose.asIterable())
            .addAllCameraIntrinsics(cameraIntrinsics.asIterable())
            .setPointCount(points3D.size)
            .setPoints3DF32Le(ByteString.copyFrom(encodePoints(points3D)))
            .setDescriptors(ByteString.copyFrom(flattenDescriptors(descriptors, descriptorSize)))
            .setCapture(captureMetadata.toProto(points3D.size))

        geospatialMetadata?.let { builder.geospatial = it.toProto() }
        return builder.build()
    }

    private fun PointCloudPayloadV1.toPayload(): PointCloudPayload {
        val pointCount = pointCount
        val descriptorSize = descriptorSize
        val pointBytes = points3DF32Le.toByteArray()
        val descriptorBytes = descriptors.toByteArray()

        require(pointCount > 0) { "Protobuf payload must contain at least one point." }
        require(descriptorBytes.size == pointCount * descriptorSize) {
            "Descriptor byte count ${descriptorBytes.size} does not match pointCount=$pointCount descriptorSize=$descriptorSize."
        }

        return PointCloudPayload(
            pinPose = pinPoseList.toFloatArray(),
            points3D = decodePoints(pointBytes, pointCount),
            descriptors = descriptorBytes.toListOfDescriptors(descriptorSize),
            cameraIntrinsics = cameraIntrinsicsList.toFloatArray(),
            timestamp = timestampMs,
            algorithmId = algorithmId,
            descriptorSize = descriptorSize,
            geospatialMetadata = if (hasGeospatial()) geospatial.toPayloadMetadata() else null,
            captureMetadata = if (hasCapture()) capture.toPayloadMetadata() else com.nimpu.spatial.sdk.CaptureMetadata(
                coveredSectors = 0,
                pointCount = pointCount,
                schemaVersion = schemaVersion
            )
        )
    }

    private fun encodePoints(points: List<FloatArray>): ByteArray {
        val bytes = ByteArray(points.size * 3 * Float.SIZE_BYTES)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        points.forEach { point ->
            require(point.size == 3) { "Each point must contain exactly 3 floats." }
            buffer.putFloat(point[0])
            buffer.putFloat(point[1])
            buffer.putFloat(point[2])
        }
        return bytes
    }

    private fun decodePoints(bytes: ByteArray, pointCount: Int): List<FloatArray> {
        require(bytes.size == pointCount * 3 * Float.SIZE_BYTES) {
            "Point byte count ${bytes.size} does not match pointCount=$pointCount."
        }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return List(pointCount) {
            floatArrayOf(buffer.float, buffer.float, buffer.float)
        }
    }

    private fun flattenDescriptors(descriptors: List<ByteArray>, descriptorSize: Int): ByteArray {
        val bytes = ByteArray(descriptors.size * descriptorSize)
        descriptors.forEachIndexed { index, descriptor ->
            require(descriptor.size == descriptorSize) {
                "Descriptor $index has size ${descriptor.size}; expected $descriptorSize."
            }
            descriptor.copyInto(bytes, destinationOffset = index * descriptorSize)
        }
        return bytes
    }

    private fun ByteArray.toListOfDescriptors(descriptorSize: Int): List<ByteArray> =
        (indices step descriptorSize).map { start ->
            copyOfRange(start, start + descriptorSize)
        }

    private fun List<Float>.toFloatArray(): FloatArray =
        FloatArray(size) { index -> this[index] }

    private fun GeospatialPinMetadata.toProto(): GeospatialMetadata {
        val builder = GeospatialMetadata.newBuilder()
            .setLatitude(latitude)
            .setLongitude(longitude)
            .setReliableLocalization(reliableLocalization)

        altitude?.let { builder.altitude = it }
        floorLabel?.takeIf { it.isNotBlank() }?.let { builder.floorLabel = it }
        headingDegrees?.let { builder.headingDegrees = it }
        horizontalAccuracyMeters?.let { builder.horizontalAccuracyMeters = it }
        verticalAccuracyMeters?.let { builder.verticalAccuracyMeters = it }
        headingAccuracyDegrees?.let { builder.headingAccuracyDegrees = it }
        return builder.build()
    }

    private fun GeospatialMetadata.toPayloadMetadata(): GeospatialPinMetadata =
        GeospatialPinMetadata(
            latitude = latitude,
            longitude = longitude,
            altitude = if (hasAltitude()) altitude else null,
            floorLabel = if (hasFloorLabel()) floorLabel else null,
            headingDegrees = if (hasHeadingDegrees()) headingDegrees else null,
            horizontalAccuracyMeters = if (hasHorizontalAccuracyMeters()) horizontalAccuracyMeters else null,
            verticalAccuracyMeters = if (hasVerticalAccuracyMeters()) verticalAccuracyMeters else null,
            headingAccuracyDegrees = if (hasHeadingAccuracyDegrees()) headingAccuracyDegrees else null,
            reliableLocalization = reliableLocalization
        )

    private fun com.nimpu.spatial.sdk.CaptureMetadata.toProto(pointCountFallback: Int): CaptureMetadata =
        CaptureMetadata.newBuilder()
            .setCoveredSectors(coveredSectors.coerceAtLeast(0))
            .setPointCount(pointCount.takeIf { it > 0 } ?: pointCountFallback)
            .setSchemaVersion(schemaVersion.coerceAtLeast(1))
            .build()

    private fun CaptureMetadata.toPayloadMetadata(): com.nimpu.spatial.sdk.CaptureMetadata =
        com.nimpu.spatial.sdk.CaptureMetadata(
            coveredSectors = coveredSectors,
            pointCount = pointCount,
            schemaVersion = schemaVersion
        )
}
