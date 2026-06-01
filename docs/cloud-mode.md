# Cloud Mode

This document explains how Nimpu Spatial uses Nimpu Spatial Cloud for cross-device Create/Resolve.

## What Cloud Mode Supports

Cloud mode supports:

- Uploading created pins to Nimpu Spatial Cloud.
- Listing cloud pins for resolve.
- Preparing a cloud pin for resolve.
- Reusing saved cloud copies when available.
- Retry upload for locally saved pins.
- Soft delete for cloud pins.

## Configuration

Nimpu Spatial Cloud activation requires a project API key from the Nimpu Spatial Developer Portal:

```kotlin
NimpuSpatialSdk.initialize(
    context = applicationContext,
    config = NimpuSpatialConfig(
        apiKey = BuildConfig.NIMPU_API_KEY,
        geospatialGuidanceMode = GeospatialGuidanceMode.ENABLED
    )
)
```

If the API key is missing, invalid, or revoked, cloud APIs return a failure result that the host app
can show or recover from.

## Create Pin Upload Flow

The SDK always saves locally first. When cloud upload is requested, it then attempts upload.

```kotlin
createSession.savePin(
    context = context,
    anchorPose = anchor.pose,
    geospatialMetadata = currentGeospatialMetadata,
    displayName = displayName,
    uploadToCloud = true
) { result ->
    when (result) {
        is CreatePinResult.SavedLocal -> {
            // Local save succeeded, but upload did not complete.
            // Show Retry Upload.
        }
        is CreatePinResult.Uploaded -> {
            // Local save and cloud upload succeeded.
        }
        is CreatePinResult.Failed -> {
            // Show recoverable error.
        }
    }
}
```

## Cloud Pin List

List active cloud pins:

```kotlin
NimpuSpatialSdk.listCloudPins { result ->
    when (result) {
        is CloudPinListResult.Success -> {
            val pins = result.pins
            // Show pins using NimpuPin.displayName.
        }
        is CloudPinListResult.Failed -> {
            // Fall back to local pins if available.
        }
    }
}
```

Cloud pin list results are intended for app-owned picker UI. The SDK does not own the picker dialog.

## Resolve Session Flow

Prepare a selected cloud pin before starting Resolve:

```kotlin
NimpuSpatialSdk.prepareResolveTarget(
    context = context,
    pin = selectedCloudPin,
    onProgress = { progress ->
        when (progress) {
            is PrepareResolveTargetProgress.CheckingCloud -> {
                // Show "Checking cloud pin..."
            }
            is PrepareResolveTargetProgress.UsingCachedPayload -> {
                // Show "Using saved cloud copy..."
            }
            is PrepareResolveTargetProgress.DownloadingPayload -> {
                // Show "Downloading pin..."
            }
            is PrepareResolveTargetProgress.LoadingLocal -> {
                // Show "Loading saved pin..."
            }
        }
    }
) { result ->
    when (result) {
        is PrepareResolveTargetResult.Success -> {
            val sdkSession = NimpuSpatialSdk.newSession(NimpuSpatialMode.RESOLVE_PIN)
            val resolveSession = sdkSession.newResolveSession(result.target)
        }
        is PrepareResolveTargetResult.Failed -> {
            // Show error or fall back to local pins.
        }
    }
}
```

Host apps should use `prepareResolveTarget(...)` instead of trying to fetch or inspect pin data
directly.

## Retry Upload Flow

Retry upload is for local pins whose upload failed or was skipped:

```kotlin
NimpuSpatialSdk.uploadLocalPin(
    context = context,
    localPinId = localPinId,
    displayName = editedDisplayName
) { result ->
    when (result) {
        is LocalPinUploadResult.Uploaded -> {
            // Update UI to Uploaded.
        }
        is LocalPinUploadResult.Failed -> {
            // Keep retry available.
        }
    }
}
```

The host app should own the retry dialog. The SDK accepts the final display name and local pin id.

## Soft Delete

Local-only delete:

```kotlin
NimpuSpatialSdk.deleteLocalPin(context, localPinId)
```

Cloud soft delete:

```kotlin
NimpuSpatialSdk.deleteCloudPin(cloudPinId) { result ->
    // Remove from UI on success.
}
```

Deleting local + cloud is a host-app workflow:

1. Delete the local record.
2. Call cloud delete if the pin has a cloud id.
3. Remove or refresh the item in the app UI.

Cloud delete marks the cloud pin deleted so it does not appear in active cloud lists.

## Security And Logs

Do not log API keys, full signed URLs, raw pin data, or user PII. Share Log output should only
include support-safe summaries such as high-level operation state, cloud list/upload results, and
resolve success/failure status.
