# bfloat16

`bfloat16` is the TOP SIXTEEN BITS of an IEEE 754 binary32: one sign bit, the same eight
exponent bits an f32 has, and seven mantissa bits. It covers the whole f32 range and
trades precision for width, which is what makes it the storage format published
machine-learning checkpoints use (`.todo/482`).

This file covers the SCALAR conversion pair, the rounding `.todo/671`'s bulk pair shares
with it, and the packed `#bf16` array width on the interpreter and the JVM
(`.todo/484`/`485`, "The packed array" below). The file-reading path (`.todo/487` steps
3-5) is not built yet; when it lands it belongs here.

## The pair

`rontolisp:bfloat16-bits` (a real -> an integer 0..65535) and `rontolisp:bits-bfloat16`
(an integer -> a float, low sixteen bits only). There is no bfloat16 SCALAR -- a Lisp
float is always a `double-float` -- so both directions cross a `double`.

`am.ik.rontolisp.BFloat16` is the single Java authority. The two compile backends emit
the same arithmetic INLINE rather than calling into it, because `BFloat16` lives in the
root package and does not travel with a compiled program
(`codegen/jvm/JvmBFloat16Compiler`, `codegen/wasm/WasmBFloat16Compiler`).

Unlike the `%ieee754-*` quartet beside it, which needs a 64-bit unsigned integer the WASM
numeric model has no room for and therefore signals there, sixteen bits fit an i31 fixnum:
this pair is REAL on all four backends. `ci-spec.yaml`'s `bfloat16-bits` case is what
pins them against each other; `BFloat16Test`, `LispEvaluatorTest`,
`JvmLispCompilerTest#compileAndRunBfloat16Bits` and
`WasmLispCompilerIntegrationTest#compileAndRunBfloat16Bits` pin them one at a time.

## The three invariants

**Widening is exact and total.** Every one of the 65536 patterns names a float --
infinities and NaNs included -- and narrowing takes it back unchanged. So the pair is an
involution ON PATTERNS, which is what makes a fused widen-then-compute kernel safe
(`.todo/488`) and what every test here asserts over all 65536 rather than over a sample.

**Narrowing rounds to NEAREST EVEN.** `(f + 0x7fff + ((f >>> 16) & 1)) >>> 16` over the
f32 bits. A truncating `>>> 16` passes a casual test, biases every sum downward, and shows
up as drift in a model's output rather than as a failure -- so both tie directions and the
values either side of them are pinned explicitly.

**A NaN never changes class.** Its payload's top seven bits are carried across BY HAND,
never through the f32, and a payload whose top seven bits are all zero becomes the
smallest NaN rather than reading back as an infinity.

## Two choices, not consequences

**Ordinary values round twice.** A `double` is narrowed to an f32 and the f32 to a
bfloat16, so a value sitting between two f32s either side of a bfloat16 midpoint can land
on the other neighbour than one direct rounding would choose. Deliberate: the packed array
stores the top half of an f32, and the scalar pair must answer what storing into it and
reading it back would answer.

**NaN does not go through the f32 at all.** Measured 2026-09-03: a float/double conversion
quiets a signalling NaN in EITHER direction, so 126 of the 65536 patterns (the sNaN range,
`0x7f81..0x7fbf` and its negatives) lost their payload and broke the round trip. WASM is worse than merely quiet --
`f32.demote_f64` is free by specification to invent any NaN payload it likes, so the same
program could answer differently on two engines. Doing the bits explicitly on both sides
(`i64.reinterpret_f64` / `f64.reinterpret_i64`, `Double.doubleToRawLongBits` /
`Double.longBitsToDouble`) is what makes the exactness claim true rather than hardware-
dependent. A `double` holds an sNaN unharmed as long as no arithmetic and no width
conversion touches it, which was measured on this JDK before the design was fixed.

## One rounding, not two

`.todo/671`'s bulk pair (`rontolisp:widen-float-bits` / `narrow-float-bits`, a
`(unsigned-byte 16)` vector of patterns against an existing packed float array) shares
this rounding rather than carrying its own: `eval/FloatBitsWidening` calls
`BFloat16.bits`, and `codegen/jvm/JvmFloat16RuntimeBuilder` emits `bits(float)`
instruction for instruction. It landed with a private copy of the same trick on each side,
which is two more things to keep right and would have put a checkpoint's bulk load a bit
away from what the program computes element by element. `bits(float)` is the arm that
dedup wanted: no `double` in the way, so a NaN's payload is carried rather than
force-quieted. Pinned on both backends by
`LispEvaluatorTest#bfloat16BulkNarrowingIsTheSameRoundingAsTheScalarPair` and
`JvmLispCompilerTest#compileAndRunBfloat16BulkAgreesWithTheScalarPair`.

**There was a ceiling here, and it is closed (2026-09-03).** The bulk WIDEN into a packed
single-float array used to build the value as a `double` and cast it down, which quieted a
signalling NaN on the way in and left widen-then-narrow the identity on 65,410 patterns
rather than all 65536. It now writes `Float.intBitsToFloat(bits << 16)` straight into the
`float[]`, and the round trip is the identity on every pattern. Do not re-derive the old
shape from a `BFloat16.value` call: the pattern IS the f32's top half, so the widen has no
reason to visit a `double` at all.

The measurement that settles which step loses a payload, since it is easy to guess wrong
(2026-09-03, and guessed wrong twice before it was taken): **BOTH float/double conversions
quiet a signalling NaN.** Each of the 126 signalling patterns loses its payload through
`f2d` AND through `d2f` -- it is the conversion, not the array store, and not one
direction more than the other. `Float.intBitsToFloat` is bit-preserving for all 65536
patterns, and a `double` built with `Double.longBitsToDouble` holds a signalling NaN
unharmed through an array, a box and a call as long as no arithmetic and no width
conversion touches it. So the rule is simply: at this width, a NaN must never cross a
`double` in either direction.

**Two lanes work this file at once.** The scalar pair, the bulk pair and the packed array
land from different items, and the two times this area broke on 2026-09-03 it presented
the same way both times -- as a change to how a NaN is handled, once with no behavioural
difference (withdrawn) and once with a real one (the new side was right). A `git merge`
has nothing to say about it: both sides were correct alone. **If you change NaN handling
anywhere in this file's subject, tell the other lane before you push.**

## The packed array

`#bf16(...)` / `(make-array dims :element-type 'bfloat16)` is the third permit of the
sealed `LispFloatArray` (`LispBFloat16Array(short[] data, int[] dims)`, a rontolisp
extension, a fourth EMPTY subtype of `float` in the type lattice: no scalar has it, every
`aref` answers a `double`). It exists on the interpreter and the JVM; every other backend
refuses it by name at the representation chokepoint (`.todo/486`,
`compiler/UnsupportedFloatWidth`), never degrades it. `vec:` carries the width (the
element-wise kernels answer `#bf16`, `%make-like` preserves it); `linalg:` declines it
explicitly at `%la-etype` / `%la-make`.

**The JVM representation is a bare `short[]` with a TWO-SLOTS-PER-DIMENSION header**,
`[rank, hi_0, lo_0, ..., e_0, ...]`, data offset `1 + 2 * rank`: a dimension is an `int`
and a `short` caps at 32767, and a 1B-class tensor has dimensions well above that
(`.todo/485`, option (a)). `codegen/jvm/JvmPackedFloatWidth` is the one place that knows
the layout at every width, and `.kb/vec.md` says why no emitter may spell the offset
itself. Element access goes through the program's own `_bf16Value(I)D` / `_bf16Bits(D)I`
helpers, `BFloat16` emitted instruction for instruction (it lives in the root package and
does not travel), and the copy is pinned against the authority by
`JvmBFloat16ArrayTest`: `_bf16Bits` over ALL 2^32 f32 patterns plus the double NaN space,
`_bf16Value` and `_bf16Print` over all 65536. That test is exhaustive in the NARROW
direction on purpose -- the three times this arithmetic broke on 2026-09-03, it broke in a
hand-written copy, in the narrow/NaN direction, and a round trip over the 65536
representable patterns cannot see it. The element cap is unchanged by the narrowing: a
`short[]` holds at most 2^31-1 elements, so a single tensor above that needs chunking
regardless of width (`.todo/489`'s problem, not this file's).

What a `--simd` build does with a bf16 operand on the JVM: the lane kernels carry
`double[]` and `float[]` only and CAST anything else, so every `vec:` call site asks each
ARRAY argument, positively, whether it is one of those two and takes the spliced
`vec.lisp` defun otherwise (`JvmSimdCompiler.emitLaneWidthGuard`; scalar positions --
`scale`'s factor, `clip`'s bounds -- are not asked). Asking "is it the unsupported one?"
would let the next representation (`.todo/672`'s quantized matrix) fall through to the
cast. The interpreter's `VecSimd` decline chain is the same rule. The fused bf16 kernels
that make the width a performance width are `.todo/488`.

Printing an element is `_bf16Print`: the `Float` whose `Float.toString` is
`FloatText.bfloat16Text` of the value (below), so `#bf16(0.1)` prints as written and
`(aref #bf16(0.1) 0)` as `0.10009765625`. A program that also `read`s, or defines a
`print-object` method, prints through the Lisp-level `%print-object-str` walk instead
(`LispMacroExpander.PRINT_OBJECT_VECTOR_ARM`), whose vector arm excludes the packed
widths BY NAME -- `bfloat16` had to be added there by hand, and the miss showed only in
the `-o Prog.class` E2E of a program that read (`.todo/683` lists the site). The bulk pair (`widen-float-bits` /
`narrow-float-bits`) declines a bf16 destination/source on both backends with the same
words until `.todo/487` adds the copy, and `read-sequence` / `write-sequence` decline the
width on both (the interpreter's `PackedBuffer.of`, the JVM's `_readSeqPacked`) into the
element loop -- the raw two-byte transfer is 487's.

## Printing

`FloatText.bfloat16Text` is the shortest decimal that reads back as the same bfloat16.
Seven mantissa bits is far less than an f32 carries, so `singleText` would print the
widened f32's digits (`0.100097656` where `0.1` already round-trips). It walks the
significant-digit counts upwards and takes the first whose value narrows back to the same
pattern, then hands that value to `singleText` so the plain-versus-exponent decision and
the lowercase exponent marker stay the one shared choice (`.kb/format.md`) rather than
`BigDecimal`'s. Three significant digits carry 46,444 of the patterns and eight carry the
worst two.

Nothing calls it yet: it is the printer half `.todo/484` step 4 needs, landed with the
conversion pair so the two are pinned together. The WASM emission mirror every other
`FloatText` member has lands with the array width, not before -- a bfloat16 array is the
only thing that can reach it from compiled code.
