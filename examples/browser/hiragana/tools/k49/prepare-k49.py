#!/usr/bin/env python3
# prepare-k49.py -- turn the real-handwriting dataset Kuzushiji-49 into the tiny
# binary rontolisp can read, so the TRAINING itself happens in Lisp.
#
# The demo trains a CNN on a mix of synthetic multi-font glyphs (prototypes.lisp)
# and real handwritten kana.  rontolisp cannot read numpy's .npz (a zip of binary
# arrays), and the preprocessing (crop / centre / binarize) must match what the
# browser does to a drawn stroke to the pixel -- so this script does BOTH offline
# and writes a flat, sequential binary file that dataset.lisp reads with plain
# read-byte on every backend.  Nothing about the model lives here.
#
# Output format ("HKB1", one file per split, read strictly front-to-back):
#
#     magic  "HKB1"                     4 bytes
#     count  u32 big-endian             number of samples
#     grid   u8                         bitmap edge (24)
#     then COUNT records of 1 + grid*grid bytes:
#         label u8                      class index 0..45 (the demo's kana order)
#         pixels grid*grid bytes        0 or 1, row-major
#
# Class set: K49's 49 classes minus ゐ (wi) / ゑ (we) / the iteration mark ゝ,
# remapped onto the demo's 46 gojuon order (see LABELS below).
#
# Dataset: Kuzushiji-49, (c) ROIS-DS Center for Open Data in the Humanities
# (CODH), licensed CC BY-SA 4.0.  http://codh.rois.ac.jp/kmnist/  The .npz files
# are downloaded on first run and cached under --data-dir (NOT committed).
#
# Usage (from anywhere):
#   python3 examples/browser/hiragana/tools/k49/prepare-k49.py
#   python3 .../prepare-k49.py --per-class-cap 800 --test-per-class-cap 100

import argparse
import struct
import sys
import urllib.request
from pathlib import Path

import numpy as np
from PIL import Image

BASE_URL = "http://codh.rois.ac.jp/kmnist/dataset/k49/"
TRAIN_IMGS = "k49-train-imgs.npz"
TRAIN_LBLS = "k49-train-labels.npz"
TEST_IMGS = "k49-test-imgs.npz"
TEST_LBLS = "k49-test-labels.npz"

GRID = 24  # output bitmap edge (matches the browser / GlyphGen)
INK_BBOX = 0.3  # ink threshold for the bounding box (matches GlyphGen)
BINARIZE = 0.35  # cell on/off threshold (matches GlyphGen / index.html)

# The demo's 46 classes, in output-unit order (GlyphGen.KANA / *romaji*).
LABELS = [
    "a", "i", "u", "e", "o", "ka", "ki", "ku", "ke", "ko", "sa", "shi", "su",
    "se", "so", "ta", "chi", "tsu", "te", "to", "na", "ni", "nu", "ne", "no",
    "ha", "hi", "fu", "he", "ho", "ma", "mi", "mu", "me", "mo", "ya", "yu",
    "yo", "ra", "ri", "ru", "re", "ro", "wa", "wo", "n",
]

# K49's own class order (k49_classmap.csv) is the demo's a..wa for 0..43, then
# 44 ゐ, 45 ゑ, 46 を, 47 ん, 48 ゝ.  Map the shared classes onto the demo's
# indices and drop the three the synthetic set has no glyph for.
K49_TO_DEMO = {k: k for k in range(44)}
K49_TO_DEMO[46] = 44  # wo
K49_TO_DEMO[47] = 45  # n


def download(data_dir: Path, name: str) -> Path:
    path = data_dir / name
    if path.exists():
        return path
    data_dir.mkdir(parents=True, exist_ok=True)
    url = BASE_URL + name
    print(f"downloading {url}", file=sys.stderr)
    urllib.request.urlretrieve(url, path)
    return path


def load_npz(path: Path) -> np.ndarray:
    with np.load(path) as z:
        return z["arr_0"]


def to_bitmap(img: np.ndarray) -> np.ndarray:
    """One 28x28 uint8 image -> a GRID*GRID uint8 vector of 0/1.

    Mirrors index.html's toBitmap / GlyphGen.render: ink = pixel/255 (K49 is a
    white stroke on black, so high = ink), crop to the ink bounding box, scale
    it to fit a (GRID-2) box preserving aspect, centre it with a 1px margin,
    binarize.  The network therefore sees real handwriting in exactly the
    representation the browser will hand it."""
    ink = img.astype(np.float32) / 255.0
    mask = ink > INK_BBOX
    if not mask.any():
        return np.zeros(GRID * GRID, dtype=np.uint8)
    ys, xs = np.where(mask)
    crop = ink[ys.min() : ys.max() + 1, xs.min() : xs.max() + 1]
    bh, bw = crop.shape
    scale = (GRID - 2) / max(bw, bh)
    new_w = max(1, int(round(bw * scale)))
    new_h = max(1, int(round(bh * scale)))
    resized = np.asarray(
        Image.fromarray((crop * 255).astype(np.uint8)).resize(
            (new_w, new_h), Image.BILINEAR
        ),
        dtype=np.float32,
    ) / 255.0
    canvas = np.zeros((GRID, GRID), dtype=np.float32)
    oy = (GRID - new_h) // 2
    ox = (GRID - new_w) // 2
    canvas[oy : oy + new_h, ox : ox + new_w] = resized
    return (canvas > BINARIZE).astype(np.uint8).reshape(-1)


def build_split(imgs, lbls, per_class_cap, rng):
    """Keep the 46 shared classes, cap each class (K49 is imbalanced, and the
    cap is also what bounds the Lisp trainer's runtime), shuffle, preprocess."""
    demo = np.full(len(lbls), -1, dtype=np.int64)
    for k, d in K49_TO_DEMO.items():
        demo[lbls == k] = d
    idx = np.flatnonzero(demo >= 0)
    if per_class_cap > 0:
        keep = []
        for c in range(len(LABELS)):
            ci = idx[demo[idx] == c]
            if len(ci) > per_class_cap:
                ci = rng.choice(ci, per_class_cap, replace=False)
            keep.append(ci)
        idx = np.concatenate(keep)
    rng.shuffle(idx)
    print(f"preprocessing {len(idx)} images to {GRID}x{GRID} bitmaps...", file=sys.stderr)
    x = np.empty((len(idx), GRID * GRID), dtype=np.uint8)
    for i, j in enumerate(idx):
        x[i] = to_bitmap(imgs[j])
    return x, demo[idx].astype(np.uint8)


def write_hkb1(path: Path, x: np.ndarray, y: np.ndarray) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "wb") as f:
        f.write(b"HKB1")
        f.write(struct.pack(">I", len(y)))
        f.write(bytes([GRID]))
        for i in range(len(y)):
            f.write(bytes([int(y[i])]))
            f.write(x[i].tobytes())
    print(f"wrote {path} ({len(y)} samples, {path.stat().st_size} bytes)", file=sys.stderr)


def main():
    here = Path(__file__).resolve().parent
    demo_root = here.parent.parent  # examples/browser/hiragana
    ap = argparse.ArgumentParser(description="Preprocess Kuzushiji-49 into HKB1 bitmaps for the Lisp trainer")
    ap.add_argument("--data-dir", type=Path, default=here / "data",
                    help="cache directory for the downloaded .npz files")
    ap.add_argument("--out-dir", type=Path, default=demo_root / "data",
                    help="where the .bin files land (read by dataset.lisp)")
    ap.add_argument("--per-class-cap", type=int, default=800,
                    help="max TRAIN images per class (0 = all); the Lisp trainer's runtime scales with this")
    ap.add_argument("--test-per-class-cap", type=int, default=100,
                    help="max TEST images per class (0 = all)")
    ap.add_argument("--seed", type=int, default=0)
    args = ap.parse_args()

    rng = np.random.default_rng(args.seed)
    xtr, ytr = build_split(
        load_npz(download(args.data_dir, TRAIN_IMGS)),
        load_npz(download(args.data_dir, TRAIN_LBLS)),
        args.per_class_cap, rng)
    write_hkb1(args.out_dir / "k49-train.bin", xtr, ytr)

    xte, yte = build_split(
        load_npz(download(args.data_dir, TEST_IMGS)),
        load_npz(download(args.data_dir, TEST_LBLS)),
        args.test_per_class_cap, rng)
    write_hkb1(args.out_dir / "k49-test.bin", xte, yte)


if __name__ == "__main__":
    main()
