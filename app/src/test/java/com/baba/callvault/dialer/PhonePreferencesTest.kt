// SPDX-License-Identifier: GPL-3.0-or-later
package com.baba.callvault.dialer

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PhonePreferencesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    @Test fun favorite_survives_reload_and_toggle_uses_normalized_digits() {
        val repository = PhoneRepository(context)
        repository.toggleFavorite("٥٥٥-٠١٠٠")
        assertEquals(setOf("5550100"), PhoneRepository(context).favorites())
        repository.toggleFavorite("555 0100")
        assertTrue(repository.favorites().isEmpty())
    }
    @Test fun preferred_sim_is_scoped_to_the_number_and_never_assumed_for_another_number() {
        val repository = PhoneRepository(context)
        repository.rememberSim("555-0100", "opaque-account-hash")
        assertEquals("opaque-account-hash", PhoneRepository(context).preferredSim("٥٥٥٠١٠٠"))
        assertNull(repository.preferredSim("5550101"))
    }
}
