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
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 화면 위에 떠 있는 플로팅 마이크.
 *
 * 삼성 키보드(천지인 포함)를 그대로 쓰면서, 화면 어딘가에 떠 있는 원형 마이크를 눌러 말하면
 * 인식 결과가 **지금 커서가 있는 입력창**에 들어간다. 글자를 넣는 일은 [VoiceAccessibilityService] 가 한다.
 *
 * - [WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY] 오버레이라 어떤 앱 위에도 뜬다.
 * - 마이크를 끌면 위치가 옮겨지고 마지막 위치를 기억한다(드래그 vs 탭 구분).
 * - 끌기 시작하면 화면 아래 중앙에 원형 ✕ 닫기 존이 나타난다(갤럭시 플로팅 버블과 같은 방식).
 *   마이크를 그 원 안으로 끌어다 놓으면 플로팅 모드가 꺼지고, 밖에서 놓으면 위치만 저장된다.
 * - 탭하면 녹음 시작/정지. 녹음 중에는 버튼이 빨갛게 바뀌고 배지에 경과 시간이 뜬다.
 * - '키보드가 뜰 때만 표시' 옵션(기본 켜짐)이 켜져 있으면 키보드가 화면에 떠 있을 때만 버튼을 보여 주고,
 *   키보드가 내려가면 숨긴다. 단, 녹음·인식 중에는 키보드가 내려가도 인식이 끝날 때까지 그대로 둔다.
 *   접근성 서비스가 꺼져 있어 키보드를 알 수 없을 때는 예전처럼 항상 보여 준다.
 *   보이기는 오버레이 창을 붙였다/뗐다 하지 않고 [View.VISIBLE]·[View.GONE] 만 바꾼다(깜박임·비용 없음).
 * - 인식 파이프라인은 키보드 모드와 같은 [VoiceRecorder](10분·1.3초 무음 컷) → [AsrEngine] →
 *   [PostProcessors](치환 사전·한자 정리) 를 그대로 쓴다.
 * - 접근성 입력이 막히면 클립보드로 복사하고 Toast 로 알린다.
 * - Android 14+ 규칙에 맞춰 `microphone` 타입 포그라운드 서비스로 돈다(알림 필수).
 */
class FloatingMicService : Service() {

    companion object {
        const val ACTION_START = "com.sbiografia.qwenvoice.action.FLOATING_START"
        const val ACTION_STOP = "com.sbiografia.qwenvoice.action.FLOATING_STOP"

        /** '키보드가 뜰 때만 표시' 설정이 바뀌었을 때 표시 상태만 다시 계산하게 하는 인텐트. */
        const val ACTION_REFRESH_VISIBILITY =
            "com.sbiografia.qwenvoice.action.FLOATING_REFRESH_VISIBILITY"

        private const val CHANNEL_ID = "floating_mic"
        private const val NOTIFICATION_ID = 1001
        private const val REQUEST_OPEN_APP = 0
        private const val REQUEST_STOP = 1

        /** 이보다 짧은 마지막 토막은 인식하지 않는다(0.1초). */
        private const val MIN_SAMPLES = 1_600

        /** 끌기 시작할 때 화면 아래 중앙에 띄우는 닫기 드롭 존(농구 골대)의 한 변 길이. */
        private const val DROP_ZONE_SIZE_DP = 88

        /** 닫기 존을 화면 맨 아래에서 띄우는 높이(제스처·네비게이션 바 위로 올린다). */
        private const val DROP_ZONE_BOTTOM_MARGIN_DP = 140

        /** 원 중심에서 이만큼 더 떨어진 데까지 손가락을 받아 준다(놓치지 않게). */
        private const val DROP_ZONE_SLOP_DP = 20

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

        /**
         * 설정 화면에서 '키보드가 뜰 때만 표시' 스위치를 바꿨을 때 부른다.
         * 켜져 있지 않으면 아무것도 하지 않는다(서비스를 새로 띄우지 않는다).
         */
        fun refreshVisibility(context: Context) {
            if (!isRunning) return
            val intent = Intent(context, FloatingMicService::class.java)
                .setAction(ACTION_REFRESH_VISIBILITY)
            runCatching { context.startService(intent) }
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

    /** 끌기 중에만 떠 있는 하단 닫기 존 뷰(없으면 null). */
    private var dropZoneView: View? = null

    /** 닫기 존 중심의 화면 좌표(px). 판정은 항상 이 값과 `rawX`·`rawY` 의 거리로 한다. */
    private var dropZoneCenterX = 0f
    private var dropZoneCenterY = 0f

    /** 닫기 존이 손가락을 받아 주는 반지름(px). */
    private var dropZoneHitRadius = 0f

    /** 지금 손가락이 닫기 존 안에 있는지(들어갈 때만 진동·강조한다). */
    private var dropZoneHovered = false

    private var lastDropZoneVibrateAt = 0L

    private var isRecording = false
    private var isFinalizing = false
    private var shutdownWhenIdle = false

    /** 오버레이가 지금 화면에 보이는 상태인지(불필요한 visibility 변경을 피하려고 기억한다). */
    private var overlayVisible = true

    /** 키보드 표시 상태를 따라가는 수집 작업. 서비스가 끝나거나 오버레이가 사라지면 취소한다. */
    private var keyboardWatchJob: Job? = null

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
            ACTION_REFRESH_VISIBILITY -> {
                if (isRunning) applyOverlayVisibility() else stopSelf()
            }
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
        // 회전하면 닫기 존의 화면 좌표도 달라지므로 다시 잰다.
        dropZoneView?.let { zone -> zone.post { updateDropZoneGeometry() } }
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
        micButton?.let { attachDragOrTap(it, tapToToggle = true) }
        view.findViewById<View>(R.id.floating_root)?.let { attachDragOrTap(it, tapToToggle = false) }
        applyMicState()

        // 서비스가 막 생겼을 때의 키보드 상태를 바로 반영하고, 그 뒤로는 접근성 서비스가 알려 주는 값을 따른다.
        overlayVisible = true
        applyOverlayVisibility()
        watchKeyboardVisibility()
    }

    /**
     * '키보드가 뜰 때만 표시' 옵션에 맞춰 오버레이를 보이거나 숨긴다.
     *
     * - 옵션이 꺼져 있거나, 접근성 서비스가 연결되어 있지 않아 키보드 상태를 알 수 없으면 항상 보여 준다.
     * - 녹음 중이거나 인식(마무리 변환) 중이면 키보드가 내려가도 숨기지 않는다.
     * - 그 밖에는 키보드가 떠 있을 때만 보여 준다.
     */
    private fun applyOverlayVisibility() {
        val view = rootView ?: return
        val shouldShow = when {
            !settings.floatingOnlyWhenKeyboard -> true
            !VoiceAccessibilityService.isConnected() -> true
            isRecording || isFinalizing -> true
            else -> VoiceAccessibilityService.isKeyboardVisible()
        }
        // 마이크를 숨길 때는 끌기 중이던 닫기 존도 같이 치운다.
        if (!shouldShow) hideDropZone()
        if (shouldShow == overlayVisible) return
        view.visibility = if (shouldShow) View.VISIBLE else View.GONE
        overlayVisible = shouldShow
    }

    /** 접근성 서비스가 알려 주는 키보드 표시 상태를 계속 따라간다. */
    private fun watchKeyboardVisibility() {
        keyboardWatchJob?.cancel()
        keyboardWatchJob = scope.launch {
            // 키보드 상태뿐 아니라 접근성 서비스가 켜졌다/끊겼다는 변화도 다시 그린다.
            combine(
                VoiceAccessibilityService.keyboardVisible,
                VoiceAccessibilityService.accessibilityConnected,
            ) { keyboard, _ -> keyboard }
                .collect { applyOverlayVisibility() }
        }
    }

    private fun removeOverlay() {
        hideDropZone()
        keyboardWatchJob?.cancel()
        keyboardWatchJob = null
        val view = rootView
        rootView = null
        micButton = null
        badgeView = null
        layoutParams = null
        overlayVisible = true
        if (view != null) runCatching { windowManager?.removeView(view) }
        windowManager = null
    }

    /**
     * 끌면 위치를 옮기고, 끌지 않고 탭하면(마이크 버튼일 때) 녹음을 시작/정지한다.
     * 마지막 위치는 SharedPreferences 에 남겨 다음에 그 자리에 띄운다.
     *
     * 끌기 시작 → 하단 닫기 존 표시 → 존 안에서 손을 떼면 플로팅 모드 종료,
     * 존 밖에서 손을 떼면 위치만 저장하고 닫기 존은 사라진다.
     */
    private fun attachDragOrTap(view: View, tapToToggle: Boolean) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        var overDropZone = false

        view.setOnTouchListener { touched, event ->
            val params = layoutParams ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    overDropZone = false
                    touched.animate().scaleX(0.92f).scaleY(0.92f).setDuration(80).start()
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                        // 끌기 시작한 순간에만 하단 닫기 존을 띄운다(평소 화면에는 ✕ 가 없다).
                        showDropZone()
                    }
                    if (dragging) {
                        params.x = startX + dx
                        params.y = startY + dy
                        clampInto(params)
                        runCatching { windowManager?.updateViewLayout(rootView, params) }
                        overDropZone = updateDropZoneHover(event.rawX, event.rawY)
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    touched.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                    val droppedIntoZone = dragging &&
                        event.actionMasked == MotionEvent.ACTION_UP &&
                        overDropZone
                    if (dragging) hideDropZone()
                    overDropZone = false
                    when {
                        // 닫기 존 위에서 손을 뗐다: 플로팅 모드를 끈다(위치는 저장하지 않는다).
                        droppedIntoZone -> {
                            dragging = false
                            toast(getString(R.string.floating_toast_dropped))
                            requestShutdown()
                        }

                        // 그 밖에서 끌다 놓았다: 새 위치만 저장하고 계속 쓴다.
                        dragging -> {
                            dragging = false
                            settings.floatingX = params.x
                            settings.floatingY = params.y
                        }

                        tapToToggle && event.actionMasked == MotionEvent.ACTION_UP -> toggleRecording()
                    }
                    true
                }

                else -> false
            }
        }
    }

    // ---------------------------------------------------------------- 닫기 드롭 존

    /**
     * 끌기 시작할 때 화면 아래 중앙에 띄우는 원형 ✕ 닫기 존.
     *
     * - 창은 [WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE] 이라 터치를 가로채지 않는다.
     *   손가락은 계속 위에 있는 마이크 오버레이가 받고, 위치 판정만 화면 좌표로 한다.
     * - 중심은 화면 아래에서 띄운 위치에 그대로 두고, 붙인 뒤 [View.getLocationOnScreen] 으로 실제 좌표를
     *   다시 재서 기기 해상도·네비게이션 바 높이와 무관하게 맞춘다.
     */
    private fun showDropZone() {
        if (dropZoneView != null) return
        val manager = windowManager ?: return
        val zonePx = dp(DROP_ZONE_SIZE_DP)
        val screen = displaySize()
        val params = WindowManager.LayoutParams().apply {
            width = zonePx
            height = zonePx
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            // NOT_TOUCHABLE: 아래 마이크 오버레이가 계속 손가락을 받는다.
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(DROP_ZONE_BOTTOM_MARGIN_DP)
        }
        val zone = LayoutInflater.from(viewContext).inflate(R.layout.view_floating_drop_zone, null)
        try {
            manager.addView(zone, params)
        } catch (error: Exception) {
            return
        }
        dropZoneView = zone
        dropZoneHovered = false
        // 첫 프레임 전에도 바로 판정할 수 있게 예상값을 먼저 넣고, 레이아웃 뒤 실제 좌표로 덮어쓴다.
        dropZoneCenterX = screen.x / 2f
        dropZoneCenterY = screen.y - dp(DROP_ZONE_BOTTOM_MARGIN_DP) - zonePx / 2f
        dropZoneHitRadius = zonePx / 2f + dp(DROP_ZONE_SLOP_DP)
        zone.post { updateDropZoneGeometry() }
        // 톡 튀어나오는 느낌. 이 창은 터치를 받지 않으므로 애니메이션이 조작을 방해하지 않는다.
        zone.scaleX = 0.7f
        zone.scaleY = 0.7f
        zone.alpha = 0f
        zone.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(140).start()
    }

    private fun hideDropZone() {
        val zone = dropZoneView ?: return
        dropZoneView = null
        dropZoneHovered = false
        runCatching { windowManager?.removeView(zone) }
    }

    /** 닫기 존의 실제 화면 중심과 판정 반지름을 잰다. */
    private fun updateDropZoneGeometry() {
        val zone = dropZoneView ?: return
        if (zone.width <= 0 || zone.height <= 0) return
        val location = IntArray(2)
        zone.getLocationOnScreen(location)
        dropZoneCenterX = location[0] + zone.width / 2f
        dropZoneCenterY = location[1] + zone.height / 2f
        dropZoneHitRadius = maxOf(zone.width, zone.height) / 2f + dp(DROP_ZONE_SLOP_DP)
    }

    /**
     * 손가락(화면 좌표)이 닫기 존 안에 있는지 갱신한다.
     * 안으로 들어오면 빨갛게 바뀌며 살짝 커지고 짧게 진동한다.
     */
    private fun updateDropZoneHover(rawX: Float, rawY: Float): Boolean {
        val zone = dropZoneView ?: return false
        val inside = hypot(rawX - dropZoneCenterX, rawY - dropZoneCenterY) <= dropZoneHitRadius
        if (inside == dropZoneHovered) return inside
        dropZoneHovered = inside
        zone.setBackgroundResource(
            if (inside) R.drawable.bg_drop_zone_active else R.drawable.bg_drop_zone,
        )
        zone.animate()
            .scaleX(if (inside) 1.18f else 1f)
            .scaleY(if (inside) 1.18f else 1f)
            .alpha(1f)
            .setDuration(120)
            .start()
        if (inside) vibrateDropZoneHover()
        return inside
    }

    /** 닫기 존에 처음 들어갈 때의 짧은 진동(부르는 쪽에서 0.15초 간격을 지킨다). */
    private fun vibrateDropZoneHover() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastDropZoneVibrateAt < 150) return
        lastDropZoneVibrateAt = now
        runCatching {
            val vibrator = systemVibrator() ?: return@runCatching
            if (!vibrator.hasVibrator()) return@runCatching
            vibrator.vibrate(VibrationEffect.createOneShot(20L, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    @Suppress("DEPRECATION")
    private fun systemVibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            getSystemService(Vibrator::class.java)
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
        // 녹음이 시작되면 키보드가 내려가도 끝까지 보이게 한다.
        applyOverlayVisibility()
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
        // 인식이 끝날 때까지는 키보드가 내려가도 버튼을 유지한다.
        applyOverlayVisibility()

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
            // 녹음·인식이 모두 끝났다. 키보드가 여전히 없으면 이때 숨긴다.
            applyOverlayVisibility()

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
        applyOverlayVisibility()
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
