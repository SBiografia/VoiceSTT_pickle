package com.sbiografia.qwenvoice.postprocess

/** 후처리 단계에 넘기는 문맥 정보. 지금은 자리만 잡아 두고, LLM 단계에서 쓴다. */
data class PostProcessContext(
    val languageTag: String = "ko-KR",
    val targetPackage: String? = null,
    val inputType: Int = 0,
)

/**
 * 인식 결과를 입력 직전에 다듬는 단계.
 *
 * 체인 순서(고정):
 *   1) [CustomDictionaryPostProcessor] — 사용자 단어 치환. 항상 적용.
 *   2) 사용자가 고른 단계([RawPassThroughProcessor] 또는 [LlmApiPostProcessor]).
 *
 * 새 단계를 붙일 때는 이 인터페이스를 구현하고 [PostProcessors] 에 등록하면 된다.
 */
interface TranscriptPostProcessor {
    val id: String
    val displayName: String
    val isAvailable: Boolean

    suspend fun process(raw: String, context: PostProcessContext): String
}
