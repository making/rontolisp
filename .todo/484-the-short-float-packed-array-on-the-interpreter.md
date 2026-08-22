# 484. The `short-float` packed array on the interpreter

Difficulty: Medium

Part of `.todo/482`. Depends on `.todo/483`. This is the front end and the interpreter
half only -- the JVM backend is `.todo/485`.

Add `LispHalfFloatArray(short[] data, int[] dims)` as the third permit of the sealed
`LispFloatArray` umbrella, storing IEEE binary16 bits and converting with
`Float.floatToFloat16` / `Float.float16ToFloat`. It mirrors `LispSingleFloatArray`
exactly: `elementAt` widens to `double` on read, `setElement` narrows on write, there is
no half-float *scalar*, and the width lives entirely in the array storage.

## Do

1. `LispHalfFloatArray` + the `permits` clause. `elementType()` -> `LispNames.SHORT_FLOAT`
   (add the constant; `PackageRegistry.CL_SYMBOLS` already carries `SHORT-FLOAT`).
   `setElement` must call `FloatArrayWriteHook.written(this.data)` like its siblings, so
   `--gpu` residency invalidation stays correct even though the device never accepts this
   width (`.todo/486`).
2. `toGeneralArray()` -> boxed `LispDouble`s, as the siblings do.
3. Reader: `#h(...)` at every rank, beside the `#f`/`#d` dispatch at `LispReader:667`
   (the `label` there is already a `single ? "#f" : "#d"` ternary that wants to become a
   width). `#h` is unclaimed -- `#S`, `#P`, `#A` and `#<width>@` are the taken prefixes.
4. Printer: `openPrefix()` -> `"#h("`, and `elementText(flat)` must print **the shortest
   decimal that round-trips at f16 width**, the way `FloatText.singleText` does for f32,
   so `#h(0.1)` prints `0.1` and not `0.099975586`. Add `FloatText.halfText`.
   `.todo/482-half-float-short-float-storage/Text.java` confirms a shortest-decimal search
   round-trips **all 63488 finite patterns** and needs at most 5 significant digits -- but
   note that probe uses `BigDecimal.toString`, which spells 65504 as `6.55E+4`; reuse
   `FloatText`'s existing plain-vs-exponent decision, only swapping in the f16 round-trip
   predicate.
5. `make-array dims :element-type 'short-float` (and `upgraded-array-element-type`,
   `array-element-type`, `type-of`, `typep`, `subtypep`). The type lattice already folds
   `SHORT-FLOAT` to `FLOAT` in `ArgumentShapes`, `DeclaredArrayTypes` and
   `LispMacroExpander`; check each of those sites actually reaches the new array rather
   than assuming it stayed a general array.
6. `vec::%make` / `vec::%make-like` and the `linalg:` constructors take
   `:element-type 'short-float` -- so `vec:zeros`/`ones`/`arange` and the width-preserving
   element-wise kernels carry it. Per `.todo/482` the accelerated paths must **widen once
   to f32 and run the existing kernel**, never decode per element in an inner loop.

## Verify

- `LispFloatArrayTest`: the `#f`/`#d` cases, repeated for `#h`.
- Round-trip: every finite f16 bit pattern printed and re-read is bit-identical.
- `aref` on a `#h` array answers the *widened* `double`, and `(setf (aref a i) 0.1)` then
  `(aref a i)` answers `0.099975586d0` -- the narrowing is observable, and that is
  correct and must be pinned, since it is the whole point of the width.
- A `#h` array is `eq`-compared like every other array; two `#h(1.0)` are not `equal`.
- `ci-spec.yaml`: one case, once `.todo/485` and `.todo/486` make every backend either
  carry the width or refuse it -- not before, or the wasm rows break.
