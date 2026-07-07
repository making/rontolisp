#!/usr/bin/env python3
# train_k49.py -- real-data trainer for the hiragana demo's (B) path.
#
# Trains a small MLP on the Kuzushiji-49 (K49) dataset and emits a weights file
# in the EXACT shape the rontolisp inference program expects, so it can be baked
# into infer.wasm via:
#
#     examples/hiragana/gen.sh --weights-from weights-k49.lisp
#
# This is the external, offline half of path (B): rontolisp cannot read binary
# (.npz) nor train at this scale efficiently, so we do the heavy lifting here in
# NumPy and hand rontolisp only the finished weights (a Lisp source file).  The
# inference itself still runs in rontolisp -> WASM, unchanged.
#
# What it produces (matching examples/hiragana/common.lisp + infer.lisp):
#   (defparameter *labels* (list "a" "i" ... "wi" "we" "wo" "n" "iter"))  ; 49
#   (defparameter *weights*
#     (list (list H   576 <H*576 flat row-major W1> <H flat b1>)
#           (list 49  H   <49*H flat row-major W2>  <49 flat b2>)))
# The network is sigmoid(W1 x + b1) -> sigmoid(W2 a1 + b2), argmax -- identical
# to common.lisp's forward pass, so the baked weights reproduce training exactly.
#
# Architecture note: H defaults to 20 so the baked float-constant count
# (576*H + H + 49*H + 49) stays under the JVM class-version-50 verifier ceiling
# (~12.8k), keeping the JVM inference backend working.  --hidden larger still
# runs on the interpreter and WASM but drops the JVM path (see the demo README).
#
# Dataset: Kuzushiji-49, (c) ROIS-DS Center for Open Data in the Humanities
# (CODH), licensed CC BY-SA 4.0.  http://codh.rois.ac.jp/kmnist/  The .npz files
# are downloaded on first run and cached under --data-dir (NOT committed).
#
# Usage:
#   python3 train_k49.py                       # download, train, write weights-k49.lisp
#   python3 train_k49.py --hidden 64 --epochs 80
#   python3 train_k49.py --per-class-cap 0     # use every training image (slow prep)

import argparse
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
INPUT = GRID * GRID  # 576
NCLASSES = 49
INK_BBOX = 0.3  # ink threshold for the bounding box (matches GlyphGen)
BINARIZE = 0.35  # cell on/off threshold (matches GlyphGen / index.html)

# Romaji labels for K49 classes 0..48, in the dataset's own class order
# (k49_classmap.csv).  Classes 0..43 are identical to the demo's a..wa; 44/45
# are ゐ/ゑ (wi/we), 46/47 are を/ん (wo/n), 48 is the iteration mark ゝ (iter).
LABELS = [
    "a", "i", "u", "e", "o", "ka", "ki", "ku", "ke", "ko", "sa", "shi", "su",
    "se", "so", "ta", "chi", "tsu", "te", "to", "na", "ni", "nu", "ne", "no",
    "ha", "hi", "fu", "he", "ho", "ma", "mi", "mu", "me", "mo", "ya", "yu",
    "yo", "ra", "ri", "ru", "re", "ro", "wa", "wi", "we", "wo", "n", "iter",
]
assert len(LABELS) == NCLASSES


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
    """One 28x28 uint8 image -> a flat length-576 float32 vector of 0.0/1.0.

    Mirrors index.html's toBitmap / GlyphGen.render: take ink = pixel/255 (K49
    is white stroke on black, so high = ink), crop to the ink bounding box,
    scale it to fit a (GRID-2) box preserving aspect, centre it with a 1px
    margin, and binarize."""
    ink = img.astype(np.float32) / 255.0
    mask = ink > INK_BBOX
    if not mask.any():
        return np.zeros(INPUT, dtype=np.float32)
    ys, xs = np.where(mask)
    miny, maxy, minx, maxx = ys.min(), ys.max(), xs.min(), xs.max()
    crop = ink[miny : maxy + 1, minx : maxx + 1]
    bh, bw = crop.shape
    fit = GRID - 2
    scale = fit / max(bw, bh)
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
    return (canvas > BINARIZE).astype(np.float32).reshape(-1)


def build_dataset(imgs, lbls, per_class_cap, rng):
    """Preprocess images to bitmaps, optionally capping per class to balance the
    (imbalanced) dataset and bound preprocessing time."""
    idx = np.arange(len(lbls))
    if per_class_cap > 0:
        keep = []
        for c in range(NCLASSES):
            ci = idx[lbls == c]
            if len(ci) > per_class_cap:
                ci = rng.choice(ci, per_class_cap, replace=False)
            keep.append(ci)
        idx = np.concatenate(keep)
    rng.shuffle(idx)
    print(f"preprocessing {len(idx)} images to {GRID}x{GRID} bitmaps...", file=sys.stderr)
    X = np.empty((len(idx), INPUT), dtype=np.float32)
    for i, j in enumerate(idx):
        X[i] = to_bitmap(imgs[j])
        if (i + 1) % 20000 == 0:
            print(f"  {i + 1}/{len(idx)}", file=sys.stderr)
    Y = lbls[idx].astype(np.int64)
    return X, Y


def sigmoid(z):
    return 1.0 / (1.0 + np.exp(-z))


def train(X, Y, hidden, epochs, batch, lr, rng):
    """Plain SGD on a 576-hidden-49 net, sigmoid on BOTH layers (so the baked
    forward pass reproduces it).  Output loss is binary cross-entropy against a
    one-hot target -> output delta is simply (a - y); argmax of the sigmoid
    outputs equals argmax of the logits, so predictions are well defined."""
    n = X.shape[0]
    # He-ish small init.
    W1 = rng.normal(0, np.sqrt(2.0 / INPUT), (hidden, INPUT)).astype(np.float32)
    b1 = np.zeros(hidden, dtype=np.float32)
    W2 = rng.normal(0, np.sqrt(2.0 / hidden), (NCLASSES, hidden)).astype(np.float32)
    b2 = np.zeros(NCLASSES, dtype=np.float32)
    onehot = np.eye(NCLASSES, dtype=np.float32)[Y]
    for e in range(epochs):
        perm = rng.permutation(n)
        for s in range(0, n, batch):
            bi = perm[s : s + batch]
            x = X[bi]  # (B, 576)
            t = onehot[bi]  # (B, 49)
            a1 = sigmoid(x @ W1.T + b1)  # (B, H)
            a2 = sigmoid(a1 @ W2.T + b2)  # (B, 49)
            d2 = (a2 - t) / len(bi)  # BCE+sigmoid output delta
            d1 = (d2 @ W2) * a1 * (1.0 - a1)
            W2 -= lr * (d2.T @ a1)
            b2 -= lr * d2.sum(0)
            W1 -= lr * (d1.T @ x)
            b1 -= lr * d1.sum(0)
        if e == 0 or (e + 1) % 10 == 0 or e == epochs - 1:
            acc = accuracy(X, Y, W1, b1, W2, b2)
            print(f";; epoch {e}  train-acc {acc:.4f}", file=sys.stderr)
    return W1, b1, W2, b2


def accuracy(X, Y, W1, b1, W2, b2):
    a1 = sigmoid(X @ W1.T + b1)
    a2 = sigmoid(a1 @ W2.T + b2)
    return float((a2.argmax(1) == Y).mean())


def balanced_accuracy(X, Y, W1, b1, W2, b2):
    a1 = sigmoid(X @ W1.T + b1)
    a2 = sigmoid(a1 @ W2.T + b2)
    pred = a2.argmax(1)
    accs = []
    for c in range(NCLASSES):
        m = Y == c
        if m.any():
            accs.append((pred[m] == c).mean())
    return float(np.mean(accs))


def fmt(x: float) -> str:
    """Plain positional decimal (no exponent), guaranteed to contain a '.', so
    the rontolisp reader parses it as a float."""
    s = np.format_float_positional(np.float32(x), unique=True, trim="0")
    if s in ("", "-"):
        s = "0.0"
    if "." not in s:
        s += ".0"
    elif s.endswith("."):
        s += "0"
    return s


CHUNK = 200  # floats per chunk defun (matches train.lisp's *chunk*)


def emit_weights(path, hidden, W1, b1, W2, b2):
    # Mirror train.lisp's serialization EXACTLY: split each flat vector into
    # small (defun gN () (list ...)) chunk functions and reassemble with append.
    # A single multi-thousand-element literal list otherwise overflows both the
    # JVM 64KB method cap and the recursive-descent reader/resolver's stack.
    gid = 0
    chunk_defs = []  # accumulated "(defun gN () (list ...))" lines

    def emit_flat(arr):
        nonlocal gid
        vals = [fmt(v) for v in np.asarray(arr).reshape(-1)]
        names = []
        for s in range(0, len(vals), CHUNK):
            name = f"g{gid}"
            gid += 1
            chunk_defs.append(f"(defun {name} () (list " + " ".join(vals[s : s + CHUNK]) + "))")
            names.append(name)
        return names

    def append_expr(names):
        return "(append" + "".join(f" ({n})" for n in names) + ")"

    # Pass 1 order must match emit-weights: layer0 W, layer0 b, layer1 W, layer1 b.
    w1n = emit_flat(W1)  # (hidden, 576) row-major
    b1n = emit_flat(b1)
    w2n = emit_flat(W2)  # (49, hidden) row-major
    b2n = emit_flat(b2)

    with open(path, "w") as f:
        f.write(";;;; weights-k49.lisp -- GENERATED by tools/k49/train_k49.py -- DO NOT EDIT.\n")
        f.write(";;;;\n")
        f.write(";;;; Real-data weights trained on Kuzushiji-49 (49 classes, CC BY-SA 4.0,\n")
        f.write(";;;; ROIS-DS CODH, http://codh.rois.ac.jp/kmnist/).  Bake into infer.wasm with:\n")
        f.write(";;;;   examples/hiragana/gen.sh --weights-from weights-k49.lisp\n")
        f.write(f";;;; Network: 576-{hidden}-49, sigmoid both layers (matches common.lisp).\n")
        f.write(";;;; Weights are split into gN chunk defuns (like train.lisp) so no\n")
        f.write(";;;; single literal list overflows the JVM method cap / the reader stack.\n\n")
        f.write("(defparameter *labels* (list")
        for lab in LABELS:
            f.write(f' "{lab}"')
        f.write("))\n\n")
        for line in chunk_defs:
            f.write(line + "\n")
        f.write("\n(defparameter *weights* (list")
        f.write(f" (list {hidden} {INPUT} {append_expr(w1n)} {append_expr(b1n)})")
        f.write(f" (list {NCLASSES} {hidden} {append_expr(w2n)} {append_expr(b2n)})")
        f.write("))\n")
    print(f"wrote {path} ({gid} chunk defuns)", file=sys.stderr)


def main():
    ap = argparse.ArgumentParser(description="Train an MLP on Kuzushiji-49 and emit weights-k49.lisp")
    ap.add_argument("--data-dir", type=Path, default=Path(__file__).resolve().parent / "data")
    ap.add_argument("--out", type=Path, default=Path(__file__).resolve().parent.parent.parent / "weights-k49.lisp")
    ap.add_argument("--hidden", type=int, default=20)
    ap.add_argument("--epochs", type=int, default=60)
    ap.add_argument("--batch", type=int, default=128)
    ap.add_argument("--lr", type=float, default=0.5)
    ap.add_argument("--per-class-cap", type=int, default=3000,
                    help="max training images per class (0 = all; balances the imbalanced set)")
    ap.add_argument("--seed", type=int, default=0)
    args = ap.parse_args()

    rng = np.random.default_rng(args.seed)
    tr_imgs = load_npz(download(args.data_dir, TRAIN_IMGS))
    tr_lbls = load_npz(download(args.data_dir, TRAIN_LBLS))
    print(f"loaded {len(tr_lbls)} training images", file=sys.stderr)

    X, Y = build_dataset(tr_imgs, tr_lbls, args.per_class_cap, rng)
    W1, b1, W2, b2 = train(X, Y, args.hidden, args.epochs, args.batch, args.lr, rng)

    # Report on the held-out test set (balanced accuracy, as K49 recommends).
    try:
        te_imgs = load_npz(download(args.data_dir, TEST_IMGS))
        te_lbls = load_npz(download(args.data_dir, TEST_LBLS))
        Xte, Yte = build_dataset(te_imgs, te_lbls, 0, rng)
        print(f";; test-acc {accuracy(Xte, Yte, W1, b1, W2, b2):.4f}"
              f"  test-balanced-acc {balanced_accuracy(Xte, Yte, W1, b1, W2, b2):.4f}",
              file=sys.stderr)
    except Exception as ex:  # noqa: BLE001 -- reporting is best-effort
        print(f"(skipped test eval: {ex})", file=sys.stderr)

    cap = 576 * args.hidden + args.hidden + NCLASSES * args.hidden + NCLASSES
    if cap > 12800:
        print(f"WARNING: {cap} baked floats exceeds the ~12.8k JVM ceiling; "
              f"infer.wasm and the interpreter still work, but `java Infer` will not.",
              file=sys.stderr)
    emit_weights(args.out, args.hidden, W1, b1, W2, b2)


if __name__ == "__main__":
    main()
