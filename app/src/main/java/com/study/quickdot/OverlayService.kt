package com.study.quickdot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.math.hypot

/**
 * 悬浮球服务：常驻半透明圆点。
 * 单击 = 隐藏圆点 -> 截取当前屏幕 -> 发给视觉模型 -> 圆点变成答案；
 * 答案状态下单击 = 弹出解析浮层；长按 = 打开设置页。
 */
class OverlayService : Service() {

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        var isRunning = false
            private set

        private const val CH_ID = "quickdot_service"
        private const val NOTIF_ID = 1
        private const val CAPTURE_DELAY_MS = 300L
        private const val LONG_PRESS_MS = 600L
        private const val DOUBLE_TAP_MS = 300L
        private const val MAX_IMAGE_WIDTH = 1600
        private const val MAX_FRAMES = 3
    }

    enum class State { IDLE, ANSWER, UNCERTAIN, ERROR, WAITING }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("quickdot", MODE_PRIVATE) }
    private lateinit var wm: WindowManager

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenW = 0
    private var screenH = 0
    private var densityDpi = 0

    private var dotView: DotView? = null
    private var dotParams: WindowManager.LayoutParams? = null
    private var panelRoot: FrameLayout? = null
    private var busy = false
    private var longPressFired = false
    private var lastAnswer = ""
    private var lastAnalysis = ""
    private var lastFrameB64: String? = null
    private val pendingFrames = ArrayList<String>()

    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var touchMoved = false
    private val touchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop }
    private var lastTapTime = 0L
    private val longPressRunnable = Runnable {
        longPressFired = true
        openSettings()
    }
    private val singleTapRunnable = Runnable {
        if (busy) {
            toast("识别中…")
            return@Runnable
        }
        when (dotView?.state) {
            State.ANSWER, State.UNCERTAIN -> if (lastAnalysis.isNotEmpty()) showPanel()
            State.WAITING -> {
                if (pendingFrames.isEmpty()) {
                    toast("还没有截到任何一屏，双击截取当前屏幕")
                } else {
                    toast("发送已截的 ${pendingFrames.size} 屏…")
                    sendFrames()
                }
            }
            else -> toast("双击圆点识屏出答案")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        readScreenMetrics()
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra(EXTRA_DATA)
        if (data != null && projection == null) {
            val code = intent!!.getIntExtra(EXTRA_CODE, -1)
            startAsForeground()
            val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = pm.getMediaProjection(code, data!!)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    mainHandler.post { stopSelf() }
                }
            }, mainHandler)
            setupCapture()
            showDot()
            isRunning = true
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        mainHandler.removeCallbacks(longPressRunnable)
        dotView?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        dotView = null
        try { hidePanel() } catch (e: Exception) { }
        try { virtualDisplay?.release() } catch (e: Exception) { }
        try { imageReader?.close() } catch (e: Exception) { }
        try { projection?.stop() } catch (e: Exception) { }
        super.onDestroy()
    }

    // ---------- 启动 ----------

    private fun startAsForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CH_ID, "答题悬浮球", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val settingsPi = android.app.PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CH_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val notif = builder
            .setSmallIcon(R.drawable.ic_stat)
            .setContentIntent(settingsPi)
            .setContentTitle("秒答悬浮球运行中")
            .setContentText("双击圆点识屏，单击看解析，长按进设置")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    @Suppress("DEPRECATION")
    private fun readScreenMetrics() {
        val dm = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        screenW = dm.widthPixels
        screenH = dm.heightPixels
        densityDpi = dm.densityDpi
    }

    private fun setupCapture() {
        val reader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        virtualDisplay = projection?.createVirtualDisplay(
            "quickdot_capture", screenW, screenH, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, mainHandler
        )
    }

    // ---------- 悬浮圆点 ----------

    private fun showDot() {
        if (dotView != null) return
        val v = DotView(this)
        dotView = v
        val size = dp(24)
        dotParams = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(200)
        }
        v.setOnTouchListener { _, ev ->
            handleDotTouch(ev)
            true
        }
        wm.addView(v, dotParams)
    }

    private fun handleDotTouch(ev: MotionEvent) {
        val v = dotView ?: return
        val p = dotParams ?: return
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = ev.rawX
                downRawY = ev.rawY
                startX = p.x
                startY = p.y
                touchMoved = false
                longPressFired = false
                mainHandler.postDelayed(longPressRunnable, LONG_PRESS_MS)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - downRawX
                val dy = ev.rawY - downRawY
                if (!touchMoved && hypot(dx, dy) > touchSlop) {
                    touchMoved = true
                    mainHandler.removeCallbacks(longPressRunnable)
                }
                if (touchMoved) {
                    p.x = startX + dx.toInt()
                    p.y = startY + dy.toInt()
                    wm.updateViewLayout(v, p)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mainHandler.removeCallbacks(longPressRunnable)
                if (!touchMoved && !longPressFired && ev.actionMasked == MotionEvent.ACTION_UP) {
                    handleTap()
                }
            }
        }
    }

    /**
     * 点按分发：300ms 内的第二击 = 双击（识屏出答案）；
     * 否则等满 300ms 按单击处理（查看解析；没有答案时提示先双击）。
     * 长按 600ms = 打开设置。
     */
    private fun handleTap() {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastTapTime < DOUBLE_TAP_MS) {
            lastTapTime = 0L
            mainHandler.removeCallbacks(singleTapRunnable)
            if (busy) toast("识别中…") else captureAndAnswer()
        } else {
            lastTapTime = now
            mainHandler.postDelayed(singleTapRunnable, DOUBLE_TAP_MS)
        }
    }

    // ---------- 识屏 ----------

    private fun captureAndAnswer() {
        val reader = imageReader
        if (reader == null || projection == null) {
            toast("悬浮球未就绪，稍后再试")
            return
        }
        busy = true
        hidePanel()
        // 拼接模式（WAITING）下截屏是"续截一屏"，否则是一次全新的识屏
        val stitching = dotView?.state == State.WAITING
        if (!stitching) {
            pendingFrames.clear()
            lastAnswer = ""
        }
        setDotLabel("", State.IDLE)
        // 截屏前把圆点自己藏起来，避免拍进画面
        dotView?.visibility = View.INVISIBLE
        mainHandler.postDelayed({
            var bmp: Bitmap? = null
            var img: Image? = null
            try {
                img = reader.acquireLatestImage()
                if (img != null) bmp = imageToBitmap(img)
            } catch (e: Exception) {
                bmp = null
            } finally {
                try { img?.close() } catch (e: Exception) { }
            }
            dotView?.visibility = View.VISIBLE
            if (bmp == null) {
                busy = false
                showError("识屏失败，请再点一次试试")
                return@postDelayed
            }
            if (isMostlyBlack(bmp)) {
                busy = false
                setUncertain("屏幕内容受保护或未捕获到画面（黑屏），无法识别题目")
                return@postDelayed
            }
            val scaled = downscale(bmp, MAX_IMAGE_WIDTH)
            val baos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, baos)
            val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
            lastFrameB64 = b64
            if (stitching) {
                if (pendingFrames.size >= MAX_FRAMES) {
                    toast("已达最大拼接数 $MAX_FRAMES 屏，直接作答")
                } else {
                    pendingFrames.add(b64)
                    setDotLabel("…", State.WAITING)
                    busy = false
                    toast("已记下第 ${pendingFrames.size} 屏；继续双击截下一屏，单击立即作答")
                    return@postDelayed
                }
            }
            pendingFrames.clear()
            pendingFrames.add(b64)
            sendFrames()
        }, CAPTURE_DELAY_MS)
    }

    private fun imageToBitmap(img: Image): Bitmap {
        val plane = img.planes[0]
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val rowPadding = rowStride - pixelStride * img.width
        return if (rowPadding == 0) {
            val bmp = Bitmap.createBitmap(img.width, img.height, Bitmap.Config.ARGB_8888)
            val buffer = plane.buffer
            buffer.rewind()
            bmp.copyPixelsFromBuffer(buffer)
            bmp
        } else {
            val bitmapWidth = img.width + rowPadding / pixelStride
            val full = Bitmap.createBitmap(bitmapWidth, img.height, Bitmap.Config.ARGB_8888)
            val buffer = plane.buffer
            buffer.rewind()
            full.copyPixelsFromBuffer(buffer)
            Bitmap.createBitmap(full, 0, 0, img.width, img.height)
        }
    }

    private fun isMostlyBlack(bmp: Bitmap): Boolean {
        val h = (64 * bmp.height / bmp.width.coerceAtLeast(1)).coerceIn(1, 512)
        val small = Bitmap.createScaledBitmap(bmp, 64, h, true)
        val pixels = IntArray(small.width * small.height)
        small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
        var sum = 0L
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            sum += (r + g + b) / 3
        }
        return sum / pixels.size < 6
    }

    private fun downscale(src: Bitmap, maxW: Int): Bitmap =
        if (src.width <= maxW) src
        else Bitmap.createScaledBitmap(src, maxW, src.height * maxW / src.width, true)

    // ---------- 调用接口 ----------

    /** 把当前积累的截图帧发给模型作答（单击"立即作答"或识屏完成时调用） */
    private fun sendFrames() {
        busy = true
        callApi(pendingFrames.toList())
    }

    private fun callApi(images: List<String>, retryTokens: Long? = null) {
        val cfg = ConfigStore.load(prefs)
        val profile = ConfigStore.current(cfg)
        val base = profile.baseUrl.trim()
        val key = profile.apiKey.trim()
        val model = cfg.model.trim()
        if (base.isEmpty() || key.isEmpty() || model.isEmpty()) {
            busy = false
            showError("接口/Key/模型未填全，长按圆点打开设置")
            return
        }
        val tokens = retryTokens ?: ConfigStore.tokenLimit(cfg)
        ApiClient.request(
            ApiClient.chatUrl(base), key,
            ApiClient.answerPayload(model, images, tokens),
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    mainHandler.post {
                        busy = false
                        showError("网络错误：${e.message?.take(60) ?: "未知"}")
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    val body = try {
                        response.body?.string()
                    } catch (e: Exception) {
                        null
                    }
                    if (response.code == 400 && retryTokens == null && tokens > 1024L) {
                        // 个别模型不接受过大的 max_tokens，自动降档重试一次
                        mainHandler.post {
                            toast("当前强度被接口拒绝，自动降档重试…")
                            callApi(images, 1024L)
                        }
                        return
                    }
                    mainHandler.post {
                        busy = false
                        if (!response.isSuccessful || body == null) {
                            showError("接口错误 HTTP ${response.code}")
                            return@post
                        }
                        try {
                            val content = JSONObject(body)
                                .getJSONArray("choices").getJSONObject(0)
                                .getJSONObject("message").getString("content")
                            applyAnswer(content)
                        } catch (e: Exception) {
                            showError("返回解析失败：${body.take(80)}")
                        }
                    }
                }
            }
        )
    }

    private fun applyAnswer(content: String) {
        pendingFrames.clear()
        val (answer, analysis) = ApiClient.parseAnswer(content)
        lastAnswer = answer
        lastAnalysis = analysis.ifEmpty { "(模型未给出解析)" }
        if (answer == "?") {
            setUncertain(lastAnalysis)
        } else {
            setDotLabel(answer, State.ANSWER)
            toast("$answer 已显示，点圆点看解析")
        }
    }

    // ---------- 圆点状态 ----------

    private fun setDotLabel(text: String, state: State) {
        val v = dotView ?: return
        v.label = text
        v.state = state
        val p = dotParams ?: return
        val base = dp(24)
        val w = if (text.length <= 1) base else base + dp(10) * (text.length - 1)
        if (w != p.width) {
            p.width = w
            try { wm.updateViewLayout(v, p) } catch (e: Exception) { }
        }
        v.invalidate()
    }

    private fun setUncertain(reason: String) {
        lastAnswer = "?"
        lastAnalysis = reason
        setDotLabel("?", State.UNCERTAIN)
    }

    private fun showError(msg: String) {
        setDotLabel("!", State.ERROR)
        toast(msg)
    }

    // ---------- 解析浮层 ----------

    private fun showPanel() {
        hidePanel()
        val root = FrameLayout(this)

        val catcher = View(this).apply {
            setBackgroundColor(0x59000000)
            setOnClickListener { hidePanel() }
        }
        root.addView(
            catcher,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )

        val cardBg = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(14).toFloat()
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }
        val header = if (dotView?.state == State.UNCERTAIN) "不确定（模型没把握，原因如下）" else "答案：$lastAnswer"
        val tv = TextView(this).apply {
            text = "$header\n\n$lastAnalysis"
            textSize = 14f
            setTextColor(0xFF222222.toInt())
        }
        val sv = ScrollView(this).apply { addView(tv) }
        card.addView(
            sv,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        // v1.0.1：一键复制答案——只复制 lastAnswer 本身（如 A/ABD/对），不带解析
        val btnCopy = TextView(this).apply {
            text = "复制答案"
            setTextColor(0xFF43A047.toInt())
            textSize = 14f
            setPadding(0, dp(10), dp(16), dp(6))
        }
        btnCopy.setOnClickListener {
            if (lastAnswer.isEmpty() || lastAnswer == "?") {
                toast("本题无确定答案，无法复制")
            } else {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("答案", lastAnswer))
                toast("答案已复制：$lastAnswer")
            }
        }
        val btnStitch = TextView(this).apply {
            text = "拼接下一屏"
            setTextColor(0xFF7E57C2.toInt())
            textSize = 14f
            setPadding(dp(16), dp(10), dp(16), dp(6))
        }
        btnStitch.setOnClickListener {
            val frame = lastFrameB64
            when {
                busy -> toast("正在识别中…")
                frame == null -> toast("还没有截过屏")
                else -> {
                    if (pendingFrames.isEmpty()) pendingFrames.add(frame)
                    hidePanel()
                    setDotLabel("…", State.WAITING)
                    toast("已记下这一屏；滑到下一屏后双击继续截（最多${MAX_FRAMES}屏），单击立即作答")
                }
            }
        }
        val btnRetry = TextView(this).apply {
            text = "再识一题"
            setTextColor(0xFF1E88E5.toInt())
            textSize = 14f
            setPadding(dp(16), dp(10), dp(16), dp(6))
        }
        btnRetry.setOnClickListener {
            hidePanel()
            captureAndAnswer()
        }
        val btnSettings = TextView(this).apply {
            text = "设置"
            setTextColor(0xFF1E88E5.toInt())
            textSize = 14f
            setPadding(dp(16), dp(10), dp(16), dp(6))
        }
        btnSettings.setOnClickListener {
            hidePanel()
            openSettings()
        }
        val btnClose = TextView(this).apply {
            text = "关闭"
            setTextColor(0xFF888888.toInt())
            textSize = 14f
            setPadding(dp(16), dp(10), 0, dp(6))
        }
        btnClose.setOnClickListener { hidePanel() }
        row.addView(btnCopy)
        row.addView(btnStitch)
        row.addView(btnRetry)
        row.addView(btnSettings)
        row.addView(btnClose)
        card.addView(row)

        root.addView(
            card,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(280), Gravity.BOTTOM
            ).apply { setMargins(dp(12), 0, dp(12), dp(20)) }
        )

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(root, p)
        panelRoot = root
    }

    private fun hidePanel() {
        panelRoot?.let {
            try { wm.removeView(it) } catch (e: Exception) { }
        }
        panelRoot = null
    }

    // ---------- 杂项 ----------

    private fun openSettings() {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 圆点视图：圆/胶囊底 + 居中文字，颜色随状态变化 */
    inner class DotView(context: Context) : View(context) {
        private val circle = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }

        var label: String = ""
        var state: State = State.IDLE
            set(value) {
                field = value
                invalidate()
            }

        private fun bgColor(): Int = when (state) {
            State.IDLE -> 0x80333333.toInt()
            State.ANSWER -> 0xE61E88E5.toInt()
            State.UNCERTAIN -> 0xE6FB8C00.toInt()
            State.ERROR -> 0xE6D32F2F.toInt()
            State.WAITING -> 0xE67E57C2.toInt()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            circle.color = bgColor()
            if (w <= h + 2f) {
                canvas.drawCircle(w / 2f, h / 2f, h / 2f - 2f, circle)
            } else {
                canvas.drawRoundRect(2f, 2f, w - 2f, h - 2f, h / 2f, h / 2f, circle)
            }
            if (label.isNotEmpty()) {
                textPaint.textSize = h * when {
                    label.length > 2 -> 0.30f
                    label.length == 2 -> 0.42f
                    else -> 0.52f
                }
                val y = h / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
                canvas.drawText(label, w / 2f, y, textPaint)
            }
        }
    }
}
