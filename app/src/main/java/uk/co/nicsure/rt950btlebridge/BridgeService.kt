package uk.co.nicsure.rt950btlebridge

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.BufferedInputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class BridgeService : Service() {
    inner class LocalBinder : Binder() { val service: BridgeService get() = this@BridgeService }
    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var reconnectRunnable: Runnable? = null
    private var reconnectAttempts = 0
    @Volatile var currentStatus: String = "Not connected"
        private set
    @Volatile var onStatus: ((String) -> Unit)? = null
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var deviceAddress: String? = null
    @Volatile private var characteristic: BluetoothGattCharacteristic? = null
    @Volatile private var server: ServerSocket? = null
    @Volatile private var client: Socket? = null
    @Volatile private var running = false
    @Volatile private var connected = false
    @Volatile private var listenerStarted = false
    @Volatile private var initialValue: ByteArray? = null
    @Volatile private var mtuPayload = 20
    @Volatile private var channelManagerMode = false
    private var requestedPort = 14580
    private var deviceName = "RT950Pro"
    private val writeQueue = ArrayBlockingQueue<ByteArray>(256)
    private val channelNotifications = ArrayBlockingQueue<ByteArray>(1024)
    private var channelNotificationRemainder = ByteArray(0)
    private val bleIoLock = ReentrantLock(true)
    @Volatile private var pendingWrite: java.util.concurrent.CountDownLatch? = null
    @Volatile private var writeResult = BluetoothGatt.GATT_FAILURE

    val isBleConnected: Boolean get() = connected
    val isChannelManagerOpen: Boolean get() = channelManagerMode

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "RT950Pro bridge", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopBridge()
            ACTION_START -> {
                if (running) stopBridge()
                requestedPort = intent.getIntExtra(EXTRA_PORT, 14580)
                deviceName = intent.getStringExtra(EXTRA_NAME) ?: "RT950Pro"
                val address = intent.getStringExtra(EXTRA_ADDRESS)
                if (address.isNullOrBlank()) { publish("No BLE device address was supplied."); stopSelf(); return START_NOT_STICKY }
                deviceAddress = address
                reconnectAttempts = 0
                if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(NOTIFICATION_ID, notification("Connecting to $deviceName…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                } else {
                    startForeground(NOTIFICATION_ID, notification("Connecting to $deviceName…"))
                }
                running = true
                listenerStarted = false
                initialValue = null
                publish("Connecting to $deviceName over BLE…")
                connectGatt(address)
            }
        }
        return START_NOT_STICKY
    }

    @Suppress("MissingPermission")
    private fun connectGatt(address: String) {
        if (!running) return
        try {
            val device = (getSystemService(BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager)
                .adapter.getRemoteDevice(address)
            val connection = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            if (connection == null) scheduleReconnect("Could not start the BLE connection.")
            else gatt = connection
        } catch (e: Exception) {
            scheduleReconnect("BLE connection could not start: ${e.localizedMessage ?: e.javaClass.simpleName}")
        }
    }

    private fun scheduleReconnect(reason: String) {
        if (!running || reconnectRunnable != null) return
        connected = false
        characteristic = null
        channelNotifications.clear()
        channelNotificationRemainder = ByteArray(0)
        pendingWrite?.let {
            writeResult = BluetoothGatt.GATT_FAILURE
            it.countDown()
        }
        val oldGatt = gatt
        gatt = null
        runCatching { oldGatt?.disconnect() }
        runCatching { oldGatt?.close() }
        val delaySeconds = minOf(15, 2 shl reconnectAttempts.coerceAtMost(3))
        reconnectAttempts++
        publish("$reason Retrying BLE in $delaySeconds seconds; tap Stop Bridge to cancel.")
        reconnectRunnable = Runnable {
            reconnectRunnable = null
            val address = deviceAddress
            if (running && address != null) connectGatt(address)
        }.also { mainHandler.postDelayed(it, delaySeconds * 1000L) }
    }

    private fun bleReady() {
        if (!running) return
        connected = true
        reconnectAttempts = 0
        if (listenerStarted) {
            publish("BLE reconnected. TCP listener remains active on port $requestedPort.")
        } else {
            startTcpServer()
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (g !== gatt || !running) return
            if (status != BluetoothGatt.GATT_SUCCESS) { scheduleReconnect("BLE connection failed (status $status). Check that the RT950Pro is on and nearby."); return }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connected = false
                    publish("Connected to $deviceName; discovering FFE0/FFE1…")
                    if (!g.discoverServices()) scheduleReconnect("Could not start GATT service discovery.")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    scheduleReconnect("BLE device disconnected.")
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (g !== gatt || !running) return
            if (status != BluetoothGatt.GATT_SUCCESS) { scheduleReconnect("GATT service discovery failed (status $status)."); return }
            val service = g.getService(SERVICE_UUID)
            val data = service?.getCharacteristic(CHARACTERISTIC_UUID)
            if (data == null) { fail("The device does not expose service FFE0 with characteristic FFE1."); return }
            if (data.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) == 0) {
                fail("FFE1 does not support notifications/indications."); return
            }
            if (data.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) == 0) {
                fail("FFE1 does not support writes."); return
            }
            characteristic = data
            if (!g.setCharacteristicNotification(data, true)) { fail("Could not enable FFE1 updates."); return }
            val cccd = data.getDescriptor(CCCD_UUID)
            if (cccd == null) { fail("FFE1 has no client configuration descriptor for updates."); return }
            val updateValue = if (data.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            val started = if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(cccd, updateValue) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = updateValue
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
            if (!started) scheduleReconnect("Could not subscribe to FFE1 updates.")
        }

        @Deprecated("Legacy callback retained for Android 8–12")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (g === gatt && Build.VERSION.SDK_INT < 33) routeBleUpdate(c.value?.copyOf() ?: return)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (g === gatt) routeBleUpdate(value.copyOf())
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (g !== gatt || !running) return
            if (descriptor.uuid == CCCD_UUID) {
                if (status != BluetoothGatt.GATT_SUCCESS) { scheduleReconnect("FFE1 update subscription failed (status $status)."); return }
                val c = characteristic
                val readStarted = if (c != null && c.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
                    if (Build.VERSION.SDK_INT >= 33) g.readCharacteristic(c)
                    else {
                        @Suppress("DEPRECATION")
                        g.readCharacteristic(c)
                    }
                } else false
                if (!readStarted) bleReady()
            }
        }

        @Deprecated("Legacy callback retained for Android 8–12")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (g === gatt && Build.VERSION.SDK_INT < 33 && c.uuid == CHARACTERISTIC_UUID) {
                initialValue = if (status == BluetoothGatt.GATT_SUCCESS) c.value?.copyOf() else null
                if (initialValue != null && client != null && !channelManagerMode) forwardBleToTcp(initialValue!!)
                bleReady()
            }
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (g === gatt && c.uuid == CHARACTERISTIC_UUID) {
                initialValue = if (status == BluetoothGatt.GATT_SUCCESS) value.copyOf() else null
                if (initialValue != null && client != null && !channelManagerMode) forwardBleToTcp(initialValue!!)
                bleReady()
            }
        }

        @Deprecated("Legacy callback retained for Android 8–12")
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (g === gatt) completeGattWrite(status)
        }

    }

    private fun startTcpServer() {
        if (!running || listenerStarted) return
        listenerStarted = true
        Thread({
            try {
                val listener = ServerSocket(requestedPort, 1, java.net.InetAddress.getByName("0.0.0.0"))
                server = listener
                val ip = localIpv4Address()
                publish("BLE ready. Listening on TCP $ip:${listener.localPort}; waiting for one client…")
                while (running && !listener.isClosed) {
                    val socket = listener.accept()
                    if (!running) { socket.close(); break }
                    client = socket
                    socket.tcpNoDelay = true
                    initialValue?.let { value ->
                        socket.getOutputStream().apply { write(value); flush() }
                    }
                    publish("TCP client connected from ${socket.inetAddress.hostAddress}; bridge active.")
                    val writer = Thread({ writeSocketToBle(socket) }, "rt950-tcp-to-ble")
                    writer.start()
                    try {
                        val input = BufferedInputStream(socket.getInputStream())
                        val buffer = ByteArray(4096)
                        while (running && !socket.isClosed) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            val bytes = buffer.copyOf(count)
                            if (!channelManagerMode && !writeQueue.offer(bytes, 2, TimeUnit.SECONDS)) throw IOException("BLE write queue is full")
                        }
                    } catch (e: Exception) {
                        if (running) Log.i(TAG, "TCP client read ended: ${e.message}")
                    } finally {
                        runCatching { socket.close() }
                        writeQueue.clear()
                        client = null
                        if (running) publish("TCP client disconnected. Waiting for one client on $ip:${listener.localPort}…")
                    }
                }
            } catch (e: Exception) {
                if (running) fail("TCP listener failed: ${e.localizedMessage ?: e.javaClass.simpleName}")
            }
        }, "rt950-tcp-listener").start()
    }

    private fun writeSocketToBle(socket: Socket) {
        try {
            while (running && !socket.isClosed) {
                if (channelManagerMode) {
                    writeQueue.clear()
                    Thread.sleep(100)
                    continue
                }
                val packet = writeQueue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                val completed = bleIoLock.withLock {
                    if (channelManagerMode) true // discard bytes queued before the pause took effect
                    else writeGattBytesUnlocked(packet)
                }
                if (!completed) throw IOException("BLE characteristic write failed")
            }
        } catch (e: Exception) {
            if (running && !socket.isClosed) Log.w(TAG, "TCP to BLE writer ended", e)
            runCatching { socket.close() }
        }
    }

    @Suppress("DEPRECATION")
    private fun writeGattChunkUnlocked(bytes: ByteArray): Boolean {
        while (running) {
            while (running && !connected) Thread.sleep(250)
            if (!running) return false
            val g = gatt ?: continue
            val c = characteristic ?: continue
            val latch = java.util.concurrent.CountDownLatch(1)
            pendingWrite = latch
            writeResult = BluetoothGatt.GATT_FAILURE
            val started = if (Build.VERSION.SDK_INT >= 33) {
                val writeType = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0)
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                g.writeCharacteristic(c, bytes, writeType) == BluetoothGatt.GATT_SUCCESS
            } else {
                c.writeType = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0)
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                c.value = bytes
                g.writeCharacteristic(c)
            }
            if (!started) {
                pendingWrite = null
                if (!connected || g !== gatt) continue
                return false
            }
            val completed = latch.await(10, TimeUnit.SECONDS)
            pendingWrite = null
            if (completed && writeResult == BluetoothGatt.GATT_SUCCESS) return true
            if (!connected || g !== gatt) continue
            return false
        }
        return false
    }

    private fun writeGattBytesUnlocked(bytes: ByteArray): Boolean {
        var offset = 0
        while (offset < bytes.size && running && connected) {
            val end = minOf(offset + mtuPayload, bytes.size)
            if (!writeGattChunkUnlocked(bytes.copyOfRange(offset, end))) return false
            offset = end
        }
        return offset == bytes.size
    }

    private fun routeBleUpdate(bytes: ByteArray) {
        if (channelManagerMode) {
            if (!channelNotifications.offer(bytes)) {
                channelNotifications.poll()
                channelNotifications.offer(bytes)
            }
        } else {
            forwardBleToTcp(bytes)
        }
    }

    /** Pauses raw TCP forwarding while the UI uses FFE1 for device commands. */
    fun pauseProxyForChannelManager(): Boolean = bleIoLock.withLock {
        if (!running || !connected || characteristic == null) return false
        if (!channelManagerMode) {
            channelManagerMode = true
            writeQueue.clear()
            channelNotifications.clear()
            channelNotificationRemainder = ByteArray(0)
            publish("Channel Manager open. TCP forwarding is paused.")
        }
        true
    }

    fun resumeProxyFromChannelManager() {
        bleIoLock.withLock {
            if (!channelManagerMode) return
            channelNotifications.clear()
            channelNotificationRemainder = ByteArray(0)
            writeQueue.clear()
            channelManagerMode = false
            publish("Channel Manager closed. TCP forwarding resumed.")
        }
    }

    fun readChannelMemory(onProgress: (Int, Int) -> Unit): ByteArray {
        check(channelManagerMode) { "Open Channel Manager before reading channel data." }
        check(connected) { "The RT950Pro is not connected." }
        val data = ByteArray(CHANNEL_MEMORY_LENGTH)
        val blocks = CHANNEL_MEMORY_LENGTH / CHANNEL_BLOCK_SIZE
        repeat(blocks) { block ->
            val offset = block * CHANNEL_BLOCK_SIZE
            val address = CHANNEL_MEMORY_ADDRESS + offset
            val response = bleIoLock.withLock {
                sendChannelBytes(readRequest(address))
                readChannelBytes(CHANNEL_READ_RESPONSE_SIZE, 15_000)
            }
            validateChannelResponse(response, 0x30, address)
            response.copyInto(data, offset, 6, 6 + CHANNEL_BLOCK_SIZE)
            onProgress(block + 1, blocks)
        }
        return data
    }

    fun writeChannelMemory(data: ByteArray, onProgress: (Int, Int) -> Unit) {
        require(data.size == CHANNEL_MEMORY_LENGTH) { "Channel data has an unexpected size." }
        check(channelManagerMode) { "Open Channel Manager before writing channel data." }
        check(connected) { "The RT950Pro is not connected." }
        val blocks = CHANNEL_MEMORY_LENGTH / CHANNEL_BLOCK_SIZE
        repeat(blocks) { block ->
            val offset = block * CHANNEL_BLOCK_SIZE
            val address = CHANNEL_MEMORY_ADDRESS + offset
            bleIoLock.withLock {
                val packet = ByteArray(CHANNEL_WRITE_PACKET_SIZE)
                packet[0] = 0xAA.toByte()
                packet[1] = 0x40
                putAddress(packet, address)
                data.copyInto(packet, 6, offset, offset + CHANNEL_BLOCK_SIZE)
                packet[packet.lastIndex] = channelChecksum(packet, 2, packet.lastIndex)
                sendChannelBytes(packet)
                expectAck(0x40)
            }
            onProgress(block + 1, blocks)
        }
        bleIoLock.withLock {
            sendChannelBytes(byteArrayOf(0xAA.toByte(), 0x48))
            expectAck(0x48)
            // The desktop channel manager commits the flash and then reboots the radio.
            sendChannelBytes(byteArrayOf(0xAA.toByte(), 0x50))
            expectAck(0x48)
        }
    }

    fun readSelectedChannelBlocks(data: ByteArray, firstOffset: Int, lastOffsetExclusive: Int) {
        require(data.size == CHANNEL_MEMORY_LENGTH)
        readChannelBlocks(data, blockOffsets(firstOffset, lastOffsetExclusive))
    }

    fun writeSelectedChannelBlocks(data: ByteArray, firstOffset: Int, lastOffsetExclusive: Int, onProgress: (Int, Int) -> Unit) {
        writeChannelBlocks(data, blockOffsets(firstOffset, lastOffsetExclusive), onProgress)
    }

    fun readSelectedChannels(data: ByteArray, channelNumbers: List<Int>) {
        require(channelNumbers.isNotEmpty()) { "Select at least one channel." }
        val offsets = channelNumbers.flatMap { number -> channelBlockOffsets(number) }.distinct().sorted()
        readChannelBlocks(data, offsets)
    }

    fun writeSelectedChannels(data: ByteArray, channelNumbers: List<Int>, onProgress: (Int, Int) -> Unit) {
        require(channelNumbers.isNotEmpty()) { "Select at least one channel." }
        val offsets = channelNumbers.flatMap { number -> channelBlockOffsets(number) }.distinct().sorted()
        writeChannelBlocks(data, offsets, onProgress)
    }

    private fun readChannelBlocks(data: ByteArray, offsets: List<Int>) {
        require(data.size == CHANNEL_MEMORY_LENGTH)
        check(channelManagerMode && connected)
        for (offset in offsets) {
            val address = CHANNEL_MEMORY_ADDRESS + offset
            val response = bleIoLock.withLock {
                sendChannelBytes(readRequest(address))
                readChannelBytes(CHANNEL_READ_RESPONSE_SIZE, 15_000)
            }
            validateChannelResponse(response, 0x30, address)
            response.copyInto(data, offset, 6, 6 + CHANNEL_BLOCK_SIZE)
        }
    }

    private fun writeChannelBlocks(data: ByteArray, offsets: List<Int>, onProgress: (Int, Int) -> Unit) {
        require(data.size == CHANNEL_MEMORY_LENGTH)
        check(offsets.isNotEmpty()) { "Select at least one channel." }
        check(channelManagerMode && connected)
        offsets.forEachIndexed { index, offset ->
            val address = CHANNEL_MEMORY_ADDRESS + offset
            bleIoLock.withLock {
                val packet = ByteArray(CHANNEL_WRITE_PACKET_SIZE)
                packet[0] = 0xAA.toByte()
                packet[1] = 0x40
                putAddress(packet, address)
                data.copyInto(packet, 6, offset, offset + CHANNEL_BLOCK_SIZE)
                packet[packet.lastIndex] = channelChecksum(packet, 2, packet.lastIndex)
                sendChannelBytes(packet)
                expectAck(0x40)
            }
            onProgress(index + 1, offsets.size)
        }
        bleIoLock.withLock {
            sendChannelBytes(byteArrayOf(0xAA.toByte(), 0x48))
            expectAck(0x48)
            sendChannelBytes(byteArrayOf(0xAA.toByte(), 0x50))
            expectAck(0x48)
        }
    }

    private fun blockOffsets(firstOffset: Int, lastOffsetExclusive: Int): List<Int> {
        require(firstOffset >= 0 && lastOffsetExclusive > firstOffset && lastOffsetExclusive <= CHANNEL_MEMORY_LENGTH)
        val blockStart = firstOffset / CHANNEL_BLOCK_SIZE * CHANNEL_BLOCK_SIZE
        val blockEnd = ((lastOffsetExclusive + CHANNEL_BLOCK_SIZE - 1) / CHANNEL_BLOCK_SIZE) * CHANNEL_BLOCK_SIZE
        require(blockEnd <= CHANNEL_MEMORY_LENGTH)
        return (blockStart until blockEnd step CHANNEL_BLOCK_SIZE).toList()
    }

    private fun channelBlockOffsets(number: Int): List<Int> {
        require(number in 1..999) { "Channel number must be between 1 and 999." }
        val offset = (number + 3) * 62
        return blockOffsets(offset, offset + 62)
    }

    private fun sendChannelBytes(bytes: ByteArray) {
        if (!running || !connected || !writeGattBytesUnlocked(bytes)) {
            throw IOException("BLE write failed while communicating with the RT950Pro.")
        }
    }

    private fun readChannelBytes(count: Int, timeoutMs: Long): ByteArray {
        val result = ByteArray(count)
        var offset = 0
        var pending = ByteArray(0)
        var pendingOffset = 0
        while (offset < count) {
            if (pendingOffset >= pending.size) {
                pending = if (channelNotificationRemainder.isNotEmpty()) {
                    channelNotificationRemainder.also { channelNotificationRemainder = ByteArray(0) }
                } else {
                    channelNotifications.poll(timeoutMs, TimeUnit.MILLISECONDS)
                        ?: throw IOException("Timed out waiting for an RT950Pro response.")
                }
                pendingOffset = 0
            }
            val take = minOf(count - offset, pending.size - pendingOffset)
            pending.copyInto(result, offset, pendingOffset, pendingOffset + take)
            offset += take
            pendingOffset += take
            if (pendingOffset < pending.size) {
                channelNotificationRemainder = pending.copyOfRange(pendingOffset, pending.size)
                pendingOffset = pending.size
            }
        }
        return result
    }

    private fun expectAck(command: Int) {
        val ack = readChannelBytes(2, 10_000)
        if ((ack[0].toInt() and 0xFF) != 0xAA || (ack[1].toInt() and 0xFF) != command) {
            val received = ack.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
            throw IOException("Unexpected RT950Pro acknowledgement ($received).")
        }
    }

    private fun validateChannelResponse(packet: ByteArray, command: Int, address: Int) {
        val actualAddress = (packet[2].toInt() and 0xFF) or
            ((packet[3].toInt() and 0xFF) shl 8) or
            ((packet[4].toInt() and 0xFF) shl 16) or
            ((packet[5].toInt() and 0xFF) shl 24)
        if ((packet[0].toInt() and 0xFF) != 0xAA || (packet[1].toInt() and 0xFF) != command || actualAddress != address) {
            throw IOException("Unexpected RT950Pro response for address 0x${address.toString(16)}.")
        }
        if (channelChecksum(packet, 2, packet.lastIndex) != packet.last()) {
            throw IOException("RT950Pro response checksum mismatch at address 0x${address.toString(16)}.")
        }
    }

    private fun readRequest(address: Int): ByteArray = byteArrayOf(
        0xAA.toByte(), 0x30,
        (address and 0xFF).toByte(),
        ((address ushr 8) and 0xFF).toByte(),
        ((address ushr 16) and 0xFF).toByte(),
        ((address ushr 24) and 0xFF).toByte(),
        0
    )

    private fun putAddress(packet: ByteArray, address: Int) {
        packet[2] = (address and 0xFF).toByte()
        packet[3] = ((address ushr 8) and 0xFF).toByte()
        packet[4] = ((address ushr 16) and 0xFF).toByte()
        packet[5] = ((address ushr 24) and 0xFF).toByte()
    }

    private fun channelChecksum(packet: ByteArray, start: Int, endExclusive: Int): Byte {
        var sum = 0
        for (index in start until endExclusive) sum = (sum + (packet[index].toInt() and 0xFF)) and 0xFF
        return sum.toByte()
    }

    private fun completeGattWrite(status: Int) {
        writeResult = status
        pendingWrite?.countDown()
    }

    private fun forwardBleToTcp(bytes: ByteArray) {
        val socket = client ?: return
        try {
            synchronized(socket) {
                if (!socket.isClosed) {
                    val output = socket.getOutputStream()
                    output.write(bytes)
                    output.flush()
                }
            }
        } catch (e: Exception) {
            Log.i(TAG, "Could not forward BLE update to TCP client: ${e.message}")
        }
    }

    private fun localIpv4Address(): String = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { network ->
                val name = "${network.name} ${network.displayName}".lowercase()
                when {
                    name.contains("wlan") || name.contains("wi-fi") || name.contains("wifi") -> 0
                    name.contains("eth") -> 1
                    else -> 2
                }
            }
            .asSequence()
            .flatMap { it.inetAddresses.toList().asSequence() }
            .filterIsInstance<Inet4Address>().firstOrNull()?.hostAddress ?: "<tablet-ip>"
    } catch (_: Exception) { "<tablet-ip>" }

    private fun notification(text: String): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
        .setContentTitle("RT950Pro BTLE TCP Bridge")
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .build()

    private fun publish(message: String) {
        currentStatus = message
        onStatus?.invoke(message)
        if (running) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message))
    }

    private fun fail(message: String) {
        publish(message)
        stopBridge()
    }

    private fun stopBridge() {
        running = false
        connected = false
        channelManagerMode = false
        channelNotifications.clear()
        channelNotificationRemainder = ByteArray(0)
        reconnectRunnable?.let(mainHandler::removeCallbacks)
        reconnectRunnable = null
        runCatching { server?.close() }; server = null
        runCatching { client?.close() }; client = null
        writeQueue.clear()
        pendingWrite?.countDown(); pendingWrite = null
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }; gatt = null
        characteristic = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        publish("Stopped")
        stopSelf()
    }

    override fun onDestroy() {
        if (running) stopBridge()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "uk.co.nicsure.rt950btlebridge.START"
        const val ACTION_STOP = "uk.co.nicsure.rt950btlebridge.STOP"
        const val EXTRA_ADDRESS = "device_address"
        const val EXTRA_PORT = "tcp_port"
        const val EXTRA_NAME = "device_name"
        private const val CHANNEL_ID = "rt950_bridge"
        private const val NOTIFICATION_ID = 9501
        private const val TAG = "RT950Bridge"
        private const val CHANNEL_MEMORY_ADDRESS = 0x50200
        private const val CHANNEL_MEMORY_LENGTH = 0xF400
        private const val CHANNEL_BLOCK_SIZE = 128
        private const val CHANNEL_READ_RESPONSE_SIZE = 135
        private const val CHANNEL_WRITE_PACKET_SIZE = 135
        private val SERVICE_UUID: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        private val CHARACTERISTIC_UUID: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
