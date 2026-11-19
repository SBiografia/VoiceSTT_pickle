package com.sbiografia.qwenvoice.floating

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.sbiografia.qwenvoice.MainActivity
import com.sbiografia.qwenvoice.R
import com.sbiografia.qwenvoice.asr.AsrEngine
import com.sbiografia.qwenvoice.asr.VoiceRecorder
import com.sbiografia.qwenvoice.core.AppSettings
import com.sbiografia.qwenvoice.core.ClipboardSupport
import com.sbiografia.qwenvoice.core.DictionaryStore
import com.sbiografia.qwenvoice.core.formatClock
import com.sbiografia.qwenvoice.model.ModelCatalog
import com.sbiografia.qwenvoice.model.ModelRepository
import com.sbiografia.qwenvoice.model.ModelSpec
import com.sbiografia.qwenvoice.postprocess.PostProcessContext
import com.sbiografia.qwenvoice.postprocess.PostProcessors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * 화면 위에 떠 있는 플로팅 마이크.
 *
 * 삼성 키보드(천지인 포함)를 그대로 쓰면서, 화면 어딘가에 떠 있는 원형 마이크를 눌러 말하면
 * 인식 결과가 **지금 커서가 있는 입력창**에 들어간다. 글자를 넣는 일은 [VoiceAccessibilityService] 가 한다.
 *
 * - [WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY] 오버레이라 어떤 앱 위에도 뜬다.
 * - 마이크를 끌면 위치가 옮겨지고 마지막 위치를 기억한다(드래그 vs 탭 구분).
 * - 탭하면 녹음 시작/정지. 녹음 중에는 버튼이 빨갛게 바뀌고 배지에 경과 시간이 뜬다.
 * - 인식 파이프라인은 키보드 모드와 같은 [VoiceRecorder](10분·1.3초 무음 컷) → [AsrEngine] →
 *   [PostProcessors](치환 사전·한자 정리) 를 그대로 쓴다.
 * - 접근성 입력이 막히면 클립보드로 복사하고 Toast 로 알린다.
 * - Android 14+ 규칙에 맞춰 `microphone` 타입 포그라운드 서비스로 돈다(알림 필수).
 */
class FloatingMicService : Service() {

    companion object {
        const val ACTION_START = "com.sbiografia.qwenvoice.action.FLOATING_START"
        const val ACTION_STOP = "com.sbiografia.qwenvoice.action.FLOATING_STOP"

        private const val CHANNEL_ID = "floating_mic"
        private const val NOTIFICATION_ID = 1001
        private const val REQUEST_OPEN_APP = 0
        private const val REQUEST_STOP = 1

        /** 이보다 짧은 마지막 토막은 인식하지 않는다(0.1초). */
        private const val MIN_SAMPLES = 1_600

        /** 플로팅 마이크가 떠 있는지. 설정 화면 버튼 표시에 쓴다. */
        @Volatile
        var isRunning: Boolean = false
            private set

        /** 설정 화면에서 켜기. '다른 앱 위에 표시'·마이크 권한 확인은 서비스가 한 번 더 한다. */
        fun start(context: Context) {
            val intent = Intent(context, FloatingMicService::class.java).setAction(ACTION_START)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        /** 설정 화면·알림에서 끄기. */
        fun stop(context: Context) {
            val intent = Intent(context, FloatingMicService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
                .onFailure { runCatching { context.stopService(Intent(context, FloatingMicService::class.java)) } }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var settings: AppSettings
    private val recorder = VoiceRecorder()

    /**
     * 오버레이 뷰를 부풀릴 때 쓰는 테마 적용 컨텍스트.
     * Service 에는 매니페스트의 Material3 테마가 붙지 않아, 그대로 MaterialButton 을 부풀리면
     * "The style on this component requires your app theme to be Theme.MaterialComponents" 로 죽는다.
     */
    private val viewContext: Context by lazy {
        ContextThemeWrapper(this, R.style.Theme_QwenVoice)
    }

    private var windowManager: WindowManager? = null
    private var rootView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var micButton: MaterialButton? = null
    private var badgeView: TextView? = null

    private var isRecording = false
    private var isFinalizing = false
    private var shutdownWhenIdle = false

    // ---------------------------------------------------------------- 청크 인식 큐
    //
    // AsrEngine 은 OfflineRecognizer 하나를 공유하므로 인식은 한 번에 하나씩 순서대로 처리한다.
    // 녹음 스레드는 큐에 넣기만 하므로 녹음은 끊기지 않고, 말이 끊긴 구간마다 글자가 들어간다.

    @Volatile
    private var chunkQueue: Channel<FloatArray>? = null

    private var consumerJob: Job? = null
    private var timerJob: Job? = null

    private var sessionError: String? = null
    private var committedAny = false
    private var clipboardNotified = false

    /** 접근성 입력이 막혔을 때 클립보드에 모아 둠 말. 한 세션의 모든 구간을 합쳐 복사한다. */
    private val clipboardBuffer = StringBuilder()

    private val pendingChunks = AtomicInteger(0)
    private val sessionChunks = AtomicInteger(0)

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        recorder.onChunkReady = { samples -> enqueueChunk(samples) }
        recorder.onAutoStop = { if (isRecording) stopAndTranscribe() }
        recorder.onError = { message -> toast(message) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> requestShutdown()
            else -> startOverlay()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        discardSession()
        removeOverlay()
        isRunning = false
        if (this::settings.isInitialized) settings.floatingMicEnabled = false
        scope.cancel()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 화면 회전 등으로 화면 크기가 바뀌면 마이크가 화면 밖으로 나가지 않게 다시 맞춘다.
        val params = layoutParams ?: return
        val view = rootView ?: return
        clampInto(params)
        runCatching { windowManager?.updateViewLayout(view, params) }
    }

    // ---------------------------------------------------------------- 켜기 / 끄기

    private fun startOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            toast(getString(R.string.floating_toast_need_overlay))
            openApp()
            stopSelf()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            toast(getString(R.string.floating_toast_need_permission))
            openApp()
            stopSelf()
            return
        }
        if (!startForegroundNotification()) {
            stopSelf()
            return
        }
        isRunning = true
        settings.floatingMicEnabled = true
        showOverlay()
    }

    private fun requestShutdown() {
        if (isRecording || isFinalizing) {
            shutdownWhenIdle = true
            if (isRecording) stopAndTranscribe()
        } else {
            shutdown()
        }
    }

    private fun shutdown() {
        discardSession()
        removeOverlay()
        isRunning = false
        settings.floatingMicEnabled = false
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    // ---------------------------------------------------------------- 알림 (포그라운드 서비스)

    private fun startForegroundNotification(): Boolean = try {
        createChannel()
        val notification = buildNotification(getString(R.string.floating_notif_idle))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        true
    } catch (error: Exception) {
        toast(getString(R.string.floating_start_failed, error.message ?: error.javaClass.simpleName))
        false
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.floating_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.floating_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(statusText: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN_APP,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, FloatingMicService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val action = Notification.Action.Builder(
            null,
            getString(R.string.floating_notif_close),
            stopIntent,
        ).build()

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic_24)
            .setContentTitle(getString(R.string.floating_notif_title))
            .setContentText(statusText)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .addAction(action)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    /** 녹음 상태가 바뀔 때만 부른다(잦은 갱신은 시스템이 제한한다). */
    private fun updateNotification(statusText: String) {
        if (!isRunning) return
        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, buildNotification(statusText))
        }
    }

    // ---------------------------------------------------------------- 오버레이 뷰

    private fun showOverlay() {
        if (rootView != null) return
        val manager = getSystemService(WindowManager::class.java) ?: return
        windowManager = manager

        val view = LayoutInflater.from(viewContext).inflate(R.layout.view_floating_mic, null)
        val size = displaySize()
        val savedX = settings.floatingX
        val savedY = settings.floatingY

        val params = WindowManager.LayoutParams().apply {
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            // FLAG_NOT_FOCUSABLE 이 있어야 마이크를 눌러도 삼성 키보드/입력창이 포커스를 잃지 않는다.
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.TOP or Gravity.START
            x = if (savedX == AppSettings.POSITION_UNSET) size.x - dp(96) else savedX
            y = if (savedY == AppSettings.POSITION_UNSET) (size.y * 0.55f).toInt() else savedY
        }
        layoutParams = params
        clampInto(params)

        try {
            manager.addView(view, params)
        } catch (error: Exception) {
            toast(getString(R.string.floating_start_failed, error.message ?: error.javaClass.simpleName))
            layoutParams = null
            stopSelf()
            return
        }

        rootView = view
        micButton = view.findViewById(R.id.floating_mic)
        badgeView = view.findViewById(R.id.floating_badge)
        view.findViewById<View>(R.id.floating_close)?.setOnClickListener { requestShutdown() }
        micButton?.let { attachDragOrTap(it, tapToToggle = true) }
        view.findViewById<View>(R.id.floating_root)?.let { attachDragOrTap(it, tapToToggle = false) }
        applyMicState()
    }

    private fun removeOverlay() {
        val view = rootView
        rootView = null
        micButton = null
        badgeView = null
        layoutParams = null
        if (view != null) runCatching { windowManager?.removeView(view) }
        windowManager = null
    }

    /**
     * 끌면 위치를 옮기고, 끌지 않고 탭하면(마이크 버튼일 때) 녹음을 시작/정지한다.
     * 마지막 위치는 SharedPreferences 에 남겨 다음에 그 자리에 띄운다.
     */
    private fun attachDragOrTap(view: View, tapToToggle: Boolean) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false

        view.setOnTouchListener { touched, event ->
            val params = layoutParams ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    touched.animate().scaleX(0.92f).scaleY(0.92f).setDuration(80).start()
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) dragging = true
                    if (dragging) {
                        params.x = startX + dx
                        params.y = startY + dy
                        clampInto(params)
                        runCatching { windowManager?.updateViewLayout(rootView, params) }
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    touched.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                    if (dragging) {
                        settings.floatingX = params.x
                        settings.floatingY = params.y
                    } else if (tapToToggle && event.actionMasked == MotionEvent.ACTION_UP) {
                        toggleRecording()
                    }
                    dragging = false
                    true
                }

                else -> false
            }
        }
    }

    private fun clampInto(params: WindowManager.LayoutParams) {
        val size = displaySize()
        val width = rootView?.width?.takeIf { it > 0 } ?: dp(88)
        val height = rootView?.height?.takeIf { it > 0 } ?: dp(100)
        params.x = params.x.coerceIn(0, maxOf(0, size.x - width))
        params.y = params.y.coerceIn(0, maxOf(0, size.y - height))
    }

    private fun displaySize(): Point {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = runCatching {
                getSystemService(WindowManager::class.java)?.currentWindowMetrics?.bounds
            }.getOrNull()
            if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
                return Point(bounds.width(), bounds.height())
            }
        }
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        val display = runCatching { getSystemService(WindowManager::class.java)?.defaultDisplay }.getOrNull()
        @Suppress("DEPRECATION")
        if (display != null) display.getRealMetrics(metrics)
        if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
            return Point(metrics.widthPixels, metrics.heightPixels)
        }
        return Point(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- 마이크 모양 / 배지

    private fun applyMicState() {
        val button = micButton ?: return
        when {
            isFinalizing -> {
                button.isEnabled = false
                button.setIconResource(R.drawable.ic_mic_24)
                button.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.finalizing),
                )
                button.contentDescription = getString(R.string.mic_desc_finalizing)
            }

            isRecording -> {
                button.isEnabled = true
                button.setIconResource(R.drawable.ic_stop_24)
                button.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.recording),
                )
                button.contentDescription = getString(R.string.mic_desc_recording)
            }

            else -> {
                button.isEnabled = true
                button.setIconResource(R.drawable.ic_mic_24)
                button.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.brand),
                )
                button.contentDescription = getString(R.string.mic_desc_floating_idle)
            }
        }
    }

    private fun showBadge(text: String) {
        badgeView?.apply {
            this.text = text
            visibility = View.VISIBLE
        }
    }

    private fun hideBadge() {
        badgeView?.visibility = View.GONE
    }

    // ---------------------------------------------------------------- 녹음 / 인식

    private fun toggleRecording() {
        if (isFinalizing) return
        if (isRecording) stopAndTranscribe() else startRecording()
    }

    private fun startRecording() {
        if (isRecording || isFinalizing) return

        val spec = ensureActiveModel()
        if (spec == null) {
            toast(getString(R.string.floating_no_model))
            openApp()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            toast(getString(R.string.floating_toast_need_permission))
            openApp()
            return
        }
        if (!VoiceAccessibilityService.isConnected()) {
            toast(getString(R.string.floating_no_a11y))
        }

        beginSession(spec)
        if (!recorder.start()) {
            discardSession()
            return
        }

        isRecording = true
        committedAny = false
        clipboardNotified = false
        clipboardBuffer.setLength(0)
        applyMicState()
        startTimer()
        updateNotification(getString(R.string.floating_notif_recording, formatClock(0)))
    }

    private fun beginSession(spec: ModelSpec) {
        val queue = Channel<FloatArray>(Channel.UNLIMITED)
        chunkQueue = queue
        pendingChunks.set(0)
        sessionChunks.set(0)
        sessionError = null
        committedAny = false
        consumerJob = scope.launch { consumeChunks(queue, spec) }
    }

    /** 녹음 스레드에서 호출된다. */
    private fun enqueueChunk(samples: FloatArray) {
        if (samples.isEmpty()) return
        val queue = chunkQueue ?: return
        sessionChunks.incrementAndGet()
        pendingChunks.incrementAndGet()
        if (queue.trySend(samples).isFailure) pendingChunks.decrementAndGet()
    }

    private suspend fun consumeChunks(queue: Channel<FloatArray>, spec: ModelSpec) {
        try {
            for (samples in queue) {
                recognizeChunk(samples, spec)
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            sessionError = error.message ?: error.javaClass.simpleName
        }
    }

    private suspend fun recognizeChunk(samples: FloatArray, spec: ModelSpec) {
        val hotwords = DictionaryStore.get(this).hotwords()
        val outcome = try {
            withContext(Dispatchers.IO) {
                runCatching {
                    if (!AsrEngine.isLoaded(spec.id)) {
                        AsrEngine.prepare(applicationContext, spec)
                    }
                    AsrEngine.transcribe(samples, hotwords, VoiceRecorder.SAMPLE_RATE)
                }
            }
        } finally {
            pendingChunks.decrementAndGet()
        }

        val raw = outcome.getOrNull()?.trim().orEmpty()
        if (raw.isEmpty()) {
            outcome.exceptionOrNull()?.let { error ->
                sessionError = error.message ?: error.javaClass.simpleName
            }
            return
        }

        val context = PostProcessContext(
            languageTag = "ko-KR",
            targetPackage = VoiceAccessibilityService.focusedPackage(),
            inputType = VoiceAccessibilityService.focusedInputType(),
        )
        val processed = PostProcessors.process(this, settings, raw, context)
        if (processed.isBlank()) return

        val text = if (settings.appendTrailingSpace) "$processed " else processed
        deliver(text)
    }

    /** 접근성으로 입력창에 넣고, 막히면 클립보드로 복사해 한 번만 알린다. */
    private fun deliver(text: String) {
        if (VoiceAccessibilityService.insertText(text)) {
            committedAny = true
            return
        }
        clipboardBuffer.append(text)
        val copied = ClipboardSupport.copy(this, clipboardBuffer.toString().trimEnd())
        if (!clipboardNotified) {
            clipboardNotified = true
            toast(
                getString(
                    if (copied) R.string.floating_clipboard_copied
                    else R.string.floating_clipboard_failed,
                ),
            )
        }
    }

    private fun stopAndTranscribe() {
        if (!isRecording) {
            if (shutdownWhenIdle) shutdown()
            return
        }
        isRecording = false
        timerJob?.cancel()
        timerJob = null

        val tail = recorder.stop()
        if (tail.isNotEmpty() && (tail.size >= MIN_SAMPLES || sessionChunks.get() > 0)) {
            enqueueChunk(tail)
        }

        val queue = chunkQueue
        if (queue == null || sessionChunks.get() == 0) {
            discardSession()
            hideBadge()
            updateNotification(getString(R.string.floating_notif_idle))
            if (shutdownWhenIdle) shutdown() else toast(getString(R.string.status_too_short))
            return
        }

        isFinalizing = true
        applyMicState()
        showBadge(getString(R.string.floating_finalizing))
        updateNotification(getString(R.string.floating_notif_idle))

        scope.launch {
            queue.close()
            consumerJob?.join()
            isFinalizing = false
            consumerJob = null
            chunkQueue = null
            pendingChunks.set(0)
            sessionChunks.set(0)
            applyMicState()
            hideBadge()

            val failure = sessionError
            val inserted = committedAny
            val notified = clipboardNotified
            sessionError = null
            committedAny = false

            if (failure != null) {
                toast(getString(R.string.status_error, failure))
            } else if (!inserted && !notified) {
                toast(getString(R.string.status_empty_result))
            }
            if (shutdownWhenIdle) shutdown()
        }
    }

    private fun discardSession() {
        timerJob?.cancel()
        timerJob = null
        if (isRecording) {
            isRecording = false
            runCatching { recorder.stop() }
        }
        isFinalizing = false
        consumerJob?.cancel()
        consumerJob = null
        chunkQueue?.cancel()
        chunkQueue = null
        sessionError = null
        committedAny = false
        pendingChunks.set(0)
        sessionChunks.set(0)
        hideBadge()
        applyMicState()
    }

    private fun startTimer() {
        timerJob?.cancel()
        val startedAt = SystemClock.elapsedRealtime()
        timerJob = scope.launch {
            while (isActive && isRecording) {
                val elapsed = formatClock(SystemClock.elapsedRealtime() - startedAt)
                showBadge(
                    getString(
                        if (pendingChunks.get() > 0) R.string.floating_listening_busy
                        else R.string.floating_listening,
                        elapsed,
                    ),
                )
                delay(200)
            }
        }
    }

    // ---------------------------------------------------------------- 모델 / 잡일

    private fun ensureActiveModel(): ModelSpec? {
        val stored = ModelCatalog.byId(settings.activeModelId)
        if (stored != null && ModelRepository.isInstalled(this, stored)) return stored

        val fallback = ModelCatalog.all.firstOrNull { ModelRepository.isInstalled(this, it) }
        if (fallback != null) {
            settings.activeModelId = fallback.id
            return fallback
        }
        return null
    }

    private fun openApp() {
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                },
            )
        }
    }

    private fun toast(message: String) {
        runCatching { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }
}
