"""Build a 3-emoji set: a smiley, a beating heart and a ripple.

    python3 example_set.py              writes example_set.bin (no Bluetooth needed)
    uv run l308.py push example_set.bin

Pushing replaces every emoji on the light, and the light can't send its emojis back,
so anything not in your file is gone. Keep the files you push.
"""
from l308 import still, animation, build_file

SMILEY = ["..######..",
          ".#......#.",
          "#..#..#..#",
          "#..#..#..#",
          "#........#",
          "#.#....#.#",
          "#..#..#..#",
          "#...##...#",
          ".#......#.",
          "..######.."]

HEART = ["..........",
         ".##....##.",
         "####..####",
         "##########",
         "##########",
         ".########.",
         "..######..",
         "...####...",
         "....##....",
         ".........."]

SMALL_HEART = ["..........",
               "..........",
               "..##..##..",
               ".########.",
               ".########.",
               "..######..",
               "...####...",
               "....##....",
               "..........",
               ".........."]


def ring(r):
    """LEDs about r from the centre of the grid."""
    return ["".join("#" if abs(((x - 4.5) ** 2 + (y - 4.5) ** 2) ** 0.5 - r) < 0.6 else "."
                    for x in range(10)) for y in range(10)]


# 10 frames at ~100 ms each, about a 1 s loop.
HEARTBEAT = [HEART, HEART, SMALL_HEART, HEART, HEART] + [SMALL_HEART] * 5
RIPPLE = [ring(r) for r in (0.7, 1.5, 2.5, 3.5, 4.5)] + [["." * 10] * 10] * 2

if __name__ == "__main__":
    blob = build_file([still(9000, SMILEY), animation(9001, HEARTBEAT), animation(9002, RIPPLE)], show=0)
    open("example_set.bin", "wb").write(blob)
    print(f"example_set.bin: {len(blob)} bytes. Indexes: 0 smiley, 1 heart, 2 ripple.")
