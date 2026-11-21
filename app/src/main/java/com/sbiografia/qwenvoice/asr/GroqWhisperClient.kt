package com.sbiografia.qwenvoice.asr

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Groq Whisper(온라인 초고속) 로 16kHz 모노 PCM 을 인식한다.
 *
 * - [VoiceRecorder] 가 말이 끊긴 구간마다 주는 `FloatArray`(-1..1)를 메모리에서
 *   16bit PCM WAV 로 인코딩해 `multipart/form-data` 로 한 번에 보낸다.
 * - 요청 형식은 OpenAI 호환이다: `model`, `language`, `response_format`, `prompt`, `file`.
 *   (Groq 공식 문서 <https://console.groq.com/docs/speech-to-text> 에서 엔드포인트·모델·지원 형식 확인)
 * - `prompt` 에는 치환 사전의 단어([com.sbiografia.qwenvoice.core.DictionaryStore.hotwords])를
 *   **단어만 나열하지 않고 자연어 문맥**으로 감싸 보낸다([buildPrompt]). 단어만 던지면 Whisper 가
 *   그 단어를 이어 쓰는 습관이 생겨 환각이 늘어난다. 인식 뒤 치환은 후처리 체인이 한 번 더 한다.
 * - `response_format=verbose_json` 으로 받아 세그먼트의 `no_speech_prob`·`avg_logprob`·
 *   `compression_ratio` 를 검사하고, 환각으로 판단한 세그먼트는 버린다([isHallucinatedSegment]).
 *   정상 세그먼트가 하나도 남지 않으면 빈 문자열이다.
 * - 마지막으로 [HallucinationFilter] 가 프롬프트 메아리(`"강현, 강현, 강현"`)를 지운다.
 * - 실질적 무음([SilenceGuard]) 은 보내지 않고 빈 문자열을 돌려준다(1.5.1). 무음 오디오를 보내면
 *   Whisper 가 `prompt` 단어를 스스로 되풀이하는 환각("강현, 강현, 강현") 이 생겼다.
 * - 네트워크·HTTP 오류는 [GroqException](IOException) 으로 바꿔 던진다. 호출 쪽(키보드·플로팅)이
 *   잡아 상태 문구로 보여 주므로 앱이 죽지 않는다.
 */
object GroqWhisperClient {

    const val ENDPOINT = "https://api.groq.com/openai/v1/audio/transcriptions"
    const val MODEL = "whisper-large-v3-turbo"
    const val CONSOLE_URL = "https://console.groq.com/keys"

    /** 키가 비었을 때의 안내 문구. 호출 쪽 상태 표시와 같은 내용이다. */
    const val ERROR_NO_KEY =
        "Groq API 키가 없습니다. 앱 설정의 '3. 인식 엔진'에서 키를 입력해 주세요."

    /** `prompt` 앞에 붙이는 자연어 문맥. 단어만 나열하는 것보다 환각이 적다. */
    const val PROMPT_PREFIX = "한국어 음성 받아쓰기. 고유명사 참고: "

    /** 이보다 짧은 소리는 보내지 않는다(Groq 최소 길이 0.01초보다 넉넉하게). */
    private const val MIN_SAMPLES = 1_600 // 0.1초

    /** 이 확률 이상이면 말소기가 없다고 본 세그먼트다. */
    const val NO_SPEECH_DROP = 0.60

    /** 말소기 없음 확률이 이 이상이면서 [AVG_LOGPROB_LOW] 보다 낮은 신뢰도면 버린다. */
    const val NO_SPEECH_SUSPECT = 0.45

    /** 세그먼트 평균 로그확률이 이보다 낮으면 모델도 확신하지 못한 것이다. */
    const val AVG_LOGPROB_LOW = -1.0

    /** 압축률이 이 이상이면 같은 말을 되풀이한 환각 세그먼트다. */
    const val REPEAT_COMPRESSION_DROP = 2.4

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * 힌트 단어 목록을 API `prompt` 문구로 감싼다. 힌트가 비면 빈 문자열(보내지 않음).
     *
     * 필터 검사는 이 문구가 아니라 원본 힌트 단어 목록으로 한다([HallucinationFilter]).
     */
    fun buildPrompt(hint: String): String {
        val words = hint.trim()
        return if (words.isEmpty()) "" else PROMPT_PREFIX + words
    }

    /**
     * [samples] 를 WAV 로 만들어 Groq 에 보내고 인식 문장을 돌려준다.
     *
     * @param apiKey `gsk_...` 로 시작하는 Groq API 키
     * @param prompt 치환 사전 힌트(쉼표로 이은 단어 목록). 비어 있으면 `prompt` 를 보내지 않는다.
     * @throws GroqException 키가 없거나, 연결·HTTP·응답 해석 오류일 때
     *
     * 실질적 무음([SilenceGuard]) 이거나 0.1초보다 짧으면 요청을 만들지 않고 빈 문자열을 돌려준다.
     */
    fun transcribe(
        apiKey: String,
        samples: FloatArray,
        sampleRate: Int = VoiceRecorder.SAMPLE_RATE,
        prompt: String = "",
    ): String {
        val key = apiKey.trim()
        if (key.isEmpty()) throw GroqException(ERROR_NO_KEY)
        if (samples.size < MIN_SAMPLES) return ""
        // 무음·배경잡음은 요청조차 만들지 않는다: 보내면 Whisper 가 prompt 단어를 되풀이하는 환각이 생긴다.
        if (SilenceGuard.isSilent(samples)) return ""

        val hint = prompt.trim()
        val audio = encodeWav(samples, sampleRate)
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", MODEL)
            .addFormDataPart("language", "ko")
            .addFormDataPart("response_format", "verbose_json")
            .apply {
                val formPrompt = buildPrompt(hint)
                if (formPrompt.isNotEmpty()) addFormDataPart("prompt", formPrompt)
            }
            .addFormDataPart(
                "file",
                "chunk.wav",
                audio.toRequestBody("audio/wav".toMediaType()),
            )
            .build()

        val request = Request.Builder()
            .url(ENDPOINT)
            .header("Authorization", "Bearer $key")
            .post(body)
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: java.net.SocketTimeoutException) {
            throw GroqException("Groq 응답이 늦습니다(시간 초과). 잠시 뒤 다시 말해 주세요.", e)
        } catch (e: IOException) {
            throw GroqException("네트워크 오류로 Groq에 연결하지 못했습니다. 연결을 확인해 주세요.", e)
        }

        response.use { res ->
            val payload = try {
                res.body?.string().orEmpty()
            } catch (e: IOException) {
                throw GroqException("Groq 응답을 읽지 못했습니다.", e)
            }
            if (!res.isSuccessful) throw GroqException(describeError(res.code, payload))
            return interpretResponse(payload, hint)
        }
    }

    /**
     * Groq 응답(JSON 문자열)에서 환각 세그먼트를 걸러 인식문을 만든다. **순수 함수**라 JVM 에서 검증한다.
     *
     * @param hint 원본 힌트 단어 목록(쉼표 구분). 프롬프트 메아리 판정에 쓴다.
     * @throws GroqException 응답이 JSON 이 아닐 때
     */
    fun interpretResponse(payload: String, hint: String = ""): String {
        val root = try {
            JSONObject(payload)
        } catch (e: Exception) {
            throw GroqException("Groq 응답을 해석하지 못했습니다.", e)
        }

        val segments = parseSegments(root)
        val raw = if (segments == null) {
            // segments 가 없거나 비었으면 기존처럼 text 로 폴백한다.
            root.optString("text", "").trim()
        } else {
            combineSegments(segments)
        }
        return HallucinationFilter.filter(raw, hint)
    }

    /** 응답 세그먼트 하나. Whisper verbose_json 의 환각 판단 값들. */
    data class Segment(
        val text: String,
        val noSpeechProb: Double,
        val avgLogprob: Double,
        val compressionRatio: Double,
    )

    /**
     * `segments` 배열을 읽는다. 없거나 비었거나 읽을 수 없으면 null(호출 쪽이 `text` 폴백).
     *
     * 값이 없는 필드는 중립값(no_speech_prob 0, avg_logprob 0, compression_ratio 1)으로 둔다 —
     * 정보가 없다고 정상 발화를 버리지 않는다.
     */
    fun parseSegments(root: JSONObject): List<Segment>? {
        val array = root.optJSONArray("segments") ?: return null
        if (array.length() == 0) return null

        val out = ArrayList<Segment>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            out.add(
                Segment(
                    text = item.optString("text", "").trim(),
                    noSpeechProb = number(item, "no_speech_prob", 0.0),
                    avgLogprob = number(item, "avg_logprob", 0.0),
                    compressionRatio = number(item, "compression_ratio", 1.0),
                )
            )
        }
        return out.ifEmpty { null }
    }

    /** 필드가 없거나 숫자가 아니면 [fallback]. NaN·무한대도 [fallback]. */
    private fun number(item: JSONObject, key: String, fallback: Double): Double {
        if (!item.has(key)) return fallback
        val value = item.optDouble(key, fallback)
        return if (value.isNaN() || value.isInfinite()) fallback else value
    }

    /**
     * 환각으로 판단한 세그먼트인지.
     *
     * 1. `compression_ratio >= 2.4` — 같은 말을 되풀이한 전형적 환각
     * 2. `no_speech_prob >= 0.60` — 말소기가 없다고 본 구간
     * 3. `no_speech_prob >= 0.45` 이면서 `avg_logprob < -1.0` — 말소기도 의심되고 확신도 낮은 구간
     */
    fun isHallucinatedSegment(segment: Segment): Boolean {
        if (segment.compressionRatio >= REPEAT_COMPRESSION_DROP) return true
        if (segment.noSpeechProb >= NO_SPEECH_DROP) return true
        if (segment.noSpeechProb >= NO_SPEECH_SUSPECT && segment.avgLogprob < AVG_LOGPROB_LOW) return true
        return false
    }

    /** 남은 세그먼트들의 문장을 순서대로 잇는다. 남는 세그먼트가 없으면 빈 문자열. */
    fun combineSegments(segments: List<Segment>): String =
        segments
            .filterNot { isHallucinatedSegment(it) }
            .map { it.text.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .trim()

    /** HTTP 상태 코드를 사용자가 읽을 수 있는 문장으로 바꾼다. */
    private fun describeError(code: Int, payload: String): String = when (code) {
        401, 403 -> "Groq API 키가 올바르지 않습니다(HTTP $code). 앱 설정에서 키를 다시 확인해 주세요."
        413 -> "녹음 구간이 너무 깁니다(HTTP 413). 짧게 끊어 말해 주세요."
        429 -> "Groq 무료 한도(HTTP 429)를 넘었습니다. 잠시 뒤 다시 시도해 주세요."
        in 500..599 -> "Groq 서버 오류(HTTP $code). 잠시 뒤 다시 시도해 주세요."
        else -> {
            val detail = runCatching {
                JSONObject(payload).optJSONObject("error")?.optString("message").orEmpty()
            }.getOrDefault("")
            if (detail.isNotBlank()) "Groq 오류(HTTP $code): $detail"
            else "Groq 오류(HTTP $code)"
        }
    }

    /**
     * -1..1 float 샘플을 16bit PCM WAV(모노) 바이트로 인코딩한다. 파일을 만들지 않고 메모리에서 끝낸다.
     * 헤더 44바이트: RIFF/WAVE/fmt(16)/data.
     */
    fun encodeWav(samples: FloatArray, sampleRate: Int = VoiceRecorder.SAMPLE_RATE): ByteArray {
        val dataSize = samples.size * 2
        val out = ByteArrayOutputStream(44 + dataSize)

        fun ascii(text: String) = out.write(text.toByteArray(Charsets.US_ASCII))
        fun le32(value: Int) {
            out.write(value and 0xFF)
            out.write((value shr 8) and 0xFF)
            out.write((value shr 16) and 0xFF)
            out.write((value shr 24) and 0xFF)
        }

        fun le16(value: Int) {
            out.write(value and 0xFF)
            out.write((value shr 8) and 0xFF)
        }

        ascii("RIFF")
        le32(36 + dataSize)
        ascii("WAVE")
        ascii("fmt ")
        le32(16) // fmt 청크 크기
        le16(1) // PCM
        le16(1) // 모노
        le32(sampleRate)
        le32(sampleRate * 2) // 초당 바이트(모노 16bit)
        le16(2) // 블록 정렬
        le16(16) // 비트 깊이
        ascii("data")
        le32(dataSize)

        for (sample in samples) {
            val value = (sample.coerceIn(-1f, 1f) * 32_767f).toInt()
            out.write(value and 0xFF)
            out.write((value shr 8) and 0xFF)
        }
        return out.toByteArray()
    }
}

/** Groq 인식 실패. [IOException] 이라 호출 쪽의 기존 예외 처리 경로를 그대로 탄다. */
class GroqException(message: String, cause: Throwable? = null) : IOException(message, cause)
