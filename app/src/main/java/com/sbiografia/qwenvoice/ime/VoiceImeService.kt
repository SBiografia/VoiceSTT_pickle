package com.sbiografia.qwenvoice.ime

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import com.sbiografia.qwenvoice.MainActivity
import com.sbiografia.qwenvoice.R
import com.sbiografia.qwenvoice.asr.AsrEngine
import com.sbiografia.qwenvoice.asr.Transcriber
import com.sbiografia.qwenvoice.asr.VoiceRecorder
import com.sbiografia.qwenvoice.core.AppSettings
import com.sbiografia.qwenvoice.core.DictionaryStore
import com.sbiografia.qwenvoice.core.TranscriptOutput
import com.sbiografia.qwenvoice.core.formatClock
import com.sbiografia.qwenvoice.databinding.KeyboardViewBinding
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

/**
 * 음성 입력 키보드(IME).
 *
 * - 삼성 키보드의 ⌨/🌐 아이콘으로 전환해 쓰고, "⌨ 키보드 전환" 버튼으로 되돌아온다.
 * - 마이크(가운데 원형 버튼)를 탭하면 녹음이 시작되고, 다시 탭하면 끝난다.
 * - 녹음은 최대 10분. 말이 끊길 때마다 [VoiceRecorder] 가 청크를 내보내고,
 *   이 서비스가 FIFO 큐로 하나씩 인식해 **말하는 도중에도** 입력창에 글자를 꽂는다.
 * - 인식 엔진은 칩으로 고른다: 기기 내 모델(SenseVoice / 0.6B / 1.7B) 또는 Groq Whisper(온라인).
 *   Groq 모드에서 키가 비어 있으면 녹음을 시작하지 않고 설정 화면으로 안내한다(1.5).
 * - 인식 결과는 어느 엔진이든 [PostProcessors] 를 거친다(치환 사전 + 한자 정리).
 */
class VoiceImeService : InputMethodService() {

    private companion object {
        const val HOLD_THRESHOLD_MS = 500L
        const val MIN_SAMPLES = 1_600 // 0.1초
    }

    /** 원형 마이크 버튼의 세 가지 표시 상태. */
    private enum class MicState { IDLE, RECORDING, FINALIZING }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var settings: AppSettings
    private val recorder = VoiceRecorder()

    /**
     * 키보드 뷰를 부풀릴 때 쓰는 테마 적용 컨텍스트.
     *
     * InputMethodService 는 Activity 가 아니라서 매니페스트의 앱 테마가 자동으로 붙지 않는다.
     * 그대로 LayoutInflater.from(this) 로 MaterialButton(Material3 스타일)을 부풀리면
     * "The style on this component requires your app theme to be Theme.MaterialComponents" 로
     * IllegalStateException 이 나며 키보드가 뜨는 순간 크래시한다. 그래서 앱 테마를 씌운
     * ContextThemeWrapper 를 만들어 인플레이트한다.
     */
    private val viewContext: Context by lazy {
        ContextThemeWrapper(this, R.style.Theme_QwenVoice)
    }

    private var binding: KeyboardViewBinding? = null
    private var statusJob: Job? = null
    private var deleteJob: Job? = null

    private var isRecording = false
    private var isFinalizing = false
    private var stopOnRelease = false
    private var micDownAt = 0L

    // ---------------------------------------------------------------- 청크 인식 큐
    //
    // AsrEngine 은 OfflineRecognizer 하나를 공유하므로 인식은 반드시 한 번에 하나씩,
    // 들어온 순서대로(FIFO) 처리해야 한다. 그래서 세션마다 단일 소비자 코루틴 하나가
    // 채널에서 청크를 꺼내 인식한다. 녹음 스레드는 채널에 넣기만 하므로 녹음은 멈추지 않는다.

    /** 녹음 스레드에서도 읽으므로 @Volatile. 세션마다 새로 만든다. */
    @Volatile
    private var chunkQueue: Channel<FloatArray>? = null

    private var consumerJob: Job? = null

    /** 세션 중에 난 인식 오류. 마무리가 끝날 때 한 번 보여 준다. */
    private var sessionError: String? = null

    /** 이번 세션에서 실제로 글자를 하나라도 넣었는지. */
    private var committedAny = false

    /** 이번 세션에서 사용자가 "입력 완료" 를 말했는지(녹음을 끝냈으면 상태 문구를 바꾼다). */
    private var commandedStop = false

    /** 아직 인식하지 못한(대기+진행 중) 청크 수. 상태 문구의 "변환 중" 판단에 쓴다. */
    private val pendingChunks = AtomicInteger(0)

    /** 이번 세션에서 큐에 들어간 청크 수. 하나라도 있으면 "너무 짧음"이 아니다. */
    private val sessionChunks = AtomicInteger(0)

    /** 녹음 스레드에서 호출된다. */
    private fun enqueueChunk(samples: FloatArray) {
        if (samples.isEmpty()) return
        val queue = chunkQueue ?: return
        sessionChunks.incrementAndGet()
        pendingChunks.incrementAndGet()
        if (queue.trySend(samples).isFailure) pendingChunks.decrementAndGet()
    }

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        recorder.onLevel = { level -> binding?.waveform?.pushLevel(level) }
        recorder.onChunkReady = { samples -> enqueueChunk(samples) }
        recorder.onAutoStop = { if (isRecording) stopAndTranscribe() }
        recorder.onError = { message -> showStatus(message) }
        ensureActiveModel()
    }

    override fun onCreateInputView(): View {
        val inflater = LayoutInflater.from(viewContext)
        val view = KeyboardViewBinding.inflate(inflater)
        binding = view
        setupButtons(view)
        refreshModelChips()
        applyMicState(currentMicState())
        showStatus(getString(R.string.status_idle))
        return view.root
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        refreshModelChips()
        if (!isRecording && !isFinalizing) showStatus(getString(R.string.status_idle))
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        if (isRecording || isFinalizing) {
            discardSession()
            showStatus(getString(R.string.status_idle))
        }
        super.onFinishInputView(finishingInput)
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onDestroy() {
        statusJob?.cancel()
        deleteJob?.cancel()
        discardSession()
        serviceScope.cancel()
        binding = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 버튼 설정

    private fun setupButtons(view: KeyboardViewBinding) {
        view.micButton.setOnTouchListener { _, event -> handleMicTouch(event) }

        view.btnBackspace.setOnClickListener { deleteOne() }
        view.btnBackspace.setOnLongClickListener {
            startRepeatDelete()
            true
        }
        view.btnBackspace.setOnTouchListener { _, event ->
            val action = event.actionMasked
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                stopRepeatDelete()
            }
            false
        }

        view.btnSpace.setOnClickListener { commitText(" ") }
        view.btnEnter.setOnClickListener { handleEnter() }
        view.btnClear.setOnClickListener { clearAll() }

        view.btnDot.setOnClickListener { commitText(".") }
        view.btnComma.setOnClickListener { commitText(",") }
        view.btnQuestion.setOnClickListener { commitText("?") }
        view.btnExclaim.setOnClickListener { commitText("!") }
        view.btnLparen.setOnClickListener { commitText("(") }
        view.btnRparen.setOnClickListener { commitText(")") }
        view.btnEllipsis.setOnClickListener { commitText("…") }

        view.chipSense.setOnClickListener { selectModel(ModelCatalog.SENSE_VOICE) }
        view.chip06b.setOnClickListener { selectModel(ModelCatalog.SMALL) }
        view.chip17b.setOnClickListener { selectModel(ModelCatalog.LARGE) }
        view.chipGroq.setOnClickListener { selectCloud() }

        view.btnSwitchKeyboard.setOnClickListener { switchKeyboard() }
        view.btnSwitchKeyboard.setOnLongClickListener {
            showImePicker()
            true
        }
    }

    // ---------------------------------------------------------------- 마이크 표시

    private fun currentMicState(): MicState = when {
        isRecording -> MicState.RECORDING
        isFinalizing -> MicState.FINALIZING
        else -> MicState.IDLE
    }

    private fun applyMicState(state: MicState) {
        val button = binding?.micButton ?: return
        when (state) {
            MicState.IDLE -> {
                button.isEnabled = true
                button.setIconResource(R.drawable.ic_mic_24)
                button.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.brand),
                )
                button.contentDescription = getString(R.string.mic_desc_idle)
            }

            MicState.RECORDING -> {
                button.isEnabled = true
                button.setIconResource(R.drawable.ic_stop_24)
                button.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.recording),
                )
                button.contentDescription = getString(R.string.mic_desc_recording)
            }

            MicState.FINALIZING -> {
                button.isEnabled = false
                button.setIconResource(R.drawable.ic_mic_24)
                button.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.finalizing),
                )
                button.contentDescription = getString(R.string.mic_desc_finalizing)
            }
        }
    }

    private fun resetMic() {
        applyMicState(currentMicState())
    }

    // ---------------------------------------------------------------- 녹음 / 인식

    private fun handleMicTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (isFinalizing) return true
                micDownAt = SystemClock.elapsedRealtime()
                if (isRecording) {
                    stopOnRelease = false
                    stopAndTranscribe()
                } else {
                    stopOnRelease = startRecording()
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val held = SystemClock.elapsedRealtime() - micDownAt
                if (isRecording && stopOnRelease && held >= HOLD_THRESHOLD_MS) {
                    stopAndTranscribe()
                }
                stopOnRelease = false
            }
        }
        return true
    }

    private fun startRecording(): Boolean {
        if (isRecording || isFinalizing) return false

        // 엔진 확인: Groq(온라인)면 API 키, 기기 내면 설치된 모델이 있어야 한다.
        // 여기서 막지 않으면 인식 단계에서 예외가 나 빈 결과로 끝난다.
        val cloud = Transcriber.isCloud(settings)
        val spec = if (cloud) {
            if (settings.groqApiKey.isBlank()) {
                showStatus(getString(R.string.status_groq_no_key))
                Toast.makeText(this, getString(R.string.status_groq_no_key), Toast.LENGTH_LONG).show()
                openAppSettings()
                return false
            }
            null
        } else {
            val local = ensureActiveModel()
            if (local == null) {
                showStatus(getString(R.string.status_no_model))
                openAppSettings()
                return false
            }
            local
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            showStatus(getString(R.string.status_permission))
            return false
        }
        if (currentInputConnection == null) {
            showStatus(getString(R.string.status_no_input_connection))
            return false
        }

        beginSession(spec)
        // 말 끝나자마자 입력: SenseVoice·Groq 는 무음 대기를 0.7초로 짧게 끊는다.
        recorder.useFastCut(Transcriber.isFastCut(settings, spec))
        if (!recorder.start()) {
            discardSession()
            return false
        }

        isRecording = true
        binding?.waveform?.clear()
        applyMicState(MicState.RECORDING)
        startStatusTimer()
        return true
    }

    /** 녹음 세션의 단일 소비자 코루틴과 큐를 준비한다. Groq 모드에서는 [spec] 이 null 이다. */
    private fun beginSession(spec: ModelSpec?) {
        val queue = Channel<FloatArray>(Channel.UNLIMITED)
        chunkQueue = queue
        pendingChunks.set(0)
        sessionChunks.set(0)
        sessionError = null
        committedAny = false
        commandedStop = false
        consumerJob = serviceScope.launch { consumeChunks(queue, spec) }
    }

    /**
     * 큐에서 청크를 하나씩 꺼내 인식하고 바로 입력한다.
     *
     * 이 코루틴은 메인 디스패처에서 돌고, 모델 준비와 인식만 [Dispatchers.IO] 로 넘긴다.
     * 덕분에 상태·버튼 갱신은 모두 메인 스레드에서 일어나고, 인식은 순서대로 하나씩만 실행된다.
     */
    private suspend fun consumeChunks(queue: Channel<FloatArray>, spec: ModelSpec?) {
        try {
            for (samples in queue) {
                recognizeChunk(samples, spec)
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            showStatus(getString(R.string.status_error, error.message ?: error.javaClass.simpleName))
        }
    }

    private suspend fun recognizeChunk(samples: FloatArray, spec: ModelSpec?) {
        val hotwords = DictionaryStore.get(this).hotwords()
        val outcome = try {
            withContext(Dispatchers.IO) {
                runCatching {
                    // 기기 내 모델은 여기서 준비하고, Groq 모드는 네트워크 호출만 한다.
                    Transcriber.transcribe(applicationContext, settings, spec, samples, hotwords)
                }
            }
        } finally {
            pendingChunks.decrementAndGet()
        }

        val raw = outcome.getOrNull()?.trim().orEmpty()
        if (raw.isEmpty()) {
            outcome.exceptionOrNull()?.let { error ->
                sessionError = error.message ?: error.javaClass.simpleName
                if (!isRecording && sessionError != null) {
                    showStatus(getString(R.string.status_error, sessionError))
                }
            }
            return
        }

        val context = PostProcessContext(
            languageTag = "ko-KR",
            targetPackage = currentInputEditorInfo?.packageName,
            inputType = currentInputEditorInfo?.inputType ?: 0,
        )
        val processed = PostProcessors.process(this, settings, raw, context)
        if (processed.stopRequested) commandedStop = true

        // 한자·중국어 부호만 남아 정리 결과가 비었거나 공백뿐이면 커서에 아무것도 넣지 않는다.
        // 단, 말로 부른 "엔터"·"다음 줄" 로 남은 `\n` 하나는 넣어야 한다(1.5.5).
        val toCommit = TranscriptOutput.withTrailingSpace(
            processed.text,
            settings.appendTrailingSpace,
        )
        if (TranscriptOutput.shouldCommit(toCommit)) {
            // `\n` 도 글자로만 넣는다(commitText). IME_ACTION_SEND 같은 전송 액션은 보내지 않는다.
            if (commitText(toCommit)) {
                committedAny = true
                if (!isRecording && !isFinalizing) showStatus(getString(R.string.status_done))
            }
        }

        if (processed.stopRequested) {
            // "입력 완료" 를 말했으면 녹음을 끝낸다. 이 코루틴은 청크 큐를 소비하는 중이라
            // 여기서 바로 stop 을 부르면 큐를 닫고 자기 자신을 join 하는 꼴이 된다.
            // 메인 스레드로 넘겨 한 박자 뒤에 안전하게 마무리한다(중복 호출은 stopAndTranscribe 가 거른다).
            serviceScope.launch { stopAndTranscribe() }
        }
    }

    /**
     * 녹음을 멈추고 남은 청크를 모두 인식한 뒤 대기 상태로 돌아간다.
     * 인식이 끝날 때까지 마이크 버튼은 "마무리 변환 중" 으로 잠긴다.
     */
    private fun stopAndTranscribe() {
        if (!isRecording) return
        isRecording = false
        statusJob?.cancel()
        statusJob = null

        val tail = recorder.stop()
        binding?.waveform?.clear()

        if (tail.isNotEmpty() && (tail.size >= MIN_SAMPLES || sessionChunks.get() > 0)) {
            enqueueChunk(tail)
        }

        val queue = chunkQueue
        if (queue == null || sessionChunks.get() == 0) {
            discardSession()
            showStatus(getString(R.string.status_too_short))
            return
        }

        isFinalizing = true
        applyMicState(MicState.FINALIZING)
        showStatus(getString(R.string.status_finalizing))

        serviceScope.launch {
            queue.close()
            consumerJob?.join()
            if (!isFinalizing) return@launch // 그 사이 세션이 정리됨
            isFinalizing = false
            consumerJob = null
            chunkQueue = null
            pendingChunks.set(0)
            sessionChunks.set(0)
            applyMicState(MicState.IDLE)
            val failure = sessionError
            val inserted = committedAny
            val finishedByCommand = commandedStop
            sessionError = null
            committedAny = false
            commandedStop = false
            showStatus(
                when {
                    failure != null -> getString(R.string.status_error, failure)
                    !inserted && !finishedByCommand -> getString(R.string.status_empty_result)
                    else -> getString(R.string.status_done)
                },
            )
        }
    }

    /** 진행 중인 세션을 인식 없이 버린다(입력창이 사라졌거나 서비스가 끝날 때). */
    private fun discardSession() {
        statusJob?.cancel()
        statusJob = null
        if (isRecording) {
            isRecording = false
            recorder.stop()
        }
        isFinalizing = false
        consumerJob?.cancel()
        chunkQueue?.cancel()
        consumerJob = null
        chunkQueue = null
        sessionError = null
        committedAny = false
        commandedStop = false
        pendingChunks.set(0)
        sessionChunks.set(0)
        binding?.waveform?.clear()
        resetMic()
    }

    private fun startStatusTimer() {
        statusJob?.cancel()
        val startedAt = SystemClock.elapsedRealtime()
        statusJob = serviceScope.launch {
            while (isActive && isRecording) {
                val elapsed = formatClock(SystemClock.elapsedRealtime() - startedAt)
                showStatus(
                    getString(
                        if (pendingChunks.get() > 0) R.string.status_recording_recognizing
                        else R.string.status_recording_stream,
                        elapsed,
                    ),
                )
                delay(200)
            }
        }
    }

    // ---------------------------------------------------------------- 텍스트 입력

    private fun commitText(text: String): Boolean {
        val connection = currentInputConnection
        if (connection == null) {
            showStatus(getString(R.string.status_no_input_connection))
            return false
        }
        connection.commitText(text, 1)
        return true
    }

    private fun deleteOne() {
        val connection = currentInputConnection ?: return
        val selected = connection.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            connection.commitText("", 1)
        } else {
            connection.deleteSurroundingText(1, 0)
        }
    }

    private fun startRepeatDelete() {
        deleteJob?.cancel()
        deleteJob = serviceScope.launch {
            delay(180)
            while (isActive) {
                deleteOne()
                delay(60)
            }
        }
    }

    private fun stopRepeatDelete() {
        deleteJob?.cancel()
        deleteJob = null
    }

    private fun clearAll() {
        val connection = currentInputConnection ?: return
        val before = connection.getTextBeforeCursor(10_000, 0)?.length ?: 0
        val after = connection.getTextAfterCursor(10_000, 0)?.length ?: 0
        if (before == 0 && after == 0) return
        connection.beginBatchEdit()
        connection.deleteSurroundingText(before, after)
        connection.endBatchEdit()
    }

    /** 엔터: imeOptions 에 액션이 있으면 실행, 여러 줄 입력창이면 줄바꿈. */
    private fun handleEnter() {
        val connection = currentInputConnection ?: return
        val info: EditorInfo? = currentInputEditorInfo
        val imeOptions = info?.imeOptions ?: 0
        val inputType = info?.inputType ?: 0
        val action = imeOptions and EditorInfo.IME_MASK_ACTION
        val hasAction = action != EditorInfo.IME_ACTION_NONE &&
            action != EditorInfo.IME_ACTION_UNSPECIFIED &&
            (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0
        val isMultiLine = (inputType and EditorInfo.TYPE_MASK_CLASS) == EditorInfo.TYPE_CLASS_TEXT &&
            (inputType and EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE) != 0

        when {
            isMultiLine -> connection.commitText("\n", 1)
            hasAction && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                connection.performEditorAction(action)
            }

            else -> {
                connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            }
        }
    }

    // ---------------------------------------------------------------- 키보드 전환

    private fun switchKeyboard() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (switchToNextInputMethod(false)) return
        }
        showImePicker()
    }

    private fun showImePicker() {
        try {
            val manager = getSystemService(InputMethodManager::class.java)
            if (manager != null) {
                manager.showInputMethodPicker()
                return
            }
        } catch (e: Exception) {
            // 아래 안내로 대체
        }
        Toast.makeText(this, getString(R.string.toast_cannot_switch), Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------- 모델

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

    private fun refreshModelChips() {
        val view = binding ?: return
        val cloud = Transcriber.isCloud(settings)
        val activeId = if (cloud) null else ensureActiveModel()?.id
        view.chipSense.setText(
            if (activeId == ModelCatalog.SENSE_VOICE.id) R.string.chip_sense_active
            else R.string.chip_sense_idle,
        )
        view.chip06b.setText(
            if (activeId == ModelCatalog.SMALL.id) R.string.chip_06b_active
            else R.string.chip_06b_idle,
        )
        view.chip17b.setText(
            if (activeId == ModelCatalog.LARGE.id) R.string.chip_17b_active
            else R.string.chip_17b_idle,
        )
        view.chipGroq.setText(
            if (cloud) R.string.chip_groq_active else R.string.chip_groq_idle,
        )
    }

    /**
     * 모델 전환.
     *
     * 인식 중에 OfflineRecognizer 를 갈아치우면 진행 중인 디코딩이 깨지므로,
     * 녹음·마무리 변환이 끝난 뒤에만 바꾼다. 기기 내 모델을 고르면 엔진도 기기 내로 돌아온다.
     */
    private fun selectModel(spec: ModelSpec) {
        if (isRecording || isFinalizing) {
            showStatus(getString(R.string.status_model_locked))
            return
        }
        if (!ModelRepository.isInstalled(this, spec)) {
            showStatus(getString(R.string.status_model_missing, spec.shortName))
            Toast.makeText(this, getString(R.string.toast_open_app), Toast.LENGTH_SHORT).show()
            openAppSettings()
            return
        }
        val alreadyLocal = !Transcriber.isCloud(settings) && settings.activeModelId == spec.id
        settings.engineId = AppSettings.ENGINE_LOCAL
        settings.activeModelId = spec.id
        refreshModelChips()
        if (alreadyLocal) return

        showStatus(getString(R.string.status_switching_model, spec.shortName))

        serviceScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { AsrEngine.prepare(applicationContext, spec) }
            }
            showStatus(getString(R.string.status_idle))
        }
    }

    /** Groq Whisper(온라인) 로 전환. 키가 없으면 설정 화면으로 안내한다. */
    private fun selectCloud() {
        if (isRecording || isFinalizing) {
            showStatus(getString(R.string.status_model_locked))
            return
        }
        if (settings.groqApiKey.isBlank()) {
            showStatus(getString(R.string.status_groq_no_key))
            Toast.makeText(this, getString(R.string.status_groq_no_key), Toast.LENGTH_LONG).show()
            openAppSettings()
            return
        }
        if (Transcriber.isCloud(settings)) return

        settings.engineId = AppSettings.ENGINE_GROQ
        refreshModelChips()
        showStatus(getString(R.string.status_engine_groq))
        // 기기 모델을 메모리에서 내려 RAM 을 비운다(다시 고르면 새로 로딩).
        serviceScope.launch {
            withContext(Dispatchers.IO) { runCatching { AsrEngine.release() } }
            showStatus(getString(R.string.status_idle))
        }
    }

    private fun openAppSettings() {
        try {
            val intent = Intent(this, MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            // 무시
        }
    }

    private fun showStatus(message: String) {
        binding?.statusText?.let { it.text = message }
    }
}
