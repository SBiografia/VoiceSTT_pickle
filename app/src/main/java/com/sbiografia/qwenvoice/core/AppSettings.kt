package com.sbiografia.qwenvoice.core

import android.content.Context
import android.content.SharedPreferences

/**
 * 앱 전체 설정. 키보드(IME) 서비스와 설정 화면이 같은 값을 본다.
 */
class AppSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("qwen_voice_settings", Context.MODE_PRIVATE)

    /** 현재 사용 중인 모델 id. 설치된 모델이 없으면 null. */
    var activeModelId: String?
        get() = prefs.getString(KEY_ACTIVE_MODEL, null)
        set(value) {
            prefs.edit().putString(KEY_ACTIVE_MODEL, value).apply()
        }

    /** 인식 후 문장 다듬기 단계. 단어 치환 사전은 이 값과 무관하게 항상 먼저 적용된다. */
    var postProcessorId: String
        get() = prefs.getString(KEY_POST_PROCESSOR, POST_RAW) ?: POST_RAW
        set(value) {
            prefs.edit().putString(KEY_POST_PROCESSOR, value).apply()
        }

    /** 인식 결과 뒤에 공백을 붙일지. */
    var appendTrailingSpace: Boolean
        get() = prefs.getBoolean(KEY_APPEND_SPACE, true)
        set(value) {
            prefs.edit().putBoolean(KEY_APPEND_SPACE, value).apply()
        }

    /** 플로팅 마이크(오버레이)를 켜 둔 상태인지. 앱을 다시 열었을 때 버튼 표시에 쓴다. */
    var floatingMicEnabled: Boolean
        get() = prefs.getBoolean(KEY_FLOATING_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_FLOATING_ENABLED, value).apply()
        }

    /** 플로팅 마이크를 키보드가 떠 있을 때만 보여 줄지. 기본값은 켜짐. */
    var floatingOnlyWhenKeyboard: Boolean
        get() = prefs.getBoolean(KEY_FLOATING_KEYBOARD_ONLY, true)
        set(value) {
            prefs.edit().putBoolean(KEY_FLOATING_KEYBOARD_ONLY, value).apply()
        }

    /** 플로팅 마이크 마지막 위치 X(px). 한 번도 옴기지 않았으면 [POSITION_UNSET]. */
    var floatingX: Int
        get() = prefs.getInt(KEY_FLOATING_X, POSITION_UNSET)
        set(value) {
            prefs.edit().putInt(KEY_FLOATING_X, value).apply()
        }

    /** 플로팅 마이크 마지막 위치 Y(px). */
    var floatingY: Int
        get() = prefs.getInt(KEY_FLOATING_Y, POSITION_UNSET)
        set(value) {
            prefs.edit().putInt(KEY_FLOATING_Y, value).apply()
        }

    companion object {
        const val POST_RAW = "raw"
        const val POST_LLM = "llm"

        /** 아직 저장된 값이 없음을 나타내는 위치 값. */
        const val POSITION_UNSET = Int.MIN_VALUE

        private const val KEY_ACTIVE_MODEL = "active_model_id"
        private const val KEY_POST_PROCESSOR = "post_processor_id"
        private const val KEY_APPEND_SPACE = "append_trailing_space"
        private const val KEY_FLOATING_ENABLED = "floating_mic_enabled"
        private const val KEY_FLOATING_KEYBOARD_ONLY = "floating_mic_keyboard_only"
        private const val KEY_FLOATING_X = "floating_mic_x"
        private const val KEY_FLOATING_Y = "floating_mic_y"
    }
}
