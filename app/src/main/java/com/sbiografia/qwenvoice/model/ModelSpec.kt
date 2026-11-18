package com.sbiografia.qwenvoice.model

/** 모델을 이루는 파일 하나. [relativePath] 는 모델 폴더 기준 상대 경로. */
data class ModelFile(
    val relativePath: String,
    val url: String,
    val sizeBytes: Long,
)

/**
 * 설치 가능한 Qwen3-ASR 모델.
 *
 * URL 은 HuggingFace 의 공식 sherpa-onnx 배포본(0.6B)과, 공식 문서가 안내하는
 * ModelScope 1.7B int8 과 동일한 파일 구성의 공개 리포지토리(1.7B)를 쓴다.
 */
data class ModelSpec(
    val id: String,
    val displayName: String,
    val shortName: String,
    val description: String,
    val files: List<ModelFile>,
) {
    val totalBytes: Long
        get() = files.sumOf { it.sizeBytes }
}

object ModelCatalog {

    private const val BASE_06B =
        "https://huggingface.co/csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25/resolve/main"
    private const val BASE_17B =
        "https://huggingface.co/thieunv-asilla/sherpa-onnx-qwen3-asr-1.7B-int8/resolve/main"

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

    val all: List<ModelSpec> = listOf(SMALL, LARGE)

    fun byId(id: String?): ModelSpec? = all.firstOrNull { it.id == id }
}
