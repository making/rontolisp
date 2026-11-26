"""Writes safetensors-check.safetensors, the few-KB fixture safetensors-check.lisp
reads: one tensor per supported dtype (F32, F16, BF16) with values chosen so the
widened numbers are exact in every width, a rank-1 and a rank-2 shape, a
__metadata__ entry, and one I64 tensor the reader must refuse by name. Plain
Python, no numpy; the bit patterns are written by hand. Also writes the two-shard
pair safetensors-check-00001-of-00002.safetensors / -00002- and their
model.safetensors.index.json so the sharded path is covered."""
import json
import math
import struct

def f16_bits(x):
    """IEEE binary16 bits of a value that is exactly representable there."""
    if x == 0.0:
        return 0x0000 if math.copysign(1.0, x) > 0 else 0x8000
    sign = 0x8000 if x < 0 else 0
    x = abs(x)
    e = math.floor(math.log2(x))
    m = x / (2.0 ** e) - 1.0
    assert 0 <= m < 1 and -14 <= e <= 15
    mant = int(round(m * 1024))
    assert mant / 1024 == m, x
    return sign | ((e + 15) << 10) | mant

def bf16_bits(x):
    """The high half of the f32 pattern of a value exact in bf16 (8 mantissa bits)."""
    bits = struct.unpack("<I", struct.pack("<f", x))[0]
    assert bits & 0xFFFF == 0, x
    return bits >> 16

def write_file(path, tensors, metadata=None):
    """tensors: list of (name, dtype, shape, bytes)."""
    header = {}
    offset = 0
    blobs = []
    for name, dtype, shape, blob in tensors:
        header[name] = {"dtype": dtype, "shape": shape, "data_offsets": [offset, offset + len(blob)]}
        offset += len(blob)
        blobs.append(blob)
    if metadata is not None:
        header["__metadata__"] = metadata
    hjson = json.dumps(header, separators=(",", ":")).encode("utf-8")
    hjson += b" " * (-len(hjson) % 8)          # safetensors pads the header to 8 bytes
    with open(path, "wb") as f:
        f.write(struct.pack("<Q", len(hjson)))
        f.write(hjson)
        for blob in blobs:
            f.write(blob)

def main():
    f32 = [1.5, -2.25, 0.125, 1e-3, 3.0, -0.5]                       # rank-2 2x3
    f16 = [1.0, -0.5, 0.75, 2048.0, -0.001953125, 65504.0, 0.0, -1.5]  # rank-1 8 (65504 = f16 max)
    bf16 = [1.0, -1.5, 0.25, 128.0, -3.0, 0.0078125]                  # rank-2 3x2
    cube = [0.5, -0.25, 4.0, -8.0, 0.0625, 1.0, -1.0, 16.0]                # rank-3 2x2x2
    tensors = [
        ("norm.weight", "F32", [2, 3], b"".join(struct.pack("<f", v) for v in f32)),
        ("half.weight", "F16", [8], b"".join(struct.pack("<H", f16_bits(v)) for v in f16)),
        ("brain.weight", "BF16", [3, 2], b"".join(struct.pack("<H", bf16_bits(v)) for v in bf16)),
        # one element: the destination's flat index 0 is its first element, not a header
        ("scale", "BF16", [1], struct.pack("<H", bf16_bits(-2.5))),
        # rank 3: the header a packed array carries grows with its rank
        ("cube.weight", "F16", [2, 2, 2], b"".join(struct.pack("<H", f16_bits(v)) for v in cube)),
        ("ids", "I64", [2], struct.pack("<qq", 7, -1)),
    ]
    write_file("safetensors-check.safetensors", tensors, {"format": "pt", "note": "rontolisp fixture"})
    # the sharded pair: the same float tensors split over two files
    write_file("safetensors-check-00001-of-00002.safetensors", tensors[:1])
    write_file("safetensors-check-00002-of-00002.safetensors", tensors[1:5])
    index = {"metadata": {"total_size": 0},
             "weight_map": {"norm.weight": "safetensors-check-00001-of-00002.safetensors",
                            "half.weight": "safetensors-check-00002-of-00002.safetensors",
                            "brain.weight": "safetensors-check-00002-of-00002.safetensors",
                            "scale": "safetensors-check-00002-of-00002.safetensors",
                            "cube.weight": "safetensors-check-00002-of-00002.safetensors"}}
    with open("safetensors-check.index.json", "w") as f:
        json.dump(index, f, indent=1)

if __name__ == "__main__":
    main()
