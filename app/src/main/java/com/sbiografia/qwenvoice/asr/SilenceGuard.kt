package com.sbiografia.qwenvoice.asr

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 인식 직전의 2차 무음·배경잡음 방어(1.5.1 신설 → 1.5.2 강화).
 *
 * 말소기가 전혀 없는 오디오가 엔진으로 넘어가면 Whisper 계열(특히 Groq 의 `prompt` 힌트)이
 * 힌트 단어를 스스로 되풀이하는 환각이 생긴다 — 무음에서 "강현, 강현, 강현" 이 입력되던 증상이다.
 * 1차 방어는 [VoiceRecorder] 가 연속 발화를 확인해 말소리 여부를 정하는 것이고, 여기서는
 * **멀리서 들리는 다른 사람 말소리·노래·배경 잡음**처럼 음량은 있지만 발화로 보기 어려운 구간을
 * 한 번 더 막는다(1.5.2).
 *
 * 판정 순서(모두 통과해야 엔진으로 넘어간다):
 * 1. 순간 최대 진폭도 [PEAK_THRESHOLD] 미만이고 RMS 도 [RMS_THRESHOLD] 미만 → 무음.
 * 2. 50ms 프레임 단위로 셌을 때 "발화 프레임"(RMS >= [ACTIVE_FRAME_RMS])이 [MIN_ACTIVE_FRAMES] 개
 *    미만 → 배경 잡음·먼 소리. 단, 아주 큰 소리 한 번([STRONG_PEAK_THRESHOLD] 이상)이면서 전체 RMS 도
 *    [STRONG_PEAK_RMS] 이상이면 짧은 외침으로 보고 통과시킨다(딸깍·티끌 소리는 RMS 가 낮아 걸러진다).
 * 3. 3초 이상 긴 오디오에서 발화 프레임 비율이 [MIN_ACTIVE_RATIO] 미만 → 대부분 잡음인 구간.
 *
 * 임계값은 **근거리에서 또박또박 말한 짧은 단어를 절대 버리지 않는 쪽**으로 맞췄다. 0.2초짜리
 * 한 단어는 50ms 프레임 4개를 채우므로 [MIN_ACTIVE_FRAMES] 를 그대로 통과하고(프레임 경계에
 * 걸쳐도 3개 이상), 작은 목소리
 * (RMS 0.02 안팎)도 [ACTIVE_FRAME_RMS] 위라서 살아남는다. 인식 **결과 문자열**은 절대 건드리지
 * 않으므로 실제로 "강현" 이라고 말한 경우는 정상 인식된다(프롬프트 메아리는 [HallucinationFilter] 담당).
 */
object SilenceGuard {

    /** 이 RMS 미만이고 순간 최대 진폭도 [PEAK_THRESHOLD] 미만이면 완전 무음으로 본다. */
    const val RMS_THRESHOLD = 0.006f

    /** 이보다 순간 최대 진폭이 크면 소리가 있는 것으로 본다(1.5.1: 0.01 → 1.5.2: 0.025). */
    const val PEAK_THRESHOLD = 0.025f

    /** 발화 프레임으로 세는 프레임 RMS. 배경 잡음(0.002~0.008)보다 확실히 위. */
    const val ACTIVE_FRAME_RMS = 0.012f

    /**
     * 발화로 인정하는 최소 발화 프레임 수. 50ms × 3 = 0.15초.
     *
     * 0.2초짜리 한 단어는 프레임 경계에 걸려도 4개(또는 3개)를 채워 통과한다 — 짧은 단어 보호.
     */
    const val MIN_ACTIVE_FRAMES = 3

    /** 아주 큰 소리 한 번으로 통과시키는 최소 순간 최대 진폭. */
    const val STRONG_PEAK_THRESHOLD = 0.080f

    /** 그 큰 소리를 통과시키려면 전체 RMS 도 이 이상이어야 한다(딸깍 한 번 배제). */
    const val STRONG_PEAK_RMS = 0.012f

    /** 이보다 긴 오디오에서만 발화 프레임 비율을 따진다(3초 = 50ms × 60). */
    const val LONG_AUDIO_FRAMES = 60

    /** 긴 오디오에서 요구하는 최소 발화 프레임 비율. */
    const val MIN_ACTIVE_RATIO = 0.03f

    /** 이보다 짧은 배열(16kHz 기준 0.01초)은 소리로 볼 것이 없어 무음으로 본다. */
    private const val MIN_SAMPLES = 160

    /** 프레임 길이(50ms). 짧은 단어가 프레임 경계에 걸려도 3개 이상 잡히게 촘촘히 센다. */
    private const val FRAME_SAMPLES = 800

    /** 오디오의 음량·발화 분포. [analyze] 가 채운다. */
    data class Stats(
        val rms: Float,
        val peak: Float,
        val frames: Int,
        val activeFrames: Int,
    )

    /** 샘플(-1..1)의 RMS·피크와 50ms 프레임별 발화 프레임 수를 센다. 순수 함수. */
    fun analyze(samples: FloatArray): Stats {
        var sum = 0.0
        var peak = 0f
        for (sample in samples) {
            val value = abs(sample)
            if (value > peak) peak = value
            sum += (sample * sample).toDouble()
        }
        val rms = if (samples.isEmpty()) 0f else sqrt(sum / samples.size).toFloat()

        var activeFrames = 0
        var frames = 0
        var index = 0
        while (index < samples.size) {
            val end = minOf(index + FRAME_SAMPLES, samples.size)
            var frameSum = 0.0
            for (i in index until end) {
                frameSum += (samples[i] * samples[i]).toDouble()
            }
            val frameRms = sqrt(frameSum / (end - index)).toFloat()
            if (frameRms >= ACTIVE_FRAME_RMS) activeFrames++
            frames++
            index = end
        }
        return Stats(rms = rms, peak = peak, frames = frames, activeFrames = activeFrames)
    }

    /**
     * [samples](-1..1)가 실질적으로 무음·배경잡음인지.
     *
     * @see analyze 판정에 쓰는 값들
     */
    fun isSilent(samples: FloatArray): Boolean {
        if (samples.size < MIN_SAMPLES) return true

        val stats = analyze(samples)
        // 1) 발화 프레임이 하나도 없고 음량도 무음 기준 아래 → 완전 무음·아주 작은 배경 잡음.
        //    (발화 프레임이 하나라도 있으면 아래 2·3번에서 판단한다 — 긴 구간에 섞인 짧은 단어를
        //     전체 RMS 만으로 버리지 않기 위해서다.)
        if (stats.activeFrames == 0 && stats.peak < PEAK_THRESHOLD && stats.rms < RMS_THRESHOLD) {
            return true
        }

        // 아주 큰 소리 한 번은 짧은 외침으로 보고 통과시킨다(전체 RMS 가 낮은 딸깍·티끌은 제외).
        val strongBurst = stats.peak >= STRONG_PEAK_THRESHOLD && stats.rms >= STRONG_PEAK_RMS

        // 2) 0.15초 이상 이어지는 발화 프레임이 없으면 먼 소리·배경 잡음이다.
        if (stats.activeFrames < MIN_ACTIVE_FRAMES && !strongBurst) return true

        // 3) 3초 이상 긴 구간인데 발화 프레임이 거의 없다 → 대부분 잡음.
        if (stats.frames >= LONG_AUDIO_FRAMES && !strongBurst) {
            val ratio = stats.activeFrames.toFloat() / stats.frames
            if (ratio < MIN_ACTIVE_RATIO) return true
        }

        return false
    }
}
