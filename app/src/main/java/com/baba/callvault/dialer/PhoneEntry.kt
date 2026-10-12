// SPDX-License-Identifier: GPL-3.0-or-later
package com.baba.callvault.dialer

import android.content.Intent
import com.baba.callvault.ui.navigation.NotificationDestination
import com.baba.callvault.ui.navigation.OpenRecordingRequest
import com.baba.callvault.ui.navigation.OpenTranscriptRequest

object PhoneEntry {
    const val LIBRARY = "local.assistant.openLibrary"
    fun wantsPhone(intent: Intent?): Boolean = when {
        intent?.action == Intent.ACTION_DIAL -> true
        intent?.getBooleanExtra(LIBRARY, false) == true -> false
        intent?.hasExtra(NotificationDestination.EXTRA) == true -> false
        intent?.hasExtra(OpenRecordingRequest.EXTRA) == true -> false
        intent?.hasExtra(OpenTranscriptRequest.EXTRA) == true -> false
        else -> true
    }
    fun number(intent: Intent?): String =
        if (intent?.action == Intent.ACTION_DIAL && intent.data?.scheme == "tel")
            PhoneSearch.dialable(intent.data?.schemeSpecificPart.orEmpty()).take(100) else ""
}
