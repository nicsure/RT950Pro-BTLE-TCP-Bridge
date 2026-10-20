package uk.co.nicsure.rt950btlebridge

import java.nio.charset.StandardCharsets
import java.util.Locale

internal data class RadioChannel(
    val number: Int,
    val active: Boolean = false,
    val name: String = "",
    val rxMHz: Double = 0.0,
    val txMHz: Double = 0.0,
    val rxTone: String = "None",
    val txTone: String = "None",
    val txPower: Int = 0,
    val groups: String = "",
    val bandwidth: Int = 2,
    val modulation: Int = 3,
    val busyLock: Boolean = false,
    val reversed: Boolean = false,
    val pttId: Int = 0,
    val clarifier: Double = 0.0,
    val scrambler: Int = 0,
    val scanMonitor: Int = 0,
    val scanHold: Double = 0.0
)

internal object ChannelData {
    const val MEMORY_SIZE = 0xF400
    const val RECORD_SIZE = 62
    const val CHANNEL_COUNT = 999
    private const val CHANNEL_MAGIC = 0xC950

    val modulations = listOf("FM", "AM", "DSB", "Automatic", "Tuner WFM", "Tuner AM", "Tuner LSB", "Tuner USB", "Tuner CW")
    val bandwidths = listOf("Wide", "Narrow", "Automatic", "Tuner 6KHz", "Tuner 4KHz", "Tuner 3KHz", "Tuner 2KHz", "Tuner 1KHz")
    val txPowers = listOf("N/T", "Zero", "Very Low", "Low", "Medium", "High", "Very High")
    val pttIds = listOf("Off", "BoT", "EoT", "Both")
    val scramblers = listOf("Off") + (2600..4500 step 100).map { "$it Hz" }
    private val ctcssToneCodes = listOf(
        670, 693, 719, 744, 770, 797, 825, 854, 885, 915,
        948, 974, 1000, 1035, 1072, 1109, 1148, 1188, 1230, 1273,
        1318, 1365, 1413, 1462, 1514, 1567, 1598, 1622, 1655, 1679,
        1713, 1738, 1773, 1799, 1835, 1862, 1899, 1928, 1966, 1995,
        2035, 2065, 2107, 2181, 2257, 2291, 2336, 2418, 2503, 2541
    )
    private val dcsToneCodes = listOf(
        0x0013, 0x0015, 0x0016, 0x0019, 0x001A, 0x001E, 0x0023, 0x0027,
        0x0029, 0x002B, 0x002C, 0x0035, 0x0039, 0x003A, 0x003B, 0x003C,
        0x004C, 0x004D, 0x004E, 0x0052, 0x0055, 0x0059, 0x005A, 0x005C,
        0x0063, 0x0065, 0x006A, 0x006D, 0x006E, 0x0072, 0x0075, 0x007A,
        0x007C, 0x0085, 0x008A, 0x0093, 0x0095, 0x0096, 0x00A3, 0x00A4,
        0x00A5, 0x00A6, 0x00A9, 0x00AA, 0x00AD, 0x00B1, 0x00B3, 0x00B5,
        0x00B6, 0x00B9, 0x00BC, 0x00C6, 0x00C9, 0x00CD, 0x00D5, 0x00D9,
        0x00DA, 0x00E3, 0x00E6, 0x00E9, 0x00EE, 0x00F4, 0x00F5, 0x00F9,
        0x0109, 0x010A, 0x010B, 0x0113, 0x0119, 0x011A, 0x0125, 0x0126,
        0x012A, 0x012C, 0x012D, 0x0132, 0x0134, 0x0135, 0x0136, 0x0143,
        0x0146, 0x014E, 0x0153, 0x0156, 0x015A, 0x0166, 0x0175, 0x0186,
        0x018A, 0x0194, 0x0197, 0x0199, 0x019A, 0x01AC, 0x01B2, 0x01B4,
        0x01C3, 0x01CA, 0x01D3, 0x01D9, 0x01DA, 0x01DC, 0x01E3, 0x01EC
    )
    val ctcssTonePresets = ctcssToneCodes.map { String.format(Locale.US, "%.1f", it / 10.0) }
    val dcsTonePresets = dcsToneCodes.map { "D${Integer.toOctalString(it).padStart(3, '0')}" }

    fun emptyChannels(): List<RadioChannel> = (1..CHANNEL_COUNT).map { RadioChannel(it) }

    fun recordOffset(number: Int): Int {
        require(number in 1..CHANNEL_COUNT)
        return (number + 3) * RECORD_SIZE
    }

    fun decodeAll(memory: ByteArray): List<RadioChannel> {
        require(memory.size == MEMORY_SIZE)
        return (1..CHANNEL_COUNT).map { decode(memory, it) }
    }

    fun decode(memory: ByteArray, number: Int): RadioChannel {
        require(memory.size == MEMORY_SIZE)
        val at = recordOffset(number)
        val rx = readInt(memory, at)
        val tx = readInt(memory, at + 4)
        val magic = readUShort(memory, at + 24)
        return RadioChannel(
            number = number,
            active = rx >= 0 && rx < 1300 * 100_000 && magic == CHANNEL_MAGIC,
            name = readString(memory, at + 32, 30),
            rxMHz = rx / 100_000.0,
            txMHz = tx / 100_000.0,
            rxTone = intToTone(readUShort(memory, at + 12)),
            txTone = intToTone(readUShort(memory, at + 14)),
            txPower = memory[at + 21].u8().coerceIn(0, 6),
            groups = intToGroups(readUInt(memory, at + 8)),
            bandwidth = memory[at + 19].u8().coerceIn(0, 7),
            modulation = memory[at + 18].u8().coerceIn(0, 8),
            busyLock = memory[at + 20].u8() != 0,
            reversed = memory[at + 22].u8() != 0,
            pttId = memory[at + 23].u8().coerceIn(0, 3),
            clarifier = readShort(memory, at + 16) / 100.0,
            scrambler = memory[at + 26].u8().coerceIn(0, 20),
            scanHold = memory[at + 27].u8().coerceIn(0, 90) / 10.0,
            scanMonitor = memory[at + 28].u8().coerceIn(0, 99)
        )
    }

    fun encode(memory: ByteArray, channel: RadioChannel) {
        require(memory.size == MEMORY_SIZE)
        val at = recordOffset(channel.number)
        writeInt(memory, at, if (channel.active) (channel.rxMHz * 100_000.0).toInt() else 0)
        writeInt(memory, at + 4, if (channel.active) (channel.txMHz * 100_000.0).toInt() else 0)
        writeUInt(memory, at + 8, groupsToInt(channel.groups))
        writeUShort(memory, at + 12, toneToInt(channel.rxTone))
        writeUShort(memory, at + 14, toneToInt(channel.txTone))
        writeShort(memory, at + 16, (channel.clarifier * 100.0).toInt().coerceIn(-10000, 10000))
        memory[at + 18] = channel.modulation.coerceIn(0, 8).toByte()
        memory[at + 19] = channel.bandwidth.coerceIn(0, 7).toByte()
        memory[at + 20] = if (channel.busyLock) 1 else 0
        memory[at + 21] = channel.txPower.coerceIn(0, 6).toByte()
        memory[at + 22] = if (channel.reversed) 1 else 0
        memory[at + 23] = channel.pttId.coerceIn(0, 3).toByte()
        writeUShort(memory, at + 24, if (channel.active) CHANNEL_MAGIC else 0)
        memory[at + 26] = channel.scrambler.coerceIn(0, 20).toByte()
        memory[at + 27] = (channel.scanHold.coerceIn(0.0, 9.0) * 10.0).toInt().toByte()
        memory[at + 28] = channel.scanMonitor.coerceIn(0, 99).toByte()
        writeString(memory, at + 32, channel.name, 30)
    }

    fun toneToInt(input: String): Int {
        val text = input.trim().uppercase(Locale.ROOT)
        if (text == "NONE" || text.isEmpty()) return 0
        if (text.startsWith("D")) {
            val body = text.drop(1)
            val inverted = body.endsWith("I")
            val digits = body.removeSuffix("I").removeSuffix("N")
            val code = digits.toIntOrNull(8) ?: return 0
            return (code and 0x1FF) or if (inverted) 0xC000 else 0x8000
        }
        val tone = text.toDoubleOrNull()?.takeIf { it in 0.0..300.0 } ?: return 0
        return (tone * 10.0).toInt()
    }

    fun intToTone(code: Int): String {
        val value = code and 0xFFF
        if (value == 0) return "None"
        if (code and 0x8000 == 0) return String.format(Locale.US, "%.1f", (value.coerceAtMost(3000)) / 10.0)
        val octal = Integer.toOctalString(value and 0x1FF).padStart(3, '0')
        return "D$octal${if (code and 0x4000 != 0) "I" else "N"}"
    }

    fun groupsToInt(groups: String): Int {
        val sorted = groups.uppercase(Locale.ROOT).filter { it in 'A'..'Z' }.toSortedSet()
        var result = 0
        sorted.forEach { result = (result shl 8) or (it.code - 64) }
        return result
    }

    fun intToGroups(value: Int): String {
        val letters = mutableListOf<Char>()
        for (i in 0..3) {
            val b = (value ushr (i * 8)) and 0xFF
            if (b in 1..26) letters.add((b + 64).toChar())
        }
        return letters.distinct().sorted().joinToString("")
    }

    private fun Byte.u8() = toInt() and 0xFF
    private fun readInt(bytes: ByteArray, at: Int): Int =
        bytes[at].u8() or (bytes[at + 1].u8() shl 8) or (bytes[at + 2].u8() shl 16) or (bytes[at + 3].u8() shl 24)
    private fun readUInt(bytes: ByteArray, at: Int): Int = readInt(bytes, at)
    private fun readShort(bytes: ByteArray, at: Int): Short = (bytes[at].u8() or (bytes[at + 1].u8() shl 8)).toShort()
    private fun readUShort(bytes: ByteArray, at: Int): Int = bytes[at].u8() or (bytes[at + 1].u8() shl 8)
    private fun writeInt(bytes: ByteArray, at: Int, value: Int) {
        for (i in 0..3) bytes[at + i] = (value ushr (i * 8)).toByte()
    }
    private fun writeUInt(bytes: ByteArray, at: Int, value: Int) = writeInt(bytes, at, value)
    private fun writeShort(bytes: ByteArray, at: Int, value: Int) {
        bytes[at] = value.toByte()
        bytes[at + 1] = (value ushr 8).toByte()
    }
    private fun writeUShort(bytes: ByteArray, at: Int, value: Int) = writeShort(bytes, at, value)
    private fun readString(bytes: ByteArray, at: Int, length: Int): String =
        String(bytes, at, length, StandardCharsets.US_ASCII).map { if (it.code in 32..126) it else ' ' }.joinToString("").trim()
    private fun writeString(bytes: ByteArray, at: Int, value: String, length: Int) {
        val source = value.trim().toByteArray(StandardCharsets.US_ASCII)
        repeat(length) { index -> bytes[at + index] = if (index < source.size && source[index].u8() in 32..126) source[index] else 0 }
    }
}
