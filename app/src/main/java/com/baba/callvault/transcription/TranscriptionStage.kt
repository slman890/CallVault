// SPDX-License-Identifier: GPL-3.0-or-later
package com.baba.callvault.transcription

enum class TranscriptionStage(val key: String) {
    LOADING("loading"), DECODING("decoding"), RECOGNIZING("recognizing"), SAVING("saving");
    companion object {
        fun fromKey(key: String?): TranscriptionStage? = entries.firstOrNull { it.key == key }
    }
}
