package com.imagecompressor.app.util

import java.util.Locale
import kotlin.math.roundToInt

/** Human readable file size, e.g. "86.4 KB" or "1.82 MB". */
fun formatSize(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 100L * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> String.format(Locale.US, "%.2f MB", bytes / 1048576.0)
}

/** Label for a target expressed in KB, e.g. 20 -> "20 KB", 1024 -> "1 MB". */
fun formatTargetKb(kb: Long): String =
    if (kb >= 1024 && kb % 1024 == 0L) "${kb / 1024} MB" else "$kb KB"

/** Percentage of size saved (negative if the result is bigger than the original). */
fun percentSaved(original: Long, final: Long): Int =
    if (original <= 0L) 0 else (((original - final).toDouble() / original) * 100).roundToInt()
