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
 * - `prompt` 에는 치환 사전의 목표 단어([com.sbiografia.qwenvoice.core.DictionaryStore.hotwords])를
 *   그대로 넘겨 인식 전 힌트로 쓴다. 인식 뒤 치환은 후처리 체인이 한 번 더 한다.
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

    /** 이보다 짧은 소리는 보내지 않는다(Groq 최소 길이 0.01초보다 넉넉하게). */
    private const val MIN_SAMPLES = 1_600 // 0.1초

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * [samples] 를 WAV 로 만들어 Groq 에 보내고 인식 문장을 돌려준다.
     *
     * @param apiKey `gsk_...` 로 시작하는 Groq API 키
     * @param prompt 치환 사전 힌트(쉼표로 이은 단어). 비어 있으면 보내지 않는다.
     * @throws GroqException 키가 없거나, 연결·HTTP 오류일 때
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

        val audio = encodeWav(samples, sampleRate)
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", MODEL)
            .addFormDataPart("language", "ko")
            .addFormDataPart("response_format", "json")
            .apply {
                val hint = prompt.trim()
                if (hint.isNotEmpty()) addFormDataPart("prompt", hint)
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
            val text = try {
                JSONObject(payload).optString("text", "")
            } catch (e: Exception) {
                throw GroqException("Groq 응답을 해석하지 못했습니다.", e)
            }
            return text.trim()
        }
    }

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
