package com.blackhole.screensaver.gl

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import com.blackhole.screensaver.prefs.AppPrefs
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

/**
 * Transparent overlay: real screen shows 1:1 outside the black hole.
 * Distortion matches desktop [shaders.py] LENS_FRAG:
 *   displace = (rs² * k) / r, darken + photon ring.
 *
 * Size 1×/1.5×/2× grows the visible hole & ring only; lens pull always uses
 * 1× baseRs so the frozen wallpaper does not zoom with size.
 */
class BlackholeRenderer : GLSurfaceView.Renderer {

    private val pendingFrame = AtomicReference<Bitmap?>(null)

    @Volatile
    var viewWidth: Int = 1
        private set

    @Volatile
    var viewHeight: Int = 1
        private set

    @Volatile
    var sizeScale: Float = 1f

    private var program = 0
    private var textureId = 0
    private var hasTexture = false
    private var texWidth = 0
    private var texHeight = 0

    private var aPosition = 0
    private var uTex = 0
    private var uResolution = 0
    private var uCenter = 0
    private var uRs = 0
    private var uBaseRs = 0
    private var uK = 0
    private var uTime = 0
    private var uHorizon = 0
    private var uEffectRadius = 0

    private lateinit var vertexBuffer: FloatBuffer

    private var startNs = 0L
    private var centerX = 0f
    private var centerY = 0f
    private var velX = 110f
    private var velY = 80f
    private var lastFrameNs = 0L

    // Tuned like desktop config.py (1080p reference), then scaled to this view.
    private var baseRs = 90f
    private var rs = 90f
    private var k = 18.0f
    private var horizonFactor = 1.75f
    private var effectRadiusFactor = 9.0f

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
        hasTexture = false
        texWidth = 0
        texHeight = 0

        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        program = buildProgram(VERTEX, FRAGMENT)
        aPosition = GLES20.glGetAttribLocation(program, "a_position")
        uTex = GLES20.glGetUniformLocation(program, "u_tex")
        uResolution = GLES20.glGetUniformLocation(program, "u_resolution")
        uCenter = GLES20.glGetUniformLocation(program, "u_center")
        uRs = GLES20.glGetUniformLocation(program, "u_rs")
        uBaseRs = GLES20.glGetUniformLocation(program, "u_baseRs")
        uK = GLES20.glGetUniformLocation(program, "u_k")
        uTime = GLES20.glGetUniformLocation(program, "u_time")
        uHorizon = GLES20.glGetUniformLocation(program, "u_horizon")
        uEffectRadius = GLES20.glGetUniformLocation(program, "u_effectRadius")

        val verts = floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f
        )
        vertexBuffer = ByteBuffer.allocateDirect(verts.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().put(verts)
        vertexBuffer.position(0)

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
        recomputeRs()
        velX = viewWidth * 0.045f
        velY = viewHeight * 0.035f
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val dt = ((now - lastFrameNs) / 1_000_000_000f).coerceIn(0f, 0.05f)
        lastFrameNs = now

        val preferred = AppPrefs.blackholeSizeScale
        if (preferred != sizeScale) {
            sizeScale = preferred
            recomputeRs()
        }

        uploadPendingTexture()
        roam(dt)

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (!hasTexture) return

        val effectRadius = rs * effectRadiusFactor

        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glUniform2f(uResolution, viewWidth.toFloat(), viewHeight.toFloat())
        GLES20.glUniform2f(uCenter, centerX, centerY)
        GLES20.glUniform1f(uRs, rs)
        GLES20.glUniform1f(uBaseRs, baseRs)
        GLES20.glUniform1f(uK, k)
        GLES20.glUniform1f(uTime, (now - startNs) / 1_000_000_000f)
        GLES20.glUniform1f(uHorizon, horizonFactor)
        GLES20.glUniform1f(uEffectRadius, effectRadius)

        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPosition)
    }

    private fun recomputeRs() {
        // Desktop blackhole_rs @ 1080p, then half for mobile overlay sizing.
        val minDim = minOf(viewWidth, viewHeight).toFloat()
        baseRs = (58f * (minDim / 1080f) * 0.5f).coerceIn(24f, 80f)
        rs = baseRs * sizeScale.coerceIn(1f, 2f)
    }

    private fun roam(dt: Float) {
        centerX += velX * dt
        centerY += velY * dt
        val margin = rs * horizonFactor
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
        val jitter = 0.08f / sizeScale.coerceIn(1f, 2f)
        centerX += (sin(System.nanoTime() / 1.7e9) * jitter).toFloat()
        centerY += (cos(System.nanoTime() / 2.1e9) * jitter).toFloat()
    }

    private fun uploadPendingTexture() {
        val bmp = pendingFrame.getAndSet(null) ?: return
        var toUpload = bmp
        var scaled: Bitmap? = null
        try {
            if (viewWidth > 1 && viewHeight > 1 &&
                (bmp.width != viewWidth || bmp.height != viewHeight)
            ) {
                scaled = Bitmap.createScaledBitmap(bmp, viewWidth, viewHeight, true)
                toUpload = scaled
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            val sizeChanged = toUpload.width != texWidth || toUpload.height != texHeight
            if (!hasTexture || sizeChanged) {
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, toUpload, 0)
                texWidth = toUpload.width
                texHeight = toUpload.height
                hasTexture = true
            } else {
                GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, toUpload)
            }
        } catch (_: Exception) {
            try {
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
                texWidth = bmp.width
                texHeight = bmp.height
                hasTexture = true
            } catch (_: Exception) {
                // ignore bad frame
            }
        } finally {
            if (scaled != null && scaled !== bmp && !scaled.isRecycled) scaled.recycle()
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
            void main() {
                gl_Position = vec4(a_position, 0.0, 1.0);
            }
        """

        /**
         * Port of desktop shaders.py LENS_FRAG for a transparent Android overlay.
         *
         * - Lens: displace = (baseRs² * k) / r  (always 1× baseRs → no wallpaper zoom)
         * - Hole / ring use visual u_rs (= baseRs * sizeScale)
         * - Outside u_effectRadius: fully transparent (live UI 1:1)
         */
        private const val FRAGMENT = """
            precision highp float;
            uniform sampler2D u_tex;
            uniform vec2 u_resolution;
            uniform vec2 u_center;
            uniform float u_rs;
            uniform float u_baseRs;
            uniform float u_k;
            uniform float u_time;
            uniform float u_horizon;
            uniform float u_effectRadius;

            void main() {
                vec2 p = gl_FragCoord.xy;
                vec2 d = p - u_center;
                float r = max(length(d), 0.0001);

                if (r > u_effectRadius) {
                    gl_FragColor = vec4(0.0);
                    return;
                }

                // Visual event horizon grows with size setting (desktop: rs * horizon).
                float horizon = u_rs * u_horizon;
                if (r < horizon) {
                    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                    return;
                }

                vec2 dir = d / r;

                // Desktop LENS_FRAG: sample = p - dir * (rs*rs*k / r)
                // Use 1× baseRs only so enlarging the hole does not magnify wallpaper.
                float displace = (u_baseRs * u_baseRs * u_k) / r;
                // Softly kill pull near the overlay cutoff (hard alpha — no white rim).
                float edge = smoothstep(u_effectRadius, u_effectRadius * 0.82, r);
                displace *= edge;
                // Keep samples outside the black disc (avoids icon inversion).
                displace = min(displace, max(r - horizon - 1.0, 0.0));

                vec2 sample_px = p - dir * displace;
                vec2 uv = sample_px / u_resolution;
                uv.y = 1.0 - uv.y;
                uv = clamp(uv, vec2(0.001), vec2(0.999));

                vec3 col = texture2D(u_tex, uv).rgb;

                // Desktop darkening toward the horizon.
                float darken = smoothstep(horizon, horizon * 2.2, r);
                col *= darken;

                // Desktop photon ring — static (no time flicker → no background sparkle).
                float ring_center = horizon * 1.06;
                float ring_width = max(u_rs * 0.16, 1.0);
                float x = (r - ring_center) / ring_width;
                float ring = exp(-x * x);
                col += vec3(1.0, 0.6, 0.15) * ring * 1.35;

                gl_FragColor = vec4(col, 1.0);
            }
        """
    }
}
