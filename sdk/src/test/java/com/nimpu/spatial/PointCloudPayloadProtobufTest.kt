package com.nimpu.spatial.sdk

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PointCloudPayloadProtobufTest {
    @Test
    fun protobufRoundTripPreservesPayloadFields() {
        val payload = samplePayload()

        val bytes = PointCloudPayloadProtobuf.encode(payload)
        val decoded = PointCloudPayloadProtobuf.decode(bytes)

        assertArrayEquals(payload.pinPose, decoded.pinPose, 0.0f)
        assertArrayEquals(payload.cameraIntrinsics, decoded.cameraIntrinsics, 0.0f)
        assertEquals(payload.timestamp, decoded.timestamp)
        assertEquals(payload.algorithmId, decoded.algorithmId)
        assertEquals(payload.descriptorSize, decoded.descriptorSize)
        assertEquals(payload.points3D.size, decoded.points3D.size)
        payload.points3D.indices.forEach { index ->
            assertArrayEquals(payload.points3D[index], decoded.points3D[index], 0.0f)
            assertArrayEquals(payload.descriptors[index], decoded.descriptors[index])
        }
        assertEquals(payload.geospatialMetadata, decoded.geospatialMetadata)
        assertEquals(payload.captureMetadata, decoded.captureMetadata)
    }

    @Test
    fun protobufIntegrityIsStableAndUsesProtobufCanonicalization() {
        val bytes = PointCloudPayloadProtobuf.encode(samplePayload())

        val first = PointCloudPayloadProtobuf.computeIntegrity(bytes)
        val second = PointCloudPayloadProtobuf.computeIntegrity(bytes)

        assertEquals(first.payloadHash, second.payloadHash)
        assertEquals("SHA-256", first.payloadHashAlgorithm)
        assertEquals("nimpu-pointcloud-protobuf-v1", first.payloadCanonicalization)
        assertTrue(first.payloadHash.matches(Regex("^sha256:[0-9a-f]{64}$")))
    }

    private fun samplePayload(): PointCloudPayload =
        PointCloudPayload(
            pinPose = floatArrayOf(1f, 2f, 3f, 0f, 0.5f, 0f, 1f),
            points3D = listOf(
                floatArrayOf(0.1f, 0.2f, 0.3f),
                floatArrayOf(-1.25f, 2.5f, 3.75f)
            ),
            descriptors = listOf(
                ByteArray(VisualProfile.DEFAULT.descSize) { index -> index.toByte() },
                ByteArray(VisualProfile.DEFAULT.descSize) { index -> (index + 7).toByte() }
            ),
            cameraIntrinsics = floatArrayOf(500f, 501f, 320f, 240f),
            timestamp = 1_778_000_000_000L,
            algorithmId = VisualProfile.DEFAULT.id,
            descriptorSize = VisualProfile.DEFAULT.descSize,
            geospatialMetadata = GeospatialPinMetadata(
                latitude = 17.455,
                longitude = 78.422,
                altitude = 501.25,
                floorLabel = "5",
                headingDegrees = 180.0,
                horizontalAccuracyMeters = 3.5,
                verticalAccuracyMeters = 2.0,
                headingAccuracyDegrees = 15.0,
                reliableLocalization = true
            ),
            captureMetadata = CaptureMetadata(
                coveredSectors = 5,
                pointCount = 2,
                schemaVersion = 2
            )
        )
}
