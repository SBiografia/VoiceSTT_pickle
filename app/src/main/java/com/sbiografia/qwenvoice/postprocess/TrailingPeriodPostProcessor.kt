package com.sbiografia.qwenvoice.postprocess

/**
 * 인식 결과 **문장 끝 마침표**를 지우는 단계. 체인의 **맨 마지막**에서 항상 동작한다(1.5.4).
 *
 * 구간마다 자동으로 입력되는 구조라, 인식 엔진이 문장 끝에 마침표를 붙여 내보내면
 * 말이 끊길 때마다 `안녕하세요. ` 처럼 마침표가 찍히고 띄어쓰기가 들어갔다.
 * ASR 엔진이 붙이는 마침표를 그대로 두면 사용자가 찍지 않은 마침표가 글에 남으므로,
 * 입력 직전에 **문장 끝(또는 끝의 닫는 부호 앞)에 붙은 마침표만** 지운다.
 *
 * 지우는 것
 *  - 문자열 끝의 `.` (`.` 이 겹친 `...`, 공백을 사이에 둔 `. .` 포함)
 *  - 끝의 닫는 부호(`"`, `'`, `”`, `)`, `]`, `}` 등) **바로 앞**에 붙은 `.`
 *    (예: `"안녕하세요."` → `"안녕하세요"`)
 *  - 중국식·전각 마침표(`。`, `．`, `｡`)는 [ChineseCleanupPostProcessor] 가 `.` 로 바꾼 뒤 여기서 지운다.
 *  - 지운 자리에 남는 공백은 함께 정리한다(뒤에 붙는 띄어쓰기는 호출 쪽이 한 칸만 넣는다).
 *
 * 지우지 않는 것
 *  - 단어 **중간**의 점: 숫자 소수점(`3.5`, `1.5`), 도메인·파일명·버전(`naver.com`, `v1.5`, `보고서.docx`).
 *    끝 글자가 숫자나 글자라서 마침표로 보지 않는다.
 *  - `?` `!` `,` 같은 다른 구두점과 말줄임표(`…`).
 *  - 키보드의 `.` 버튼으로 직접 찍은 마침표(이 단계를 거치지 않는 별도 입력 경로).
 *
 * 안드로이드 의존이 없어 JVM 단위 테스트로 그대로 확인할 수 있다([strip]).
 */
class TrailingPeriodPostProcessor : TranscriptPostProcessor {

    override val id: String = "trailing-period"
    override val displayName: String = "문장 끝 마침표 제거"
    override val isAvailable: Boolean = true

    override suspend fun process(raw: String, context: PostProcessContext): String = strip(raw)

    companion object {

        /** 마침표로 보는 글자. 전각·반각 중국식 마침표도 함께 본다. */
        private val PERIOD_CHARS = charArrayOf('.', '。', '．', '｡')

        /** 마침표가 앞에 와도 문장이 끝난 것으로 보는 닫는 부호. */
        private val CLOSERS = charArrayOf('"', '\'', '”', '’', '」', '』', ')', ']', '}')

        /**
         * 문장 끝 마침표를 지운 텍스트를 돌려준다.
         *
         * 마침표가 하나도 없으면 원문(앞뒤 공백만 정리)을 그대로 돌려준다.
         */
        fun strip(text: String): String {
            var out = text.trimEnd()
            while (out.isNotEmpty()) {
                val last = out.last()
                if (last in PERIOD_CHARS) {
                    out = out.dropLast(1).trimEnd()
                    continue
                }
                if (last in CLOSERS) {
                    val before = out.dropLast(1).trimEnd()
                    if (before.isNotEmpty() && before.last() in PERIOD_CHARS) {
                        out = before.dropLast(1).trimEnd() + last
                        continue
                    }
                }
                break
            }
            return out
        }
    }
}
