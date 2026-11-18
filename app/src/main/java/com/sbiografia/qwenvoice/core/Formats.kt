package com.sbiografia.qwenvoice.core

import java.util.Locale

fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.0f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format(Locale.US, "%.0f MB", mb)
    return String.format(Locale.US, "%.2f GB", mb / 1024.0)
}

fun formatClock(millis: Long): String {
    val totalSeconds = millis / 1000L
    return String.format(Locale.US, "%02d:%02d", totalSeconds / 60L, totalSeconds % 60L)
}
