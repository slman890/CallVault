// SPDX-License-Identifier: GPL-3.0-or-later
package com.baba.callvault.transcription

/** A manual request gets an actionable failure; an automatic download race gets two retries. */
internal object MissingModelPolicy {
    fun mayRetry(userRequested: Boolean, previousAttempts: Int): Boolean =
        !userRequested && previousAttempts < 2
}
