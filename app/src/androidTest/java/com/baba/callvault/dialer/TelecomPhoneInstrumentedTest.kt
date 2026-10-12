// SPDX-License-Identifier: GPL-3.0-or-later
package com.baba.callvault.dialer

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.telecom.Call
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.baba.callvault.MainActivity
import com.baba.callvault.R
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import android.content.res.Configuration
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class TelecomPhoneInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val fixtureContext = instrumentation.context
    // AppCompat applies app locales to Activity resources; the instrumentation context can stay English.
    private val strings = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
        setLocale(Locale.forLanguageTag("ar"))
    })
    private val device = UiDevice.getInstance(instrumentation)
    private val telecom = context.getSystemService(TelecomManager::class.java)
    private val component = ComponentName(fixtureContext, TestPhoneConnectionService::class.java)
    private val accounts = listOf("local-fixture-a", "local-fixture-b").map { PhoneAccountHandle(component, it) }
    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use { fd ->
        android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText() }
    }
    @Before fun setup() {
        assumeTrue("Synthetic Telecom tests run only on the emulator", Build.HARDWARE in listOf("ranchu", "goldfish"))
        assertEquals(context.packageName, context.getSystemService(TelecomManager::class.java).defaultDialerPackage)
        context.startActivity(Intent().setComponent(ComponentName(fixtureContext, TestPhoneHarnessActivity::class.java))
            .setAction("register").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue(waitFor { accounts.all { telecom.getPhoneAccount(it) != null } })
        accounts.forEachIndexed { index, handle ->
            shell("cmd telecom set-phone-account-enabled " + component.flattenToString() + " " + handle.id + " 0")
            assertTrue(telecom.getPhoneAccount(handle).isEnabled)
        }
        device.wakeUp()
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        screenshot("phone-setup")
        assertTrue(device.wait(Until.hasObject(By.text(strings.getString(R.string.phone_title))), 10_000))
    }
    @After fun cleanup() {
        instrumentation.runOnMainSync { DialerCalls.calls.value.forEach { DialerCalls.end(it.id) } }
        waitFor(5_000) { DialerCalls.calls.value.isEmpty() }
        context.startActivity(Intent().setComponent(ComponentName(fixtureContext, TestPhoneHarnessActivity::class.java))
            .setAction("cleanup").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    private fun waitFor(timeout: Long = 10_000, predicate: () -> Boolean): Boolean {
        val end = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < end) { if (predicate()) return true; SystemClock.sleep(100) }
        return predicate()
    }
    private fun clickDescription(id: Int) {
        val obj = device.wait(Until.findObject(By.desc(strings.getString(id))), 10_000)
        assertNotNull("Missing control: " + strings.getString(id), obj)
        obj.click()
    }
    private fun incoming(account: PhoneAccountHandle) {
        context.startActivity(Intent().setComponent(ComponentName(fixtureContext, TestPhoneHarnessActivity::class.java))
            .setAction("incoming").putExtra("account", account.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue("Telecom did not bind the in-call service", waitFor { DialerCalls.calls.value.any { it.state == Call.STATE_RINGING } })
        instrumentation.runOnMainSync { DialerCalls.service!!.openCall(false) }
        assertTrue(device.wait(Until.hasObject(By.desc(strings.getString(R.string.phone_answer))), 10_000))
    }
    private fun screenshot(name: String) {
        val dir = File(context.getExternalFilesDir(null), "phone-test").apply { mkdirs() }
        assertTrue(device.takeScreenshot(File(dir, name + ".png")))
    }
    @Test fun incoming_call_can_be_answered_muted_held_resumed_and_ended() {
        screenshot("phone-main-ar")
        incoming(accounts[0])
        screenshot("phone-incoming-ar")
        assertEquals("Test SIM A", DialerCalls.calls.value.single().simLabel)
        clickDescription(R.string.phone_answer)
        assertTrue(waitFor { DialerCalls.calls.value.singleOrNull()?.state == Call.STATE_ACTIVE })
        assertTrue(device.wait(Until.hasObject(By.desc(strings.getString(R.string.phone_mute))), 10_000))
        screenshot("phone-active-ar")
        clickDescription(R.string.phone_mute)
        assertTrue(waitFor { DialerCalls.audio.value.muted })
        clickDescription(R.string.phone_hold)
        assertTrue(waitFor { DialerCalls.calls.value.singleOrNull()?.state == Call.STATE_HOLDING })
        clickDescription(R.string.phone_resume)
        assertTrue(waitFor { DialerCalls.calls.value.singleOrNull()?.state == Call.STATE_ACTIVE })
        clickDescription(R.string.phone_end)
        assertTrue(waitFor { DialerCalls.calls.value.isEmpty() })
    }
    @Test fun incoming_call_on_second_account_can_be_declined() {
        incoming(accounts[1])
        assertEquals("Test SIM B", DialerCalls.calls.value.single().simLabel)
        clickDescription(R.string.phone_reject)
        assertTrue(waitFor { DialerCalls.calls.value.isEmpty() })
    }
    @Test fun outgoing_call_uses_the_account_chosen_in_the_phone_ui() {
        context.startActivity(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_DIAL)
            .setData(Uri.parse("tel:5550100101")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        val button = device.wait(Until.findObject(By.text(strings.getString(R.string.phone_call))), 10_000)
        assertNotNull(button); button.click()
        val sim = device.wait(Until.findObject(By.text("Test SIM B")), 10_000)
        assertNotNull(sim); sim.click()
        val confirm = device.wait(Until.findObject(By.text(strings.getString(R.string.phone_call))), 10_000)
        assertNotNull(confirm); confirm.click()
        assertTrue(waitFor { DialerCalls.calls.value.singleOrNull()?.state == Call.STATE_ACTIVE })
        assertEquals("Test SIM B", DialerCalls.calls.value.single().simLabel)
        clickDescription(R.string.phone_end)
        assertTrue(waitFor { DialerCalls.calls.value.isEmpty() })
    }
}
