package com.sbiografia.qwenvoice.asr

/**
 * 프롬프트 메아리 환각 필터(1.5.2).
 *
 * Groq Whisper 는 `prompt`(치환 사전 힌트)를 받으면, 오디오가 불분명할 때 그 단어를 스스로
 * 되풀이해 출력한다 — 멀리서 다른 사람이 말하거나 노래가 들릴 때 메모장에
 * `"위쪽 칸 강현,강현,강현 가로 35에서 36 강현,강현,강현"` 처럼 `강현` 이 반복되던 증상이다.
 *
 * 이 필터는 **결과 문자열만** 보고 판단하는 순수 함수라 JVM 에서 그대로 테스트할 수 있다.
 * 판단 규칙은 오직 "힌트 단어가 연달아 반복" 이다:
 * - 힌트 단어가 **연속 2회 이상** (`"강현, 강현, 강현"`, `"강현 강현"`) → 메아리로 보고 빈 문자열.
 * - 결과 전체가 힌트 단어로만 이루어지고 2개 이상 → 메아리.
 * - 결과 전체가 프롬프트 문맥어(`한국어 음성 받아쓰기 고유명사 참고`)로만 이루어짐 → 메아리.
 *
 * **지우지 않는 것**(사용자 발화 보호):
 * - `"강현"` 처럼 힌트 단어를 **한 번** 말한 경우
 * - `"강현이 밥 먹었어"`, `"오늘 강현 유치원 갔어"` 처럼 다른 말이 섞인 문장
 * - 힌트 단어가 문장 안에서 떨어져 여러 번 나오는 경우(`"강현이 밥 먹고 강현이 잤어"`)
 */
object HallucinationFilter {

    /** 메아리로 인정하는 최소 연속 반복 횟수. */
    const val MIN_ECHO_RUN = 2

    /** 쉼표·공백·마침표·문장부호로 토큰을 나눈다. 한글은 그대로, 문장부호는 버린다. */
    private val SEPARATORS = Regex("[\\s,.;:!?·、，。「」\"'()\\[\\]{}\\-~…/]+")

    /** 프롬프트 문맥 문구([GroqWhisperClient.buildPrompt])에 쓰는 단어들. 이것만 나오면 메아리다. */
    private val PROMPT_CONTEXT_WORDS = setOf("한국어", "음성", "받아쓰기", "고유명사", "참고")

    /** [text] 를 단어 단위로 나눈다. 빈 조각은 버린다. */
    fun tokenize(text: String): List<String> =
        text.split(SEPARATORS).filter { it.isNotEmpty() }

    /** 힌트 문구("강현,순영")의 단어 집합. */
    fun hintTokens(hint: String): Set<String> = tokenize(hint).toSet()

    /**
     * [text] 가 [hint] 단어를 되풀이한 프롬프트 메아리인지.
     *
     * @param hint 치환 사전에서 만든 원본 힌트 단어 문구(쉼표 구분). 비어 있으면 항상 false.
     */
    fun isPromptEcho(text: String, hint: String): Boolean {
        val hints = hintTokens(hint)
        if (hints.isEmpty()) return false

        val tokens = tokenize(text)
        if (tokens.isEmpty()) return false

        // 1) 힌트 단어가 연속으로 2회 이상 → 메아리. ("강현, 강현, 강현", "강현 강현")
        var run = 0
        var previous: String? = null
        for (token in tokens) {
            if (token in hints) {
                run = if (token == previous) run + 1 else 1
                if (run >= MIN_ECHO_RUN) return true
            } else {
                run = 0
            }
            previous = token
        }

        // 2) 결과 전체가 힌트 단어로만 이루어졌고 2개 이상 → 메아리. ("강현 순영 강현")
        if (tokens.size >= MIN_ECHO_RUN && tokens.all { it in hints }) return true

        // 3) 결과 전체가 프롬프트 문맥어로만 → 프롬프트 문구 자체를 받아쓴 메아리.
        if (tokens.size >= 2 && tokens.all { it in PROMPT_CONTEXT_WORDS }) return true

        return false
    }

    /**
     * 메아리면 빈 문자열, 아니면 다듬은 원문을 돌려준다.
     *
     * @param hint 원본 힌트 단어 문구(쉼표 구분). `prompt` 로 보낸 자연어 문구가 아니라 단어 목록이어야 한다.
     */
    fun filter(text: String, hint: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        return if (isPromptEcho(trimmed, hint)) "" else trimmed
    }
}
