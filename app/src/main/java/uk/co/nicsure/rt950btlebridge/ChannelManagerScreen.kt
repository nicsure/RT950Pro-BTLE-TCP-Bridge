package uk.co.nicsure.rt950btlebridge

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale

@Composable
internal fun ChannelManagerScreen(
    service: BridgeService,
    isConnected: Boolean,
    onExit: () -> Unit,
    onStopBridge: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val channels = remember { mutableStateListOf<RadioChannel>().apply { addAll(ChannelData.emptyChannels()) } }
    val transferSelection = remember { mutableStateListOf<Int>() }
    var memory by remember { mutableStateOf(ByteArray(ChannelData.MEMORY_SIZE)) }
    var hasFullRead by remember { mutableStateOf(false) }
    var pendingCsv by remember { mutableStateOf<Map<Int, RadioChannel>?>(null) }
    var selectedNumber by remember { mutableIntStateOf(1) }
    var rxStepKHz by remember { mutableStateOf("0") }
    var refreshDraft by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var operation by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var message by remember { mutableStateOf("Read channels from the radio to begin.") }
    var confirmWriteAll by remember { mutableStateOf(false) }
    var confirmWriteSelected by remember { mutableStateOf(false) }

    fun launchOperation(label: String, action: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true
            operation = label
            progress = null
            message = label
            try {
                action()
            } catch (e: Exception) {
                message = e.localizedMessage ?: e.javaClass.simpleName
            } finally {
                busy = false
                operation = ""
                progress = null
            }
        }
    }

    fun updateSelectedChannels(change: (RadioChannel) -> RadioChannel) {
        val targets = transferSelection.toList().ifEmpty { listOf(selectedNumber) }
        targets.forEach { number ->
            val updated = change(channels[number - 1])
            channels[number - 1] = updated
            pendingCsv?.let { pendingCsv = it + (number to updated) }
        }
    }

    fun updateRxFrequencies(firstFrequencyMHz: Double, stepKHz: Double) {
        val targets = transferSelection.toList().ifEmpty { listOf(selectedNumber) }.sorted()
        targets.forEachIndexed { index, number ->
            val current = channels[number - 1]
            val offset = current.txMHz - current.rxMHz
            val rxMHz = (firstFrequencyMHz + index * stepKHz / 1000.0).coerceIn(0.00001, 1299.99999)
            val updated = current.copy(rxMHz = rxMHz, txMHz = (rxMHz + offset).coerceIn(0.00001, 1299.99999))
            channels[number - 1] = updated
            pendingCsv?.let { pendingCsv = it + (number to updated) }
        }
    }

    fun readAll() = launchOperation("Reading channels…") {
        val loaded = withContext(Dispatchers.IO) {
            service.readChannelMemory { done, total -> mainHandler.post { progress = done to total } }
        }
        memory = loaded
        val decoded = ChannelData.decodeAll(loaded).toMutableList()
        pendingCsv?.forEach { (number, channel) -> decoded[number - 1] = channel }
        channels.clear()
        channels.addAll(decoded)
        hasFullRead = true
        refreshDraft++
        message = if (pendingCsv == null) "All ${ChannelData.CHANNEL_COUNT} channel slots read."
        else "Radio memory read and loaded CSV channels merged. Write All to apply the imported channels."
        pendingCsv = null
    }

    fun writeAll() {
        if (!hasFullRead) {
            message = "Read all channels first, so the rest of the radio memory is preserved."
            return
        }
        confirmWriteAll = true
    }

    fun performWriteAll() = launchOperation("Writing all channels…") {
        val snapshot = memory.copyOf()
        channels.forEach { ChannelData.encode(snapshot, it) }
        withContext(Dispatchers.IO) {
            service.writeChannelMemory(snapshot) { done, total -> mainHandler.post { progress = done to total } }
        }
        memory = snapshot
        pendingCsv = null
        message = "All channels written. The radio is rebooting; BLE will reconnect automatically."
    }

    fun readSelected() = launchOperation("Reading ${transferSelection.size} selected channel(s)…") {
        val snapshot = memory.copyOf()
        val numbers = transferSelection.sorted()
        withContext(Dispatchers.IO) {
            service.readSelectedChannels(snapshot, numbers)
        }
        memory = snapshot
        numbers.forEach { number ->
            val updated = ChannelData.decode(snapshot, number)
            channels[number - 1] = updated
            pendingCsv?.let { pendingCsv = it - number }
        }
        refreshDraft++
        message = "${numbers.size} selected channel(s) read."
    }

    fun writeSelected() {
        confirmWriteSelected = true
    }

    fun performWriteSelected() = launchOperation("Writing ${transferSelection.size} selected channel(s)…") {
        val snapshot = memory.copyOf()
        val numbers = transferSelection.sorted()
        withContext(Dispatchers.IO) {
            // Refresh every touched block first, preserving unselected channels and other memory.
            service.readSelectedChannels(snapshot, numbers)
        }
        numbers.forEach { number -> ChannelData.encode(snapshot, channels[number - 1]) }
        withContext(Dispatchers.IO) {
            service.writeSelectedChannels(snapshot, numbers) { done, total ->
                mainHandler.post { progress = done to total }
            }
        }
        memory = snapshot
        message = "${numbers.size} selected channel(s) written. The radio is rebooting; BLE will reconnect automatically."
    }

    val saveCsvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
        if (uri != null) launchOperation("Saving channel CSV…") {
            val csv = ChannelCsv.write(channels)
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(csv) }
                    ?: throw IOException("Could not open the selected file for writing.")
            }
            message = "Channel CSV saved."
        }
    }
    val openCsvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) launchOperation("Loading channel CSV…") {
            val csv = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: throw IOException("Could not open the selected file.")
            }
            val imported = ChannelCsv.read(csv)
            channels.clear()
            channels.addAll(ChannelData.emptyChannels().map { imported[it.number] ?: it })
            pendingCsv = imported
            hasFullRead = false
            refreshDraft++
            message = "Channel CSV loaded. Read the radio before using Write All; Write Selected preserves neighboring data."
        }
    }

    BackHandler(enabled = true) {
        if (!busy) onExit() else message = "Wait for the current radio operation to finish before closing the manager."
    }

    if (confirmWriteAll) {
        AlertDialog(
            onDismissRequest = { confirmWriteAll = false },
            title = { Text("Write all channels?") },
            text = { Text("This writes the full channel memory to the RT950Pro and reboots the radio.") },
            confirmButton = {
                Button(onClick = { confirmWriteAll = false; performWriteAll() }) { Text("Write all") }
            },
            dismissButton = { TextButton(onClick = { confirmWriteAll = false }) { Text("Cancel") } }
        )
    }
    if (confirmWriteSelected) {
        AlertDialog(
            onDismissRequest = { confirmWriteSelected = false },
            title = { Text("Write ${transferSelection.size} selected channel(s)?") },
            text = { Text("The manager will refresh touched blocks, write the selected channels, and reboot the radio.") },
            confirmButton = {
                Button(onClick = { confirmWriteSelected = false; performWriteSelected() }) { Text("Write selected") }
            },
            dismissButton = { TextButton(onClick = { confirmWriteSelected = false }) { Text("Cancel") } }
        )
    }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Channel Manager", style = MaterialTheme.typography.headlineSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onExit, enabled = !busy) { Text("Done") }
                TextButton(onClick = onStopBridge, enabled = !busy) { Text("Stop Bridge") }
            }
        }
        Text(
            if (isConnected) "TCP forwarding paused · BLE connected" else "TCP forwarding paused · waiting for BLE reconnect",
            style = MaterialTheme.typography.bodySmall
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = { readAll() }, enabled = !busy && isConnected, modifier = Modifier.weight(1f)) { Text("Read All") }
            Button(onClick = { writeAll() }, enabled = !busy && isConnected && hasFullRead, modifier = Modifier.weight(1f)) { Text("Write All") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = { readSelected() }, enabled = !busy && isConnected && transferSelection.isNotEmpty(), modifier = Modifier.weight(1f)) { Text("Read Selected") }
            Button(onClick = { writeSelected() }, enabled = !busy && isConnected && transferSelection.isNotEmpty(), modifier = Modifier.weight(1f)) { Text("Write Selected") }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${transferSelection.size} selected")
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { transferSelection.clear(); transferSelection.addAll(channels.map { it.number }) }, enabled = !busy) { Text("Select all") }
                TextButton(onClick = { transferSelection.clear() }, enabled = !busy && transferSelection.isNotEmpty()) { Text("Clear") }
            }
        }
        var selectedDraft by remember(selectedNumber) { mutableStateOf(selectedNumber.toString()) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = selectedDraft,
                onValueChange = { text ->
                    selectedDraft = text
                    text.toIntOrNull()?.takeIf { it in 1..ChannelData.CHANNEL_COUNT }?.let { selectedNumber = it }
                },
                label = { Text("Channel #") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
            Button(onClick = { openCsvLauncher.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel")) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Load CSV") }
            Button(onClick = { saveCsvLauncher.launch("RT950Pro-Channels.csv") }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Save CSV") }
        }
        if (busy) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator()
                Text(progress?.let { "$operation ${it.first}/${it.second}" } ?: operation)
            }
        } else {
            Text(message, style = MaterialTheme.typography.bodySmall)
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val selected = channels[selectedNumber - 1]
            val editingChannels = transferSelection.map { channels[it - 1] }.ifEmpty { listOf(selected) }
            if (maxWidth >= 700.dp) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChannelList(channels, selectedNumber, transferSelection, { selectedNumber = it }, { number, checked ->
                        if (checked) { if (number !in transferSelection) transferSelection.add(number) }
                        else transferSelection.remove(number)
                    }, Modifier.weight(0.42f))
                    ChannelEditor(selected, editingChannels, refreshDraft, rxStepKHz, { rxStepKHz = it }, ::updateSelectedChannels,
                        { frequency, step -> updateRxFrequencies(frequency, step) }, Modifier.weight(0.58f))
                }
            } else {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ChannelList(channels, selectedNumber, transferSelection, { selectedNumber = it }, { number, checked ->
                        if (checked) { if (number !in transferSelection) transferSelection.add(number) }
                        else transferSelection.remove(number)
                    }, Modifier.weight(0.36f))
                    ChannelEditor(selected, editingChannels, refreshDraft, rxStepKHz, { rxStepKHz = it }, ::updateSelectedChannels,
                        { frequency, step -> updateRxFrequencies(frequency, step) }, Modifier.weight(0.64f))
                }
            }
        }
    }
}

@Composable
private fun ChannelList(
    channels: List<RadioChannel>,
    selectedNumber: Int,
    transferSelection: List<Int>,
    onSelect: (Int) -> Unit,
    onTransferSelectionChange: (Int, Boolean) -> Unit,
    modifier: Modifier
) {
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        items(channels, key = { it.number }) { channel ->
            val selected = channel.number == selectedNumber
            Card(
                Modifier.fillMaxWidth().clickable { onSelect(channel.number) },
                colors = CardDefaults.cardColors(
                    containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                        Text(
                            if (channel.active) "%03d  %s / %s MHz".format(Locale.US, channel.number, channel.rxMHz.fmt5(), channel.txMHz.fmt5())
                            else "%03d  Unused".format(channel.number),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (channel.active && channel.name.isNotBlank()) Text(channel.name, style = MaterialTheme.typography.bodySmall)
                    }
                    Checkbox(checked = channel.number in transferSelection, onCheckedChange = { onTransferSelectionChange(channel.number, it) })
                }
            }
        }
    }
}

@Composable
private fun ChannelEditor(
    channel: RadioChannel,
    editingChannels: List<RadioChannel>,
    refreshDraft: Int,
    rxStepKHz: String,
    onRxStepChange: (String) -> Unit,
    onChange: ((RadioChannel) -> RadioChannel) -> Unit,
    onRxFrequencyChange: (Double, Double) -> Unit,
    modifier: Modifier
) {
    val selectionKey = editingChannels.joinToString(",") { it.number.toString() }
    val activeKey = editingChannels.joinToString(",") { it.active.toString() }
    val canEditActiveSettings = editingChannels.all { it.active }
    val mixed: (RadioChannel.() -> Any) -> Boolean = { value -> editingChannels.map(value).distinct().size > 1 }
    var nameDraft by remember(selectionKey, activeKey, refreshDraft) { mutableStateOf(if (mixed { name }) "" else channel.name) }
    var nameFieldFocused by remember(selectionKey, activeKey) { mutableStateOf(false) }
    val nameBringIntoView = remember { BringIntoViewRequester() }
    val focusScope = rememberCoroutineScope()
    var tonePickerTarget by remember { mutableStateOf<String?>(null) }

    val currentTone = when (tonePickerTarget) {
        "RX" -> channel.rxTone
        "TX" -> channel.txTone
        else -> "None"
    }
    if (tonePickerTarget != null) {
        TonePresetDialog(
            title = "Select ${tonePickerTarget} Tone",
            currentTone = currentTone,
            onDismiss = { tonePickerTarget = null },
            onSelect = { tone ->
                if (tonePickerTarget == "RX") onChange { it.copy(rxTone = tone) }
                else onChange { it.copy(txTone = tone) }
                tonePickerTarget = null
            }
        )
    }

    fun applyNameDraft(input: String) {
        val percentIndex = input.indexOf('%')
        var firstNumber = 1
        var template = input
        if (percentIndex >= 0) {
            var digitEnd = percentIndex + 1
            while (digitEnd < input.length && input[digitEnd].isDigit()) digitEnd++
            if (digitEnd > percentIndex + 1) {
                firstNumber = input.substring(percentIndex + 1, digitEnd).toIntOrNull() ?: 1
                template = input.removeRange(percentIndex + 1, digitEnd)
            }
        }
        val names = editingChannels.map { it.number }.sorted().mapIndexed { index, number ->
            number to if (percentIndex >= 0) template.replace("%", (firstNumber + index).toString()) else template
        }.toMap()
        onChange { current -> current.copy(name = names[current.number] ?: input) }
        nameDraft = names.values.distinct().singleOrNull() ?: ""
    }

    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(if (editingChannels.size > 1) "Editing ${editingChannels.size} selected channels" else "Channel ${channel.number}", style = MaterialTheme.typography.titleLarge)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (mixed { active }) "Active (mixed)" else "Active")
            Switch(checked = editingChannels.all { it.active }, onCheckedChange = { active -> onChange { current ->
                if (!active) current.copy(active = false, rxMHz = 0.0, txMHz = 0.0)
                else if (current.active) current
                else current.copy(active = true, rxMHz = 145.0, txMHz = 145.0, modulation = 3, bandwidth = 2,
                    txPower = 0, pttId = 0, scrambler = 0, clarifier = 0.0, name = "", rxTone = "None", txTone = "None",
                    groups = "", busyLock = false, reversed = false)
            } })
        }
        if (canEditActiveSettings) {
        OutlinedTextField(
            value = nameDraft,
            onValueChange = { nameDraft = it },
            enabled = canEditActiveSettings,
            label = { Text(if (mixed { name }) "Name (mixed)" else "Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().bringIntoViewRequester(nameBringIntoView).onFocusChanged { focus ->
                if (focus.isFocused) {
                    nameFieldFocused = true
                    focusScope.launch { delay(250); nameBringIntoView.bringIntoView() }
                }
                else if (nameFieldFocused) {
                    nameFieldFocused = false
                    applyNameDraft(nameDraft)
                }
            }
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NumberDraft("RX (MHz)", selectionKey, activeKey, if (mixed { rxMHz }) null else channel.rxMHz, refreshDraft, 5, Modifier.weight(1f), canEditActiveSettings) { value ->
                onRxFrequencyChange(value, rxStepKHz.toDoubleOrNull() ?: 0.0)
            }
            NumberDraft("TX (MHz)", selectionKey, activeKey, if (mixed { txMHz }) null else channel.txMHz, refreshDraft, 5, Modifier.weight(1f), canEditActiveSettings) { value -> onChange { it.copy(txMHz = value.coerceIn(0.00001, 1299.99999)) } }
        }
        OutlinedTextField(
            value = rxStepKHz,
            onValueChange = { text ->
                if (text.all { it.isDigit() || it == '.' } && text.count { it == '.' } <= 1) onRxStepChange(text)
            },
            enabled = canEditActiveSettings,
            label = { Text("Step (kHz)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth().then(rememberFocusBringIntoViewModifier())
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(if (mixed { rxTone }) "" else channel.rxTone, { text -> onChange { it.copy(rxTone = text) } }, enabled = canEditActiveSettings, label = { Text(if (mixed { rxTone }) "RX tone (mixed)" else "RX tone") }, singleLine = true, modifier = Modifier.weight(1f).then(rememberFocusBringIntoViewModifier()))
            OutlinedButton(onClick = { tonePickerTarget = "RX" }, enabled = canEditActiveSettings) { Text("Presets") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(if (mixed { txTone }) "" else channel.txTone, { text -> onChange { it.copy(txTone = text) } }, enabled = canEditActiveSettings, label = { Text(if (mixed { txTone }) "TX tone (mixed)" else "TX tone") }, singleLine = true, modifier = Modifier.weight(1f).then(rememberFocusBringIntoViewModifier()))
            OutlinedButton(onClick = { tonePickerTarget = "TX" }, enabled = canEditActiveSettings) { Text("Presets") }
        }
        OutlinedTextField(if (mixed { groups }) "" else channel.groups, { text -> onChange { it.copy(groups = text.filter(Char::isLetter).uppercase(Locale.ROOT).toList().distinct().sorted().take(4).joinToString("")) } },
            enabled = canEditActiveSettings, label = { Text(if (mixed { groups }) "Groups (mixed)" else "Groups (A–Z)") }, singleLine = true, modifier = Modifier.fillMaxWidth().then(rememberFocusBringIntoViewModifier()))
        ChoiceEditor("Modulation", ChannelData.modulations, if (mixed { modulation }) null else channel.modulation, canEditActiveSettings) { index -> onChange { it.copy(modulation = index) } }
        ChoiceEditor("Bandwidth", ChannelData.bandwidths, if (mixed { bandwidth }) null else channel.bandwidth, canEditActiveSettings) { index -> onChange { it.copy(bandwidth = index) } }
        ChoiceEditor("TX power", ChannelData.txPowers, if (mixed { txPower }) null else channel.txPower, canEditActiveSettings) { index -> onChange { it.copy(txPower = index) } }
        ChoiceEditor("PTT ID", ChannelData.pttIds, if (mixed { pttId }) null else channel.pttId, canEditActiveSettings) { index -> onChange { it.copy(pttId = index) } }
        ChoiceEditor("Scrambler", ChannelData.scramblers, if (mixed { scrambler }) null else channel.scrambler, canEditActiveSettings) { index -> onChange { it.copy(scrambler = index) } }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (mixed { busyLock }) "Busy lock (mixed)" else "Busy lock")
            Switch(editingChannels.all { it.busyLock }, { checked -> onChange { it.copy(busyLock = checked) } }, enabled = canEditActiveSettings)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (mixed { reversed }) "Reversed / AM filter (mixed)" else "Reversed / AM filter")
            Switch(editingChannels.all { it.reversed }, { checked -> onChange { it.copy(reversed = checked) } }, enabled = canEditActiveSettings)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NumberDraft("Clarifier", selectionKey, activeKey, if (mixed { clarifier }) null else channel.clarifier, refreshDraft, 2, Modifier.weight(1f), canEditActiveSettings) { value -> onChange { it.copy(clarifier = value.coerceIn(-100.0, 100.0)) } }
            NumberDraft("Scan hold", selectionKey, activeKey, if (mixed { scanHold }) null else channel.scanHold, refreshDraft, 1, Modifier.weight(1f), canEditActiveSettings) { value -> onChange { it.copy(scanHold = value.coerceIn(0.0, 9.0)) } }
        }
        NumberDraft("Scan monitor override", selectionKey, activeKey, if (mixed { scanMonitor }) null else channel.scanMonitor.toDouble(), refreshDraft, 0, Modifier.fillMaxWidth(), canEditActiveSettings) { value ->
            onChange { it.copy(scanMonitor = value.toInt().coerceIn(0, 99)) }
        }
        } else {
            Text("Activate all selected channels to edit their settings.", style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun NumberDraft(label: String, selectionKey: String, activeKey: String, value: Double?, refreshDraft: Int, decimals: Int, modifier: Modifier, enabled: Boolean, onValue: (Double) -> Unit) {
    var text by remember(selectionKey, activeKey, refreshDraft) { mutableStateOf(value?.fmt(decimals) ?: "") }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input
            input.toDoubleOrNull()?.let(onValue)
        },
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier.then(rememberFocusBringIntoViewModifier())
    )
}

@Composable
private fun rememberFocusBringIntoViewModifier(): Modifier {
    val requester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    return Modifier.bringIntoViewRequester(requester).onFocusChanged { focus ->
        if (focus.isFocused) scope.launch { delay(250); requester.bringIntoView() }
    }
}

@Composable
private fun ChoiceEditor(label: String, options: List<String>, selectedIndex: Int?, enabled: Boolean, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    if (!enabled) expanded = false
    androidx.compose.foundation.layout.Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) {
                Text(label, style = MaterialTheme.typography.labelSmall)
                Text(selectedIndex?.let { options.getOrElse(it) { options.first() } } ?: "Multiple values · choose one")
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(text = { Text(option) }, onClick = { onSelect(index); expanded = false })
            }
        }
    }
}

@Composable
private fun TonePresetDialog(title: String, currentTone: String, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    val initialCategory = when {
        currentTone.startsWith("D") && currentTone.endsWith("I") -> "DCS-I"
        currentTone.startsWith("D") -> "DCS-N"
        currentTone == "None" -> "None"
        else -> "CTCSS"
    }
    var category by remember(currentTone) { mutableStateOf(initialCategory) }
    var filter by remember { mutableStateOf("") }
    val presets = when (category) {
        "None" -> listOf("None")
        "CTCSS" -> ChannelData.ctcssTonePresets
        "DCS-N" -> ChannelData.dcsTonePresets.map { "${it}N" }
        else -> ChannelData.dcsTonePresets.map { "${it}I" }
    }.filter { it.contains(filter.trim(), ignoreCase = true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    listOf(listOf("None", "CTCSS"), listOf("DCS-N", "DCS-I")).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            row.forEach { name ->
                                FilterChip(selected = category == name, onClick = { category = name; filter = "" }, label = { Text(name) })
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    label = { Text("Filter presets") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(presets) { tone ->
                        TextButton(onClick = { onSelect(tone) }, modifier = Modifier.fillMaxWidth()) {
                            Text(tone, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun Double.fmt(decimals: Int): String = String.format(Locale.US, "% .${decimals}f", this).trim()
private fun Double.fmt5(): String = String.format(Locale.US, "%.5f", this)

private object ChannelCsv {
    private val header = listOf("Channel_Num", "Active", "Name", "RX", "TX", "RX_Tone", "TX_Tone", "TX_Power", "Slot1", "Slot2", "Slot3", "Slot4", "Bandwidth", "Modulation", "BusyLock", "Reversed", "PTTID", "Clarifier", "Scrambler", "ScanMonitor", "ScanHold")

    fun write(channels: List<RadioChannel>): String = buildString {
        appendLine(header.joinToString(","))
        channels.forEach { channel ->
            val groupSlots = channel.groups.padEnd(4, ' ').take(4).map { if (it == ' ') "" else it.toString() }
            val row = listOf(
                channel.number.toString(), channel.active.toString(), channel.name, channel.rxMHz.fmt5(), channel.txMHz.fmt5(),
                channel.rxTone, channel.txTone, ChannelData.txPowers[channel.txPower.coerceIn(0, 6)],
                groupSlots[0], groupSlots[1], groupSlots[2], groupSlots[3],
                ChannelData.bandwidths[channel.bandwidth.coerceIn(0, 7)], ChannelData.modulations[channel.modulation.coerceIn(0, 8)],
                channel.busyLock.toString(), channel.reversed.toString(), ChannelData.pttIds[channel.pttId.coerceIn(0, 3)],
                channel.clarifier.fmt(2), ChannelData.scramblers[channel.scrambler.coerceIn(0, 20)], channel.scanMonitor.toString(), channel.scanHold.fmt(1)
            )
            appendLine(row.joinToString(",") { csvQuote(it) })
        }
    }

    fun read(text: String): Map<Int, RadioChannel> {
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) throw IOException("The CSV file is empty.")
        val headerRow = splitCsv(lines.first()).map { it.trim() }
        val mapping = headerRow.withIndex().associate { it.value.lowercase(Locale.ROOT) to it.index }
        fun value(row: List<String>, key: String, default: String = ""): String = row.getOrNull(mapping[key.lowercase(Locale.ROOT)] ?: -1) ?: default
        val parsed = mutableMapOf<Int, RadioChannel>()
        lines.drop(1).forEach { line ->
            val cells = splitCsv(line)
            val number = value(cells, "Channel_Num").toIntOrNull()?.takeIf { it in 1..ChannelData.CHANNEL_COUNT } ?: return@forEach
            fun option(key: String, options: List<String>, fallback: Int): Int = options.indexOfFirst { it.equals(value(cells, key), ignoreCase = true) }.takeIf { it >= 0 } ?: fallback
            val slots = (1..4).mapNotNull { slot -> value(cells, "Slot$slot").trim().firstOrNull() }.filter { it.isLetter() }.distinct().sorted().joinToString("")
            val rx = value(cells, "RX").toDoubleOrNull() ?: 0.0
            val record = RadioChannel(
                number = number,
                active = value(cells, "Active").toBooleanStrictOrNull() ?: (rx > 0),
                name = value(cells, "Name"), rxMHz = rx, txMHz = value(cells, "TX").toDoubleOrNull() ?: rx,
                rxTone = value(cells, "RX_Tone", "None"), txTone = value(cells, "TX_Tone", "None"),
                txPower = option("TX_Power", ChannelData.txPowers, 0), groups = slots,
                bandwidth = option("Bandwidth", ChannelData.bandwidths, 2), modulation = option("Modulation", ChannelData.modulations, 3),
                busyLock = value(cells, "BusyLock").toBooleanStrictOrNull() ?: false,
                reversed = value(cells, "Reversed").toBooleanStrictOrNull() ?: false,
                pttId = option("PTTID", ChannelData.pttIds, 0), clarifier = value(cells, "Clarifier").toDoubleOrNull() ?: 0.0,
                scrambler = option("Scrambler", ChannelData.scramblers, 0), scanMonitor = value(cells, "ScanMonitor").toIntOrNull() ?: 0,
                scanHold = value(cells, "ScanHold").toDoubleOrNull() ?: 0.0
            )
            parsed[number] = record
        }
        if (parsed.isEmpty()) throw IOException("No channel rows were found in the CSV file.")
        return parsed
    }

    private fun csvQuote(value: String): String = if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"${value.replace("\"", "\"\"")}\"" else value
    private fun splitCsv(line: String): List<String> {
        val result = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { field.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { result.add(field.toString()); field.setLength(0) }
                else -> field.append(c)
            }
            i++
        }
        result.add(field.toString())
        return result
    }
}
