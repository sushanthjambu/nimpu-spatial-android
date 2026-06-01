# Diagnostics And Debug Logging

This document explains SDK diagnostics and how host apps can build a Share Log equivalent.

## Diagnostics Goals

Diagnostics should help integration developers understand:

- Create scan readiness.
- Sector coverage.
- Upload status.
- Cloud resolve preparation status.
- Resolve attempt status.
- Geospatial accuracy state.

Diagnostics are intended for integration/debugging. They should not expose raw pin data, API keys, or
private user information.

## Create Diagnostics

Create state is available from `CreateSession`:

```kotlin
createSession.observeCreateState { state ->
    // state.phase
    // state.progressMessage
    // state.instructionMessage
    // state.coveredSectors / state.totalSectors
    // state.canSave
    // state.diagnostics
}
```

Important Create models:

- `CreatePinState`: user-facing Create phase, scan progress, instructions, sector masks, and save
  eligibility.
- `CreateDiagnostics`: optional details for debug panels and logs.
- `GeospatialAccuracy`: geospatial status and accuracy text when geospatial guidance is enabled.

Host apps should render `progressMessage` and `instructionMessage` separately so progress does not
visually flicker when instruction text changes.

## Resolve Diagnostics

Resolve state is available from `ResolveSession`:

```kotlin
resolveSession.observeResolveState { state ->
    // state.phase
    // state.message
    // state.earthStatus
    // state.distanceMeters / state.altitudeDeltaMeters
    // state.preciseSearchActive
    // state.showTryAgain / state.showStartPreciseSearch
    // state.diagnostics
}
```

Resolve result stats are available from `observeResolveResult(...)`:

```kotlin
resolveSession.observeResolveResult { result ->
    when (result) {
        is ResolveEngineResult.Resolved -> {
            val stats = result.stats
            // stats.attemptCount
            // stats.bestInlierCount
            // stats.bestMatchCount
            // stats.elapsedMs
        }
        is ResolveEngineResult.Failed -> {
            val reason = result.reason
            val stats = result.stats
        }
    }
}
```

Important Resolve models:

- `ResolvePinState`: user-facing Resolve phase, coarse guidance state, action visibility, and
  diagnostics.
- `ResolveDiagnostics`: geospatial distance/altitude and resolve metadata.
- `ResolveGuidanceIndicatorState`: off-screen/on-screen guidance indicator position and rotation.
- `ResolveEngineResult`: precise visual resolve success/failure.

## Share Log Equivalent

The sample app uses `DebugSessionLog` to collect a readable support log. Host apps can build an
equivalent log by collecting:

- SDK initialization/activation state.
- Geospatial enabled or disabled state.
- Create state transitions.
- Create sector coverage summaries.
- Create save result: local id, cloud id if uploaded, and point count.
- Upload/retry status.
- Cloud pin list success/failure and count.
- Resolve target preparation result.
- Resolve phase transitions.
- Resolve success/failure stats.
- Geospatial status summaries.

Keep logs event-based. Avoid logging every frame.

## Sensitive Data

Do not log:

- Raw pin data.
- Full signed URLs.
- API keys.
- Private app credentials.
- User names, phone numbers, delivery addresses, or other personal information.

Safe support-log summaries:

- Operation name and success/failure state.
- Local pin id or cloud pin id when needed for support.
- Geospatial status summaries.
- Covered sector count and resolve attempt stats.
- Short, support-safe identifiers shown by the SDK.

## Error States

Common Create errors:

- ARCore camera image unavailable.
- No usable scan captured.
- Local save failed.
- Cloud upload failed.

Common Resolve errors:

- Cloud pin list failed.
- SDK activation failed.
- Resolve preparation failed.
- Local pin missing.
- Precise resolve failed.
- Geospatial tracking unavailable.

Recommended handling:

- Show locally saved pins when cloud list fails.
- Keep retry upload available after upload failure.
- Allow Restart Search after precise resolve failure.
- Keep security-sensitive failures visible in support logs without exposing raw values.
