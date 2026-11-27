package com.sbiografia.qwenvoice.postprocess

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 말로 부른 문장부호·줄바꿈·"입력 완료" 규칙(1.5.5) 단위 테스트.
 *
 * 사용자가 실제로 말하는 문장을 그대로 넣어, 치환 결과와 **치환하면 안 되는 경우**를 함께 확인한다.
 */
class SpokenCommandsTest {

    // ------------------------------------------------------------ 문장부호 치환

    @Test
    fun 마침표_뒤에_오면_마침표로_바꾸고_앞칸을_지운다() {
        assertEquals("안녕.", SpokenCommands.replaceSpoken("안녕 마침표"))
    }

    @Test
    fun 쉼표는_뒤의_칸을_남긴다() {
        assertEquals("하나, 둘", SpokenCommands.replaceSpoken("하나 쉼표 둘"))
    }

    @Test
    fun 하이픈은_앞뒤_칸을_모두_지운다() {
        assertEquals("1-2", SpokenCommands.replaceSpoken("1 하이픈 2"))
    }

    @Test
    fun 콜론은_뒤의_칸을_남긴다() {
        assertEquals("시간: 3시", SpokenCommands.replaceSpoken("시간 콜론 3시"))
    }

    @Test
    fun 세미콜론() {
        assertEquals("가; 나", SpokenCommands.replaceSpoken("가 세미콜론 나"))
    }

    @Test
    fun 세미_콜론처럼_띄어_말해도_세미콜론이다() {
        assertEquals("가; 나", SpokenCommands.replaceSpoken("가 세미 콜론 나"))
    }

    @Test
    fun 물음표와_느낌표도_지원한다() {
        assertEquals("정말?", SpokenCommands.replaceSpoken("정말 물음표"))
        assertEquals("정말!", SpokenCommands.replaceSpoken("정말 느낌표"))
    }

    @Test
    fun 문장_가운데_문장부호() {
        assertEquals("뭐라고? 다시 말해", SpokenCommands.replaceSpoken("뭐라고 물음표 다시 말해"))
    }

    @Test
    fun 문장_끝에_여러_부호() {
        assertEquals(
            "하나, 둘. 셋: 넷",
            SpokenCommands.replaceSpoken("하나 쉼표 둘 마침표 셋 콜론 넷"),
        )
    }

    @Test
    fun 문장_맨앞에서_말해도_된다() {
        assertEquals(". 시작", SpokenCommands.replaceSpoken("마침표 시작"))
    }

    // ------------------------------------------------------------ 오치환 방지

    @Test
    fun 조사가_붙은_마침표는_그대로_둔다() {
        assertEquals("마침표가 뭐야", SpokenCommands.replaceSpoken("마침표가 뭐야"))
    }

    @Test
    fun 조사가_붙은_쉼표는_그대로_둔다() {
        assertEquals("쉼표를 찍어 봐", SpokenCommands.replaceSpoken("쉼표를 찍어 봐"))
    }

    @Test
    fun 조사가_붙은_하이픈은_그대로_둔다() {
        assertEquals("하이픈으로 이어 줘", SpokenCommands.replaceSpoken("하이픈으로 이어 줘"))
    }

    @Test
    fun 앞에_글자가_붙어_있으면_그대로_둔다() {
        assertEquals("그마침표", SpokenCommands.replaceSpoken("그마침표"))
    }

    @Test
    fun 다른_낱말_속의_글자는_건드리지_않는다() {
        assertEquals("엔터키를 눌러", SpokenCommands.replaceSpoken("엔터키를 눌러"))
        assertEquals("다음 줄에 써 줘", SpokenCommands.replaceSpoken("다음 줄에 써 줘"))
        assertEquals("그다음 줄", SpokenCommands.replaceSpoken("그다음 줄"))
    }

    @Test
    fun 문장부호_낱말이_없으면_원문_그대로() {
        val original = "오늘 날씨가 좋아서 산책을 했어"
        assertEquals(original, SpokenCommands.replaceSpoken(original))
    }

    @Test
    fun 소수점과_도메인은_그대로_둔다() {
        assertEquals("3.5 킬로", SpokenCommands.replaceSpoken("3.5 킬로"))
        assertEquals("naver.com 열어", SpokenCommands.replaceSpoken("naver.com 열어"))
    }

    // ------------------------------------------------------------ 줄바꿈

    @Test
    fun 엔터는_줄바꿈() {
        assertEquals("안녕\n반가워", SpokenCommands.replaceSpoken("안녕 엔터 반가워"))
    }

    @Test
    fun 다음_줄은_줄바꿈() {
        assertEquals("안녕\n반가워", SpokenCommands.replaceSpoken("안녕 다음 줄 반가워"))
        assertEquals("안녕\n반가워", SpokenCommands.replaceSpoken("안녕 다음줄 반가워"))
    }

    @Test
    fun 줄바꿈이라는_말도_줄바꿈() {
        assertEquals("안녕\n반가워", SpokenCommands.replaceSpoken("안녕 줄바꿈 반가워"))
        assertEquals("안녕\n반가워", SpokenCommands.replaceSpoken("안녕 줄 바꿈 반가워"))
    }

    @Test
    fun 줄바꿈_단독_발화는_줄바꿈_한_글자() {
        assertEquals("\n", SpokenCommands.replaceSpoken("다음 줄"))
        assertEquals("\n", SpokenCommands.replaceSpoken("다음줄"))
        assertEquals("\n", SpokenCommands.replaceSpoken("엔터"))
        assertEquals("\n", SpokenCommands.replaceSpoken("줄바꿈"))
        assertEquals("\n", SpokenCommands.replaceSpoken("줄 바꿈"))
    }

    @Test
    fun 문장_끝의_엔터도_줄바꿈으로_남긴다() {
        assertEquals("안녕\n", SpokenCommands.replaceSpoken("안녕 엔터"))
    }

    @Test
    fun 문장_앞의_엔터도_줄바꿈으로_남긴다() {
        assertEquals("\n안녕", SpokenCommands.replaceSpoken("엔터 안녕"))
    }

    @Test
    fun 줄바꿈에_부호가_섞여도_순서대로_처리한다() {
        assertEquals("안녕.\n반가워", SpokenCommands.replaceSpoken("안녕 마침표 엔터 반가워"))
    }

    // ------------------------------------------------------------ 입력 완료

    @Test
    fun 입력_완료_단독_발화() {
        val result = SpokenCommands.apply("입력 완료")
        assertTrue(result.stopRequested)
        assertEquals("", result.text)
    }

    @Test
    fun 입력완료_붙여_써도_인식한다() {
        val result = SpokenCommands.apply("입력완료")
        assertTrue(result.stopRequested)
        assertEquals("", result.text)
    }

    @Test
    fun 입력_완료_앞의_말은_그대로_남긴다() {
        val result = SpokenCommands.apply("오늘 날씨 어때 입력 완료")
        assertTrue(result.stopRequested)
        assertEquals("오늘 날씨 어때", result.text)
    }

    @Test
    fun 입력_완료_앞의_쉼표는_같이_정리한다() {
        val result = SpokenCommands.apply("오늘 날씨 어때, 입력 완료")
        assertTrue(result.stopRequested)
        assertEquals("오늘 날씨 어때", result.text)
    }

    @Test
    fun 입력_완료_앞에_문장부호_낱말이_있으면_함께_바꾼다() {
        val result = SpokenCommands.apply("안녕 마침표 입력 완료")
        assertTrue(result.stopRequested)
        assertEquals("안녕.", result.text)
    }

    @Test
    fun 문장_가운데_입력_완료는_명령이_아니다() {
        val result = SpokenCommands.apply("입력 완료 버튼을 눌러")
        assertFalse(result.stopRequested)
        assertEquals("입력 완료 버튼을 눌러", result.text)
    }

    @Test
    fun 입력_완료를_말하지_않으면_명령이_아니다() {
        val result = SpokenCommands.apply("오늘 회의는 세 시에 시작해")
        assertFalse(result.stopRequested)
        assertEquals("오늘 회의는 세 시에 시작해", result.text)
    }

    @Test
    fun 입력_완료에_말이_이어지면_명령이_아니다() {
        val result = SpokenCommands.apply("회의 자료 입력 완료했어")
        assertFalse(result.stopRequested)
        assertEquals("회의 자료 입력 완료했어", result.text)
    }

    @Test
    fun 입력_완료_뒤에_부호가_남아도_명령으로_본다() {
        assertTrue(SpokenCommands.wantsStop("입력 완료."))
        assertTrue(SpokenCommands.wantsStop("네 입력 완료."))
    }

    @Test
    fun 입력_완료_위치_찾기() {
        assertEquals(0, SpokenCommands.findStopIndex("입력 완료"))
        assertEquals(3, SpokenCommands.findStopIndex("안녕 입력 완료"))
        assertEquals(null, SpokenCommands.findStopIndex("입력 완료 버튼"))
    }
}
