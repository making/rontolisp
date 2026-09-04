# bfloat16

`bfloat16` = the TOP SIXTEEN BITS of an IEEE 754 binary32: one sign bit, an f32's eight exponent bits, seven mantissa bits. Whole f32 range, less precision — the storage format published ML checkpoints use.

Covers the SCALAR conversion pair, the rounding the bulk pair shares with it, and the packed `#bf16` array width on the interpreter and JVM. The file-reading path is not built yet; it belongs here when it lands.

## The scalar pair
- `rontolisp:bfloat16-bits` (real -> integer 0..65535) and `rontolisp:bits-bfloat16` (integer -> float, low sixteen bits only). There is no bfloat16 SCALAR — a Lisp float is always a `double-float` — so both directions cross a `double`.
- `am.ik.rontolisp.BFloat16` is the single Java authority. Both compile backends emit the same arithmetic INLINE rather than calling it, because `BFloat16` is in the root package and does not travel with a compiled program (`codegen/jvm/JvmBFloat16Compiler`, `codegen/wasm/WasmBFloat16Compiler`).
- Unlike the `%ieee754-*` quartet (needs a 64-bit unsigned the WASM numeric model has no room for, so it signals there), sixteen bits fit an i31 fixnum: this pair is REAL on all four backends.
- Pins: `ci-spec.yaml` `bfloat16-bits` (cross-backend); `BFloat16Test`, `LispEvaluatorTest`, `JvmLispCompilerTest#compileAndRunBfloat16Bits`, `WasmLispCompilerIntegrationTest#compileAndRunBfloat16Bits`.

## Three invariants
- **Widening is exact and total.** All 65536 patterns name a float (infinities and NaNs included) and narrowing takes it back unchanged — an involution ON PATTERNS, which is what makes a fused widen-then-compute kernel safe. Tests assert over all 65536, never a sample.
- **Narrowing rounds to NEAREST EVEN**: `(f + 0x7fff + ((f >>> 16) & 1)) >>> 16` over the f32 bits. A truncating `>>> 16` passes a casual test, biases every sum downward, and shows up as model drift rather than a failure — both tie directions and the values either side are pinned explicitly.
- **A NaN never changes class.** Its payload's top seven bits are carried across BY HAND, never through the f32; a payload whose top seven bits are all zero becomes the smallest NaN rather than reading back as an infinity.

## Two deliberate choices
- **Ordinary values round twice** (`double` -> f32 -> bfloat16), so a value between two f32s either side of a bfloat16 midpoint can land on the other neighbour than one direct rounding would choose. The packed array stores the top half of an f32, and the scalar pair must answer what storing and reading back would answer.
- **NaN does not go through the f32 at all.** A float/double conversion (`f2d` and `d2f` alike) quiets a signalling NaN in EITHER direction, losing 126 of the 65536 patterns (sNaN range `0x7f81..0x7fbf` and negatives). WASM is worse: `f32.demote_f64` may invent any NaN payload, so two engines could differ. Both sides do the bits explicitly (`i64.reinterpret_f64` / `f64.reinterpret_i64`, `Double.doubleToRawLongBits` / `Double.longBitsToDouble`). A `double` holds an sNaN unharmed as long as no arithmetic and no width conversion touches it.
- **Rule: at this width, a NaN must never cross a `double` in either direction.** `Float.intBitsToFloat` is bit-preserving for all 65536 patterns; it is the CONVERSION that loses a payload, not the array store, and not one direction more than the other.

## One rounding, reached three ways
The bulk pair `rontolisp:widen-float-bits` / `narrow-float-bits` (a `(unsigned-byte 16)` vector of patterns against an existing packed float array) shares this rounding but reaches it differently by destination width, because `BFloat16`'s API only takes a `double`.

- **`double-float` array**: calls `BFloat16.value` / `BFloat16.bits` directly.
- **`single-float` array never calls `BFloat16` at all.** `eval/FloatBitsWidening` and each compile backend's emitter carry their OWN copy of the identical bit trick on the float's raw bits, no `double` ever created — `eval/FloatBitsWidening#bfloat16BitsOfFloat` (narrow) and `Float.intBitsToFloat(bits << 16)` (widen), all three backends.
- That third copy is deliberate, not a violation of "one rounding": `BFloat16.bits(double)` from a `float` source auto-widens, and `f2d` quiets a signalling NaN exactly as often as a roundtrip does.
- All three copies of the NaN branch use the SAME shape, adapted to each format's mantissa width: `payload | ((payload - 1) >>> 31)` — a nonzero payload untouched, a zero one forced nonzero so it cannot read back as infinity. Trap: a plain `bits | <quiet bit>` forces the quiet bit unconditionally and loses the same 126 patterns — a different way to lose them, not a fix.
- wasm-GC's inline emitter never had either bug: its bf16 widen is a bare `i32.shl` + `f32.reinterpret_i32`, no float/double conversion. The direct implementation was the correct one; the JVM and interpreter arms went through more machinery and that is exactly where the 126 patterns lived.
- Pinned by `LispEvaluatorTest#bfloat16BulkNarrowingIsTheSameRoundingAsTheScalarPair` and `JvmLispCompilerTest#compileAndRunBfloat16BulkAgreesWithTheScalarPair` (both assert plain identity, not a "126 quieted" shape).

### Exact through the PAIR, not through the array it writes into
On wasm-GC these are different ceilings. 65536 patterns widened into a packed array and narrowed back (wasmtime 47):

| wasm-GC path | `single-float` array | `double-float` array |
| --- | --- | --- |
| scalar layout (no `--simd`) | 0 bad | 126 bad |
| `--simd` vblock layout | 126 bad | 126 bad |

The 126 are the signalling patterns. Every cell but the first crosses an f64: the double-float arm by the documented `f64 -> f32` demote in `WasmFloat16Compiler.emitNarrowLoop`, the vblock cells because `_v_get`/`_v_set` are typed `(eq,i32)->f64` / `(eq,i32,f64)->f64` at BOTH widths (`.kb/vec.md`, acceleration layer 3). **This is the wasm element model's ceiling, not the pair's**: the same program with the pair removed loses the identical 126 through `aref` alone, which boxes through the f64 `TYPE_FLOAT` struct either way. So the bulk pair's `--simd` arm has no reason to grow f32-native vblock accessors. The one exact cell stays exact because the scalar single-float arm reads and writes `$f32arr` with no conversion — keep it that way.

**Two lanes work this file at once**, and both times it broke it presented as a change to NaN handling that `git merge` has nothing to say about (both sides correct alone). If you change NaN handling anywhere in this file's subject, tell the other lane before you push.

## The packed array
`#bf16(...)` / `(make-array dims :element-type 'bfloat16)` is the third permit of the sealed `LispFloatArray` — `LispBFloat16Array(short[] data, int[] dims)`, a rontolisp extension, a fourth EMPTY subtype of `float` in the type lattice (no scalar has it; every `aref` answers a `double`).

- Exists on the interpreter and the JVM; every other backend refuses it BY NAME at the representation chokepoint (`compiler/UnsupportedFloatWidth`), never degrades it. `vec:` carries the width (element-wise kernels answer `#bf16`, `%make-like` preserves it); `linalg:` declines it explicitly at `%la-etype` / `%la-make`.
- **JVM representation: a bare `short[]` with a TWO-SLOTS-PER-DIMENSION header** `[rank, hi_0, lo_0, ..., e_0, ...]`, data offset `1 + 2 * rank` — a dimension is an `int` and a `short` caps at 32767, while a 1B-class tensor has larger dimensions. `codegen/jvm/JvmPackedFloatWidth` is the ONE place that knows the layout at every width; no emitter may spell the offset itself (`.kb/vec.md`).
- Element access goes through the program's own `_bf16Value(I)D` / `_bf16Bits(D)I` helpers, `BFloat16` emitted instruction for instruction, pinned against the authority by `JvmBFloat16ArrayTest`: `_bf16Bits` over ALL 2^32 f32 patterns plus the double NaN space, `_bf16Value` and `_bf16Print` over all 65536. Exhaustive in the NARROW direction on purpose — every break in this arithmetic was in a hand-written copy in the narrow/NaN direction, which a round trip over the 65536 representable patterns cannot see.
- Element cap: a `short[]` holds at most 2^31-1 elements, so a larger single tensor needs chunking regardless of width.
- `--simd` on the JVM: the lane kernels carry `double[]` and `float[]` only and CAST anything else, so every `vec:` call site asks each ARRAY argument POSITIVELY whether it is one of those two and takes the spliced `vec.lisp` defun otherwise (`JvmSimdCompiler.emitLaneWidthGuard`; scalar positions like `scale`'s factor and `clip`'s bounds are not asked). Trap: asking "is it the unsupported one?" would let the next representation fall through to the cast. The interpreter's `VecSimd` decline chain follows the same rule.
- Printing an element is `_bf16Print`: the `Float` whose `Float.toString` is `FloatText.bfloat16Text` of the value, so `#bf16(0.1)` prints as written and `(aref #bf16(0.1) 0)` as `0.10009765625`. A program that also `read`s, or defines a `print-object` method, prints through the Lisp-level `%print-object-str` walk (`LispMacroExpander.PRINT_OBJECT_VECTOR_ARM`), whose vector arm excludes the packed widths BY NAME — `bfloat16` had to be added there by hand, and the miss showed only in the `-o Prog.class` E2E of a program that read.
- The bulk pair declines a bf16 destination/source on both backends with the same words until the copy is added; `read-sequence` / `write-sequence` decline the width on both (interpreter `PackedBuffer.of`, JVM `_readSeqPacked`) into the element loop — the raw two-byte transfer is still to come.

## Refusing a width: three behaviours
- **Silent DECLINE**: returns `null` (or `false`), the rung below answers. `VecSimd`, `LinalgSimd`, `LinalgGpu`, `LinalgBlas` — no lane/device/CBLAS kernel reads this width, the scalar defun runs, the ANSWER is identical. A declining reader is the only kind a SOURCE-SHAPE pin can guard (`eval/LinalgWidthWireTest`), because nothing at run time differs when a caller is missed; a reader that GUESSES instead needs a differential test.
- **TEMPORARY refusal**: a `LispEvalException` at RUN time from the not-yet-extended primitive, message says "does not yet". `FloatBitsWidening`'s two arms, `linalg.lisp`'s `%la-make` / `%la-etype` guards.
- **PERMANENT refusal**: an `UnsupportedOperationException` on the COMPILE path naming width and backend — "bfloat16 arrays are supported on the interpreter and the JVM only, not on the wasm-GC backend". One helper builds it, `compiler/UnsupportedFloatWidth`, and the frontend gives it a source position through `SourceProvenance.noteFailure` (which takes any `RuntimeException`). Not `LispCompileException`: `codegen/wasm` does not use that type at all.
- **The phase and the exception TYPE carry the distinction; the prose only decorates it.** Told apart by the word "yet" alone it is invisible to every test. Pinned by one test per backend on the exact refusal text (`WasmLispCompilerTest`, `NoGcWasmCompilerTest`) and — the load-bearing one — a test asserting every message containing "does not yet" comes out as a `LispEvalException` rather than a compile error.
- Refusals READ a width designator, not a boolean: a refusal written against a boolean is one a fourth width falls silently past. An integer code with a `default:` arm is the same trap.

## Printing
`FloatText.bfloat16Text` = the shortest decimal that reads back as the same bfloat16. Seven mantissa bits is far less than an f32, so `singleText` would print the widened f32's digits (`0.100097656` where `0.1` already round-trips). It walks significant-digit counts upwards, takes the first whose value narrows back to the same pattern, then hands that value to `singleText` so the plain-versus-exponent decision and the lowercase exponent marker stay one shared choice (`.kb/format.md`) rather than `BigDecimal`'s. Three significant digits carry 46,444 patterns; eight carry the worst two.

Nothing calls it yet — it is the printer half the array width needs. The WASM emission mirror every other `FloatText` member has lands with the array width, not before: a bfloat16 array is the only thing that can reach it from compiled code.
