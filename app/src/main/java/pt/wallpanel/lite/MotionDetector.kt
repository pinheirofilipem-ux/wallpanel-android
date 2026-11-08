package pt.wallpanel.lite

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import kotlin.math.abs

/**
 * Deteção de movimento ultra leve: Camera2 -> frames YUV minúsculos (~160x120..320x240) ->
 * só o plano Y (luminância) -> grelha 32x24 de médias -> diferença contra o frame de há 2 frames.
 * Compensa mudanças globais de brilho (auto-exposição) e exige 2 frames seguidos para evitar ruído.
 */
class MotionDetector(
    private val ctx: Context,
    private val sensitivity: () -> Int,
    private val onMotion: () -> Unit,
) {
    private companion object {
        const val COLS = 32
        const val ROWS = 24
        const val MIN_FRAME_INTERVAL_MS = 60L      // máx ~16 fps de análise
        const val NOTIFY_INTERVAL_MS = 150L        // máx de avisos por segundo ao serviço
        const val WATCHDOG_MS = 4000L
    }

    private val thread = HandlerThread("motion").apply { start() }
    private val handler = Handler(thread.looper)

    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null

    @Volatile private var running = false
    private var lastFrameAt = 0L
    private var lastNotifyAt = 0L
    private var opening = false

    private val grids = Array(3) { IntArray(COLS * ROWS) }
    private var frameCount = 0
    private var hits = 0

    fun start() {
        handler.post {
            if (running) return@post
            running = true
            lastFrameAt = SystemClock.elapsedRealtime()
            open()
            handler.postDelayed(watchdog, WATCHDOG_MS)
        }
    }

    fun stop() {
        handler.post {
            running = false
            handler.removeCallbacks(watchdog)
            closeCamera()
            thread.quitSafely()
        }
    }

    // Se a câmara parar de enviar frames (ex.: o sistema a tirou), reabre.
    private val watchdog = object : Runnable {
        override fun run() {
            if (!running) return
            if (SystemClock.elapsedRealtime() - lastFrameAt > WATCHDOG_MS) {
                closeCamera()
                open()
                lastFrameAt = SystemClock.elapsedRealtime()
            }
            handler.postDelayed(this, WATCHDOG_MS)
        }
    }

    @SuppressLint("MissingPermission")
    private fun open() {
        if (opening || camera != null) return
        try {
            val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = pickCamera(mgr) ?: return
            val chars = mgr.getCameraCharacteristics(id)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
            val sizes = map.getOutputSizes(ImageFormat.YUV_420_888) ?: return
            val size = sizes.filter { it.width >= 160 && it.height >= 120 }
                .minByOrNull { it.width * it.height } ?: sizes.minByOrNull { it.width * it.height } ?: return

            val ir = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
            ir.setOnImageAvailableListener({ onFrame(it) }, handler)
            reader = ir
            frameCount = 0
            hits = 0

            val fps = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.filter { it.upper <= 15 }
                ?.maxWithOrNull(compareBy({ it.upper }, { it.lower }))

            opening = true
            mgr.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    opening = false
                    if (!running) { device.close(); return }
                    camera = device
                    startSession(device, ir, fps)
                }
                override fun onDisconnected(device: CameraDevice) { opening = false; device.close(); camera = null }
                override fun onError(device: CameraDevice, error: Int) { opening = false; device.close(); camera = null }
            }, handler)
        } catch (e: CameraAccessException) {
            opening = false
        } catch (e: SecurityException) {
            opening = false
        } catch (e: IllegalArgumentException) {
            opening = false
        }
    }

    @Suppress("DEPRECATION")
    private fun startSession(device: CameraDevice, ir: ImageReader, fps: android.util.Range<Int>?) {
        try {
            device.createCaptureSession(listOf(ir.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (!running || camera !== device) { s.close(); return }
                    session = s
                    try {
                        val req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(ir.surface)
                            if (fps != null) set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps)
                        }
                        s.setRepeatingRequest(req.build(), null, handler)
                    } catch (e: Exception) {
                        closeCamera()
                    }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) { closeCamera() }
            }, handler)
        } catch (e: Exception) {
            closeCamera()
        }
    }

    private fun closeCamera() {
        try { session?.close() } catch (_: Exception) {}
        try { camera?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        session = null; camera = null; reader = null; opening = false
    }

    private fun pickCamera(mgr: CameraManager): String? {
        val ids = mgr.cameraIdList
        return ids.firstOrNull {
            mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        } ?: ids.firstOrNull()
    }

    private fun onFrame(r: ImageReader) {
        val img = try { r.acquireLatestImage() } catch (e: Exception) { null } ?: return
        try {
            val now = SystemClock.elapsedRealtime()
            lastFrameAt = now
            if (now - (lastProcessedAt) < MIN_FRAME_INTERVAL_MS) return
            lastProcessedAt = now

            val plane = img.planes[0]
            val cur = grids[frameCount % 3]
            fillGrid(plane.buffer, img.width, img.height, plane.rowStride, plane.pixelStride, cur)

            if (frameCount >= 2) {
                val ref = grids[(frameCount + 1) % 3] // o mais antigo (há 2 frames)
                if (changed(cur, ref)) hits++ else hits = 0
                if (hits >= 2 && now - lastNotifyAt >= NOTIFY_INTERVAL_MS) {
                    lastNotifyAt = now
                    onMotion()
                }
            }
            frameCount++
        } finally {
            img.close()
        }
    }

    private var lastProcessedAt = 0L

    private fun fillGrid(buf: java.nio.ByteBuffer, w: Int, h: Int, rowStride: Int, pixStride: Int, out: IntArray) {
        for (cy in 0 until ROWS) {
            val y0 = cy * h / ROWS
            val y1 = (cy + 1) * h / ROWS
            for (cx in 0 until COLS) {
                val x0 = cx * w / COLS
                val x1 = (cx + 1) * w / COLS
                var sum = 0
                var n = 0
                var y = y0
                while (y < y1) {
                    var x = x0
                    val row = y * rowStride
                    while (x < x1) {
                        sum += buf.get(row + x * pixStride).toInt() and 0xFF
                        n++
                        x += 2
                    }
                    y += 2
                }
                out[cy * COLS + cx] = if (n > 0) sum / n else 0
            }
        }
    }

    private fun changed(cur: IntArray, ref: IntArray): Boolean {
        val s = sensitivity().coerceIn(1, 10)
        val threshold = 20 - (s * 3) / 2     // s=1 -> 19, s=6 -> 11, s=10 -> 5 (níveis de luma)
        val minCells = (12 - s).coerceAtLeast(2) // s=1 -> 11 células, s=10 -> 2 células

        var total = 0
        for (i in cur.indices) total += cur[i] - ref[i]
        val shift = total / cur.size             // variação global (auto-exposição / luz)

        var count = 0
        for (i in cur.indices) {
            if (abs(cur[i] - ref[i] - shift) > threshold) count++
        }
        return count >= minCells
    }
}
