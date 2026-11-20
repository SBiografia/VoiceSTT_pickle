package com.sbiografia.qwenvoice.asr

import android.content.Context
import com.sbiografia.qwenvoice.core.AppSettings
import com.sbiografia.qwenvoice.model.ModelKind
import com.sbiografia.qwenvoice.model.ModelSpec

/**
 * 한 구간(청크)의 인식을 어느 엔진으로 할지 정해 주는 얇은 진입점.
 *
 * - [AppSettings.ENGINE_GROQ] 이면 [GroqWhisperClient] (온라인, whisper-large-v3-turbo).
 * - 그 밖에는 기기 안 모델([AsrEngine], Qwen3-ASR 또는 SenseVoice).
 *
 * 키보드(IME)와 플로팅 마이크가 **같은 함수**를 쓰므로 두 모드의 동작이 어긋나지 않는다.
 * 여기서 돌려주는 것은 인식 원문이고, 단어 치환 사전·한자 정리 같은 후처리는
 * 부르는 쪽에서 [com.sbiografia.qwenvoice.postprocess.PostProcessors] 로 똑같이 거친다.
 *
 * 이 함수는 네트워크·추론으로 수 초 블로킹될 수 있으므로 반드시 IO 스레드에서 부른다.
 *
 * 무음 방어: 실질적 무음([SilenceGuard])은 여기서 곧바로 빈 문자열로 끝나 엔진을 부르지 않는다.
 * 무음 오디오가 Whisper 의 `prompt` 단어를 되풀이하는 환각("강현, 강현, 강현")을 막는 2차 방어다.
 */
object Transcriber {

    /** 지금 설정이 온라인(Groq) 인식인지. */
    fun isCloud(settings: AppSettings): Boolean =
        settings.engineId == AppSettings.ENGINE_GROQ

    /**
     * 무음 대기를 짧게(0.6~0.7초) 잡아야 하는 빠른 엔진인지.
     *
     * Qwen3-ASR 만 1.3초/최소 1.5초를 유지한다(1글자 발화가 한자로 새는 것을 막는 조건).
     */
    fun isFastCut(settings: AppSettings, spec: ModelSpec?): Boolean =
        isCloud(settings) || spec?.kind == ModelKind.SENSE_VOICE

    /**
     * 16kHz 모노 float 샘플을 인식해 **원문**을 돌려준다.
     *
     * @param spec 기기 내 모델. Groq 모드에서는 쓰지 않으므로 null 이어도 된다.
     * @param hotwords 치환 사전 힌트(쉼표 구분). Qwen3-ASR 는 시스템 프롬프트, Groq 는 `prompt` 로 쓴다.
     */
    fun transcribe(
        context: Context,
        settings: AppSettings,
        spec: ModelSpec?,
        samples: FloatArray,
        hotwords: String,
    ): String {
        if (samples.isEmpty()) return ""
        // 2차 무음 방어: 순수 무음은 어느 엔진으로도 넘기지 않는다(Whisper 프롬프트 환각 차단).
        if (SilenceGuard.isSilent(samples)) return ""

        if (isCloud(settings)) {
            return GroqWhisperClient.transcribe(
                apiKey = settings.groqApiKey,
                samples = samples,
                sampleRate = VoiceRecorder.SAMPLE_RATE,
                prompt = hotwords,
            )
        }

        val local = spec
            ?: throw IllegalStateException("기기 내 모델이 선택되어 있지 않습니다.")
        if (!AsrEngine.isLoaded(local.id)) {
            AsrEngine.prepare(context, local)
        }
        return AsrEngine.transcribe(samples, hotwords, VoiceRecorder.SAMPLE_RATE)
    }
}
