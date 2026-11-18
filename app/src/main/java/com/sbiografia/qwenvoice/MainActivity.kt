package com.sbiografia.qwenvoice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
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
import com.sbiografia.qwenvoice.model.ModelCatalog
import com.sbiografia.qwenvoice.model.ModelDownloader
import com.sbiografia.qwenvoice.model.ModelRepository
import com.sbiografia.qwenvoice.model.ModelSpec
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 설정 화면: 권한/키보드 등록 상태, 모델 다운로드·전환·삭제,
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
            toast(getString(if (granted) R.string.perm_granted else R.string.perm_needed))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = AppSettings(this)
        dictionary = DictionaryStore.get(this)

        setupSetupSection()
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

    override fun onResume() {
        super.onResume()
        refreshSetupState()
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

    private fun isImeEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_INPUT_METHODS,
        ) ?: return false
        return enabled.split(':').any { it.trim().startsWith(packageName) }
    }

    // ------------------------------------------------------------ 2. 모델

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

    // ------------------------------------------------------------ 3. 단어 치환 사전

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

    // ------------------------------------------------------------ 4. 문장 다듬기 자리

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
