package com.blackhole.screensaver.gl

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

/**
 * Fullscreen quad + LENS_FRAG gravity-lens shader.
 * Screen frames arrive as Bitmaps from MediaProjection / ImageReader.
 */
class BlackholeRenderer : GLSurfaceView.Renderer {

    private val pendingFrame = AtomicReference<Bitmap?>(null)

    @Volatile
    var viewWidth: Int = 1
        private set

    @Volatile
    var viewHeight: Int = 1
        private set

    private var program = 0
    private var textureId = 0
    private var hasTexture = false

    private var aPosition = 0
    private var aTexCoord = 0
    private var uTex = 0
    private var uResolution = 0
    private var uCenter = 0
    private var uRs = 0
    private var uK = 0
    private var uTime = 0
    private var uHorizon = 0

    private lateinit var vertexBuffer: FloatBuffer
    private lateinit var texBuffer: FloatBuffer

    private var startNs = 0L
    private var centerX = 0f
    private var centerY = 0f
    private var velX = 110f
    private var velY = 80f
    private var lastFrameNs = 0L

    // Tuned for phone screens (px). Desktop values scale with resolution.
    private var rs = 90f
    private var k = 1.35f
    private var horizonFactor = 0.55f

    fun submitFrame(bitmap: Bitmap) {
        val old = pendingFrame.getAndSet(bitmap)
        if (old != null && old !== bitmap && !old.isRecycled) {
            old.recycle()
        }
    }

    fun clearPendingFrames() {
        val old = pendingFrame.getAndSet(null)
        if (old != null && !old.isRecycled) old.recycle()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        program = buildProgram(VERTEX, FRAGMENT)
        aPosition = GLES20.glGetAttribLocation(program, "a_position")
        aTexCoord = GLES20.glGetAttribLocation(program, "a_texCoord")
        uTex = GLES20.glGetUniformLocation(program, "u_tex")
        uResolution = GLES20.glGetUniformLocation(program, "u_resolution")
        uCenter = GLES20.glGetUniformLocation(program, "u_center")
        uRs = GLES20.glGetUniformLocation(program, "u_rs")
        uK = GLES20.glGetUniformLocation(program, "u_k")
        uTime = GLES20.glGetUniformLocation(program, "u_time")
        uHorizon = GLES20.glGetUniformLocation(program, "u_horizon")

        val verts = floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f
        )
        // ImageReader / Bitmap origin is top-left; GL textures are bottom-left.
        // Flip V so captured screen is upright. Re-verify on device.
        val tex = floatArrayOf(
            0f, 1f,
            1f, 1f,
            0f, 0f,
            1f, 0f
        )
        vertexBuffer = ByteBuffer.allocateDirect(verts.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().put(verts)
        vertexBuffer.position(0)
        texBuffer = ByteBuffer.allocateDirect(tex.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().put(tex)
        texBuffer.position(0)

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        textureId = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        startNs = System.nanoTime()
        lastFrameNs = startNs
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewWidth = width.coerceAtLeast(1)
        viewHeight = height.coerceAtLeast(1)
        GLES20.glViewport(0, 0, viewWidth, viewHeight)
        centerX = viewWidth * 0.35f
        centerY = viewHeight * 0.4f
        rs = (minOf(viewWidth, viewHeight) * 0.12f).coerceIn(60f, 160f)
        // Soft DVD-logo style speeds scaled to screen.
        velX = viewWidth * 0.045f
        velY = viewHeight * 0.035f
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val dt = ((now - lastFrameNs) / 1_000_000_000f).coerceIn(0f, 0.05f)
        lastFrameNs = now

        uploadPendingTexture()
        roam(dt)

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (!hasTexture) return

        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glUniform2f(uResolution, viewWidth.toFloat(), viewHeight.toFloat())
        GLES20.glUniform2f(uCenter, centerX, centerY)
        GLES20.glUniform1f(uRs, rs)
        GLES20.glUniform1f(uK, k)
        GLES20.glUniform1f(uTime, (now - startNs) / 1_000_000_000f)
        GLES20.glUniform1f(uHorizon, horizonFactor)

        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(aTexCoord)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aTexCoord)
    }

    private fun roam(dt: Float) {
        centerX += velX * dt
        centerY += velY * dt
        val margin = rs * horizonFactor + 8f
        if (centerX < margin) {
            centerX = margin
            velX = kotlin.math.abs(velX)
        } else if (centerX > viewWidth - margin) {
            centerX = viewWidth - margin
            velX = -kotlin.math.abs(velX)
        }
        if (centerY < margin) {
            centerY = margin
            velY = kotlin.math.abs(velY)
        } else if (centerY > viewHeight - margin) {
            centerY = viewHeight - margin
            velY = -kotlin.math.abs(velY)
        }
        // Tiny wobble so path is less mechanical.
        centerX += (sin(System.nanoTime() / 1.7e9) * 0.15).toFloat()
        centerY += (cos(System.nanoTime() / 2.1e9) * 0.15).toFloat()
    }

    private fun uploadPendingTexture() {
        val bmp = pendingFrame.getAndSet(null) ?: return
        try {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            if (!hasTexture) {
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
                hasTexture = true
            } else {
                GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bmp)
            }
        } catch (_: Exception) {
            // Size may change after rotation — reallocate.
            try {
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
                hasTexture = true
            } catch (_: Exception) {
                // ignore bad frame
            }
        } finally {
            if (!bmp.isRecycled) bmp.recycle()
        }
    }

    private fun buildProgram(vertex: String, fragment: String): Int {
        val vs = compile(GLES20.GL_VERTEX_SHADER, vertex)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragment)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        val link = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, link, 0)
        if (link[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(prog)
            GLES20.glDeleteProgram(prog)
            throw RuntimeException("Shader link failed: $log")
        }
        return prog
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Shader compile failed: $log")
        }
        return shader
    }

    companion object {
        private const val VERTEX = """
            attribute vec2 a_position;
            attribute vec2 a_texCoord;
            varying vec2 v_texCoord;
            void main() {
                v_texCoord = a_texCoord;
                gl_Position = vec4(a_position, 0.0, 1.0);
            }
        """

        // Desktop LENS_FRAG port. Uses gl_FragCoord for lens math;
        // texture sampling uses flipped UV from vertex stage via v_texCoord only for passthrough —
        // lens math reconstructs screen-space UV like the desktop version.
        private const val FRAGMENT = """
            precision mediump float;
            uniform sampler2D u_tex;
            uniform vec2 u_resolution;
            uniform vec2 u_center;
            uniform float u_rs;
            uniform float u_k;
            uniform float u_time;
            uniform float u_horizon;
            varying vec2 v_texCoord;

            void main() {
                vec2 p = gl_FragCoord.xy;
                vec2 d = p - u_center;
                float r = max(length(d), 0.0001);
                vec2 dir = d / r;
                float horizon = u_rs * u_horizon;
                if (r < horizon) {
                    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                    return;
                }
                float displace = (u_rs * u_rs * u_k) / r;
                vec2 sample_px = p - dir * displace;
                // Flip Y for Android capture bitmaps (top-left origin).
                vec2 uv = vec2(sample_px.x / u_resolution.x, 1.0 - (sample_px.y / u_resolution.y));
                uv = clamp(uv, vec2(0.001), vec2(0.999));
                vec3 col = texture2D(u_tex, uv).rgb;
                col *= smoothstep(horizon, horizon * 2.2, r);
                float x = (r - horizon * 1.06) / (u_rs * 0.16);
                float ring = exp(-x * x);
                float flicker = 0.8 + 0.2 * sin(u_time * 10.0 + r * 0.05);
                col += vec3(1.0, 0.6, 0.15) * ring * flicker * 1.6;
                gl_FragColor = vec4(col, 1.0);
            }
        """
    }
}
