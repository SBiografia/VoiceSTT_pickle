package com.sbiografia.qwenvoice.postprocess

import android.content.Context
import com.sbiografia.qwenvoice.core.AppSettings

/**
 * 후처리 체인 진입점.
 *
 * 1) 단어 치환 사전(항상) → 2) 사용자가 고른 단계 → 3) 한자·중국어 부호 정리(항상)
 * → 4) 문장 끝 마침표 제거(항상, 1.5.4).
 * 어느 단계에서 예외가 나도 그때까지의 텍스트를 그대로 입력한다.
 */
object PostProcessors {

    fun available(): List<TranscriptPostProcessor> = listOf(
        RawPassThroughProcessor(),
        LlmApiPostProcessor(),
    )

    fun active(settings: AppSettings): TranscriptPostProcessor {
        val all = available()
        return all.firstOrNull { it.id == settings.postProcessorId && it.isAvailable } ?: all.first()
    }

    suspend fun process(
        context: Context,
        settings: AppSettings,
        raw: String,
        postProcessContext: PostProcessContext = PostProcessContext(),
    ): String {
        val afterDictionary = CustomDictionaryPostProcessor(context).process(raw, postProcessContext)
        val processor = active(settings)
        val afterProcessor = runCatching { processor.process(afterDictionary, postProcessContext) }
            .getOrDefault(afterDictionary)
        // 어느 단계를 거쳤든 마지막에 한자·중국어 부호를 걷어낸다.
        // 한자만 남은 결과는 빈 문자열이 되고, 호출 쪽(VoiceImeService)이 입력하지 않고 버린다.
        val afterCleanup = runCatching {
            ChineseCleanupPostProcessor().process(afterProcessor, postProcessContext)
        }.getOrDefault(afterProcessor)
        // 그다음 문장 끝 마침표를 지운다(1.5.4). 인식 엔진이 붙여 내보낸 마침표가
        // 구간마다 자동 입력되면 사용자가 찍지 않은 `안녕하세요. ` 가 글에 남기 때문이다.
        // 중국식 마침표(。)는 앞 단계가 `.` 로 바꿔 두었으므로 여기서 함께 지워진다.
        return runCatching { TrailingPeriodPostProcessor().process(afterCleanup, postProcessContext) }
            .getOrDefault(afterCleanup)
    }
}
