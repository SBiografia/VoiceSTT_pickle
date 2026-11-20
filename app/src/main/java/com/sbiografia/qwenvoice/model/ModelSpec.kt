package com.sbiografia.qwenvoice.model

/** 모델 파일 하나. [relativePath] 는 모델 폴더 기준 상대 경로. */
data class ModelFile(
    val relativePath: String,
    val url: String,
    val sizeBytes: Long,
)

/** 이 앱이 sherpa-onnx 로 돌리는 모델의 종류. 설정 구성(OfflineModelConfig)이 다르다. */
enum class ModelKind {
    /** Qwen3-ASR (0.6B / 1.7B). 다국어 자동 감지 모델이라 언어 고정 옵션이 없다. */
    QWEN3_ASR,

    /** SenseVoice Small. 언어를 "ko" 로 고정하고 문장부호(ITN)를 켤 수 있다. */
    SENSE_VOICE,
}

/**
 * 설치 가능한 음성 인식 모델.
 *
 * URL 은 HuggingFace 의 공식 sherpa-onnx 배포본(0.6B)과, 공식 문서가 안내하는
 * ModelScope 1.7B int8 과 동일한 파일 구성의 공개 리포지토리(1.7B),
 * 그리고 sherpa-onnx 공식 배포본(SenseVoice Small int8)을 쓴다.
 */
data class ModelSpec(
    val id: String,
    val displayName: String,
    val shortName: String,
    val description: String,
    val files: List<ModelFile>,
    val kind: ModelKind = ModelKind.QWEN3_ASR,
) {
    val totalBytes: Long
        get() = files.sumOf { it.sizeBytes }

    /**
     * 말이 끝나자마자 바로 입력해도 되는 초고속 모델인지.
     *
     * Qwen3-ASR 은 1글자 발화를 한자로 잘못 쓰는 문제 때문에 무음 대기를 1.3초로 길게 잡지만,
     * SenseVoice 는 언어가 "ko" 로 고정돼 있어 0.7초로 짧게 끊어도 안전하다.
     */
    val isFast: Boolean
        get() = kind == ModelKind.SENSE_VOICE
}

object ModelCatalog {

    private const val BASE_06B =
        "https://huggingface.co/csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25/resolve/main"
    private const val BASE_17B =
        "https://huggingface.co/thieunv-asilla/sherpa-onnx-qwen3-asr-1.7B-int8/resolve/main"

    /** SenseVoice Small int8. 공식 문서의 `csukuangfj/sherpa-onnx-sense-voice-...` 리포지토리 안에 int8 파일이 들어 있다. */
    private const val BASE_SENSE =
        "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main"

    /** SenseVoice 모델 파일 이름. `AsrEngine` 이 같은 이름으로 경로를 만든다. */
    const val SENSE_MODEL_FILE = "model.int8.onnx"
    const val SENSE_TOKENS_FILE = "tokens.txt"

    // tokenizer 3개 파일은 두 모델이 동일하다(vocab.json / merges.txt / tokenizer_config.json).
    private const val TOKENIZER_VOCAB = 2_776_833L
    private const val TOKENIZER_MERGES = 1_671_853L
    private const val TOKENIZER_CONFIG = 12_487L

    private fun buildFiles(
        base: String,
        convFrontend: Long,
        encoder: Long,
        decoder: Long,
    ): List<ModelFile> = listOf(
        ModelFile("conv_frontend.onnx", "$base/conv_frontend.onnx", convFrontend),
        ModelFile("encoder.int8.onnx", "$base/encoder.int8.onnx", encoder),
        ModelFile("decoder.int8.onnx", "$base/decoder.int8.onnx", decoder),
        ModelFile("tokenizer/vocab.json", "$base/tokenizer/vocab.json", TOKENIZER_VOCAB),
        ModelFile("tokenizer/merges.txt", "$base/tokenizer/merges.txt", TOKENIZER_MERGES),
        ModelFile(
            "tokenizer/tokenizer_config.json",
            "$base/tokenizer/tokenizer_config.json",
            TOKENIZER_CONFIG,
        ),
    )

    /**
     * SenseVoice Small (int8). 압축을 풀 필요 없이 개별 파일 2개만 받는다.
     * 크기는 HuggingFace 응답의 `x-linked-size`(content-length)로 확인한 실제 값이다.
     */
    val SENSE_VOICE: ModelSpec = ModelSpec(
        id = "sensevoice-small-int8",
        displayName = "SenseVoice Small (INT8)",
        shortName = "SV",
        description = "초고속 · 약 228MB · 한국어 고정 + 문장부호",
        kind = ModelKind.SENSE_VOICE,
        files = listOf(
            ModelFile(SENSE_MODEL_FILE, "$BASE_SENSE/$SENSE_MODEL_FILE", 239_233_841L),
            ModelFile(SENSE_TOKENS_FILE, "$BASE_SENSE/$SENSE_TOKENS_FILE", 315_894L),
        ),
    )

    val SMALL: ModelSpec = ModelSpec(
        id = "qwen3-asr-0.6b-int8",
        displayName = "Qwen3-ASR 0.6B (int8)",
        shortName = "0.6B",
        description = "빠름 · 약 941MB · 한국어 포함 30개 언어",
        files = buildFiles(
            BASE_06B,
            convFrontend = 44_148_281L,
            encoder = 182_491_662L,
            decoder = 755_914_231L,
        ),
    )

    val LARGE: ModelSpec = ModelSpec(
        id = "qwen3-asr-1.7b-int8",
        displayName = "Qwen3-ASR 1.7B (int8)",
        shortName = "1.7B",
        description = "정확 · 약 2.24GB · 느리지만 오인식이 적음",
        files = buildFiles(
            BASE_17B,
            convFrontend = 48_080_441L,
            encoder = 314_222_162L,
            decoder = 2_037_458_645L,
        ),
    )

    /** 설정 화면·폴백 탐색 순서. 가볍고 빠른 SenseVoice 를 맨 앞에 둔다. */
    val all: List<ModelSpec> = listOf(SENSE_VOICE, SMALL, LARGE)

    fun byId(id: String?): ModelSpec? = all.firstOrNull { it.id == id }
}
