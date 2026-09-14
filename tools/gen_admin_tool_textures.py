"""Generates 16x16 pixel-art textures for the Straja admin tool items.

Run: python tools/gen_admin_tool_textures.py
Writes PNGs into src/main/resources/assets/straja/textures/item/.
"""
import os
from PIL import Image

OUT = os.path.join(os.path.dirname(__file__), "..",
                   "src/main/resources/assets/straja/textures/item")

T = None  # transparent


def canvas():
    return [[T] * 16 for _ in range(16)]


def put(img, x, y, c):
    if 0 <= x < 16 and 0 <= y < 16 and c is not None:
        img[y][x] = c


def line(img, x0, y0, x1, y1, c, w=1):
    """Bresenham line; w=2 adds a perpendicular thickness pixel."""
    dx, dy = abs(x1 - x0), -abs(y1 - y0)
    sx = 1 if x0 < x1 else -1
    sy = 1 if y0 < y1 else -1
    err = dx + dy
    x, y = x0, y0
    while True:
        put(img, x, y, c)
        if w == 2:
            put(img, x + 1, y, c) if dx > -dy else put(img, x, y + 1, c)
        if x == x1 and y == y1:
            break
        e2 = 2 * err
        if e2 >= dy:
            err += dy
            x += sx
        if e2 <= dx:
            err += dx
            y += sy


def save(img, name):
    out = Image.new("RGBA", (16, 16))
    for y in range(16):
        for x in range(16):
            if img[y][x] is not None:
                out.putpixel((x, y), img[y][x])
    path = os.path.join(OUT, name)
    out.save(path)
    print("wrote", path)


# ---- palette -------------------------------------------------------------
WOOD_DARK = (61, 43, 26, 255)
WOOD = (107, 79, 42, 255)
WOOD_LIGHT = (139, 105, 60, 255)
GOLD = (255, 215, 94, 255)
GOLD_LIGHT = (255, 240, 160, 255)
WHITE = (255, 255, 255, 255)
BLUE = (46, 92, 176, 255)        # straja blue for patrol
BLUE_DARK = (30, 60, 120, 255)
TEAL = (64, 190, 178, 255)       # survey teal
TEAL_DARK = (36, 120, 112, 255)
IRON = (160, 160, 168, 255)
IRON_DARK = (96, 96, 104, 255)
MAGENTA = (190, 84, 200, 255)    # cloner
MAGENTA_DARK = (130, 50, 140, 255)
SKIN = (222, 171, 127, 255)
SKIN_DARK = (171, 116, 78, 255)
RED = (200, 60, 60, 255)         # prison/cell boundary
RED_DARK = (130, 36, 36, 255)
OLIVE = (168, 160, 48, 255)      # room boundary (matches existing tone)
OLIVE_DARK = (110, 104, 30, 255)


def boundary_marker(main, dark):
    """Selection-frame marker: four corner brackets + center dot."""
    img = canvas()
    for cx, cy in [(3, 3), (12, 3), (3, 12), (12, 12)]:
        for dx, dy in [(0, 0), (1 if cx < 8 else -1, 0), (2 if cx < 8 else -2, 0),
                       (0, 1 if cy < 8 else -1), (0, 2 if cy < 8 else -2)]:
            put(img, cx + dx, cy + dy, main)
    # inner dashed diagonals suggesting the bounded volume
    for x, y in [(6, 6), (9, 9), (9, 6), (6, 9)]:
        put(img, x, y, dark)
    put(img, 7, 7, main)
    put(img, 8, 8, main)
    put(img, 8, 7, dark)
    put(img, 7, 8, dark)
    return img


def npc_wand():
    """Classic CustomNPCs-style wand: dark shaft, golden star tip."""
    img = canvas()
    line(img, 2, 13, 10, 5, WOOD_DARK, 2)
    line(img, 2, 13, 10, 5, WOOD, 1)
    # collar at the head
    for x, y in [(10, 4), (11, 4), (10, 5), (11, 5)]:
        put(img, x, y, IRON_DARK)
    # star tip: clean four-point star with a bright core
    for x, y in [(12, 1), (11, 2), (12, 2), (13, 2), (10, 3), (11, 3),
                 (12, 3), (13, 3), (14, 3), (11, 4), (12, 4), (13, 4),
                 (12, 5)]:
        put(img, x, y, GOLD)
    put(img, 12, 3, WHITE)
    put(img, 12, 2, GOLD_LIGHT)
    put(img, 11, 3, GOLD_LIGHT)
    return img


def patrol_wand():
    """Route-marking wand: wooden pole with a blue Straja banner + dot."""
    img = canvas()
    line(img, 3, 14, 9, 8, WOOD_DARK, 2)
    line(img, 3, 14, 9, 8, WOOD, 1)
    line(img, 9, 8, 11, 6, IRON, 1)
    # banner: blue pennant hanging off the top of the pole
    for x in range(11, 15):
        put(img, x, 5, BLUE)
        put(img, x, 6, BLUE)
    for x in range(11, 14):
        put(img, x, 7, BLUE)
    put(img, 11, 8, BLUE)
    put(img, 11, 4, BLUE_DARK)
    put(img, 12, 5, WHITE)  # waypoint dot on the banner
    put(img, 13, 7, BLUE_DARK)
    return img


def survey_rod():
    """Surveyor's rod: teal shaft with a crosshair sight at the top."""
    img = canvas()
    line(img, 4, 14, 10, 8, TEAL_DARK, 2)
    line(img, 4, 14, 10, 8, TEAL, 1)
    # sight: a small square frame with a light core at the top
    for x, y in [(10, 3), (11, 3), (12, 3), (10, 4), (12, 4),
                 (10, 5), (11, 5), (12, 5)]:
        put(img, x, y, IRON_DARK)
    put(img, 11, 4, TEAL)
    put(img, 10, 6, IRON)
    put(img, 10, 7, IRON_DARK)
    # measurement ticks on the shaft
    for x, y in [(5, 12), (7, 10), (8, 9)]:
        put(img, x, y, WHITE)
    return img


def npc_cloner():
    """Cloner: two overlapping head silhouettes + a violet flash."""
    img = canvas()
    # back head (magenta frame, skin fill)
    for x in range(8, 13):
        for y in range(3, 8):
            put(img, x, y, MAGENTA_DARK)
    for x in range(9, 12):
        for y in range(4, 7):
            put(img, x, y, SKIN)
    put(img, 9, 4, MAGENTA)
    put(img, 11, 4, MAGENTA)
    # front head, offset down-left
    for x in range(3, 8):
        for y in range(8, 13):
            put(img, x, y, MAGENTA_DARK)
    for x in range(4, 7):
        for y in range(9, 12):
            put(img, x, y, SKIN)
    put(img, 4, 9, MAGENTA)
    put(img, 6, 9, MAGENTA)
    # copy flash between them
    for x, y in [(8, 9), (9, 10), (7, 8), (10, 9), (8, 7)]:
        put(img, x, y, MAGENTA)
    put(img, 8, 8, WHITE)
    return img


if __name__ == "__main__":
    save(npc_wand(), "npc_wand.png")
    save(patrol_wand(), "patrol_wand.png")
    save(survey_rod(), "survey_rod.png")
    save(npc_cloner(), "npc_cloner.png")
    save(boundary_marker(RED, RED_DARK), "prison_marker.png")
    save(boundary_marker(OLIVE, OLIVE_DARK), "room_marker.png")
