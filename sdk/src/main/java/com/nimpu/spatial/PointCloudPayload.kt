package com.nimpu.spatial.sdk

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Full local-resolve payload plus the coarse geospatial metadata needed for the
 * MVP handoff from a world-scale pin to the precise resolved pin.
 */
data class PointCloudPayload(
    val pinPose: FloatArray,
    val points3D: List<FloatArray>,
    val descriptors: List<ByteArray>,
    val cameraIntrinsics: FloatArray,
    val timestamp: Long,
    val algorithmId: Int = DEFAULT_FEATURE_ALGORITHM_ID,
    val descriptorSize: Int = VisualProfile.fromId(DEFAULT_FEATURE_ALGORITHM_ID).descSize,
    val geospatialMetadata: GeospatialPinMetadata? = null,
    val captureMetadata: CaptureMetadata = CaptureMetadata()
) {
    data class SavedPinInfo(
        val pinId: String,
        val timestamp: Long,
        val pointCount: Int,
        val algorithmId: Int,
        val geospatialMetadata: GeospatialPinMetadata? = null,
        val displayName: String = pinId,
        val cloudPinId: String? = null,
        val uploadStatus: String = UploadStatus.LOCAL_ONLY.value,
        val uploadError: String? = null,
        val payloadFormat: String = PayloadFormat.JSON_V1,
        val payloadEncoding: String = PayloadEncoding.PLAINTEXT,
        val payloadHash: String? = null,
        val payloadHashAlgorithm: String? = null,
        val payloadCanonicalization: String? = null,
        val createSessionId: String? = null,
        val createTelemetryStatus: String? = null,
        val lastCreateTelemetryError: String? = null
    ) {
        val displayLabel: String
            get() = displayName.ifBlank { pinId }
    }

    companion object {
        private const val TAG = "PointCloudPayload"
        private const val LEGACY_JSON_EXTENSION = ".json"
        private const val METADATA_FILE_NAME = "metadata.json"
        private const val PAYLOAD_PROTO_FILE_NAME = "payload.pb"
        private const val PAYLOAD_PROTO_ENCRYPTED_FILE_NAME = "payload.pb.enc"
        private const val LOCAL_CACHE_FORMAT_PROTOBUF_V1 = "protobuf_payload_v1"
        private val fileLock = Any()

        internal fun algorithmFromId(id: Int): VisualProfile =
            VisualProfile.fromId(id)

        fun visualProfileLabelFromId(id: Int): String =
            algorithmFromId(id).label

        fun load(context: Context, pinId: String): PointCloudPayload? {
            val encryptedFile = encryptedPayloadFile(context, pinId)
            if (encryptedFile.exists()) {
                return try {
                    val metadata = JSONObject(metadataFile(context, pinId).readText())
                    val nonceBase64 = metadata.optString("localPayloadNonceBase64")
                        .takeIf { it.isNotBlank() }
                        ?: error("Encrypted local payload nonce is missing.")
                    val plaintext = LocalPayloadCrypto.decrypt(
                        ciphertext = encryptedFile.readBytes(),
                        nonce = Base64.decode(nonceBase64, Base64.NO_WRAP)
                    )
                    PointCloudPayloadProtobuf.decode(plaintext)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load encrypted protobuf payload: ${encryptedFile.absolutePath}", e)
                    null
                }
            }

            val protoFile = payloadProtoFile(context, pinId)
            if (protoFile.exists()) {
                return try {
                    PointCloudPayloadProtobuf.decode(protoFile.readBytes())
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load protobuf payload: ${protoFile.absolutePath}", e)
                    null
                }
            }

            val legacyFile = legacyJsonFile(context, pinId)
            if (!legacyFile.exists()) {
                Log.e(TAG, "Payload not found for $pinId")
                return null
            }

            return try {
                fromJson(JSONObject(legacyFile.readText()))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load legacy JSON payload", e)
                null
            }
        }

        fun fromJson(json: JSONObject): PointCloudPayload {
            val pinPoseArr = json.getJSONArray("pinPose")
            val pinPose = FloatArray(pinPoseArr.length()) { pinPoseArr.getDouble(it).toFloat() }

            val points3DArr = json.getJSONArray("points3D")
            val points3D = (0 until points3DArr.length()).map { i ->
                val pt = points3DArr.getJSONArray(i)
                FloatArray(3) { pt.getDouble(it).toFloat() }
            }

            val descArr = json.getJSONArray("descriptors")
            val descriptors = (0 until descArr.length()).map { i ->
                Base64.decode(descArr.getString(i), Base64.NO_WRAP)
            }

            val intrArr = json.getJSONArray("cameraIntrinsics")
            val cameraIntrinsics = FloatArray(intrArr.length()) { intrArr.getDouble(it).toFloat() }

            val timestamp = json.getLong("timestamp")
            val algorithmId = json.optInt("algorithmId", DEFAULT_FEATURE_ALGORITHM_ID)
            val descriptorSize = json.optInt("descriptorSize", VisualProfile.fromId(algorithmId).descSize)
            val geospatialMetadata = json.optJSONObject("geospatialMetadata")?.let(::geospatialFromJson)
            val captureMetadata = json.optJSONObject("captureMetadata")?.let(::captureMetadataFromJson) ?: CaptureMetadata()

            return PointCloudPayload(
                pinPose = pinPose,
                points3D = points3D,
                descriptors = descriptors,
                cameraIntrinsics = cameraIntrinsics,
                timestamp = timestamp,
                algorithmId = algorithmId,
                descriptorSize = descriptorSize,
                geospatialMetadata = geospatialMetadata,
                captureMetadata = captureMetadata
            )
        }

        fun listSavedPins(context: Context): List<SavedPinInfo> {
            val dir = pinsDir(context)
            val entries = dir.listFiles() ?: return emptyList()
            val protobufEntries = entries.filter { it.isDirectory }
            val protobufPins = readSavedPinInfo(protobufEntries) {
                savedPinInfoFromMetadataFile(File(it, METADATA_FILE_NAME), it.name)
            }
            val protobufIds = protobufPins.mapTo(mutableSetOf()) { it.pinId }
            val legacyEntries = entries
                .filter { it.isFile && it.name.endsWith(LEGACY_JSON_EXTENSION) }
                .filterNot { protobufIds.contains(it.nameWithoutExtension) }
            val legacyPins = readSavedPinInfo(legacyEntries) { savedPinInfoFromLegacyJsonFile(it) }
            return (protobufPins + legacyPins).sortedBy { it.timestamp }
        }

        fun savedPinInfo(context: Context, pinId: String): SavedPinInfo? {
            val metadata = metadataFile(context, pinId)
            return savedPinInfoFromMetadataFile(metadata, pinId)
                ?: savedPinInfoFromLegacyJsonFile(legacyJsonFile(context, pinId))
        }

        fun delete(context: Context, pinId: String): Boolean {
            val dir = pinDir(context, pinId)
            val legacyFile = legacyJsonFile(context, pinId)
            val deletedDir = !dir.exists() || dir.deleteRecursively()
            val deletedLegacy = !legacyFile.exists() || legacyFile.delete()
            return deletedDir && deletedLegacy
        }

        fun updateUploadMetadata(
            context: Context,
            pinId: String,
            cloudPinId: String? = null,
            uploadStatus: UploadStatus,
            displayName: String? = null,
            uploadError: String? = null
        ) {
            val file = writableMetadataFile(context, pinId) ?: return

            try {
                synchronized(fileLock) {
                    val json = JSONObject(file.readText())
                    displayName?.takeIf { it.isNotBlank() }?.let { json.put("displayName", it) }
                    cloudPinId?.takeIf { it.isNotBlank() }?.let { json.put("cloudPinId", it) }
                    json.put("uploadStatus", uploadStatus.value)
                    if (uploadError.isNullOrBlank()) {
                        json.remove("uploadError")
                    } else {
                        json.put("uploadError", uploadError)
                    }
                    json.put("updatedAt", System.currentTimeMillis())
                    file.writeText(json.toString(2))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update upload metadata for $pinId", e)
            }
        }

        fun updateCreateTelemetryMetadata(
            context: Context,
            pinId: String,
            createSessionId: String? = null,
            createTelemetryStatus: String? = null,
            lastCreateTelemetryError: String? = null
        ) {
            val file = writableMetadataFile(context, pinId) ?: return

            try {
                synchronized(fileLock) {
                    val json = JSONObject(file.readText())
                    createSessionId?.takeIf { it.isNotBlank() }?.let { json.put("createSessionId", it) }
                    createTelemetryStatus?.takeIf { it.isNotBlank() }?.let { json.put("createTelemetryStatus", it) }
                    if (lastCreateTelemetryError.isNullOrBlank()) {
                        json.remove("lastCreateTelemetryError")
                    } else {
                        json.put("lastCreateTelemetryError", lastCreateTelemetryError)
                    }
                    json.put("updatedAt", System.currentTimeMillis())
                    file.writeText(json.toString(2))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update create telemetry metadata for $pinId", e)
            }
        }

        fun defaultPinDisplayName(context: Context): String {
            val nextNumber = listSavedPins(context).size + 1
            val timestamp = SimpleDateFormat("dd-MM-yyyy-HH-mm-ss", Locale.US).format(Date())
            return "Pin %02d %s".format(Locale.US, nextNumber, timestamp)
        }

        fun uploadDisplayNameOrDefault(context: Context, pin: SavedPinInfo): String {
            return pin.displayName
                .takeIf { it.isNotBlank() && it != pin.pinId }
                ?: defaultPinDisplayName(context)
        }

        internal fun computeIntegrity(
            payload: PointCloudPayload,
            payloadFormat: String
        ): PayloadIntegrityMetadata =
            when (payloadFormat) {
                PayloadFormat.PROTOBUF_V1 -> PointCloudPayloadProtobuf.computeIntegrity(payload)
                PayloadFormat.JSON_V1 -> PayloadIntegrity.compute(payload)
                else -> PayloadIntegrity.compute(payload)
            }

        private fun pinsDir(context: Context): File = File(context.filesDir, "pins")

        private fun pinDir(context: Context, pinId: String): File = File(pinsDir(context), pinId)

        private fun payloadProtoFile(context: Context, pinId: String): File =
            File(pinDir(context, pinId), PAYLOAD_PROTO_FILE_NAME)

        private fun encryptedPayloadFile(context: Context, pinId: String): File =
            File(pinDir(context, pinId), PAYLOAD_PROTO_ENCRYPTED_FILE_NAME)

        private fun metadataFile(context: Context, pinId: String): File =
            File(pinDir(context, pinId), METADATA_FILE_NAME)

        private fun legacyJsonFile(context: Context, pinId: String): File =
            File(pinsDir(context), "$pinId$LEGACY_JSON_EXTENSION")

        private fun writableMetadataFile(context: Context, pinId: String): File? {
            val metadata = metadataFile(context, pinId)
            if (metadata.exists()) return metadata
            val legacy = legacyJsonFile(context, pinId)
            return legacy.takeIf { it.exists() }
        }

        private fun savedPinInfoFromMetadataFile(file: File, pinId: String): SavedPinInfo? {
            if (!file.exists()) return null
            return try {
                savedPinInfoFromJson(pinId, JSONObject(file.readText()), file.lastModified())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read pin metadata: ${file.absolutePath}", e)
                null
            }
        }

        private fun readSavedPinInfo(
            entries: List<File>,
            reader: (File) -> SavedPinInfo?
        ): List<SavedPinInfo> {
            if (entries.size < 16) return entries.mapNotNull(reader)

            val threadCount = entries.size
                .coerceAtMost(Runtime.getRuntime().availableProcessors().coerceAtLeast(2))
            val executor = Executors.newFixedThreadPool(threadCount)
            return try {
                val tasks = entries.map { entry ->
                    Callable { runCatching { reader(entry) }.getOrNull() }
                }
                executor.invokeAll(tasks).mapNotNull { it.get() }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                entries.mapNotNull(reader)
            } finally {
                executor.shutdown()
            }
        }

        private fun savedPinInfoFromLegacyJsonFile(file: File): SavedPinInfo? {
            if (!file.exists()) return null
            return try {
                val json = JSONObject(file.readText())
                savedPinInfoFromJson(file.nameWithoutExtension, json, file.lastModified())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read pin metadata: ${file.absolutePath}", e)
                null
            }
        }

        private fun savedPinInfoFromJson(
            pinId: String,
            json: JSONObject,
            fallbackTimestamp: Long
        ): SavedPinInfo =
            SavedPinInfo(
                pinId = pinId,
                timestamp = json.optLong("timestamp", fallbackTimestamp),
                pointCount = json.optInt("pointCount", 0),
                algorithmId = json.optInt("algorithmId", DEFAULT_FEATURE_ALGORITHM_ID),
                geospatialMetadata = json.optJSONObject("geospatialMetadata")?.let(::geospatialFromJson),
                displayName = json.optString("displayName").takeIf { it.isNotBlank() } ?: pinId,
                cloudPinId = json.optString("cloudPinId").takeIf { it.isNotBlank() },
                uploadStatus = json.optString(
                    "uploadStatus",
                    UploadStatus.LOCAL_ONLY.value
                ),
                uploadError = json.optString("uploadError").takeIf { it.isNotBlank() },
                payloadFormat = json.optString("payloadFormat", PayloadFormat.JSON_V1)
                    .takeIf { it.isNotBlank() }
                    ?: PayloadFormat.JSON_V1,
                payloadEncoding = json.optString("payloadEncoding", PayloadEncoding.PLAINTEXT)
                    .takeIf { it.isNotBlank() }
                    ?: PayloadEncoding.PLAINTEXT,
                payloadHash = json.optString("payloadHash").takeIf { it.isNotBlank() },
                payloadHashAlgorithm = json.optString("payloadHashAlgorithm").takeIf { it.isNotBlank() },
                payloadCanonicalization = json.optString("payloadCanonicalization").takeIf { it.isNotBlank() },
                createSessionId = json.optString("createSessionId").takeIf { it.isNotBlank() },
                createTelemetryStatus = json.optString("createTelemetryStatus").takeIf { it.isNotBlank() },
                lastCreateTelemetryError = json.optString("lastCreateTelemetryError").takeIf { it.isNotBlank() }
            )

        private fun geospatialFromJson(json: JSONObject): GeospatialPinMetadata =
            GeospatialPinMetadata(
                latitude = json.getDouble("latitude"),
                longitude = json.getDouble("longitude"),
                altitude = if (json.has("altitude")) json.optDouble("altitude") else null,
                floorLabel = json.optString("floorLabel").takeIf { it.isNotBlank() },
                headingDegrees = if (json.has("headingDegrees")) json.optDouble("headingDegrees") else null,
                horizontalAccuracyMeters = if (json.has("horizontalAccuracyMeters")) json.optDouble("horizontalAccuracyMeters") else null,
                verticalAccuracyMeters = if (json.has("verticalAccuracyMeters")) json.optDouble("verticalAccuracyMeters") else null,
                headingAccuracyDegrees = if (json.has("headingAccuracyDegrees")) json.optDouble("headingAccuracyDegrees") else null,
                reliableLocalization = json.optBoolean("reliableLocalization", false)
            )

        private fun captureMetadataFromJson(json: JSONObject): CaptureMetadata =
            CaptureMetadata(
                coveredSectors = json.optInt("coveredSectors", 0),
                pointCount = json.optInt("pointCount", 0),
                schemaVersion = json.optInt("schemaVersion", 1)
            )
    }

    fun save(
        context: Context,
        pinId: String,
        displayName: String? = null,
        cloudPinId: String? = null,
        uploadStatus: UploadStatus = UploadStatus.LOCAL_ONLY,
        uploadError: String? = null,
        createSessionId: String? = null,
        createTelemetryStatus: String? = null,
        lastCreateTelemetryError: String? = null,
        payloadFormat: String = PayloadFormat.PROTOBUF_V1,
        payloadEncoding: String = PayloadEncoding.PLAINTEXT,
        payloadIntegrity: PayloadIntegrityMetadata? = null
    ) {
        val dir = pinDir(context, pinId)
        dir.mkdirs()
        val payloadFile = File(dir, PAYLOAD_PROTO_ENCRYPTED_FILE_NAME)
        val plaintextPayloadFile = File(dir, PAYLOAD_PROTO_FILE_NAME)
        val metadataFile = File(dir, METADATA_FILE_NAME)
        val resolvedPayloadFormat = PayloadFormat.PROTOBUF_V1
        val payloadBytes = PointCloudPayloadProtobuf.encode(this)
        val encryptedPayload = LocalPayloadCrypto.encrypt(payloadBytes)
        val integrity = payloadIntegrity
            ?.takeIf { it.payloadCanonicalization == PointCloudPayloadProtobuf.CANONICALIZATION }
            ?: PointCloudPayloadProtobuf.computeIntegrity(payloadBytes)
        val json = metadataJson().apply {
            put("localCacheFormat", LOCAL_CACHE_FORMAT_PROTOBUF_V1)
            put("localPayloadEncoding", LocalPayloadCrypto.ENCODING)
            put("localPayloadNonceBase64", Base64.encodeToString(encryptedPayload.nonce, Base64.NO_WRAP))
            put("payloadFormat", resolvedPayloadFormat)
            put("payloadEncoding", payloadEncoding)
            put("payloadHash", integrity.payloadHash)
            put("payloadHashAlgorithm", integrity.payloadHashAlgorithm)
            put("payloadCanonicalization", integrity.payloadCanonicalization)
            displayName?.takeIf { it.isNotBlank() }?.let { put("displayName", it) }
            cloudPinId?.takeIf { it.isNotBlank() }?.let { put("cloudPinId", it) }
            put("uploadStatus", uploadStatus.value)
            uploadError?.takeIf { it.isNotBlank() }?.let { put("uploadError", it) }
            createSessionId?.takeIf { it.isNotBlank() }?.let { put("createSessionId", it) }
            createTelemetryStatus?.takeIf { it.isNotBlank() }?.let { put("createTelemetryStatus", it) }
            lastCreateTelemetryError?.takeIf { it.isNotBlank() }?.let { put("lastCreateTelemetryError", it) }
            put("updatedAt", System.currentTimeMillis())
        }
        synchronized(fileLock) {
            payloadFile.writeBytes(encryptedPayload.ciphertext)
            if (plaintextPayloadFile.exists()) plaintextPayloadFile.delete()
            metadataFile.writeText(json.toString(2))
        }

        Log.d(
            TAG,
            "Saved payload: ${payloadFile.absolutePath} (${points3D.size} points, ${payloadFile.length()} bytes, hash=${integrity.shortHash})"
        )
    }

    private fun metadataJson(): JSONObject = JSONObject().apply {
        put("timestamp", timestamp)
        put("pointCount", points3D.size)
        put("algorithmId", algorithmId)
        put("descriptorSize", descriptorSize)
        geospatialMetadata?.let { put("geospatialMetadata", it.toJson()) }
        put("captureMetadata", captureMetadata.toJson())
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("pinPose", JSONArray().apply {
            pinPose.forEach { put(it.toDouble()) }
        })
        put("points3D", JSONArray().apply {
            points3D.forEach { pt ->
                put(JSONArray().apply {
                    pt.forEach { put(it.toDouble()) }
                })
            }
        })
        put("descriptors", JSONArray().apply {
            descriptors.forEach { desc ->
                put(Base64.encodeToString(desc, Base64.NO_WRAP))
            }
        })
        put("cameraIntrinsics", JSONArray().apply {
            cameraIntrinsics.forEach { put(it.toDouble()) }
        })
        put("timestamp", timestamp)
        put("pointCount", points3D.size)
        put("algorithmId", algorithmId)
        put("descriptorSize", descriptorSize)
        geospatialMetadata?.let { put("geospatialMetadata", it.toJson()) }
        put("captureMetadata", captureMetadata.toJson())
    }
}

object PayloadIntegrity {
    const val HASH_ALGORITHM = "SHA-256"
    const val CANONICALIZATION = "nimpu-pointcloud-json-v1"
    private const val HASH_PREFIX = "sha256:"

    fun compute(payload: PointCloudPayload): PayloadIntegrityMetadata {
        val canonicalJson = payload.toJson().toString()
        val digest = MessageDigest.getInstance(HASH_ALGORITHM)
            .digest(canonicalJson.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
        val hash = HASH_PREFIX + digest
        return PayloadIntegrityMetadata(
            payloadHash = hash,
            payloadHashAlgorithm = HASH_ALGORITHM,
            payloadCanonicalization = CANONICALIZATION
        )
    }

    fun matches(payload: PointCloudPayload, expectedHash: String?): Boolean {
        if (expectedHash.isNullOrBlank()) return true
        return compute(payload).payloadHash == expectedHash
    }

    fun shortHash(hash: String?): String = hash
        ?.removePrefix(HASH_PREFIX)
        ?.take(12)
        ?.takeIf { it.isNotBlank() }
        ?: "none"
}

data class PayloadIntegrityMetadata(
    val payloadHash: String,
    val payloadHashAlgorithm: String,
    val payloadCanonicalization: String
) {
    val shortHash: String
        get() = PayloadIntegrity.shortHash(payloadHash)
}

object PayloadFormat {
    const val JSON_V1 = "json_v1"
    const val PROTOBUF_V1 = "protobuf_v1"
}

object PayloadEncoding {
    const val PLAINTEXT = "plaintext"
    const val ENCRYPTED_V1 = "encrypted_v1"
}

enum class UploadStatus(val value: String) {
    LOCAL_ONLY("LOCAL_ONLY"),
    UPLOADING("UPLOADING"),
    UPLOADED("UPLOADED"),
    FAILED("FAILED")
}

data class GeospatialPinMetadata(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double? = null,
    val floorLabel: String? = null,
    val headingDegrees: Double? = null,
    val horizontalAccuracyMeters: Double? = null,
    val verticalAccuracyMeters: Double? = null,
    val headingAccuracyDegrees: Double? = null,
    val reliableLocalization: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("latitude", latitude)
        put("longitude", longitude)
        altitude?.let { put("altitude", it) }
        floorLabel?.let { put("floorLabel", it) }
        headingDegrees?.let { put("headingDegrees", it) }
        horizontalAccuracyMeters?.let { put("horizontalAccuracyMeters", it) }
        verticalAccuracyMeters?.let { put("verticalAccuracyMeters", it) }
        headingAccuracyDegrees?.let { put("headingAccuracyDegrees", it) }
        put("reliableLocalization", reliableLocalization)
    }
}

data class CaptureMetadata(
    val coveredSectors: Int = 0,
    val pointCount: Int = 0,
    val schemaVersion: Int = 1
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("coveredSectors", coveredSectors)
        put("pointCount", pointCount)
        put("schemaVersion", schemaVersion)
    }
}
