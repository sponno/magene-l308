package l308

import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.UUID

/**
 * The L308 over a [GattLink]:
 *
 *     val link = GattLink(context, BleScan.find(context, address))
 *     link.connect()
 *     val light = L308Client(link).apply { setUp() }
 *     val modes = light.modes()
 *     light.select(modes.ids.indexOf(9001L))
 */
class L308Client(private val link: GattLink) {
    private val replies = Channel<ByteArray>(Channel.UNLIMITED)
    private val lock = Mutex()

    init {
        link.onNotify = { _, data -> replies.trySend(data) }
    }

    /** Call once after connect: MTU for 244-byte file packets, services, notifications. */
    suspend fun setUp() {
        link.requestMtu(247)
        link.discoverServices()
        link.enableNotify(Srap.SERVICE, Srap.CONTROL)
        link.enableNotify(Srap.SERVICE, Srap.FILE)
    }

    suspend fun modes(): Srap.Modes =
        Srap.parseModes(request(Srap.readModes(), Srap.CONTROL, Srap.RES_DIRECT, Srap.PAGE_MODES))

    suspend fun select(index: Int) {
        request(Srap.select(index), Srap.CONTROL, Srap.RES_DIRECT, Srap.PAGE_SELECT)
    }

    suspend fun battery(): Int? {
        if (!link.has(Srap.BATTERY_SERVICE, Srap.BATTERY_LEVEL)) return null
        return link.read(Srap.BATTERY_SERVICE, Srap.BATTERY_LEVEL)?.firstOrNull()?.toInt()?.and(0xFF)
    }

    /** Replace the light's whole emoji set with [blob] (a light_mode.bin). */
    suspend fun push(blob: ByteArray) {
        val version = (System.currentTimeMillis() / 1000).toInt()
        val start = request(Srap.fileStart(version), Srap.CONTROL, Srap.RES_FILE, Srap.PAGE_FILE_START)
        if (!Srap.startAccepted(start)) throw IOException("START refused: ${start.hex()}")
        // Packets are 8 header bytes + chunk; fit both the light's mtu and ours.
        val chunk = minOf(Srap.startMtu(start), link.mtu - 3) - 8
        val packets = blob.toList().chunked(chunk).map { it.toByteArray() }
        val crc = Srap.crc16Xmodem(blob)
        val info = request(Srap.fileInfo(blob.size, packets.size, crc), Srap.CONTROL, Srap.RES_FILE, Srap.PAGE_FILE_INFO)
        if (!Srap.infoAccepted(info)) throw IOException("FILE INFO refused: ${info.hex()}")
        Log.i(TAG, "push ${blob.size} bytes in ${packets.size} packets of $chunk, crc %04x".format(crc))
        packets.forEachIndexed { i, p ->
            val ack = request(Srap.fileData(i + 1, p), Srap.FILE, Srap.RES_FILE, Srap.PAGE_FILE_DATA)
            if (!Srap.dataAccepted(ack)) throw IOException("packet ${i + 1} rejected: ${ack.hex()}")
        }
        val done = request(Srap.fileComplete(version), Srap.CONTROL, Srap.RES_FILE, Srap.PAGE_FILE_START)
        Log.i(TAG, "push complete: ${done.hex()}")
    }

    private suspend fun request(payload: ByteArray, char: UUID, resource: Int, page: Int, timeoutMs: Long = 5_000): ByteArray =
        lock.withLock {
            while (replies.tryReceive().isSuccess) Unit
            link.write(Srap.SERVICE, char, payload)
            withTimeout(timeoutMs) {
                var r: ByteArray
                do r = replies.receive() while (!Srap.answers(r, resource, page))
                r
            }
        }

    private fun ByteArray.hex() = joinToString(" ") { "%02x".format(it) }

    private companion object {
        const val TAG = "L308"
    }
}
