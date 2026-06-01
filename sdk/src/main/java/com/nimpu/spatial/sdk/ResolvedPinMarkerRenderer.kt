package com.nimpu.spatial.sdk

import com.google.ar.core.Pose

/**
 * Minimal rendering boundary for a resolved AR pin marker.
 *
 * The SDK provides [DefaultResolvedPinMarkerRenderer] as a safe built-in marker. Host apps that own
 * their AR rendering pipeline may provide their own implementation and handle asset loading,
 * textures, animation, memory limits, and failure recovery themselves.
 */
interface ResolvedPinMarkerRenderer {
    /**
     * Initialize GL resources. Called on the GL thread.
     */
    fun init()

    /**
     * Draw the marker at an ARCore pose. Called on the GL thread.
     */
    fun draw(pose: Pose, viewMatrix: FloatArray, projectionMatrix: FloatArray)

    /**
     * Draw the marker at a 4x4 model matrix. Called on the GL thread.
     */
    fun drawWithMatrix(modelMatrix: FloatArray, viewMatrix: FloatArray, projectionMatrix: FloatArray)

    /**
     * Release GL resources owned by this renderer. Called on the GL thread when used.
     */
    fun release() = Unit
}
