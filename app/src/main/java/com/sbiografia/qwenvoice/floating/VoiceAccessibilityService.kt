package com.sbiografia.qwenvoice.floating

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import com.sbiografia.qwenvoice.core.ClipboardSupport

/**
 * 플로팅 마이크가 인식한 글자를 **지금 커서가 있는 입력창**에 넣는 접근성 서비스.
 *
 * 키보드(IME) 모드와 달리 삼성 키보드·다른 앱이 입력을 계속 맡고, 이 서비스는
 * 접근성 API 로 그 앱의 입력창을 찾아 글자만 끼워 넣는다.
 *
 * 넣는 순서:
 *  1. 빈 입력창 → `ACTION_SET_TEXT` 로 통째로 넣고 커서를 끝으로 옮긴다.
 *  2. 비어 있지 않은 입력창 → 클립보드에 넣고 `ACTION_PASTE` 로 커서 자리에 붙여넣는다.
 *  3. 붙여넣기가 막힌 입력창 → 선택 구간을 계산해 `ACTION_SET_TEXT` 로 다시 쓴다.
 *  4. 비밀번호 입력창이거나 입력창을 찾지 못하면 아무것도 넣지 않고 false 를 돌려준다
 *     (호출 쪽 [FloatingMicService] 가 클립보드 복사 + 안내로 넘어간다).
 *
 * 화면 내용은 읽기만 하고 어디로도 보내지 않는다. 이벤트는 받기만 하며,
 * 입력 시점에 [rootInActiveWindow] 로 포커스를 다시 확인한다.
 */
class VoiceAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 포커스는 입력하는 순간에 다시 찾는다. 여기서는 아무것도 보관하지 않는다.
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ------------------------------------------------------------ 입력

    private fun insertIntoFocused(text: String): Boolean {
        if (text.isEmpty()) return false
        val node = resolveEditable() ?: return false

        // 비밀번호 칸에는 넣지 않는다. 화면에도 읽히지 않고, 붙여넣기도 대개 막혀 있다.
        if (runCatching { node.isPassword }.getOrDefault(false)) return false

        node.refresh()
        val existing = runCatching { node.text?.toString().orEmpty() }.getOrDefault("")

        if (existing.isEmpty() && setText(node, text, text.length)) return true

        if (!runCatching { node.isFocused }.getOrDefault(true)) {
            runCatching { node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
        }
        if (ClipboardSupport.copy(this, text) && paste(node)) return true

        val start = runCatching { node.textSelectionStart }.getOrDefault(-1)
        val end = runCatching { node.textSelectionEnd }.getOrDefault(-1)
        if (start in 0..existing.length && end in 0..existing.length) {
            val from = minOf(start, end)
            val to = maxOf(start, end)
            val merged = existing.substring(0, from) + text + existing.substring(to)
            if (setText(node, merged, from + text.length)) return true
        }
        return false
    }

    /** 지금 포커스를 가진 입력창. 없으면 화면에서 처음 만나는 입력창을 쓴다. */
    private fun resolveEditable(): AccessibilityNodeInfo? {
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return null
        val focused = runCatching { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
        if (focused != null && runCatching { focused.isEditable }.getOrDefault(false)) return focused
        return findEditable(root, 0)
    }

    private fun findEditable(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
        if (node == null || depth > MAX_DEPTH) return null
        if (runCatching { node.isEditable }.getOrDefault(false)) return node
        val count = runCatching { node.childCount }.getOrDefault(0)
        for (i in 0 until minOf(count, MAX_CHILDREN)) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            val found = findEditable(child, depth + 1)
            if (found != null) return found
        }
        return null
    }

    private fun setText(node: AccessibilityNodeInfo, value: String, cursor: Int): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        val done = runCatching { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }
            .getOrDefault(false)
        if (done && cursor >= 0) {
            val selection = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
            }
            runCatching { node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection) }
        }
        return done
    }

    private fun paste(node: AccessibilityNodeInfo): Boolean {
        if (runCatching { node.performAction(AccessibilityNodeInfo.ACTION_PASTE) }.getOrDefault(false)) {
            return true
        }
        val parent = runCatching { node.parent }.getOrNull() ?: return false
        return runCatching { parent.performAction(AccessibilityNodeInfo.ACTION_PASTE) }.getOrDefault(false)
    }

    private fun currentPackageName(): String? =
        runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()

    private fun currentInputType(): Int =
        runCatching { resolveEditable()?.inputType ?: 0 }.getOrDefault(0)

    companion object {

        private const val MAX_DEPTH = 30
        private const val MAX_CHILDREN = 60

        @Volatile
        private var instance: VoiceAccessibilityService? = null

        /** 서비스가 실제로 연결되어 있(고 켜져 있)는지. */
        fun isConnected(): Boolean = instance != null

        /** 설정 화면용: 이 앱의 접근성 서비스가 사용 설정되어 있는지. */
        fun isEnabled(context: Context): Boolean = runCatching {
            val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
            manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { info ->
                    val serviceInfo = info.resolveInfo?.serviceInfo ?: return@any false
                    serviceInfo.packageName == context.packageName &&
                        serviceInfo.name == VoiceAccessibilityService::class.java.name
                }
        }.getOrDefault(false)

        /** 후처리 단계에 넘길 대상 앱 패키지. */
        fun focusedPackage(): String? = instance?.currentPackageName()

        /** 후처리 단계에 넘길 입력창 타입. */
        fun focusedInputType(): Int = instance?.currentInputType() ?: 0

        /**
         * 포커스된 입력창에 [text] 를 넣는다.
         * 넣지 못했으면(접근성 꺼짐·비밀번호 칸·입력창 없음·앱이 거부) false.
         */
        fun insertText(text: String): Boolean {
            val service = instance ?: return false
            if (text.isEmpty()) return false
            return runCatching { service.insertIntoFocused(text) }.getOrDefault(false)
        }
    }
}
