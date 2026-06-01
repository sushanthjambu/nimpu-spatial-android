# Getting Started

This guide gets the sample app running and shows the current AAR-based integration path for an existing Android app.

## Requirements

- Android Studio.
- Android device with ARCore support.
- Nimpu Spatial project API key from [spatial.nimpu.in](https://spatial.nimpu.in/).
- Google Cloud ARCore/Geospatial setup if you enable geospatial guidance.

## Run The Sample App

1. Copy `local.properties.example` to `local.properties`.
2. Set your Nimpu project API key:

```properties
NIMPU_API_KEY=nsk_...
NIMPU_ENABLE_GEOSPATIAL_GUIDANCE=true
```

3. Open the repository in Android Studio.
4. Select the `sample` run configuration.
5. Run on a physical ARCore-supported Android device.

The sample consumes the local `:sdk` module, so it can run directly from this repository.

## Configure Geospatial Guidance

The sample package is:

```text
com.example.spatialsdk.sample
```

If `NIMPU_ENABLE_GEOSPATIAL_GUIDANCE=true`, configure ARCore/Geospatial access in your Google Cloud project for the sample package name and the signing certificate used by your build.

Google's setup docs:

- [ARCore Geospatial API overview](https://developers.google.com/ar/develop/geospatial)
- [Enable Geospatial capabilities for Android](https://developers.google.com/ar/develop/java/geospatial/enable)
- [ARCore authorization](https://developers.google.com/ar/develop/authorization)

If you do not configure geospatial access yet, set:

```properties
NIMPU_ENABLE_GEOSPATIAL_GUIDANCE=false
```

Create/Resolve can still use precise visual matching after SDK activation, but coarse geospatial guidance will be skipped.

## Integrate The AAR In An Existing App

Maven publishing is not available yet. For now, download the SDK AAR from a Nimpu Spatial release and place it in your app's `libs/` directory.

```kotlin
dependencies {
    implementation(files("libs/nimpu-spatial-android-0.1.0.aar"))
}
```

Initialize the SDK once:

```kotlin
NimpuSpatialSdk.initialize(
    context = applicationContext,
    config = NimpuSpatialConfig(
        apiKey = BuildConfig.NIMPU_API_KEY,
        geospatialGuidanceMode = GeospatialGuidanceMode.ENABLED
    )
)
```

The host app should provide the Nimpu API key through build-time or app configuration. Do not ask end users to type Nimpu API keys into app UI.

## Next Docs

- [Android Integration](android-integration.md)
- [Cloud Mode](cloud-mode.md)
- [Geospatial Setup](geospatial-setup.md)
- [Diagnostics](diagnostics.md)
