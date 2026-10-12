// SPDX-License-Identifier: GPL-3.0-or-later
package com.baba.callvault.dialer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.BlockedNumberContract
import android.provider.CallLog
import android.provider.ContactsContract
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import com.baba.callvault.data.recordings.CallSimLookup

data class PhoneContact(val id: Long, val name: String, val number: String, val favorite: Boolean)
data class PhoneHistory(val id: Long, val name: String?, val number: String, val date: Long,
    val type: Int, val duration: Long, val accountKey: String?)
data class PhoneSim(val handle: PhoneAccountHandle, val key: String, val label: String)
data class PhoneData(val contacts: List<PhoneContact>, val history: List<PhoneHistory>,
    val sims: List<PhoneSim>, val contactsAllowed: Boolean, val historyAllowed: Boolean)

/** All provider reads run on Dispatchers.IO. Numbers and favorites stay in this app's private data. */
class PhoneRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("local_phone", Context.MODE_PRIVATE)
    fun allowed(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    fun isDefault() = context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage == context.packageName
    fun favorites(): Set<String> = prefs.getStringSet("favorites", emptySet())!!.toSet()
    fun preferredSim(number: String): String? = prefs.getString("sim_" + PhoneSearch.number(number), null)
    fun rememberSim(number: String, key: String) { prefs.edit().putString("sim_" + PhoneSearch.number(number), key).apply() }
    fun toggleFavorite(number: String) {
        val key = PhoneSearch.number(number)
        if (key.isBlank()) return
        val favorites = favorites().toMutableSet()
        if (!favorites.add(key)) favorites.remove(key)
        prefs.edit().putStringSet("favorites", favorites).apply()
    }

    fun load(): PhoneData {
        val contactsAllowed = allowed(Manifest.permission.READ_CONTACTS)
        val historyAllowed = allowed(Manifest.permission.READ_CALL_LOG)
        val favorites = favorites()
        val contacts = mutableListOf<PhoneContact>()
        if (contactsAllowed) {
            val phone = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            context.contentResolver.query(phone, arrayOf(ContactsContract.CommonDataKinds.Phone._ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE LOCALIZED ASC")?.use { c ->
                while (c.moveToNext()) {
                    val number = c.getString(2) ?: continue
                    if (PhoneSearch.number(number).isBlank()) continue
                    contacts += PhoneContact(c.getLong(0), c.getString(1) ?: number, number,
                        PhoneSearch.number(number) in favorites)
                }
            }
        }
        val history = mutableListOf<PhoneHistory>()
        if (historyAllowed) context.contentResolver.query(CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls._ID, CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER, CallLog.Calls.DATE,
                CallLog.Calls.TYPE, CallLog.Calls.DURATION, CallLog.Calls.PHONE_ACCOUNT_COMPONENT_NAME, CallLog.Calls.PHONE_ACCOUNT_ID),
            null, null, CallLog.Calls.DATE + " DESC")?.use { c ->
            while (c.moveToNext() && history.size < 300) {
                val component = c.getString(6)
                val account = c.getString(7)
                history += PhoneHistory(c.getLong(0), c.getString(1), c.getString(2).orEmpty(),
                    c.getLong(3), c.getInt(4), c.getLong(5),
                    if (!component.isNullOrBlank() && !account.isNullOrBlank()) CallSimLookup.accountKey(component, account) else null)
            }
        }
        return PhoneData(contacts.distinctBy { it.name to PhoneSearch.number(it.number) }, history, sims(), contactsAllowed, historyAllowed)
    }

    fun sims(): List<PhoneSim> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val telecom = context.getSystemService(TelecomManager::class.java) ?: return emptyList()
        return telecom.callCapablePhoneAccounts.mapNotNull { handle ->
            val account = telecom.getPhoneAccount(handle) ?: return@mapNotNull null
            PhoneSim(handle, CallSimLookup.accountKey(handle.componentName.flattenToString(), handle.id),
                account.label?.toString()?.takeIf { it.isNotBlank() } ?: context.getString(com.baba.callvault.R.string.phone_sim))
        }
    }

    fun callerName(number: String): String? {
        if (!allowed(Manifest.permission.READ_CONTACTS) || number.isBlank()) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
            null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    }

    fun place(number: String, sim: PhoneSim?) {
        val dialable = PhoneSearch.dialable(number)
        require(dialable.isNotEmpty())
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED)
            throw SecurityException("Phone permission is required")
        val extras = Bundle()
        sim?.let { extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it.handle) }
        // Telecom chooses the emergency path and the system emergency UI; never intercept it ourselves.
        context.getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", dialable, null), extras)
    }

    fun blocked(): List<Pair<Long, String>> {
        if (!BlockedNumberContract.canCurrentUserBlockNumbers(context)) return emptyList()
        val result = mutableListOf<Pair<Long, String>>()
        context.contentResolver.query(BlockedNumberContract.BlockedNumbers.CONTENT_URI,
            arrayOf(BlockedNumberContract.BlockedNumbers.COLUMN_ID, BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER),
            null, null, null)?.use { c -> while (c.moveToNext()) result += c.getLong(0) to c.getString(1).orEmpty() }
        return result
    }
    fun block(number: String) {
        check(BlockedNumberContract.canCurrentUserBlockNumbers(context))
        val values = android.content.ContentValues().apply {
            put(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER, PhoneSearch.dialable(number))
        }
        context.contentResolver.insert(BlockedNumberContract.BlockedNumbers.CONTENT_URI, values)
    }
    fun unblock(id: Long) {
        check(BlockedNumberContract.canCurrentUserBlockNumbers(context))
        context.contentResolver.delete(android.content.ContentUris.withAppendedId(BlockedNumberContract.BlockedNumbers.CONTENT_URI, id), null, null)
    }
}

object PhoneSearch {
    fun dialable(value: String): String = buildString {
        for (c in value) {
            val digit = Character.digit(c, 10)
            if (digit >= 0) append(digit) else if (c in "+*#,;") append(c)
        }
    }
    fun number(value: String): String = dialable(value).filter { it in '0'..'9' }.removePrefix("00")
    private fun t9(value: String): String = buildString {
        for (c in value.uppercase(java.util.Locale.ROOT)) {
            val digit = when (c) {
                in 'A'..'C' -> '2'; in 'D'..'F' -> '3'; in 'G'..'I' -> '4'; in 'J'..'L' -> '5'
                in 'M'..'O' -> '6'; in 'P'..'S' -> '7'; in 'T'..'V' -> '8'; in 'W'..'Z' -> '9'
                else -> null
            }
            digit?.let { append(it) }
        }
    }
    fun matches(contact: PhoneContact, query: String): Boolean {
        if (query.isBlank()) return true
        if (contact.name.contains(query.trim(), ignoreCase = true)) return true
        val digits = number(query)
        // A text-only query must not match every number because the numeric query is empty.
        return digits.isNotEmpty() && (number(contact.number).contains(digits) || t9(contact.name).contains(digits))
    }
}
