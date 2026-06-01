package com.example.spatialsdk.sample

import com.nimpu.spatial.sdk.NimpuPin

object LocalPinDisplayGrouper {
    fun group(pins: List<NimpuPin>): List<NimpuPin> {
        return pins
            .groupBy(::groupKey)
            .values
            .map(::representativePin)
            .sortedBy { it.createdAt }
    }

    private fun groupKey(pin: NimpuPin): String {
        val cloudPinId = pin.cloudPinId?.takeIf { it.isNotBlank() }
        if (cloudPinId != null) return "cloud:$cloudPinId"

        val localPinId = pin.localPinId?.takeIf { it.isNotBlank() }
        if (localPinId != null) return "local:$localPinId"

        return "pin:${pin.primaryId}"
    }

    private fun representativePin(pins: List<NimpuPin>): NimpuPin {
        return pins.minWith(
            compareBy<NimpuPin> { displayPriority(it) }
                .thenBy { it.createdAt }
        )
    }

    private fun displayPriority(pin: NimpuPin): Int {
        val cloudPinId = pin.cloudPinId?.takeIf { it.isNotBlank() }
        val localPinId = pin.localPinId?.takeIf { it.isNotBlank() }
        return when {
            cloudPinId != null && localPinId != null && localPinId != cloudPinId -> 0
            cloudPinId != null -> 1
            else -> 2
        }
    }
}
