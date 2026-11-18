package com.sbiografia.qwenvoice.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 인식된 표현 [from] 을 [to] 로 바꾸는 한 쌍. */
data class ReplacementRule(val from: String, val to: String)

/**
 * 사용자 정의 단어 치환 사전.
 *
 * - SharedPreferences 에 JSON 배열로 영구 저장한다.
 * - 인식 직후(후처리 1단계)와 인식 힌트(hotwords) 두 곳에 함께 쓰인다.
 */
class DictionaryStore private constructor(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile
    private var cache: List<ReplacementRule>? = null

    fun rules(): List<ReplacementRule> {
        cache?.let { return it }
        val loaded = load()
        cache = loaded
        return loaded
    }

    fun save(rules: List<ReplacementRule>) {
        val cleaned = rules
            .map { ReplacementRule(it.from.trim(), it.to.trim()) }
            .filter { it.from.isNotEmpty() && it.to.isNotEmpty() && it.from != it.to }

        val array = JSONArray()
        for (rule in cleaned) {
            val obj = JSONObject()
            obj.put(FIELD_FROM, rule.from)
            obj.put(FIELD_TO, rule.to)
            array.put(obj)
        }
        prefs.edit().putString(KEY_RULES, array.toString()).apply()
        cache = cleaned
    }

    fun add(from: String, to: String) {
        val list = rules().toMutableList()
        list.add(ReplacementRule(from.trim(), to.trim()))
        save(list)
    }

    fun update(index: Int, from: String, to: String) {
        val list = rules().toMutableList()
        if (index in list.indices) {
            list[index] = ReplacementRule(from.trim(), to.trim())
            save(list)
        }
    }

    fun remove(index: Int) {
        val list = rules().toMutableList()
        if (index in list.indices) {
            list.removeAt(index)
            save(list)
        }
    }

    fun contains(from: String, to: String): Boolean =
        rules().any { it.from == from.trim() && it.to == to.trim() }

    /** 인식 전 힌트. sherpa-onnx Qwen3-ASR 은 쉼표로 구분된 문자열을 시스템 프롬프트에 넣는다(파일 경로 아님). */
    fun hotwords(maxWords: Int = 16): String {
        val words = rules()
            .map { it.to.replace(",", " ").trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        return words.take(maxWords).joinToString(",")
    }

    /** 등록된 규칙으로 치환. 긴 표현을 먼저 바꿔 부분 겹침 문제를 줄인다. */
    fun applyTo(text: String): String = applyRules(text, rules())

    private fun load(): List<ReplacementRule> {
        val raw = prefs.getString(KEY_RULES, null)
        if (raw == null) {
            // 최초 실행 시 예시 1건만 넣어 둔다. 사용자가 자유롭게 지우거나 고칠 수 있다.
            val seed = listOf(ReplacementRule(DEFAULT_FROM, DEFAULT_TO))
            save(seed)
            return seed
        }

        val result = mutableListOf<ReplacementRule>()
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val from = obj.optString(FIELD_FROM, "").trim()
                val to = obj.optString(FIELD_TO, "").trim()
                if (from.isNotEmpty() && to.isNotEmpty() && from != to) {
                    result.add(ReplacementRule(from, to))
                }
            }
        } catch (e: Exception) {
            // 저장 형식이 깨졌으면 빈 사전으로 시작한다.
        }
        return result
    }

    companion object {
        private const val PREFS_NAME = "qwen_voice_dictionary"
        private const val KEY_RULES = "rules_json"
        private const val FIELD_FROM = "from"
        private const val FIELD_TO = "to"

        const val DEFAULT_FROM = "강연"
        const val DEFAULT_TO = "강현"

        @Volatile
        private var instance: DictionaryStore? = null

        fun get(context: Context): DictionaryStore =
            instance ?: synchronized(this) {
                instance ?: DictionaryStore(context.applicationContext).also { instance = it }
            }

        fun applyRules(text: String, rules: List<ReplacementRule>): String {
            if (text.isEmpty() || rules.isEmpty()) return text
            var out = text
            for (rule in rules.sortedByDescending { it.from.length }) {
                if (rule.from.isNotEmpty() && rule.from != rule.to) {
                    out = out.replace(rule.from, rule.to)
                }
            }
            return out
        }
    }
}
