# Magene L308 tail light: BLE protocol

Code and notes for controlling the Magene L308 smart tail light directly. You can switch
emojis on demand and load your own pixel art and animations, with no OnelapFit and no Onelap
server.

This is unofficial and not affiliated with, or endorsed by, Magene or Onelap. It was worked
out for interoperability by studying the OnelapFit app and its Bluetooth traffic, and tested
on one light (firmware 0.120). The repo contains no Magene or Onelap code, images or other
files. Product names belong to their owners. It's not a safety device. Use it at your own risk.

## Files

| File | What |
|---|---|
| `PROTOCOL.md` | The protocol: GATT, frames, commands, the emoji file format and how to push it |
| `l308.py` | Python library and command line (bleak) |
| `example_set.py` | Builds a 3-emoji set: a smiley, a beating heart and a ripple |
| `kotlin/Srap.kt`, `kotlin/EmojiFile.kt` | Command frames, reply parsing and the file builder (pure Kotlin) |
| `kotlin/GattLink.kt`, `kotlin/BleScan.kt`, `kotlin/L308Client.kt` | The Android BLE connection and client (coroutines), as used in a Karoo extension |
| `SKILL.md` | Instructions for Claude or another coding agent |

## Quick start (Mac, Linux, Windows)

You need [uv](https://docs.astral.sh/uv/), which installs bleak for you. Close OnelapFit first:
the light takes one connection at a time.

```
git clone https://github.com/sponno/magene-l308 && cd magene-l308
uv run l308.py scan        # find the light
uv run l308.py modes       # emoji ids on it, and which is showing
uv run l308.py show 1      # show the emoji at index 1
uv run l308.py battery
```

Load your own emojis:

```
python3 example_set.py               # writes example_set.bin
uv run l308.py push example_set.bin  # replaces every emoji on the light
```

The light can't send its emojis back to you. A push deletes everything that isn't in your
file, so keep copies of the files you push.

## With Claude Code

Clone it as a skill, and Claude knows the protocol when you ask it to build something for
the light:

```
git clone https://github.com/sponno/magene-l308 ~/.claude/skills/magene-l308
```

Or clone it into `.claude/skills/magene-l308/` in your project.

## Android

Copy `kotlin/` into your app and change `package l308` to your own package. You need the
`kotlinx-coroutines-android` dependency and the `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT`
permissions (Android 12+). The usage example is in `L308Client.kt`.

## License

MIT. See `LICENSE`.
