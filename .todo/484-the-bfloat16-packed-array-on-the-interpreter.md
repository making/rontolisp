# 484. The `bfloat16` packed array on the interpreter

Difficulty: Medium

Part of `.todo/482`. Depends on `.todo/483`. This is the front end and the interpreter
side only -- the JVM backend is `.todo/485`, the fused kernels `.todo/488`.

Add `LispBFloat16Array(short[] data, int[] dims)` as the third permit of the sealed
`LispFloatArray` umbrella, holding the **top 16 bits of an f32**. It mirrors
`LispSingleFloatArray` exactly: `elementAt` widens to `double` on read, `setElement`
narrows on write, there is no bf16 *scalar*, and the width lives entirely in the array
storage.

Naming, per `.todo/482`: `bfloat16` as the Lisp type name, `BFloat16` in Java identifiers,
`#bf16(` as the reader prefix.

## The two conversions

```java
static float widen(short b)  { return Float.intBitsToFloat(b << 16); }          // exact
static short narrow(float f) {                                                  // RNE
    int b = Float.floatToRawIntBits(f);
    return (short) ((b + 0x7fff + ((b >>> 16) & 1)) >>> 16);
}
```

Widening is exact and total -- every bf16 pattern is an f32, NaNs and infinities
included -- which is what makes `.todo/488`'s fused kernel safe. Narrowing must be
**round-to-nearest-even**, as above; a bare `>>> 16` truncates and biases every sum
downward, and the difference shows up as drift in a model's output rather than as a test
failure, so pin it. There is no JDK builtin for either (`Float.floatToFloat16` is IEEE
f16, a different format -- see `.todo/482` for why it is not what this item uses).

> **2026-09-03, on landing.** The `narrow` above is INCOMPLETE, and neither conversion
> was written as shown. Both now live in `BFloat16` (`bits(double)` / `value(int)`),
> which landed alongside this item, and NOWHERE else -- this array and the bulk
> widen/narrow primitives delegate to it. Two things the sketch above misses:
>
> - **The NaN cases.** The bias-add can carry a heavy-payload NaN's low bits up through
>   the exponent, and a NaN whose top seven payload bits are all zero would come back as
>   an INFINITY -- a class change, not a rounding difference. `BFloat16.bits` takes NaN
>   out of the arithmetic path entirely.
> - **`float` is the wrong entry width.** `setElement` takes a `double`, so narrowing
>   through a `float` first would round TWICE. `BFloat16.bits(double)` goes straight
>   there. The existing `narrow-float-bits` still narrows a `#d` source through a float
>   (`FloatBitsWidening` line ~145) and so still double-rounds; that is not this item's
>   to fix.
>
> A test-side note. `GpuOfferDifferentialTest.bitsOf` needed a bfloat16 arm to keep
> compiling, and the arm is right (the stored pattern IS the bits at this width, so
> there is no conversion a NaN payload could be lost through) -- but it is **dead code
> today**: that test builds its operands as `new LispSingleFloatArray` / `new
> LispDoubleFloatArray` chosen by a BOOLEAN, and never makes a bfloat16 array. The
> compiler asking for an arm is not the same as a test exercising it. That boolean is
> itself another "exactly two widths" assumption, in test code, where no exhaustiveness
> check reaches it; it is where a bfloat16 row would have to be added when `.todo/486`
> gives the device something to refuse.
>
> A related claim made while landing this, and then WITHDRAWN: that the pre-existing
> `bfloat16BitsOf` and `BFloat16.bits` disagreed on signalling NaNs. **They agree on all
> 2^32 float patterns** (checked exhaustively, 2026-09-03). The reason, worth writing
> down so nobody re-derives it: Java's `float` -> `double` widening QUIETS a signalling
> NaN, so once the entry width is `float` both spellings land on the same `0x7FC0`.
> The unification is justified by "one implementation, and no double rounding at the
> `double` entry point", not by any disagreement.

## Do

1. `LispBFloat16Array` + the `permits` clause. `elementType()` -> `LispNames.BFLOAT16`
   (add the constant, plus a `PackageRegistry` entry -- unlike `SHORT-FLOAT` this name is
   **not** already in `CL_SYMBOLS`, and it must not be: it is a rontolisp extension, so it
   belongs to the rontolisp package, not to `COMMON-LISP`).
   `setElement` must call `FloatArrayWriteHook.written(this.data)` like its siblings, so
   `--gpu` residency invalidation stays correct even though the device never accepts this
   width (`.todo/486`).
2. `toGeneralArray()` -> boxed `LispDouble`s, as the siblings do.
3. Reader: `#bf16(...)` at every rank, beside the `#f(`/`#d(` dispatch

   > **2026-09-03, on landing.** The reader carried the width as a BOOLEAN --
   > `Token.FloatArrayOpen(boolean single)` -- which is the same "exactly two widths"
   > assumption `.todo/483` removed from the Java kernels, in a place that item did not
   > reach. It is now the three-valued `Token.FloatWidth` enum and `readFloatArray` is an
   > exhaustive `switch` over it, so a fourth width is a compile error in the reader too.
   > `.todo/683` collects the places that ask a width BY NAME; this was a place that
   > asked it by boolean.
   (`LispLexer` ~line 275, `LispReader.readFloatArray`). **The branch must be tried before
   the `#x`/`#o`/`#b` radix branch** or the radix reader claims the `#b`; `f` is not a
   binary digit so there is no real ambiguity, only an ordering bug waiting to happen.
   Pin it with a test that reads `#b1010` and `#bf16(1.0)` in the same program.
4. Printer: `openPrefix()` -> `"#bf16("`, and `elementText(flat)` must print **the shortest
   decimal that round-trips at bf16 width**, the way `FloatText.singleText` does for f32,
   so a printed array re-reads to the same bits. Add `FloatText.bfloat16Text`.
   `.todo/482-bfloat16-a-narrow-width-that-pays/Text.java` demonstrates the
   shortest-decimal search for f16; bf16 needs the same search with `narrow`/`widen` as
   the round-trip predicate and will need fewer digits (8 mantissa bits, so 3 significant
   digits suffice for most patterns). Reuse `FloatText`'s existing plain-vs-exponent
   decision rather than `BigDecimal.toString`.
5. `make-array dims :element-type 'bfloat16`, `array-element-type`,
   `upgraded-array-element-type`, `type-of`, `typep`, `subtypep`. `bfloat16` enters the
   type lattice as a **fourth `float` subtype**, disjoint from the three CL ones -- so
   `(typep x 'float)` is true, `(subtypep 'bfloat16 'float)` is true, and it is NOT a
   subtype of `short-float`. Touch points: `ArgumentShapes` (`Shape.FLOAT`),
   `DeclaredArrayTypes`, and `LispMacroExpander`'s several float-type-name lists.

   > **2026-09-03, on landing.** What it answers, since "`(typep x 'float)` is true" did
   > not say for which `x`: **`(typep 1.0 'bfloat16)` is NIL, and so is every other
   > `typep` against a scalar.** `bfloat16` is an EMPTY subtype of `float` -- the width
   > exists only as array storage, `aref` answers a `double`, so no value in the
   > language has this type. `(subtypep 'bfloat16 'float)` is T, which is consistent: an
   > empty type is a subtype of anything. `(typep #bf16(1.0) '(array bfloat16))` and
   > `(simple-array bfloat16 (1))` are T, `(array-element-type #bf16(1.0))` is
   > `BFLOAT16`, and `(type-of #bf16(1.0 2.0))` is `(SIMPLE-ARRAY BFLOAT16 (2))`.
   >
   > **`(typep 1.0 'short-float)` is T while `(typep 1.0 'bfloat16)` is NIL**, and the
   > asymmetry is intended: `short-float` is a standard CL float type an implementation
   > may collapse onto another width, so a float IS one; `bfloat16` is a rontolisp
   > extension that names an array element width and nothing else.
   >
   > **The lattice also needs `LispMacroExpander.upgradedArrayElementType`, and NOTHING
   > POINTS AT IT.** `(typep #bf16(1.0) '(array bfloat16))` answered NIL after every
   > other part of this item was done: that method normalizes a declared element type by
   > SYMBOL NAME, so it produced no compile error when the permit was added and no
   > existing test covered it. Found only by running the form. It is another place that
   > asks a width by name rather than by type -- the family `.todo/683` collects.
6. `vec::%make` / `vec::%make-like` and the `linalg:` constructors accept
   `:element-type 'bfloat16`, so `vec:zeros`/`ones`/`arange` and the width-preserving
   element-wise kernels carry it.

   > **2026-09-03, on landing.** Only the `vec:` half was done. `linalg:` REFUSES the
   > width, explicitly and temporarily (`linalg: does not yet carry bfloat16 arrays`),
   > rather than answering `#d` for a `#bf16` input. When this step was written the
   > obstacle was not visible: `linalg`'s width rides as a **boolean** through
   > `%la-gather-strided (a od rs base single)`, whose own comment says the flag exists
   > "so a kernel on any backend can read it without a symbol comparison". Two sites
   > feed it (`linalg.lisp` 471 and 1043, both `(eq (%la-etype a) 'single-float)`), and
   > widening that flag to three values reaches `LinalgSimd.gatherStrided` and
   > `LinalgGpu.gatherStrided` (which read `args.get(4)` as a boolean) and the compiled
   > backends -- past "the front end and the interpreter side only". The refusal is one
   > guard in `%la-etype`, which every internal width question flows through, plus one
   > in the `%la-make` constructor funnel for an explicitly requested width.

## Verify

- `LispFloatArrayTest`: the `#f`/`#d` cases, repeated for `#bf16`.
- Round-trip: every one of the 65536 bf16 bit patterns printed and re-read is
  bit-identical.
- Widening is exact: for all 65536 patterns, `widen(b)` equals
  `Float.intBitsToFloat(b << 16)` and re-narrowing returns `b`.
- Narrowing is round-to-nearest-even: pin the two ties (round-half-to-even up and down)
  and the values just below and above them. A truncating implementation passes a naive
  test and fails these.
- `aref` on a `#bf16` array answers the *widened* `double`; `(setf (aref a i) 0.1)` then
  `(aref a i)` answers the bf16-rounded value. The narrowing is observable and correct,
  and is the point of the width.
- `ci-spec.yaml`: one case, once `.todo/485` and `.todo/486` make every backend either
  carry the width or refuse it -- not before, or the wasm rows break.

  > **2026-09-03: DELIBERATELY NOT DONE, waiting on 485/486.** Not an oversight -- the
  > line above forbids it until every backend either carries the width or refuses it.
