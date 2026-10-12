// SPDX-License-Identifier: GPL-3.0-or-later
package com.baba.callvault.dialer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.OutcomeReceiver
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.CallEndpoint
import android.telecom.CallEndpointException
import android.telecom.InCallService
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import com.baba.callvault.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import androidx.annotation.RequiresApi

data class PhoneAudioDevice(val route: Int, val label: String, val endpointId: String? = null)

class LocalInCallService : InCallService() {
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var endpoints = emptyList<CallEndpoint>()
    companion object {
        private const val CHANNEL = "phone_calls"
        private const val NOTIFICATION = 8601
        private val devicesState = MutableStateFlow<List<PhoneAudioDevice>>(emptyList())
        val devices = devicesState.asStateFlow()
    }
    override fun onCreate() {
        super.onCreate()
        DialerCalls.service = this
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.phone_call_notifications), NotificationManager.IMPORTANCE_HIGH).apply {
                // IN_CALL_SERVICE_RINGING=false delegates ringing to Telecom. Avoid a second ringtone.
                setSound(null, null)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            })
    }
    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        DialerCalls.add(call)
        if (call.details.callDirection == Call.Details.DIRECTION_OUTGOING) openCall(false)
    }
    override fun onCallRemoved(call: Call) { super.onCallRemoved(call); DialerCalls.remove(call) }
    override fun onBringToForeground(showDialpad: Boolean) { openCall(showDialpad) }
    @Suppress("DEPRECATION")
    override fun onCallAudioStateChanged(audioState: CallAudioState) {
        DialerCalls.audio(audioState)
        if (Build.VERSION.SDK_INT < 34) devicesState.value = listOf(
            PhoneAudioDevice(CallAudioState.ROUTE_EARPIECE, getString(R.string.phone_earpiece)),
            PhoneAudioDevice(CallAudioState.ROUTE_SPEAKER, getString(R.string.phone_speaker)),
            PhoneAudioDevice(CallAudioState.ROUTE_BLUETOOTH, getString(R.string.phone_bluetooth)),
            PhoneAudioDevice(CallAudioState.ROUTE_WIRED_HEADSET, getString(R.string.phone_headset)))
            .filter { audioState.supportedRouteMask and it.route != 0 }
    }
    @RequiresApi(34)
    override fun onAvailableCallEndpointsChanged(availableEndpoints: MutableList<CallEndpoint>) {
        endpoints = availableEndpoints.toList()
        devicesState.value = endpoints.map { PhoneAudioDevice(endpointRoute(it), it.endpointName.toString(), it.identifier.toString()) }
    }
    @RequiresApi(34)
    override fun onCallEndpointChanged(callEndpoint: CallEndpoint) {
        val old = DialerCalls.audio.value
        DialerCalls.audio(CallAudioState(old.muted, endpointRoute(callEndpoint), old.supported), callEndpoint.identifier.toString())
    }
    override fun onMuteStateChanged(isMuted: Boolean) {
        val old = DialerCalls.audio.value
        DialerCalls.audio(CallAudioState(isMuted, old.route, old.supported))
    }
    @RequiresApi(34)
    private fun endpointRoute(endpoint: CallEndpoint): Int = when (endpoint.endpointType) {
        CallEndpoint.TYPE_SPEAKER -> CallAudioState.ROUTE_SPEAKER
        CallEndpoint.TYPE_BLUETOOTH -> CallAudioState.ROUTE_BLUETOOTH
        CallEndpoint.TYPE_WIRED_HEADSET -> CallAudioState.ROUTE_WIRED_HEADSET
        CallEndpoint.TYPE_STREAMING -> CallAudioState.ROUTE_STREAMING
        else -> CallAudioState.ROUTE_EARPIECE
    }
    @Suppress("DEPRECATION")
    fun routeTo(route: Int, endpointId: String? = null) {
        if (Build.VERSION.SDK_INT >= 34) {
            val endpoint = endpoints.firstOrNull {
                if (endpointId != null) it.identifier.toString() == endpointId else endpointRoute(it) == route
            } ?: return
            requestCallEndpointChange(endpoint, mainExecutor, object : OutcomeReceiver<Void, CallEndpointException> {
                override fun onResult(result: Void?) {}
                override fun onError(error: CallEndpointException) {
                    Toast.makeText(this@LocalInCallService, R.string.phone_operation_failed, Toast.LENGTH_SHORT).show()
                }
            })
        } else setAudioRoute(route)
    }
    fun openCall(dialpad: Boolean) {
        runCatching {
            startActivity(Intent(this, InCallActivity::class.java).putExtra("dialpad", dialpad)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }.onFailure { updateNotification() }
    }
    private fun action(action: String, id: Int): PendingIntent = PendingIntent.getBroadcast(this,
        id * 10 + when (action) { "answer" -> 1; "reject" -> 2; else -> 3 },
        Intent(this, PhoneCallActionReceiver::class.java).setAction(action).putExtra("call", id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        val call = CallSelection.choose(DialerCalls.calls.value, null)
        if (call == null || call.state == Call.STATE_DISCONNECTED) { manager.cancel(NOTIFICATION); return }
        val incoming = call.state == Call.STATE_RINGING
        val open = PendingIntent.getActivity(this, 8602, Intent(this, InCallActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val name = call.name?.takeIf { it.isNotBlank() } ?: call.number.ifBlank { getString(R.string.phone_unknown) }
        val person = Person.Builder().setName(name).setImportant(true).build()
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_local_assistant_foreground)
            .setContentTitle(name)
            .setContentText(getString(if (incoming) R.string.phone_incoming else R.string.phone_ongoing))
            .setCategory(NotificationCompat.CATEGORY_CALL).setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setOngoing(true)
            .setContentIntent(open).setOnlyAlertOnce(true)
            .setStyle(if (incoming) NotificationCompat.CallStyle.forIncomingCall(person, action("reject", call.id), action("answer", call.id))
                else NotificationCompat.CallStyle.forOngoingCall(person, action("end", call.id)))
        if (incoming) builder.setFullScreenIntent(open, true)
        runCatching { manager.notify(NOTIFICATION, builder.build()) }
    }
    override fun onDestroy() {
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION)
        DialerCalls.reset()
        devicesState.value = emptyList()
        scope.cancel()
        super.onDestroy()
    }
}

/** Not exported; only immutable PendingIntents created by the call service can reach these actions. */
class PhoneCallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("call", -1)
        when (intent.action) {
            "answer" -> { DialerCalls.answer(id); DialerCalls.service?.openCall(false) }
            "reject" -> DialerCalls.reject(id)
            "end" -> DialerCalls.end(id)
        }
    }
}
