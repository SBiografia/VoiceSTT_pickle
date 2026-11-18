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

    companion object {
        const val POST_RAW = "raw"
        const val POST_LLM = "llm"

        private const val KEY_ACTIVE_MODEL = "active_model_id"
        private const val KEY_POST_PROCESSOR = "post_processor_id"
        private const val KEY_APPEND_SPACE = "append_trailing_space"
    }
}
