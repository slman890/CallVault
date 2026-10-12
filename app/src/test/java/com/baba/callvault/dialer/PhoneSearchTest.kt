// SPDX-License-Identifier: GPL-3.0-or-later
package com.baba.callvault.dialer

import org.junit.Assert.*
import org.junit.Test

class PhoneSearchTest {
    @Test fun converts_arabic_digits_without_reversing_the_number() {
        assertEquals("+966501234567", PhoneSearch.dialable("+٩٦٦ ٥٠١-٢٣٤-٥٦٧"))
        assertEquals("966501234567", PhoneSearch.number("00966 (501) 234 567"))
    }
    @Test fun preserves_dialing_control_characters() {
        assertEquals("*123#;5,6", PhoneSearch.dialable("*123#;5,6"))
    }
    @Test fun text_query_does_not_match_every_contact_with_a_phone_number() {
        assertFalse(PhoneSearch.matches(PhoneContact(1, "سارة", "0501000000", false), "خالد"))
        assertTrue(PhoneSearch.matches(PhoneContact(1, "سارة", "0501000000", false), "سار"))
    }
    @Test fun t9_can_find_latin_names_and_numeric_substrings() {
        val contact = PhoneContact(2, "Sarah", "0501234567", false)
        assertTrue(PhoneSearch.matches(contact, "727"))
        assertTrue(PhoneSearch.matches(contact, "١٢٣٤"))
        assertFalse(PhoneSearch.matches(contact, "99999"))
    }
}
