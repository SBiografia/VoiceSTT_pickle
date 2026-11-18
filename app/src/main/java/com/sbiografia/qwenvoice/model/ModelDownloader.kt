package com.sbiografia.qwenvoice.model

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 모델 파일 다운로드. Activity 수명과 무관하게 동작하고,
 * .part 파일로 이어받기(resume)를 지원한다.
 */
object ModelDownloader {

    data class Progress(
        val modelId: String,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val running: Boolean,
        val error: String? = null,
    ) {
        val percent: Int
            get() = if (totalBytes <= 0L) 0
            else ((downloadedBytes.toDouble() / totalBytes.toDouble()) * 100.0).toInt().coerceIn(0, 100)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var job: Job? = null

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress

    @Synchronized
    fun isRunning(modelId: String): Boolean =
        job?.isActive == true && _progress.value?.modelId == modelId && _progress.value?.running == true

    @Synchronized
    fun start(context: Context, spec: ModelSpec) {
        if (job?.isActive == true) return
        val appContext = context.applicationContext
        _progress.value = Progress(
            modelId = spec.id,
            downloadedBytes = ModelRepository.downloadedBytes(appContext, spec),
            totalBytes = spec.totalBytes,
            running = true,
        )

        job = scope.launch {
            try {
                ModelRepository.modelDir(appContext, spec).mkdirs()
                for (file in spec.files) {
                    if (!isActive) throw CancellationException("다운로드 취소")
                    downloadOne(appContext, spec, file)
                    _progress.value = Progress(
                        modelId = spec.id,
                        downloadedBytes = ModelRepository.downloadedBytes(appContext, spec),
                        totalBytes = spec.totalBytes,
                        running = true,
                    )
                }

                val missing = spec.files.filterNot { ModelRepository.isFileComplete(appContext, spec, it) }
                if (missing.isNotEmpty()) {
                    throw IOException("파일 검증 실패: " + missing.joinToString(",") { it.relativePath })
                }
                _progress.value = Progress(
                    modelId = spec.id,
                    downloadedBytes = spec.totalBytes,
                    totalBytes = spec.totalBytes,
                    running = false,
                )
            } catch (e: CancellationException) {
                _progress.value = Progress(
                    modelId = spec.id,
                    downloadedBytes = ModelRepository.downloadedBytes(appContext, spec),
                    totalBytes = spec.totalBytes,
                    running = false,
                )
                throw e
            } catch (e: Exception) {
                _progress.value = Progress(
                    modelId = spec.id,
                    downloadedBytes = ModelRepository.downloadedBytes(appContext, spec),
                    totalBytes = spec.totalBytes,
                    running = false,
                    error = e.message ?: e.javaClass.simpleName,
                )
            }
        }
    }

    @Synchronized
    fun cancel() {
        job?.cancel()
        job = null
    }

    /**
     * 파일 하나를 받는다. 이미 완료된 파일은 건너뛰고,
     * .part 가 있으면 Range 요청으로 이어받는다. 최대 3회 재시도.
     */
    private fun downloadOne(context: Context, spec: ModelSpec, file: ModelFile) {
        val target = ModelRepository.fileFor(context, spec, file.relativePath)
        if (target.isFile && target.length() == file.sizeBytes) return

        val parent = target.parentFile
        parent?.mkdirs()
        val part = ModelRepository.partFor(context, spec, file.relativePath)

        var attempt = 0
        while (true) {
            try {
                if (part.isFile && part.length() > file.sizeBytes) {
                    part.delete()
                }
                val already = if (part.isFile) part.length() else 0L
                if (already == file.sizeBytes) {
                    moveToTarget(part, target)
                    return
                }

                val builder = Request.Builder().url(file.url)
                if (already > 0L) {
                    builder.header("Range", "bytes=$already-")
                }

                client.newCall(builder.build()).execute().use { response ->
                    if (response.code == 416) {
                        // 서버가 이미 끝난 파일로 판단 → 처음부터 다시
                        part.delete()
                        throw IOException("다시 받아야 합니다 (416)")
                    }
                    if (!response.isSuccessful) {
                        throw IOException("HTTP ${response.code}")
                    }
                    val body = response.body ?: throw IOException("빈 응답")
                    val append = response.code == 206 && already > 0L

                    FileOutputStream(part, append).use { out ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            var lastReport = System.currentTimeMillis()
                            var unsynced = 0L
                            while (true) {
                                ensureActiveForDownload()
                                val read = input.read(buffer)
                                if (read <= 0) break
                                out.write(buffer, 0, read)
                                unsynced += read
                                val now = System.currentTimeMillis()
                                if (unsynced >= 1L * 1024 * 1024 || now - lastReport >= 400L) {
                                    unsynced = 0L
                                    lastReport = now
                                    _progress.value = Progress(
                                        modelId = spec.id,
                                        downloadedBytes = ModelRepository.downloadedBytes(context, spec),
                                        totalBytes = spec.totalBytes,
                                        running = true,
                                    )
                                }
                            }
                            out.flush()
                        }
                    }
                }

                if (part.isFile && part.length() == file.sizeBytes) {
                    moveToTarget(part, target)
                    return
                }
                throw IOException("크기 불일치: ${part.length()} / ${file.sizeBytes}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                attempt++
                if (attempt >= 3) throw e
                Thread.sleep(1500L)
            }
        }
    }

    private fun moveToTarget(part: File, target: File) {
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
        }
    }

    /** 다운로드 도중 취소를 확인한다(작업 스레드 기준). */
    private fun ensureActiveForDownload() {
        if (job?.isActive != true) throw CancellationException("다운로드 취소")
    }
}
