# Geospatial Setup

This document explains how Nimpu Spatial uses ARCore geospatial guidance and how host apps can
configure it.

## What Geospatial Guidance Does

Geospatial guidance is the coarse outdoor guidance layer. It helps a user approach the saved pin
location before precise visual resolve begins.

Create/Resolve can still work with geospatial guidance disabled after Nimpu Spatial Cloud activation.

## Recommended Production Setup

Production host apps should configure ARCore/Geospatial access in their own Google Cloud project and
Android app configuration.

Recommended production shape:

- Host app owns its Google Cloud project.
- Host app enables the required ARCore/Geospatial APIs.
- Host app configures Android package name and signing certificate restrictions.
- Host app requests location permissions only when geospatial guidance is enabled.
- Nimpu SDK uses ARCore from inside the host app.

Use normal Android/Google Cloud configuration and build-time app configuration for keys.

## Sample App Setup

The sample app reads settings from `local.properties` or environment variables:

```properties
NIMPU_API_KEY=nsk_...
NIMPU_ENABLE_GEOSPATIAL_GUIDANCE=true
```

The sample app converts that into SDK configuration:

```kotlin
NimpuSpatialSdk.initialize(
    context = applicationContext,
    config = NimpuSpatialConfig(
        apiKey = BuildConfig.NIMPU_API_KEY.takeIf { it.isNotBlank() },
        geospatialGuidanceMode = if (BuildConfig.NIMPU_ENABLE_GEOSPATIAL_GUIDANCE) {
            GeospatialGuidanceMode.ENABLED
        } else {
            GeospatialGuidanceMode.DISABLED
        }
    )
)
```

`local.properties` is intended for local developer machines and should not be committed.

The sample package name is:

```text
com.example.spatialsdk.sample
```

Configure this package and your signing certificate in Google Cloud when running the unmodified
sample with geospatial guidance enabled. If you rename the sample package, update the Google Cloud
configuration to match.

Google's setup docs:

- [ARCore Geospatial API overview](https://developers.google.com/ar/develop/geospatial)
- [Enable Geospatial capabilities for Android](https://developers.google.com/ar/develop/java/geospatial/enable)
- [ARCore authorization](https://developers.google.com/ar/develop/authorization)

## Geospatial Enabled

Use this when the app should provide coarse guidance from saved latitude/longitude/altitude metadata:

```kotlin
NimpuSpatialSdk.initialize(
    context = applicationContext,
    config = NimpuSpatialConfig(
        apiKey = BuildConfig.NIMPU_API_KEY,
        geospatialGuidanceMode = GeospatialGuidanceMode.ENABLED
    )
)
```

When enabled:

- Create Pin may store geospatial metadata when ARCore Earth tracking is available.
- Resolve Pin may create a coarse geospatial anchor before precise visual resolve.
- The app should request camera and fine location permissions.
- The app should handle cases where Earth tracking is not yet available.

## Geospatial Disabled

Use this when the app should use Create/Resolve without coarse geospatial guidance:

```kotlin
NimpuSpatialSdk.initialize(
    context = applicationContext,
    config = NimpuSpatialConfig(
        apiKey = BuildConfig.NIMPU_API_KEY,
        geospatialGuidanceMode = GeospatialGuidanceMode.DISABLED
    )
)
```

When disabled:

- Create Pin still captures the saved place.
- Resolve Pin still performs precise visual resolve.
- Cloud upload/download can still be used when a Nimpu API key is configured.
- Coarse outdoor guidance is skipped.
- Location permission is not needed for Nimpu geospatial guidance.

## Host App Responsibilities

The host app owns:

- ARCore session creation and lifecycle.
- Camera permission.
- Fine location permission when geospatial guidance is enabled.
- Handling ARCore install/update prompts.
- Rendering UI for Earth/geospatial state.
- Deciding whether geospatial guidance is appropriate for the product flow.

The SDK owns:

- `GeospatialGuidanceMode`.
- Geospatial metadata state models.
- Create/Resolve diagnostics that describe geospatial availability and accuracy.
- Coarse guidance state used by SDK guidance views or custom host UI.

## Failure And Fallback Behavior

Host apps should expect geospatial guidance to be unavailable sometimes:

- User denied location permission.
- ARCore session does not support geospatial mode.
- Earth tracking is not yet active.
- VPS/geospatial localization is weak.
- Pin does not include geospatial metadata.

In these cases, the app can still allow precise search when a local or cloud pin has been prepared.
Geospatial guidance improves approach, but precise visual resolve is the final pin lock.
