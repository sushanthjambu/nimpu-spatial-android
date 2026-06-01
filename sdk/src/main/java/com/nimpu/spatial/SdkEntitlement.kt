package com.nimpu.spatial.sdk

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

internal enum class EntitlementFeature(val wireValue: String) {
    CLOUD_CREATE("cloud_create"),
    CLOUD_RESOLVE("cloud_resolve"),
    OFFLINE_CACHE("offline_cache"),
    OFFLINE_CLOUD_CREATE("offline_cloud_create"),
    STANDALONE_LOCAL_CREATE("standalone_local_create"),
    STANDALONE_LOCAL_RESOLVE("standalone_local_resolve");
}

internal data class CreateEntitlementRequest(
    val sdkVersion: String,
    val platform: String = "android",
    val appPackageName: String? = null,
    val appVersion: String? = null,
    val deviceInstallId: String? = null
)

internal data class SdkEntitlement(
    val entitlementToken: String,
    val expiresAt: String,
    val refreshAfter: String,
    val features: List<String>
) {
    val expiresAtMillis: Long
        get() = parseIsoMillis(expiresAt)

    val refreshAfterMillis: Long
        get() = parseIsoMillis(refreshAfter)

    fun isValid(nowMillis: Long = System.currentTimeMillis()): Boolean =
        entitlementToken.isNotBlank() && expiresAtMillis > nowMillis

    fun shouldRefresh(nowMillis: Long = System.currentTimeMillis()): Boolean =
        refreshAfterMillis <= nowMillis

    fun hasFeature(feature: EntitlementFeature): Boolean =
        features.contains(feature.wireValue)
}

internal sealed class EntitlementCheckResult {
    data class Allowed(val entitlement: SdkEntitlement) : EntitlementCheckResult()
    data class Denied(val message: String) : EntitlementCheckResult()
}

internal object SdkEntitlementStore {
    private const val PREFS = "nimpu_spatial_entitlement"
    private const val KEY_TOKEN = "token"
    private const val KEY_EXPIRES_AT = "expiresAt"
    private const val KEY_REFRESH_AFTER = "refreshAfter"
    private const val KEY_FEATURES = "features"

    fun load(context: Context): SdkEntitlement? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val token = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() } ?: return null
        return SdkEntitlement(
            entitlementToken = token,
            expiresAt = prefs.getString(KEY_EXPIRES_AT, null).orEmpty(),
            refreshAfter = prefs.getString(KEY_REFRESH_AFTER, null).orEmpty(),
            features = prefs.getString(KEY_FEATURES, null)
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                .orEmpty()
        )
    }

    fun save(context: Context, entitlement: SdkEntitlement) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TOKEN, entitlement.entitlementToken)
            .putString(KEY_EXPIRES_AT, entitlement.expiresAt)
            .putString(KEY_REFRESH_AFTER, entitlement.refreshAfter)
            .putString(KEY_FEATURES, entitlement.features.joinToString(","))
            .apply()
    }
}

private fun parseIsoMillis(raw: String): Long {
    if (raw.isBlank()) return 0L
    val normalized = raw.replace(Regex("\\.\\d+"), "").replace("+00:00", "Z")
    return runCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.parse(normalized)?.time ?: 0L
    }.getOrDefault(0L)
}
