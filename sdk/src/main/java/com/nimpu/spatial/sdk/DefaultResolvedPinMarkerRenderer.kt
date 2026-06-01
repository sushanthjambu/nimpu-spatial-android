package com.nimpu.spatial.sdk

import android.opengl.GLES20
import android.opengl.Matrix
import com.google.ar.core.Pose
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Lightweight red map-pin marker used as the SDK default resolved-pin renderer.
 *
 * The marker is intentionally simple: an extruded teardrop body, darker side faces for depth, and
 * a small white center disk. The bottom tip sits on the anchor so the shape clearly points to the
 * resolved location without adding heavy model or texture work to the AR render loop.
 */
class DefaultResolvedPinMarkerRenderer : ResolvedPinMarkerRenderer {

    private var program = 0
    private var uMvpMatrixHandle = 0
    private var uColorHandle = 0
    private var aPositionHandle = 0

    private lateinit var frontVertBuf: FloatBuffer
    private lateinit var frontIdxBuf: ShortBuffer
    private lateinit var backVertBuf: FloatBuffer
    private lateinit var backIdxBuf: ShortBuffer
    private lateinit var sideVertBuf: FloatBuffer
    private lateinit var sideIdxBuf: ShortBuffer
    private lateinit var centerVertBuf: FloatBuffer
    private lateinit var centerIdxBuf: ShortBuffer
    private lateinit var tipVertBuf: FloatBuffer
    private lateinit var tipIdxBuf: ShortBuffer

    private val frontMesh = buildFaceMesh(z = -DEPTH)
    private val backMesh = buildFaceMesh(z = DEPTH, reverse = true)
    private val sideMesh = buildSideMesh()
    private val centerMesh = buildCenterDiskMesh(z = -DEPTH - 0.0015f)
    private val tipMesh = buildTipBevelMesh()

    private val vertexSrc = """
        uniform mat4 uMvpMatrix;
        attribute vec4 aPosition;
        void main() {
            gl_Position = uMvpMatrix * aPosition;
        }
    """.trimIndent()

    private val fragmentSrc = """
        precision mediump float;
        uniform vec4 uColor;
        void main() {
            gl_FragColor = uColor;
        }
    """.trimIndent()

    override fun init() {
        frontVertBuf = makeFloatBuf(frontMesh.vertices)
        frontIdxBuf = makeShortBuf(frontMesh.indices)
        backVertBuf = makeFloatBuf(backMesh.vertices)
        backIdxBuf = makeShortBuf(backMesh.indices)
        sideVertBuf = makeFloatBuf(sideMesh.vertices)
        sideIdxBuf = makeShortBuf(sideMesh.indices)
        centerVertBuf = makeFloatBuf(centerMesh.vertices)
        centerIdxBuf = makeShortBuf(centerMesh.indices)
        tipVertBuf = makeFloatBuf(tipMesh.vertices)
        tipIdxBuf = makeShortBuf(tipMesh.indices)

        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)

        uMvpMatrixHandle = GLES20.glGetUniformLocation(program, "uMvpMatrix")
        uColorHandle = GLES20.glGetUniformLocation(program, "uColor")
        aPositionHandle = GLES20.glGetAttribLocation(program, "aPosition")
    }

    override fun draw(pose: Pose, viewMatrix: FloatArray, projectionMatrix: FloatArray) {
        val modelMatrix = FloatArray(16)
        pose.toMatrix(modelMatrix, 0)
        drawWithMatrix(modelMatrix, viewMatrix, projectionMatrix)
    }

    override fun drawWithMatrix(
        modelMatrix: FloatArray,
        viewMatrix: FloatArray,
        projectionMatrix: FloatArray
    ) {
        if (program == 0) return

        val markerMatrix = modelMatrix.copyOf()
        Matrix.rotateM(markerMatrix, 0, 180f, 0f, 1f, 0f)
        Matrix.scaleM(markerMatrix, 0, MARKER_SCALE, MARKER_SCALE, MARKER_SCALE)

        val mvMatrix = FloatArray(16)
        Matrix.multiplyMM(mvMatrix, 0, viewMatrix, 0, markerMatrix, 0)
        val mvpMatrix = FloatArray(16)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, mvMatrix, 0)

        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uMvpMatrixHandle, 1, false, mvpMatrix, 0)
        GLES20.glEnableVertexAttribArray(aPositionHandle)

        drawSection(backVertBuf, backIdxBuf, backMesh.indices.size, COLOR_BACK)
        drawSection(sideVertBuf, sideIdxBuf, sideMesh.indices.size, COLOR_SIDE)
        drawSection(frontVertBuf, frontIdxBuf, frontMesh.indices.size, COLOR_FRONT)
        drawSection(tipVertBuf, tipIdxBuf, tipMesh.indices.size, COLOR_TIP)
        drawSection(centerVertBuf, centerIdxBuf, centerMesh.indices.size, COLOR_CENTER)

        GLES20.glDisableVertexAttribArray(aPositionHandle)
    }

    override fun release() {
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
    }

    private fun drawSection(vertices: FloatBuffer, indices: ShortBuffer, count: Int, color: FloatArray) {
        GLES20.glUniform4fv(uColorHandle, 1, color, 0)
        vertices.position(0)
        GLES20.glVertexAttribPointer(aPositionHandle, 3, GLES20.GL_FLOAT, false, 0, vertices)
        indices.position(0)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, count, GLES20.GL_UNSIGNED_SHORT, indices)
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        return shader
    }

    private data class Mesh(
        val vertices: FloatArray,
        val indices: ShortArray
    )

    private companion object {
        private const val DEPTH = 0.008f
        private const val RADIUS = 0.052f
        private const val CENTER_Y = 0.115f
        private const val TIP_Y = 0.0f
        private const val SEGMENTS = 18
        private const val MARKER_SCALE = 4.0f

        private val COLOR_FRONT = floatArrayOf(0.95f, 0.04f, 0.04f, 1.0f)
        private val COLOR_BACK = floatArrayOf(0.68f, 0.02f, 0.02f, 1.0f)
        private val COLOR_SIDE = floatArrayOf(0.50f, 0.015f, 0.015f, 1.0f)
        private val COLOR_TIP = floatArrayOf(0.40f, 0.01f, 0.01f, 1.0f)
        private val COLOR_CENTER = floatArrayOf(1.0f, 0.96f, 0.92f, 1.0f)

        private fun outlinePoints(): List<Pair<Float, Float>> {
            val points = mutableListOf<Pair<Float, Float>>()
            points += 0f to TIP_Y
            for (i in SEGMENTS downTo 0) {
                val angle = Math.toRadians((210.0 - (240.0 * i / SEGMENTS)).coerceIn(-30.0, 210.0))
                val x = (cos(angle) * RADIUS).toFloat()
                val y = CENTER_Y + (sin(angle) * RADIUS).toFloat()
                points += x to y
            }
            return points
        }

        private fun buildFaceMesh(z: Float, reverse: Boolean = false): Mesh {
            val outline = outlinePoints()
            val vertices = FloatArray((outline.size + 1) * 3)
            vertices[0] = 0f
            vertices[1] = CENTER_Y
            vertices[2] = z
            outline.forEachIndexed { index, point ->
                val offset = (index + 1) * 3
                vertices[offset] = point.first
                vertices[offset + 1] = point.second
                vertices[offset + 2] = z
            }

            val indices = mutableListOf<Short>()
            for (i in outline.indices) {
                val a = (i + 1).toShort()
                val b = (if (i == outline.lastIndex) 1 else i + 2).toShort()
                if (reverse) {
                    indices += 0
                    indices += b
                    indices += a
                } else {
                    indices += 0
                    indices += a
                    indices += b
                }
            }
            return Mesh(vertices, indices.toShortArray())
        }

        private fun buildSideMesh(): Mesh {
            val outline = outlinePoints()
            val vertices = FloatArray(outline.size * 2 * 3)
            outline.forEachIndexed { index, point ->
                val front = index * 6
                vertices[front] = point.first
                vertices[front + 1] = point.second
                vertices[front + 2] = -DEPTH
                vertices[front + 3] = point.first
                vertices[front + 4] = point.second
                vertices[front + 5] = DEPTH
            }

            val indices = mutableListOf<Short>()
            for (i in outline.indices) {
                val next = if (i == outline.lastIndex) 0 else i + 1
                val f0 = (i * 2).toShort()
                val b0 = (i * 2 + 1).toShort()
                val f1 = (next * 2).toShort()
                val b1 = (next * 2 + 1).toShort()
                indices += f0
                indices += f1
                indices += b1
                indices += f0
                indices += b1
                indices += b0
            }
            return Mesh(vertices, indices.toShortArray())
        }

        private fun buildCenterDiskMesh(z: Float): Mesh {
            val diskSegments = 16
            val radius = 0.018f
            val vertices = FloatArray((diskSegments + 1) * 3)
            vertices[0] = 0f
            vertices[1] = CENTER_Y + 0.004f
            vertices[2] = z
            for (i in 0 until diskSegments) {
                val angle = (2.0 * PI * i / diskSegments).toFloat()
                val offset = (i + 1) * 3
                vertices[offset] = cos(angle) * radius
                vertices[offset + 1] = CENTER_Y + 0.004f + sin(angle) * radius
                vertices[offset + 2] = z
            }

            val indices = mutableListOf<Short>()
            for (i in 0 until diskSegments) {
                indices += 0
                indices += (i + 1).toShort()
                indices += (if (i == diskSegments - 1) 1 else i + 2).toShort()
            }
            return Mesh(vertices, indices.toShortArray())
        }

        private fun buildTipBevelMesh(): Mesh {
            val vertices = floatArrayOf(
                0f, TIP_Y, -DEPTH - 0.020f,
                -0.014f, 0.032f, -DEPTH,
                0.014f, 0.032f, -DEPTH,
                0f, 0.036f, DEPTH
            )
            val indices = shortArrayOf(
                0, 1, 2,
                0, 2, 3,
                0, 3, 1,
                1, 3, 2
            )
            return Mesh(vertices, indices)
        }

        private fun makeFloatBuf(data: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(data.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(data)
                    position(0)
                }

        private fun makeShortBuf(data: ShortArray): ShortBuffer =
            ByteBuffer.allocateDirect(data.size * 2)
                .order(ByteOrder.nativeOrder())
                .asShortBuffer()
                .apply {
                    put(data)
                    position(0)
                }
    }
}
