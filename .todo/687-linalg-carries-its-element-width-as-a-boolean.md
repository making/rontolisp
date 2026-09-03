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

## The same shape appears three times

A boolean is a two-valued type, so any width that rides as one admits exactly two widths.
Found while working `.todo/484` (2026-09-03), in three places, none of which the exhaustive
switches of `.todo/483` can reach -- a `boolean` has no permits to enumerate:

| where | form | status |
| --- | --- | --- |
| `reader/Token` | `FloatArrayOpen(boolean single)` | **fixed** by `.todo/484`: a three-valued `Token.FloatWidth` enum, so `readFloatArray` is an exhaustive switch and a fourth width is a compile error there |
| `linalg.lisp` -> the backends | `%la-gather-strided (a od rs base single)` | **this item** |
| `GpuOfferDifferentialTest` (~line 508) | `f ? new LispSingleFloatArray(...) : new LispDoubleFloatArray(...)` | test code; `.todo/486` touches it when it gives the device a width to refuse |

The reader's fix is the model: the boolean was replaced by a type whose values can be
enumerated, and the compiler then found every site. Do the same here -- the constraint
that makes it harder is only that this boolean crosses a backend boundary.

## Do

Widen the protocol from a boolean to a width designator, in one change across every
backend that reads it:

1. `linalg.lisp`: the two sites, `%la-gather-strided`, and `%la-make`'s decline.
2. `LinalgSimd.gatherStrided` / `LinalgGpu.gatherStrided` (`args.get(4)`), and any
   compiled-backend counterpart -- grep the arity, not the name.
3. Whatever the wasm and JVM lowerings do with the flag.

**A small integer code with a `default:` arm is the trap.** It admits a third value while
re-importing exactly the silence `.todo/483` and this item exist to remove: a fourth width
would fall past every refusal and every kernel arm in silence. The shape that keeps both
properties is an integer on the WIRE and an enumerable type in Java -- convert once at
each entry point (`FloatWidth.ofCode`) and switch exhaustively after that, so a fourth
width is a compile error at every site. `.todo/486` introduces
`am.ik.rontolisp.FloatWidth` for its refusals; this item reads the same one.

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

## What `.todo/486` already landed here, and the one hole it left

**Steps 1-3 of "Do" are done.** `.todo/486`'s work converted `%la-gather-strided`'s fifth
argument from a boolean to a width CODE and gave `LinalgSimd.gatherStrided` the shape this
item asked for -- `FloatWidth.ofCode` at the entry point, then a switch EXPRESSION (not a
statement switch: only the expression form is checked for exhaustiveness over an enum) with
an explicit `BFLOAT16 -> null` decline. `linalg.lisp` routes every internal width question
through `%la-etype`. **What is left of this item is the larger half**: `linalg:zeros` /
`ones` / the constructors and the element-wise operators carrying a third width at all,
which `%la-make` still declines.

**The conversion broke three readers on the way, and the shape is worth keeping.** The
change grepped readers by NAME and found two of FIVE. The three it missed --
`JvmSimdVectorTemplate.laGatherStrided` (`single = singlev != null`),
`JvmGpuTemplate.gpuGatherStrided`, and `WasmLinalgSimdRuntimeBuilder.buildGatherStrided`
(`ref.is_null` / `i32.eqz`) -- all tested the argument for NULLNESS. A boxed `Long 0` is
not null and an i31 `0` is not a null ref, so all three read EVERY width as single-float,
and the JVM `--simd` one returned a `float[]` for a double gather: **data corruption, not a
decline.** All three are fixed. Grep the ARITY, as "Do" step 2 already says, never the name.

**Two failure modes meet at this one door and they need different instruments:**

- a missed CALLER meeting a DECLINING reader is silently correct-but-scalar. Nothing
  differs at run time, so it is pinned AT THE SOURCE -- `LinalgWidthWireTest` reads
  `linalg.lisp` through the project's own `LispReader` (AST, not regex) and asserts every
  `%la-gather-strided` call passes `(linalg::%la-width-code ...)`.
- a missed READER that GUESSES is silently wrong. The answer differs at run time, so no
  source pin can see it; only a differential test against the defun can.

**The open hole.** The three travelling templates cannot import `FloatWidth` (they travel
into compiled output), so they hardcode its codes as literal `0`/`1`. The test written with
the fix asserts the codes are DISTINCT and ROUND-TRIP, which does **not** pin the values:
reorder the enum and the test stays green while all three templates read the wrong width.
Pin the literals, with a javadoc on `FloatWidth.code()` naming the three templates that
transcribe them.

## Verify

- `linalg:zeros` / `ones` / the constructors accept every width `vec:` accepts, and the
  element-wise operators preserve it -- so `(linalg:add a b)` on two `#bf16` arrays
  answers `#bf16`, the property `.todo/484` could only give `vec:`.
- `--simd` and no-`--simd` agree bit-for-bit on every width, the way `.todo/484`'s decline
  chain now makes them agree for `vec:` (nine operators, measured 2026-09-03).
- The backends that do not carry a width still refuse it at the point of failure
  (`.todo/486`), and the refusal reads the new designator.
- No number moves for `single-float` / `double-float`: this is a representation change.
