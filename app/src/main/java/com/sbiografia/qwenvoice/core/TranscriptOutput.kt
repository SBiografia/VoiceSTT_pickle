package com.sbiografia.qwenvoice.core

/**
 * 다듬은 글자를 입력창에 넣을지, 넣을 때 뒤에 칸을 붙일지 정하는 규칙(1.5.5).
 *
 * 키보드([com.sbiografia.qwenvoice.ime.VoiceImeService])와 플로팅 마이크
 * ([com.sbiografia.qwenvoice.floating.FloatingMicService])가 같은 함수를 쓴다.
 *
 * 말로 부른 "엔터"·"다음 줄" 은 `\n` 하나만 남는 경우가 있다. 예전에는 `isBlank()` 로
 * 빈 결과를 걸렀는데, 그러면 `\n` 이 공백으로 취급되어 **줄바꿈이 통째로 사라졌다**.
 * 그래서 "공백뿐"이 아니라 "줄바꿈 글자도 하나도 없이 공백뿐"일 때만 버린다.
 */
object TranscriptOutput {

    /** 입력창에 넣을 만한 글자가 있는지. `\n` 하나만 있어도 넣는다. */
    fun shouldCommit(text: String): Boolean {
        if (text.isEmpty()) return false
        if (text.indexOf('\n') >= 0) return true
        return text.any { !it.isWhitespace() }
    }

    /**
     * 뒤에 칸을 붙일지 정한다.
     *
     * 줄바꿈으로 끝나는 글자 뒤에는 붙이지 않는다(줄 끝에 공백이 남으면 다음 줄이 한 칸 들여쓰기 된다).
     */
    fun withTrailingSpace(text: String, append: Boolean): String {
        if (!append || text.isEmpty()) return text
        val last = text.last()
        if (last == '\n' || last.isWhitespace()) return text
        return "$text "
    }
}
