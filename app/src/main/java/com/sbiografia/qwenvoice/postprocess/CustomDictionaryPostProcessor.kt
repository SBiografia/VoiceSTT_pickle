package com.sbiografia.qwenvoice.postprocess

import android.content.Context
import com.sbiografia.qwenvoice.core.DictionaryStore

/**
 * 사용자 단어 치환 사전 단계. 체인의 맨 앞에서 항상 동작한다.
 *
 * 예: 사전에 "강연 → 강현" 이 있으면 인식 결과의 "강연" 은 무조건 "강현" 으로 바뀐다.
 */
class CustomDictionaryPostProcessor(context: Context) : TranscriptPostProcessor {

    private val appContext: Context = context.applicationContext

    override val id: String = "dictionary"
    override val displayName: String = "단어 치환 사전"
    override val isAvailable: Boolean = true

    override suspend fun process(raw: String, context: PostProcessContext): String =
        DictionaryStore.get(appContext).applyTo(raw)
}
