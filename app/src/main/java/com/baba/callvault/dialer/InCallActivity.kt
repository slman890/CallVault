// SPDX-License-Identifier: GPL-3.0-or-later
package com.baba.callvault.dialer

import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.telecom.CallAudioState
import android.telecom.Call
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.baba.callvault.MainActivity
import com.baba.callvault.R
import com.baba.callvault.system.AppLock
import com.baba.callvault.ui.theme.CallVaultTheme
import kotlinx.coroutines.delay

/** Only Telecom-bound calls are visible here. No history, transcript, or app-lock bypass. */
class InCallActivity : AppCompatActivity() {
    private var requestedDialpad by mutableStateOf(false)
    private var proximity: PowerManager.WakeLock? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (AppLock.isEnabled(this)) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        val power = getSystemService(PowerManager::class.java)
        if (power.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            proximity = power.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "LocalCallAssistant:proximity")
                .apply { setReferenceCounted(false) }
        }
        requestedDialpad = intent.getBooleanExtra("dialpad", false)
        setContent { CallVaultTheme {
            val calls by DialerCalls.calls.collectAsState()
            val audio by DialerCalls.audio.collectAsState()
            LaunchedEffect(calls, audio.route) {
                val shouldHold = audio.route == CallAudioState.ROUTE_EARPIECE && calls.any { it.state == Call.STATE_ACTIVE }
                if (shouldHold) proximity?.acquire(60 * 60 * 1000L)
                else releaseProximity()
            }
            InCallScreen(requestedDialpad, ::finish) {
                startActivity(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_DIAL)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            }
        } }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedDialpad = intent.getBooleanExtra("dialpad", false)
    }
    private fun releaseProximity() {
        proximity?.takeIf { it.isHeld }?.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY)
    }
    override fun onDestroy() { releaseProximity(); super.onDestroy() }
}

@Composable
internal fun InCallScreen(requestedDialpad: Boolean, onEmpty: () -> Unit, onAddCall: () -> Unit) {
    val calls by DialerCalls.calls.collectAsState()
    val audio by DialerCalls.audio.collectAsState()
    val devices by LocalInCallService.devices.collectAsState()
    var selected by rememberSaveable { mutableStateOf<Int?>(null) }
    var pad by rememberSaveable { mutableStateOf(false) }
    var route by remember { mutableStateOf(false) }
    var digits by rememberSaveable { mutableStateOf("") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val call = CallSelection.choose(calls, selected)
    LaunchedEffect(requestedDialpad) { if (requestedDialpad) pad = true }
    LaunchedEffect(calls.isEmpty()) { if (calls.isEmpty()) { delay(500); onEmpty() } }
    LaunchedEffect(call?.connectedAt) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val colors = MaterialTheme.colorScheme
    val header: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (!pad) Surface(shape = CircleShape, color = colors.primary.copy(alpha = .15f), modifier = Modifier.size(80.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Person, null, Modifier.size(52.dp), tint = colors.primary) }
            }
            Text(call?.name?.takeIf { it.isNotBlank() } ?: call?.number?.ifBlank { stringResource(R.string.phone_unknown) }
                ?: stringResource(R.string.phone_ended), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            if (!pad && !call?.name.isNullOrBlank()) Text(call?.number.orEmpty(),
                style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Ltr))
            Text(stringResource(when(call?.state) {
                Call.STATE_RINGING -> R.string.phone_incoming; Call.STATE_DIALING, Call.STATE_CONNECTING -> R.string.phone_connecting
                Call.STATE_HOLDING -> R.string.phone_held; Call.STATE_ACTIVE -> R.string.phone_ongoing
                Call.STATE_SELECT_PHONE_ACCOUNT -> R.string.phone_choose_sim
                else -> R.string.phone_ended
            }), style = MaterialTheme.typography.titleMedium, color = colors.primary)
            if (!pad) call?.simLabel?.let { label -> SuggestionChip(onClick = {}, icon = { Icon(Icons.Default.SimCard, null) }, label = { Text(label) }) }
            if (call != null && call.connectedAt > 0 && call.state in listOf(Call.STATE_ACTIVE, Call.STATE_HOLDING)) {
                Text(android.text.format.DateUtils.formatElapsedTime(((now - call.connectedAt) / 1000).coerceAtLeast(0)),
                    style = MaterialTheme.typography.headlineSmall.copy(textDirection = TextDirection.Ltr))
            }
        }
    }
    val controls: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (call != null) {
                when (call.state) {
                    Call.STATE_SELECT_PHONE_ACCOUNT -> call.accounts.forEach { sim ->
                        Button(onClick = { DialerCalls.selectAccount(call.id, sim) }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.SimCard, null); Spacer(Modifier.width(8.dp)); Text(sim.label)
                        }
                    }
                    Call.STATE_RINGING -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        CallControl(Icons.Default.CallEnd, R.string.phone_reject, Color(0xFFB3261E)) { DialerCalls.reject(call.id) }
                        CallControl(Icons.Default.Call, R.string.phone_answer, Color(0xFF13754D)) { DialerCalls.answer(call.id) }
                    }
                    else -> {
                        if (pad) {
                            Text(digits, style = MaterialTheme.typography.titleLarge.copy(textDirection = TextDirection.Ltr))
                            NumberPad(onDigit = {}, toneMode = true, toneCallId = call.id, keyHeight = 44.dp,
                                onTone = { digit -> digits += digit; DialerCalls.tone(call.id, digit) },
                                onToneStop = { DialerCalls.stopTone(call.id) })
                            TextButton(onClick = { pad = false }) { Text(stringResource(R.string.phone_hide_pad)) }
                        } else {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                SmallCallControl(if (audio.muted) Icons.Default.MicOff else Icons.Default.Mic, R.string.phone_mute,
                                    selected = audio.muted) { DialerCalls.mute() }
                                SmallCallControl(Icons.Default.Dialpad, R.string.phone_dialpad, enabled = call.state == Call.STATE_ACTIVE) { pad = true }
                                SmallCallControl(Icons.Default.VolumeUp, R.string.phone_audio) { route = true }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                SmallCallControl(Icons.Default.Pause, if (call.state == Call.STATE_HOLDING) R.string.phone_resume else R.string.phone_hold,
                                    enabled = call.holdAllowed, selected = call.state == Call.STATE_HOLDING) { DialerCalls.hold(call.id) }
                                SmallCallControl(Icons.Default.AddIcCall, R.string.phone_add_call, enabled = call.state == Call.STATE_ACTIVE) { onAddCall() }
                                SmallCallControl(Icons.Default.CallMerge, R.string.phone_merge, enabled = call.mergeAllowed) { DialerCalls.merge(call.id) }
                            }
                        }
                        CallControl(Icons.Default.CallEnd, R.string.phone_end, Color(0xFFB3261E)) { DialerCalls.end(call.id) }
                    }
                }
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(colors.primaryContainer, colors.background)))) {
        val wide = maxWidth > maxHeight
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (calls.size > 1) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(calls, key = { it.id }) { item -> FilterChip(selected = call?.id == item.id,
                    onClick = { selected = item.id }, label = { Text(item.name ?: item.number.takeLast(8)) }) }
            }
            if (wide) Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally) { header() }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally) { controls() }
            } else Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
                header(); controls()
            }
        }
    }
    if (route) AlertDialog(onDismissRequest = { route = false }, title = { Text(stringResource(R.string.phone_audio)) },
        text = { Column {
            devices.forEach { device -> TextButton(onClick = { route = false; DialerCalls.route(device.route, device.endpointId) }) {
                RadioButton(selected = if (device.endpointId != null) device.endpointId == audio.endpointId else device.route == audio.route,
                    onClick = null); Spacer(Modifier.width(8.dp)); Text(device.label)
            } }
            if (devices.isEmpty()) Text(stringResource(R.string.phone_audio_unavailable))
        } }, confirmButton = { TextButton(onClick = { route = false }) { Text(stringResource(R.string.phone_close)) } })
}

@Composable
private fun CallControl(icon: ImageVector, label: Int, color: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledIconButton(onClick = onClick, modifier = Modifier.size(72.dp),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = color, contentColor = Color.White)) {
            Icon(icon, stringResource(label), Modifier.size(30.dp))
        }
        Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun SmallCallControl(icon: ImageVector, label: Int, enabled: Boolean = true, selected: Boolean = false, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(86.dp)) {
        FilledTonalIconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(54.dp),
            colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor =
                if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Icon(icon, stringResource(label))
        }
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}
