"""Cuts the room sprites out of the user's tileset (tileset_user.png) into an atlas for the app."""
import json, os, sys
from collections import deque
import numpy as np
from PIL import Image

SRC = "tileset_user.png"
OUT = sys.argv[1] if len(sys.argv) > 1 else "."
B = json.load(open("ts_boxes.json"))
im = np.array(Image.open(SRC).convert("RGB")).astype(np.int16)


def rgba(rect, pad=1, transparent=True, src=None, cut=4):
    im_ = im if src is None else src
    x0, y0, x1, y1 = rect
    x0, y0, x1, y1 = x0 - pad, y0 - pad, x1 + pad, y1 + pad
    a = im_[y0:y1, x0:x1]
    h, w, _ = a.shape
    out = np.zeros((h, w, 4), np.uint8)
    out[..., :3] = a.clip(0, 255)
    out[..., 3] = 255
    if transparent:
        # background = near-black connected to the crop edge
        dark = a.max(axis=2) <= cut
        seen = np.zeros((h, w), bool)
        q = deque()
        for x in range(w):
            for y in (0, h - 1):
                if dark[y, x] and not seen[y, x]: seen[y, x] = True; q.append((y, x))
        for y in range(h):
            for x in (0, w - 1):
                if dark[y, x] and not seen[y, x]: seen[y, x] = True; q.append((y, x))
        while q:
            y, x = q.popleft()
            for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                ny, nx = y + dy, x + dx
                if 0 <= ny < h and 0 <= nx < w and dark[ny, nx] and not seen[ny, nx]:
                    seen[ny, nx] = True; q.append((ny, nx))
        out[seen, 3] = 0
    return out


def trim(a):
    ys, xs = np.where(a[..., 3] > 0)
    return a[ys.min():ys.max() + 1, xs.min():xs.max() + 1]


def box(i, pad=1):
    return trim(rgba(B[i], pad))


def inset(i, k):
    x0, y0, x1, y1 = B[i]
    return rgba((x0 + k, y0 + k, x1 - k, y1 - k), pad=0, transparent=False)


S = {}
# floor planks + wall
for n, i in enumerate([7, 8, 9, 10, 19, 20, 21]):
    S[f"floor{n}"] = inset(i, 3)
S["wall"] = inset(25, 4)

# desk without its chair; the chair on its own
x0, y0, x1, y1 = B[49]
desk = rgba((x0, y0, x1, y1), pad=1)
cx0, cx1, cy = 56, 132, 119                     # chair columns / where it starts (crop coords, pad=1)
chair = desk[cy:, cx0:cx1].copy()
chair_alpha = chair[..., 3] > 0
d = desk.copy()
# patch the desk's front edge behind the chair with the same rows further right
d[106:119, cx0:cx1] = desk[106:119, 132:132 + (cx1 - cx0)]
d[119:, cx0 - 4:cx1 + 3, 3] = 0
d = d[:180]                                     # stop above the low table below it
S["desk"] = trim(d)
S["deskchair"] = trim(chair)

S["bed"] = box(48)
x0, y0, x1, y1 = B[46]
S["bookshelf"] = trim(rgba((x0, 392, x1, y1)))
S["skullshelf"] = box(55)
S["dresser"] = box(50)
S["wardrobe"] = box(51)
S["drawers"] = box(47)
S["nightstand"] = box(63)
S["table"] = box(64)
S["box"] = box(62)
S["tv"] = box(80)
S["pc"] = box(89)
S["window"] = box(24)
S["door"] = box(17)
for n, i in enumerate([58, 65, 77, 76, 83]): S[f"plant{n}"] = box(i)
for n, i in enumerate([90, 92, 67]): S[f"lava{n}"] = box(i)
for n, i in enumerate([52, 53, 56, 57, 112]): S[f"poster{n}"] = box(i)
for n, i in enumerate([87, 86, 81, 82]): S[f"seat{n}"] = box(i)
S["cat0"] = box(66); S["cat1"] = box(129)
S["rug"] = box(78); S["smallrug"] = box(79); S["roundrug"] = box(85)
S["runner0"] = box(22); S["runner1"] = box(54)
S["mat0"] = box(28); S["mat1"] = box(32); S["mat2"] = box(60)
S["gamechair"] = box(75)
S["headphones"] = box(91)


# the character: down / side (faces left) / back (face painted over with hair from above)
def back(i):
    a = box(i).copy()
    h, w, _ = a.shape
    r, g, b = a[..., 0].astype(int), a[..., 1].astype(int), a[..., 2].astype(int)
    skin = (r > 140) & (r > g + 25) & (a[..., 3] > 0)
    skin[int(h * 0.5):] = False
    ys, xs = np.where(skin)
    fy0, fy1, fx0, fx1 = ys.min() - 2, ys.max() + 3, xs.min() - 2, xs.max() + 3
    src = a.copy()
    top = max(4, fy0)
    for x in range(fx0, fx1):
        ys0 = np.where(src[:, x, 3] > 0)[0]
        if len(ys0) == 0: continue
        y0 = ys0.min()
        for y in range(y0, fy1):
            sy = y0 + int((y - y0) * (top - y0) / max(1, fy1 - y0))
            if src[sy, x, 3] > 0: a[y, x] = src[sy, x]
    return a


def steps(base, key):
    """One real frame per direction + two step frames made from it (left / right foot lifted)."""
    h, w, _ = base.shape
    key = key if "_" in key else f"guy_{key}"
    S[f"{key}0"] = base
    leg = int(h * 0.8)
    for n, (x0, x1) in enumerate([(0, w // 2), (w // 2, w)], start=1):
        f = base.copy()
        part = base[leg:, x0:x1].copy()
        f[leg:, x0:x1] = 0
        f[leg - 3:h - 3, x0:x1] = np.where(part[..., 3:4] > 0, part, f[leg - 3:h - 3, x0:x1])
        S[f"{key}{n}"] = f


steps(box(116), "d"); steps(back(117), "u"); steps(box(131), "s")   # side frame faces left


# ── more characters: sheets made by Umbra's image models (front, back, side in one row, black background) ──
def char_sheet(path: str, prefix: str, height: int = 74):
    """Cut the 3 poses, scale them to the room character's height, then the same step frames as the guy."""
    from scipy import ndimage
    src = np.array(Image.open(path).convert("RGB")).astype(np.int16)
    mask = ndimage.binary_closing(src.max(axis=2) > 24, iterations=3)
    lab, n = ndimage.label(mask)
    found = ndimage.find_objects(lab)
    tallest = max(sl[0].stop - sl[0].start for sl in found)
    # the 3 figures: tall shapes only (loose hair strands / earrings are separate small pieces), left to right
    objs = sorted([sl for sl in found if sl[0].stop - sl[0].start > tallest * 0.6], key=lambda sl: sl[1].start)[:3]
    assert len(objs) == 3, f"{path}: found {len(objs)} poses"
    poses = []
    for sl in objs:
        a = trim(rgba((sl[1].start, sl[0].start, sl[1].stop, sl[0].stop), pad=2, src=src, cut=24))
        img = Image.fromarray(a)
        w = round(img.width * height / img.height)
        poses.append(np.array(img.resize((w, height), Image.LANCZOS)))
    front, back_, side = poses
    # side frames must face LEFT (the room mirrors them for walking right): find the face (skin) side
    r, g, b = (side[..., i].astype(int) for i in range(3))
    skin = (r > 140) & (r > g + 25) & (side[..., 3] > 0)
    skin[int(side.shape[0] * 0.5):] = False
    ys, xs = np.where(skin)
    if len(xs) and xs.mean() > side.shape[1] / 2:
        side = side[:, ::-1].copy()
    steps(front, f"{prefix}_d"); steps(back_, f"{prefix}_u"); steps(side, f"{prefix}_s")


for _f, _p in (("muse_sheet_raw.png", "muse"),):
    if os.path.exists(_f):
        char_sheet(_f, _p)

# second sheet (tileset2.png): thought bubbles + more props (its characters aren't used yet)
im2 = np.array(Image.open("tileset2.png").convert("RGB")).astype(np.int16)
B2 = json.load(open("ts2_boxes.json"))


def box2(i, factor=1.0, main=False):
    a = rgba(B2[i], 1, src=im2, cut=16)
    if main:   # only the biggest piece (drops bits of the labels under the bubbles)
        from scipy import ndimage
        lab, n = ndimage.label(a[..., 3] > 0)
        if n > 1:
            sizes = ndimage.sum(np.ones_like(lab), lab, range(1, n + 1))
            keep = 1 + int(np.argmax(sizes))
            a[(lab != keep) & (lab > 0), 3] = 0
    a = trim(a)
    if factor != 1.0:
        h, w, _ = a.shape
        a = np.array(Image.fromarray(a).resize((round(w * factor), round(h * factor)), Image.NEAREST))
    return a


for n, i in enumerate([6, 7, 8, 9, 11, 12, 13, 14, 17, 18, 19, 20, 25, 26, 27, 28, 35, 36, 37, 38], start=1):
    S[f"bubble{n}"] = box2(i, main=True)
S["pentarug"] = box2(60, 1.6)
S["poster5"] = box2(70, 1.4)
S["gameshelf"] = box2(73, 1.5)
S["gamepad"] = box2(75, 1.1)
S["sword"] = box2(77, 1.3)
S["spellbook"] = box2(78, 1.2)
S["cabinet"] = box2(57, 1.6)
S["lavastand"] = box2(63, 1.5)

# pack (shelf packer)
W = 1024
names = sorted(S, key=lambda k: -S[k].shape[0])
x = y = rowh = 0
rects = {}
for n in names:
    h, w, _ = S[n].shape
    if x + w + 2 > W: x = 0; y += rowh + 2; rowh = 0
    rects[n] = [x, y, w, h]
    x += w + 2; rowh = max(rowh, h)
H = y + rowh
atlas = np.zeros((H, W, 4), np.uint8)
for n, (x, y, w, h) in rects.items():
    atlas[y:y + h, x:x + w] = S[n]
Image.fromarray(atlas).save(f"{OUT}/atlas.png", optimize=True)
json.dump(rects, open(f"{OUT}/atlas.json", "w"), separators=(",", ":"))
print("atlas", W, H, len(rects))
