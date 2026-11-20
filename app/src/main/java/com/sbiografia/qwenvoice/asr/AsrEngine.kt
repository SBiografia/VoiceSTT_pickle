package com.sbiografia.qwenvoice.asr

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.sbiografia.qwenvoice.model.ModelCatalog
import com.sbiografia.qwenvoice.model.ModelKind
import com.sbiografia.qwenvoice.model.ModelRepository
import com.sbiografia.qwenvoice.model.ModelSpec
import java.io.File

/**
 * sherpa-onnx OfflineRecognizer 를 감싼다.
 *
 * - 모델은 한 번에 하나만 메모리에 올린다. 모델 종류(Qwen3-ASR / SenseVoice)에 따라
 *   `OfflineModelConfig` 구성을 바꾼다.
 * - 모델 전환/해제는 반드시 백그라운드 스레드에서 호출할 것(로딩에 수 초~수십 초 걸린다).
 * - hotwords 는 recognizer 재생성 없이 stream 단위로 전달한다.
 *
 * SenseVoice 설정은 `sherpa-onnx-2.0.1.aar` 의 `classes.jar` 를 `javap` 로 직접 확인했다:
 * `OfflineSenseVoiceModelConfig(model: String, language: String, useInverseTextNormalization: Boolean, qnnConfig: QnnConfig)`
 * 이고, `tokens.txt` 는 `OfflineModelConfig.tokens` 로 넘긴다. 언어 값으로 쓸 수 있는 것은
 * `auto, zh, en, ja, ko, yue`(라이브러리 .so 문자열에서 확인)다.
 */
object AsrEngine {

    /** SenseVoice 에 넘기는 언어. 한국어 고정. */
    private const val SENSE_LANGUAGE = "ko"

    private val lock = Any()

    private var recognizer: OfflineRecognizer? = null
    private var loadedModelId: String? = null
    private var loadedKind: ModelKind? = null

    /**
     * 지금 `decode()` 가 돌고 있는 호출 수.
     *
     * 키보드와 플로팅 마이크가 동시에 살아 있을 수 있어, 인식 중에 recognizer 를
     * release/prepare 하면 네이티브에서 죽는다. 그래서 바꾸기 전에 인식이 끝나기를 기다린다.
     */
    private var activeDecodes = 0

    private val threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

    fun isLoaded(modelId: String): Boolean = synchronized(lock) {
        recognizer != null && loadedModelId == modelId
    }

    /** 지금 메모리에 올라와 있는 모델 종류. 없으면 null. */
    fun loadedKind(): ModelKind? = synchronized(lock) { loadedKind }

    fun prepare(context: Context, spec: ModelSpec): OfflineRecognizer {
        // 키보드와 플로팅 마이크가 동시에 살아 있을 수 있다. 인식(decode) 중에 recognizer 를
        // 갈아치우면 네이티브가 죽으므로, 인식이 끝날 때까지(최대 5초) 기다렸다가 바꾼다.
        val deadline = System.currentTimeMillis() + 5_000L
        while (true) {
            synchronized(lock) {
                if (activeDecodes == 0) return buildLocked(context, spec)
            }
            if (System.currentTimeMillis() >= deadline) {
                throw IllegalStateException("다른 곳에서 인식 중입니다. 잠시 뒤 다시 시도해 주세요.")
            }
            try {
                Thread.sleep(20L)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IllegalStateException("모델 준비가 취소되었습니다.")
            }
        }
    }

    /** lock 을 잡은 상태에서 모델을 (필요하면) 새로 만든다. */
    private fun buildLocked(context: Context, spec: ModelSpec): OfflineRecognizer {
        val current = recognizer
        if (current != null && loadedModelId == spec.id) return current

        current?.release()
        recognizer = null
        loadedModelId = null
        loadedKind = null

        val dir = ModelRepository.modelDir(context, spec)
        val config = OfflineRecognizerConfig(
            modelConfig = when (spec.kind) {
                ModelKind.SENSE_VOICE -> OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = File(dir, ModelCatalog.SENSE_MODEL_FILE).absolutePath,
                        language = SENSE_LANGUAGE,
                        // 문장부호·숫자 표기를 살린다(예: "삼 시" → "3시", 문장 끝 마침표).
                        useInverseTextNormalization = true,
                    ),
                    tokens = File(dir, ModelCatalog.SENSE_TOKENS_FILE).absolutePath,
                    numThreads = threads,
                    provider = "cpu",
                    debug = false,
                )

                ModelKind.QWEN3_ASR -> OfflineModelConfig(
                    qwen3Asr = OfflineQwen3AsrModelConfig(
                        convFrontend = File(dir, "conv_frontend.onnx").absolutePath,
                        encoder = File(dir, "encoder.int8.onnx").absolutePath,
                        decoder = File(dir, "decoder.int8.onnx").absolutePath,
                        tokenizer = File(dir, "tokenizer").absolutePath,
                        maxTotalLen = 1024,
                        maxNewTokens = 512,
                        temperature = 1e-6f,
                        topP = 0.8f,
                        seed = 42,
                    ),
                    numThreads = threads,
                    provider = "cpu",
                    debug = false,
                )
            },
        )

        val created = OfflineRecognizer(config = config)
        recognizer = created
        loadedModelId = spec.id
        loadedKind = spec.kind
        return created
    }

    /**
     * 16kHz 모노 float 샘플(-1..1)을 인식한다.
     * [hotwords] 는 쉼표로 구분된 문자열(예: "강현,순영").
     *
     * Qwen3-ASR 은 이 값을 시스템 프롬프트에 넣는다. SenseVoice 는 hotwords 옵션을 쓰지 않으므로
     * (언어·문장부호만 설정) 전달하지 않고, 치환 사전은 인식 뒤 후처리에서 그대로 적용된다.
     */
    fun transcribe(samples: FloatArray, hotwords: String = "", sampleRate: Int = 16_000): String {
        val engine: OfflineRecognizer
        val kind: ModelKind?
        synchronized(lock) {
            engine = recognizer ?: throw IllegalStateException("ASR 모델이 로드되지 않았습니다")
            kind = loadedKind
            activeDecodes++
        }
        val stream = engine.createStream()
        return try {
            if (hotwords.isNotBlank() && kind != ModelKind.SENSE_VOICE) {
                stream.setOption("hotwords", hotwords)
            }
            stream.acceptWaveform(samples = samples, sampleRate = sampleRate)
            engine.decode(stream)
            engine.getResult(stream).text.trim()
        } finally {
            runCatching { stream.release() }
            synchronized(lock) { activeDecodes-- }
        }
    }

    /** 모델을 메모리에서 내린다(다음에 쓰면 다시 로딩). 인식 중이면 그 호출이 끝나기를 기다린다. */
    fun release() {
        val deadline = System.currentTimeMillis() + 5_000L
        while (true) {
            synchronized(lock) {
                if (activeDecodes == 0) {
                    recognizer?.release()
                    recognizer = null
                    loadedModelId = null
                    loadedKind = null
                    return
                }
            }
            if (System.currentTimeMillis() >= deadline) return
            try {
                Thread.sleep(20L)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }
}
