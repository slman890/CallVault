// SPDX-License-Identifier: GPL-3.0-or-later
package com.baba.callvault.dialer

import android.Manifest
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.CallLog
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.baba.callvault.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneScreen(initialNumber: String = "", onOpenLibrary: () -> Unit, vm: PhoneViewModel = viewModel()) {
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    val liveCalls by DialerCalls.calls.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var number by rememberSaveable { mutableStateOf(PhoneSearch.dialable(initialNumber)) }
    var search by rememberSaveable { mutableStateOf("") }
    var pendingNumber by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedSim by rememberSaveable { mutableStateOf<String?>(null) }
    var rememberSim by rememberSaveable { mutableStateOf(false) }
    var blocked by remember { mutableStateOf<List<Pair<Long, String>>?>(null) }
    var blockNumber by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val error = stringResource(R.string.phone_operation_failed)
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.refresh() }
    val role = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.refresh() }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) vm.refresh() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(initialNumber) { if (initialNumber.isNotEmpty()) { number = PhoneSearch.dialable(initialNumber); tab = 0 } }
    fun requestPermissions() { permissions.launch(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_PHONE_STATE, Manifest.permission.CALL_PHONE) +
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()) }
    fun call(value: String) {
        if (PhoneSearch.dialable(value).isBlank()) return
        if (!vm.repository.allowed(Manifest.permission.CALL_PHONE)) { requestPermissions(); return }
        pendingNumber = value
        selectedSim = vm.repository.preferredSim(value)?.takeIf { key -> state.data.sims.any { it.key == key } }
            ?: state.data.sims.singleOrNull()?.key
        rememberSim = false
    }
    fun perform(action: () -> Unit) { scope.launch {
        try { withContext(Dispatchers.IO) { action() }; vm.refresh() }
        catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (_: Exception) { snackbar.showSnackbar(error) }
    } }
    val titles = listOf(R.string.phone_dialpad, R.string.phone_history, R.string.phone_contacts, R.string.phone_favorites)
    val icons = listOf(Icons.Default.Dialpad, Icons.Default.History, Icons.Default.Contacts, Icons.Default.Star)
    Scaffold(
        topBar = { TopAppBar(title = { Column {
            Text(stringResource(R.string.phone_title), fontWeight = FontWeight.Bold)
            Text(stringResource(if (state.isDefault) R.string.phone_default_active else R.string.phone_local),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        } }, actions = {
            IconButton(onClick = onOpenLibrary) { Icon(Icons.Default.GraphicEq, stringResource(R.string.phone_library)) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.phone_more)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.phone_permissions)) },
                        onClick = { menu = false; requestPermissions() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.phone_blocked)) }, onClick = {
                        menu = false
                        scope.launch {
                            try { blocked = withContext(Dispatchers.IO) { vm.repository.blocked() } }
                            catch (_: Exception) { snackbar.showSnackbar(error) }
                        }
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.phone_refresh)) }, onClick = { menu = false; vm.refresh() })
                }
            }
        }) },
        bottomBar = { NavigationBar {
            titles.forEachIndexed { index, title -> NavigationBarItem(selected = tab == index,
                onClick = { tab = index; search = "" }, icon = { Icon(icons[index], null) },
                label = { Text(stringResource(title), maxLines = 1) }) }
        } },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (!state.isDefault) {
                ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.phone_default_title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.phone_default_body), style = MaterialTheme.typography.bodySmall)
                        Button(onClick = {
                            val manager = context.getSystemService(RoleManager::class.java)
                            if (manager.isRoleAvailable(RoleManager.ROLE_DIALER))
                                role.launch(manager.createRequestRoleIntent(RoleManager.ROLE_DIALER))
                            else scope.launch { snackbar.showSnackbar(error) }
                        }) { Text(stringResource(R.string.phone_make_default)) }
                    }
                }
            }
            if (liveCalls.isNotEmpty()) {
                FilledTonalButton(onClick = { context.startActivity(Intent(context, InCallActivity::class.java)) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Icon(Icons.Default.Call, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.phone_return_call))
                }
            }
            if (state.isDefault && Build.VERSION.SDK_INT >= 34 &&
                !context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()) {
                TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                    Uri.parse("package:" + context.packageName))) }) { Text(stringResource(R.string.phone_fullscreen)) }
            }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.failed) TextButton(onClick = vm::refresh) { Text(stringResource(R.string.phone_load_failed)) }
            if (tab == 0) {
                DialingPanel(number, state.data.contacts, onNumber = { number = it }, onCall = ::call,
                    onFavorite = vm::favorite)
            } else {
                OutlinedTextField(value = search, onValueChange = { search = it }, modifier = Modifier.fillMaxWidth().padding(16.dp),
                    singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) },
                    placeholder = { Text(stringResource(R.string.phone_search)) })
                val needsPermission = if (tab == 1) !state.data.historyAllowed else !state.data.contactsAllowed
                if (needsPermission) Button(onClick = { requestPermissions() }, modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(stringResource(R.string.phone_permissions))
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (tab == 1) {
                        val history = state.data.history.filter {
                            search.isBlank() || it.name?.contains(search, true) == true ||
                                (PhoneSearch.number(search).isNotEmpty() && PhoneSearch.number(it.number).contains(PhoneSearch.number(search)))
                        }
                        if (history.isEmpty()) item { EmptyPhoneState(R.string.phone_empty_history) }
                        items(history, key = { it.id }) { row ->
                            val sim = state.data.sims.firstOrNull { it.key == row.accountKey }?.label
                            val direction = when(row.type) { CallLog.Calls.INCOMING_TYPE -> R.string.phone_incoming
                                CallLog.Calls.OUTGOING_TYPE -> R.string.phone_outgoing
                                CallLog.Calls.MISSED_TYPE -> R.string.phone_missed
                                CallLog.Calls.REJECTED_TYPE -> R.string.phone_rejected
                                CallLog.Calls.BLOCKED_TYPE -> R.string.phone_blocked
                                else -> R.string.phone_call }
                            var expanded by remember { mutableStateOf(false) }
                            ElevatedCard(modifier = Modifier.fillMaxWidth(), onClick = { number = PhoneSearch.dialable(row.number); tab = 0 }) {
                                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(if (row.type == CallLog.Calls.MISSED_TYPE) Icons.Default.CallMissed else Icons.Default.Call, null,
                                        tint = if (row.type == CallLog.Calls.MISSED_TYPE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(row.name?.takeIf { it.isNotBlank() } ?: row.number.ifBlank { stringResource(R.string.phone_unknown) },
                                            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(listOfNotNull(stringResource(direction), sim,
                                            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(row.date))).joinToString(" · "),
                                            style = MaterialTheme.typography.bodySmall)
                                    }
                                    IconButton(onClick = { call(row.number) }, enabled = row.number.isNotBlank()) {
                                        Icon(Icons.Default.Call, stringResource(R.string.phone_call)) }
                                    Box {
                                        IconButton(onClick = { expanded = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.phone_more)) }
                                        DropdownMenu(expanded, { expanded = false }) {
                                            DropdownMenuItem(text = { Text(stringResource(R.string.phone_block)) }, enabled = state.isDefault && row.number.isNotBlank(),
                                                onClick = { expanded = false; blockNumber = row.number })
                                            DropdownMenuItem(text = { Text(stringResource(R.string.phone_save_contact)) }, enabled = row.number.isNotBlank(),
                                                onClick = { expanded = false; context.startActivity(Intent(Intent.ACTION_INSERT_OR_EDIT).setType("vnd.android.cursor.item/contact")
                                                    .putExtra(android.provider.ContactsContract.Intents.Insert.PHONE, row.number)) })
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        val contacts = state.data.contacts.filter { PhoneSearch.matches(it, search) && (tab != 3 || it.favorite) }
                        if (contacts.isEmpty()) item { EmptyPhoneState(if (tab == 3) R.string.phone_empty_favorites else R.string.phone_empty_contacts) }
                        items(contacts, key = { it.id }) { contact ->
                            ContactLine(contact, onCall = { call(contact.number) }, onSelect = { number = PhoneSearch.dialable(contact.number); tab = 0 },
                                onFavorite = { vm.favorite(contact.number) })
                        }
                    }
                }
            }
        }
    }
    pendingNumber?.let { value ->
        AlertDialog(onDismissRequest = { pendingNumber = null }, title = { Text(stringResource(R.string.phone_choose_sim)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(value, style = MaterialTheme.typography.titleLarge.copy(textDirection = TextDirection.Ltr))
                if (state.data.sims.isEmpty()) Text(stringResource(R.string.phone_system_sim))
                state.data.sims.forEach { sim ->
                    Surface(onClick = { selectedSim = sim.key }, shape = MaterialTheme.shapes.medium,
                        color = if (selectedSim == sim.key) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
                        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selectedSim == sim.key, onClick = { selectedSim = sim.key })
                            Icon(Icons.Default.SimCard, null); Spacer(Modifier.width(8.dp)); Text(sim.label)
                        }
                    }
                }
                if (state.data.sims.size > 1) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(rememberSim, onCheckedChange = { rememberSim = it }); Text(stringResource(R.string.phone_remember_sim))
                }
            } },
            confirmButton = { Button(enabled = state.data.sims.isEmpty() || selectedSim != null, onClick = {
                val sim = state.data.sims.firstOrNull { it.key == selectedSim }
                pendingNumber = null
                if (rememberSim && sim != null) vm.repository.rememberSim(value, sim.key)
                perform { vm.repository.place(value, sim) }
            }) { Text(stringResource(R.string.phone_call)) } },
            dismissButton = { TextButton(onClick = { pendingNumber = null }) { Text(stringResource(R.string.phone_cancel)) } })
    }
    blockNumber?.let { value -> AlertDialog(onDismissRequest = { blockNumber = null },
        title = { Text(stringResource(R.string.phone_block)) }, text = { Text(value) },
        confirmButton = { Button(onClick = { blockNumber = null; perform { vm.repository.block(value) } }) { Text(stringResource(R.string.phone_block)) } },
        dismissButton = { TextButton(onClick = { blockNumber = null }) { Text(stringResource(R.string.phone_cancel)) } }) }
    blocked?.let { list -> AlertDialog(onDismissRequest = { blocked = null }, title = { Text(stringResource(R.string.phone_blocked)) },
        text = { LazyColumn(Modifier.heightIn(max = 400.dp)) {
            if (list.isEmpty()) item { Text(stringResource(R.string.phone_empty_blocked)) }
            items(list, key = { it.first }) { (id, value) -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(value, Modifier.weight(1f)); IconButton(onClick = {
                    perform { vm.repository.unblock(id) }; blocked = list.filterNot { it.first == id }
                }) { Icon(Icons.Default.Delete, stringResource(R.string.phone_unblock)) }
            } }
        } }, confirmButton = { TextButton(onClick = { blocked = null }) { Text(stringResource(R.string.phone_close)) } }) }
}

@Composable
private fun EmptyPhoneState(text: Int) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Default.PhoneAndroid, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(text), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DialingPanel(number: String, contacts: List<PhoneContact>, onNumber: (String) -> Unit,
    onCall: (String) -> Unit, onFavorite: (String) -> Unit) {
    val matches = if (number.isBlank()) emptyList() else contacts.filter { PhoneSearch.matches(it, number) }.take(4)
    val field: @Composable () -> Unit = {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            OutlinedTextField(value = number, onValueChange = { onNumber(PhoneSearch.dialable(it)) },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                textStyle = MaterialTheme.typography.headlineMedium.copy(textDirection = TextDirection.Ltr),
                label = { Text(stringResource(R.string.phone_number)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                trailingIcon = {
                    Surface(modifier = Modifier.size(48.dp).combinedClickable(onClick = { onNumber(number.dropLast(1)) },
                        onLongClick = { onNumber("") }), color = Color.Transparent) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.AutoMirrored.Filled.Backspace, stringResource(R.string.phone_delete))
                        }
                    }
                })
        }
    }
    val suggestions: @Composable (Modifier) -> Unit = { modifier ->
        LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(matches, key = { it.id }) { contact -> ContactLine(contact, onCall = { onCall(contact.number) },
                onSelect = { onNumber(PhoneSearch.dialable(contact.number)) }, onFavorite = { onFavorite(contact.number) }) }
            if (matches.isEmpty()) item { Text(stringResource(R.string.phone_search_hint), Modifier.padding(top = 12.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        }
    }
    val keypad: @Composable (Dp) -> Unit = { keyHeight ->
        NumberPad(onDigit = { onNumber(number + it) }, onLongZero = { onNumber(number + "+") }, keyHeight = keyHeight)
        Button(onClick = { onCall(number) }, enabled = number.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(54.dp), shape = CircleShape) {
            Icon(Icons.Default.Call, null); Spacer(Modifier.width(12.dp)); Text(stringResource(R.string.phone_call))
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 8.dp)) {
        val keyHeight = ((maxHeight - 86.dp) / 4).coerceIn(32.dp, 54.dp)
        if (maxWidth > 600.dp || maxHeight < 420.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    field(); suggestions(Modifier.weight(1f).fillMaxWidth())
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { keypad(keyHeight) }
            }
        } else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            field(); suggestions(Modifier.weight(1f).fillMaxWidth()); keypad(keyHeight)
        }
    }
}

@Composable
private fun ContactLine(contact: PhoneContact, onCall: () -> Unit, onSelect: () -> Unit, onFavorite: () -> Unit) {
    ElevatedCard(onClick = onSelect, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center) { Text(contact.name.take(1), fontWeight = FontWeight.Bold) }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(contact.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(contact.number, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onFavorite) {
                Icon(if (contact.favorite) Icons.Default.Star else Icons.Default.StarBorder, stringResource(R.string.phone_favorite),
                    tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = onCall) { Icon(Icons.Default.Call, stringResource(R.string.phone_call)) }
        }
    }
}

@Composable
internal fun NumberPad(onDigit: (String) -> Unit, onLongZero: () -> Unit = {}, toneMode: Boolean = false,
    onTone: (Char) -> Unit = {}, onToneStop: () -> Unit = {}, keyHeight: Dp = 54.dp, toneCallId: Int? = null) {
    val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("*", "0", "#"))
    val letters = mapOf("2" to "ABC", "3" to "DEF", "4" to "GHI", "5" to "JKL", "6" to "MNO", "7" to "PQRS", "8" to "TUV", "9" to "WXYZ", "0" to "+")
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) { row.forEach { digit ->
                val keyModifier = if (toneMode) Modifier.pointerInput(digit, toneCallId) {
                    detectTapGestures(onPress = {
                        onTone(digit[0])
                        try { tryAwaitRelease() } finally { onToneStop() }
                    })
                } else Modifier.combinedClickable(onClick = { onDigit(digit) },
                    onLongClick = { if (digit == "0") onLongZero() })
                Surface(modifier = Modifier.weight(1f).height(keyHeight)
                    .then(keyModifier),
                    shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text(digit, style = if (keyHeight < 44.dp) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
                        if (keyHeight >= 44.dp) Text(letters[digit].orEmpty(), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } }
        } }
    }
}
