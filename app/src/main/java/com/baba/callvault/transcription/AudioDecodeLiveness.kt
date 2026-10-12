// SPDX-License-Identifier: GPL-3.0-or-later
package com.baba.callvault.transcription

/** Bounds repeated codec polls that produce no decoded audio, using a monotonic clock. */
internal class AudioDecodeLiveness(startedAtMs: Long, private val maxIdleMs: Long = 120_000L) {
    private var lastOutputMs = startedAtMs
    fun outputProduced(nowMs: Long) { lastOutputMs = nowMs }
    fun requireResponsive(nowMs: Long) {
        if (nowMs - lastOutputMs >= maxIdleMs) throw AudioDecodeStalledException()
    }
}

internal class AudioDecodeStalledException : IllegalStateException("Audio decoder stopped producing output")
