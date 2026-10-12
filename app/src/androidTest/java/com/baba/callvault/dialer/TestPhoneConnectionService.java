// SPDX-License-Identifier: GPL-3.0-or-later
package com.baba.callvault.dialer;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.telecom.Connection;
import android.telecom.ConnectionRequest;
import android.telecom.ConnectionService;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;

/** Framework-only Java: the fixture APK runs in its own process without the target's Kotlin runtime. */
public class TestPhoneConnectionService extends ConnectionService {
    @Override public Connection onCreateIncomingConnection(PhoneAccountHandle account, ConnectionRequest request) {
        return fixture(true, request);
    }
    @Override public Connection onCreateOutgoingConnection(PhoneAccountHandle account, ConnectionRequest request) {
        return fixture(false, request);
    }
    private Connection fixture(boolean incoming, ConnectionRequest request) {
        Connection connection = new Connection() {
            @Override public void onAnswer() { setActive(); }
            @Override public void onAnswer(int videoState) { setActive(); }
            @Override public void onReject() { finishCall(DisconnectCause.REJECTED); }
            @Override public void onDisconnect() { finishCall(DisconnectCause.LOCAL); }
            @Override public void onHold() { setConnectionCapabilities(getConnectionCapabilities() & ~CAPABILITY_HOLD); setOnHold(); }
            @Override public void onUnhold() { setConnectionCapabilities(getConnectionCapabilities() | CAPABILITY_HOLD); setActive(); }
            private void finishCall(int reason) { setDisconnected(new DisconnectCause(reason)); destroy(); }
        };
        connection.setAddress(request.getAddress() != null ? request.getAddress() : Uri.parse("tel:5550100100"),
                TelecomManager.PRESENTATION_ALLOWED);
        connection.setCallerDisplayName("Test Caller", TelecomManager.PRESENTATION_ALLOWED);
        connection.setConnectionCapabilities(Connection.CAPABILITY_HOLD | Connection.CAPABILITY_SUPPORT_HOLD | Connection.CAPABILITY_MUTE);
        connection.setAudioModeIsVoip(true);
        if (incoming) connection.setRinging();
        else { connection.setDialing(); new Handler(Looper.getMainLooper()).postDelayed(connection::setActive, 300); }
        return connection;
    }
}
