package l308

/**
 * Builds the light_mode.bin the light keeps its emojis in (see PROTOCOL.md). Pure Kotlin.
 * Images are 10 strings of 10 chars, '#' = LED on, drawn as seen from behind the bike.
 *
 *     val file = EmojiFile.build(listOf(EmojiFile.still(9000, smiley), EmojiFile.animation(9001, frames)))
 *     light.push(file)   // replaces every emoji on the light
 */
object EmojiFile {
    const val MAX_EMOJIS = 15
    const val MAX_FRAMES = 10
    private val CORNERS = setOf(0, 9, 90, 99)

    /** 13 bytes: LED (x, y) is bit y*10+x, least significant bit first. */
    fun bitmap(rows: List<String>): ByteArray {
        require(rows.size == 10 && rows.all { it.length == 10 }) { "need 10 rows of 10 chars" }
        val out = ByteArray(13)
        for (y in 0 until 10) for (x in 0 until 10) {
            val bit = y * 10 + x
            if (rows[y][x] == '#' && bit !in CORNERS) {
                out[bit / 8] = (out[bit / 8].toInt() or (1 shl (bit % 8))).toByte()
            }
        }
        return out
    }

    fun still(id: Long, rows: List<String>): ByteArray =
        record(id, type = 1, frames = listOf(byteArrayOf(0x12, 0xFF.toByte(), 0, 0, 0) + bitmap(rows)))

    /** 2-10 frames, looped. [speed] 0x0A is about 100 ms per frame. */
    fun animation(id: Long, frames: List<List<String>>, speed: Int = 0x0A): ByteArray {
        require(frames.size in 2..MAX_FRAMES) { "2-10 frames" }
        return record(id, type = 2, frames = frames.map { byteArrayOf(0x18, speed.toByte(), 0, 0, 0) + bitmap(it) })
    }

    /** The file to push. [show] is the index the light displays afterwards. */
    fun build(records: List<ByteArray>, show: Int = 0): ByteArray {
        require(records.size in 1..MAX_EMOJIS) { "the light holds 1-$MAX_EMOJIS emojis" }
        require(show in records.indices) { "show must be an index into records" }
        return records.fold(byteArrayOf(0, 1, records.size.toByte(), show.toByte(), 0)) { acc, r -> acc + r }
    }

    private fun record(id: Long, type: Int, frames: List<ByteArray>): ByteArray {
        val out = ByteArray(Srap.RECORD_SIZE)
        for (k in 0 until 4) out[k] = (id shr (8 * k)).toByte()
        out[4] = type.toByte()
        out[5] = frames.size.toByte()
        frames.forEachIndexed { i, f -> f.copyInto(out, 6 + 18 * i) }
        return out
    }
}
