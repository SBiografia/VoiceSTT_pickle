package com.sbiografia.qwenvoice.postprocess

/** LLM 후처리 단계의 설정값. 아직 화면에 노출하지 않았다(자리만 확보). */
data class LlmConfig(
    val endpoint: String = "",
    val apiKey: String = "",
    val model: String = "",
    val systemPrompt: String =
        "한국어 음성 인식 결과를 자연스러운 문장으로 다듬어라. " +
            "추임새(어, 음, 그…)를 지우고 말실수를 고치되 의미는 바꾸지 말고, " +
            "설명 없이 결과 문장만 출력하라.",
)

/**
 * 향후 LLM 으로 추임새 제거·말실수 교정·톤 맞춤을 하는 자리.
 *
 * 지금은 꺼져 있다([isAvailable] = false). 붙일 때는
 *  - [LlmConfig] 를 채우고
 *  - [process] 에서 네트워크 호출(OkHttp)로 다듬은 문장을 받아 반환하면
 * 나머지 코드(키보드·설정 화면)는 그대로 둔 채 동작한다.
 */
class LlmApiPostProcessor(private val config: LlmConfig = LlmConfig()) : TranscriptPostProcessor {

    override val id: String = "llm"
    override val displayName: String = "LLM 문장 다듬기"
    override val isAvailable: Boolean = false

    override suspend fun process(raw: String, context: PostProcessContext): String {
        // TODO: endpoint/apiKey 가 채워지면 이 자리에서 호출한다. 지금은 원문 유지.
        if (config.endpoint.isBlank() || config.apiKey.isBlank()) return raw
        return raw
    }
}
