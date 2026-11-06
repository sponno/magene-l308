#!/usr/bin/env python3
# /// script
# requires-python = ">=3.9"
# dependencies = ["bleak>=0.22"]
# ///
"""Magene L308 tail light: build emoji files and control the light over BLE.

Unofficial. See PROTOCOL.md for the byte-level details.

    uv run l308.py scan               lights in range
    uv run l308.py modes              emoji ids on the light, and which one is showing
    uv run l308.py show 2             show the emoji at index 2
    uv run l308.py battery            battery %
    uv run l308.py push my_set.bin    replace the whole emoji set (see example_set.py)

Add --name L308_XXXX when there's more than one light around.
"""
import argparse, asyncio, struct, time

CONTROL = "8ce5cc05-0a4d-11e9-ab14-d663bd873d93"  # commands and replies
FILE = "8ce5cc06-0a4d-11e9-ab14-d663bd873d93"     # emoji file packets and acks
BATTERY = "00002a19-0000-1000-8000-00805f9b34fb"

NODE = 0x94
READ, WRITE = 0xF1, 0xF2
RES_LIGHT, RES_FILE = 0x23, 0x01
PAGE_SHOW, PAGE_MODES = 0xE1, 0xE2
PAGE_TRANSFER, PAGE_INFO, PAGE_DATA = 0x24, 0x25, 0x26

RECORD_SIZE = 186
MAX_EMOJIS = 15
MAX_FRAMES = 10
CORNERS = {(0, 0), (9, 0), (0, 9), (9, 9)}


# ---- emoji file -------------------------------------------------------------

def bitmap(rows):
    """10 strings of 10 chars, '#' = on, drawn as seen from behind the bike. Returns 13 bytes."""
    assert len(rows) == 10 and all(len(r) == 10 for r in rows), "need 10 rows of 10 chars"
    v = 0
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch == "#" and (x, y) not in CORNERS:
                v |= 1 << (y * 10 + x)
    return v.to_bytes(13, "little")


def still(emoji_id, rows):
    """A still image record."""
    body = struct.pack("<IBB", emoji_id, 1, 1) + bytes([0x12, 0xFF, 0, 0, 0]) + bitmap(rows)
    return body.ljust(RECORD_SIZE, b"\0")


def animation(emoji_id, frames, speed=0x0A):
    """An animation record: 2-10 frames, looped. speed 0x0A is about 100 ms per frame."""
    assert 2 <= len(frames) <= MAX_FRAMES, "2-10 frames"
    body = struct.pack("<IBB", emoji_id, 2, len(frames))
    body += b"".join(bytes([0x18, speed, 0, 0, 0]) + bitmap(f) for f in frames)
    return body.ljust(RECORD_SIZE, b"\0")


def build_file(records, show=0):
    """The light_mode.bin to push. show = index of the emoji to display afterwards."""
    assert 1 <= len(records) <= MAX_EMOJIS, f"the light holds 1-{MAX_EMOJIS} emojis"
    assert 0 <= show < len(records)
    return bytes([0x00, 0x01, len(records), show, 0x00]) + b"".join(records)


def file_ids(blob):
    """Emoji ids in a light_mode.bin, in order."""
    return [struct.unpack_from("<I", blob, 5 + RECORD_SIZE * i)[0] for i in range(blob[2])]


def crc16_xmodem(data):
    c = 0
    for b in data:
        c ^= b << 8
        for _ in range(8):
            c = ((c << 1) ^ 0x1021) & 0xFFFF if c & 0x8000 else (c << 1) & 0xFFFF
    return c


# ---- BLE --------------------------------------------------------------------

async def scan(timeout=10):
    """(name, address) of every L308 advertising nearby."""
    from bleak import BleakScanner
    found = await BleakScanner.discover(timeout=timeout)
    return [(d.name, d.address) for d in found if (d.name or "").startswith("L308")]


class L308:
    """
    async with L308() as light:
        showing, ids = await light.modes()
        await light.show(ids.index(9001))
    """

    def __init__(self, name=None, timeout=30):
        self.name, self.timeout = name, timeout
        self.replies = asyncio.Queue()

    async def __aenter__(self):
        from bleak import BleakClient, BleakScanner
        wanted = (lambda d, _: d.name == self.name) if self.name else \
                 (lambda d, _: (d.name or "").startswith("L308"))
        device = await BleakScanner.find_device_by_filter(wanted, timeout=self.timeout)
        if device is None:
            raise SystemExit("No L308 found. Switch it on, and close OnelapFit or anything else connected to it.")
        self.ble = BleakClient(device)
        await self.ble.connect()
        on_reply = lambda _, data: self.replies.put_nowait(bytes(data))
        await self.ble.start_notify(CONTROL, on_reply)
        await self.ble.start_notify(FILE, on_reply)
        return self

    async def __aexit__(self, *_):
        await self.ble.disconnect()

    async def request(self, frame, char=CONTROL, timeout=5):
        """Write one frame and return the reply with the same resource and page (bytes 2-3)."""
        while not self.replies.empty():
            self.replies.get_nowait()
        await self.ble.write_gatt_char(char, frame, response=False)
        deadline = time.monotonic() + timeout
        while True:
            reply = await asyncio.wait_for(self.replies.get(), max(0.05, deadline - time.monotonic()))
            if len(reply) >= 4 and reply[2] == frame[2] and reply[3] == frame[3]:
                return reply

    async def modes(self):
        """(index showing, [emoji ids in order])."""
        r = await self.request(bytes([NODE, READ, RES_LIGHT, PAGE_MODES, 0x01]))
        count, showing = r[5], r[6]
        return showing, [struct.unpack_from("<I", r, 7 + 4 * i)[0] for i in range(count)]

    async def show(self, index):
        """Show the emoji at this position in the list (not its id)."""
        await self.request(bytes([NODE, WRITE, RES_LIGHT, PAGE_SHOW, 0x01, 0x01]) + struct.pack("<I", index))

    async def battery(self):
        return (await self.ble.read_gatt_char(BATTERY))[0]

    async def push(self, blob, log=print):
        """Replace every emoji on the light with the ones in blob (from build_file)."""
        ver = struct.pack("<i", int(time.time()))
        r = await self.request(bytes([NODE, WRITE, RES_FILE, PAGE_TRANSFER, 0x01, 0, 0, 0, 0, 1, 1]) + ver + bytes(6))
        if r[5] != 0:
            raise RuntimeError(f"start refused: {r.hex(' ')}")
        light_mtu = struct.unpack_from("<H", r, 19)[0] if len(r) >= 21 else 23
        max_write = self.ble.services.get_characteristic(FILE).max_write_without_response_size
        chunk = min(light_mtu, max_write) - 8
        packets = [blob[i:i + chunk] for i in range(0, len(blob), chunk)]
        crc = crc16_xmodem(blob)
        info = bytes([NODE, WRITE, RES_FILE, PAGE_INFO, 0x0B, 0, 0, 0, 0, 0, 0]) + \
            struct.pack("<IHH", len(blob), len(packets), crc) + bytes(4) + b"light_mode.bin"
        r = await self.request(info)
        if r[7] != 0:
            raise RuntimeError(f"file info refused: {r.hex(' ')}")
        log(f"{len(blob)} bytes in {len(packets)} packets of {chunk}, crc {crc:04x}")
        for n, p in enumerate(packets, 1):
            r = await self.request(bytes([NODE, WRITE, RES_FILE, PAGE_DATA]) + struct.pack("<HH", n, len(p)) + p, char=FILE)
            if r[8] != 0:
                raise RuntimeError(f"packet {n} rejected (error {r[8]}): {r.hex(' ')}")
        await self.request(bytes([NODE, WRITE, RES_FILE, PAGE_TRANSFER, 0x02, 0, 0, 0, 0, 1, 1]) + ver + bytes(6))
        await asyncio.sleep(1)  # let the light load the new set


# ---- command line -----------------------------------------------------------

async def main():
    ap = argparse.ArgumentParser(description="Magene L308 tail light over BLE")
    ap.add_argument("--name", help="advertised name, e.g. L308_1A2B (default: first L308 found)")
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("scan", help="list lights in range")
    sub.add_parser("modes", help="emoji ids on the light")
    sub.add_parser("battery", help="battery %%")
    sub.add_parser("show", help="show the emoji at an index").add_argument("index", type=int)
    sub.add_parser("push", help="replace the emoji set with a .bin file").add_argument("file")
    a = ap.parse_args()

    if a.cmd == "scan":
        for name, address in await scan():
            print(name, address)
        return
    async with L308(a.name) as light:
        if a.cmd == "modes":
            showing, ids = await light.modes()
            for i, emoji_id in enumerate(ids):
                print(f"{i:2}  id {emoji_id}{'  <- showing' if i == showing else ''}")
        elif a.cmd == "battery":
            print(f"{await light.battery()}%")
        elif a.cmd == "show":
            await light.show(a.index)
        elif a.cmd == "push":
            blob = open(a.file, "rb").read()
            await light.push(blob)
            print("now on the light:", (await light.modes())[1])


if __name__ == "__main__":
    asyncio.run(main())
