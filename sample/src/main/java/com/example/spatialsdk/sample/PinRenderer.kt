package com.example.spatialsdk.sample

import android.opengl.GLES20
import android.opengl.Matrix
import com.google.ar.core.Pose
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * Renders an asymmetric 3D Arrow pin at the given ARCore Pose.
 *
 * Shape: a box-based arrow body pointing in the -Z direction (ARCore's "forward"),
 * with a colored pyramid nose at the front and a flat base at the back.
 * This makes Yaw rotation trivially verifiable: the nose should always point
 * toward the direction the camera was facing when the pin was created.
 *
 * ## Color coding (for debugging rotational alignment)
 *   - NOSE (front pyramid)  : Bright cyan  [0.0, 1.0, 1.0] — points "forward"
 *   - BODY (shaft)          : Coral red    [1.0, 0.3, 0.3]
 *   - BACK cap              : Orange       [1.0, 0.6, 0.1]
 *   - LEFT face             : Slightly darker red (ambient contrast)
 *   - RIGHT face            : Slightly lighter red (ambient contrast)
 *
 * Self-contained: compiles its own shader program and manages its own VBO.
 * Call [init] once on the GL thread, then [draw] every frame when an anchor exists.
 */
class PinRenderer {

    private var program = 0
    private var uMvpMatrixHandle = 0
    private var uColorHandle = 0
    private var aPositionHandle = 0

    // Buffers — one per colored section so we can call glUniform4fv per segment
    // We draw the arrow in three draw calls: nose, body, back cap.

    // ── Arrow geometry ─────────────────────────────────────────────────
    //
    // The arrow sits upright, centered at the anchor (+Y up, -Z forward).
    //
    //  Back cap (orange)    Body shaft (red)        Nose pyramid (cyan)
    //  [back]──────────────[body box]──────────────▷ [nose apex]
    //
    // Shaft: a rectangular box, 8 cm tall, 3 cm wide/deep, centered at y=0.04
    // Nose:  a square pyramid whose base is the front face of the shaft, apex 4 cm forward
    // Base:  a flat quad capping the back face of the shaft
    //
    // All in meters. Arrow points in -Z (ARCore world forward when created).

    // Shaft half-dimensions
    private val SW = 0.015f  // half-width  (X)
    private val SD = 0.015f  // half-depth  (Z — shaft extent from y-axis center)
    private val SY0 = 0.010f // shaft bottom y
    private val SY1 = 0.070f // shaft top y
    private val SZF = -0.030f // front face Z (toward viewer / -Z world)
    private val SZB = +0.030f // back face Z

    // Nose apex
    private val NAZ = -0.065f // nose apex Z (further forward)
    private val NAY = (SY0 + SY1) / 2f // apex at mid-height

    // ── BODY vertices (8 corners of the shaft box) ─────────────────────
    //   Indices 0-7
    private val BODY_VERTS = floatArrayOf(
        // front-bottom-left, front-bottom-right, front-top-right, front-top-left
        -SW, SY0, SZF,   SW, SY0, SZF,   SW, SY1, SZF,   -SW, SY1, SZF,
        // back-bottom-left,  back-bottom-right,  back-top-right,  back-top-left
        -SW, SY0, SZB,   SW, SY0, SZB,   SW, SY1, SZB,   -SW, SY1, SZB
    )
    private val BODY_INDICES = shortArrayOf(
        // Front face
        0, 1, 2,  0, 2, 3,
        // Right face
        1, 5, 6,  1, 6, 2,
        // Back face
        5, 4, 7,  5, 7, 6,
        // Left face
        4, 0, 3,  4, 3, 7,
        // Top face
        3, 2, 6,  3, 6, 7,
        // Bottom face
        4, 5, 1,  4, 1, 0
    )

    // ── NOSE vertices ──────────────────────────────────────────────────
    //   Base = front face of shaft; apex = (0, NAY, NAZ)
    //   Base corners: front-bottom-left, front-bottom-right, front-top-right, front-top-left
    //   Apex index 4
    private val NOSE_VERTS = floatArrayOf(
        -SW, SY0, SZF,   SW, SY0, SZF,   SW, SY1, SZF,   -SW, SY1, SZF,
        0f,  NAY, NAZ     // apex
    )
    private val NOSE_INDICES = shortArrayOf(
        // 4 triangular faces of the pyramid
        0, 1, 4,
        1, 2, 4,
        2, 3, 4,
        3, 0, 4
    )

    // ── BACK CAP vertices ──────────────────────────────────────────────
    //   Just the 4 corners of the back face
    private val BACK_VERTS = floatArrayOf(
        -SW, SY0, SZB,   SW, SY0, SZB,   SW, SY1, SZB,   -SW, SY1, SZB
    )
    private val BACK_INDICES = shortArrayOf(
        0, 1, 2,  0, 2, 3
    )

    // ── Colors ─────────────────────────────────────────────────────────
    private val COLOR_NOSE = floatArrayOf(0.0f, 1.0f, 1.0f, 1.0f)  // Cyan
    private val COLOR_BODY = floatArrayOf(1.0f, 0.3f, 0.3f, 1.0f)  // Coral red
    private val COLOR_BACK = floatArrayOf(1.0f, 0.6f, 0.1f, 1.0f)  // Orange

    // ── GL resources ───────────────────────────────────────────────────
    private lateinit var bodyVertBuf: FloatBuffer
    private lateinit var bodyIdxBuf: ShortBuffer
    private lateinit var noseVertBuf: FloatBuffer
    private lateinit var noseIdxBuf: ShortBuffer
    private lateinit var backVertBuf: FloatBuffer
    private lateinit var backIdxBuf: ShortBuffer

    // ── Shaders ─────────────────────────────────────────────────────────
    private val VERT_SRC = """
        uniform mat4 uMvpMatrix;
        attribute vec4 aPosition;
        void main() {
            gl_Position = uMvpMatrix * aPosition;
        }
    """.trimIndent()

    private val FRAG_SRC = """
        precision mediump float;
        uniform vec4 uColor;
        void main() {
            gl_FragColor = uColor;
        }
    """.trimIndent()

    // ── Public API ──────────────────────────────────────────────────────

    /** Compile shaders and upload geometry. Call once on GL thread. */
    fun init() {
        bodyVertBuf = makeFloatBuf(BODY_VERTS)
        bodyIdxBuf  = makeShortBuf(BODY_INDICES)
        noseVertBuf = makeFloatBuf(NOSE_VERTS)
        noseIdxBuf  = makeShortBuf(NOSE_INDICES)
        backVertBuf = makeFloatBuf(BACK_VERTS)
        backIdxBuf  = makeShortBuf(BACK_INDICES)

        val vert = compileShader(GLES20.GL_VERTEX_SHADER, VERT_SRC)
        val frag = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAG_SRC)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vert)
        GLES20.glAttachShader(program, frag)
        GLES20.glLinkProgram(program)

        uMvpMatrixHandle = GLES20.glGetUniformLocation(program, "uMvpMatrix")
        uColorHandle     = GLES20.glGetUniformLocation(program, "uColor")
        aPositionHandle  = GLES20.glGetAttribLocation(program, "aPosition")
    }

    /**
     * Draw the arrow pin at the given anchor [pose].
     *
     * @param pose       anchor pose from ARCore (world space)
     * @param viewMatrix 4×4 view matrix from `camera.getViewMatrix()`
     * @param projMatrix 4×4 projection matrix from `camera.getProjectionMatrix()`
     */
    fun draw(pose: Pose, viewMatrix: FloatArray, projMatrix: FloatArray) {
        val modelMatrix = FloatArray(16)
        pose.toMatrix(modelMatrix, 0)
        drawWithMatrix(modelMatrix, viewMatrix, projMatrix)
    }

    /**
     * Draw the arrow pin at the given 4x4 model matrix.
     * Used by Phase 4 Resolve Pin when we have a computed pose matrix.
     */
    fun drawWithMatrix(modelMatrix: FloatArray, viewMatrix: FloatArray, projMatrix: FloatArray) {
        val mvMatrix = FloatArray(16)
        Matrix.multiplyMM(mvMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        val mvpMatrix = FloatArray(16)
        Matrix.multiplyMM(mvpMatrix, 0, projMatrix, 0, mvMatrix, 0)

        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uMvpMatrixHandle, 1, false, mvpMatrix, 0)
        GLES20.glEnableVertexAttribArray(aPositionHandle)

        // Draw body (red)
        drawSection(bodyVertBuf, bodyIdxBuf, BODY_INDICES.size, COLOR_BODY)

        // Draw nose (cyan) — identifiable "forward" direction marker
        drawSection(noseVertBuf, noseIdxBuf, NOSE_INDICES.size, COLOR_NOSE)

        // Draw back cap (orange)
        drawSection(backVertBuf, backIdxBuf, BACK_INDICES.size, COLOR_BACK)

        GLES20.glDisableVertexAttribArray(aPositionHandle)
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun drawSection(verts: FloatBuffer, indices: ShortBuffer, count: Int, color: FloatArray) {
        GLES20.glUniform4fv(uColorHandle, 1, color, 0)
        verts.position(0)
        GLES20.glVertexAttribPointer(aPositionHandle, 3, GLES20.GL_FLOAT, false, 0, verts)
        indices.position(0)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, count, GLES20.GL_UNSIGNED_SHORT, indices)
    }

    private fun makeFloatBuf(data: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(data); position(0) }

    private fun makeShortBuf(data: ShortArray): ShortBuffer =
        ByteBuffer.allocateDirect(data.size * 2)
            .order(ByteOrder.nativeOrder())
            .asShortBuffer()
            .apply { put(data); position(0) }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        return shader
    }
}
