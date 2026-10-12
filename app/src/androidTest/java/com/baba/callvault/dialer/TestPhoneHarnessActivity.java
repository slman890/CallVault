// SPDX-License-Identifier: GPL-3.0-or-later
package com.baba.callvault.dialer;

import android.app.Activity;
import android.content.ComponentName;
import android.net.Uri;
import android.os.Bundle;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import java.util.Collections;

/** Synthetic call setup under the fixture's own UID. Included only in the instrumentation APK. */
public class TestPhoneHarnessActivity extends Activity {
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TelecomManager telecom = getSystemService(TelecomManager.class);
        ComponentName component = new ComponentName(this, TestPhoneConnectionService.class);
        PhoneAccountHandle a = new PhoneAccountHandle(component, "local-fixture-a");
        PhoneAccountHandle b = new PhoneAccountHandle(component, "local-fixture-b");
        String action = getIntent().getAction();
        if ("register".equals(action)) {
            telecom.registerPhoneAccount(PhoneAccount.builder(a, "Test SIM A").setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER)
                    .setSupportedUriSchemes(Collections.singletonList(PhoneAccount.SCHEME_TEL)).build());
            telecom.registerPhoneAccount(PhoneAccount.builder(b, "Test SIM B").setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER)
                    .setSupportedUriSchemes(Collections.singletonList(PhoneAccount.SCHEME_TEL)).build());
        } else if ("incoming".equals(action)) {
            PhoneAccountHandle account = "local-fixture-b".equals(getIntent().getStringExtra("account")) ? b : a;
            Bundle extras = new Bundle();
            extras.putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, Uri.parse("tel:5550100100"));
            telecom.addNewIncomingCall(account, extras);
        } else if ("cleanup".equals(action)) {
            telecom.unregisterPhoneAccount(a);
            telecom.unregisterPhoneAccount(b);
        }
        finish();
    }
}
