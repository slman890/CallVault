// SPDX-License-Identifier: GPL-3.0-or-later
package com.baba.callvault.transcription

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MissingModelPolicyTest {
    @Test fun a_manual_request_never_waits_for_an_unscheduled_download() {
        assertFalse(MissingModelPolicy.mayRetry(true, 0))
    }
    @Test fun an_automatic_download_race_has_a_finite_retry_budget() {
        assertTrue(MissingModelPolicy.mayRetry(false, 0))
        assertTrue(MissingModelPolicy.mayRetry(false, 1))
        assertFalse(MissingModelPolicy.mayRetry(false, 2))
        assertFalse(MissingModelPolicy.mayRetry(false, 20))
    }
}
