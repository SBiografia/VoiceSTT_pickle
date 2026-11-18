package com.sbiografia.qwenvoice.asr

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.sbiografia.qwenvoice.model.ModelRepository
import com.sbiografia.qwenvoice.model.ModelSpec
import java.io.File

/**
 * sherpa-onnx OfflineRecognizer(Qwen3-ASR) 를 감싼다.
 *
 * - 모델은 한 번에 하나만 메모리에 올린다.
 * - 모델 전환/해제는 반드시 백그라운드 스레드에서 호출할 것(로딩에 수 초~수십 초 걸린다).
 * - hotwords 는 recognizer 재생성 없이 stream 단위로 전달한다.
 */
object AsrEngine {

    private val lock = Any()

    private var recognizer: OfflineRecognizer? = null
    private var loadedModelId: String? = null

    private val threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

    fun isLoaded(modelId: String): Boolean = synchronized(lock) {
        recognizer != null && loadedModelId == modelId
    }

    fun prepare(context: Context, spec: ModelSpec): OfflineRecognizer = synchronized(lock) {
        val current = recognizer
        if (current != null && loadedModelId == spec.id) {
            current
        } else {
            current?.release()
            recognizer = null
            loadedModelId = null

            val dir = ModelRepository.modelDir(context, spec)
            val config = OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
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
                ),
            )

            val created = OfflineRecognizer(config = config)
            recognizer = created
            loadedModelId = spec.id
            created
        }
    }

    /**
     * 16kHz 모노 float 샘플(-1..1)을 인식한다.
     * [hotwords] 는 쉼표로 구분된 문자열(예: "강현,순영"). Qwen3-ASR 은 이 값을 시스템 프롬프트에 넣는다.
     */
    fun transcribe(samples: FloatArray, hotwords: String = "", sampleRate: Int = 16_000): String {
        val engine = synchronized(lock) {
            recognizer ?: throw IllegalStateException("ASR 모델이 로드되지 않았습니다")
        }
        val stream = engine.createStream()
        return try {
            if (hotwords.isNotBlank()) {
                stream.setOption("hotwords", hotwords)
            }
            stream.acceptWaveform(samples = samples, sampleRate = sampleRate)
            engine.decode(stream)
            engine.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    fun release() {
        synchronized(lock) {
            recognizer?.release()
            recognizer = null
            loadedModelId = null
        }
    }
}
