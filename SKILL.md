---
name: magene-l308
description: Control a Magene L308 smart bike tail light over Bluetooth LE - switch its emoji, load custom pixel art or animations, read its battery. Use when writing code that talks to an L308 (Python, Android/Karoo, or anything with BLE).
---

# Magene L308 tail light

The protocol is in `PROTOCOL.md` next to this file. It's unofficial, worked out from BLE
captures. Read it before writing code. Working code to copy or port:

- `l308.py`: Python/bleak. The `L308` client (`modes`, `show`, `battery`, `push`) and the file
  builders `still`, `animation`, `build_file`.
- `kotlin/`: Android.
  - `Srap.kt`: frames.
  - `EmojiFile.kt`: the file builder.
  - `GattLink.kt`, `BleScan.kt`, `L308Client.kt`: BLE.

## Easy to get wrong

- Request MTU 247, then enable notifications on **both** cc05 and cc06.
- Send every write **without response**. Replies arrive as notifications.
- Commands go to cc05. Emoji file data packets go to cc06.
- Match a reply on bytes 2-3 (resource, page) only. Keep one command in flight, and wait
  for each reply or packet ack.
- "Show emoji" takes the **index** in the light's list, not the emoji id. Read the list
  (`modes`) and look the id up.
- A push **replaces the whole set** (15 emojis maximum), and the light can't send images back.
  Before pushing to someone's light, tell them that what's on it will be replaced. Keep a
  copy of every file pushed.
- To change the emoji from an app, load the emojis once, then switch with "show". Afterwards,
  switch back to the index that was showing, including when your app disconnects.
- The light takes one BLE connection at a time, so OnelapFit must be closed. When OnelapFit
  syncs, it replaces emoji ids it doesn't know with an all-on block.
- Images are drawn as seen from behind the bike. The 4 corner LEDs don't exist.
- File packet chunk = min(light mtu from the START reply, ATT MTU - 3) - 8.

## Testing

- Building files needs no light. Compare the bytes with what `l308.py` produces, e.g. run
  `python3 example_set.py` and diff.
- On a real light, `uv run l308.py modes` and `battery` are read-only and safe.
