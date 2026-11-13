#!/usr/bin/env python3
"""Rebrand the bundled skins: replace the "WINAMP" title-bar lettering with "FOLDAMP".

Reads the original skins from skins/ and writes patched copies to app/src/main/assets/skins/.
Requires Pillow (pip install Pillow); runs anywhere Python 3 does.

    python3 scripts/rebrand_skins.py [preview.png]

For each title strip the old word is erased (each row blends between the background at both ends
of the gap, since title bars shade horizontally), the groove end-caps move outward a little to make
room, and FOLDAMP is drawn with letters taken from the skin itself:
  - base-2.91 assembles F, O, L, D from its own title lettering (E minus its foot, Q minus its tail,
    the L of PLAYLIST, P's stem plus Q's bowl) and keeps the original "AMP", so it matches exactly;
  - the Classified skins use their gen.bmp font, the same letters as the "FOLDAMP SPECTRUM" title.
Anything after the first word (" EQUALIZER", " PLAYLIST") keeps its original pixels.
"""
import io
import os
import sys
import zipfile

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "skins")
DST = os.path.join(ROOT, "app", "src", "main", "assets", "skins")


class Bar:
    """One title strip layout. All x values are sheet columns; [lcap, rcap] are the 2-column groove
    end-caps (cap + dark edge) either side of the text; [tx0, tx1) is the text, [tx0, wx1) its first
    word; amp is the [x0, x1) span of "AMP" inside it (base-2.91 only); top is the first text row."""

    def __init__(self, bmp, strips, height, lcap, rcap, tx0, wx1, tx1, top, shift=2, amp=None):
        self.bmp, self.strips, self.height = bmp, strips, height
        self.lcap, self.rcap, self.tx0, self.wx1, self.tx1 = lcap, rcap, tx0, wx1, tx1
        self.top, self.shift, self.amp = top, shift, amp


PATCHES = {
    "base-2.91.wsz": ("title", [
        Bar("titlebar.bmp", [(0, False), (15, True)], 14, 139, 188, 145, 184, 184, 5, amp=(163, 184)),
        Bar("eqmain.bmp", [(134, False), (149, True)], 14, 89, 192, 95, 134, 188, 5, amp=(113, 134)),
        Bar("pledit.bmp", [(0, False), (21, True)], 20, 28, 122, 33, 72, 120, 5, amp=(51, 72)),
    ]),
    "Winamp5_Classified_v5.5.wsz": ("gen", [
        Bar("titlebar.bmp", [(0, False), (15, True)], 14, 133, 194, 135, 194, 194, 5, shift=0),
    ]),
    "Winamp3_Classified_v5.5.wsz": ("gen", [
        Bar("titlebar.bmp", [(0, False), (15, True)], 14, 137, 190, 139, 190, 190, 4),
    ]),
}

# Glyphs are lists of columns; each pixel is an (r, g, b) offset from the background, or for gen.bmp
# letters an (alpha, ink colour) pair.
SPACE = 1


def lum(c):
    return 0.3 * c[0] + 0.59 * c[1] + 0.11 * c[2]


def open_bmp(data):
    im = Image.open(io.BytesIO(data))
    im.load()
    return im.convert("RGB")


def diff_cols(im, x0, x1, y0, rows, bg_x):
    """Columns [x0, x1) of rows y0..y0+rows as offsets from the background colour at column bg_x."""
    bg = [im.getpixel((bg_x, y0 + j)) for j in range(rows)]
    return [[tuple(im.getpixel((x, y0 + j))[k] - bg[j][k] for k in range(3)) for j in range(rows)]
            for x in range(x0, x1)]


def title_letters(sheets, inactive):
    """base-2.91: F, O, L, D cut from the skin's own title lettering (rows 5..10 of each strip)."""
    eq_y = 149 if inactive else 134
    pl_y = 21 if inactive else 0
    e = diff_cols(sheets["eqmain.bmp"], 138, 143, eq_y + 5, 6, 136)
    q = diff_cols(sheets["eqmain.bmp"], 144, 149, eq_y + 5, 6, 136)
    l = diff_cols(sheets["pledit.bmp"], 83, 87, pl_y + 5, 6, 74)
    p_stem = diff_cols(sheets["pledit.bmp"], 76, 78, pl_y + 5, 6, 74)
    zero = (0, 0, 0)
    f = [col[:5] + [zero] if i >= 2 else col for i, col in enumerate(e)]
    return {"F": f, "O": q, "L": l, "D": p_stem + q[2:]}


def gen_letters(gen, inactive):
    """Classified skins: letters from gen.bmp, sliced the way Skin.scanGenFont does."""
    y = 96 if inactive else 88
    sep = gen.getpixel((0, 88))
    cells, x = {}, 1
    for ch in "ABCDEFGHIJKLMNOPQRSTUVWXYZ":
        nxt = x
        while nxt < gen.width and gen.getpixel((nxt, 88)) != sep:
            nxt += 1
        if nxt <= x:
            break
        cells[ch] = (x, nxt)
        x = nxt + 1
    # Cells sit on a shaded background, so key each row against a dark (20th-percentile) pixel of that row and
    # its brightest ink, then blend: pixels become (alpha, ink) pairs instead of offsets.
    span = range(1, x - 1)
    out = {}
    bg, ink = [], []
    for j in range(7):
        row = [gen.getpixel((i, y + j)) for i in span if gen.getpixel((i, 88)) != sep]
        bg.append(sorted(row, key=lum)[len(row) // 5])
        ink.append(max(row, key=lum))
    for ch, (x0, x1) in cells.items():
        cols = []
        for i in range(x0, x1):
            col = []
            for j in range(7):
                a = (lum(gen.getpixel((i, y + j))) - lum(bg[j])) / max(1.0, lum(ink[j]) - lum(bg[j]))
                col.append((max(0.0, min(1.0, a)), ink[j]))
            cols.append(col)
        keep = [i for i, col in enumerate(cols) if max(a for a, _ in col) > 0.25]
        out[ch] = cols[keep[0]:keep[-1] + 1] if keep else cols
    return out


def word_cols(letters, word):
    cols = []
    for i, ch in enumerate(word):
        if i:
            cols += [None] * SPACE
        cols += letters[ch]
    return cols


def patch_strip(im, sy, bar, word, rows_off):
    rows = range(sy, sy + bar.height)
    gl, gr = bar.lcap + 2, bar.rcap  # current gap [gl, gr)
    bg_l = {y: im.getpixel((gl, y)) for y in rows}
    bg_r = {y: im.getpixel((gr - 1, y)) for y in rows}
    tail = [[im.getpixel((x, y)) for y in rows] for x in range(bar.wx1, bar.tx1)]
    tail_bg = [[bg_at(bg_l, bg_r, gl, gr, x, y) for y in rows] for x in range(bar.wx1, bar.tx1)]
    # Move the end-caps outward to widen the gap.
    k = bar.shift
    for x in range(bar.lcap, bar.lcap + 2):
        for y in rows:
            im.putpixel((x - k, y), im.getpixel((x, y)))
    for x in range(bar.rcap + 1, bar.rcap - 1, -1):
        for y in rows:
            im.putpixel((x + k, y), im.getpixel((x, y)))
    gl, gr = gl - k, gr + k
    for x in range(gl, gr):
        for y in rows:
            im.putpixel((x, y), bg_at(bg_l, bg_r, gl, gr, x, y))
    width = len(word) + len(tail)
    if width + 2 > gr - gl:
        sys.exit(f"{bar.bmp} y={sy}: {width}px of text won't fit in {gr - gl}px")
    x0 = gl + (gr - gl - width + 1) // 2
    for i, col in enumerate(word):
        if col is None:
            continue
        for j, d in enumerate(col):
            y = sy + rows_off + j
            b = im.getpixel((x0 + i, y))
            if isinstance(d[0], float):  # (alpha, ink) from gen_letters
                a, ink = d
                im.putpixel((x0 + i, y), tuple(round(b[c] * (1 - a) + ink[c] * a) for c in range(3)))
            else:
                im.putpixel((x0 + i, y), tuple(max(0, min(255, b[c] + d[c])) for c in range(3)))
    for i, col in enumerate(tail):
        x = x0 + len(word) + i
        for j, y in enumerate(rows):
            b = im.getpixel((x, y))
            im.putpixel((x, y), tuple(max(0, min(255, b[c] + col[j][c] - tail_bg[i][j][c])) for c in range(3)))


def amp_cols(im, sy, bar):
    """base-2.91's original "AMP", as offsets from the (horizontally shaded) background behind it."""
    gl, gr = bar.lcap + 2, bar.rcap
    bg_l = {y: im.getpixel((gl, y)) for y in range(sy, sy + bar.height)}
    bg_r = {y: im.getpixel((gr - 1, y)) for y in range(sy, sy + bar.height)}
    return [[tuple(im.getpixel((x, y))[c] - bg_at(bg_l, bg_r, gl, gr, x, y)[c] for c in range(3))
             for y in range(sy + 5, sy + 11)] for x in range(*bar.amp)]


def bg_at(bg_l, bg_r, gl, gr, x, y):
    t = (x - gl) / max(1, gr - 1 - gl)
    return tuple(round(bg_l[y][c] * (1 - t) + bg_r[y][c] * t) for c in range(3))


def main():
    os.makedirs(DST, exist_ok=True)
    previews = []
    for skin, (source, bars) in PATCHES.items():
        with zipfile.ZipFile(os.path.join(SRC, skin)) as z:
            entries = [(info, z.read(info)) for info in z.infolist()]
        # Like Skin.load: match names case-insensitively and prefer the shallowest copy.
        by_name = {}
        for idx, (info, _) in sorted(enumerate(entries), key=lambda e: -e[1][0].filename.count("/")):
            by_name[os.path.basename(info.filename).lower()] = idx
        sheets = {b.bmp: open_bmp(entries[by_name[b.bmp]][1]) for b in bars}
        originals = {name: im.copy() for name, im in sheets.items()}
        # Only the Classified skins need gen.bmp (base-2.91's is RLE-compressed, which Pillow can't read).
        gen = open_bmp(entries[by_name["gen.bmp"]][1]) if source == "gen" else None
        for bar in bars:
            im = sheets[bar.bmp]
            for sy, inactive in bar.strips:
                if source == "title":
                    letters = title_letters(originals, inactive)
                    amp = amp_cols(originals[bar.bmp], sy, bar)
                    word = word_cols(letters, "FOLD") + [None] * SPACE + amp
                    patch_strip(im, sy, bar, word, 5)
                else:
                    word = word_cols(gen_letters(gen, inactive), "FOLDAMP")
                    patch_strip(im, sy, bar, word, bar.top)
                previews.append(im.crop((0, sy, im.width, sy + bar.height)))
        for name, im in sheets.items():
            buf = io.BytesIO()
            im.save(buf, "BMP")
            idx = by_name[name]
            entries[idx] = (entries[idx][0], buf.getvalue())
        with zipfile.ZipFile(os.path.join(DST, skin), "w", zipfile.ZIP_DEFLATED) as z:
            for info, data in entries:
                z.writestr(info, data)
        print(f"patched {skin}")
    if len(sys.argv) > 1:
        w = max(p.width for p in previews)
        sheet = Image.new("RGB", (w, sum(p.height + 2 for p in previews)), (255, 0, 255))
        y = 0
        for p in previews:
            sheet.paste(p, (0, y))
            y += p.height + 2
        sheet.resize((w * 4, sheet.height * 4), Image.NEAREST).save(sys.argv[1])


if __name__ == "__main__":
    main()
