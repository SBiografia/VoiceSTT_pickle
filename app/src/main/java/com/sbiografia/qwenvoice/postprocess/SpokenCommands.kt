package com.sbiografia.qwenvoice.postprocess

/**
 * 사용자가 **말로 부른** 문장부호·줄바꿈·입력 완료를 실제 문자로 바꾸는 규칙(1.5.5).
 *
 * 인식 엔진은 "마침표"라고 말하면 그대로 `마침표` 라고 적고, "엔터"라고 말하면 `엔터` 라고 적는다.
 * 그 글자를 입력창에 넣기 전에 이 단계에서 다음 세 가지를 실제 문자로 바꾼다.
 *
 *  1) 문장부호: `마침표`→`.`, `쉼표`→`,`, `하이픈`→`-`, `콜론`→`:`, `세미콜론`→`;`,
 *     `물음표`→`?`, `느낌표`→`!`
 *     - 앞의 칸(공백)은 지우고, 뒤의 칸은 그대로 둔다.
 *       `안녕 마침표` → `안녕.`, `하나 쉼표 둘` → `하나, 둘`, `시간 콜론 3시` → `시간: 3시`
 *     - `하이픈`은 붙여 쓰는 부호라 앞뒤 칸을 모두 지운다. `1 하이픈 2` → `1-2`
 *  2) 줄바꿈: `다음 줄`·`다음줄`·`엔터`·`줄바꿈`·`줄 바꿈` → `\n`(줄바꿈 문자)
 *       `안녕 엔터 반가워` → `안녕\n반가워`, `다음 줄` 하나만 말하면 `\n`
 *     - 이 줄바꿈은 **전송이 아니라 글자 안의 줄바꿈**이다. 부르는 쪽(키보드·플로팅)은
 *       이 문자열을 그대로 `commitText`/접근성 입력으로 넣을 뿐 `IME_ACTION_SEND` 같은
 *       편집 액션을 보내지 않는다.
 *  3) 입력 완료: `입력 완료`·`입력완료` 가 **문장 끝(또는 단독 발화)** 에 오면
 *     그 문구를 지우고 [CommandResult.stopRequested] 로 알린다. 부르는 쪽이 녹음을 끝낸다.
 *
 * ## 오치환 방지
 *
 * 낱말에 조사가 붙은 `마침표가`, `쉼표를`, `하이픈으로` 처럼 **다른 글자에 붙어 있는** 경우는
 * 그냥 대화로 보고 바꾸지 않는다. 앞뒤에 한글·영문·숫자가 바로 붙어 있으면 치환하지 않고,
 * 앞이 공백(또는 문장 시작)이고 뒤가 공백·문장 끝·부호일 때만 낱말 하나로 인정한다.
 *   - `마침표가 뭐야`, `쉼표를 찍어 봐`, `하이픈으로 이어 줘` → 그대로 둔다.
 *   - `엔터를 눌러`, `다음 줄에 써 줘` → 그대로 둔다.
 *
 * ## 순서
 *
 * [PostProcessors] 체인에서 **문장 끝 마침표 제거([TrailingPeriodPostProcessor]) 뒤**에 실행된다.
 * 그래야 `안녕하세요 마침표` 처럼 사용자가 직접 말한 마침표가 인식 엔진의 자동 마침표로
 * 오해되어 지워지지 않는다.
 *
 * 안드로이드에 기대지 않는 순수 함수라 JVM 단위 테스트로 그대로 확인할 수 있다.
 */
object SpokenCommands {

    /** 말로 부른 것을 바꾼 결과. [stopRequested] 면 "입력 완료" 를 말한 것이다. */
    data class CommandResult(
        val text: String,
        val stopRequested: Boolean,
    )

    /** "입력 완료" 로 인정하는 말들. */
    private val STOP_WORDS = listOf("입력 완료", "입력완료")

    /** "입력 완료" 뒤에 남아 있어도 되는 글자(공백·부호). */
    private val STOP_TAIL_ALLOWED = charArrayOf(' ', '\t', ',', '.', '!', '?', '~', '·', '…')

    /** 문구를 걷어 낸 자리에서 함께 지우는 꼬리 글자. */
    private val BODY_TAIL_TRIM = charArrayOf(' ', '\t', ',', '·', '…', '~')

    private data class Token(
        /** 말로 하는 낱말. */
        val word: String,
        /** 바꿀 실제 문자. */
        val symbol: String,
        /** 기호 뒤의 칸도 지울지(붙여 쓰는 부호·줄바꿈). */
        val trimFollowing: Boolean,
    )

    /**
     * 바꿀 낱말 목록. **긴 낱말이 먼저** 와야 `세미 콜론` 이 `콜론` 으로 잘리지 않는다.
     */
    private val TOKENS: List<Token> = listOf(
        Token("세미 콜론", ";", trimFollowing = false),
        Token("마침표", ".", trimFollowing = false),
        Token("쉼표", ",", trimFollowing = false),
        Token("하이픈", "-", trimFollowing = true),
        Token("콜론", ":", trimFollowing = false),
        Token("세미콜론", ";", trimFollowing = false),
        Token("물음표", "?", trimFollowing = false),
        Token("느낌표", "!", trimFollowing = false),
        Token("다음 줄", "\n", trimFollowing = true),
        Token("다음줄", "\n", trimFollowing = true),
        Token("줄 바꿈", "\n", trimFollowing = true),
        Token("줄바꿈", "\n", trimFollowing = true),
        Token("엔터", "\n", trimFollowing = true),
    ).sortedByDescending { it.word.length }

    /**
     * "입력 완료" 를 걷어 내고 나머지에 문장부호·줄바꿈 치환을 적용한다.
     *
     * "입력 완료" 가 문장 끝에 없으면(대화 중에 섞여 있으면) 아무것도 걷어 내지 않고
     * [CommandResult.stopRequested] 도 false 다.
     */
    fun apply(text: String): CommandResult {
        val stopIndex = findStopIndex(text)
        val body = if (stopIndex != null) text.substring(0, stopIndex) else text
        // 문구를 걷어 낸 자리에 남은 칸·쉼표와, 말이 끊긴 자리에 인식 엔진이 붙였을 문장 끝 마침표를 정리한다.
        val trimmedBody = if (stopIndex != null) {
            TrailingPeriodPostProcessor.strip(trimBodyTail(body))
        } else {
            body
        }
        return CommandResult(
            text = replaceSpoken(trimmedBody),
            stopRequested = stopIndex != null,
        )
    }

    /** "입력 완료" 가 문장 끝(또는 단독 발화)에 있으면 그 시작 위치를 돌려준다. */
    fun findStopIndex(text: String): Int? {
        var best: Int? = null
        for (word in STOP_WORDS) {
            var from = 0
            while (from <= text.length - word.length) {
                val index = text.indexOf(word, from)
                if (index < 0) break
                val before = if (index > 0) text[index - 1] else null
                val tailOk = text.substring(index + word.length).all { it in STOP_TAIL_ALLOWED }
                val boundaryOk = before == null || !isWordChar(before)
                if (tailOk && boundaryOk && (best == null || index > best!!)) best = index
                from = index + 1
            }
        }
        return best
    }

    /** "입력 완료" 가 문장 끝에 와서 녹음을 끝내야 하는지. */
    fun wantsStop(text: String): Boolean = findStopIndex(text) != null

    /** 문장부호·줄바꿈 낱말만 실제 문자로 바꾼다(입력 완료 처리 없음). */
    fun replaceSpoken(text: String): String {
        if (text.isEmpty()) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val token = matchTokenAt(text, i)
            if (token == null) {
                out.append(text[i])
                i++
                continue
            }
            // 기호 앞의 칸을 지운다. 칸이 있었으면 낱말 하나로 본다.
            // 칸 없이 앞 글자에 딱 붙어 있으면(마침표가·그마침표) 조사가 붙은 대화로 보고 건드리지 않는다.
            val hadSpace = out.isNotEmpty() && (out[out.length - 1] == ' ' || out[out.length - 1] == '\t')
            var cut = out.length
            while (cut > 0 && (out[cut - 1] == ' ' || out[cut - 1] == '\t')) cut--
            val prev = if (cut > 0) out[cut - 1] else null
            if (!hadSpace && prev != null && isWordChar(prev)) {
                out.append(text[i])
                i++
                continue
            }
            out.setLength(cut)
            out.append(token.symbol)
            i += token.word.length
            if (token.trimFollowing) {
                while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
            }
        }
        return out.toString()
    }

    /** [i] 위치에서 시작하고 앞뒤 경계가 맞는 낱말을 찾는다(뒤 경계만 여기서 본다). */
    private fun matchTokenAt(text: String, i: Int): Token? {
        for (token in TOKENS) {
            if (!text.startsWith(token.word, i)) continue
            val after = i + token.word.length
            val next = if (after < text.length) text[after] else null
            if (next != null && isWordChar(next)) continue
            return token
        }
        return null
    }

    /** 한글·영문·숫자처럼 낱말에 바로 붙어 있으면 치환하지 않게 하는 글자. */
    private fun isWordChar(ch: Char): Boolean = ch.isLetterOrDigit()

    private fun trimBodyTail(body: String): String {
        var end = body.length
        while (end > 0 && body[end - 1] in BODY_TAIL_TRIM) end--
        return body.substring(0, end)
    }
}
