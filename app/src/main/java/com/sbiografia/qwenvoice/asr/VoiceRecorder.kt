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
 * - [stop] 은 그때까지의 샘플을 -1..1 float 배열로 돌려준다.
 * - 최대 [MAX_SECONDS] 초에서 자동 정지한다.
 */
class VoiceRecorder {

    companion object {
        const val SAMPLE_RATE = 16_000
        const val MAX_SECONDS = 45
        private const val READ_FRAMES = 1_600 // 100ms
    }

    var onLevel: ((Float) -> Unit)? = null
    var onAutoStop: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var audioRecord: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile
    private var recording = false

    private var buffer = ShortArray(SAMPLE_RATE * 20)
    private var length = 0
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
            buffer = ShortArray(SAMPLE_RATE * 20)
            length = 0
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
            val count = length
            val samples = FloatArray(count)
            for (i in 0 until count) {
                samples[i] = buffer[i] / 32768f
            }
            length = 0
            samples
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

                append(chunk, read)

                var sum = 0.0
                for (i in 0 until read) {
                    val value = chunk[i] / 32768f
                    sum += (value * value).toDouble()
                }
                val rms = sqrt(sum / read).toFloat()
                val level = (rms * 6f).coerceIn(0f, 1f)
                mainHandler.post { onLevel?.invoke(level) }

                if (SystemClock.elapsedRealtime() - startedAt >= MAX_SECONDS * 1000L) {
                    mainHandler.post { onAutoStop?.invoke() }
                    break
                }
            }
        } catch (e: Exception) {
            mainHandler.post { onError?.invoke("녹음 오류: ${e.message}") }
        }
    }

    private fun append(chunk: ShortArray, count: Int) {
        synchronized(lock) {
            if (length + count > buffer.size) {
                var newSize = buffer.size * 2
                while (newSize < length + count) newSize *= 2
                buffer = buffer.copyOf(newSize)
            }
            System.arraycopy(chunk, 0, buffer, length, count)
            length += count
        }
    }
}
