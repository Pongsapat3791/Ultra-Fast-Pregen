"""Draws the icons of Ultra Fast Pregen, its Manager and Link as pixel art, on a 64 x 64 grid like a Minecraft texture.

Usage: make_icons.py <output folder>
Writes <name>-128.png and <name>-512.png for ultrafastpregen, ufpmanager and ufplink; the 128 ones go into the mods' jars,
the 512 ones to mod sites. Python's standard library only."""
import os
import struct
import sys
import zlib

SIZE = 64
BACK = (24, 28, 25)
EDGE = (56, 66, 58)
GREEN = (92, 196, 134)
GREEN_DARK = (47, 125, 79)
GREEN_DEEP = (34, 84, 56)
WIRE = (64, 156, 102)
EMPTY = (39, 45, 40)
WHITE = (240, 244, 238)
SHADOW = (12, 14, 12)


class Canvas:
    def __init__(self):
        self.pixels = [[None] * SIZE for _ in range(SIZE)]

    def dot(self, x, y, color):
        if 0 <= x < SIZE and 0 <= y < SIZE:
            self.pixels[y][x] = color

    def rect(self, x0, y0, x1, y1, color):
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                self.dot(x, y, color)

    def line(self, x0, y0, x1, y1, color, thick=2):
        steps = max(abs(x1 - x0), abs(y1 - y0))
        for i in range(steps + 1):
            x = round(x0 + (x1 - x0) * i / steps)
            y = round(y0 + (y1 - y0) * i / steps)
            self.rect(x, y, x + thick - 1, y + thick - 1, color)

    def tile(self):
        """The rounded dark tile both icons stand on."""
        corner = 7
        for y in range(SIZE):
            for x in range(SIZE):
                dx = max(corner - x, x - (SIZE - 1 - corner), 0)
                dy = max(corner - y, y - (SIZE - 1 - corner), 0)
                distance = dx * dx + dy * dy
                if distance > corner * corner:
                    continue
                inner = max(corner - 2, 1)
                edge = distance > inner * inner or x < 2 or y < 2 or x > SIZE - 3 or y > SIZE - 3
                self.pixels[y][x] = EDGE if edge else BACK

    def png(self, scale):
        side = SIZE * scale
        rows = bytearray()
        for y in range(SIZE):
            row = bytearray()
            for x in range(SIZE):
                color = self.pixels[y][x]
                row += bytes(scale * (color + (255,) if color else (0, 0, 0, 0)))
            rows += (b"\0" + row) * scale

        def chunk(tag, data):
            return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data))

        return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", side, side, 8, 6, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(bytes(rows), 9)) + chunk(b"IEND", b""))


def speckle(x, y, a, b, seed=0):
    """A texture of two shades, always the same."""
    return a if (x * 7 + y * 13 + (x * y) % 5 + seed) % 5 else b


def cube(canvas, center, top, half, height):
    """A grass block seen from above the corner: `half` is half the height of its top face."""
    grass = (112, 182, 74)
    grass_dark = (88, 152, 58)
    grass_side = (94, 158, 62)
    dirt = (134, 96, 67)
    dirt_dark = (106, 74, 50)
    dirt_right = (104, 74, 52)
    dirt_right_dark = (84, 58, 40)
    for row in range(2 * half):
        width = 2 * (row + 1) if row < half else 2 * (2 * half - row)
        for x in range(center - width, center + width):
            canvas.dot(x, top + row, speckle(x, row, grass, grass_dark))
    for step in range(2 * half):
        edge = top + 2 * half - 1 - step // 2
        for y in range(height):
            fringe = y < 3 or (y == 3 and step % 3 == 0)
            canvas.dot(center - 1 - step, edge + y + 1, grass_side if fringe else speckle(step, y, dirt, dirt_dark))
            canvas.dot(center + step, edge + y + 1, grass_dark if fringe else speckle(step, y, dirt_right, dirt_right_dark, 2))


def manager():
    """A browser window with a map of chunks in it, some generated, and the pointer that started the run."""
    c = Canvas()
    c.tile()
    c.rect(7, 10, 56, 53, SHADOW)
    c.rect(8, 11, 55, 52, (47, 54, 48))
    c.rect(8, 11, 55, 17, (70, 80, 72))
    for x, color in ((11, (224, 99, 122)), (15, (217, 178, 60)), (19, GREEN)):
        c.rect(x, 13, x + 1, 15, color)
    c.rect(24, 13, 52, 15, (47, 54, 48))
    generated = {(0, 3): 1, (1, 3): 2, (2, 3): 2, (0, 2): 1, (1, 2): 2, (2, 2): 3, (3, 2): 2, (1, 1): 1, (2, 1): 2, (3, 1): 2, (4, 1): 1,
                 (2, 0): 1, (3, 0): 1}
    shades = {0: EMPTY, 1: GREEN_DEEP, 2: GREEN_DARK, 3: GREEN}
    for row in range(4):
        for column in range(5):
            x = 10 + column * 9
            y = 19 + row * 8
            c.rect(x, y, x + 7, y + 6, shades[generated.get((column, row), 0)])
    arrow = ["W.........", "WW........", "W*W.......", "W**W......", "W***W.....", "W****W....", "W*****W...", "W******W..", "W*******W.", "W********W",
             "W*****WWWW", "W**W**W...", "W*W.W**W..", "WW..W**W..", ".....W**W.", ".....W**W.", "......WW.."]
    for dy, row in enumerate(arrow):
        for dx, mark in enumerate(row):
            x, y = 36 + dx, 41 + dy
            if mark == "W":
                c.dot(x, y, SHADOW)
            elif mark == "*":
                c.dot(x, y, WHITE)
    return c


def link():
    """Grass blocks joined by lines: the host above, two clients below, and a chunk on its way along each line."""
    c = Canvas()
    c.tile()
    c.line(25, 22, 15, 42, WIRE, 3)
    c.line(37, 22, 47, 42, WIRE, 3)
    cube(c, 32, 6, 7, 9)
    cube(c, 15, 38, 5, 7)
    cube(c, 49, 38, 5, 7)
    for x, y in ((19, 33), (43, 33)):
        c.rect(x - 2, y - 2, x + 3, y + 3, SHADOW)
        c.rect(x - 1, y - 1, x + 2, y + 2, GREEN)
        c.rect(x, y, x + 1, y + 1, WHITE)
    return c


def tree(canvas, x, base, leaf, leaf_dark, leaf_light, small=False):
    """A tree on the island: `x` is its trunk, `base` the row it stands on."""
    canvas.rect(x, base - 3, x + 1, base, (112, 74, 44))
    width, height = (2, 4) if small else (3, 6)
    top = base - 2 - height
    for y in range(height):
        reach = width if 0 < y < height - 1 else width - 1
        for dx in range(-reach, reach + 2):
            color = leaf_light if y < 2 and dx < 1 else leaf_dark if y >= height - 2 or dx > reach - 1 else leaf
            canvas.dot(x + dx, top + y, speckle(dx, y, color, leaf_dark))


def pregen():
    """The world as it is generated: a floating island, real terrain on the left and the green grid of chunks on the right."""
    c = Canvas()
    c.tile()
    center, top, half = 32, 9, 14
    grass = (112, 182, 74)
    grass_dark = (88, 152, 58)
    water = (64, 120, 226)
    water_light = (118, 170, 244)
    grid = (70, 214, 140)
    grid_line = (150, 246, 196)
    glow = (214, 255, 234)
    # What hangs under the island: a column of each face is as deep as this, from the middle out.
    depths = (13, 15, 15, 13, 14, 12, 13, 11, 12, 12, 10, 11, 11, 9, 10, 10, 8, 9, 9, 7, 8, 8, 6, 7, 7, 5, 6, 5)
    for step in range(2 * half):
        edge = top + 2 * half - 1 - step // 2
        for y in range(depths[step]):
            left_stone = y > depths[step] * 0.55
            right_stone = (step + y * 2) % 11 < 2 or y > depths[step] * 0.72
            fringe = y < 2 or (y == 2 and step % 3 == 0)
            left = grass_dark if fringe else speckle(step, y, (92, 88, 128), (74, 72, 108)) if left_stone                 else speckle(step, y, (134, 96, 67), (106, 74, 50))
            right = (84, 190, 92) if fringe else speckle(step, y, (138, 134, 132), (108, 104, 104)) if right_stone                 else speckle(step, y, (196, 132, 74), (168, 106, 56), 2)
            c.dot(center - 1 - step, edge + y + 1, left)
            c.dot(center + step, edge + y + 1, right)
    for row in range(2 * half):
        width = 2 * (row + 1) if row < half else 2 * (2 * half - row)
        for x in range(center - width, center + width):
            if x < center:
                c.dot(x, top + row, speckle(x, row, grass, grass_dark))
                continue
            line = (2 * row - (x - center)) % 12 < 2 or (2 * row + (x - center)) % 12 < 2
            edge = width - (x - center) <= 2 or row == 2 * half - 1
            c.dot(x, top + row, glow if edge else grid_line if line else grid)
    # The river, and where it falls off the front of the island.
    for x0, y0, x1, y1 in ((28, 13, 22, 22), (22, 22, 26, 30), (26, 30, 28, 36)):
        c.line(x0, y0, x1, y1, water, 3)
    for x, y in ((25, 18), (24, 23), (25, 27), (28, 34), (23, 21)):
        c.dot(x, y, water_light)
    c.rect(27, 37, 29, 47, water)
    c.rect(28, 38, 28, 45, water_light)
    leaf, leaf_dark, leaf_light = (70, 150, 56), (46, 112, 44), (104, 186, 72)
    tree(c, 19, 16, (200, 72, 72), (156, 48, 56), (232, 112, 104))
    tree(c, 11, 24, leaf, leaf_dark, leaf_light)
    tree(c, 27, 23, leaf, leaf_dark, leaf_light, small=True)
    tree(c, 16, 31, leaf, leaf_dark, leaf_light, small=True)
    # The line between what is done and what is being made, and the chunks flying in.
    c.rect(32, top - 3, 32, top + 2 * half + 13, glow)
    for x, y, color in ((39, 6, GREEN), (45, 10, grid_line), (53, 13, GREEN), (48, 5, GREEN), (57, 19, grid_line), (9, 12, (200, 72, 72)),
                        (27, 6, leaf_light)):
        c.rect(x, y, x + 1, y + 1, color)
    return c


def main():
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    for name, canvas in (("ultrafastpregen", pregen()), ("ufpmanager", manager()), ("ufplink", link())):
        for side in (128, 512):
            with open(os.path.join(out, "%s-%d.png" % (name, side)), "wb") as f:
                f.write(canvas.png(side // SIZE))


if __name__ == "__main__":
    main()
