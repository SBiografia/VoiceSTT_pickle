package com.sbiografia.qwenvoice.postprocess

/**
 * 중국어(한자) 문자와 중국어·전각 구두점을 걷어내는 단계. 체인의 **맨 마지막**에서 항상 동작한다.
 *
 * Qwen3-ASR 은 한국어 발화에서도 간혹 중국어 글자나 전각 부호를 섞어 내보낸다
 * (예: "안녕하세요。" , "회의는 3시에 开始"). 그대로 입력되면 지워야 할 글자가 되므로
 * 입력 직전에 다음 순서로 정리한다.
 *
 *  1) 중국어·전각 구두점을 일반 구두점으로 바꾼다. `。`→`.`, `？`→`?`, `！`→`!`, `，`·`、`→`,`,
 *     `：`→`:`, `；`→`;`, `「`·`」`·`『`·`』`→`"`, `（`→`(`, `）`→`)`.
 *  2) CJK 한자(`[\u4E00-\u9FFF\u3400-\u4DBF\uF900-\uFAFF]`)를 모두 지우고, 남은 공백을 하나로 정리한다.
 *  3) 한자를 지운 뒤 한글·영문·숫자가 하나도 없이 구두점·공백만 남으면 빈 문자열을 돌려준다
 *     (그 청크는 [com.sbiografia.qwenvoice.ime.VoiceImeService] 에서 입력되지 않고 버려진다).
 *  4) 남은 구두점이 어색하게 붙어 있으면 정리한다(`"음. ."` → `"음."`).
 *
 * 바꿀 것이 하나도 없는 평범한 한국어 결과는 손대지 않고 그대로 돌려준다(공백·부호 원형 보존).
 */
class ChineseCleanupPostProcessor : TranscriptPostProcessor {

    override val id: String = "chinese-cleanup"
    override val displayName: String = "한자·중국어 부호 정리"
    override val isAvailable: Boolean = true

    override suspend fun process(raw: String, context: PostProcessContext): String = clean(raw)

    companion object {

        /** 전각 공백까지 포함한 공백 묶음. */
        private val WHITESPACE_RUN = Regex("[ \\t\\u00A0\\u3000]+")

        /** 구두점 앞에 남은 공백. */
        private val SPACE_BEFORE_PUNCT = Regex(" +([.,?!:;)\\]}])")

        /** 같은 구두점이 공백을 사이에 두고 되풀이된 경우(`. .`). */
        private val REPEATED_PUNCT = Regex("([.,!?;:])(?: +\\1)+")

        /** 같은 구두점이 붙어서 되풀이된 경우(`。。` → `..` → `.`). */
        private val REPEATED_PUNCT_TIGHT = Regex("([.,!?;:])\\1+")

        /** 문장 맨 앞에 남은 구두점. */
        private val LEADING_PUNCT = Regex("^[.,!?;:]+")

        /** 문장 끝에 남은 쉼표·콜론류(마침표·물음표·느낌표는 문장 끝으로 남긴다). */
        private val TRAILING_WEAK_PUNCT = Regex("[,;:]+$")

        /** 입력으로 남길 만한 내용(한글·영문·숫자)이 있는지. */
        private val HAS_CONTENT = Regex("[가-힣ㄱ-ㅎㅏ-ㅣa-zA-Z0-9]")

        fun clean(text: String): String {
            if (text.isEmpty()) return ""

            val builder = StringBuilder(text.length)
            var changed = false
            for (ch in text) {
                val mapped = toAsciiPunct(ch)
                when {
                    mapped != null -> {
                        builder.append(mapped)
                        changed = true
                    }
                    isHanja(ch) -> changed = true
                    else -> builder.append(ch)
                }
            }

            if (!changed) {
                // 손댈 글자가 없으면 원문 유지. 다만 내용 없이 구두점·공백뿐이면 버린다.
                return if (HAS_CONTENT.containsMatchIn(text)) text else ""
            }

            var out = builder.toString()
            out = out.replace(WHITESPACE_RUN, " ")
            out = out.replace(REPEATED_PUNCT, "$1")
            out = out.replace(REPEATED_PUNCT_TIGHT, "$1")
            out = out.replace(SPACE_BEFORE_PUNCT, "$1")
            out = out.replace(LEADING_PUNCT, "")
            out = out.replace(TRAILING_WEAK_PUNCT, "")
            out = out.trim()

            if (out.isEmpty()) return ""
            if (!HAS_CONTENT.containsMatchIn(out)) return ""
            return out
        }

        private fun toAsciiPunct(ch: Char): Char? = when (ch) {
            '。' -> '.'
            '？' -> '?'
            '！' -> '!'
            '，', '、' -> ','
            '：' -> ':'
            '；' -> ';'
            '「', '」', '『', '』' -> '"'
            '（' -> '('
            '）' -> ')'
            else -> null
        }

        private fun isHanja(ch: Char): Boolean {
            val code = ch.code
            return code in 0x4E00..0x9FFF || code in 0x3400..0x4DBF || code in 0xF900..0xFAFF
        }
    }
}
