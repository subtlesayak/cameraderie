package app.gripbridge.ble

import android.os.Build

object GattConnectionDiagnostics {
    fun available(): Boolean =
        Build.VERSION.SDK_INT >= 36 && Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.CINNAMON_BUN_2

    fun parameters(interval: Int, latency: Int, supervisionTimeout: Int): String? {
        if (interval !in 6..3200 || latency !in 0..499 || supervisionTimeout !in 10..3200) return null
        return "interval_us=${interval * 1250} peripheral_latency_events=$latency supervision_timeout_ms=${supervisionTimeout * 10}"
    }
}
