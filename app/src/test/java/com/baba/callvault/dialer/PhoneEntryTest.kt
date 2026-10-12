// SPDX-License-Identifier: GPL-3.0-or-later
package com.baba.callvault.dialer

import android.app.Application
import android.content.Intent
import android.net.Uri
import com.baba.callvault.ui.navigation.NotificationDestination
import com.baba.callvault.ui.navigation.OpenRecordingRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PhoneEntryTest {
    @Test fun launcher_opens_the_phone_but_recording_notifications_keep_their_destination() {
        assertTrue(PhoneEntry.wantsPhone(Intent(Intent.ACTION_MAIN)))
        assertFalse(PhoneEntry.wantsPhone(Intent().putExtra(NotificationDestination.EXTRA, "status")))
        assertFalse(PhoneEntry.wantsPhone(Intent().putExtra(OpenRecordingRequest.EXTRA, "recording.ogg")))
        assertFalse(PhoneEntry.wantsPhone(Intent().putExtra(PhoneEntry.LIBRARY, true)))
    }
    @Test fun dial_intent_only_prefills_a_tel_number() {
        val dial = Intent(Intent.ACTION_DIAL, Uri.parse("tel:+966501234567"))
        assertTrue(PhoneEntry.wantsPhone(dial))
        assertEquals("+966501234567", PhoneEntry.number(dial))
        assertEquals("", PhoneEntry.number(Intent(Intent.ACTION_DIAL, Uri.parse("https://example.com/123"))))
        assertEquals("", PhoneEntry.number(Intent(Intent.ACTION_VIEW, Uri.parse("tel:555"))))
    }
}
