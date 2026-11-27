# n06. JVM: SHA-512's eight-byte load is a fused method past FreqInlineSize

Difficulty: Medium

`w[t] = (long) data[j] << 56 | ... | data[j + 7] & 0xff` is one fused store of 726 bytecodes
of fast path (4,096-4,288 B of machine code): past C2's `FreqInlineSize` (325), a call per
word (`.kb/jvm-int-fusion.md`, "What C2 still leaves").

Ceiling, measured 2026-11-27 (Xeon E5-2697A v4, C2 of Oracle GraalVM 25.0.5.1, minimum of
rounds 6-11, four launches, ms) with `-XX:FreqInlineSize=1000 -XX:InlineSmallCode=8000`:
SHA-512 of 4 MiB plus one byte 47-53 -> 47-48, of 4 MiB 70-72 -> 62-66. Graal's inlining of
it was not measured.

- Each of the eight reads computes and checks its own subscript (`j + k` wrapped to 32 bits,
  narrowed, compared back) and casts the vector again. Price one cast for the leaves naming
  one variable (as a general array's storage is one pair) and the subscripts `j .. j + 7`
  checked as one range, by the bytecodes they leave: under 325 or not worth it.
