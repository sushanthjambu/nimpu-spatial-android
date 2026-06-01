# Nimpu Spatial Android Integration

This guide shows how to integrate Nimpu Spatial Android into a host app.

## SDK Responsibilities

The SDK owns:

- Create Pin session state.
- Resolve Pin session state.
- Spatial pin creation and precise visual resolve.
- Nimpu Spatial Cloud upload, list, prepare, retry upload, and delete APIs.
- SDK-owned spatial UI helpers such as create coverage and resolve guidance views.
- Diagnostics state and debug logging hooks.

## Host App Responsibilities

The host app owns:

- App layout and navigation.
- User-facing dialogs.
- Pin naming UI.
- Pin selection UI.
- Retry upload UI.
- ARCore session lifecycle integration.
- Permission prompts.
- Product workflow decisions such as delivery, order, or session access.

## Basic Setup

For an existing app, place the SDK AAR in your app module's `libs/` directory:

```kotlin
dependencies {
    implementation(files("libs/nimpu-spatial-android-0.1.0.aar"))
}
```

Initialize the SDK once during app startup or before starting a Create/Resolve flow:

```kotlin
NimpuSpatialSdk.initialize(
    context = applicationContext,
    config = NimpuSpatialConfig(
        apiKey = BuildConfig.NIMPU_API_KEY,
        geospatialGuidanceMode = GeospatialGuidanceMode.ENABLED
    )
)
```

Create a project API key in the Nimpu Spatial Developer Portal and provide it through build-time or
app configuration:

```properties
NIMPU_API_KEY=nsk_...
```

## Basic Session Flow

Create and Resolve flows start from `NimpuSpatialSession`.

```kotlin
val sdkSession = NimpuSpatialSdk.newSession(NimpuSpatialMode.CREATE_PIN)
val createSession = sdkSession.newCreateSession()
```

```kotlin
val sdkSession = NimpuSpatialSdk.newSession(NimpuSpatialMode.RESOLVE_PIN)
val resolveSession = sdkSession.newResolveSession(resolveTarget)
```

## Create Pin Flow

The host app is responsible for the AR screen and Save dialog. The SDK owns scan state, diagnostics,
pin creation, local save, and optional cloud upload.

Typical flow:

1. Host app starts an ARCore session and lets the user place a pin anchor.
2. Host app creates a `CreateSession`.
3. Host app calls `startGuidedScan(...)` when guided scanning begins.
4. Host app passes AR frames to `processFrame(...)`.
5. Host app observes `CreatePinState` and renders UI.
6. Host app asks the user for a display name.
7. Host app calls `savePin(...)`.
8. SDK saves locally first, then uploads to cloud when configured.

Relevant SDK hooks:

```kotlin
createSession.observeCreateState { state ->
    // Render progress, instruction text, save eligibility, and sector state.
}

createSession.observeCreateDiagnostics { diagnostics ->
    // Optional debug panel or Share Log source.
}

createSession.attachCreateCoverageView(createCoverageView)
```

Save example:

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
            // Local save succeeded; upload was skipped or unavailable.
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

## Resolve Pin Flow

The host app owns the pin picker UI. The SDK owns cloud target preparation, local pin loading,
precise visual resolve, resolve state, guidance state, and result callbacks.

Typical cloud flow:

1. Host app calls `NimpuSpatialSdk.listCloudPins(...)`.
2. Host app shows a picker using `NimpuPin.displayName`.
3. Host app calls `NimpuSpatialSdk.prepareResolveTarget(...)`.
4. Host app creates a `ResolveSession`.
5. Host app passes camera frames to `resolveSession.processFrame(...)`.
6. Host app observes resolve state/guidance/result.
7. Host app creates/render anchors from successful resolve results.

Cloud target preparation:

```kotlin
NimpuSpatialSdk.prepareResolveTarget(
    context = context,
    pin = selectedPin,
    onProgress = { progress ->
        when (progress) {
            is PrepareResolveTargetProgress.LoadingLocal -> {
                // Show "Loading saved pin..."
            }
            is PrepareResolveTargetProgress.CheckingCloud -> {
                // Show "Checking cloud pin..."
            }
            is PrepareResolveTargetProgress.UsingCachedPayload -> {
                // Show "Using saved cloud copy..."
            }
            is PrepareResolveTargetProgress.DownloadingPayload -> {
                // Show "Downloading pin..."
            }
        }
    }
) { result ->
    when (result) {
        is PrepareResolveTargetResult.Success -> {
            val resolveSession = sdkSession.newResolveSession(result.target)
        }
        is PrepareResolveTargetResult.Failed -> {
            // Fall back to local pins or show an error.
        }
    }
}
```

The progress callback is optional. Use it when the host app wants accurate Resolve preparation UI.

Resolve state and guidance:

```kotlin
resolveSession.observeResolveState { state ->
    // Render status text, action buttons, and debug panels.
}

resolveSession.attachResolveGuidanceView(resolveGuidanceView)

resolveSession.observeResolveResult { result ->
    when (result) {
        is ResolveEngineResult.Resolved -> {
            // Host app creates an ARCore anchor from result.pose.
        }
        is ResolveEngineResult.Failed -> {
            // Host app decides whether to show Restart / Start Precise Search.
        }
    }
}
```

The host app may trigger precise search directly:

```kotlin
resolveSession.startPreciseSearch()
resolveSession.restartPreciseSearch()
resolveSession.stopPreciseSearch()
```

## Local Pin Listing And Retry Upload

Local pins:

```kotlin
val localPins: List<NimpuPin> = NimpuSpatialSdk.listLocalPins(context)
```

Retry upload:

```kotlin
NimpuSpatialSdk.uploadLocalPin(
    context = context,
    localPinId = requireNotNull(pin.localPinId),
    displayName = editedDisplayName
) { result ->
    // Handle uploaded or failed.
}
```

Delete:

```kotlin
NimpuSpatialSdk.deleteLocalPin(context, localPinId)
NimpuSpatialSdk.deleteCloudPin(cloudPinId) { result ->
    // Cloud delete is a soft delete on Nimpu Spatial Cloud.
}
```

Callback APIs and suspend APIs are both available for cloud list, prepare, upload, and delete
operations. UI-driven apps can use callbacks; coroutine-based apps can use the suspend variants.

## Supported Modes

- Nimpu Spatial Cloud activated mode with project API key.
- Geospatial guidance enabled.
- Geospatial guidance disabled.

See:

- [Getting Started](getting-started.md)
- [Geospatial Setup](geospatial-setup.md)
- [Cloud Mode](cloud-mode.md)
- [Diagnostics](diagnostics.md)

## Host App UI Ownership

The SDK intentionally does not own:

- Save Pin naming dialog.
- Retry Upload naming dialog.
- Resolve Pin picker dialog.
- Toasts, navigation, or screen transitions.
- Delivery or app-specific workflow rules.

The SDK does provide state and helper views for the spatial parts that are easy to get wrong:

- `CreatePinCoverageView`
- `ResolveGuidanceView`
- `CreatePinState`
- `ResolvePinState`
- `ResolveGuidanceIndicatorState`

Apps can either use the SDK helper views or render custom UI from the same state.

## AR Marker Rendering

The SDK default resolved-pin marker is a lightweight red map-pin-style 3D marker. It is intended to
be readable at typical door and room distances while keeping the AR render path cheap.

Host apps may replace the marker renderer in their own AR screen by implementing
`ResolvedPinMarkerRenderer`. The SDK does not load arbitrary 3D assets for the host app; custom
asset loading, GLTF parsing, textures, animation, memory limits, and graceful fallback behavior stay
app-owned.

```kotlin
class MyMarkerRenderer : ResolvedPinMarkerRenderer {
    override fun init() {
        // Load app-owned GL resources on the GL thread.
    }

    override fun draw(
        pose: Pose,
        viewMatrix: FloatArray,
        projectionMatrix: FloatArray
    ) {
        // Render the app-owned marker at the resolved pose.
    }

    override fun drawWithMatrix(
        modelMatrix: FloatArray,
        viewMatrix: FloatArray,
        projectionMatrix: FloatArray
    ) {
        // Render the app-owned marker at a prepared model matrix.
    }

    override fun release() {
        // Release app-owned GL resources on the GL thread.
    }
}
```

Marker assets should stay small and predictable:

- Prefer simple geometry over high-poly models.
- Keep textures small, or avoid textures when flat colors are enough.
- Avoid expensive transparency, particle effects, and continuous animation unless profiled on target
  devices.
- Keep the marker large enough to see, but not so large that it hides the real door, room feature,
  or surface the user needs to inspect.

Heavy 3D markers can reduce AR frame rate and make Create/Resolve interactions feel less stable.
The marker is only an on-screen render; it does not block the physical camera feed or change precise
visual resolve, but slower rendering can still hurt the overall user experience.

## Error Handling Principles

Host apps should treat most errors as recoverable.

Common recovery paths:

- If cloud pin listing fails, show locally saved pins.
- If upload fails after local save, show Retry Upload.
- If precise resolve fails, allow Restart Search.
- If geospatial tracking is unavailable, allow precise search when a local or cloud pin is prepared.

## Packaging Note

Maven publishing is not available yet. Use the AAR integration path documented above until public
Maven distribution is ready.
