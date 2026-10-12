// SPDX-License-Identifier: GPL-3.0-or-later
package com.baba.callvault.dialer

import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.PhoneAccountHandle
import com.baba.callvault.data.recordings.CallSimLookup
import com.baba.callvault.data.recordings.CallSimSnapshot
import com.baba.callvault.data.recordings.RecordingDirection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

data class PhoneCall(val id: Int, val number: String, val name: String?, val state: Int,
    val connectedAt: Long, val simLabel: String?, val holdAllowed: Boolean, val mergeAllowed: Boolean,
    val accounts: List<PhoneSim>)
data class PhoneAudio(val muted: Boolean = false, val route: Int = CallAudioState.ROUTE_EARPIECE,
    val supported: Int = CallAudioState.ROUTE_EARPIECE or CallAudioState.ROUTE_SPEAKER, val endpointId: String? = null)

object CallCapabilities {
    fun mayHold(state: Int, capabilities: Int): Boolean =
        capabilities and Call.Details.CAPABILITY_HOLD != 0 ||
            (state == Call.STATE_HOLDING && capabilities and Call.Details.CAPABILITY_SUPPORT_HOLD != 0)
}

object CallSelection {
    fun choose(calls: List<PhoneCall>, selectedId: Int?): PhoneCall? =
        calls.firstOrNull { it.state == Call.STATE_RINGING }
            ?: calls.firstOrNull { it.id == selectedId && it.state != Call.STATE_DISCONNECTED }
            ?: calls.firstOrNull { it.state == Call.STATE_SELECT_PHONE_ACCOUNT }
            ?: calls.firstOrNull { it.state != Call.STATE_DISCONNECTED }
}

/** The system supplies Call objects. Each callback/action is scoped to one live call identity. */
object DialerCalls {
    private val live = linkedMapOf<Int, Call>()
    private val callbacks = mutableMapOf<Int, Call.Callback>()
    private val names = mutableMapOf<Int, String>()
    private var sequence = 0
    private val mutable = MutableStateFlow<List<PhoneCall>>(emptyList())
    val calls = mutable.asStateFlow()
    private val mutableAudio = MutableStateFlow(PhoneAudio())
    val audio = mutableAudio.asStateFlow()
    internal var service: LocalInCallService? = null
    private data class SimRecord(val number: String, val incoming: Boolean, val from: Long,
        val until: Long, val snapshot: CallSimSnapshot)
    private var recentSim: List<SimRecord> = emptyList()

    internal fun add(call: Call) {
        val id = ++sequence
        live[id] = call
        val callback = object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) { publish() }
            override fun onDetailsChanged(call: Call, details: Call.Details) { publish() }
            override fun onConferenceableCallsChanged(call: Call, conferenceableCalls: MutableList<Call>) { publish() }
            override fun onChildrenChanged(call: Call, children: MutableList<Call>) { publish() }
        }
        callbacks[id] = callback
        call.registerCallback(callback, Handler(Looper.getMainLooper()))
        publish()
        val number = call.details.handle?.schemeSpecificPart.orEmpty()
        service?.let { owner -> owner.scope.launch {
            val name = withContext(Dispatchers.IO) { runCatching { PhoneRepository(owner).callerName(number) }.getOrNull() }
            if (live[id] === call && !name.isNullOrBlank()) { names[id] = name; publish() }
        } }
    }
    internal fun remove(call: Call) {
        val id = live.entries.firstOrNull { it.value === call }?.key ?: return
        callbacks.remove(id)?.let(call::unregisterCallback)
        rememberSim(call, System.currentTimeMillis())
        live.remove(id)
        names.remove(id)
        publish()
    }
    internal fun reset() {
        live.values.forEach { rememberSim(it, System.currentTimeMillis()) }
        live.forEach { (id, call) -> callbacks[id]?.let(call::unregisterCallback) }
        live.clear(); callbacks.clear(); names.clear(); mutable.value = emptyList(); mutableAudio.value = PhoneAudio()
        service = null
    }
    internal fun audio(state: CallAudioState, endpointId: String? = mutableAudio.value.endpointId) {
        mutableAudio.value = PhoneAudio(state.isMuted, state.route, state.supportedRouteMask, endpointId)
    }
    @Suppress("DEPRECATION")
    internal fun publish() {
        val service = service ?: return
        mutable.value = live.map { (id, call) ->
            val details = call.details
            val handle = details.accountHandle
            val account = handle?.let { runCatching { service.getSystemService(android.telecom.TelecomManager::class.java).getPhoneAccount(it) }.getOrNull() }
            val choices = details.intentExtras?.getParcelableArrayList<PhoneAccountHandle>(Call.AVAILABLE_PHONE_ACCOUNTS).orEmpty()
            if (call.state != Call.STATE_DISCONNECTED) rememberSim(call, Long.MAX_VALUE)
            PhoneCall(id, details.handle?.schemeSpecificPart.orEmpty(), names[id] ?: details.callerDisplayName,
                call.state, details.connectTimeMillis, account?.label?.toString(),
                CallCapabilities.mayHold(call.state, details.callCapabilities),
                details.can(Call.Details.CAPABILITY_MERGE_CONFERENCE) || call.conferenceableCalls.isNotEmpty(),
                choices.map { h -> PhoneSim(h, CallSimLookup.accountKey(h.componentName.flattenToString(), h.id),
                    runCatching { service.getSystemService(android.telecom.TelecomManager::class.java).getPhoneAccount(h)?.label?.toString() }.getOrNull().orEmpty()) })
        }
        service.updateNotification()
    }
    private fun rememberSim(call: Call, until: Long) {
        val context = service ?: return
        val d = call.details
        if (d.handle?.scheme != "tel" || d.accountHandle == null) return
        val handle = d.accountHandle
        val snapshot = CallSimLookup.fromHandle(context, handle) ?: return
        val from = d.creationTimeMillis
        val now = System.currentTimeMillis()
        synchronized(this) {
            recentSim = recentSim.filter { it.until > now - 180_000 && !(it.from == from && it.snapshot.accountKey == snapshot.accountKey) } +
                SimRecord(PhoneSearch.number(d.handle.schemeSpecificPart), d.callDirection == Call.Details.DIRECTION_INCOMING, from, until, snapshot)
        }
    }
    /** Only an unambiguous live/recent Telecom account can bypass the after-call log lookup. */
    fun simForRecording(request: CallSimLookup.Request): CallSimSnapshot? = synchronized(this) {
        val number = PhoneSearch.number(request.number.orEmpty())
        if (number.isBlank()) return@synchronized null
        val matches = recentSim.filter {
            it.number == number && it.incoming == (request.direction == RecordingDirection.INCOMING) &&
                request.startedAtMs >= it.from - 5_000 && request.startedAtMs <= it.until &&
                it.from <= request.endedAtMs + 5_000
        }
        if (matches.map { it.snapshot.accountKey }.distinct().size == 1) matches.maxByOrNull { it.from }?.snapshot else null
    }
    fun answer(id: Int) { live[id]?.takeIf { it.state == Call.STATE_RINGING }?.answer(android.telecom.VideoProfile.STATE_AUDIO_ONLY) }
    @Suppress("DEPRECATION")
    fun reject(id: Int) { live[id]?.takeIf { it.state == Call.STATE_RINGING }?.reject(false, null) }
    fun end(id: Int) { live[id]?.disconnect() }
    fun hold(id: Int) { live[id]?.let { if (it.state == Call.STATE_HOLDING) it.unhold() else if (it.details.can(Call.Details.CAPABILITY_HOLD)) it.hold() } }
    fun merge(id: Int) { live[id]?.let { c ->
        if (c.details.can(Call.Details.CAPABILITY_MERGE_CONFERENCE)) c.mergeConference()
        else c.conferenceableCalls.firstOrNull()?.let(c::conference)
    } }
    fun selectAccount(id: Int, sim: PhoneSim) { live[id]?.takeIf { it.state == Call.STATE_SELECT_PHONE_ACCOUNT }?.phoneAccountSelected(sim.handle, false) }
    fun mute() { service?.setMuted(!mutableAudio.value.muted) }
    fun route(type: Int, endpointId: String? = null) { service?.routeTo(type, endpointId) }
    fun tone(id: Int, digit: Char) { live[id]?.takeIf { it.state == Call.STATE_ACTIVE }?.playDtmfTone(digit) }
    fun stopTone(id: Int) { live[id]?.stopDtmfTone() }
}
