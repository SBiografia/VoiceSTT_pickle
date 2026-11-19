package com.sbiografia.qwenvoice.core

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/**
 * 인식 결과를 클립보드에 넣는다.
 *
 * 접근성 자동 입력이 막힌 화면(은행 앱, 비밀번호 입력창, 접근성 꺼짐)에서 쓰는 대비책이다.
 * 안드로이드 10+ 에서는 포커스가 없는 앱의 클립보드 접근이 제한되므로 실패할 수 있어
 * 결과를 Boolean 으로 돌려주고, 호출 쪽이 그에 맞는 안내를 띄운다.
 */
object ClipboardSupport {

    /** [text] 를 클립보드에 넣는다. 성공하면 true. */
    fun copy(context: Context, text: String): Boolean {
        if (text.isEmpty()) return false
        return runCatching {
            val manager = context.getSystemService(ClipboardManager::class.java) ?: return false
            manager.setPrimaryClip(ClipData.newPlainText(LABEL, text))
            true
        }.getOrDefault(false)
    }

    private const val LABEL = "QwenVoice"
}
