package com.visionex.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

enum class EyeState { OPEN, CLOSED, UNKNOWN }

data class CameraFrameMetrics(
    val eyeState: EyeState,
    val confidence: Float,
    val perclosPercent: Float?,
    val closedDurationSeconds: Float,
    val modelReady: Boolean,
    val drowsy: Boolean
)

/**
 * CameraX preview and ONNX YOLO eye-state model for controlled app testing.
 *
 * The model is fetched from its public Hugging Face repository on demand and
 * checked against the repository's published SHA-256 before it is opened.
 * The model is licensed CC BY-NC-SA 4.0. This is for non-commercial research use.
 */
class CameraDrowsinessManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val onStatus: (String) -> Unit,
    private val onMetrics: (CameraFrameMetrics) -> Unit,
    private val onDrowsinessChanged: (Boolean) -> Unit
) {
    companion object {
        private const val MODEL_URL =
            "https://huggingface.co/MichalMlodawski/open-closed-eye-detection/resolve/main/model.onnx"
        private const val MODEL_SHA256 =
            "2f207282e996a6341f33e1471de77abc1d847d47c33cabe4e3a31490e7484d76"
        private const val MODEL_FILE_NAME = "visionex_open_closed_eye.onnx"
        private const val MODEL_INPUT_SIZE = 640
    }

    private data class Sample(val timeMs: Long, val closed: Boolean?)
    private data class Candidate(val box: RectF, val classIndex: Int, val score: Float)
    private data class LetterboxedBitmap(
        val bitmap: Bitmap,
        val scale: Float,
        val padX: Float,
        val padY: Float
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val modelExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val modelDownloadRunning = AtomicBoolean(false)
    private val temporalLock = Any()

    @Volatile private var cameraRequested = false
    @Volatile private var cameraRunning = false
    @Volatile private var yoloEnabled = true
    @Volatile private var perclosEnabled = true
    @Volatile private var microsleepEnabled = true
    @Volatile private var confidenceThreshold = 0.50f
    @Volatile private var closedEyeClassIndex = 0
    @Volatile private var perclosWindowSeconds = 60
    @Volatile private var perclosThresholdPercent = 60
    @Volatile private var microsleepThresholdSeconds = 2

    private var cameraProvider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var modelSession: OrtSession? = null
    private var modelInputName: String? = null
    private var modelReady = false
    private var firstAnalysisTimeMs = 0L
    private var lastAnalysisTimeMs = 0L
    private var closedSinceMs: Long? = null
    private var reportedDrowsy = false
    private val samples = ArrayDeque<Sample>()

    fun isCameraRunning(): Boolean = cameraRunning
    fun isModelReady(): Boolean = modelReady

    fun setOptions(
        yolo: Boolean,
        perclos: Boolean,
        microsleep: Boolean,
        confidence: Float,
        closedClassIndex: Int,
        windowSeconds: Int,
        perclosThreshold: Int,
        microsleepSeconds: Int
    ) {
        yoloEnabled = yolo
        perclosEnabled = perclos
        microsleepEnabled = microsleep
        confidenceThreshold = confidence.coerceIn(0.10f, 0.99f)
        this.closedEyeClassIndex = closedClassIndex.coerceIn(0, 1)
        perclosWindowSeconds = windowSeconds.coerceIn(10, 60)
        perclosThresholdPercent = perclosThreshold.coerceIn(20, 80)
        microsleepThresholdSeconds = microsleepSeconds.coerceIn(1, 5)
        if (!yolo || (!perclos && !microsleep)) {
            synchronized(temporalLock) {
                samples.clear()
                closedSinceMs = null
                firstAnalysisTimeMs = 0L
                if (reportedDrowsy) {
                    reportedDrowsy = false
                    postDrowsinessChanged(false)
                }
            }
        }
    }

    fun downloadOrLoadModel() {
        if (!modelDownloadRunning.compareAndSet(false, true)) {
            postStatus("YOLO model is already loading or downloading.")
            return
        }
        modelExecutor.execute {
            try {
                postStatus("Checking the trained YOLO eye model...")
                val modelFile = File(context.filesDir, MODEL_FILE_NAME)
                if (!modelFile.exists() || sha256(modelFile) != MODEL_SHA256) {
                    modelReady = false
                    val tempFile = File(context.filesDir, "$MODEL_FILE_NAME.download")
                    tempFile.delete()
                    val copiedBundledModel = try {
                        context.assets.open(MODEL_FILE_NAME).use { input ->
                            FileOutputStream(tempFile).use { output -> input.copyTo(output) }
                        }
                        postStatus("Copying the trained YOLO model bundled in this APK...")
                        true
                    } catch (_: java.io.FileNotFoundException) {
                        false
                    }
                    if (!copiedBundledModel) {
                        postStatus("Downloading the trained eye model (about 12 MB)...")
                        downloadModel(tempFile)
                    }
                    val downloadedHash = sha256(tempFile)
                    if (!downloadedHash.equals(MODEL_SHA256, ignoreCase = true)) {
                        tempFile.delete()
                        throw IllegalStateException("Model checksum did not match. Download rejected.")
                    }
                    if (modelFile.exists()) modelFile.delete()
                    if (!tempFile.renameTo(modelFile)) {
                        tempFile.copyTo(modelFile, overwrite = true)
                        tempFile.delete()
                    }
                }
                val env = OrtEnvironment.getEnvironment()
                val options = OrtSession.SessionOptions()
                options.setIntraOpNumThreads(2)
                val loadedSession = try {
                    env.createSession(modelFile.absolutePath, options)
                } finally {
                    options.close()
                }
                synchronized(this) {
                    modelSession?.close()
                    modelSession = loadedSession
                    modelInputName = loadedSession.inputNames.firstOrNull()
                    modelReady = modelInputName != null
                }
                if (!modelReady) throw IllegalStateException("The model input could not be identified.")
                postStatus("Trained YOLO eye model is ready. Verify the open/closed class mapping with a parked test.")
            } catch (e: Exception) {
                modelReady = false
                postStatus("YOLO model unavailable: ${e.message ?: "unknown error"}")
            } finally {
                modelDownloadRunning.set(false)
            }
        }
    }

    private fun downloadModel(destination: File) {
        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000
            readTimeout = 60000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "VisionEx-Android/0.0.3")
        }
        try {
            connection.connect()
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("Model download returned HTTP ${connection.responseCode}.")
            }
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } >= 0) {
                        if (bytesRead > 0) output.write(buffer, 0, bytesRead)
                    }
                    output.fd.sync()
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val bytes = ByteArray(16 * 1024)
            var count: Int
            while (input.read(bytes).also { count = it } >= 0) {
                if (count > 0) digest.update(bytes, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun startCamera(frontCamera: Boolean) {
        cameraRequested = true
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            postStatus("Allow camera permission before starting the preview.")
            return
        }
        postStatus("Opening ${if (frontCamera) "front" else "back"} camera...")
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            if (!cameraRequested) return@addListener
            try {
                val provider = providerFuture.get()
                cameraProvider = provider
                val selector = if (frontCamera) CameraSelector.DEFAULT_FRONT_CAMERA
                else CameraSelector.DEFAULT_BACK_CAMERA
                if (!provider.hasCamera(selector)) {
                    cameraRunning = false
                    postStatus("This device does not have the selected camera.")
                    return@addListener
                }
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val analyzer = ImageAnalysis.Builder()
                    .setTargetResolution(Size(640, 480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analyzer.setAnalyzer(cameraExecutor) { frame -> analyzeFrame(frame) }
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, selector, preview, analyzer)
                analysis = analyzer
                cameraRunning = true
                synchronized(temporalLock) {
                    samples.clear()
                    firstAnalysisTimeMs = 0L
                    lastAnalysisTimeMs = 0L
                    closedSinceMs = null
                }
                postStatus(
                    if (modelReady) "Camera running. YOLO and enabled temporal checks are active."
                    else "Camera running. Download/load the trained YOLO model to enable eye detection."
                )
            } catch (e: Exception) {
                cameraRunning = false
                postStatus("Could not start camera: ${e.message ?: "camera error"}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stopCamera() {
        cameraRequested = false
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {
            // Ignore close races during navigation or activity teardown.
        }
        analysis = null
        cameraRunning = false
        synchronized(temporalLock) {
            samples.clear()
            closedSinceMs = null
            firstAnalysisTimeMs = 0L
            lastAnalysisTimeMs = 0L
        }
        if (reportedDrowsy) {
            reportedDrowsy = false
            postDrowsinessChanged(false)
        }
        postStatus("Camera stopped.")
    }

    /**
     * Acknowledge the current phone alert without clearing a live camera result.
     * If the camera still observes drowsiness on the next frame, it can raise a new
     * state transition. This avoids a stale app-side camera state.
     */
    fun acknowledgeCurrentAlert() {
        if (reportedDrowsy) {
            reportedDrowsy = false
            postDrowsinessChanged(false)
        }
    }

    private fun analyzeFrame(frame: ImageProxy) {
        try {
            if (!cameraRequested || !cameraRunning || !yoloEnabled || !modelReady) return
            val now = System.currentTimeMillis()
            if (now - lastAnalysisTimeMs < 200L) return
            lastAnalysisTimeMs = now
            val session = modelSession ?: return
            val inputName = modelInputName ?: return
            val sourceBitmap = imageProxyToBitmap(frame)
            val rotated = rotateBitmap(sourceBitmap, frame.imageInfo.rotationDegrees)
            if (rotated !== sourceBitmap) sourceBitmap.recycle()
            val result = try {
                runYolo(session, inputName, rotated, confidenceThreshold, closedEyeClassIndex)
            } finally {
                rotated.recycle()
            }
            processTemporalResult(result.first, result.second, now)
        } catch (e: Exception) {
            postStatus("Camera analysis issue: ${e.message ?: "unknown error"}")
        } finally {
            frame.close()
        }
    }

    private fun processTemporalResult(state: EyeState, confidence: Float, now: Long) {
        var perclos: Float? = null
        var closureSeconds = 0f
        var shouldReportDrowsy: Boolean? = null
        synchronized(temporalLock) {
            if (firstAnalysisTimeMs == 0L) firstAnalysisTimeMs = now
            if (state == EyeState.CLOSED) {
                if (closedSinceMs == null) closedSinceMs = now
            } else {
                closedSinceMs = null
            }
            closureSeconds = closedSinceMs?.let { (now - it) / 1000f } ?: 0f

            samples.addLast(Sample(now, when (state) {
                EyeState.CLOSED -> true
                EyeState.OPEN -> false
                EyeState.UNKNOWN -> null
            }))
            val cutoff = now - perclosWindowSeconds * 1000L
            while (samples.isNotEmpty() && samples.first.timeMs < cutoff) samples.removeFirst()
            val valid = samples.filter { it.closed != null }
            val validCoverageEnough = valid.size >= max(10, perclosWindowSeconds * 2)
            if (valid.isNotEmpty()) {
                perclos = valid.count { it.closed == true }.toFloat() / valid.size * 100f
            }
            val fullWindowObserved = firstAnalysisTimeMs > 0L &&
                now - firstAnalysisTimeMs >= perclosWindowSeconds * 1000L
            val microsleepTriggered = microsleepEnabled &&
                closureSeconds >= microsleepThresholdSeconds.toFloat()
            val perclosTriggered = perclosEnabled && fullWindowObserved && validCoverageEnough &&
                perclos != null && perclos!! >= perclosThresholdPercent.toFloat()
            val drowsyNow = microsleepTriggered || perclosTriggered
            if (drowsyNow != reportedDrowsy) {
                reportedDrowsy = drowsyNow
                shouldReportDrowsy = drowsyNow
            }
        }
        if (shouldReportDrowsy != null) postDrowsinessChanged(shouldReportDrowsy!!)
        postMetrics(
            CameraFrameMetrics(
                eyeState = state,
                confidence = confidence,
                perclosPercent = perclos,
                closedDurationSeconds = closureSeconds,
                modelReady = modelReady,
                drowsy = reportedDrowsy
            )
        )
    }

    private fun runYolo(
        session: OrtSession,
        inputName: String,
        original: Bitmap,
        threshold: Float,
        closedClass: Int
    ): Pair<EyeState, Float> {
        val boxed = letterbox(original)
        val pixels = IntArray(MODEL_INPUT_SIZE * MODEL_INPUT_SIZE)
        boxed.bitmap.getPixels(pixels, 0, MODEL_INPUT_SIZE, 0, 0, MODEL_INPUT_SIZE, MODEL_INPUT_SIZE)
        val buffer = ByteBuffer.allocateDirect(4 * 3 * pixels.size).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val planeSize = pixels.size
        for (i in pixels.indices) {
            val color = pixels[i]
            buffer.put(i, Color.red(color) / 255f)
            buffer.put(planeSize + i, Color.green(color) / 255f)
            buffer.put(2 * planeSize + i, Color.blue(color) / 255f)
        }
        buffer.position(0)
        boxed.bitmap.recycle()
        val inputTensor = OnnxTensor.createTensor(
            OrtEnvironment.getEnvironment(),
            buffer,
            longArrayOf(1, 3, MODEL_INPUT_SIZE.toLong(), MODEL_INPUT_SIZE.toLong())
        )
        try {
            session.run(mapOf(inputName to inputTensor)).use { outputs ->
                val output = outputs[0] as OnnxTensor
                val info = output.info as TensorInfo
                val shape = info.shape
                if (shape.size != 3) return Pair(EyeState.UNKNOWN, 0f)
                val channelsFirst = shape[1] <= 32L && shape[2] > shape[1]
                val channels = if (channelsFirst) shape[1].toInt() else shape[2].toInt()
                val anchors = if (channelsFirst) shape[2].toInt() else shape[1].toInt()
                if (channels < 6) return Pair(EyeState.UNKNOWN, 0f)
                val outputBuffer = output.floatBuffer.duplicate()
                var bestOpen = 0f
                var bestClosed = 0f
                var openBoxes = 0
                var closedBoxes = 0
                val candidates = ArrayList<Candidate>()
                for (i in 0 until anchors) {
                    val x = valueAt(outputBuffer, channelsFirst, channels, anchors, 0, i)
                    val y = valueAt(outputBuffer, channelsFirst, channels, anchors, 1, i)
                    val w = valueAt(outputBuffer, channelsFirst, channels, anchors, 2, i)
                    val h = valueAt(outputBuffer, channelsFirst, channels, anchors, 3, i)
                    val score0 = valueAt(outputBuffer, channelsFirst, channels, anchors, 4, i)
                    val score1 = valueAt(outputBuffer, channelsFirst, channels, anchors, 5, i)
                    val classIndex = if (score0 >= score1) 0 else 1
                    val score = max(score0, score1)
                    if (score < threshold) continue
                    val cx = if (x <= 2f && w <= 2f) x * MODEL_INPUT_SIZE else x
                    val cy = if (y <= 2f && h <= 2f) y * MODEL_INPUT_SIZE else y
                    val bw = if (x <= 2f && w <= 2f) w * MODEL_INPUT_SIZE else w
                    val bh = if (y <= 2f && h <= 2f) h * MODEL_INPUT_SIZE else h
                    val left = ((cx - bw / 2f - boxed.padX) / boxed.scale).coerceIn(0f, original.width.toFloat())
                    val top = ((cy - bh / 2f - boxed.padY) / boxed.scale).coerceIn(0f, original.height.toFloat())
                    val right = ((cx + bw / 2f - boxed.padX) / boxed.scale).coerceIn(0f, original.width.toFloat())
                    val bottom = ((cy + bh / 2f - boxed.padY) / boxed.scale).coerceIn(0f, original.height.toFloat())
                    if (right <= left || bottom <= top) continue
                    candidates += Candidate(RectF(left, top, right, bottom), classIndex, score)
                    if (classIndex == closedClass) {
                        closedBoxes++
                        bestClosed = max(bestClosed, score)
                    } else {
                        openBoxes++
                        bestOpen = max(bestOpen, score)
                    }
                }
                val filtered = nonMaximumSuppression(
                    candidates.sortedByDescending { it.score }.take(300),
                    0.45f
                )
                if (filtered.isEmpty()) return Pair(EyeState.UNKNOWN, 0f)
                val closedCount = filtered.count { it.classIndex == closedClass }
                val openCount = filtered.count { it.classIndex != closedClass }
                val state = when {
                    closedCount > openCount -> EyeState.CLOSED
                    openCount > closedCount -> EyeState.OPEN
                    bestClosed > bestOpen + 0.08f -> EyeState.CLOSED
                    bestOpen > bestClosed + 0.08f -> EyeState.OPEN
                    else -> EyeState.UNKNOWN
                }
                val bestScore = when (state) {
                    EyeState.CLOSED -> bestClosed
                    EyeState.OPEN -> bestOpen
                    EyeState.UNKNOWN -> max(bestClosed, bestOpen)
                }
                return Pair(state, bestScore)
            }
        } finally {
            inputTensor.close()
        }
    }

    private fun valueAt(
        buffer: FloatBuffer,
        channelsFirst: Boolean,
        channels: Int,
        anchors: Int,
        channel: Int,
        anchor: Int
    ): Float {
        val index = if (channelsFirst) channel * anchors + anchor else anchor * channels + channel
        return buffer.get(index)
    }

    private fun nonMaximumSuppression(input: List<Candidate>, iouThreshold: Float): List<Candidate> {
        val remaining = input.sortedByDescending { it.score }.toMutableList()
        val kept = ArrayList<Candidate>()
        while (remaining.isNotEmpty() && kept.size < 20) {
            val current = remaining.removeAt(0)
            kept += current
            val iterator = remaining.iterator()
            while (iterator.hasNext()) {
                val candidate = iterator.next()
                if (candidate.classIndex == current.classIndex &&
                    intersectionOverUnion(candidate.box, current.box) > iouThreshold
                ) iterator.remove()
            }
        }
        return kept
    }

    private fun intersectionOverUnion(a: RectF, b: RectF): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        val intersection = max(0f, right - left) * max(0f, bottom - top)
        val union = a.width() * a.height() + b.width() * b.height() - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    private fun letterbox(source: Bitmap): LetterboxedBitmap {
        val scale = min(
            MODEL_INPUT_SIZE.toFloat() / source.width,
            MODEL_INPUT_SIZE.toFloat() / source.height
        )
        val scaledWidth = (source.width * scale).toInt().coerceAtLeast(1)
        val scaledHeight = (source.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, true)
        val canvasBitmap = Bitmap.createBitmap(MODEL_INPUT_SIZE, MODEL_INPUT_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBitmap)
        canvas.drawColor(Color.rgb(114, 114, 114))
        val padX = (MODEL_INPUT_SIZE - scaledWidth) / 2f
        val padY = (MODEL_INPUT_SIZE - scaledHeight) / 2f
        canvas.drawBitmap(scaled, padX, padY, Paint(Paint.FILTER_BITMAP_FLAG))
        scaled.recycle()
        return LetterboxedBitmap(canvasBitmap, scale, padX, padY)
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val width = image.width
        val height = image.height
        val output = IntArray(width * height)
        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride
        for (row in 0 until height) {
            for (col in 0 until width) {
                val y = (yBuffer.get(row * yRowStride + col * yPixelStride).toInt() and 0xff) - 16
                val u = (uBuffer.get((row / 2) * uRowStride + (col / 2) * uPixelStride).toInt() and 0xff) - 128
                val v = (vBuffer.get((row / 2) * vRowStride + (col / 2) * vPixelStride).toInt() and 0xff) - 128
                val yy = max(0, y) * 1192
                val r = ((yy + 1634 * v) shr 10).coerceIn(0, 255)
                val g = ((yy - 833 * v - 400 * u) shr 10).coerceIn(0, 255)
                val b = ((yy + 2066 * u) shr 10).coerceIn(0, 255)
                output[row * width + col] = Color.rgb(r, g, b)
            }
        }
        return Bitmap.createBitmap(output, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun rotateBitmap(source: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return source
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun postStatus(message: String) {
        mainHandler.post { onStatus(message) }
    }

    private fun postDrowsinessChanged(drowsy: Boolean) {
        mainHandler.post { onDrowsinessChanged(drowsy) }
    }

    private fun postMetrics(metrics: CameraFrameMetrics) {
        mainHandler.post { onMetrics(metrics) }
    }

    fun close() {
        stopCamera()
        cameraExecutor.shutdownNow()
        modelExecutor.shutdownNow()
        try { modelSession?.close() } catch (_: Exception) {}
        modelSession = null
        modelReady = false
    }
}
