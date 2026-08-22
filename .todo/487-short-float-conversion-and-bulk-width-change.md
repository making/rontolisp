# 487. `short-float` conversion: the bits pair, `coerce`, and reading f16 files

Difficulty: Medium

Part of `.todo/482`. Depends on `.todo/484` (and on `.todo/485` for the JVM side).

`.todo/482` specifies `short-float` as a **storage** width, so the value of the whole item
is concentrated here: getting data in and out at f16 cheaply, and widening in one bulk
pass rather than per element.

## Do

1. **The bits pair.** `LispNames` already carries `SINGLE_FLOAT_BITS` / `BITS_SINGLE_FLOAT`
   and the double pair; add `short-float-bits` / `bits-short-float` over
   `Float.floatToFloat16` / `Float.float16ToFloat`. Follow the full "Adding a Built-in
   Function" checklist in `CLAUDE.md` -- except step 4 (wasm), which per `.todo/486` is
   out of scope, so decide there whether the *scalar* conversions may exist everywhere
   (they can: they are `double -> double`, no array involved) while the *array* width does
   not. Recommended: yes, the scalar pair is portable; only the array is JVM/interpreter.
2. **`coerce` and the bulk width change.** `(coerce v '(array short-float))` and back, and
   the `vec:`/`linalg:` constructor `:element-type` route, must go through one bulk
   converter, not an element loop through the generic setter. The measured rates
   (`.todo/482-short-float-a-storage-only-narrow-width/Dec.java`) are ~1.9 Gelem/s for a scalar
   `Float.float16ToFloat` loop against ~3.5 for a hand-vectorized exact decode -- this
   JDK does **not** auto-vectorize the intrinsic, so the vectorized decode is worth
   writing once, here, where every other path can call it.
   Use the exact decode (`Gem.java`'s `decode`), not the magic-multiply of `Dec.java`'s
   variant C: that one is 7.9 Gelem/s but wrong on 2048 of the 65536 patterns, and a
   language runtime does not get to be wrong about infinities.
3. **Reading a f16 file.** `read-sequence` into a `short-float` packed array, mirroring
   the bulk transfer `examples/llama2/llama2.lisp` already does into single-float arrays
   (`.kb/binary-sequence-io.md`). This is the path that makes a f16 checkpoint loadable,
   and `.todo/488` is its first consumer. Endianness must match what the existing f32
   bulk read does.
4. **Widen-once helper.** The policy in `.todo/482` -- kernels widen a `short-float`
   operand into an f32 scratch and run the existing f32 kernel -- needs one place that
   does it, with a scratch that is reused rather than allocated per call. Put it beside
   the converter from (2).

## Verify

- `(bits-short-float (short-float-bits x))` is the f16-rounded `x` for a sweep including
  subnormals, +/-0, the max finite 65504, and both infinities.
- Bulk widen of all 65536 patterns is bit-identical to a `Float.float16ToFloat` loop --
  including NaN payloads, which is where the fast decodes differ.
- Re-measure the widen-once cost **on x64** as well as aarch64. The spike was run on
  aarch64 only, and x64 has had `vcvtph2ps` since 2013, so the bulk decode may want a
  different implementation there -- see "Should this be re-run on x64?" in
  `.todo/482-short-float-a-storage-only-narrow-width/README.md`. The *result* must be
  bit-identical on both; only the cost may differ.
- A f16 file written by `short-float-bits` and read back by `read-sequence` compares equal
  on every backend that carries the width.
