package com.github.zeckson.rtspvideoplayer.renderer

import android.opengl.GLES11Ext
import android.opengl.GLES20
import com.google.vr.sdk.base.Eye
import java.nio.FloatBuffer

/**
 * Utility class to generate & render spherical meshes for video or images. Use the static creation
 * methods to construct the Mesh's data. Then call the Mesh constructor on the GL thread when ready.
 * Use glDraw method to render it.
 */
class Mesh
/** Used by static constructors.  */
private constructor(// Vertices for the mesh with 3D position + left 2D texture UV + right 2D texture UV.
    val vertices: FloatArray
) {
    private val vertexBuffer: FloatBuffer

    // Program related GL items. These are only valid if program != 0.
    private var program: Int = 0
    private var mvpMatrixHandle: Int = 0
    private var positionHandle: Int = 0
    private var texCoordsHandle: Int = 0
    private var textureHandle: Int = 0
    private var textureId: Int = 0

    init {
        vertexBuffer = Utils.createBuffer(vertices)
    }

    /**
     * Finishes initialization of the GL components.
     *
     * @param textureId GL_TEXTURE_EXTERNAL_OES used for this mesh.
     */
    /* package */ internal fun glInit(textureId: Int) {
        this.textureId = textureId

        program = Utils.compileProgram(VERTEX_SHADER_CODE, FRAGMENT_SHADER_CODE)

        mvpMatrixHandle = GLES20.glGetUniformLocation(program, "uMvpMatrix")
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordsHandle = GLES20.glGetAttribLocation(program, "aTexCoords")
        textureHandle = GLES20.glGetUniformLocation(program, "uTexture")
    }

    /**
     * Renders the mesh. This must be called on the GL thread.
     *
     * @param mvpMatrix The Model View Projection matrix.
     * @param eyeType An [Eye.Type] value.
     */
    /* package */ internal fun glDraw(mvpMatrix: FloatArray, eyeType: Int) {
        // Configure shader.
        GLES20.glUseProgram(program)
        Utils.checkGlError()

        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glEnableVertexAttribArray(texCoordsHandle)
        Utils.checkGlError()

        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(textureHandle, 0)
        Utils.checkGlError()

        // Load position data.
        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(
            positionHandle,
            POSITION_COORDS_PER_VERTEX,
            GLES20.GL_FLOAT,
            false,
            VERTEX_STRIDE_BYTES,
            vertexBuffer
        )
        Utils.checkGlError()

        // Load texture data. Eye.Type.RIGHT uses the left eye's data.
        val textureOffset =
            if (eyeType == Eye.Type.RIGHT) POSITION_COORDS_PER_VERTEX + 2 else POSITION_COORDS_PER_VERTEX
        vertexBuffer.position(textureOffset)
        GLES20.glVertexAttribPointer(
            texCoordsHandle,
            TEXTURE_COORDS_PER_VERTEX,
            GLES20.GL_FLOAT,
            false,
            VERTEX_STRIDE_BYTES,
            vertexBuffer
        )
        Utils.checkGlError()

        // Render.
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, vertices.size / CPV)
        Utils.checkGlError()

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordsHandle)
    }

    /** Cleans up the GL resources.  */
    /* package */ internal fun glShutdown() {
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
        }
    }

    companion object {
        /** Standard media where a single camera frame takes up the entire media frame.  */
        val MEDIA_MONOSCOPIC = 0
        /**
         * Stereo media where the left & right halves of the frame are rendered for the left & right eyes,
         * respectively. If the stereo media is rendered in a non-VR display, only the left half is used.
         */
        val MEDIA_STEREO_LEFT_RIGHT = 1
        /**
         * Stereo media where the top & bottom halves of the frame are rendered for the left & right eyes,
         * respectively. If the stereo media is rendered in a non-VR display, only the top half is used.
         */
        val MEDIA_STEREO_TOP_BOTTOM = 2

        // Basic vertex & fragment shaders to render a mesh with 3D position & 2D texture data.
        private val VERTEX_SHADER_CODE = arrayOf(
            "uniform mat4 uMvpMatrix;",
            "attribute vec4 aPosition;",
            "attribute vec2 aTexCoords;",
            "varying vec2 vTexCoords;",

            // Standard transformation.
            "void main() {",
            "  gl_Position = uMvpMatrix * aPosition;",
            "  vTexCoords = aTexCoords;",
            "}"
        )
        private val FRAGMENT_SHADER_CODE = arrayOf(
            // This is required since the texture data is GL_TEXTURE_EXTERNAL_OES.
            "#extension GL_OES_EGL_image_external : require",
            "precision mediump float;",

            // Standard texture rendering shader.
            "uniform samplerExternalOES uTexture;",
            "varying vec2 vTexCoords;",
            "void main() {",
            "  gl_FragColor = texture2D(uTexture, vTexCoords);",
            "}"
        )

        // Constants related to vertex data.
        private val POSITION_COORDS_PER_VERTEX = 3 // X, Y, Z.
        // The vertex contains texture coordinates for both the left & right eyes. If the scene is
        // rendered in VR, the appropriate part of the vertex will be selected at runtime. For a mono
        // scene, only the left eye's UV coordinates are used.
        // For mono media, the UV coordinates are duplicated in each. For stereo media, the UV coords
        // point to the appropriate part of the source media.
        private val TEXTURE_COORDS_PER_VERTEX = 2 * 2
        // COORDS_PER_VERTEX
        private val CPV = POSITION_COORDS_PER_VERTEX + TEXTURE_COORDS_PER_VERTEX
        // Data is tightly packed. Each vertex is [x, y, z, u_left, v_left, u_right, v_right].
        private val VERTEX_STRIDE_BYTES = CPV * Utils.BYTES_PER_FLOAT

        /**
         * Generates a 3D UV sphere for rendering monoscopic or stereoscopic video.
         *
         *
         * This can be called on any thread. The returned [Mesh] isn't valid until
         * [.glInit] is called.
         *
         * @param radius Size of the sphere. Must be > 0.
         * @param latitudes Number of rows that make up the sphere. Must be >= 1.
         * @param longitudes Number of columns that make up the sphere. Must be >= 1.
         * @param verticalFovDegrees Total latitudinal degrees that are covered by the sphere. Must be in
         * (0, 180].
         * @param horizontalFovDegrees Total longitudinal degrees that are covered by the sphere.Must be
         * in (0, 360].
         * @param mediaFormat A MEDIA_* value.
         * @return Unintialized Mesh.
         */
        fun createUvSphere(
            radius: Float,
            latitudes: Int,
            longitudes: Int,
            verticalFovDegrees: Float,
            horizontalFovDegrees: Float,
            mediaFormat: Int
        ): Mesh {
            if (radius <= 0
                || latitudes < 1 || longitudes < 1
                || verticalFovDegrees <= 0 || verticalFovDegrees > 180
                || horizontalFovDegrees <= 0 || horizontalFovDegrees > 360
            ) {
                throw IllegalArgumentException("Invalid parameters for sphere.")
            }

            // Compute angular size in radians of each UV quad.
            val verticalFovRads = Math.toRadians(verticalFovDegrees.toDouble()).toFloat()
            val horizontalFovRads = Math.toRadians(horizontalFovDegrees.toDouble()).toFloat()
            val quadHeightRads = verticalFovRads / latitudes
            val quadWidthRads = horizontalFovRads / longitudes

            // Each latitude strip has 2 * (longitudes quads + extra edge) vertices + 2 degenerate vertices.
            val vertexCount = (2 * (longitudes + 1) + 2) * latitudes
            // Buffer to return.
            val vertexData = FloatArray(vertexCount * CPV)

            // Generate the data for the sphere which is a set of triangle strips representing each
            // latitude band.
            var v = 0 // Index into the vertex array.
            // (i, j) represents a quad in the equirectangular sphere.
            for (j in 0 until latitudes) { // For each horizontal triangle strip.
                // Each latitude band lies between the two phi values. Each vertical edge on a band lies on
                // a theta value.
                val phiLow = quadHeightRads * j - verticalFovRads / 2
                val phiHigh = quadHeightRads * (j + 1) - verticalFovRads / 2

                for (i in 0 until longitudes + 1) { // For each vertical edge in the band.
                    for (k in 0..1) { // For low and high points on an edge.
                        // For each point, determine it's position in polar coordinates.
                        val phi = if (k == 0) phiLow else phiHigh
                        val theta = quadWidthRads * i + Math.PI.toFloat() - horizontalFovRads / 2

                        // Set vertex position data as Cartesian coordinates.
                        vertexData[CPV * v + 0] =
                                -(radius.toDouble() * Math.sin(theta.toDouble()) * Math.cos(phi.toDouble())).toFloat()
                        vertexData[CPV * v + 1] = (radius * Math.sin(phi.toDouble())).toFloat()
                        vertexData[CPV * v + 2] =
                                (radius.toDouble() * Math.cos(theta.toDouble()) * Math.cos(phi.toDouble())).toFloat()

                        // Set vertex texture.x data.
                        if (mediaFormat == MEDIA_STEREO_LEFT_RIGHT) {
                            // For left-right media, each eye's x coordinate points to the left or right half of the
                            // texture.
                            vertexData[CPV * v + 3] = i * quadWidthRads / horizontalFovRads / 2
                            vertexData[CPV * v + 5] = i * quadWidthRads / horizontalFovRads / 2 + .5f
                        } else {
                            // For top-bottom or monoscopic media, the eye's x spans the full width of the texture.
                            vertexData[CPV * v + 3] = i * quadWidthRads / horizontalFovRads
                            vertexData[CPV * v + 5] = i * quadWidthRads / horizontalFovRads
                        }

                        // Set vertex texture.y data. The "1 - ..." is due to Canvas vs GL coords.
                        if (mediaFormat == MEDIA_STEREO_TOP_BOTTOM) {
                            // For top-bottom media, each eye's y coordinate points to the top or bottom half of the
                            // texture.
                            vertexData[CPV * v + 4] = 1 - ((j + k) * quadHeightRads / verticalFovRads / 2 + .5f)
                            vertexData[CPV * v + 6] = 1 - (j + k) * quadHeightRads / verticalFovRads / 2
                        } else {
                            // For left-right or monoscopic media, the eye's y spans the full height of the texture.
                            vertexData[CPV * v + 4] = 1 - (j + k) * quadHeightRads / verticalFovRads
                            vertexData[CPV * v + 6] = 1 - (j + k) * quadHeightRads / verticalFovRads
                        }
                        v++

                        // Break up the triangle strip with degenerate vertices by copying first and last points.
                        if (i == 0 && k == 0 || i == longitudes && k == 1) {
                            System.arraycopy(vertexData, CPV * (v - 1), vertexData, CPV * v, CPV)
                            v++
                        }
                    }
                    // Move on to the next vertical edge in the triangle strip.
                }
                // Move on to the next triangle strip.
            }

            return Mesh(vertexData)
        }
    }
}
