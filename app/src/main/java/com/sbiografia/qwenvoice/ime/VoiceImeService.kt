package com.sbiografia.qwenvoice.ime

import android.Manifest
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
import androidx.core.content.ContextCompat
import com.sbiografia.qwenvoice.MainActivity
import com.sbiografia.qwenvoice.R
import com.sbiografia.qwenvoice.asr.AsrEngine
import com.sbiografia.qwenvoice.asr.VoiceRecorder
import com.sbiografia.qwenvoice.core.AppSettings
import com.sbiografia.qwenvoice.core.DictionaryStore
import com.sbiografia.qwenvoice.core.formatClock
import com.sbiografia.qwenvoice.databinding.KeyboardViewBinding
import com.sbiografia.qwenvoice.model.ModelCatalog
import com.sbiografia.qwenvoice.model.ModelRepository
import com.sbiografia.qwenvoice.model.ModelSpec
import com.sbiografia.qwenvoice.postprocess.PostProcessContext
import com.sbiografia.qwenvoice.postprocess.PostProcessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 음성 입력 키보드(IME).
 *
 * 삼성 키보드의 ⌨/🌐 아이콘으로 전환해 쓰고, "⌨ 키보드 전환" 버튼으로 되돌아온다.
 */
class VoiceImeService : InputMethodService() {

    private companion object {
        const val HOLD_THRESHOLD_MS = 500L
        const val MIN_SAMPLES = 1_600 // 0.1초
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var settings: AppSettings
    private val recorder = VoiceRecorder()

    private var binding: KeyboardViewBinding? = null
    private var statusJob: Job? = null
    private var deleteJob: Job? = null

    private var isRecording = false
    private var isBusy = false
    private var stopOnRelease = false
    private var micDownAt = 0L

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        recorder.onLevel = { level -> binding?.waveform?.pushLevel(level) }
        recorder.onAutoStop = { if (isRecording) stopAndTranscribe() }
        recorder.onError = { message -> showStatus(message) }
        ensureActiveModel()
    }

    override fun onCreateInputView(): View {
        val inflater = LayoutInflater.from(this)
        val view = KeyboardViewBinding.inflate(inflater)
        binding = view
        setupButtons(view)
        refreshModelChips()
        showStatus(getString(R.string.status_idle))
        return view.root
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        refreshModelChips()
        if (!isRecording && !isBusy) showStatus(getString(R.string.status_idle))
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        if (isRecording) {
            recorder.stop()
            isRecording = false
            resetMic()
            statusJob?.cancel()
            statusJob = null
        }
        super.onFinishInputView(finishingInput)
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onDestroy() {
        statusJob?.cancel()
        deleteJob?.cancel()
        if (isRecording) recorder.stop()
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

        view.chip06b.setOnClickListener { selectModel(ModelCatalog.SMALL) }
        view.chip17b.setOnClickListener { selectModel(ModelCatalog.LARGE) }

        view.btnSwitchKeyboard.setOnClickListener { switchKeyboard() }
        view.btnSwitchKeyboard.setOnLongClickListener {
            showImePicker()
            true
        }
    }

    // ---------------------------------------------------------------- 녹음 / 인식

    private fun handleMicTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
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
        if (isBusy) return false

        val spec = ensureActiveModel()
        if (spec == null) {
            showStatus(getString(R.string.status_no_model))
            openAppSettings()
            return false
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            showStatus(getString(R.string.status_permission))
            return false
        }
        if (!recorder.start()) return false

        isRecording = true
        binding?.waveform?.clear()
        binding?.micButton?.let {
            it.setText(R.string.mic_label_recording)
            it.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.recording),
            )
        }
        startStatusTimer()
        return true
    }

    private fun stopAndTranscribe() {
        if (!isRecording) return
        isRecording = false
        statusJob?.cancel()
        statusJob = null

        val samples = recorder.stop()
        binding?.waveform?.clear()
        resetMic()

        val spec = ensureActiveModel()
        if (spec == null) {
            showStatus(getString(R.string.status_no_model))
            return
        }
        if (samples.size < MIN_SAMPLES) {
            showStatus(getString(R.string.status_too_short))
            return
        }
        if (currentInputConnection == null) {
            showStatus(getString(R.string.status_no_input_connection))
            return
        }

        isBusy = true
        binding?.micButton?.setText(R.string.mic_label_thinking)
        showStatus(
            getString(
                if (AsrEngine.isLoaded(spec.id)) R.string.status_recognizing
                else R.string.status_loading_model,
            ),
        )

        serviceScope.launch {
            val hotwords = DictionaryStore.get(this@VoiceImeService).hotwords()
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    if (!AsrEngine.isLoaded(spec.id)) {
                        AsrEngine.prepare(applicationContext, spec)
                    }
                    AsrEngine.transcribe(samples, hotwords, VoiceRecorder.SAMPLE_RATE)
                }
            }

            isBusy = false
            resetMic()

            outcome.fold(
                onSuccess = { raw ->
                    if (raw.isBlank()) {
                        showStatus(getString(R.string.status_empty_result))
                        return@launch
                    }
                    showStatus(getString(R.string.status_recognizing))
                    val context = PostProcessContext(
                        languageTag = "ko-KR",
                        targetPackage = currentInputEditorInfo?.packageName,
                        inputType = currentInputEditorInfo?.inputType ?: 0,
                    )
                    val processed = PostProcessors.process(
                        this@VoiceImeService,
                        settings,
                        raw,
                        context,
                    )
                    val toCommit = if (settings.appendTrailingSpace) "$processed " else processed
                    if (commitText(toCommit)) {
                        showStatus(getString(R.string.status_done))
                    }
                },
                onFailure = { error ->
                    showStatus(
                        getString(
                            R.string.status_error,
                            error.message ?: error.javaClass.simpleName,
                        ),
                    )
                },
            )
        }
    }

    private fun startStatusTimer() {
        statusJob?.cancel()
        val startedAt = SystemClock.elapsedRealtime()
        statusJob = serviceScope.launch {
            while (isActive && isRecording) {
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                showStatus(getString(R.string.status_recording, formatClock(elapsed)))
                delay(200)
            }
        }
    }

    private fun resetMic() {
        binding?.micButton?.let {
            it.setText(R.string.mic_label_idle)
            it.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.brand),
            )
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
        val activeId = ensureActiveModel()?.id
        val smallActive = activeId == ModelCatalog.SMALL.id
        view.chip06b.setText(if (smallActive) R.string.chip_06b_active else R.string.chip_06b_idle)
        view.chip17b.setText(if (smallActive) R.string.chip_17b_idle else R.string.chip_17b_active)
    }

    private fun selectModel(spec: ModelSpec) {
        if (!ModelRepository.isInstalled(this, spec)) {
            showStatus(getString(R.string.status_model_missing, spec.shortName))
            Toast.makeText(this, getString(R.string.toast_open_app), Toast.LENGTH_SHORT).show()
            openAppSettings()
            return
        }
        if (settings.activeModelId == spec.id) return

        settings.activeModelId = spec.id
        refreshModelChips()
        showStatus(getString(R.string.status_switching_model, spec.shortName))

        serviceScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { AsrEngine.prepare(applicationContext, spec) }
            }
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
