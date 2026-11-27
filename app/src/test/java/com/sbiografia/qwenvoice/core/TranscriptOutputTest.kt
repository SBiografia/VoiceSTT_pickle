package com.sbiografia.qwenvoice.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 입력 직전 판단(1.5.5) 단위 테스트.
 *
 * 말로 부른 "엔터"·"다음 줄" 은 `\n` 한 글자만 남는다. 예전 `isBlank()` 판단은 이것을
 * "빈 결과"로 보고 버렸기 때문에, 줄바꿈이 사라지지 않는지 여기서 못 박아 둔다.
 */
class TranscriptOutputTest {

    @Test
    fun 줄바꿈_한_글자는_입력한다() {
        assertTrue(TranscriptOutput.shouldCommit("\n"))
    }

    @Test
    fun 줄바꿈이_섞인_글자는_입력한다() {
        assertTrue(TranscriptOutput.shouldCommit("안녕\n반가워"))
        assertTrue(TranscriptOutput.shouldCommit("\n안녕"))
        assertTrue(TranscriptOutput.shouldCommit("안녕\n"))
        assertTrue(TranscriptOutput.shouldCommit(" \n "))
    }

    @Test
    fun 빈_결과와_공백만_있는_결과는_입력하지_않는다() {
        assertFalse(TranscriptOutput.shouldCommit(""))
        assertFalse(TranscriptOutput.shouldCommit(" "))
        assertFalse(TranscriptOutput.shouldCommit("   "))
    }

    @Test
    fun 보통_글자는_입력한다() {
        assertTrue(TranscriptOutput.shouldCommit("안녕"))
        assertTrue(TranscriptOutput.shouldCommit("a"))
        assertTrue(TranscriptOutput.shouldCommit("3"))
    }

    @Test
    fun 설정이_켜져_있으면_뒤에_칸을_붙인다() {
        assertEquals("안녕 ", TranscriptOutput.withTrailingSpace("안녕", true))
        assertEquals("안녕. ", TranscriptOutput.withTrailingSpace("안녕.", true))
    }

    @Test
    fun 설정이_꺼져_있으면_그대로_둔다() {
        assertEquals("안녕", TranscriptOutput.withTrailingSpace("안녕", false))
    }

    @Test
    fun 줄바꿈으로_끝나면_뒤에_칸을_붙이지_않는다() {
        assertEquals("안녕\n", TranscriptOutput.withTrailingSpace("안녕\n", true))
        assertEquals("\n", TranscriptOutput.withTrailingSpace("\n", true))
    }

    @Test
    fun 빈_글자에는_칸을_붙이지_않는다() {
        assertEquals("", TranscriptOutput.withTrailingSpace("", true))
    }

    @Test
    fun 줄바꿈만_남은_결과를_그대로_입력할_수_있다() {
        // "엔터" 라고 말한 경우의 실제 흐름: `\n` → 뒤 칸 없음 → 입력함.
        val text = TranscriptOutput.withTrailingSpace("\n", true)
        assertTrue(TranscriptOutput.shouldCommit(text))
        assertEquals("\n", text)
    }
}
