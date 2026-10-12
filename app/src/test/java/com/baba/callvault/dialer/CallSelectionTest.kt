// SPDX-License-Identifier: GPL-3.0-or-later
package com.baba.callvault.dialer

import android.telecom.Call
import org.junit.Assert.*
import org.junit.Test

class CallSelectionTest {
    private fun call(id: Int, state: Int) = PhoneCall(id, "555000$id", null, state, 0, null, true, false, emptyList())
    @Test fun waiting_incoming_call_takes_priority_over_the_previously_selected_call() {
        val active = call(1, Call.STATE_ACTIVE)
        val incoming = call(2, Call.STATE_RINGING)
        assertEquals(incoming, CallSelection.choose(listOf(active, incoming), 1))
    }
    @Test fun stale_selection_does_not_keep_a_disconnected_call_on_screen() {
        val ended = call(1, Call.STATE_DISCONNECTED)
        val active = call(2, Call.STATE_ACTIVE)
        assertEquals(active, CallSelection.choose(listOf(ended, active), 1))
        assertNull(CallSelection.choose(emptyList(), 1))
    }
    @Test fun user_can_return_to_a_held_call_when_nothing_is_ringing() {
        val active = call(1, Call.STATE_ACTIVE)
        val held = call(2, Call.STATE_HOLDING)
        assertEquals(held, CallSelection.choose(listOf(active, held), 2))
    }
    @Test fun resume_stays_available_when_telecom_clears_hold_capability_while_held() {
        assertTrue(CallCapabilities.mayHold(Call.STATE_HOLDING, Call.Details.CAPABILITY_SUPPORT_HOLD))
        assertFalse(CallCapabilities.mayHold(Call.STATE_ACTIVE, Call.Details.CAPABILITY_SUPPORT_HOLD))
    }
    @Test fun unsupported_hold_is_not_offered() {
        assertFalse(CallCapabilities.mayHold(Call.STATE_ACTIVE, 0))
        assertTrue(CallCapabilities.mayHold(Call.STATE_ACTIVE, Call.Details.CAPABILITY_HOLD))
    }
}
