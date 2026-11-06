# Magene L308 BLE protocol

Unofficial notes, worked out for interoperability by studying the OnelapFit app and its
Bluetooth traffic. Tested on an L308 reporting model 284, firmware 0.120. Everything here is
confirmed on that light unless it is marked *unconfirmed*.

## The light

- A 10×10 LED matrix with the 4 corners missing (96 LEDs).
- It stores up to **15 emojis**: stills or animations of up to 10 frames.
- It shows one emoji at a time. You pick which one over BLE.
- It advertises as `L308_XXXX`.
- It accepts **one BLE connection at a time**. Close OnelapFit, or anything else
  connected to it, before you connect.
- It switches itself off after about 5 minutes idle. When it's switched back on, it
  advertises again.

## GATT

| UUID | Role | Properties |
|---|---|---|
| `8ce5cc01-0a4d-11e9-ab14-d663bd873d93` | service | |
| `8ce5cc05-0a4d-11e9-ab14-d663bd873d93` | control: every command and its reply | write without response, notify |
| `8ce5cc06-0a4d-11e9-ab14-d663bd873d93` | file data: emoji file packets and their acks | write without response, notify |
| `0000180f-…` / `00002a19-…` | standard Battery Service / Battery Level (1 byte, %) | read |

Connection setup:
1. Request ATT MTU 247 (OnelapFit does this; a full file packet is 244 bytes).
2. Enable notifications on **both** cc05 and cc06.
3. Send every command as a **write without response**. Replies arrive as notifications.

## Frames

Every command and reply has the same shape. Multi-byte numbers are **little-endian**.

```
byte 0   0x80 | node     always 0x94 (node 20)
byte 1   function        F1 read, F2 write, F5 reply (F4 = notification from the light)
byte 2   resource        0x23 light settings, 0x01 file transfer
byte 3   page            what the command is about
byte 4…  payload
```

To match a reply to its command, check **bytes 2 and 3 only** (resource and page). Empty
any stale notifications before you send. Send one command at a time and wait for its reply.

## Commands

All of these are written to **cc05**.

| What | Send | Reply |
|---|---|---|
| List emojis | `94 F1 23 E2 01` | `94 F5 23 E2 01 <count> <showing> <id u32 × count>` |
| Show emoji | `94 F2 23 E1 01 01 <index u32>` | page E1 (an echo) |
| Read settings *(partly decoded)* | `94 F1 23 F0 01` | `94 F5 23 F0 01 <brake> <sleep> <auto-off u16> …` |

- **index** is the emoji's position in the list (0-based), **not** its id.
- **showing** is the index currently on the light.
- Switching emoji takes one 10-byte write and happens immediately. To change the emoji from
  your app, load your emojis once, then switch between them.

Example list reply: `94 F5 23 E2 01 03 01 65 00 00 00 8D 23 00 00 8E 23 00 00` means
3 emojis with ids 101, 9101 and 9102; index 1 (id 9101) is showing.

## The emoji file (`light_mode.bin`)

The whole set goes to the light as one file. Pushing it **replaces every emoji** on the light.

```
header   5 bytes    00 01 <count> <index to show> 00
records  count × 186 bytes
```

Each record is 186 bytes, zero padded:

```
id       u32        your choice; OnelapFit's stock emojis use 101–106, server ones e.g. 40591
type     u8         1 = still, 2 = animation
frames   u8         1 for a still, 2–10 for an animation
frame × frames, 18 bytes each:
  5-byte header     still:      12 FF 00 00 00
                    animation:  18 <speed> 00 00 00   speed 0x0A ≈ 100 ms per frame
  13-byte bitmap
```

`4 + 1 + 1 + 10 × 18 = 186`, so a full 10-frame animation fills a record exactly.

### Bitmap

100 bits, 13 bytes. The LED at column `x`, row `y` (0–9, top-left origin) is bit `y*10 + x`,
counted from the least significant bit of byte 0. Put another way, bit `n` is
`byte[n / 8] >> (n % 8) & 1`. The image is drawn **as seen from behind the bike**, not
mirrored. Keep the 4 corner bits (0, 9, 90 and 99) at 0.

```python
def bitmap(rows):            # rows: 10 strings of 10 chars, '#' = on
    v = 0
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch == '#' and (x, y) not in {(0, 0), (9, 0), (0, 9), (9, 9)}:
                v |= 1 << (y * 10 + x)
    return v.to_bytes(13, 'little')
```

### Seen in captures, not decoded

- **Flash/pulse effect** (record type 3, 1 frame). Its frame header is
  `18 <t1> <t2> <max %> <min %>`. OnelapFit's "Pulsing 4 s, 20–80 %" was `18 80 80 50 14`
  and "Flashing 2 s, 20–80 %" was `18 06 06 50 14`. The brightness bytes are confirmed; the
  scale of the timing bytes is *unconfirmed*.
- The still header's first byte (`12`) is what OnelapFit's "Solid 100 %" sends. It may be a
  brightness level (*unconfirmed*).
- Speeds other than `0x0A` are untested. Stock emojis also use `0x08`.

## Pushing the file

The file is cut into packets of `chunk = mtu − 8` bytes. `mtu` is the value the light
returns in step 1 (244 here). If your BLE stack can't send a 244-byte write, use the smaller
size: `chunk = min(light mtu, ATT MTU − 3) − 8`. Chunk sizes other than 236 are untested.

`ver` is any i32. OnelapFit uses the Unix time.

| Step | Char | Send | Reply is OK when |
|---|---|---|---|
| 1. start | cc05 | `94 F2 01 24 01 00 00 00 00 01 01 <ver i32> 00×6` | byte 5 = 0; the light's mtu is bytes 19–20 (u16) |
| 2. file info | cc05 | `94 F2 01 25 0B 00 00 00 00 00 00 <size u32> <packets u16> <crc u16> 00 00 00 00 "light_mode.bin"` | byte 7 = 0 |
| 3. data, each packet | **cc06** | `94 F2 01 26 <n u16, from 1> <len u16> <chunk bytes>` | ack on page 26: byte 8 = 0 |
| 4. complete | cc05 | `94 F2 01 24 02 00 00 00 00 01 01 <ver i32> 00×6` | reply on page 24 |

- Wait for each packet's ack before sending the next one.
- **crc** is CRC-16/XMODEM over the whole file: poly 0x1021, init 0, no reflection, no final
  XOR. `"123456789"` gives `0x31C3`.
- After step 4, wait about 1 s before listing the emojis again. The light then shows the
  emoji at the header's "index to show".
- A 15-emoji file is 2795 bytes, which is 12 packets.

Packet ack error codes (byte 8): 1 bad packet index, 2 CRC error, 3 bad file size, 4 file
operation failed, 5 over the maximum, 6 light-side error, 0x20 cancelled by the light.
There's no abort command. To give up, stop sending; the light times out on its own.

## Things that will bite you

- **You can't read emojis back.** Listing gives ids only, not images. Keep your own copy of
  every file you push. A push that leaves out an emoji deletes it.
- **OnelapFit overwrites custom emojis.** When it syncs, it re-downloads every id on the light
  from Onelap's server. Ids it doesn't know come back as an all-LEDs-on block. If you use
  both, push your file again after OnelapFit has connected.
- **15 emojis maximum** per file.
- **One connection at a time.** While your app holds the light, OnelapFit can't see it,
  and the other way round.
- **Restore the rider's emoji.** If your app switches emoji, remember the index
  that was showing and switch back afterwards, including when your app disconnects.
