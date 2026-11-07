package org.awemissions.truenight

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.*
import android.provider.MediaStore
import android.view.*
import android.widget.*
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {
    private lateinit var preview: TextureView
    private lateinit var status: TextView
    private var enhanced = false
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var workerThread: HandlerThread? = null
    private var worker: Handler? = null
    private var properties: CameraCharacteristics? = null
    private var repeating: CaptureRequest.Builder? = null
    private val busy = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        status = TextView(this).apply { text = "TrueNight • Natural-to-Eye"; setTextColor(Color.WHITE); textSize = 17f; setPadding(20,20,20,20) }
        preview = TextureView(this)
        val mode = Button(this).apply {
            text = "Mode: Natural-to-Eye"
            setOnClickListener {
                enhanced = !enhanced
                text = if (enhanced) "Mode: Enhanced Night" else "Mode: Natural-to-Eye"
                updatePreview()
            }
        }
        val capture = Button(this).apply { text = "CAPTURE PHOTO"; setOnClickListener { takePhoto() } }
        layout.addView(status)
        layout.addView(preview, LinearLayout.LayoutParams(-1,0,1f))
        layout.addView(mode)
        layout.addView(capture)
        setContentView(layout)
        preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) { openCamera() }
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean { closeCamera(); return true }
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 5)
    }
    override fun onResume() {
        super.onResume()
        workerThread = HandlerThread("TrueNightCamera").apply { start() }
        worker = Handler(workerThread!!.looper)
        if (preview.isAvailable) openCamera()
    }
    override fun onPause() {
        closeCamera()
        workerThread?.quitSafely()
        workerThread = null
        worker = null
        super.onPause()
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == 5 && results.firstOrNull() == PackageManager.PERMISSION_GRANTED && preview.isAvailable) openCamera()
    }
    private fun openCamera() {
        if (camera != null || worker == null || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        try {
            val manager = getSystemService(CAMERA_SERVICE) as CameraManager
            val id = manager.cameraIdList.firstOrNull {
                manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: return
            properties = manager.getCameraCharacteristics(id)
            val map = properties!!.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
            val sizes = map.getOutputSizes(ImageFormat.JPEG)
            val size = sizes.filter { it.width.toLong() * it.height <= 12_000_000L }
                .maxByOrNull { it.width.toLong() * it.height }
                ?: sizes.maxByOrNull { it.width.toLong() * it.height } ?: return
            reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2).apply {
                setOnImageAvailableListener({ r ->
                    val image = r.acquireNextImage() ?: return@setOnImageAvailableListener
                    try {
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        saveJpeg(bytes)
                    } finally {
                        image.close()
                        busy.set(false)
                    }
                }, worker)
            }
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) { camera = device; createSession() }
                override fun onDisconnected(device: CameraDevice) { device.close(); camera = null }
                override fun onError(device: CameraDevice, error: Int) { device.close(); camera = null; message("Camera error: $error") }
            }, worker)
        } catch (e: Exception) { message("Camera unavailable: ${e.message}") }
    }
    private fun createSession() {
        val device = camera ?: return
        val r = reader ?: return
        try {
            val surface = Surface(preview.surfaceTexture)
            repeating = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(surface) }
            @Suppress("DEPRECATION")
            device.createCaptureSession(listOf(surface, r.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) { session = s; updatePreview() }
                override fun onConfigureFailed(s: CameraCaptureSession) { message("Preview configuration failed") }
            }, worker)
        } catch (e: Exception) { message("Preview error: ${e.message}") }
    }
    private fun configure(builder: CaptureRequest.Builder, still: Boolean) {
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        val range = properties?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val step = properties?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 1f
        val ev = if (enhanced) 1.0f else -0.7f
        if (range != null && step > 0f)
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, (ev / step).toInt().coerceIn(range.lower, range.upper))
        val available = properties?.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)?.toSet() ?: emptySet()
        val requested = if (enhanced) CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY else CaptureRequest.NOISE_REDUCTION_MODE_FAST
        if (requested in available) builder.set(CaptureRequest.NOISE_REDUCTION_MODE, requested)
        if (still) builder.set(CaptureRequest.JPEG_QUALITY, 95.toByte())
    }
    private fun updatePreview() {
        try {
            val b = repeating ?: return
            configure(b, false)
            session?.setRepeatingRequest(b.build(), null, worker)
            message(if (enhanced) "Enhanced Night • brighter exposure" else "Natural-to-Eye • restrained exposure")
        } catch (e: Exception) { message("Mode unsupported: ${e.message}") }
    }
    private fun takePhoto() {
        if (!busy.compareAndSet(false, true)) return
        try {
            val b = camera!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            b.addTarget(reader!!.surface)
            configure(b, true)
            b.set(CaptureRequest.JPEG_ORIENTATION, 90)
            session!!.capture(b.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, f: CaptureFailure) {
                    busy.set(false)
                    message("Capture failed")
                }
            }, worker)
        } catch (e: Exception) { busy.set(false); message("Capture error: ${e.message}") }
    }
    private fun saveJpeg(bytes: ByteArray) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "TrueNight_${System.currentTimeMillis()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TrueNight")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("Insert failed")
            try {
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Output unavailable")
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
                message("Saved to Pictures/TrueNight")
            } catch (e: Exception) { contentResolver.delete(uri, null, null); throw e }
        } catch (e: Exception) { message("Save failed: ${e.message}") }
    }
    private fun message(s: String) { runOnUiThread { status.text = s } }
    private fun closeCamera() {
        session?.close(); session = null
        camera?.close(); camera = null
        reader?.close(); reader = null
        repeating = null
        busy.set(false)
    }
}
