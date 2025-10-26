package lv.spele.objectdetectionapp

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Bundle
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import lv.spele.objectdetectionapp.ui.theme.ObjectDetectionAppTheme
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.FileInputStream
import java.nio.*
import java.nio.channels.FileChannel
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class MainActivity : ComponentActivity() {

    private lateinit var interpreter: Interpreter
    private var lastPayloadHash: Int = 0
    private lateinit var labels: List<String>
    private val httpClient by lazy { OkHttpClient() }
    private val netExecutor = Executors.newSingleThreadExecutor()
    @Volatile
    private var isSending = false

    // vienkāršs debouncers, lai nesūtītu pārāk bieži
    @Volatile
    private var lastSendAt = 0L
    private val sendMinIntervalMs = 800L  // pieskaidro pēc vajadzības
    private val boxes = mutableStateListOf<RectF>()            // preview-space rects
    private val detectedLabels = mutableStateListOf<String>()
    private val confidences = mutableStateListOf<Float>()

    private var fps by mutableStateOf(0f)
    private var lastFrameTime = System.currentTimeMillis()
    private var previewWidth by mutableStateOf(1)
    private var previewHeight by mutableStateOf(1)
    private var detectionStatus by mutableStateOf("No detections")
    private val debugLog = mutableStateListOf<String>()
    private var modelShape: IntArray = intArrayOf()

    // vienā vietā maināms slieksnis
    private val confidenceThreshold = 0.70f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 100)
        }

        // ielādē modeli (float16 arī OK — ievade joprojām float32)
        interpreter = Interpreter(loadModelFile("best_float16.tflite"))

        modelShape = interpreter.getOutputTensor(0).shape()
        appendLog("Model output shape: ${modelShape.joinToString()}")

        labels = try {
            assets.open("labels.txt").bufferedReader().readLines()
        } catch (_: Exception) {
            listOf("unknown")
        }

        appendLog("🚀 App started. Model & labels loaded (${labels.size} classes).")

        setContent {
            ObjectDetectionAppTheme {
                CameraPreviewWithBoxes()
            }
        }
    }

    private fun appendLog(msg: String) {
        val line = "[${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())}] $msg"
        if (Looper.myLooper() == Looper.getMainLooper()) {
            debugLog.add(line)
            if (debugLog.size > 100) debugLog.removeAt(0)
        } else {
            runOnUiThread {
                debugLog.add(line)
                if (debugLog.size > 100) debugLog.removeAt(0)
            }
        }
    }


    private fun loadModelFile(name: String): MappedByteBuffer {
        val fd = assets.openFd(name)
        val stream = FileInputStream(fd.fileDescriptor)
        val channel = stream.channel
        return channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
    }

    @Composable
    fun CameraPreviewWithBoxes() {
        var bitmap by remember { mutableStateOf<Bitmap?>(null) }

        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(factory = { ctx ->
                val previewView = androidx.camera.view.PreviewView(ctx).apply {
                    // kritiski, lai mūsu overlay mērogošana sakristu ar renderi
                    scaleType = androidx.camera.view.PreviewView.ScaleType.FIT_CENTER
                }
                val providerFuture = ProcessCameraProvider.getInstance(ctx)
                providerFuture.addListener({
                    val provider = providerFuture.get()

                    val preview = Preview.Builder().build().apply {
                        setSurfaceProvider(previewView.surfaceProvider)
                    }

                    val analyzer = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also {
                            it.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                                val bmp = imageProxyToBitmap(imageProxy) // ar rotācijas labošanu
                                bitmap = bmp
                                if (modelShape.contentEquals(intArrayOf(1, 300, 6)))
                                    runYoloDetection(bmp)
                                else
                                    runYolo10Detection(bmp)
                                imageProxy.close()
                            }
                        }

                    provider.unbindAll()
                    provider.bindToLifecycle(
                        this@MainActivity,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analyzer
                    )
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged {
                        previewWidth = it.width
                        previewHeight = it.height
                    })

            // Zīmē rāmjus (jau PREVIEW koordinātēs)
            Canvas(modifier = Modifier.fillMaxSize()) {
                val paint = Paint().apply {
                    color = Color.RED
                    textSize = 36f
                    style = Paint.Style.FILL
                    isAntiAlias = true
                    setShadowLayer(4f, 2f, 2f, Color.BLACK)
                }
                for (i in boxes.indices) {
                    val rect = boxes[i]
                    val label = detectedLabels.getOrNull(i) ?: "?"
                    val conf = ((confidences.getOrNull(i) ?: 0f) * 100).toInt()
                    drawRect(
                        color = ComposeColor.Red,
                        topLeft = Offset(rect.left, rect.top),
                        size = Size(rect.width(), rect.height()),
                        style = Stroke(width = 5f)
                    )
                    drawContext.canvas.nativeCanvas.drawText(
                        "$label $conf%",
                        rect.left,
                        max(40f, rect.top - 10),
                        paint
                    )
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
            ) {
                Text(
                    text = "FPS: ${"%.1f".format(fps)}",
                    color = ComposeColor.White,
                    fontSize = 16.sp
                )
                Text(
                    text = detectionStatus,
                    color = ComposeColor.Yellow,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(180.dp)
                    .background(ComposeColor.Black.copy(alpha = 0.6f))
                    .verticalScroll(rememberScrollState())
                    .padding(8.dp)
            ) {
                for (entry in debugLog) {
                    Text(
                        text = entry,
                        color = ComposeColor.LightGray,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }


    private fun sendDetectionToSupabase(
        label: String,
        confidence: Float,
        rectFrame: RectF,
        frame: Bitmap
    ) {
        netExecutor.execute {
            try {
                val now = System.currentTimeMillis()

                // Sagatavojam drošu Base64 JPEG
                val imageBase64 = try {
                    val out = java.io.ByteArrayOutputStream()
                    val rgbBitmap = if (frame.config != Bitmap.Config.RGB_565) {
                        frame.copy(Bitmap.Config.RGB_565, false)
                    } else frame
                    rgbBitmap.compress(Bitmap.CompressFormat.JPEG, 60, out)
                    rgbBitmap.recycle()
                    val bytes = out.toByteArray()
                    android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                } catch (e: Exception) {
                    appendLog("❌ Base64 error: ${e.message}")
                    null
                }

                // JSON payload
                val json = org.json.JSONObject().apply {
                    put("label", label)
                    put("confidence", confidence.toDouble())
                    put("timestamp", now)
                    put("left", rectFrame.left.toDouble())
                    put("top", rectFrame.top.toDouble())
                    put("right", rectFrame.right.toDouble())
                    put("bottom", rectFrame.bottom.toDouble())
                    put("device_id", android.os.Build.MODEL)
                    if (imageBase64 != null) put("image_base64", imageBase64)
                }

                val body = json.toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaType())

                val req = Request.Builder()
                    .url("${BuildConfig.SUPABASE_URL}/rest/v1/cipari")
                    .addHeader("apikey", BuildConfig.SUPABASE_KEY)
                    .addHeader("Authorization", "Bearer ${BuildConfig.SUPABASE_KEY}")
                    .addHeader("Content-Type", "application/json")
                    .post(body)
                    .build()

                httpClient.newCall(req).execute().use { resp ->
                    runOnUiThread {
                        if (resp.isSuccessful)
                            appendLog("✅ Supabase OK: '$label' ${(confidence * 100).toInt()}%")
                        else
                            appendLog("⚠️ Supabase HTTP ${resp.code}: ${resp.message}")
                    }
                }
            } catch (e: OutOfMemoryError) {
                runOnUiThread { appendLog("💥 OutOfMemory sūtot uz Supabase") }
            } catch (e: Exception) {
                runOnUiThread { appendLog("❌ sendDetection crash: ${e.message}") }
            } finally {
                isSending = false
            }
        }
    }


    // Bitmap ar korektu rotāciju
    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = java.io.ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 90, out)
        val bmp = BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())

        val rotation = image.imageInfo.rotationDegrees
        if (rotation != 0) {
            val m = Matrix().apply { postRotate(rotation.toFloat()) }
            return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        return bmp
    }

    // Letterbox uz 640x640
    data class LetterboxParams(val scale: Float, val padX: Float, val padY: Float)

    private fun letterbox(src: Bitmap, dst: Int = 640): Pair<Bitmap, LetterboxParams> {
        val inW = src.width.toFloat()
        val inH = src.height.toFloat()
        val r = min(dst / inW, dst / inH)
        val newW = (inW * r).toInt()
        val newH = (inH * r).toInt()
        val resized = Bitmap.createScaledBitmap(src, newW, newH, true)
        val out = Bitmap.createBitmap(dst, dst, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.BLACK)
        val padX = ((dst - newW) / 2f)
        val padY = ((dst - newH) / 2f)
        c.drawBitmap(resized, padX, padY, null)
        return out to LetterboxParams(r, padX, padY)
    }

    private fun shouldSend(label: String, conf: Float, r: RectF): Boolean {
        val rounded = RectF(
            (r.left / 4).toInt() * 4f, (r.top / 4).toInt() * 4f,
            (r.right / 4).toInt() * 4f, (r.bottom / 4).toInt() * 4f
        )
        val h = Objects.hash(
            label,
            (conf * 100).toInt(),
            rounded.left,
            rounded.top,
            rounded.right,
            rounded.bottom
        )
        return if (h != lastPayloadHash) {
            lastPayloadHash = h; true
        } else false
    }

    private fun cropDetection(frame: Bitmap, rect: RectF): Bitmap? {
        return try {
            val x = rect.left.toInt().coerceIn(0, frame.width - 1)
            val y = rect.top.toInt().coerceIn(0, frame.height - 1)
            val w = rect.width().toInt().coerceAtLeast(1).coerceAtMost(frame.width - x)
            val h = rect.height().toInt().coerceAtLeast(1).coerceAtMost(frame.height - y)
            Bitmap.createBitmap(frame, x, y, w, h)
        } catch (_: Exception) {
            null
        }
    }


    // Palīgfunkcija: kadra (frame) koordinātas → PreviewView koordinātas (FIT_CENTER)
    private fun mapFrameRectToPreview(
        rectFrame: RectF,
        frameW: Int,
        frameH: Int,
        previewW: Int,
        previewH: Int
    ): RectF {
        val scale = min(previewW / frameW.toFloat(), previewH / frameH.toFloat())
        val drawW = frameW * scale
        val drawH = frameH * scale
        val offsetX = (previewW - drawW) / 2f
        val offsetY = (previewH - drawH) / 2f

        return RectF(
            offsetX + rectFrame.left * scale,
            offsetY + rectFrame.top * scale,
            offsetX + rectFrame.right * scale,
            offsetY + rectFrame.bottom * scale
        )
    }


    // --- YOLO [1,300,6] ---
    private fun runYoloDetection(frame: Bitmap) {
        try {
            val inputSize = 640
            val (lbBitmap, lb) = letterbox(frame, inputSize)

            // Ievade: ByteBuffer (float32, NHWC)
            val inputBuffer = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4)
            inputBuffer.order(ByteOrder.nativeOrder())
            for (y in 0 until inputSize) for (x in 0 until inputSize) {
                val p = lbBitmap.getPixel(x, y)
                inputBuffer.putFloat(Color.red(p) / 255f)
                inputBuffer.putFloat(Color.green(p) / 255f)
                inputBuffer.putFloat(Color.blue(p) / 255f)
            }

            val outShape = intArrayOf(1, 300, 6)
            val out = TensorBuffer.createFixedSize(outShape, org.tensorflow.lite.DataType.FLOAT32)
            interpreter.run(inputBuffer.rewind(), out.buffer.rewind())
            val t = out.floatArray

            val pickedFrameSpace = mutableListOf<Triple<RectF, String, Float>>() // frame-space

            for (i in 0 until 300) {
                val base = i * 6
                val cx = t[base] * inputSize
                val cy = t[base + 1] * inputSize
                val bw = t[base + 2] * inputSize
                val bh = t[base + 3] * inputSize
                val clsId = t[base + 4].toInt()
                val conf = t[base + 5]
                if (conf < confidenceThreshold) continue

                // 1) box 640-space (letterboxed)
                var left = cx - bw / 2f
                var top = cy - bh / 2f
                var right = cx + bw / 2f
                var bottom = cy + bh / 2f

                // 2) atleterbox → atpakaļ UZ oriģinālā kadra (frame) koordinātēm
                var fx = ((left - lb.padX) / lb.scale).coerceIn(0f, frame.width.toFloat())
                var fy = ((top - lb.padY) / lb.scale).coerceIn(0f, frame.height.toFloat())
                var fx2 = ((right - lb.padX) / lb.scale).coerceIn(0f, frame.width.toFloat())
                var fy2 = ((bottom - lb.padY) / lb.scale).coerceIn(0f, frame.height.toFloat())

                // filtrācija pret sīkiem trokšņiem pēc reālajiem pikseļiem
                if ((fx2 - fx) < 6f || (fy2 - fy) < 6f) continue

                val label = labels.getOrElse(clsId) { "unknown" }
                pickedFrameSpace.add(Triple(RectF(fx, fy, fx2, fy2), label, conf))
            }

            // pārrēķins no FRAME uz PREVIEW koordinātām, tad NMS + UI update
            finishAndRender(pickedFrameSpace, frame.width, frame.height, frame)


        } catch (e: Exception) {
            e.printStackTrace()
            runOnUiThread {
                appendLog("❌ Error: ${e.message}")
                detectionStatus = "Detection error"
            }
        }
    }

    // --- YOLOv10 [1,14,8400] ---
    private fun runYolo10Detection(frame: Bitmap) {
        try {
            val inputSize = 640
            val (lbBitmap, lb) = letterbox(frame, inputSize)

            val inputBuffer = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4)
            inputBuffer.order(ByteOrder.nativeOrder())
            for (y in 0 until inputSize) for (x in 0 until inputSize) {
                val p = lbBitmap.getPixel(x, y)
                inputBuffer.putFloat(Color.red(p) / 255f)
                inputBuffer.putFloat(Color.green(p) / 255f)
                inputBuffer.putFloat(Color.blue(p) / 255f)
            }

            val outShape = intArrayOf(1, 14, 8400)
            val out = TensorBuffer.createFixedSize(outShape, org.tensorflow.lite.DataType.FLOAT32)
            interpreter.run(inputBuffer.rewind(), out.buffer.rewind())
            val t = out.floatArray

            val numChannels = 14
            val numBoxes = 8400
            val pickedFrameSpace = mutableListOf<Triple<RectF, String, Float>>() // frame-space

            for (i in 0 until numBoxes) {
                val x = t[i]
                val y = t[numBoxes + i]
                val w = t[2 * numBoxes + i]
                val h = t[3 * numBoxes + i]

                var bestScore = 0f
                var bestIdx = -1
                for (ch in 4 until numChannels) {
                    val s = t[ch * numBoxes + i]
                    if (s > bestScore) {
                        bestScore = s
                        bestIdx = ch - 4
                    }
                }
                if (bestScore < confidenceThreshold) continue

                val cx = x * inputSize
                val cy = y * inputSize
                val bw = w * inputSize
                val bh = h * inputSize

                var left = cx - bw / 2f
                var top = cy - bh / 2f
                var right = cx + bw / 2f
                var bottom = cy + bh / 2f

                // atpakaļ uz oriģinālo kadru
                var fx = ((left - lb.padX) / lb.scale).coerceIn(0f, frame.width.toFloat())
                var fy = ((top - lb.padY) / lb.scale).coerceIn(0f, frame.height.toFloat())
                var fx2 = ((right - lb.padX) / lb.scale).coerceIn(0f, frame.width.toFloat())
                var fy2 = ((bottom - lb.padY) / lb.scale).coerceIn(0f, frame.height.toFloat())

                if ((fx2 - fx) < 6f || (fy2 - fy) < 6f) continue

                val label = labels.getOrElse(bestIdx) { "unknown" }
                pickedFrameSpace.add(Triple(RectF(fx, fy, fx2, fy2), label, bestScore))
            }

            // pārrēķins no FRAME uz PREVIEW koordinātām, tad NMS + UI update
            finishAndRender(pickedFrameSpace, frame.width, frame.height, frame)


        } catch (e: Exception) {
            e.printStackTrace()
            runOnUiThread {
                appendLog("❌ Error: ${e.message}")
                detectionStatus = "Detection error"
            }
        }
    }

    // Apvieno ciparus, kas atrodas blakus (1 + 2 = 12)
    private fun combineAdjacentNumbers(
        boxes: List<RectF>,
        labels: List<String>,
        confidences: List<Float>
    ): List<Triple<RectF, String, Float>> {
        if (boxes.isEmpty()) return emptyList()

        val result = mutableListOf<Triple<RectF, String, Float>>()
        val sorted = boxes.indices.sortedBy { boxes[it].left }

        var currentRect = boxes[sorted[0]]
        var currentLabel = labels[sorted[0]]
        var currentConf = confidences[sorted[0]]

        for (i in 1 until sorted.size) {
            val idx = sorted[i]
            val rect = boxes[idx]
            val label = labels[idx]
            val conf = confidences[idx]

            val horizGap = rect.left - currentRect.right
            val sameLine =
                kotlin.math.abs(rect.centerY() - currentRect.centerY()) < currentRect.height() * 0.6f

            // ja horizontāli pietiekami tuvu un aptuveni vienā rindā
            if (horizGap in -currentRect.width() * 0.3f..currentRect.width() * 0.3f && sameLine) {
                currentRect = RectF(
                    min(currentRect.left, rect.left),
                    min(currentRect.top, rect.top),
                    max(currentRect.right, rect.right),
                    max(currentRect.bottom, rect.bottom)
                )
                currentLabel += label // apvieno tekstu, piem. "1" + "2" -> "12"
                currentConf = max(currentConf, conf)
            } else {
                result.add(Triple(currentRect, currentLabel, currentConf))
                currentRect = rect
                currentLabel = label
                currentConf = conf
            }
        }
        result.add(Triple(currentRect, currentLabel, currentConf))
        return result
    }

    private fun mapPreviewRectToFrame(
        rectPreview: RectF,
        frameW: Int,
        frameH: Int,
        previewW: Int,
        previewH: Int
    ): RectF {
        val scale = min(previewW / frameW.toFloat(), previewH / frameH.toFloat())
        val drawW = frameW * scale
        val drawH = frameH * scale
        val offsetX = (previewW - drawW) / 2f
        val offsetY = (previewH - drawH) / 2f

        val fl = (rectPreview.left - offsetX) / scale
        val ft = (rectPreview.top - offsetY) / scale
        val fr = (rectPreview.right - offsetX) / scale
        val fb = (rectPreview.bottom - offsetY) / scale

        return RectF(
            fl.coerceIn(0f, frameW.toFloat()),
            ft.coerceIn(0f, frameH.toFloat()),
            fr.coerceIn(0f, frameW.toFloat()),
            fb.coerceIn(0f, frameH.toFloat())
        )
    }

    // Pabeigšana: FRAME → PREVIEW, NMS, FPS, UI
// Pabeigšana: FRAME → PREVIEW, NMS, FPS, UI
    private fun finishAndRender(
        pickedFrameSpace: List<Triple<RectF, String, Float>>,
        frameW: Int,
        frameH: Int,
        frameBitmap: Bitmap
    ) {
        fun iou(a: RectF, b: RectF): Float {
            val interLeft = max(a.left, b.left)
            val interTop = max(a.top, b.top)
            val interRight = min(a.right, b.right)
            val interBottom = min(a.bottom, b.bottom)
            val interArea = max(0f, interRight - interLeft) * max(0f, interBottom - interTop)
            val union = a.width() * a.height() + b.width() * b.height() - interArea
            return if (union > 0f) interArea / union else 0f
        }

        // Non-max suppression
        val nms = mutableListOf<Triple<RectF, String, Float>>()
        for (p in pickedFrameSpace.sortedByDescending { it.third })
            if (nms.none { iou(it.first, p.first) > 0.45f }) nms.add(p)

        // FRAME → PREVIEW
        val mapped = nms.map { triple ->
            val r = mapFrameRectToPreview(triple.first, frameW, frameH, previewWidth, previewHeight)
            Triple(r, triple.second, triple.third)
        }

        // Apvieno ciparus, kas ir blakus
        val merged = combineAdjacentNumbers(
            mapped.map { it.first },
            mapped.map { it.second },
            mapped.map { it.third }
        )

        val now = System.currentTimeMillis()
        val dt = now - lastFrameTime
        if (dt > 0) fps = 1000f / dt
        lastFrameTime = now

        runOnUiThread {
            detectionStatus =
                if (merged.isNotEmpty()) "Objects: ${merged.size}" else "No detections"
            appendLog("Detections: ${merged.size}, FPS=${"%.1f".format(fps)}")
            boxes.clear(); boxes.addAll(merged.map { it.first })
            detectedLabels.clear(); detectedLabels.addAll(merged.map { it.second })
            confidences.clear(); confidences.addAll(merged.map { it.third })
        }

        // 🚀 Sūtīt uz Supabase (konvertē preview-rect uz frame-rect pirms sūtīšanas)
        for (i in merged.indices) {
            val rectPreview = merged[i].first
            val label = merged[i].second
            val conf = merged[i].third

            val rectFrame = mapPreviewRectToFrame(
                rectPreview,
                frameW, frameH,
                previewWidth, previewHeight
            )

            val crop = cropDetection(frameBitmap, rectFrame)

            // Tikai ja: (1) crop izdevās, (2) jauns detekts, (3) šobrīd nesūtām citu
            if (crop != null && shouldSend(label, conf, rectFrame) && !isSending) {
                isSending = true
                runOnUiThread { detectionStatus = "📸 Sending..." }

                // Iepriekšējais kadrs paliek "iefrīzēts"
                sendDetectionToSupabase(label, conf, rectFrame, crop)

                // Pēc pauzes atsāk analīzi
                netExecutor.execute {
                    Thread.sleep(1500) // aptuveni 1.5 sekunde pauze
                    isSending = false
                }
            }
        }
    }
}
