package uk.co.nicsure.rt950btlebridge

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import uk.co.nicsure.rt950btlebridge.ui.theme.RT950ProBTLETCPBridgeTheme

class MainActivity : ComponentActivity() {
    private val devices = mutableStateListOf<FoundDevice>()
    private var status by mutableStateOf("Not connected")
    private var scanning by mutableStateOf(false)
    private var bridgeRunning by mutableStateOf(false)
    private var port by mutableStateOf("14580")
    private var bridgeService: BridgeService? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val serviceConnection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, binder: android.os.IBinder?) {
            bridgeService = (binder as BridgeService.LocalBinder).service
            bridgeService?.onStatus = { message ->
                mainHandler.post {
                    status = message
                    bridgeRunning = message != "Stopped" && message != "Not connected"
                }
            }
            status = bridgeService?.currentStatus ?: status
            bridgeRunning = status != "Stopped" && status != "Not connected"
        }
        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            bridgeService = null
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val bluetoothPermissions = result.filterKeys { it != Manifest.permission.POST_NOTIFICATIONS }
        if (bluetoothPermissions.values.all { it }) startScan() else status = "Bluetooth permissions are required to scan and connect."
    }

    private val scanner: android.bluetooth.le.BluetoothLeScanner?
        get() = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.bluetoothLeScanner

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val found = FoundDevice(
                device = device,
                name = runCatching { result.scanRecord?.deviceName ?: device.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Unknown BLE device",
                address = runCatching { device.address }.getOrDefault("")
            )
            mainHandler.post {
                val index = devices.indexOfFirst { it.address == found.address }
                if (index < 0) devices.add(found) else devices[index] = found
            }
        }
        override fun onScanFailed(errorCode: Int) {
            mainHandler.post { scanning = false; status = "BLE scan failed (code $errorCode)." }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RT950ProBTLETCPBridgeTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("RT950Pro BTLE TCP Bridge", style = MaterialTheme.typography.headlineSmall)
                        Text(status, style = MaterialTheme.typography.bodyLarge)
                        OutlinedTextField(
                            value = port,
                            onValueChange = { value -> if (value.length <= 5 && value.all(Char::isDigit)) port = value },
                            label = { Text("TCP port") },
                            singleLine = true,
                            enabled = !bridgeRunning,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { requestPermissionsAndScan() }, enabled = !scanning && !bridgeRunning) {
                                Text(if (scanning) "Scanning…" else "Scan for RT950Pro")
                            }
                            if (scanning) Button(onClick = { stopScan() }) { Text("Stop scan") }
                            if (bridgeRunning) Button(onClick = { stopBridge() }) { Text("Stop bridge") }
                        }
                        Text("Tap the RT950Pro in the scan results to connect. The first TCP client gets an initial FFE1 read, then receives updates; client bytes are written to FFE1.")
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(devices, key = { it.address }) { item ->
                                Card(Modifier.fillMaxWidth().clickable(enabled = !bridgeRunning) { connect(item) }) {
                                    Column(Modifier.padding(14.dp)) {
                                        Text(item.name, style = MaterialTheme.typography.titleMedium)
                                        Text(item.address, style = MaterialTheme.typography.bodySmall)
                                        if (item.device.name?.contains("950", ignoreCase = true) == true || item.name.contains("950", ignoreCase = true)) {
                                            Text("Name looks like an RT950 device")
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        Text("Service FFE0 · Characteristic FFE1 · one TCP client · listens on the local network without encryption or a password")
                    }
                }
            }
        }
        bindService(Intent(this, BridgeService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun requiredPermissions(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= 31) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    private fun requestPermissionsAndScan() {
        val missing = requiredPermissions().filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startScan() else permissionLauncher.launch(missing.toTypedArray())
    }

    @Suppress("MissingPermission")
    private fun startScan() {
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) { status = "Turn on Bluetooth, then scan again."; return }
        val bleScanner = scanner
        if (bleScanner == null) { status = "BLE scanning is unavailable on this device."; return }
        devices.clear()
        status = "Scanning nearby BLE devices…"
        scanning = true
        try {
            bleScanner.startScan(scanCallback)
            mainHandler.postDelayed({ stopScan() }, 12_000)
        } catch (e: Exception) {
            scanning = false
            status = "Could not start scan: ${e.localizedMessage ?: e.javaClass.simpleName}"
        }
    }

    @Suppress("MissingPermission")
    private fun stopScan() {
        if (scanning) runCatching { scanner?.stopScan(scanCallback) }
        scanning = false
        if (devices.isEmpty() && status.startsWith("Scanning")) status = "No BLE devices found. Move near the RT950Pro and scan again."
        else if (status.startsWith("Scanning")) status = "Select the RT950Pro from the results."
    }

    private fun connect(item: FoundDevice) {
        stopScan()
        val tcpPort = port.toIntOrNull()?.takeIf { it in 1..65535 }
        if (tcpPort == null) { status = "Enter a valid TCP port (1–65535)."; return }
        val intent = Intent(this, BridgeService::class.java).apply {
            action = BridgeService.ACTION_START
            putExtra(BridgeService.EXTRA_ADDRESS, item.address)
            putExtra(BridgeService.EXTRA_PORT, tcpPort)
            putExtra(BridgeService.EXTRA_NAME, item.name)
        }
        try {
            ContextCompat.startForegroundService(this, intent)
            status = "Starting bridge to ${item.name}…"
        } catch (e: Exception) {
            status = "Could not start bridge: ${e.localizedMessage ?: e.javaClass.simpleName}"
        }
    }

    private fun stopBridge() {
        startService(Intent(this, BridgeService::class.java).setAction(BridgeService.ACTION_STOP))
        status = "Stopping bridge…"
    }

    override fun onDestroy() {
        stopScan()
        runCatching { unbindService(serviceConnection) }
        super.onDestroy()
    }
}

private data class FoundDevice(val device: BluetoothDevice, val name: String, val address: String)
