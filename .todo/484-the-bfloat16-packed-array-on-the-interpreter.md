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
6. `vec::%make` / `vec::%make-like` and the `linalg:` constructors accept
   `:element-type 'bfloat16`, so `vec:zeros`/`ones`/`arange` and the width-preserving
   element-wise kernels carry it.

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
