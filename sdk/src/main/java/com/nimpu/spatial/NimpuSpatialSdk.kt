package com.nimpu.spatial.sdk

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlin.coroutines.resume

/**
 * Public SDK bootstrap/config surface for the Android MVP.
 */
object NimpuSpatialSdk {
    private const val TAG = "NimpuSdk"
    private const val SDK_VERSION = "0.1.0"
    internal const val NIMPU_CLOUD_BASE_URL = "https://api.spatial.nimpu.in"

    @Volatile
    private var config: NimpuSpatialConfig = NimpuSpatialConfig()
    @Volatile
    private var applicationContext: Context? = null
    private val telemetryExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "NimpuTelemetry").apply { isDaemon = true }
    }

    fun initialize(config: NimpuSpatialConfig) {
        this.config = config
        Log.i(
            TAG,
            "initialize cloudConfigured=${config.isCloudConfigured} " +
                "apiKeyConfigured=${!config.effectiveApiKey.isNullOrBlank()} " +
                "geospatialGuidance=${config.geospatialGuidanceMode}"
        )
    }

    fun initialize(context: Context, config: NimpuSpatialConfig) {
        applicationContext = context.applicationContext
        initialize(config)
        applicationContext?.let { appContext ->
            if (config.isCloudConfigured) {
                SdkEntitlementStore.load(appContext)?.let { cached ->
                    installNativeEntitlement(cached, "startup cached")
                }
            }
            thread(name = "NimpuEntitlementRefresh") {
                when (val result = ensureEntitlementBlocking(appContext, EntitlementFeature.CLOUD_CREATE)) {
                    is EntitlementCheckResult.Allowed -> Log.i(
                        TAG,
                        "entitlement ready features=${result.entitlement.features.joinToString()}"
                    )
                    is EntitlementCheckResult.Denied -> Log.w(TAG, "entitlement activation pending: ${result.message}")
                }
            }
        }
    }

    fun currentConfig(): NimpuSpatialConfig = config

    fun listLocalPins(context: Context): List<NimpuPin> {
        val pins = PointCloudPayload.listSavedPins(context).map { it.toNimpuPin(isLocalRecord = true) }
        Log.i(TAG, "listLocalPins count=${pins.size}")
        return pins
    }

    fun deleteLocalPin(context: Context, localPinId: String): Boolean {
        val deleted = PointCloudPayload.delete(context, localPinId)
        Log.i(TAG, "deleteLocalPin localPinId=$localPinId deleted=$deleted")
        return deleted
    }

    fun deleteCloudPin(cloudPinId: String, callback: (CloudPinDeleteResult) -> Unit) {
        thread(name = "NimpuCloudPinDelete") {
            callback(deleteCloudPinBlocking(cloudPinId))
        }
    }

    suspend fun deleteCloudPin(cloudPinId: String): CloudPinDeleteResult =
        withContext(Dispatchers.IO) {
            deleteCloudPinBlocking(cloudPinId)
        }

    fun deleteCloudPinBlocking(cloudPinId: String): CloudPinDeleteResult {
        if (cloudPinId.isBlank()) {
            Log.w(TAG, "deleteCloudPin skipped: cloudPinId is blank")
            return CloudPinDeleteResult.Failed(cloudPinId, "Cloud pin id is required.")
        }
        if (!hasBackendConfig()) {
            Log.w(TAG, "deleteCloudPin skipped: backend not configured cloudPinId=$cloudPinId")
            DebugSessionLog.append("DELETE", "Cloud pin delete skipped: backend not configured cloudPinId=$cloudPinId")
            return CloudPinDeleteResult.Failed(cloudPinId, "Nimpu backend is not configured.")
        }
        Log.i(TAG, "deleteCloudPin start cloudPinId=$cloudPinId")
        DebugSessionLog.append("DELETE", "Cloud pin delete requested: cloudPinId=$cloudPinId")
        return runCatching {
            PinRepository(HttpPinApiClient(config)).delete(cloudPinId)
        }.fold(
            onSuccess = { deleted ->
                if (deleted) {
                    Log.i(TAG, "deleteCloudPin success cloudPinId=$cloudPinId")
                    DebugSessionLog.append("DELETE", "Cloud pin delete complete: cloudPinId=$cloudPinId")
                    CloudPinDeleteResult.Success(cloudPinId)
                } else {
                    Log.w(TAG, "deleteCloudPin failed cloudPinId=$cloudPinId error=backend returned false")
                    DebugSessionLog.append("DELETE", "Cloud pin delete failed: cloudPinId=$cloudPinId backend returned false")
                    CloudPinDeleteResult.Failed(cloudPinId, "Backend did not confirm cloud delete.")
                }
            },
            onFailure = { error ->
                val message = error.message ?: "Failed to delete cloud pin."
                Log.w(TAG, "deleteCloudPin failed cloudPinId=$cloudPinId error=$message", error)
                DebugSessionLog.append("DELETE", "Cloud pin delete failed: cloudPinId=$cloudPinId error=$message")
                CloudPinDeleteResult.Failed(cloudPinId, message)
            }
        )
    }

    fun defaultPinDisplayName(context: Context): String {
        return PointCloudPayload.defaultPinDisplayName(context)
    }

    fun uploadDisplayNameOrDefault(context: Context, pin: NimpuPin): String {
        return pin.displayName
            .takeIf { it.isNotBlank() && it != pin.primaryId }
            ?: PointCloudPayload.defaultPinDisplayName(context)
    }

    internal fun saveCreatedPin(
        context: Context,
        payload: PointCloudPayload,
        displayName: String,
        uploadToCloud: Boolean = true,
        captureTelemetry: CreateCaptureTelemetry? = null,
        callback: (CreatePinResult) -> Unit
    ) {
        val appContext = context.applicationContext
        thread(name = "NimpuCreatePinSave") {
            when (val entitlement = ensureEntitlementBlocking(appContext, EntitlementFeature.OFFLINE_CLOUD_CREATE)) {
                is EntitlementCheckResult.Allowed -> Unit
                is EntitlementCheckResult.Denied -> {
                    Log.w(TAG, "saveCreatedPin blocked: ${entitlement.message}")
                    DebugSessionLog.append("CREATE", "Create blocked: ${entitlement.message}")
                    callback(CreatePinResult.Failed(localPinId = null, error = entitlement.message))
                    return@thread
                }
            }
            val localPinId = newLocalPinId()
            val integrity = PointCloudPayload.computeIntegrity(payload, PayloadFormat.PROTOBUF_V1)
            runCatching {
                payload.save(
                    context = appContext,
                    pinId = localPinId,
                    displayName = displayName,
                    uploadStatus = UploadStatus.LOCAL_ONLY
                )
            }.fold(
                onSuccess = {
                    Log.i(
                        TAG,
                        "saveCreatedPin saved localPinId=$localPinId pointCount=${payload.points3D.size} payloadHash=${integrity.shortHash}"
                    )
                    DebugSessionLog.append(
                        "CREATE",
                        "Local save complete: $localPinId points=${payload.points3D.size} hash=${integrity.shortHash}"
                    )
                    callback(
                        CreatePinResult.SavedLocal(
                            localPinId = localPinId,
                            pointCount = payload.points3D.size
                        )
                    )
                    startCreateTelemetryAfterLocalSaveAsync(
                        context = appContext,
                        localPinId = localPinId,
                        displayName = displayName,
                        payload = payload,
                        integrity = integrity,
                        captureTelemetry = captureTelemetry
                    )
                    if (uploadToCloud) {
                        DebugSessionLog.append("UPLOAD", "Cloud upload requested: localPinId=$localPinId")
                        when (val uploadResult = uploadLocalPinBlocking(
                            context = appContext,
                            localPinId = localPinId,
                            displayName = displayName,
                            telemetryKind = CreateUploadTelemetryKind.INITIAL_UPLOAD,
                            captureTelemetry = captureTelemetry
                        )) {
                            is LocalPinUploadResult.Uploaded -> callback(
                                CreatePinResult.Uploaded(
                                    localPinId = uploadResult.localPinId,
                                    cloudPinId = uploadResult.cloudPinId
                                )
                            )
                            is LocalPinUploadResult.Failed -> callback(
                                CreatePinResult.Failed(
                                    localPinId = uploadResult.localPinId,
                                    error = uploadResult.error
                                )
                            )
                        }
                    }
                },
                onFailure = { error ->
                    val message = error.message ?: "Failed to save pin."
                    Log.w(TAG, "saveCreatedPin failed error=$message", error)
                    DebugSessionLog.append("CREATE", "Local save failed: $message")
                    reportCreateLocalSaveFailedAsync(
                        context = appContext,
                        localPinId = localPinId,
                        displayName = displayName,
                        payload = payload,
                        integrity = integrity,
                        captureTelemetry = captureTelemetry,
                        failureReason = message
                    )
                    callback(CreatePinResult.Failed(localPinId = null, error = message))
                }
            )
        }
    }

    internal suspend fun saveCreatedPin(
        context: Context,
        payload: PointCloudPayload,
        displayName: String,
        uploadToCloud: Boolean = true,
        captureTelemetry: CreateCaptureTelemetry? = null
    ): CreatePinResult =
        suspendCancellableCoroutine { continuation ->
            saveCreatedPin(
                context = context,
                payload = payload,
                displayName = displayName,
                uploadToCloud = uploadToCloud,
                captureTelemetry = captureTelemetry
            ) { result ->
                if (!continuation.isActive) return@saveCreatedPin
                if (!uploadToCloud || result !is CreatePinResult.SavedLocal) {
                    continuation.resume(result)
                }
            }
        }

    fun listCloudPins(callback: (CloudPinListResult) -> Unit) {
        thread(name = "NimpuCloudPinList") {
            callback(listCloudPinsBlocking())
        }
    }

    fun listCloudPins(context: Context, callback: (CloudPinListResult) -> Unit) {
        val appContext = context.applicationContext
        thread(name = "NimpuCloudPinList") {
            callback(listCloudPinsBlocking(appContext))
        }
    }

    suspend fun listCloudPins(): CloudPinListResult =
        withContext(Dispatchers.IO) {
            listCloudPinsBlocking()
        }

    suspend fun listCloudPins(context: Context): CloudPinListResult =
        withContext(Dispatchers.IO) {
            listCloudPinsBlocking(context.applicationContext)
        }

    fun listCloudPinsBlocking(): CloudPinListResult {
        val appContext = applicationContext
            ?: return CloudPinListResult.Failed("SDK was not initialized with an Android context.")
        return listCloudPinsBlocking(appContext)
    }

    fun listCloudPinsBlocking(context: Context): CloudPinListResult {
        if (!config.isCloudConfigured) {
            Log.w(TAG, "listCloudPins skipped: backend not configured")
            DebugSessionLog.append("RESOLVE", "Cloud pin list skipped: backend not configured")
            return CloudPinListResult.Failed("Nimpu backend is not configured.")
        }
        when (val entitlement = ensureEntitlementBlocking(context.applicationContext, EntitlementFeature.CLOUD_RESOLVE)) {
            is EntitlementCheckResult.Allowed -> Unit
            is EntitlementCheckResult.Denied -> {
                Log.w(TAG, "listCloudPins blocked: ${entitlement.message}")
                DebugSessionLog.append("RESOLVE", "Cloud pin list blocked: ${entitlement.message}")
                return CloudPinListResult.Failed(entitlement.message)
            }
        }
        Log.i(TAG, "listCloudPins start")
        return runCatching {
            PinRepository(HttpPinApiClient(config)).list().map { it.toNimpuPin(isLocalRecord = false) }
        }.fold(
            onSuccess = { pins ->
                Log.i(
                    TAG,
                    "listCloudPins success count=${pins.size} ids=${pins.joinToString { it.cloudPinId ?: it.primaryId }}"
                )
                DebugSessionLog.append("RESOLVE", "Cloud pin list loaded: count=${pins.size}")
                CloudPinListResult.Success(pins)
            },
            onFailure = { error ->
                val message = error.message ?: "Failed to list cloud pins."
                Log.w(TAG, "listCloudPins failed error=$message", error)
                DebugSessionLog.append("RESOLVE", "Cloud pin list failed: $message")
                CloudPinListResult.Failed(message)
            }
        )
    }

    fun loadLocalResolveTarget(
        context: Context,
        localPinId: String
    ): ResolveTarget? =
        when (val result = loadLocalResolveTargetResult(context, localPinId)) {
            is LocalResolveTargetLoadResult.Success -> result.target
            is LocalResolveTargetLoadResult.Failed -> null
        }

    private fun loadLocalResolveTargetResult(
        context: Context,
        localPinId: String
    ): LocalResolveTargetLoadResult {
        when (val entitlement = ensureEntitlementBlocking(context.applicationContext, EntitlementFeature.OFFLINE_CACHE)) {
            is EntitlementCheckResult.Allowed -> Unit
            is EntitlementCheckResult.Denied -> {
                Log.w(TAG, "loadLocalResolveTarget blocked localPinId=$localPinId error=${entitlement.message}")
                DebugSessionLog.append("RESOLVE", "Local resolve blocked: ${entitlement.message}")
                return LocalResolveTargetLoadResult.Failed(userFacingEntitlementMessage(entitlement))
            }
        }
        Log.i(TAG, "loadLocalResolveTarget start localPinId=$localPinId")
        val payload = PointCloudPayload.load(context, localPinId)
            ?: return LocalResolveTargetLoadResult.Failed("This saved pin could not be loaded.")
        val pin = PointCloudPayload.savedPinInfo(context, localPinId)?.toNimpuPin(isLocalRecord = true)
        val localPayloadFormat = pin?.payloadFormat ?: PayloadFormat.JSON_V1
        val integrity = PointCloudPayload.computeIntegrity(payload, localPayloadFormat)
        if (pin?.payloadHash != null && pin.payloadHash != integrity.payloadHash) {
            Log.e(
                TAG,
                "loadLocalResolveTarget refused localPinId=$localPinId expectedHash=${PayloadIntegrity.shortHash(pin.payloadHash)} actualHash=${integrity.shortHash}"
            )
            return LocalResolveTargetLoadResult.Failed("This saved pin could not be loaded.")
        }
        Log.i(
            TAG,
            "loadLocalResolveTarget success localPinId=$localPinId displayName=${pin?.displayName ?: localPinId} payloadHash=${integrity.shortHash}"
        )
        return LocalResolveTargetLoadResult.Success(
            ResolveTarget(
                pinId = localPinId,
                displayName = pin?.displayName ?: localPinId,
                payload = payload,
                geospatialMetadata = payload.geospatialMetadata,
                payloadFormat = localPayloadFormat,
                payloadEncoding = pin?.payloadEncoding ?: PayloadEncoding.PLAINTEXT,
                payloadHash = integrity.payloadHash,
                payloadHashAlgorithm = integrity.payloadHashAlgorithm,
                payloadCanonicalization = integrity.payloadCanonicalization
            )
        )
    }

    fun prepareResolveTarget(
        context: Context,
        pin: NimpuPin,
        callback: (PrepareResolveTargetResult) -> Unit
    ) {
        prepareResolveTarget(context, pin, onProgress = null, callback = callback)
    }

    fun prepareResolveTarget(
        context: Context,
        pin: NimpuPin,
        onProgress: ((PrepareResolveTargetProgress) -> Unit)? = null,
        callback: (PrepareResolveTargetResult) -> Unit
    ) {
        val appContext = context.applicationContext
        thread(name = "NimpuPrepareResolveTarget") {
            callback(prepareResolveTargetBlocking(appContext, pin, onProgress))
        }
    }

    suspend fun prepareResolveTarget(
        context: Context,
        pin: NimpuPin
    ): PrepareResolveTargetResult =
        withContext(Dispatchers.IO) {
            prepareResolveTargetBlocking(context.applicationContext, pin)
        }

    fun prepareResolveTargetBlocking(
        context: Context,
        pin: NimpuPin,
        onProgress: ((PrepareResolveTargetProgress) -> Unit)? = null
    ): PrepareResolveTargetResult {
        pin.localPinId?.takeIf { it.isNotBlank() }?.let { localPinId ->
            onProgress?.invoke(PrepareResolveTargetProgress.LoadingLocal(localPinId))
            return when (val result = loadLocalResolveTargetResult(context, localPinId)) {
                is LocalResolveTargetLoadResult.Success -> PrepareResolveTargetResult.Success(result.target)
                is LocalResolveTargetLoadResult.Failed -> PrepareResolveTargetResult.Failed(
                    pinId = localPinId,
                    error = result.message
                )
            }
        }

        val cloudPinId = pin.cloudPinId?.takeIf { it.isNotBlank() }
            ?: return PrepareResolveTargetResult.Failed(
                pinId = pin.primaryId,
                error = "Selected pin does not have a local or cloud id."
            )

        return when (val result = fetchCloudResolveTargetBlocking(context, cloudPinId, pin.displayName, onProgress)) {
            is CloudPinFetchResult.Success -> PrepareResolveTargetResult.Success(result.target)
            is CloudPinFetchResult.Failed -> PrepareResolveTargetResult.Failed(
                pinId = result.cloudPinId,
                error = result.error
            )
        }
    }

    fun fetchCloudResolveTarget(
        context: Context,
        cloudPinId: String,
        displayName: String? = null,
        callback: (CloudPinFetchResult) -> Unit
    ) {
        val appContext = context.applicationContext
        thread(name = "NimpuCloudPinFetch") {
            callback(fetchCloudResolveTargetBlocking(appContext, cloudPinId, displayName))
        }
    }

    suspend fun fetchCloudResolveTarget(
        context: Context,
        cloudPinId: String,
        displayName: String? = null
    ): CloudPinFetchResult =
        withContext(Dispatchers.IO) {
            fetchCloudResolveTargetBlocking(context.applicationContext, cloudPinId, displayName)
        }

    fun fetchCloudResolveTargetBlocking(
        context: Context,
        cloudPinId: String,
        displayName: String? = null,
        onProgress: ((PrepareResolveTargetProgress) -> Unit)? = null
    ): CloudPinFetchResult {
        if (!config.isCloudConfigured) {
            Log.w(TAG, "fetchCloudResolveTarget skipped: backend not configured cloudPinId=$cloudPinId")
            DebugSessionLog.append("RESOLVE", "Cloud payload fetch skipped: backend not configured")
            return CloudPinFetchResult.Failed(cloudPinId, "Nimpu backend is not configured.")
        }
        when (val entitlement = ensureEntitlementBlocking(context.applicationContext, EntitlementFeature.CLOUD_RESOLVE)) {
            is EntitlementCheckResult.Allowed -> Unit
            is EntitlementCheckResult.Denied -> {
                Log.w(TAG, "fetchCloudResolveTarget blocked cloudPinId=$cloudPinId error=${entitlement.message}")
                DebugSessionLog.append("RESOLVE", "Cloud resolve blocked: ${entitlement.message}")
                return CloudPinFetchResult.Failed(cloudPinId, entitlement.message)
            }
        }
        Log.i(TAG, "fetchCloudResolveTarget start cloudPinId=$cloudPinId displayName=${displayName.orEmpty()}")
        return runCatching {
            val repository = PinRepository(HttpPinApiClient(config))
            onProgress?.invoke(PrepareResolveTargetProgress.CheckingCloud(cloudPinId))
            val session = repository.startResolveSession(
                StartResolveSessionRequest(
                    pinId = cloudPinId,
                    sdkVersion = SDK_VERSION,
                    appPackageName = context.packageName
                )
            )
                ?: return@runCatching null
            cachedResolveTarget(context, cloudPinId, displayName, session)?.let { cachedTarget ->
                onProgress?.invoke(PrepareResolveTargetProgress.UsingCachedPayload(cloudPinId))
                return@runCatching cachedTarget
            }
            onProgress?.invoke(PrepareResolveTargetProgress.DownloadingPayload(cloudPinId))
            val fetched = repository.downloadResolveSessionPayload(session)
            val actualIntegrity = computeDownloadedPayloadIntegrity(fetched)
            val expectedHash = fetched.session.payloadHash
            if (!expectedHash.isNullOrBlank() && expectedHash != actualIntegrity.payloadHash) {
                throw IllegalStateException(
                    "Payload hash mismatch. expected=${PayloadIntegrity.shortHash(expectedHash)} actual=${actualIntegrity.shortHash}"
                )
            }
            DebugSessionLog.append(
                "RESOLVE",
                "Resolve session started: sessionId=${fetched.session.sessionId} " +
                    "payloadUrlExpiresAt=${fetched.session.payloadUrlExpiresAt ?: "unknown"} " +
                    "format=${fetched.session.payloadFormat} " +
                    "encoding=${fetched.session.payloadEncoding} " +
                    "payloadHash=${PayloadIntegrity.shortHash(expectedHash)} " +
                    "envelopeHash=${PayloadIntegrity.shortHash(fetched.session.payloadEncryption?.envelopeHash)}"
            )
            fetched.payload.save(
                context = context,
                pinId = cloudPinId,
                displayName = displayName,
                cloudPinId = cloudPinId,
                uploadStatus = UploadStatus.UPLOADED,
                payloadFormat = PayloadFormat.PROTOBUF_V1,
                payloadEncoding = PayloadEncoding.PLAINTEXT,
                payloadIntegrity = actualIntegrity
            )
            reportResolvePayloadDownloadedAsync(
                session = fetched.session,
                payloadHash = expectedHash ?: actualIntegrity.payloadHash
            )
            ResolveTarget(
                pinId = cloudPinId,
                displayName = displayName?.takeIf { it.isNotBlank() } ?: cloudPinId,
                payload = fetched.payload,
                geospatialMetadata = fetched.payload.geospatialMetadata,
                payloadFormat = fetched.session.payloadFormat,
                payloadEncoding = fetched.session.payloadEncoding,
                payloadHash = expectedHash ?: actualIntegrity.payloadHash,
                payloadHashAlgorithm = fetched.session.payloadHashAlgorithm ?: actualIntegrity.payloadHashAlgorithm,
                payloadCanonicalization = fetched.session.payloadCanonicalization ?: actualIntegrity.payloadCanonicalization,
                cloudResolveSession = fetched.session
            )
        }.fold(
            onSuccess = { target ->
                if (target == null) {
                    Log.w(TAG, "fetchCloudResolveTarget failed cloudPinId=$cloudPinId error=empty payload")
                    DebugSessionLog.append("RESOLVE", "Cloud payload fetch failed: empty payload for $cloudPinId")
                    CloudPinFetchResult.Failed(cloudPinId, "Backend returned an empty payload.")
                } else {
                    Log.i(
                        TAG,
                        "fetchCloudResolveTarget success cloudPinId=$cloudPinId pointCount=${target.payload.points3D.size} payloadHash=${PayloadIntegrity.shortHash(target.payloadHash)}"
                    )
                    DebugSessionLog.append(
                        "RESOLVE",
                        "Cloud payload ready: $cloudPinId points=${target.payload.points3D.size} " +
                            "format=${target.payloadFormat} encoding=${target.payloadEncoding} " +
                            "payloadHash=${PayloadIntegrity.shortHash(target.payloadHash)} " +
                            "envelopeHash=${PayloadIntegrity.shortHash(target.cloudResolveSession?.payloadEncryption?.envelopeHash)}"
                    )
                    CloudPinFetchResult.Success(target)
                }
            },
            onFailure = { error ->
                val message = error.message ?: "Failed to fetch cloud pin."
                Log.w(TAG, "fetchCloudResolveTarget failed cloudPinId=$cloudPinId error=$message", error)
                DebugSessionLog.append("RESOLVE", "Cloud payload fetch failed: $message")
                CloudPinFetchResult.Failed(cloudPinId, message)
            }
        )
    }

    private fun cachedResolveTarget(
        context: Context,
        cloudPinId: String,
        displayName: String?,
        session: CloudResolveSession
    ): ResolveTarget? {
        val expectedHash = session.payloadHash?.takeIf { it.isNotBlank() } ?: return null
        val expectedCanonicalization = session.payloadCanonicalization?.takeIf { it.isNotBlank() } ?: return null
        val cachedInfo = PointCloudPayload.savedPinInfo(context, cloudPinId) ?: return null
        val expectedHashAlgorithm = session.payloadHashAlgorithm?.takeIf { it.isNotBlank() }
        if (cachedInfo.payloadHash != expectedHash ||
            cachedInfo.payloadCanonicalization != expectedCanonicalization ||
            (expectedHashAlgorithm != null && cachedInfo.payloadHashAlgorithm != expectedHashAlgorithm)
        ) {
            Log.i(
                TAG,
                "cachedResolveTarget stale cloudPinId=$cloudPinId " +
                    "cachedHash=${PayloadIntegrity.shortHash(cachedInfo.payloadHash)} " +
                    "expectedHash=${PayloadIntegrity.shortHash(expectedHash)}"
            )
            DebugSessionLog.append(
                "RESOLVE",
                "Local cloud cache stale: $cloudPinId cached=${PayloadIntegrity.shortHash(cachedInfo.payloadHash)} " +
                    "expected=${PayloadIntegrity.shortHash(expectedHash)}"
            )
            return null
        }

        val payload = PointCloudPayload.load(context, cloudPinId) ?: return null
        val integrity = PointCloudPayload.computeIntegrity(payload, cachedInfo.payloadFormat)
        if (integrity.payloadHash != expectedHash ||
            integrity.payloadCanonicalization != expectedCanonicalization ||
            (expectedHashAlgorithm != null && integrity.payloadHashAlgorithm != expectedHashAlgorithm)
        ) {
            Log.w(
                TAG,
                "cachedResolveTarget integrity mismatch cloudPinId=$cloudPinId " +
                    "actualHash=${integrity.shortHash} expectedHash=${PayloadIntegrity.shortHash(expectedHash)}"
            )
            DebugSessionLog.append(
                "RESOLVE",
                "Local cloud cache integrity mismatch: $cloudPinId actual=${integrity.shortHash} " +
                    "expected=${PayloadIntegrity.shortHash(expectedHash)}"
            )
            return null
        }

        Log.i(TAG, "cachedResolveTarget hit cloudPinId=$cloudPinId payloadHash=${integrity.shortHash}")
        DebugSessionLog.append(
            "RESOLVE",
            "Resolve session started with local cache hit: sessionId=${session.sessionId} " +
                "cloudPinId=$cloudPinId payloadHash=${integrity.shortHash}"
        )
        return ResolveTarget(
            pinId = cloudPinId,
            displayName = displayName?.takeIf { it.isNotBlank() } ?: cachedInfo.displayLabel,
            payload = payload,
            geospatialMetadata = payload.geospatialMetadata,
            payloadFormat = session.payloadFormat,
            payloadEncoding = session.payloadEncoding,
            payloadHash = expectedHash,
            payloadHashAlgorithm = session.payloadHashAlgorithm ?: integrity.payloadHashAlgorithm,
            payloadCanonicalization = session.payloadCanonicalization ?: integrity.payloadCanonicalization,
            cloudResolveSession = session
        )
    }

    private fun computeDownloadedPayloadIntegrity(fetched: ResolveSessionPayload): PayloadIntegrityMetadata =
        when (fetched.session.payloadFormat) {
            PayloadFormat.PROTOBUF_V1 -> PointCloudPayloadProtobuf.computeIntegrity(fetched.payloadBytes)
            else -> PayloadIntegrity.compute(fetched.payload)
        }

    fun reportCloudResolveResult(
        target: ResolveTarget,
        result: ResolveEngineResult
    ) {
        val session = target.cloudResolveSession ?: return
        thread(name = "NimpuResolveSessionReport") {
            val event = when (result) {
                is ResolveEngineResult.Resolved -> ResolveSessionEventReport(
                    eventType = "resolve_succeeded",
                    pinId = session.pinId,
                    payloadHash = target.payloadHash,
                    attemptCount = result.stats.attemptCount,
                    bestInlierCount = result.stats.bestInlierCount,
                    bestMatchCount = result.stats.bestMatchCount,
                    elapsedMs = result.stats.elapsedMs.toInt()
                )
                is ResolveEngineResult.Failed -> ResolveSessionEventReport(
                    eventType = "resolve_failed",
                    pinId = session.pinId,
                    payloadHash = target.payloadHash,
                    attemptCount = result.stats?.attemptCount,
                    bestInlierCount = result.stats?.bestInlierCount,
                    bestMatchCount = result.stats?.bestMatchCount,
                    elapsedMs = result.stats?.elapsedMs?.toInt(),
                    failureReason = result.reason
                )
            }
            runCatching {
                PinRepository(HttpPinApiClient(config)).reportResolveSessionEvent(session.sessionId, event)
            }.fold(
                onSuccess = { report ->
                    Log.i(
                        TAG,
                        "reportCloudResolveResult sessionId=${session.sessionId} event=${event.eventType} status=${report?.status ?: "null"}"
                    )
                    DebugSessionLog.append(
                        "RESOLVE",
                        "Resolve session ${event.eventType} report: sessionId=${session.sessionId} status=${report?.status ?: "failed"}"
                    )
                },
                onFailure = { error ->
                    val message = error.message ?: "report failed"
                    Log.w(TAG, "reportCloudResolveResult failed sessionId=${session.sessionId} error=$message", error)
                    DebugSessionLog.append(
                        "RESOLVE",
                        "Resolve session ${event.eventType} report failed: sessionId=${session.sessionId} error=$message"
                    )
                }
            )
        }
    }

    fun uploadLocalPin(
        context: Context,
        localPinId: String,
        displayName: String? = null,
        callback: (LocalPinUploadResult) -> Unit
    ) {
        val appContext = context.applicationContext
        thread(name = "NimpuLocalPinUpload") {
            val result = uploadLocalPinBlocking(
                context = appContext,
                localPinId = localPinId,
                displayName = displayName,
                telemetryKind = CreateUploadTelemetryKind.RETRY_UPLOAD
            )
            callback(result)
        }
    }

    suspend fun uploadLocalPin(
        context: Context,
        localPinId: String,
        displayName: String? = null
    ): LocalPinUploadResult =
        withContext(Dispatchers.IO) {
            uploadLocalPinBlocking(context.applicationContext, localPinId, displayName)
        }

    fun uploadLocalPinBlocking(
        context: Context,
        localPinId: String,
        displayName: String? = null
    ): LocalPinUploadResult {
        return uploadLocalPinBlocking(
            context = context,
            localPinId = localPinId,
            displayName = displayName,
            telemetryKind = CreateUploadTelemetryKind.RETRY_UPLOAD
        )
    }

    private fun uploadLocalPinBlocking(
        context: Context,
        localPinId: String,
        displayName: String? = null,
        telemetryKind: CreateUploadTelemetryKind,
        captureTelemetry: CreateCaptureTelemetry? = null
    ): LocalPinUploadResult {
        val payload = PointCloudPayload.load(context, localPinId)
            ?: run {
                Log.w(TAG, "uploadLocalPin failed localPinId=$localPinId error=local payload not found")
                return LocalPinUploadResult.Failed(localPinId, "Local pin payload not found.")
            }
        val pinInfo = PointCloudPayload.savedPinInfo(context, localPinId)
        val resolvedDisplayName = displayName?.trim()?.takeIf { it.isNotBlank() }
            ?: pinInfo?.displayName?.takeIf { it.isNotBlank() && it != localPinId }
            ?: PointCloudPayload.defaultPinDisplayName(context)
        val localPayloadFormat = pinInfo?.payloadFormat ?: PayloadFormat.JSON_V1
        val integrity = PointCloudPayload.computeIntegrity(payload, localPayloadFormat)
        if (pinInfo?.payloadHash != null && pinInfo.payloadHash != integrity.payloadHash) {
            val message = "Local payload hash mismatch."
            Log.e(
                TAG,
                "uploadLocalPin refused localPinId=$localPinId expectedHash=${PayloadIntegrity.shortHash(pinInfo.payloadHash)} actualHash=${integrity.shortHash}"
            )
            DebugSessionLog.append(
                "UPLOAD",
                "Upload refused: localPinId=$localPinId integrity mismatch expected=${PayloadIntegrity.shortHash(pinInfo.payloadHash)} actual=${integrity.shortHash}"
            )
            return LocalPinUploadResult.Failed(localPinId, message)
        }
        val uploadPayloadFormat = config.preferredCloudPayloadFormat
        val uploadPayloadBytes = encodeCloudPayload(payload, uploadPayloadFormat)
        val uploadIntegrity = computeCloudPayloadIntegrity(payload, uploadPayloadFormat, uploadPayloadBytes)

        val createSessionId = pinInfo?.createSessionId

        Log.i(
            TAG,
            "uploadLocalPin start localPinId=$localPinId displayName=$resolvedDisplayName previousStatus=${pinInfo?.uploadStatus ?: "unknown"} pointCount=${payload.points3D.size} payloadFormat=$uploadPayloadFormat payloadHash=${uploadIntegrity.shortHash}"
        )
        DebugSessionLog.append(
            "UPLOAD",
            "Upload started: localPinId=$localPinId points=${payload.points3D.size} " +
                "format=$uploadPayloadFormat encoding=server-managed hash=${uploadIntegrity.shortHash}"
        )

        if (pinInfo?.uploadStatus == UploadStatus.UPLOADING.value) {
            Log.i(TAG, "uploadLocalPin pending localPinId=$localPinId; attempting cloud reconcile before refusing")
            DebugSessionLog.append("UPLOAD", "Upload pending: checking cloud for $localPinId before retry")
            val reconciled = reconcileUploadedPin(
                context = context,
                localPinId = localPinId,
                displayName = resolvedDisplayName,
                reason = "stale upload pending"
            )
            if (reconciled != null) {
                reportCreateUploadTelemetry(
                    context = context,
                    localPinId = localPinId,
                    sessionId = createSessionId,
                    payload = payload,
                    displayName = resolvedDisplayName,
                    integrity = uploadIntegrity,
                    eventType = telemetryKind.succeededEventType,
                    captureTelemetry = captureTelemetry,
                    cloudPinId = reconciled.cloudPinId
                )
                return reconciled
            }
            Log.w(TAG, "uploadLocalPin refused localPinId=$localPinId error=already uploading and reconcile found no cloud pin")
            DebugSessionLog.append("UPLOAD", "Upload refused: localPinId=$localPinId still pending and no cloud match found")
            reportCreateUploadTelemetry(
                context = context,
                localPinId = localPinId,
                sessionId = createSessionId,
                payload = payload,
                displayName = resolvedDisplayName,
                integrity = uploadIntegrity,
                eventType = telemetryKind.failedEventType,
                captureTelemetry = captureTelemetry,
                failureReason = "Upload already in progress."
            )
            return LocalPinUploadResult.Failed(localPinId, "Upload already in progress.")
        }

        val existingCloudPinId = pinInfo?.cloudPinId
        if (pinInfo?.uploadStatus == UploadStatus.UPLOADED.value && !existingCloudPinId.isNullOrBlank()) {
            Log.i(TAG, "uploadLocalPin already uploaded localPinId=$localPinId cloudPinId=$existingCloudPinId")
            DebugSessionLog.append(
                "UPLOAD",
                "Upload already complete: localPinId=$localPinId cloudPinId=$existingCloudPinId"
            )
            PointCloudPayload.updateUploadMetadata(
                context = context,
                pinId = localPinId,
                cloudPinId = existingCloudPinId,
                uploadStatus = UploadStatus.UPLOADED,
                displayName = resolvedDisplayName
            )
            return LocalPinUploadResult.Uploaded(localPinId, existingCloudPinId)
        }

        when (val entitlement = ensureEntitlementBlocking(context.applicationContext, EntitlementFeature.CLOUD_CREATE)) {
            is EntitlementCheckResult.Allowed -> Unit
            is EntitlementCheckResult.Denied -> {
                Log.w(TAG, "uploadLocalPin blocked localPinId=$localPinId error=${entitlement.message}")
                DebugSessionLog.append("UPLOAD", "Upload blocked: localPinId=$localPinId error=${entitlement.message}")
                PointCloudPayload.updateUploadMetadata(
                    context = context,
                    pinId = localPinId,
                    uploadStatus = UploadStatus.FAILED,
                    displayName = resolvedDisplayName,
                    uploadError = entitlement.message
                )
                return LocalPinUploadResult.Failed(localPinId, entitlement.message)
            }
        }

        if (telemetryKind == CreateUploadTelemetryKind.RETRY_UPLOAD &&
            pinInfo?.uploadStatus == UploadStatus.FAILED.value
        ) {
            Log.i(TAG, "uploadLocalPin retry localPinId=$localPinId; checking cloud before re-upload")
            DebugSessionLog.append("UPLOAD", "Retry upload: checking cloud for $localPinId before upload")
            val reconciled = reconcileUploadedPin(
                context = context,
                localPinId = localPinId,
                displayName = resolvedDisplayName,
                reason = "retry preflight"
            )
            if (reconciled != null) {
                reportCreateUploadTelemetry(
                    context = context,
                    localPinId = localPinId,
                    sessionId = createSessionId,
                    payload = payload,
                    displayName = resolvedDisplayName,
                    integrity = uploadIntegrity,
                    eventType = telemetryKind.succeededEventType,
                    captureTelemetry = captureTelemetry,
                    cloudPinId = reconciled.cloudPinId
                )
                return reconciled
            }
        }

        reportCreateUploadTelemetry(
            context = context,
            localPinId = localPinId,
                sessionId = createSessionId,
                payload = payload,
                displayName = resolvedDisplayName,
                integrity = uploadIntegrity,
                captureTelemetry = captureTelemetry,
                eventType = telemetryKind.startedEventType
        )

        if (!config.isCloudConfigured) {
            Log.w(TAG, "uploadLocalPin failed localPinId=$localPinId error=backend not configured")
            DebugSessionLog.append("UPLOAD", "Upload failed: backend not configured for $localPinId")
            PointCloudPayload.updateUploadMetadata(
                context = context,
                pinId = localPinId,
                uploadStatus = UploadStatus.FAILED,
                displayName = resolvedDisplayName,
                uploadError = "Nimpu backend is not configured."
            )
            reportCreateUploadTelemetry(
                context = context,
                localPinId = localPinId,
                sessionId = createSessionId,
                payload = payload,
                displayName = resolvedDisplayName,
                integrity = uploadIntegrity,
                captureTelemetry = captureTelemetry,
                eventType = telemetryKind.failedEventType,
                failureReason = "Nimpu backend is not configured."
            )
            return LocalPinUploadResult.Failed(localPinId, "Nimpu backend is not configured.")
        }

        PointCloudPayload.updateUploadMetadata(
            context = context,
            pinId = localPinId,
            uploadStatus = UploadStatus.UPLOADING,
            displayName = resolvedDisplayName
        )
        Log.i(TAG, "uploadLocalPin metadata marked uploading localPinId=$localPinId")

        return runCatching {
            HttpPinApiClient(config).uploadPin(
                PinUploadRequest(
                    externalRef = localPinId,
                    displayName = resolvedDisplayName,
                    payload = payload,
                    configSnapshot = config,
                    payloadFormat = uploadPayloadFormat,
                    payloadBytes = uploadPayloadBytes,
                    payloadIntegrity = uploadIntegrity
                )
            )
        }.fold(
            onSuccess = { upload ->
                if (upload != null && upload.pinId.isNotBlank()) {
                    Log.i(
                        TAG,
                        "uploadLocalPin success localPinId=$localPinId cloudPinId=${upload.pinId} versionId=${upload.versionId} status=${upload.status} payloadFormat=${upload.payloadFormat} payloadHash=${PayloadIntegrity.shortHash(upload.payloadHash)}"
                    )
                    PointCloudPayload.updateUploadMetadata(
                        context = context,
                        pinId = localPinId,
                        cloudPinId = upload.pinId,
                        uploadStatus = UploadStatus.UPLOADED,
                        displayName = resolvedDisplayName
                    )
                    DebugSessionLog.append(
                "UPLOAD",
                "Upload complete: localPinId=$localPinId cloudPinId=${upload.pinId} " +
                    "format=${upload.payloadFormat} encoding=${upload.payloadEncoding} " +
                    "hash=${PayloadIntegrity.shortHash(upload.payloadHash)}"
                    )
                    reportCreateUploadTelemetry(
                        context = context,
                        localPinId = localPinId,
                        sessionId = createSessionId,
                        payload = payload,
                        displayName = resolvedDisplayName,
                        integrity = uploadIntegrity,
                        captureTelemetry = captureTelemetry,
                        eventType = telemetryKind.succeededEventType,
                        cloudPinId = upload.pinId
                    )
                    LocalPinUploadResult.Uploaded(localPinId, upload.pinId)
                } else {
                    Log.w(TAG, "uploadLocalPin failed localPinId=$localPinId error=empty response")
                    val reconciled = reconcileUploadedPin(
                        context = context,
                        localPinId = localPinId,
                        displayName = resolvedDisplayName,
                        reason = "empty response"
                    )
                    if (reconciled != null) {
                        DebugSessionLog.append(
                            "UPLOAD",
                            "Upload reconciled after empty response: localPinId=$localPinId cloudPinId=${reconciled.cloudPinId}"
                        )
                        reportCreateUploadTelemetry(
                            context = context,
                            localPinId = localPinId,
                            sessionId = createSessionId,
                            payload = payload,
                            displayName = resolvedDisplayName,
                            integrity = uploadIntegrity,
                            captureTelemetry = captureTelemetry,
                            eventType = telemetryKind.succeededEventType,
                            cloudPinId = reconciled.cloudPinId
                        )
                        return@fold reconciled
                    }
                    PointCloudPayload.updateUploadMetadata(
                        context = context,
                        pinId = localPinId,
                        uploadStatus = UploadStatus.FAILED,
                        displayName = resolvedDisplayName,
                        uploadError = "Backend returned an empty response."
                    )
                    DebugSessionLog.append("UPLOAD", "Upload failed: empty backend response for $localPinId")
                    reportCreateUploadTelemetry(
                        context = context,
                        localPinId = localPinId,
                        sessionId = createSessionId,
                        payload = payload,
                        displayName = resolvedDisplayName,
                        integrity = uploadIntegrity,
                        captureTelemetry = captureTelemetry,
                        eventType = telemetryKind.failedEventType,
                        failureReason = "Backend returned an empty response."
                    )
                    LocalPinUploadResult.Failed(localPinId, "Backend returned an empty response.")
                }
            },
            onFailure = { error ->
                val message = error.message ?: "Upload failed."
                Log.w(TAG, "uploadLocalPin failed localPinId=$localPinId error=$message", error)
                val reconciled = reconcileUploadedPin(
                    context = context,
                    localPinId = localPinId,
                    displayName = resolvedDisplayName,
                    reason = message
                )
                if (reconciled != null) {
                    DebugSessionLog.append(
                        "UPLOAD",
                        "Upload reconciled after failure: localPinId=$localPinId cloudPinId=${reconciled.cloudPinId}"
                    )
                    reportCreateUploadTelemetry(
                        context = context,
                        localPinId = localPinId,
                        sessionId = createSessionId,
                        payload = payload,
                       displayName = resolvedDisplayName,
                       integrity = uploadIntegrity,
                       captureTelemetry = captureTelemetry,
                        eventType = telemetryKind.succeededEventType,
                        cloudPinId = reconciled.cloudPinId
                    )
                    return@fold reconciled
                }
                PointCloudPayload.updateUploadMetadata(
                    context = context,
                    pinId = localPinId,
                    uploadStatus = UploadStatus.FAILED,
                    displayName = resolvedDisplayName,
                    uploadError = message
                )
                DebugSessionLog.append("UPLOAD", "Upload failed: localPinId=$localPinId error=$message")
                reportCreateUploadTelemetry(
                    context = context,
                    localPinId = localPinId,
                    sessionId = createSessionId,
                    payload = payload,
                    displayName = resolvedDisplayName,
                    integrity = uploadIntegrity,
                    captureTelemetry = captureTelemetry,
                    eventType = telemetryKind.failedEventType,
                    failureReason = message
                )
                LocalPinUploadResult.Failed(localPinId, message)
            }
        )
    }

    private fun startCreateTelemetrySession(
        context: Context,
        localPinId: String,
        displayName: String
    ): CreateSessionInfo? {
        if (!hasBackendConfig()) return null
        return runCatching {
            PinRepository(HttpPinApiClient(config)).startCreateSession(
                StartCreateSessionRequest(
                    localPinId = localPinId,
                    displayName = displayName,
                    sdkVersion = SDK_VERSION,
                    appPackageName = context.packageName
                )
            )
        }.fold(
            onSuccess = { session ->
                if (session?.sessionId.isNullOrBlank()) {
                    DebugSessionLog.append("CREATE", "Create telemetry session start returned empty response")
                    null
                } else {
                    Log.i(TAG, "createTelemetry start localPinId=$localPinId sessionId=${session.sessionId}")
                    DebugSessionLog.append(
                        "CREATE",
                        "Create telemetry session started: localPinId=$localPinId sessionId=${session.sessionId}"
                    )
                    session
                }
            },
            onFailure = { error ->
                val message = error.message ?: "create telemetry session failed"
                Log.w(TAG, "createTelemetry start failed localPinId=$localPinId error=$message", error)
                DebugSessionLog.append(
                    "CREATE",
                    "Create telemetry session start failed: localPinId=$localPinId error=$message"
                )
                null
            }
        )
    }

    private fun ensureCreateTelemetrySessionForTelemetry(
        context: Context,
        localPinId: String,
        displayName: String,
        existingSessionId: String?
    ): String? {
        existingSessionId?.takeIf { it.isNotBlank() }?.let { return it }
        val session = startCreateTelemetrySession(
            context = context,
            localPinId = localPinId,
            displayName = displayName
        ) ?: return null
        PointCloudPayload.updateCreateTelemetryMetadata(
            context = context,
            pinId = localPinId,
            createSessionId = session.sessionId,
            createTelemetryStatus = session.status,
            lastCreateTelemetryError = null
        )
        return session.sessionId
    }

    private fun startCreateTelemetryAfterLocalSaveAsync(
        context: Context,
        localPinId: String,
        displayName: String,
        payload: PointCloudPayload,
        integrity: PayloadIntegrityMetadata,
        captureTelemetry: CreateCaptureTelemetry? = null
    ) {
        submitTelemetry("create local save telemetry localPinId=$localPinId") {
            val sessionId = ensureCreateTelemetrySessionForTelemetry(
                context = context,
                localPinId = localPinId,
                displayName = displayName,
                existingSessionId = PointCloudPayload.savedPinInfo(context, localPinId)?.createSessionId
            ) ?: return@submitTelemetry
            reportCreateTelemetryEvent(
                context = context,
                localPinId = localPinId,
                sessionId = sessionId,
                request = payload.createTelemetryEvent(
                    eventType = "payload_built",
                    localPinId = localPinId,
                    displayName = displayName,
                    integrity = integrity,
                    captureTelemetry = captureTelemetry
                )
            )
            reportCreateTelemetryEvent(
                context = context,
                localPinId = localPinId,
                sessionId = sessionId,
                request = payload.createTelemetryEvent(
                    eventType = "local_save_succeeded",
                    localPinId = localPinId,
                    displayName = displayName,
                    integrity = integrity,
                    captureTelemetry = captureTelemetry
                )
            )
        }
    }

    private fun reportCreateLocalSaveFailedAsync(
        context: Context,
        localPinId: String,
        displayName: String,
        payload: PointCloudPayload,
        integrity: PayloadIntegrityMetadata,
        captureTelemetry: CreateCaptureTelemetry? = null,
        failureReason: String
    ) {
        submitTelemetry("create local save failure telemetry localPinId=$localPinId") {
            val sessionId = ensureCreateTelemetrySessionForTelemetry(
                context = context,
                localPinId = localPinId,
                displayName = displayName,
                existingSessionId = null
            ) ?: return@submitTelemetry
            reportCreateTelemetryEvent(
                context = context,
                localPinId = localPinId,
                sessionId = sessionId,
                request = payload.createTelemetryEvent(
                    eventType = "local_save_failed",
                    localPinId = localPinId,
                    displayName = displayName,
                    integrity = integrity,
                    captureTelemetry = captureTelemetry,
                    failureReason = failureReason
                )
            )
        }
    }

    private fun reportCreateUploadTelemetry(
        context: Context,
        localPinId: String,
        sessionId: String?,
        payload: PointCloudPayload,
        displayName: String,
        integrity: PayloadIntegrityMetadata,
        captureTelemetry: CreateCaptureTelemetry? = null,
        eventType: String,
        cloudPinId: String? = null,
        failureReason: String? = null
    ) {
        submitTelemetry("create upload telemetry event=$eventType localPinId=$localPinId") {
            val resolvedSessionId = ensureCreateTelemetrySessionForTelemetry(
                context = context,
                localPinId = localPinId,
                displayName = displayName,
                existingSessionId = sessionId
                    ?: PointCloudPayload.savedPinInfo(context, localPinId)?.createSessionId
            ) ?: return@submitTelemetry
            reportCreateTelemetryEvent(
                context = context,
                localPinId = localPinId,
                sessionId = resolvedSessionId,
                request = payload.createTelemetryEvent(
                    eventType = eventType,
                    localPinId = localPinId,
                    displayName = displayName,
                    integrity = integrity,
                    captureTelemetry = captureTelemetry,
                    cloudPinId = cloudPinId,
                    failureReason = failureReason
                )
            )
        }
    }

    private fun reportCreateTelemetryEvent(
        context: Context,
        localPinId: String,
        sessionId: String,
        request: CreateSessionEventReport
    ) {
        if (!hasBackendConfig()) return
        runCatching {
            PinRepository(HttpPinApiClient(config)).reportCreateSessionEvent(sessionId, request)
        }.fold(
            onSuccess = { report ->
                val status = report?.status
                Log.i(
                    TAG,
                    "createTelemetry event=${request.eventType} localPinId=$localPinId sessionId=$sessionId status=${status ?: "null"}"
                )
                DebugSessionLog.append(
                    "CREATE",
                    "Create telemetry ${request.eventType}: sessionId=$sessionId status=${status ?: "failed"}"
                )
                PointCloudPayload.updateCreateTelemetryMetadata(
                    context = context,
                    pinId = localPinId,
                    createSessionId = sessionId,
                    createTelemetryStatus = status,
                    lastCreateTelemetryError = null
                )
            },
            onFailure = { error ->
                val message = error.message ?: "create telemetry event failed"
                Log.w(
                    TAG,
                    "createTelemetry event failed event=${request.eventType} localPinId=$localPinId sessionId=$sessionId error=$message",
                    error
                )
                DebugSessionLog.append(
                    "CREATE",
                    "Create telemetry ${request.eventType} failed: sessionId=$sessionId error=$message"
                )
                PointCloudPayload.updateCreateTelemetryMetadata(
                    context = context,
                    pinId = localPinId,
                    createSessionId = sessionId,
                    lastCreateTelemetryError = message
                )
            }
        )
    }

    private fun hasBackendConfig(): Boolean =
        config.isCloudConfigured

    private fun ensureEntitlementBlocking(
        context: Context,
        feature: EntitlementFeature
    ): EntitlementCheckResult {
        if (!config.isCloudConfigured) {
            logEntitlement("activation blocked: missing API key for feature=${feature.wireValue}")
            return EntitlementCheckResult.Denied("Nimpu API key is required for SDK activation.")
        }

        val cached = SdkEntitlementStore.load(context)
        val cachedInstalled = cached?.let { installNativeEntitlement(it, "cached") }
        if (cached != null && cachedInstalled == true && nativeHasFeature(feature) && !cached.shouldRefresh()) {
            logEntitlement(
                "using native-verified cached entitlement feature=${feature.wireValue} " +
                    "expiresAt=${cached.expiresAt} refreshAfter=${cached.refreshAfter}"
            )
            return EntitlementCheckResult.Allowed(cached)
        }

        if (cached != null && cachedInstalled == true && cached.shouldRefresh()) {
            logEntitlement(
                "cached entitlement reached refreshAfter; refreshing feature=${feature.wireValue} " +
                    "expiresAt=${cached.expiresAt} refreshAfter=${cached.refreshAfter}"
            )
        } else if (cached != null && cachedInstalled != true) {
            logEntitlement("cached entitlement rejected by SDK core; refreshing feature=${feature.wireValue}")
        } else if (cached != null && !nativeHasFeature(feature)) {
            logEntitlement("cached entitlement missing feature=${feature.wireValue}; refreshing")
        } else {
            logEntitlement("fetching entitlement feature=${feature.wireValue}")
        }

        val refreshed = runCatching {
            PinRepository(HttpPinApiClient(config)).createEntitlement(
                CreateEntitlementRequest(
                    sdkVersion = SDK_VERSION,
                    appPackageName = context.packageName
                )
            )
        }.getOrElse { error ->
            Log.w(TAG, "entitlement refresh failed: ${error.message}", error)
            DebugSessionLog.append("ENTITLEMENT", "refresh failed: ${error.message ?: "unknown error"}")
            null
        }

        if (refreshed != null && installNativeEntitlement(refreshed, "refreshed")) {
            SdkEntitlementStore.save(context, refreshed)
            return if (nativeHasFeature(feature)) {
                logEntitlement(
                    "refresh succeeded and installed in SDK core feature=${feature.wireValue} " +
                        "expiresAt=${refreshed.expiresAt} refreshAfter=${refreshed.refreshAfter} " +
                        "features=${refreshed.features.joinToString()}"
                )
                EntitlementCheckResult.Allowed(refreshed)
            } else {
                logEntitlement("refresh succeeded but feature not licensed feature=${feature.wireValue}")
                EntitlementCheckResult.Denied("SDK entitlement does not include ${feature.wireValue}.")
            }
        }

        if (cached != null && cachedInstalled == true) {
            return if (nativeHasFeature(feature)) {
                logEntitlement(
                    "refresh unavailable; using native-verified cached entitlement feature=${feature.wireValue} " +
                        "expiresAt=${cached.expiresAt}"
                )
                EntitlementCheckResult.Allowed(cached)
            } else {
                logEntitlement("cached entitlement missing feature=${feature.wireValue}")
                EntitlementCheckResult.Denied("Cached SDK entitlement does not include ${feature.wireValue}.")
            }
        }

        logEntitlement("activation required; no valid entitlement for feature=${feature.wireValue}")
        return EntitlementCheckResult.Denied("SDK activation is required. Connect to Nimpu Spatial Cloud with a valid API key.")
    }

    private fun installNativeEntitlement(entitlement: SdkEntitlement, source: String): Boolean {
        if (entitlement.entitlementToken.isBlank()) return false
        return runCatching {
            NativeBridge.installEntitlementToken(entitlement.entitlementToken)
        }.fold(
            onSuccess = { result ->
                if (result.success) {
                    logEntitlement(
                        "$source entitlement accepted by SDK core expiresAtEpochSeconds=${result.expiresAtEpochSeconds}"
                    )
                    true
                } else {
                    logEntitlement("$source entitlement rejected by SDK core: ${result.message}")
                    false
                }
            },
            onFailure = { error ->
                val message = error.message ?: "native entitlement install failed"
                Log.w(TAG, "native entitlement install failed source=$source error=$message", error)
                DebugSessionLog.append("ENTITLEMENT", "$source entitlement install failed: $message")
                false
            }
        )
    }

    private fun nativeHasFeature(feature: EntitlementFeature): Boolean =
        runCatching { NativeBridge.hasEntitlementFeature(feature.wireValue) }
            .getOrElse { error ->
                Log.w(TAG, "native entitlement feature check failed feature=${feature.wireValue}", error)
                false
            }

    private fun entitlementDeniedMessage(result: EntitlementCheckResult): String =
        (result as? EntitlementCheckResult.Denied)?.message ?: "SDK entitlement check failed."

    private fun userFacingEntitlementMessage(result: EntitlementCheckResult.Denied): String =
        when {
            result.message.contains("offline_cache", ignoreCase = true) ->
                "Offline Resolve is not enabled for this API key."
            result.message.contains("activation", ignoreCase = true) ||
                result.message.contains("API key", ignoreCase = true) ->
                "SDK activation is required. Connect to Nimpu Spatial Cloud with a valid API key."
            else -> "SDK activation is required. Connect to Nimpu Spatial Cloud with a valid API key."
        }

    private fun logEntitlement(message: String) {
        Log.i(TAG, "entitlement: $message")
        DebugSessionLog.append("ENTITLEMENT", message)
    }

    private fun newLocalPinId(): String = "lpin_${UUID.randomUUID()}"

    private fun submitTelemetry(label: String, block: () -> Unit) {
        if (!hasBackendConfig()) return
        telemetryExecutor.execute {
            runCatching(block).onFailure { error ->
                val message = error.message ?: "telemetry failed"
                Log.w(TAG, "telemetry task failed label=$label error=$message", error)
                DebugSessionLog.append("TELEMETRY", "Task failed: $label error=$message")
            }
        }
    }

    private fun reportResolvePayloadDownloadedAsync(
        session: CloudResolveSession,
        payloadHash: String?
    ) {
        submitTelemetry("resolve payload_downloaded sessionId=${session.sessionId}") {
            val report = PinRepository(HttpPinApiClient(config)).reportResolveSessionEvent(
                session.sessionId,
                ResolveSessionEventReport(
                    eventType = "payload_downloaded",
                    pinId = session.pinId,
                    payloadHash = payloadHash
                )
            )
            DebugSessionLog.append(
                "RESOLVE",
                "Resolve session payload_downloaded report: sessionId=${session.sessionId} " +
                    "status=${report?.status ?: "failed"} " +
                    "encoding=${session.payloadEncoding} " +
                    "envelopeHash=${PayloadIntegrity.shortHash(session.payloadEncryption?.envelopeHash)}"
            )
        }
    }

    private fun reconcileUploadedPin(
        context: Context,
        localPinId: String,
        displayName: String,
        reason: String
    ): LocalPinUploadResult.Uploaded? {
        Log.i(TAG, "uploadLocalPin reconcile start localPinId=$localPinId reason=$reason")
        return runCatching {
            PinRepository(HttpPinApiClient(config)).list(externalRef = localPinId)
        }.fold(
            onSuccess = { pins ->
                val cloudPinId = pins.firstOrNull()?.cloudPinId
                if (cloudPinId.isNullOrBlank()) {
                    Log.i(TAG, "uploadLocalPin reconcile no cloud match localPinId=$localPinId")
                    null
                } else {
                    Log.i(TAG, "uploadLocalPin reconcile success localPinId=$localPinId cloudPinId=$cloudPinId")
                    PointCloudPayload.updateUploadMetadata(
                        context = context,
                        pinId = localPinId,
                        cloudPinId = cloudPinId,
                        uploadStatus = UploadStatus.UPLOADED,
                        displayName = displayName,
                        uploadError = null
                    )
                    DebugSessionLog.append(
                        "UPLOAD",
                        "Upload reconcile found cloud pin: localPinId=$localPinId cloudPinId=$cloudPinId reason=$reason"
                    )
                    LocalPinUploadResult.Uploaded(localPinId, cloudPinId)
                }
            },
            onFailure = { reconcileError ->
                val message = reconcileError.message ?: "reconcile failed"
                Log.w(TAG, "uploadLocalPin reconcile failed localPinId=$localPinId error=$message", reconcileError)
                DebugSessionLog.append("UPLOAD", "Upload reconcile failed: localPinId=$localPinId error=$message")
                null
            }
        )
    }

    fun newSession(
        mode: NimpuSpatialMode,
        targetPinId: String? = null
    ): NimpuSpatialSession {
        return NimpuSpatialSession(
            mode = mode,
            targetPinId = targetPinId,
            config = config
        )
    }
}

data class NimpuSpatialConfig(
    val apiKey: String? = null,
    val preferredCloudPayloadFormat: String = PayloadFormat.PROTOBUF_V1,
    val geospatialGuidanceMode: GeospatialGuidanceMode = GeospatialGuidanceMode.ENABLED,
    val farDistanceMeters: Float = 200f,
    val approachDistanceMeters: Float = 50f,
    val localResolveTriggerDistanceMeters: Float = 50f,
    val localResolveTriggerAltitudeMeters: Float = 8f
) {
    val isGeospatialGuidanceEnabled: Boolean
        get() = geospatialGuidanceMode == GeospatialGuidanceMode.ENABLED

    internal val effectiveBackendBaseUrl: String
        get() = NimpuSpatialSdk.NIMPU_CLOUD_BASE_URL

    internal val effectiveApiKey: String?
        get() = apiKey?.takeIf { it.isNotBlank() }

    internal val isCloudConfigured: Boolean
        get() = !effectiveApiKey.isNullOrBlank()

    val isCloudEnabled: Boolean
        get() = isCloudConfigured
}

enum class GeospatialGuidanceMode {
    ENABLED,
    DISABLED
}

enum class NimpuSpatialMode {
    CREATE_PIN,
    RESOLVE_PIN
}

class NimpuSpatialSession internal constructor(
    val mode: NimpuSpatialMode,
    val targetPinId: String?,
    val config: NimpuSpatialConfig
) {
    internal fun createPinController(): CreatePinController = CreatePinController(config)

    fun newCreateSession(): CreateSession =
        CreateSession(config = config)

    internal fun resolvePinController(): ResolvePinController = ResolvePinController(config)

    fun newResolveSession(target: ResolveTarget): ResolveSession =
        ResolveSession(payload = target.payload, config = config)
}

internal class CreatePinController internal constructor(
    private val config: NimpuSpatialConfig
) {
    fun buildUploadRequest(
        externalRef: String,
        payload: PointCloudPayload,
        displayName: String? = null
    ): PinUploadRequest {
        return PinUploadRequest(
            externalRef = externalRef,
            displayName = displayName,
            payload = payload,
            configSnapshot = config
        )
    }
}

internal class ResolvePinController internal constructor(
    private val config: NimpuSpatialConfig
) {
    fun evaluateState(input: ResolveHandoffInput): ResolveWorkflowState {
        if (input.localResolveLocked) return ResolveWorkflowState.LOCAL_RESOLVE_LOCKED
        if (input.manualArModeActive) return ResolveWorkflowState.LOCAL_RESOLVE_SEARCHING
        if (!input.coarseAnchorAvailable) return ResolveWorkflowState.IDLE
        if (!input.distanceMeters.isFinite()) return ResolveWorkflowState.COARSE_GEOSPATIAL_AVAILABLE
        if (input.distanceMeters > config.farDistanceMeters) return ResolveWorkflowState.TARGET_TOO_FAR
        if (shouldStartLocalResolve(input)) return ResolveWorkflowState.LOCAL_RESOLVE_SEARCHING
        return if (input.distanceMeters <= config.approachDistanceMeters) {
            ResolveWorkflowState.APPROACHING_TARGET
        } else {
            ResolveWorkflowState.COARSE_GEOSPATIAL_AVAILABLE
        }
    }

    fun shouldStartLocalResolve(input: ResolveHandoffInput): Boolean {
        val altitudeCloseEnough = input.altitudeDeltaMeters?.let {
            kotlin.math.abs(it) <= config.localResolveTriggerAltitudeMeters
        } ?: false
        return input.distanceMeters <= config.localResolveTriggerDistanceMeters ||
            altitudeCloseEnough ||
            input.manualArModeActive
    }
}

data class ResolveHandoffInput(
    val distanceMeters: Float,
    val altitudeDeltaMeters: Float? = null,
    val coarseAnchorAvailable: Boolean,
    val localResolveLocked: Boolean,
    val manualArModeActive: Boolean
)

enum class ResolveWorkflowState {
    IDLE,
    TARGET_TOO_FAR,
    COARSE_GEOSPATIAL_AVAILABLE,
    APPROACHING_TARGET,
    LOCAL_RESOLVE_SEARCHING,
    LOCAL_RESOLVE_LOCKED,
    LOCAL_RESOLVE_FAILED_FALLBACK,
    SESSION_COMPLETE
}

internal data class PinUploadRequest(
    val externalRef: String?,
    val displayName: String?,
    val payload: PointCloudPayload,
    val configSnapshot: NimpuSpatialConfig,
    val payloadFormat: String = configSnapshot.preferredCloudPayloadFormat,
    val payloadEncoding: String = PayloadEncoding.PLAINTEXT,
    val payloadBytes: ByteArray = encodeCloudPayload(payload, payloadFormat),
    val payloadIntegrity: PayloadIntegrityMetadata = computeCloudPayloadIntegrity(payload, payloadFormat, payloadBytes),
    val payloadHash: String = payloadIntegrity.payloadHash,
    val payloadHashAlgorithm: String = PayloadIntegrity.HASH_ALGORITHM,
    val payloadCanonicalization: String = payloadIntegrity.payloadCanonicalization
)

internal data class PinUploadResult(
    val pinId: String,
    val versionId: String,
    val status: String,
    val payloadFormat: String = PayloadFormat.JSON_V1,
    val payloadEncoding: String = PayloadEncoding.PLAINTEXT,
    val payloadHash: String? = null,
    val payloadHashAlgorithm: String? = null,
    val payloadCanonicalization: String? = null
)

internal data class FetchedPinPayload(
    val pinId: String,
    val versionId: String?,
    val payload: PointCloudPayload,
    val payloadFormat: String,
    val payloadEncoding: String,
    val payloadHash: String?,
    val payloadHashAlgorithm: String?,
    val payloadCanonicalization: String?
)

internal data class StartCreateSessionRequest(
    val localPinId: String,
    val displayName: String? = null,
    val sdkVersion: String? = null,
    val appPackageName: String? = null,
    val deviceRef: String? = null
)

internal data class CreateSessionInfo(
    val sessionId: String,
    val status: String
)

internal data class CreateSessionEventReport(
    val eventType: String,
    val localPinId: String? = null,
    val cloudPinId: String? = null,
    val displayName: String? = null,
    val payloadHash: String? = null,
    val payloadHashAlgorithm: String? = null,
    val payloadCanonicalization: String? = null,
    val payloadFormat: String? = null,
    val payloadEncoding: String? = null,
    val pointCount: Int? = null,
    val descriptorCount: Int? = null,
    val descriptorSize: Int? = null,
    val algorithmId: Int? = null,
    val coveredSectors: Int? = null,
    val acceptedSectorMask: Int? = null,
    val attemptedSectorMask: Int? = null,
    val uploadAttemptCount: Int? = null,
    val failureReason: String? = null,
    val sdkVersion: String? = null,
    val appPackageName: String? = null,
    val deviceRef: String? = null
)

internal data class CreateSessionEventReportResult(
    val ok: Boolean,
    val sessionId: String,
    val status: String
)

sealed class LocalPinUploadResult {
    data class Uploaded(
        val localPinId: String,
        val cloudPinId: String
    ) : LocalPinUploadResult()

    data class Failed(
        val localPinId: String,
        val error: String
    ) : LocalPinUploadResult()
}

sealed class CreatePinResult {
    data class SavedLocal(
        val localPinId: String,
        val pointCount: Int
    ) : CreatePinResult()

    data class Uploaded(
        val localPinId: String,
        val cloudPinId: String
    ) : CreatePinResult()

    data class Failed(
        val localPinId: String?,
        val error: String
    ) : CreatePinResult()
}

data class NimpuPin(
    val localPinId: String?,
    val cloudPinId: String?,
    val displayName: String,
    val uploadStatus: PinUploadStatus,
    val uploadError: String?,
    val pointCount: Int,
    val algorithmId: Int,
    val visualProfileLabel: String,
    val createdAt: Long,
    val geospatialMetadata: GeospatialPinMetadata?,
    val payloadHash: String?,
    val payloadHashAlgorithm: String?,
    val payloadCanonicalization: String?,
    val payloadFormat: String = PayloadFormat.JSON_V1,
    val payloadEncoding: String = PayloadEncoding.PLAINTEXT
) {
    val primaryId: String
        get() = localPinId ?: cloudPinId ?: displayName
}

enum class PinUploadStatus {
    LOCAL_ONLY,
    UPLOADING,
    UPLOADED,
    FAILED
}

class ResolveTarget internal constructor(
    val pinId: String,
    val displayName: String,
    internal val payload: PointCloudPayload,
    val geospatialMetadata: GeospatialPinMetadata?,
    val payloadFormat: String,
    val payloadEncoding: String,
    val payloadHash: String?,
    val payloadHashAlgorithm: String?,
    val payloadCanonicalization: String?,
    val cloudResolveSession: CloudResolveSession? = null
)

internal data class StartResolveSessionRequest(
    val pinId: String,
    val deliveryRef: String? = null,
    val deviceRef: String? = null,
    val sdkVersion: String? = null,
    val appPackageName: String? = null
)

data class CloudResolveSession(
    val sessionId: String,
    val pinId: String,
    val versionId: String?,
    val projectId: String?,
    val organizationId: String?,
    val status: String,
    val sessionExpiresAt: String?,
    val payloadDownloadUrl: String,
    val payloadUrlExpiresAt: String?,
    val payloadFormat: String,
    val payloadEncoding: String,
    val payloadHash: String?,
    val payloadHashAlgorithm: String?,
    val payloadCanonicalization: String?,
    val payloadEncryption: PayloadEncryptionInfo? = null
)

data class PayloadEncryptionInfo(
    val algorithm: String,
    val dataKeyBase64: String,
    val nonceBase64: String,
    val aadBase64: String,
    val envelopeHash: String? = null,
    val envelopeHashAlgorithm: String? = null
)

internal data class ResolveSessionPayload(
    val session: CloudResolveSession,
    val payload: PointCloudPayload,
    val payloadBytes: ByteArray
)

internal data class ResolveSessionEventReport(
    val eventType: String,
    val pinId: String,
    val payloadHash: String? = null,
    val attemptCount: Int? = null,
    val bestInlierCount: Int? = null,
    val bestMatchCount: Int? = null,
    val elapsedMs: Int? = null,
    val failureReason: String? = null
)

internal data class ResolveSessionEventReportResult(
    val ok: Boolean,
    val sessionId: String,
    val status: String
)

sealed class CloudPinListResult {
    data class Success(val pins: List<NimpuPin>) : CloudPinListResult()
    data class Failed(val error: String) : CloudPinListResult()
}

sealed class CloudPinFetchResult {
    data class Success(val target: ResolveTarget) : CloudPinFetchResult()
    data class Failed(val cloudPinId: String, val error: String) : CloudPinFetchResult()
}

sealed class PrepareResolveTargetResult {
    data class Success(val target: ResolveTarget) : PrepareResolveTargetResult()
    data class Failed(val pinId: String, val error: String) : PrepareResolveTargetResult()
}

sealed class PrepareResolveTargetProgress {
    data class LoadingLocal(val localPinId: String) : PrepareResolveTargetProgress()
    data class CheckingCloud(val cloudPinId: String) : PrepareResolveTargetProgress()
    data class UsingCachedPayload(val cloudPinId: String) : PrepareResolveTargetProgress()
    data class DownloadingPayload(val cloudPinId: String) : PrepareResolveTargetProgress()
}

private sealed class LocalResolveTargetLoadResult {
    data class Success(val target: ResolveTarget) : LocalResolveTargetLoadResult()
    data class Failed(val message: String) : LocalResolveTargetLoadResult()
}

sealed class CloudPinDeleteResult {
    data class Success(val cloudPinId: String) : CloudPinDeleteResult()
    data class Failed(val cloudPinId: String, val error: String) : CloudPinDeleteResult()
}

private fun PointCloudPayload.SavedPinInfo.toNimpuPin(isLocalRecord: Boolean): NimpuPin {
    val uploadStatus = runCatching { PinUploadStatus.valueOf(uploadStatus) }
        .getOrDefault(PinUploadStatus.LOCAL_ONLY)
    return NimpuPin(
        localPinId = pinId.takeIf { isLocalRecord },
        cloudPinId = cloudPinId,
        displayName = displayLabel,
        uploadStatus = uploadStatus,
        uploadError = uploadError,
        pointCount = pointCount,
        algorithmId = algorithmId,
        visualProfileLabel = PointCloudPayload.visualProfileLabelFromId(algorithmId),
        createdAt = timestamp,
        geospatialMetadata = geospatialMetadata,
        payloadHash = payloadHash,
        payloadHashAlgorithm = payloadHashAlgorithm,
        payloadCanonicalization = payloadCanonicalization,
        payloadFormat = payloadFormat,
        payloadEncoding = payloadEncoding
    )
}

private enum class CreateUploadTelemetryKind(
    val startedEventType: String,
    val succeededEventType: String,
    val failedEventType: String
) {
    INITIAL_UPLOAD(
        startedEventType = "upload_started",
        succeededEventType = "upload_succeeded",
        failedEventType = "upload_failed"
    ),
    RETRY_UPLOAD(
        startedEventType = "retry_upload_started",
        succeededEventType = "retry_upload_succeeded",
        failedEventType = "retry_upload_failed"
    )
}

private fun PointCloudPayload.createTelemetryEvent(
    eventType: String,
    localPinId: String,
    displayName: String,
    integrity: PayloadIntegrityMetadata,
    captureTelemetry: CreateCaptureTelemetry? = null,
    cloudPinId: String? = null,
    failureReason: String? = null
): CreateSessionEventReport =
    CreateSessionEventReport(
        eventType = eventType,
        localPinId = localPinId,
        cloudPinId = cloudPinId,
        displayName = displayName,
        payloadHash = integrity.payloadHash,
        payloadHashAlgorithm = integrity.payloadHashAlgorithm,
        payloadCanonicalization = integrity.payloadCanonicalization,
        payloadFormat = integrity.payloadFormat,
        payloadEncoding = PayloadEncoding.PLAINTEXT,
        pointCount = points3D.size,
        descriptorCount = descriptors.size,
        descriptorSize = descriptorSize,
        algorithmId = algorithmId,
        coveredSectors = captureTelemetry?.coveredSectors ?: captureMetadata.coveredSectors,
        acceptedSectorMask = captureTelemetry?.acceptedSectorMask,
        attemptedSectorMask = captureTelemetry?.attemptedSectorMask,
        uploadAttemptCount = eventType.uploadAttemptCount(),
        failureReason = failureReason
    )

private val PayloadIntegrityMetadata.payloadFormat: String
    get() = when (payloadCanonicalization) {
        PointCloudPayloadProtobuf.CANONICALIZATION -> PayloadFormat.PROTOBUF_V1
        else -> PayloadFormat.JSON_V1
    }

private fun String.uploadAttemptCount(): Int? =
    when (this) {
        "upload_started",
        "upload_succeeded",
        "upload_failed" -> 1
        "retry_upload_started",
        "retry_upload_succeeded",
        "retry_upload_failed" -> 2
        else -> null
    }

private fun encodeCloudPayload(payload: PointCloudPayload, payloadFormat: String): ByteArray =
    when (payloadFormat) {
        PayloadFormat.JSON_V1 -> payload.toJson().toString().toByteArray(Charsets.UTF_8)
        PayloadFormat.PROTOBUF_V1 -> PointCloudPayloadProtobuf.encode(payload)
        else -> throw IllegalArgumentException("Unsupported payload format: $payloadFormat")
    }

private fun computeCloudPayloadIntegrity(
    payload: PointCloudPayload,
    payloadFormat: String,
    payloadBytes: ByteArray = encodeCloudPayload(payload, payloadFormat)
): PayloadIntegrityMetadata =
    when (payloadFormat) {
        PayloadFormat.JSON_V1 -> PayloadIntegrity.compute(payload)
        PayloadFormat.PROTOBUF_V1 -> PointCloudPayloadProtobuf.computeIntegrity(payloadBytes)
        else -> throw IllegalArgumentException("Unsupported payload format: $payloadFormat")
    }
