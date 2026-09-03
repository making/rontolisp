# 687. `linalg:` carries its element width as a boolean, across every backend

Difficulty: Medium

Found 2026-09-03 while working `.todo/484`, which had to decide how far a third packed
float width could reach and found that `linalg:` cannot take one at all.

`linalg.lisp` asks an array its width twice (lines 471 and 1043) as
`(eq (linalg::%la-etype a) 'single-float)`, and hands the ANSWER -- a boolean -- to
`%la-gather-strided (a od rs base single)`. That is not an oversight. The defun's own
javadoc says why:

> the width rides as a flag rather than an element-type symbol so a kernel on any backend
> can read it without a symbol comparison

So the boolean is a deliberate cross-backend protocol: `LinalgSimd.gatherStrided` and
`LinalgGpu.gatherStrided` both read `args.get(4)` as a boolean, and the compiled backends
carry the same shape. It buys kernels that never compare symbols. **It costs the ability
to have a third width**, which is the same defect `.todo/483` fixed on the Java side --
two widths encoded in a two-valued type -- expressed in Lisp as a boolean rather than in
Java as an `instanceof`.

`.todo/484` therefore made `linalg:` **decline** `bfloat16` explicitly at `%la-make`
rather than silently answer `#d`, and corrected its own step 6, which had promised that
"the `linalg:` constructors accept `:element-type 'bfloat16`" without knowing about the
flag. `vec:` carries the width; `linalg:` says it cannot.

## Do

Widen the protocol from a boolean to a width designator, in one change across every
backend that reads it:

1. `linalg.lisp`: the two sites, `%la-gather-strided`, and `%la-make`'s decline.
2. `LinalgSimd.gatherStrided` / `LinalgGpu.gatherStrided` (`args.get(4)`), and any
   compiled-backend counterpart -- grep the arity, not the name.
3. Whatever the wasm and JVM lowerings do with the flag.

**The representation is the whole decision.** The javadoc's requirement -- a kernel reads
the width without comparing symbols -- is a real one and should survive: a small integer
width code (or the ordinal of a sealed width type) keeps that property while admitting a
third value. A symbol comparison in a kernel would trade this defect for a slower one.

## Order

**Design it with `.todo/486`, not before it.** 486 makes the backends that do not carry
`bfloat16` refuse it rather than misread it, and its refusals read exactly the same flag.
Deciding the representation twice -- once for the refusal and once for the widening --
would produce two shapes for one question. Whichever lands first should define the
designator and the other should use it.

## Verify

- `linalg:zeros` / `ones` / the constructors accept every width `vec:` accepts, and the
  element-wise operators preserve it -- so `(linalg:add a b)` on two `#bf16` arrays
  answers `#bf16`, the property `.todo/484` could only give `vec:`.
- `--simd` and no-`--simd` agree bit-for-bit on every width, the way `.todo/484`'s decline
  chain now makes them agree for `vec:` (nine operators, measured 2026-09-03).
- The backends that do not carry a width still refuse it at the point of failure
  (`.todo/486`), and the refusal reads the new designator.
- No number moves for `single-float` / `double-float`: this is a representation change.
