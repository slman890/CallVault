// SPDX-License-Identifier: GPL-3.0-or-later
package com.baba.callvault.data.recordings

import com.baba.callvault.data.health.CallLogEntry
import org.junit.Assert.*
import org.junit.Test

class CallSimLookupTest {
    private val request = CallSimLookup.Request(1_000_000L, 1_060_000L, RecordingDirection.INCOMING, "+966500000001")
    private fun entry(account: String, incoming: Boolean = true, start: Long = 995_000L,
        number: String = "+966500000001") = CallLogEntry(start, 60L, incoming, null, number, "com.android.phone/.Service", account)

    @Test fun resolves_the_matching_incoming_account_without_using_the_default_sim() {
        val match = CallSimLookup.matchingAccount(listOf(entry("line-a"), entry("line-b", number = "+966500000002")), request)
        assertEquals("line-a", match?.accountId)
    }
    @Test fun outgoing_calls_use_their_own_account() {
        val match = CallSimLookup.matchingAccount(listOf(entry("incoming"), entry("outgoing", incoming = false)),
            request.copy(direction = RecordingDirection.OUTGOING))
        assertEquals("outgoing", match?.accountId)
    }
    @Test fun overlapping_accounts_remain_unknown() {
        assertNull(CallSimLookup.matchingAccount(listOf(entry("line-a"), entry("line-b")), request))
        assertNull(CallSimLookup.matchingAccount(listOf(entry("line-a"), entry("line-b")), request.copy(number = null)))
    }
    @Test fun stale_calls_and_missing_accounts_are_not_assigned() {
        assertNull(CallSimLookup.matchingAccount(listOf(entry("line-a", start = 1L)), request))
        assertNull(CallSimLookup.matchingAccount(listOf(entry("")), request))
        assertNull(CallSimLookup.matchingAccount(listOf(entry("line-a", number = "123")), request))
    }
    @Test fun account_identity_does_not_store_the_raw_identifier_or_confuse_components() {
        val first = CallSimLookup.accountKey("component-a", "private-account-identifier")
        assertEquals(64, first.length)
        assertFalse(first.contains("private-account"))
        assertNotEquals(first, CallSimLookup.accountKey("component-b", "private-account-identifier"))
        assertEquals(first, CallSimLookup.accountKey("component-a", "private-account-identifier"))
    }
    @Test fun locally_dialed_numbers_can_match_without_an_invented_country_prefix() {
        val match = CallSimLookup.matchingAccount(listOf(entry("local-line", incoming = false, number = "0500000001")),
            request.copy(direction = RecordingDirection.OUTGOING, number = "050 000 0001"))
        assertEquals("local-line", match?.accountId)
    }
}
