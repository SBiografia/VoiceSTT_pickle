package com.sbiografia.qwenvoice.postprocess

/**
 * 후처리 체인을 다 거친 결과.
 *
 * @param text 입력창에 넣을 글자. 줄바꿈(`\n`)만 남은 경우도 정상 결과다(비어 있지 않음).
 * @param stopRequested 사용자가 "입력 완료" 를 말해 **녹음을 끝내야** 하는 상태(1.5.5).
 */
data class ProcessedTranscript(
    val text: String,
    val stopRequested: Boolean = false,
)
