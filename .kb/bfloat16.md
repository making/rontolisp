# bfloat16

`bfloat16` is the TOP SIXTEEN BITS of an IEEE 754 binary32: one sign bit, the same eight
exponent bits an f32 has, and seven mantissa bits. It covers the whole f32 range and
trades precision for width, which is what makes it the storage format published
machine-learning checkpoints use (`.todo/482`).

This file covers the SCALAR conversion pair and the rounding `.todo/671`'s bulk pair
shares with it. The packed `#bf16` array
width (`.todo/484`/`485`/`486`) and the file-reading path
(`.todo/487` steps 3-5) are not built yet; when they land they belong here.

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
this rounding rather than carrying its own, but -- unlike the scalar pair -- it reaches it
DIFFERENTLY depending on the packed array's width, because `BFloat16`'s API only takes a
`double`.

- **A `double-float` array** calls `BFloat16.value`/`BFloat16.bits` directly: a genuine
  double, so the class's own double-domain NaN handling is exact.
- **A `single-float` array never calls `BFloat16` at all.** `eval/FloatBitsWidening` and
  each compile backend's emitter carry their OWN copy of the identical bit trick,
  operating on the float's raw bits with no `double` ever created --
  `eval/FloatBitsWidening#bfloat16BitsOfFloat` (narrow) and a plain
  `Float.intBitsToFloat(bits << 16)` (widen, both directions, all three backends).

That is a deliberate THIRD copy of the rounding (scalar `BFloat16`, this file's
double-array arm, this file's float-array arm), not a violation of "one rounding" --
calling `BFloat16.bits(double)` from a `float` source would auto-widen the argument
first, and an f32-&gt;f64 widen (`f2d`) quiets a signalling NaN exactly as often as a
widen-then-narrow roundtrip does (126 of 65536, measured exhaustively, BOTH directions,
2026-09-03): there is no safe direction through `double` for a value that might carry
NaN, so the float-array arm has to avoid the type entirely rather than pick a "safer"
conversion. All three copies of the NaN branch (`.todo/487`'s `BFloat16.bits`, this
file's, the compile backends') use the SAME shape --
`payload | ((payload - 1) >>> 31)`, keeping a nonzero payload untouched and forcing only
a zero one nonzero (so it cannot read back as infinity) -- adapted to each format's
mantissa width. A plain `bits | <quiet bit>` (an earlier version of this file's own
narrow, and this file's WASM emitter, briefly) forces the quiet bit unconditionally,
which quiets a signalling NaN exactly as often as the double detour does: a different
way to lose the same 126 patterns, not a fix.

**wasm-GC's inline emitter never had either bug**, and is worth naming as why: its
bf16 widen was always a bare `i32.shl` + `f32.reinterpret_i32`, no float/double
conversion of any kind, because that is simply the most direct way to write "shift the
bits" in a stack machine with no implicit widening. The naive-but-direct implementation
was the correct one; the JVM and interpreter arms went through more machinery (a shared
`double` intermediate, a borrowed-looking one-line formula) and both engineering
shortcuts happened to be exactly where the 126 patterns lived.

**The bulk pair is therefore exact on all 65536 patterns, on all three backends**,
pinned by `LispEvaluatorTest#bfloat16BulkNarrowingIsTheSameRoundingAsTheScalarPair` and
`JvmLispCompilerTest#compileAndRunBfloat16BulkAgreesWithTheScalarPair` (both assert the
plain identity, not a "126 quieted" shape -- an earlier version of both tests asserted
the ceiling as correct behavior; fixed 2026-09-03 once the ceiling itself was closed).

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
