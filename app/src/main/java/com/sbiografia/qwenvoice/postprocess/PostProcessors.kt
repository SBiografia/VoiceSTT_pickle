package com.sbiografia.qwenvoice.postprocess

import android.content.Context
import com.sbiografia.qwenvoice.core.AppSettings

/**
 * 후처리 체인 진입점.
 *
 * 1) 단어 치환 사전(항상) → 2) 사용자가 고른 단계.
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
        return runCatching { processor.process(afterDictionary, postProcessContext) }
            .getOrDefault(afterDictionary)
    }
}
