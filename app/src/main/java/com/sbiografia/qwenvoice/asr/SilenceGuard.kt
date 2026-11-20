package com.sbiografia.qwenvoice.asr

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 인식 직전의 2차 무음 방어(1.5.1).
 *
 * 말소기가 전혀 없는 오디오가 엔진으로 넘어가면 Whisper 계열(특히 Groq 의 `prompt` 힌트)이
 * 힌트 단어를 스스로 되풀이하는 환각이 생긴다 — 무음에서 "강현, 강현, 강현" 이 입력되던 증상이다.
 * 1차 방어는 [VoiceRecorder.stop] 이고(말소리가 없던 청크는 넘기지 않음), 여기서는 배경 잡음만
 * 남은 꼬리 구간이나 키보드·플로팅 두 경로를 한 번 더 막는다.
 *
 * 임계값은 **보수적**이다. 순수 디지털 무음과 아주 작은 배경 잡음만 걸러지고, 속삭이거나 작게
 * 말한 소리는 그대로 인식된다. 인식 **결과 문자열**은 절대 건드리지 않으므로, 실제로 "강현" 이라고
 * 말한 경우는 정상 인식된다.
 */
object SilenceGuard {

    /** 이 RMS 미만이고 순간 최대 진폭도 [PEAK_THRESHOLD] 미만이면 무음으로 본다. */
    const val RMS_THRESHOLD = 0.002f

    /** 순간 최대 진폭이 이 이상이면(딸깍·숨소리 포함) 무음으로 보지 않는다. */
    const val PEAK_THRESHOLD = 0.01f

    /** 이보다 짧은 배열(16kHz 기준 0.01초)은 소리로 볼 것이 없어 무음으로 본다. */
    private const val MIN_SAMPLES = 160

    /**
     * [samples](-1..1)가 실질적으로 무음인지.
     *
     * 최대 진폭이 [PEAK_THRESHOLD] 이상이면 곧바로 소리로 판단하고(계산도 생략), 그렇지 않을 때만
     * RMS 를 [RMS_THRESHOLD] 와 비교한다. 둘 다 낮아야 무음이므로 작은 목소리를 잘못 버릴 위험이 적다.
     */
    fun isSilent(samples: FloatArray): Boolean {
        if (samples.size < MIN_SAMPLES) return true

        var sum = 0.0
        var peak = 0f
        for (sample in samples) {
            val value = abs(sample)
            if (value > peak) peak = value
            sum += (sample * sample).toDouble()
        }
        if (peak >= PEAK_THRESHOLD) return false

        val rms = sqrt(sum / samples.size).toFloat()
        return rms < RMS_THRESHOLD
    }
}
