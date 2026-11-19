package com.sbiografia.qwenvoice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sbiografia.qwenvoice.core.AppSettings
import com.sbiografia.qwenvoice.core.DictionaryStore
import com.sbiografia.qwenvoice.core.formatBytes
import com.sbiografia.qwenvoice.databinding.ActivityMainBinding
import com.sbiografia.qwenvoice.databinding.ItemDictionaryBinding
import com.sbiografia.qwenvoice.databinding.ItemModelBinding
import com.sbiografia.qwenvoice.floating.FloatingMicService
import com.sbiografia.qwenvoice.floating.VoiceAccessibilityService
import com.sbiografia.qwenvoice.model.ModelCatalog
import com.sbiografia.qwenvoice.model.ModelDownloader
import com.sbiografia.qwenvoice.model.ModelRepository
import com.sbiografia.qwenvoice.model.ModelSpec
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 설정 화면: 권한/키보드 등록 상태, 플로팅 마이크(오버레이) 설정, 모델 다운로드·전환·삭제,
 * 단어 치환 사전 관리, (향후) LLM 후처리 자리.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: AppSettings
    private lateinit var dictionary: DictionaryStore

    private val modelRows = LinkedHashMap<String, ItemModelBinding>()
    private var editingIndex: Int? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            refreshSetupState()
            refreshFloatingState()
            toast(getString(if (granted) R.string.perm_granted else R.string.perm_needed))
        }

    /** Android 13+ 알림 권한. 거부해도 플로팅 마이크 자체는 동작한다(녹음 중 알림만 안 보임). */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            refreshFloatingState()
            if (!granted) toast(getString(R.string.floating_notification_denied))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyWindowInsets()

        settings = AppSettings(this)
        dictionary = DictionaryStore.get(this)

        setupSetupSection()
        setupFloatingSection()
        setupModelSection()
        setupDictionarySection()
        setupPostProcessorSection()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    renderDownload(ModelDownloader.progress.value)
                    delay(400)
                }
            }
        }
    }

    /**
     * Android 15(API 35) 부터 targetSdk 35 앱은 edge-to-edge 로 그려져
     * 화면이 상태바·내비게이션 바·키보드 아래까지 늘어난다.
     * 루트(ScrollView)에 시스템 바 인셋만큼 패딩을 더해 내용이 가려지지 않게 한다.
     */
    private fun applyWindowInsets() {
        val root = binding.root
        val baseLeft = root.paddingLeft
        val baseTop = root.paddingTop
        val baseRight = root.paddingRight
        val baseBottom = root.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.updatePadding(
                left = baseLeft + bars.left,
                top = baseTop + bars.top,
                right = baseRight + bars.right,
                bottom = baseBottom + maxOf(bars.bottom, ime.bottom),
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    override fun onResume() {
        super.onResume()
        refreshSetupState()
        refreshFloatingState()
        renderDictionary()
        renderDownload(ModelDownloader.progress.value)
    }

    // ------------------------------------------------------------ 1. 준비 상태

    private fun setupSetupSection() {
        binding.btnGrantPermission.setOnClickListener {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
        binding.btnImeSettings.setOnClickListener {
            openImeSettings()
        }
        binding.btnImePicker.setOnClickListener {
            try {
                getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
            } catch (e: Exception) {
                toast(getString(R.string.toast_cannot_switch))
            }
        }
    }

    private fun openImeSettings() {
        try {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (ignored: Exception) {
                toast(getString(R.string.toast_cannot_switch))
            }
        }
    }

    private fun refreshSetupState() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        binding.textPermissionStatus.text =
            getString(if (granted) R.string.perm_granted else R.string.perm_needed)
        binding.btnGrantPermission.isEnabled = !granted

        val enabled = isImeEnabled()
        binding.textImeStatus.text = getString(if (enabled) R.string.ime_enabled else R.string.ime_disabled)
    }

    /**
     * 우리 키보드(IME)가 사용 설정돼 있는지 확인한다.
     *
     * targetSdk 34+ 부터 Settings.Secure.ENABLED_INPUT_METHODS 는 읽을 수 없어
     * SecurityException 이 난다. InputMethodManager 가 돌려주는 목록을 쓴다.
     */
    private fun isImeEnabled(): Boolean {
        return try {
            val imm = getSystemService(InputMethodManager::class.java) ?: return false
            imm.enabledInputMethodList.any { it.packageName == packageName }
        } catch (_: Exception) {
            false
        }
    }

    // ------------------------------------------------------------ 2. 플로팅 마이크 (오버레이)

    private fun setupFloatingSection() {
        binding.btnOverlayPermission.setOnClickListener { openOverlayPermissionSettings() }
        binding.btnA11ySettings.setOnClickListener { openAccessibilitySettings() }
        binding.btnFloatingToggle.setOnClickListener {
            if (isFloatingOn()) stopFloating() else startFloatingWithChecks()
        }
    }

    private fun isFloatingOn(): Boolean = FloatingMicService.isRunning || settings.floatingMicEnabled

    /** 권한 2개와 알림 상태를 화면에 보여 준다. 설정 화면에서 돌아오면 onResume 이 다시 부른다. */
    private fun refreshFloatingState() {
        val overlayAllowed = Settings.canDrawOverlays(this)
        binding.textOverlayStatus.text =
            getString(if (overlayAllowed) R.string.overlay_granted else R.string.overlay_needed)

        val accessibilityOn = VoiceAccessibilityService.isEnabled(this)
        binding.textA11yStatus.text =
            getString(if (accessibilityOn) R.string.a11y_enabled else R.string.a11y_disabled)

        binding.btnFloatingToggle.setText(
            if (isFloatingOn()) R.string.btn_floating_off else R.string.btn_floating_on,
        )
    }

    /** 필요한 권한을 하나씩 확인하며 켠다. 부족한 것이 있으면 그 설정·권한 창으로 보낸다. */
    private fun startFloatingWithChecks() {
        if (!Settings.canDrawOverlays(this)) {
            toast(getString(R.string.floating_toast_need_overlay))
            openOverlayPermissionSettings()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            toast(getString(R.string.floating_toast_need_permission))
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            // 알림을 거부해도 녹음과 입력은 그대로 된다(녹음 중 알림만 보이지 않는다).
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            startFloating()
            return
        }
        startFloating()
    }

    private fun startFloating() {
        FloatingMicService.start(this)
        toast(
            getString(
                if (VoiceAccessibilityService.isEnabled(this)) R.string.floating_toast_on
                else R.string.floating_no_a11y,
            ),
        )
        refreshFloatingState()
        // 서비스가 실제로 뜬 뒤( isRunning ) 버튼 글자를 다시 맞춘다.
        lifecycleScope.launch {
            delay(600)
            refreshFloatingState()
        }
    }

    private fun stopFloating() {
        FloatingMicService.stop(this)
        settings.floatingMicEnabled = false
        toast(getString(R.string.floating_toast_off))
        refreshFloatingState()
        lifecycleScope.launch {
            delay(400)
            refreshFloatingState()
        }
    }

    private fun openOverlayPermissionSettings() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
            return
        } catch (e: Exception) {
            // 아래 전체 설정으로 대체
        }
        try {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        } catch (ignored: Exception) {
            toast(getString(R.string.floating_toast_need_overlay))
        }
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (ignored: Exception) {
                toast(getString(R.string.a11y_disabled))
            }
        }
    }

    // ------------------------------------------------------------ 3. 모델

    private fun setupModelSection() {
        for (spec in ModelCatalog.all) {
            val row = ItemModelBinding.inflate(layoutInflater, binding.modelList, false)
            row.modelName.text = spec.displayName
            row.modelMeta.text = spec.description
            row.btnDownload.setOnClickListener { onDownloadClicked(spec) }
            row.btnUse.setOnClickListener { useModel(spec) }
            row.btnDelete.setOnClickListener { confirmDelete(spec) }
            binding.modelList.addView(row.root)
            modelRows[spec.id] = row
        }
    }

    private fun onDownloadClicked(spec: ModelSpec) {
        val state = ModelDownloader.progress.value
        if (state != null && state.modelId == spec.id && state.running) {
            ModelDownloader.cancel()
            return
        }
        ModelDownloader.start(this, spec)
    }

    private fun useModel(spec: ModelSpec) {
        if (!ModelRepository.isInstalled(this, spec)) {
            toast(getString(R.string.status_model_missing, spec.shortName))
            return
        }
        settings.activeModelId = spec.id
        renderDownload(ModelDownloader.progress.value)
        toast(getString(R.string.status_switching_model, spec.shortName))
    }

    private fun confirmDelete(spec: ModelSpec) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_title)
            .setMessage(getString(R.string.delete_message, spec.displayName))
            .setNegativeButton(R.string.delete_cancel, null)
            .setPositiveButton(R.string.delete_ok) { _, _ ->
                if (ModelDownloader.isRunning(spec.id)) ModelDownloader.cancel()
                ModelRepository.delete(this, spec)
                if (settings.activeModelId == spec.id) settings.activeModelId = null
                renderDownload(ModelDownloader.progress.value)
            }
            .show()
    }

    private fun renderDownload(state: ModelDownloader.Progress?) {
        for ((id, row) in modelRows) {
            val spec = ModelCatalog.byId(id) ?: continue
            val ownState = state?.takeIf { it.modelId == id }
            val running = ownState?.running == true
            val installed = ModelRepository.isInstalled(this, spec)
            val partial = ModelRepository.downloadedBytes(this, spec)
            val active = settings.activeModelId == spec.id

            row.modelProgress.isVisible = running
            row.modelProgress.progress = ownState?.percent ?: 0

            row.modelStatus.text = when {
                running && ownState != null -> getString(
                    R.string.model_status_downloading,
                    ownState.percent,
                    formatBytes(ownState.downloadedBytes),
                    formatBytes(spec.totalBytes),
                )

                installed -> getString(R.string.model_status_installed)
                ownState?.error != null -> getString(R.string.model_status_failed, ownState.error)
                partial > 0L -> getString(R.string.model_status_partial, formatBytes(partial))
                else -> getString(R.string.model_status_missing)
            }

            row.btnDownload.isVisible = !installed
            row.btnDownload.setText(
                when {
                    running -> R.string.btn_cancel
                    partial > 0L -> R.string.btn_resume
                    else -> R.string.btn_download
                },
            )
            row.btnUse.isEnabled = installed
            row.btnUse.setText(if (active) R.string.btn_in_use else R.string.btn_use)
            row.btnDelete.isEnabled = installed || partial > 0L
        }

        binding.storageText.text = getString(
            R.string.storage_usage,
            formatBytes(ModelRepository.installedBytes(this)),
        )

        if (state?.running == true) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // ------------------------------------------------------------ 4. 단어 치환 사전

    private fun setupDictionarySection() {
        binding.btnAddRule.setOnClickListener { saveRuleFromInputs() }
        binding.btnRestoreExample.setOnClickListener {
            if (!dictionary.contains(DictionaryStore.DEFAULT_FROM, DictionaryStore.DEFAULT_TO)) {
                dictionary.add(DictionaryStore.DEFAULT_FROM, DictionaryStore.DEFAULT_TO)
            }
            resetRuleEditor()
            renderDictionary()
        }
    }

    private fun saveRuleFromInputs() {
        val from = binding.etFrom.text?.toString()?.trim().orEmpty()
        val to = binding.etTo.text?.toString()?.trim().orEmpty()

        if (from.isEmpty() || to.isEmpty()) {
            toast(getString(R.string.dict_need_both))
            return
        }
        if (from == to) {
            toast(getString(R.string.dict_same))
            return
        }

        val index = editingIndex
        if (index != null) {
            dictionary.update(index, from, to)
        } else {
            dictionary.add(from, to)
        }
        resetRuleEditor()
        renderDictionary()
    }

    private fun resetRuleEditor() {
        editingIndex = null
        binding.etFrom.setText("")
        binding.etTo.setText("")
        binding.btnAddRule.setText(R.string.btn_add_rule)
    }

    private fun renderDictionary() {
        binding.dictList.removeAllViews()
        val rules = dictionary.rules()
        binding.dictEmpty.isVisible = rules.isEmpty()

        rules.forEachIndexed { index, rule ->
            val row = ItemDictionaryBinding.inflate(layoutInflater, binding.dictList, false)
            row.dictText.text = getString(R.string.dict_row_format, rule.from, rule.to)
            row.btnEdit.setOnClickListener {
                editingIndex = index
                binding.etFrom.setText(rule.from)
                binding.etTo.setText(rule.to)
                binding.btnAddRule.setText(R.string.btn_save_rule)
                binding.etFrom.requestFocus()
            }
            row.btnRemove.setOnClickListener {
                dictionary.remove(index)
                resetRuleEditor()
                renderDictionary()
            }
            binding.dictList.addView(row.root)
        }
    }

    // ------------------------------------------------------------ 5. 문장 다듬기 자리

    private fun setupPostProcessorSection() {
        binding.rbPostLlm.isEnabled = false // LlmApiPostProcessor.isAvailable = false 와 맞춤
        val useLlm = settings.postProcessorId == AppSettings.POST_LLM
        binding.rbPostRaw.isChecked = !useLlm
        binding.rbPostLlm.isChecked = useLlm

        binding.postGroup.setOnCheckedChangeListener { _, checkedId ->
            settings.postProcessorId =
                if (checkedId == R.id.rb_post_llm) AppSettings.POST_LLM else AppSettings.POST_RAW
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
