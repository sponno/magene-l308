package l308

import java.util.UUID

/**
 * Magene L308 command frames and reply parsing. Pure Kotlin, no Android.
 * Frames: [node] [F1 read | F2 write | F5 reply] [resource] [page] payload.
 * See PROTOCOL.md for the byte-level notes.
 */
object Srap {
    val SERVICE: UUID = UUID.fromString("8ce5cc01-0a4d-11e9-ab14-d663bd873d93")
    val CONTROL: UUID = UUID.fromString("8ce5cc05-0a4d-11e9-ab14-d663bd873d93")
    val FILE: UUID = UUID.fromString("8ce5cc06-0a4d-11e9-ab14-d663bd873d93")
    val BATTERY_SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    val BATTERY_LEVEL: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

    const val RES_DIRECT = 0x23
    const val RES_FILE = 0x01
    const val PAGE_SELECT = 0xE1
    const val PAGE_MODES = 0xE2
    const val PAGE_FILE_START = 0x24
    const val PAGE_FILE_INFO = 0x25
    const val PAGE_FILE_DATA = 0x26

    private const val NODE = 0x94
    private const val READ = 0xF1
    private const val WRITE = 0xF2
    const val RECORD_SIZE = 186

    /** [current] is the index showing; [ids] are the emoji ids in order. */
    data class Modes(val current: Int, val ids: List<Long>)

    fun readModes() = bytes(NODE, READ, RES_DIRECT, PAGE_MODES, 0x01)

    /** Show the emoji at [index] in the list (not its id). */
    fun select(index: Int) = bytes(NODE, WRITE, RES_DIRECT, PAGE_SELECT, 0x01, 0x01) + u32(index.toLong())

    fun parseModes(r: ByteArray): Modes {
        val n = r.u8(5)
        return Modes(current = r.u8(6), ids = List(n) { r.u32(7 + 4 * it) })
    }

    /** A reply belongs to a command when its resource and page (bytes 2-3) match. */
    fun answers(reply: ByteArray, resource: Int, page: Int) =
        reply.size >= 4 && reply.u8(2) == resource && reply.u8(3) == page

    fun fileStart(version: Int) =
        bytes(NODE, WRITE, RES_FILE, PAGE_FILE_START, 0x01, 0, 0, 0, 0, 1, 1) + i32(version) + ByteArray(6)

    fun fileComplete(version: Int) =
        bytes(NODE, WRITE, RES_FILE, PAGE_FILE_START, 0x02, 0, 0, 0, 0, 1, 1) + i32(version) + ByteArray(6)

    fun fileInfo(size: Int, packets: Int, crc: Int, name: String = "light_mode.bin") =
        bytes(NODE, WRITE, RES_FILE, PAGE_FILE_INFO, 0x0B, 0, 0, 0, 0, 0, 0) +
            u32(size.toLong()) + u16(packets) + u16(crc) + ByteArray(4) + name.toByteArray()

    /** [index] is 1-based. */
    fun fileData(index: Int, chunk: ByteArray) =
        bytes(NODE, WRITE, RES_FILE, PAGE_FILE_DATA) + u16(index) + u16(chunk.size) + chunk

    /** START reply: byte 5 is 0 when accepted; the light's mtu sits at bytes 19-20. */
    fun startAccepted(r: ByteArray) = r.size > 5 && r.u8(5) == 0
    fun startMtu(r: ByteArray) = if (r.size >= 21) r.u8(19) or (r.u8(20) shl 8) else 23
    fun infoAccepted(r: ByteArray) = r.size > 7 && r.u8(7) == 0
    fun dataAccepted(r: ByteArray) = r.size > 8 && r.u8(8) == 0

    /** Ids of the records in a light_mode.bin file. */
    fun fileIds(blob: ByteArray): List<Long> = List(blob.u8(2)) { blob.u32(5 + RECORD_SIZE * it) }

    fun crc16Xmodem(data: ByteArray): Int {
        var c = 0
        for (b in data) {
            c = c xor ((b.toInt() and 0xFF) shl 8)
            repeat(8) { c = if (c and 0x8000 != 0) ((c shl 1) xor 0x1021) and 0xFFFF else (c shl 1) and 0xFFFF }
        }
        return c
    }

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun u16(v: Int) = bytes(v and 0xFF, (v shr 8) and 0xFF)
    private fun u32(v: Long) = ByteArray(4) { ((v shr (8 * it)) and 0xFF).toByte() }
    private fun i32(v: Int) = u32(v.toLong() and 0xFFFFFFFFL)
    private fun ByteArray.u8(i: Int) = this[i].toInt() and 0xFF
    private fun ByteArray.u32(i: Int) = (0 until 4).fold(0L) { acc, k -> acc or ((this[i + k].toLong() and 0xFF) shl (8 * k)) }
}
