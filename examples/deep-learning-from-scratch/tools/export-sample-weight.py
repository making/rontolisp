#!/usr/bin/env python3
"""One-time converter: a pickled params dict of numpy arrays -> an RLW1 binary.

rontolisp cannot read pickle, so pretrained network weights are re-exported as
a simple big-endian binary the Lisp loader (dataset/rlw1.lisp, load-rlw1)
parses with read-byte:

    "RLW1"                        4 magic bytes
    u8   array-count
    per array (in the given key order):
      u8   ndim
      u32  dims[ndim]             big-endian
      f32  data[prod(dims)]       big-endian IEEE-754, row-major

Usage:
    python3 export-sample-weight.py PKL [-o OUT.bin] [--keys K1 K2 ...]

Defaults reproduce the original ch03 export (sample_weight.pkl -> the
committed ch03/sample-weight.bin): keys W1 b1 W2 b2 W3 b3, output
../ch03/sample-weight.bin next to this script. The ch07/ch08 pretrained
params were exported from the book repo with:

    python3 export-sample-weight.py .../ch07/params.pkl \
        -o ../ch07/params.bin
    python3 export-sample-weight.py .../ch08/deep_convnet_params.pkl \
        -o ../ch08/deep-convnet-params.bin \
        --keys W1 b1 W2 b2 W3 b3 W4 b4 W5 b5 W6 b6 W7 b7 W8 b8

The .bin files are committed, so this script only matters when re-exporting
from the book repo.
"""

import argparse
import os
import pickle
import struct


def main() -> None:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("pkl", help="pickled dict of numpy arrays (from the book repo)")
    parser.add_argument(
        "-o",
        "--out",
        default=os.path.join(os.path.dirname(__file__), "..", "ch03", "sample-weight.bin"),
        help="output .bin path (default: ../ch03/sample-weight.bin)",
    )
    parser.add_argument(
        "--keys",
        nargs="+",
        default=["W1", "b1", "W2", "b2", "W3", "b3"],
        help="array keys in the order the Lisp loader expects (default: W1 b1 W2 b2 W3 b3)",
    )
    args = parser.parse_args()

    with open(args.pkl, "rb") as f:
        net = pickle.load(f)
    missing = [k for k in args.keys if k not in net]
    if missing:
        parser.error(f"keys not in {args.pkl}: {missing} (has {sorted(net)})")
    with open(args.out, "wb") as f:
        f.write(b"RLW1")
        f.write(bytes([len(args.keys)]))
        for key in args.keys:
            a = net[key].astype(">f4")
            f.write(bytes([a.ndim]))
            for d in a.shape:
                f.write(struct.pack(">I", d))
            f.write(a.tobytes())
    print(f"wrote {os.path.normpath(args.out)} ({os.path.getsize(args.out)} bytes)")


if __name__ == "__main__":
    main()
