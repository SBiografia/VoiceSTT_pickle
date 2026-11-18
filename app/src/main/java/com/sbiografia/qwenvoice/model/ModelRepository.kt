package com.sbiografia.qwenvoice.model

import android.content.Context
import java.io.File

/** 모델 파일이 기기 어디에 있는지, 얼마나 받았는지 계산한다. */
object ModelRepository {

    fun modelDir(context: Context, spec: ModelSpec): File =
        File(context.filesDir, "models/" + spec.id)

    fun fileFor(context: Context, spec: ModelSpec, relativePath: String): File =
        File(modelDir(context, spec), relativePath)

    fun partFor(context: Context, spec: ModelSpec, relativePath: String): File {
        val target = fileFor(context, spec, relativePath)
        return File(target.parentFile, target.name + ".part")
    }

    fun isFileComplete(context: Context, spec: ModelSpec, file: ModelFile): Boolean {
        val target = fileFor(context, spec, file.relativePath)
        return target.isFile && target.length() == file.sizeBytes
    }

    fun isInstalled(context: Context, spec: ModelSpec): Boolean =
        spec.files.all { isFileComplete(context, spec, it) }

    /** 완료 + 부분 다운로드 합계. 이어받기 버튼 표시와 진행률에 쓴다. */
    fun downloadedBytes(context: Context, spec: ModelSpec): Long {
        var total = 0L
        for (file in spec.files) {
            val done = fileFor(context, spec, file.relativePath)
            val part = partFor(context, spec, file.relativePath)
            total += when {
                done.isFile && done.length() == file.sizeBytes -> file.sizeBytes
                part.isFile -> part.length().coerceAtMost(file.sizeBytes)
                else -> 0L
            }
        }
        return total
    }

    fun installedBytes(context: Context): Long =
        ModelCatalog.all.filter { isInstalled(context, it) }.sumOf { it.totalBytes }

    fun delete(context: Context, spec: ModelSpec): Boolean =
        modelDir(context, spec).deleteRecursively()
}
