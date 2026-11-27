package com.sbiografia.qwenvoice.postprocess

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 후처리 **순서**를 지키는지 확인하는 단위 테스트(1.5.4 + 1.5.5).
 *
 * 핵심은 두 가지다.
 *  - 1.5.4 의 `TrailingPeriodPostProcessor`(인식 엔진이 붙인 문장 끝 마침표 제거)가 **먼저** 돌고,
 *    그 뒤에 "마침표" 낱말 치환이 돈다 → 사용자가 직접 말한 마침표는 살아남는다.
 *  - 한자·중국어 부호 정리(1.2)가 먼저 끝난 뒤에 나머지 단계가 돈다.
 *
 * [PostProcessors.finish] 는 안드로이드에 기대지 않아 키보드·플로팅과 같은 순서를 그대로 시험할 수 있다.
 */
class PostProcessorsOrderTest {

    private fun finish(text: String): ProcessedTranscript = PostProcessors.finish(text)

    // ------------------------------------------------ 1.5.4 회귀: 자동 마침표는 여전히 지운다

    @Test
    fun 인식_엔진이_붙인_문장_끝_마침표는_지운다() {
        assertEquals("안녕하세요", finish("안녕하세요.").text)
        assertEquals("오늘 날씨가 좋아요", finish("오늘 날씨가 좋아요.").text)
    }

    @Test
    fun 소수점과_도메인은_그대로_둔다() {
        assertEquals("3.5", finish("3.5").text)
        assertEquals("naver.com", finish("naver.com").text)
        assertEquals("v1.5", finish("v1.5").text)
    }

    @Test
    fun 물음표_느낌표는_지우지_않는다() {
        assertEquals("정말?", finish("정말?").text)
        assertEquals("정말!", finish("정말!").text)
    }

    // ------------------------------------------------ 순서: 자동 마침표 제거 → 말로 부른 마침표 치환

    @Test
    fun 사용자가_말한_마침표는_자동_마침표로_지워지지_않는다() {
        // 인식 결과 "안녕하세요 마침표." → 자동 마침표를 먼저 지우고 → 말한 마침표를 "." 로 바꾼다.
        assertEquals("안녕하세요.", finish("안녕하세요 마침표.").text)
    }

    @Test
    fun 말로_부른_마침표가_없으면_예전처럼_마침표가_없다() {
        assertEquals("안녕하세요", finish("안녕하세요.").text)
    }

    @Test
    fun 중국식_마침표는_지우고_말한_마침표는_살린다() {
        assertEquals("안녕하세요", finish("안녕하세요。").text)
        assertEquals("안녕하세요.", finish("안녕하세요 마침표。").text)
    }

    // ------------------------------------------------ 1.5.5: 문장부호·줄바꿈

    @Test
    fun 문장부호_다섯_가지() {
        assertEquals("안녕.", finish("안녕 마침표").text)
        assertEquals("하나, 둘", finish("하나 쉼표 둘").text)
        assertEquals("1-2", finish("1 하이픈 2").text)
        assertEquals("시간: 3시", finish("시간 콜론 3시").text)
        assertEquals("가; 나", finish("가 세미콜론 나").text)
    }

    @Test
    fun 보조_문장부호() {
        assertEquals("정말?", finish("정말 물음표").text)
        assertEquals("정말!", finish("정말 느낌표").text)
    }

    @Test
    fun 엔터는_줄바꿈으로_들어간다() {
        assertEquals("안녕\n반가워", finish("안녕 엔터 반가워").text)
        assertEquals("안녕\n반가워", finish("안녕 다음 줄 반가워").text)
    }

    @Test
    fun 엔터_단독_발화는_줄바꿈만_남는다() {
        assertEquals("\n", finish("엔터").text)
        assertEquals("\n", finish("다음 줄").text)
        assertEquals("\n", finish("줄바꿈").text)
    }

    @Test
    fun 줄바꿈_뒤에_붙는_자동_마침표는_먼저_지워진다() {
        // "안녕 엔터." → 자동 마침표 제거 → "안녕 엔터" → "안녕\n"
        assertEquals("안녕\n", finish("안녕 엔터.").text)
    }

    // ------------------------------------------------ 1.5.5: 입력 완료

    @Test
    fun 입력_완료는_문구를_지우고_녹음_종료를_알린다() {
        val result = finish("입력 완료")
        assertTrue(result.stopRequested)
        assertEquals("", result.text)
    }

    @Test
    fun 입력_완료_앞의_말은_입력한다() {
        val result = finish("오늘 회의는 세 시야 입력 완료")
        assertTrue(result.stopRequested)
        assertEquals("오늘 회의는 세 시야", result.text)
    }

    @Test
    fun 입력_완료_앞의_자동_마침표도_정리된다() {
        val result = finish("오늘 회의는 세 시야. 입력 완료")
        assertTrue(result.stopRequested)
        assertEquals("오늘 회의는 세 시야", result.text)
    }

    @Test
    fun 입력_완료_단독이_아니면_명령이_아니다() {
        val result = finish("입력 완료 버튼을 눌러")
        assertFalse(result.stopRequested)
        assertEquals("입력 완료 버튼을 눌러", result.text)
    }

    // ------------------------------------------------ 전체 문장

    @Test
    fun 실제_문장_하나를_끝까지_다듬는다() {
        val result = finish("회의는 3시 콜론 30분에 시작해 마침표 그다음 엔터 준비해 입력 완료")
        assertTrue(result.stopRequested)
        assertEquals("회의는 3시: 30분에 시작해. 그다음\n준비해", result.text)
    }

    @Test
    fun 아무_것도_바꿀게_없는_문장은_그대로() {
        val original = "오늘 날씨가 좋아서 산책을 했어"
        val result = finish(original)
        assertFalse(result.stopRequested)
        assertEquals(original, result.text)
    }

    @Test
    fun 한자만_남은_결과는_빈_문자열() {
        assertEquals("", finish("开始").text)
    }

    // ------------------------------------------------ 입력 판단

    @Test
    fun 줄바꿈만_남은_결과도_입력해야_한다() {
        val newlineOnly = finish("엔터")
        assertTrue(com.sbiografia.qwenvoice.core.TranscriptOutput.shouldCommit(newlineOnly.text))
        assertEquals("\n", com.sbiografia.qwenvoice.core.TranscriptOutput.withTrailingSpace("\n", true))
    }

    @Test
    fun 문구만_있던_결과는_입력하지_않는다() {
        val empty = finish("입력 완료")
        assertFalse(com.sbiografia.qwenvoice.core.TranscriptOutput.shouldCommit(empty.text))
    }
}
