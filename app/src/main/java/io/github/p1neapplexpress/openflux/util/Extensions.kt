package io.github.p1neapplexpress.openflux.util

import android.content.Context
import java.util.Locale

fun Int.dpToPx(context: Context): Int =
    (this * context.resources.displayMetrics.density).toInt()

fun Long.toUptimeHms(): String {
    val h = this / 3600
    val m = (this % 3600) / 60
    val s = this % 60
    return if (h > 0) {
        "%d:%02d:%02d".format(h, m, s)
    } else {
        "%02d:%02d".format(m, s)
    }
}

/** Formats a byte/sec throughput figure as e.g. "128 KB/s" or "1.4 MB/s". */
fun Long.toSpeedString(): String = when {
    this < 1024 -> "$this B/s"
    this < 1024 * 1024 -> String.format(Locale.US, "%.0f KB/s", this / 1024.0)
    else -> String.format(Locale.US, "%.1f MB/s", this / (1024.0 * 1024.0))
}
