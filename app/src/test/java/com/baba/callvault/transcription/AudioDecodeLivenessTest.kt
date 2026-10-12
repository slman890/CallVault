// SPDX-License-Identifier: GPL-3.0-or-later
package com.baba.callvault.transcription

import org.junit.Test

class AudioDecodeLivenessTest {
    @Test(expected = AudioDecodeStalledException::class)
    fun repeated_codec_polls_without_audio_eventually_stop() {
        val guard = AudioDecodeLiveness(1000L)
        guard.requireResponsive(120_999L)
        guard.requireResponsive(121_000L)
    }

    @Test fun a_long_decode_is_allowed_while_audio_keeps_arriving() {
        val guard = AudioDecodeLiveness(0L)
        repeat(100) { step ->
            val time = (step + 1) * 119_000L
            guard.requireResponsive(time)
            guard.outputProduced(time)
        }
    }

    @Test(expected = AudioDecodeStalledException::class)
    fun producing_audio_once_does_not_hide_a_later_stall() {
        val guard = AudioDecodeLiveness(0L)
        guard.outputProduced(50_000L)
        guard.requireResponsive(170_000L)
    }
}
