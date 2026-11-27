package com.sbiografia.qwenvoice.postprocess

import android.content.Context
import com.sbiografia.qwenvoice.core.AppSettings

/**
 * 후처리 체인 진입점.
 *
 * 1) 단어 치환 사전(항상) → 2) 사용자가 고른 단계 → 3) 한자·중국어 부호 정리(항상)
 * → 4) 문장 끝 마침표 제거(항상, 1.5.4)
 * → 5) 말로 부른 문장부호·줄바꿈·"입력 완료"(항상, 1.5.5)
 * 어느 단계에서 예외가 나도 그때까지의 텍스트를 그대로 입력한다.
 *
 * 4)와 5)의 순서가 중요하다. 인식 엔진이 붙여 내보낸 문장 끝 마침표를 **먼저** 지운 뒤에
 * "마침표" 낱말을 `.` 로 바꿔야, 사용자가 직접 "마침표"라고 말한 마침표가 자동 마침표로
 * 오해되어 지워지지 않는다(`안녕하세요 마침표` → `안녕하세요.`).
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

    /**
     * 인식 원문을 입력할 글자로 다듬는다.
     *
     * [ProcessedTranscript.stopRequested] 가 true 면 부르는 쪽(키보드·플로팅 마이크)이
     * "입력 완료" 로 보고 녹음을 끝낸다. [text] 에는 "입력 완료" 문구가 이미 걷어내져 있다.
     */
    suspend fun process(
        context: Context,
        settings: AppSettings,
        raw: String,
        postProcessContext: PostProcessContext = PostProcessContext(),
    ): ProcessedTranscript {
        val afterDictionary = CustomDictionaryPostProcessor(context).process(raw, postProcessContext)
        val processor = active(settings)
        val afterProcessor = runCatching { processor.process(afterDictionary, postProcessContext) }
            .getOrDefault(afterDictionary)
        return finish(afterProcessor)
    }

    /**
     * 안드로이드에 기대지 않는 마지막 단계들(한자 정리 → 문장 끝 마침표 → 말로 부른 부호·줄바꿈·입력 완료).
     *
     * [process] 와 단위 테스트가 **같은 함수**를 쓰므로, 순서가 바뀌면 테스트가 바로 잡아낸다.
     */
    fun finish(text: String): ProcessedTranscript {
        // 한자·중국어 부호를 걷어낸다. 한자만 남은 결과는 빈 문자열이 되고, 부르는 쪽이 입력하지 않는다.
        val afterCleanup = runCatching { ChineseCleanupPostProcessor.clean(text) }.getOrDefault(text)
        // 그다음 문장 끝 마침표를 지운다(1.5.4). 인식 엔진이 붙여 내보낸 마침표가
        // 구간마다 자동 입력되면 사용자가 찍지 않은 `안녕하세요. ` 가 글에 남기 때문이다.
        // 중국식 마침표(。)는 앞 단계가 `.` 로 바꿔 두었으므로 여기서 함께 지워진다.
        val afterTrailingPeriod = runCatching { TrailingPeriodPostProcessor.strip(afterCleanup) }
            .getOrDefault(afterCleanup)
        // 마지막으로 말로 부른 문장부호·줄바꿈·"입력 완료"를 처리한다(1.5.5).
        // 문장 끝 마침표를 이미 지운 뒤라, 사용자가 말한 "마침표"만 살아남는다.
        val commands = runCatching { SpokenCommands.apply(afterTrailingPeriod) }
            .getOrDefault(SpokenCommands.CommandResult(afterTrailingPeriod, false))
        return ProcessedTranscript(commands.text, commands.stopRequested)
    }
}
