#!/usr/bin/env python3
"""One-time converter: the book's ch03/sample_weight.pkl -> ch03/sample-weight.bin.

rontolisp cannot read pickle, so the pretrained 784-50-100-10 network weights
are re-exported as a simple big-endian binary the Lisp loader
(dataset/mnist.lisp, load-sample-weight) parses with read-byte:

    "RLW1"                        4 magic bytes
    u8   array-count (6)
    per array (fixed order W1 b1 W2 b2 W3 b3):
      u8   ndim (1 or 2)
      u32  dims[ndim]             big-endian
      f32  data[prod(dims)]       big-endian IEEE-754, row-major

Usage:
    python3 export-sample-weight.py /path/to/deep-learning-from-scratch/ch03/sample_weight.pkl

Writes ch03/sample-weight.bin next to this repo's ch03 scripts. The .bin is
committed, so this script only matters when re-exporting from the book repo.
"""

import os
import pickle
import struct
import sys


def main() -> None:
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    with open(sys.argv[1], "rb") as f:
        net = pickle.load(f)
    out_path = os.path.join(os.path.dirname(__file__), "..", "ch03", "sample-weight.bin")
    with open(out_path, "wb") as f:
        f.write(b"RLW1")
        f.write(bytes([6]))
        for key in ("W1", "b1", "W2", "b2", "W3", "b3"):
            a = net[key].astype(">f4")
            f.write(bytes([a.ndim]))
            for d in a.shape:
                f.write(struct.pack(">I", d))
            f.write(a.tobytes())
    print(f"wrote {os.path.normpath(out_path)} ({os.path.getsize(out_path)} bytes)")


if __name__ == "__main__":
    main()
