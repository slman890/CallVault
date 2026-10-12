// SPDX-License-Identifier: GPL-3.0-or-later
package com.baba.callvault.data.recordings

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.telecom.PhoneAccountHandle
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.baba.callvault.data.health.CallLogEntry
import com.baba.callvault.data.health.CallLogReader
import kotlinx.coroutines.delay
import java.security.MessageDigest

/** A snapshot at call completion. Raw account IDs, ICCIDs, IMSIs and SIM numbers are not stored. */
data class CallSimSnapshot(
    val accountKey: String,
    val subscriptionId: Int? = null,
    val slotIndex: Int? = null,
    val displayName: String? = null,
    val carrierName: String? = null,
)

object CallSimLookup {
    data class Request(val startedAtMs: Long, val endedAtMs: Long,
        val direction: RecordingDirection, val number: String?)

    /** Conservative matching: overlapping calls on different accounts must remain unknown. */
    internal fun matchingAccount(entries: List<CallLogEntry>, request: Request): CallLogEntry? {
        if (request.startedAtMs <= 0 || request.endedAtMs < request.startedAtMs) return null
        val expectedNumber = normalizedNumber(request.number)
        val matches = entries.filter { entry ->
            entry.isIncoming == (request.direction == RecordingDirection.INCOMING) &&
                entry.durationSeconds > 0 &&
                request.startedAtMs >= entry.startedAt - 5_000L &&
                request.startedAtMs <= entry.startedAt + entry.durationSeconds * 1000L + 120_000L &&
                entry.startedAt <= request.endedAtMs + 5_000L &&
                (expectedNumber == null || normalizedNumber(entry.phoneNumber) == expectedNumber) &&
                !entry.accountId.isNullOrBlank() && !entry.accountComponent.isNullOrBlank()
        }
        val accounts = matches.map { it.accountComponent to it.accountId }.distinct()
        return if (accounts.size == 1) matches.maxByOrNull { it.startedAt } else null
    }

    private fun normalizedNumber(number: String?): String? = number
        ?.filter { it in '0'..'9' }?.removePrefix("00")?.ifBlank { null }

    internal fun accountKey(component: String, accountId: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest((component + "\u0000" + accountId).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    suspend fun resolve(context: Context, request: Request): CallSimSnapshot? {
        com.baba.callvault.dialer.DialerCalls.simForRecording(request)?.let { return it }
        repeat(6) { attempt ->
            val entries = CallLogReader.entriesSince(context, request.startedAtMs - 180_000L, includeAccounts = true)
            val entry = matchingAccount(entries, request)
            if (entry != null) return snapshot(context, entry)
            if (attempt < 5) delay(400)
        }
        return null
    }

    private fun snapshot(context: Context, entry: CallLogEntry): CallSimSnapshot? {
        val component = ComponentName.unflattenFromString(entry.accountComponent.orEmpty()) ?: return null
        val rawId = entry.accountId?.takeIf { it.isNotBlank() } ?: return null
        return fromHandle(context, PhoneAccountHandle(component, rawId))
    }

    fun fromHandle(context: Context, handle: PhoneAccountHandle): CallSimSnapshot? {
        if (handle.id.isBlank()) return null
        val key = accountKey(handle.componentName.flattenToString(), handle.id)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED)
            return CallSimSnapshot(key)
        // The handle-based API is available from API 30 (our minSdk). Never parse account IDs as
        // subscription IDs and never substitute the default SIM: either would mislabel dual-SIM calls.
        return runCatching {
            val telephony = context.getSystemService(TelephonyManager::class.java)
            val subId = telephony?.getSubscriptionId(handle)
                ?.takeIf { it >= 0 } ?: return@runCatching CallSimSnapshot(key)
            val info = context.getSystemService(SubscriptionManager::class.java)?.getActiveSubscriptionInfo(subId)
            CallSimSnapshot(key, subId, info?.simSlotIndex?.takeIf { it >= 0 },
                info?.displayName?.toString()?.trim()?.take(80)?.ifBlank { null },
                info?.carrierName?.toString()?.trim()?.take(80)?.ifBlank { null })
        }.getOrDefault(CallSimSnapshot(key))
    }
}
