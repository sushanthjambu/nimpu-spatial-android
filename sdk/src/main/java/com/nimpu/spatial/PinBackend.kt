package com.nimpu.spatial.sdk

import org.json.JSONArray
import org.json.JSONObject
import android.util.Base64
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Thin real-backend contract for the MVP. This keeps network behavior out of the
 * app shell and gives the future SDK a stable integration seam.
 */
internal interface PinApiClient {
    fun createEntitlement(request: CreateEntitlementRequest): SdkEntitlement?
    fun uploadPin(request: PinUploadRequest): PinUploadResult?
    fun fetchPin(pinId: String): FetchedPinPayload?
    fun startCreateSession(request: StartCreateSessionRequest): CreateSessionInfo?
    fun reportCreateSessionEvent(
        sessionId: String,
        request: CreateSessionEventReport
    ): CreateSessionEventReportResult?
    fun startResolveSession(request: StartResolveSessionRequest): CloudResolveSession?
    fun downloadResolveSessionPayload(session: CloudResolveSession): ResolveSessionPayload
    fun reportResolveSessionEvent(
        sessionId: String,
        request: ResolveSessionEventReport
    ): ResolveSessionEventReportResult?
    fun listPins(externalRef: String? = null): List<PointCloudPayload.SavedPinInfo>
    fun deletePin(pinId: String): Boolean
}

internal class HttpPinApiClient(
    private val config: NimpuSpatialConfig,
    private val payloadSecurityCore: PayloadSecurityCore = DefaultPayloadSecurityCore
) : PinApiClient {
    private companion object {
        const val CONNECT_TIMEOUT_MS = 30_000
        const val READ_TIMEOUT_MS = 30_000
    }

    override fun createEntitlement(request: CreateEntitlementRequest): SdkEntitlement? {
        val body = JSONObject().apply {
            request.sdkVersion?.takeIf { it.isNotBlank() }?.let { put("sdkVersion", it) }
            put("platform", request.platform)
            request.appPackageName?.takeIf { it.isNotBlank() }?.let { put("appPackageName", it) }
            request.appVersion?.takeIf { it.isNotBlank() }?.let { put("appVersion", it) }
            request.deviceInstallId?.takeIf { it.isNotBlank() }?.let { put("deviceInstallId", it) }
        }
        val json = requestJson("POST", "/v1/sdk/entitlements", body) ?: return null
        val featuresJson = json.optJSONArray("features") ?: JSONArray()
        return SdkEntitlement(
            entitlementToken = json.optString("entitlementToken"),
            expiresAt = stringOrNull(json, "expiresAt").orEmpty(),
            refreshAfter = stringOrNull(json, "refreshAfter").orEmpty(),
            features = (0 until featuresJson.length()).mapNotNull { index ->
                featuresJson.optString(index).takeIf { it.isNotBlank() }
            }
        )
    }

    override fun uploadPin(request: PinUploadRequest): PinUploadResult? {
        val body = JSONObject().apply {
            request.externalRef?.takeIf { it.isNotBlank() }?.let { put("externalRef", it) }
            request.displayName?.takeIf { it.isNotBlank() }?.let { put("displayName", it) }
            put("payloadFormat", request.payloadFormat)
            put("payloadEncoding", request.payloadEncoding)
            put("payloadHash", request.payloadHash)
            put("payloadHashAlgorithm", request.payloadHashAlgorithm)
            put("payloadCanonicalization", request.payloadCanonicalization)
            if (request.payloadFormat == PayloadFormat.PROTOBUF_V1) {
                put("payloadBytesBase64", Base64.encodeToString(request.payloadBytes, Base64.NO_WRAP))
                put("payloadMetadata", request.payload.toMetadataJson())
            } else {
                put("payload", request.payload.toJson())
            }
        }
        val json = requestJson("POST", "/v1/pins", body) ?: return null
        return PinUploadResult(
            pinId = json.optString("pinId"),
            versionId = json.optString("versionId"),
            status = json.optString("status"),
            payloadFormat = stringOrNull(json, "payloadFormat") ?: PayloadFormat.JSON_V1,
            payloadEncoding = stringOrNull(json, "payloadEncoding") ?: PayloadEncoding.PLAINTEXT,
            payloadHash = stringOrNull(json, "payloadHash"),
            payloadHashAlgorithm = stringOrNull(json, "payloadHashAlgorithm"),
            payloadCanonicalization = stringOrNull(json, "payloadCanonicalization")
        )
    }

    override fun fetchPin(pinId: String): FetchedPinPayload? {
        val json = requestJson("GET", "/v1/pins/$pinId")
        val payloadJson = json?.optJSONObject("payload") ?: return null
        return FetchedPinPayload(
            pinId = json.optString("pinId", pinId),
            versionId = stringOrNull(json, "versionId"),
            payload = PointCloudPayload.fromJson(payloadJson),
            payloadFormat = stringOrNull(json, "payloadFormat") ?: PayloadFormat.JSON_V1,
            payloadEncoding = stringOrNull(json, "payloadEncoding") ?: PayloadEncoding.PLAINTEXT,
            payloadHash = stringOrNull(json, "payloadHash"),
            payloadHashAlgorithm = stringOrNull(json, "payloadHashAlgorithm"),
            payloadCanonicalization = stringOrNull(json, "payloadCanonicalization")
        )
    }

    override fun startCreateSession(request: StartCreateSessionRequest): CreateSessionInfo? {
        val body = JSONObject().apply {
            put("localPinId", request.localPinId)
            request.displayName?.takeIf { it.isNotBlank() }?.let { put("displayName", it) }
            request.sdkVersion?.takeIf { it.isNotBlank() }?.let { put("sdkVersion", it) }
            request.appPackageName?.takeIf { it.isNotBlank() }?.let { put("appPackageName", it) }
            request.deviceRef?.takeIf { it.isNotBlank() }?.let { put("deviceRef", it) }
        }
        val json = requestJson("POST", "/v1/create-sessions", body) ?: return null
        return CreateSessionInfo(
            sessionId = json.optString("sessionId"),
            status = json.optString("status")
        )
    }

    override fun reportCreateSessionEvent(
        sessionId: String,
        request: CreateSessionEventReport
    ): CreateSessionEventReportResult? {
        val body = JSONObject().apply {
            put("eventType", request.eventType)
            request.localPinId?.takeIf { it.isNotBlank() }?.let { put("localPinId", it) }
            request.cloudPinId?.takeIf { it.isNotBlank() }?.let { put("cloudPinId", it) }
            request.displayName?.takeIf { it.isNotBlank() }?.let { put("displayName", it) }
            request.payloadHash?.takeIf { it.isNotBlank() }?.let { put("payloadHash", it) }
            request.payloadHashAlgorithm?.takeIf { it.isNotBlank() }?.let { put("payloadHashAlgorithm", it) }
            request.payloadCanonicalization?.takeIf { it.isNotBlank() }?.let { put("payloadCanonicalization", it) }
            request.payloadFormat?.takeIf { it.isNotBlank() }?.let { put("payloadFormat", it) }
            request.payloadEncoding?.takeIf { it.isNotBlank() }?.let { put("payloadEncoding", it) }
            request.pointCount?.let { put("pointCount", it) }
            request.descriptorCount?.let { put("descriptorCount", it) }
            request.descriptorSize?.let { put("descriptorSize", it) }
            request.algorithmId?.let { put("algorithmId", it) }
            request.coveredSectors?.let { put("coveredSectors", it) }
            request.acceptedSectorMask?.let { put("acceptedSectorMask", it) }
            request.attemptedSectorMask?.let { put("attemptedSectorMask", it) }
            request.uploadAttemptCount?.let { put("uploadAttemptCount", it) }
            request.failureReason?.takeIf { it.isNotBlank() }?.let { put("failureReason", it) }
            request.sdkVersion?.takeIf { it.isNotBlank() }?.let { put("sdkVersion", it) }
            request.appPackageName?.takeIf { it.isNotBlank() }?.let { put("appPackageName", it) }
            request.deviceRef?.takeIf { it.isNotBlank() }?.let { put("deviceRef", it) }
        }
        val json = requestJson("POST", "/v1/create-sessions/$sessionId/events", body) ?: return null
        return CreateSessionEventReportResult(
            ok = json.optBoolean("ok"),
            sessionId = json.optString("sessionId", sessionId),
            status = json.optString("status")
        )
    }

    override fun startResolveSession(request: StartResolveSessionRequest): CloudResolveSession? {
        val body = JSONObject().apply {
            put("pinId", request.pinId)
            request.deliveryRef?.takeIf { it.isNotBlank() }?.let { put("deliveryRef", it) }
            request.deviceRef?.takeIf { it.isNotBlank() }?.let { put("deviceRef", it) }
            request.sdkVersion?.takeIf { it.isNotBlank() }?.let { put("sdkVersion", it) }
            request.appPackageName?.takeIf { it.isNotBlank() }?.let { put("appPackageName", it) }
        }
        val json = requestJson("POST", "/v1/resolve-sessions", body) ?: return null
        val session = CloudResolveSession(
            sessionId = json.optString("sessionId"),
            pinId = json.optString("pinId", request.pinId),
            versionId = stringOrNull(json, "versionId"),
            projectId = stringOrNull(json, "projectId"),
            organizationId = stringOrNull(json, "organizationId"),
            status = json.optString("status"),
            sessionExpiresAt = stringOrNull(json, "sessionExpiresAt"),
            payloadDownloadUrl = json.optString("payloadDownloadUrl"),
            payloadUrlExpiresAt = stringOrNull(json, "payloadUrlExpiresAt"),
            payloadFormat = stringOrNull(json, "payloadFormat") ?: PayloadFormat.JSON_V1,
            payloadEncoding = stringOrNull(json, "payloadEncoding") ?: PayloadEncoding.PLAINTEXT,
            payloadHash = stringOrNull(json, "payloadHash"),
            payloadHashAlgorithm = stringOrNull(json, "payloadHashAlgorithm"),
            payloadCanonicalization = stringOrNull(json, "payloadCanonicalization"),
            payloadEncryption = json.optJSONObject("payloadEncryption")?.let { encryption ->
                PayloadEncryptionInfo(
                    algorithm = encryption.optString("algorithm"),
                    dataKeyBase64 = encryption.optString("dataKeyBase64"),
                    nonceBase64 = encryption.optString("nonceBase64"),
                    aadBase64 = encryption.optString("aadBase64"),
                    envelopeHash = stringOrNull(encryption, "envelopeHash"),
                    envelopeHashAlgorithm = stringOrNull(encryption, "envelopeHashAlgorithm")
                )
            }
        )
        return session
    }

    override fun downloadResolveSessionPayload(session: CloudResolveSession): ResolveSessionPayload {
        return payloadSecurityCore.decode(
            CloudPayloadPackage(
                session = session,
                downloadedBytes = downloadPayloadBytes(session.payloadDownloadUrl)
            )
        )
    }

    override fun reportResolveSessionEvent(
        sessionId: String,
        request: ResolveSessionEventReport
    ): ResolveSessionEventReportResult? {
        val body = JSONObject().apply {
            put("eventType", request.eventType)
            put("pinId", request.pinId)
            request.payloadHash?.takeIf { it.isNotBlank() }?.let { put("payloadHash", it) }
            request.attemptCount?.let { put("attemptCount", it) }
            request.bestInlierCount?.let { put("bestInlierCount", it) }
            request.bestMatchCount?.let { put("bestMatchCount", it) }
            request.elapsedMs?.let { put("elapsedMs", it) }
            request.failureReason?.takeIf { it.isNotBlank() }?.let { put("failureReason", it) }
        }
        val json = requestJson("POST", "/v1/resolve-sessions/$sessionId/events", body) ?: return null
        return ResolveSessionEventReportResult(
            ok = json.optBoolean("ok"),
            sessionId = json.optString("sessionId", sessionId),
            status = json.optString("status")
        )
    }

    override fun listPins(externalRef: String?): List<PointCloudPayload.SavedPinInfo> {
        val queryParams = mutableListOf("limit=100")
        externalRef
            ?.takeIf { it.isNotBlank() }
            ?.let { queryParams += "externalRef=${URLEncoder.encode(it, "UTF-8")}" }
        val suffix = "?${queryParams.joinToString("&")}"
        val json = requestJson("GET", "/v1/pins$suffix") ?: return emptyList()
        val items = json.optJSONArray("items") ?: JSONArray()
        return (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            PointCloudPayload.SavedPinInfo(
                pinId = item.optString("pinId"),
                timestamp = item.optLong("timestamp", parseTimestamp(item.optString("createdAt"))),
                pointCount = item.optInt("pointCount"),
                algorithmId = item.optInt("algorithmId", DEFAULT_FEATURE_ALGORITHM_ID),
                geospatialMetadata = if (item.has("latitude") && item.has("longitude")) {
                    GeospatialPinMetadata(
                        latitude = item.optDouble("latitude"),
                        longitude = item.optDouble("longitude"),
                        altitude = if (item.has("altitude")) item.optDouble("altitude") else null,
                        floorLabel = item.optString("floorLabel").takeIf(String::isNotBlank)
                    )
                } else {
                    null
                },
                displayName = stringOrNull(item, "displayName")
                    ?: stringOrNull(item, "externalRef")
                    ?: cloudFallbackName(item.optString("pinId")),
                cloudPinId = stringOrNull(item, "pinId"),
                uploadStatus = UploadStatus.UPLOADED.value,
                payloadFormat = stringOrNull(item, "payloadFormat") ?: PayloadFormat.JSON_V1,
                payloadEncoding = stringOrNull(item, "payloadEncoding") ?: PayloadEncoding.PLAINTEXT,
                payloadHash = stringOrNull(item, "payloadHash"),
                payloadHashAlgorithm = stringOrNull(item, "payloadHashAlgorithm"),
                payloadCanonicalization = stringOrNull(item, "payloadCanonicalization")
            )
        }.sortedBy { it.timestamp }
    }

    override fun deletePin(pinId: String): Boolean {
        requestJson("DELETE", "/v1/pins/$pinId")
        return true
    }

    private fun requestJson(
        method: String,
        path: String,
        body: JSONObject? = null
    ): JSONObject? {
        val baseUrl = config.effectiveBackendBaseUrl
        val connection = (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            config.effectiveApiKey?.let {
                setRequestProperty("X-Nimpu-Api-Key", it)
            }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }

        try {
            if (body != null) {
                BufferedWriter(OutputStreamWriter(connection.outputStream)).use { writer ->
                    writer.write(body.toString())
                }
            }

            val stream = if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                val rawError = connection.errorStream?.reader()?.use { it.readText() }.orEmpty()
                throw IOException("Backend request failed (${connection.responseCode}): $rawError")
            }

            val raw = BufferedReader(stream.reader()).use { it.readText() }
            return if (raw.isBlank()) JSONObject() else JSONObject(raw)
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadPayloadBytes(url: String): ByteArray {
        if (url.isBlank()) throw IOException("Signed payload URL is empty.")
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/json, application/x-protobuf")
        }
        try {
            val stream = if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                val rawError = connection.errorStream?.reader()?.use { it.readText() }.orEmpty()
                throw IOException("Signed payload download failed (${connection.responseCode}): $rawError")
            }
            val output = ByteArrayOutputStream()
            stream.use { input -> input.copyTo(output) }
            return output.toByteArray()
        } finally {
            connection.disconnect()
        }
    }

    private fun PointCloudPayload.toMetadataJson(): JSONObject = JSONObject().apply {
        put("pointCount", points3D.size)
        put("algorithmId", algorithmId)
        put("descriptorSize", descriptorSize)
        geospatialMetadata?.let { put("geospatialMetadata", it.toJson()) }
        put("captureMetadata", captureMetadata.toJson())
    }

    private fun parseTimestamp(raw: String): Long {
        if (raw.isBlank()) return 0L
        val normalized = raw.replace(Regex("\\.\\d+"), "").replace("+00:00", "Z")
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.parse(normalized)?.time ?: 0L
        }.getOrDefault(0L)
    }

    private fun cloudFallbackName(pinId: String): String {
        val suffix = pinId.removePrefix("pin_").take(8).ifBlank { pinId.take(8) }
        return "Cloud Pin $suffix"
    }

    private fun stringOrNull(json: JSONObject, key: String): String? {
        if (json.isNull(key)) return null
        return json.optString(key).takeIf { it.isNotBlank() && it != "null" }
    }
}

internal class PinRepository(
    private val apiClient: PinApiClient
) {
    fun createEntitlement(request: CreateEntitlementRequest): SdkEntitlement? =
        apiClient.createEntitlement(request)

    fun upload(
        externalRef: String,
        payload: PointCloudPayload,
        config: NimpuSpatialConfig,
        displayName: String? = null
    ): PinUploadResult? =
        apiClient.uploadPin(PinUploadRequest(externalRef, displayName, payload, config))

    fun fetch(pinId: String): FetchedPinPayload? = apiClient.fetchPin(pinId)

    fun startCreateSession(request: StartCreateSessionRequest): CreateSessionInfo? =
        apiClient.startCreateSession(request)

    fun reportCreateSessionEvent(
        sessionId: String,
        request: CreateSessionEventReport
    ): CreateSessionEventReportResult? =
        apiClient.reportCreateSessionEvent(sessionId, request)

    fun startResolveSession(request: StartResolveSessionRequest): CloudResolveSession? =
        apiClient.startResolveSession(request)

    fun downloadResolveSessionPayload(session: CloudResolveSession): ResolveSessionPayload =
        apiClient.downloadResolveSessionPayload(session)

    fun reportResolveSessionEvent(
        sessionId: String,
        request: ResolveSessionEventReport
    ): ResolveSessionEventReportResult? =
        apiClient.reportResolveSessionEvent(sessionId, request)

    fun list(externalRef: String? = null): List<PointCloudPayload.SavedPinInfo> = apiClient.listPins(externalRef)

    fun delete(pinId: String): Boolean = apiClient.deletePin(pinId)
}
