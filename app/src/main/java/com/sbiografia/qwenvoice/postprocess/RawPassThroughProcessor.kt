package com.sbiografia.qwenvoice.postprocess

/** 아무것도 바꾸지 않는 기본 단계. */
class RawPassThroughProcessor : TranscriptPostProcessor {

    override val id: String = "raw"
    override val displayName: String = "원문 그대로"
    override val isAvailable: Boolean = true

    override suspend fun process(raw: String, context: PostProcessContext): String = raw
}
