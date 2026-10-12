// SPDX-License-Identifier: GPL-3.0-or-later
package com.baba.callvault.dialer

import android.app.Application
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.provider.ContactsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PhoneState(val data: PhoneData = PhoneData(emptyList(), emptyList(), emptyList(), false, false),
    val loading: Boolean = true, val isDefault: Boolean = false, val failed: Boolean = false)

class PhoneViewModel(application: Application) : AndroidViewModel(application) {
    val repository = PhoneRepository(application)
    private val mutable = MutableStateFlow(PhoneState())
    val state = mutable.asStateFlow()
    private var refreshJob: Job? = null
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { refresh() }
    }
    init {
        // Registration grants no read access. Reads still check each permission, including on resume.
        runCatching {
            application.contentResolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, observer)
            application.contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, observer)
        }
        refresh()
    }
    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            try {
                val data = withContext(Dispatchers.IO) { repository.load() }
                mutable.value = PhoneState(data, false, repository.isDefault(), false)
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) {
                // Do not retain phone data on screen when a provider rejects access after revocation.
                mutable.value = PhoneState(loading = false, isDefault = repository.isDefault(), failed = true)
            }
        }
    }
    fun favorite(number: String) { repository.toggleFavorite(number); refresh() }
    override fun onCleared() { getApplication<Application>().contentResolver.unregisterContentObserver(observer) }
}
