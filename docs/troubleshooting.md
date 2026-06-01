# Troubleshooting

## Missing API Key

If the app starts but Create/Resolve cannot activate, confirm `local.properties` contains:

```properties
NIMPU_API_KEY=nsk_...
```

Then rebuild the app. Gradle reads this value at build time.

## Invalid Or Revoked API Key

If cloud pin listing, upload, or SDK activation fails:

- confirm the key belongs to the intended portal project
- confirm the key is active in the portal
- generate a new key if the old key was revoked
- rebuild/reinstall the app if the key is baked into `BuildConfig`

## Empty Cloud Pin List

An empty cloud list is a valid state. It usually means the selected portal project does not have
active uploaded pins yet. Create and upload a pin first, or switch to local saved pins if you are
testing offline behavior.

## Geospatial Guidance Not Available

Geospatial guidance depends on ARCore, Google Play services, device support, package/signing
restrictions, location permission, and outdoor localization quality.

Check:

- the app package and signing certificate are configured in Google Cloud
- ARCore/Geospatial APIs are enabled
- camera and fine location permissions are granted
- the device is outdoors or in an area with VPS/geospatial availability

Precise visual resolve can still work without geospatial guidance after a pin is prepared.

## Upload Fails But Local Save Succeeds

The SDK saves locally first. If upload fails because of network or key issues, keep the local pin and
retry upload after connectivity and SDK activation are healthy.

## Share Log

Use the sample app's Share Log when debugging. It should include activation state, cloud
list/upload results, resolve preparation events, and resolve result stats without exposing API keys
or raw pin data.
