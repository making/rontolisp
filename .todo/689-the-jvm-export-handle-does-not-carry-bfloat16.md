# 689. The `jvm-export` handle does not carry `bfloat16`

Difficulty: Medium

`.kb/jvm-export.md` says `RontoFloatArray` "dispatches width in ONE private place
(`widthOf`/`headerAt`) and reports it as `Width`, an enum a caller must not assume has
two members". It has two members. A `bfloat16` packed array (`.todo/485`, a `short[]`
with the two-slots-per-dimension header) reaching a `:float-vector` / `:float-matrix`
boundary is refused by `checkPacked` -- "not a packed float array: [S" -- which is a
correct refusal and not a wrong number, so nothing is silently misread. But a Java
caller of a compiled model cannot hand a bf16 weight matrix across or receive one.

## Why it is not a one-line arm

`am.ik.rontolisp.runtime` imports nothing, so the handle cannot call
`am.ik.rontolisp.BFloat16`. The widening on `get(i)` and the round-to-nearest-even
narrowing on `set(i, v)` would be a THIRD copy of that arithmetic (the two compile
backends already emit it instruction for instruction), and the copy has to be pinned the
way `JvmBFloat16ArrayTest` pins the JVM's: all 2^32 f32 patterns and the double NaN
space in the narrow direction, all 65536 in the widen direction -- every copy of this
arithmetic that broke on 2026-09-03 broke in the narrow/NaN direction, in a copy.

## Do

1. `RontoFloatArray.Width.BFLOAT16`; `widthOf` / `headerAt` / `get` / `set` /
   `toArray` / `toFloatArray` gain the arm, with the header read through the
   `1 + 2 * rank` layout (`codegen/jvm/JvmPackedFloatWidth` documents it; the runtime
   cannot import that either, so the offset is spelled once in the handle and named).
2. `of(short[], int...)` and `toShortArray()`, the copy-in / copy-out pair at this width.
3. `JvmExportTest` cases mirroring the `float[]` ones, plus the exhaustive conversion
   sweep against `BFloat16` from the test side (the test may import it).
4. `.kb/jvm-export.md`: the width table and "an enum a caller must not assume has two
   members" -- say it has three.
