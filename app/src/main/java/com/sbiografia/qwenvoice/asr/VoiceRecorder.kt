package com.sbiografia.qwenvoice.asr

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.math.sqrt

/**
 * 16kHz 모노 PCM16 녹음기.
 *
 * - [start] 는 즉시 반환하고 별도 스레드에서 계속 읽는다.
 * - 녹음을 끊지 않고 말이 끊긴 지점마다 [onChunkReady] 로 중간 청크를 내보낸다(스트리밍).
 * - [stop] 은 그때까지 남아 있던 마지막 청크를 -1..1 float 배열로 돌려준다.
 * - 최대 [MAX_SECONDS] 초에서 자동 정지한다.
 *
 * 청크 분할 규칙(100ms 프레임마다 RMS 로 판단):
 * - 말소리(RMS >= [SPEECH_RMS_THRESHOLD])가 한 번이라도 들어와야 청크를 끊을 수 있다.
 * - 말이 끝나고 약 0.8초 조용하면 즉시 끊는다.
 * - 길어지면 10초를 넘긴 뒤 짧은 쉼(0.2초)에서, 또는 13초에서 무조건 끊는다.
 * - 아직 말이 시작되지 않은 무음만 3초 넘게 쌓이면 최근 0.5초만 남기고 버린다.
 */
class VoiceRecorder {

    companion object {
        const val SAMPLE_RATE = 16_000

        /** 최대 녹음 시간(초). 10분. */
        const val MAX_SECONDS = 600

        /** 한 번에 읽는 샘플 수(100ms). RMS 판단 단위이기도 하다. */
        private const val READ_FRAMES = 1_600

        /** 이 RMS 이상이면 말소리로 본다. */
        private const val SPEECH_RMS_THRESHOLD = 0.015f

        /** 말소리 뒤 이만큼(프레임=100ms) 조용하면 청크를 끊는다. 약 0.8초. */
        private const val SILENT_FRAMES_TO_CUT = 8

        /** 이보다 짧은 청크는 끊지 않는다(1초). */
        private const val MIN_CHUNK_SAMPLES = SAMPLE_RATE * 1

        /** 길게 말할 때 이 길이를 넘기면 짧은 쉼에서 끊는다(10초). */
        private const val SOFT_MAX_CHUNK_SECONDS = 10

        /** 쉼이 없어도 이 길이에서 끊는다(13초). */
        private const val HARD_MAX_CHUNK_SECONDS = 13

        /** 말이 시작되기 전 무음은 이만큼 넘으면 앞부분을 버린다(3초). */
        private const val IDLE_SILENCE_SECONDS = 3

        /** 무음만 쌓였을 때 남겨 두는 최근 구간(0.5초). */
        private const val IDLE_KEEP_SAMPLES = SAMPLE_RATE / 2
    }

    var onLevel: ((Float) -> Unit)? = null
    var onAutoStop: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    /**
     * 녹음을 멈추지 않고 중간 청크를 내보낸다.
     *
     * 이 콜백은 **녹음 스레드**에서 호출된다. UI 를 직접 건드리지 말고
     * 스레드 안전한 곳(Channel 등)으로 넘겨야 한다.
     */
    @Volatile
    var onChunkReady: ((FloatArray) -> Unit)? = null

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var audioRecord: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile
    private var recording = false

    /** 현재 청크 버퍼. 청크를 내보낼 때마다 비운다. */
    private var buffer = ShortArray(SAMPLE_RATE * 6)
    private var length = 0

    /** 현재 청크에 말소리가 한 번이라도 들어왔는지. */
    private var hasSpeech = false

    /** 말소리 뒤 연속 무음 프레임(100ms) 수. */
    private var silentFrames = 0

    private var startedAt = 0L

    val isRecording: Boolean
        get() = recording

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (recording) return false

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            onError?.invoke("오디오 버퍼를 만들 수 없습니다.")
            return false
        }

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer * 2, 8192),
            )
        } catch (e: Exception) {
            onError?.invoke("마이크 초기화 실패: ${e.message}")
            return false
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            onError?.invoke("마이크를 열 수 없습니다. 권한이나 다른 앱의 사용 여부를 확인해 주세요.")
            return false
        }

        synchronized(lock) {
            buffer = ShortArray(SAMPLE_RATE * 6)
            length = 0
            hasSpeech = false
            silentFrames = 0
        }
        audioRecord = record

        try {
            record.startRecording()
        } catch (e: Exception) {
            record.release()
            audioRecord = null
            onError?.invoke("녹음을 시작할 수 없습니다: ${e.message}")
            return false
        }

        recording = true
        startedAt = SystemClock.elapsedRealtime()
        thread = Thread({ readLoop(record) }, "voice-recorder").also { it.start() }
        return true
    }

    /**
     * 녹음을 멈추고 남은 마지막 청크를 돌려준다.
     *
     * 말소리가 들어 있었거나 최소 길이([MIN_CHUNK_SAMPLES]) 이상이면 그 내용을,
     * 아니면 빈 배열을 돌려준다.
     */
    fun stop(): FloatArray {
        if (!recording && audioRecord == null) return FloatArray(0)
        recording = false

        val record = audioRecord
        audioRecord = null
        try {
            record?.stop()
        } catch (e: Exception) {
            // 이미 멈춘 경우 무시
        }
        try {
            thread?.join(600)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        thread = null
        try {
            record?.release()
        } catch (e: Exception) {
            // 무시
        }

        return synchronized(lock) {
            if (length == 0) {
                FloatArray(0)
            } else if (hasSpeech || length >= MIN_CHUNK_SAMPLES) {
                takeChunkLocked()
            } else {
                length = 0
                hasSpeech = false
                silentFrames = 0
                FloatArray(0)
            }
        }
    }

    fun cancel() {
        stop()
    }

    private fun readLoop(record: AudioRecord) {
        val chunk = ShortArray(READ_FRAMES)
        try {
            while (recording) {
                val read = record.read(chunk, 0, chunk.size)
                if (read < 0) break
                if (read == 0) continue

                var sum = 0.0
                for (i in 0 until read) {
                    val value = chunk[i] / 32768f
                    sum += (value * value).toDouble()
                }
                val rms = sqrt(sum / read).toFloat()
                val level = (rms * 6f).coerceIn(0f, 1f)
                mainHandler.post { onLevel?.invoke(level) }

                appendAndMaybeCut(chunk, read, rms)?.let { ready ->
                    onChunkReady?.invoke(ready)
                }

                if (SystemClock.elapsedRealtime() - startedAt >= MAX_SECONDS * 1000L) {
                    mainHandler.post { onAutoStop?.invoke() }
                    break
                }
            }
        } catch (e: Exception) {
            mainHandler.post { onError?.invoke("녹음 오류: ${e.message}") }
        }
    }

    /**
     * 프레임을 현재 청크에 붙이고, 규칙에 닿으면 완성된 청크를 잘라 돌려준다.
     * 청크를 내보내도 녹음 자체는 계속된다.
     */
    private fun appendAndMaybeCut(chunk: ShortArray, count: Int, rms: Float): FloatArray? =
        synchronized(lock) {
            if (length + count > buffer.size) {
                var newSize = buffer.size * 2
                while (newSize < length + count) newSize *= 2
                buffer = buffer.copyOf(newSize)
            }
            System.arraycopy(chunk, 0, buffer, length, count)
            length += count

            if (rms >= SPEECH_RMS_THRESHOLD) {
                hasSpeech = true
                silentFrames = 0
            } else {
                silentFrames++
            }

            if (!hasSpeech) {
                // 아직 말이 시작되지 않았고 무음만 쌓였다면 최근 0.5초만 남긴다.
                if (length >= SAMPLE_RATE * IDLE_SILENCE_SECONDS) {
                    val keep = IDLE_KEEP_SAMPLES
                    System.arraycopy(buffer, length - keep, buffer, 0, keep)
                    length = keep
                }
                return@synchronized null
            }

            if (length < MIN_CHUNK_SAMPLES) return@synchronized null

            val softMax = length >= SAMPLE_RATE * SOFT_MAX_CHUNK_SECONDS
            val hardMax = length >= SAMPLE_RATE * HARD_MAX_CHUNK_SECONDS
            val cut = silentFrames >= SILENT_FRAMES_TO_CUT ||
                hardMax ||
                (softMax && silentFrames >= 2)

            if (cut) takeChunkLocked() else null
        }

    /** 현재 청크를 float 배열로 복사하고 버퍼를 비운다. lock 을 잡은 상태에서만 호출한다. */
    private fun takeChunkLocked(): FloatArray {
        val count = length
        val samples = FloatArray(count)
        for (i in 0 until count) {
            samples[i] = buffer[i] / 32768f
        }
        length = 0
        hasSpeech = false
        silentFrames = 0
        return samples
    }
}
