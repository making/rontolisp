# JVM typed numeric loops (`dotimes` over packed float arrays)

**Invariant: a typed loop must never change a result, an error shape, or an observable side effect --
it is a speculation with the ordinary emission as its total fallback, and where both paths run they
produce the same bits.** The JVM analogue of `.kb/wasm-unboxed-locals.md` + `.kb/wasm-int-fusion.md`,
narrowed to a `dotimes` whose body reads and writes packed float arrays through fixnum index math.

**The unit is the LOOP, not the expression**: a loop is not pure, so there is no mid-loop bail --
once entered the typed path must be total. Every assumption is a guard at ENTRY, fixnum arithmetic is
admitted only under a static magnitude bound (no `addExact` recovery is possible), and one form
outside the subset rejects the WHOLE loop rather than boxing one node.

## The typed subset

`JvmTypedLoopCompiler.tryCompile` runs first at `JvmExprCompiler`'s `dotimes` case; on false nothing
was emitted and `expandDotimes` runs as before. Body forms admitted: `let`/`let*`/`progn`/`declare`;
`setq`/`setf`, `(setf (aref a i [j]) e)`/`%aset`; `if`/`when`/`unless` on ONE binary `< > <= >= =`
(an `if` in value position needs both branches of one type); a nested `dotimes` with no result form;
literals, symbols, `(aref a i [j])` with `a` a FREE symbol, `(length a)` of a rank-1 array of the
SAME loop, `+ - * /` (unary `-` and `/` included), and the twelve unary `Math` functions
`JvmMathFnCompiler` lowers (`sqrt exp log sin cos tan asin acos atan sinh cosh tanh`).
Anything else -- a call, `return`, `floor`, `incf`, `and`/`or`, a string, `nil` in value position, a
general array -- disqualifies it; an outer result form may only be a symbol or a number.

`(length a)` is the one call-SHAPED form admitted, and it is admitted because
`(dotimes (i (length a)) ...)` is the idiom: it reads header dimension 0 exactly as `_fvLength`
does -- from the HEADER, never `arraylength`, since a lazy result stub is the header alone
(`.kb/gpu.md`) -- and it is emitted after `hoistArrays`, so the array is already guarded and
materialized. The pre-scan gives it its own case: a name that is only ever a `(length x)` argument
gets no `Spec` and keeps the loop boxed, and `(length a)` must NOT mark `a` index-shaped or the
count of the canonical loop would type its own array as a fixnum.

Two static types, `LONG` and `DOUBLE`. The counter is `LONG` in `[0, count)`; an `aref` is `DOUBLE`
(single-float reads widen, as `_fvAref*` does); `+ - *` over two `LONG`s stays `LONG` only under a
**magnitude bound** (`BigInteger` bounds: literal `|v|`, guarded free fixnum `2^31`, counter = its
count's bound), since past `Long.MAX_VALUE` `_add` promotes to `BigInteger`; `/` over two `LONG`s is
a ratio on the boxed path, ineligible; a `let` local takes its init's type and a `setq` of a
different type, or of a `LONG` with a larger bound, is ineligible (no dual representation). Two
`LONG`s compare with `LCMP`, otherwise both convert and use javac's NaN rule -- `DCMPG` for `<`/`<=`,
`DCMPL` for the rest -- exactly the bitmask `_cmpb` answers.

## Free variables: speculation + guard

Read ONCE at entry through `compileSymbolRef` (locals, captures and globals all qualify -- nothing in
the loop can change them, there are no calls) and guarded: an array by `instanceof float[]` /
`double[]`; a `LONG` by `instanceof Long` AND `(int) v == v`; a strict `DOUBLE` by
`instanceof Double`; a read-only Long-accepting `DOUBLE` by `instanceof Double`, else
`instanceof Long` + `L2D`. A raw double local (`Ctx.rawDoubleLocals`,
`.kb/jvm-double-arithmetic.md`) needs NO guard, entry read, write-back or catch-all handler -- the
slot IS the value -- so such a loop is eligible even with an enclosing expression's operands live on
the stack.

A failed guard jumps to a bail label holding the ordinary `expandDotimes` emission, so a wrong
speculation costs speed only. An ASSIGNED free variable must be a plain JVM-slot local (not captured,
special or global); its typed copy is written back at loop exit AND from a catch-all handler that
rethrows, so a `handler-case` around a loop that dies of an out-of-bounds index still sees the
accumulators the boxed path would have left -- and a loop needing that handler while an enclosing
expression's operands are on the stack stays boxed (entering a handler discards the stack).
Speculation restarts (LONG promoting to DOUBLE, Long-accepting demoting to strict) are bounded and
monotone.

## Arrays and the emission

All arrays of one loop must be `float[]` or all `double[]`; mixed bails. Each variant hoists per
array the typed reference, the data offset `1 + rank` and (rank 2) the column count `header[2]`, then
indexes exactly as `_fvAref1`/`_fvAref2` do -- `base + i * cols + j` -- and check each subscript
against its OWN dimension first (dimension 0 and the column count, hoisted with the base) through
`_ckBoundJ` under the access's wrapper, `AREF` or `(SETF AREF)`: an out-of-range subscript throws
exactly the report the boxed accessor throws (`.kb/error-handling.md`, "An out-of-range
subscript"). The subscripts -- and a store's value -- are evaluated into temps before any check,
as the boxed call evaluates its arguments. **The check is `Objects.checkIndex`**, which the JIT
hoists like its own range checks: a hand-written compare cost a `double-float` loop 27%,
`checkIndex` 1.7% (2026-09-26). A store narrows with `D2F` for `float[]` and in
value position answers the value AS STORED. Under `--gpu` each array the body STORES into is
reported `_gpuWritten` ONCE, at `hoistArrays` and after every array's `_gpuMaterialize` -- not per
store, as `_fvAset*` must (`.kb/gpu.md`). Two things make the hoist the same contract: nothing
inside a typed loop can put an array back on the device (the subset has no calls), and reporting
every materialize before any written keeps a loop whose two array variables are ONE object at run
time correct. The cost of the hoist is that a loop of zero trips drops a device copy it did not
need to -- speed only, and the next two sights re-upload it. Measured on GB10 (2026-09-06,
`.todo/723`, Qwen3.5-0.8B from the BF16 GGUF at `-w bf16`, JVM class output): the per-store guard
was **half of the whole `--gpu --simd` decode step** -- 51 ms a forward against 25 with the hoist,
and stories15M 449 tok/s against 555. A loop with no array and no assigned free variable is left alone
(byte-identical). Typed locals are `allocTemp` pairs (long/double take two slots) released at the
join; a loop pushing `nextLocal` past 250 stays boxed (one-byte slot operands), and
`StackMapAugmenter` merges the slot kinds to TOP at the join.

## Gates and tests

`Ctx.typedLoops` = `!optimize.prefersSizeOverSpeed()`, so **`--optimize=size` declines typed loops**
-- the JVM's first speed-for-size trade, a typed loop emitting the body up to three times
(`.kb/optimize-dead-code-elimination.md`). Off under `--dynamic`;
`-Drontolisp.debug.notypedloops=true` at COMPILE time force-disables them for A/B profiling, and
`-Drontolisp.debug.typedlooptrace=true` prints every loop the analyzer DECLINED, with the form and
the frames that threw -- the A/B says a loop is boxed, the trace says which form did it. Arrays
require `Ctx.usesFloatArray`. Interpreter and both wasm backends are untouched. Pins:
`JvmLispCompilerTest.typedLoopsMatchTheBoxedPathAndTheSizeLevelDeclinesThem`,
`theSizeLevelChangesNothingWithoutATypedLoop`, ci-spec `jvm-typed-numeric-loops`, `ExamplesE2eTest`'s
llm `equals` stories, and -- for the `--gpu` hoist -- the two `typed-loop-aset` lines of
`JvmLinalgGpuAccelCompilerTest.everyEnumeratedWriterInvalidatesTheResidentCopy`, which go red when
the hoist is removed. **`(length a)` has NO ci-spec case**: the one written for it tipped the corpus
over `.todo/722`'s component-backend `ref.cast` cliff, so it is parked in that item with its
expected output and re-added when 722 closes. It was hand-checked identical on all four backends. **`(length a)` has NO ci-spec case**: the one written for it tipped the corpus
over `.todo/722`'s component-backend `ref.cast` cliff, so it is parked in that item with its
expected output and re-added when 722 closes. It was hand-checked identical on all four backends.

## Not done

- **An array of the loop must be `float[]` or `double[]`, so ONE bfloat16 operand keeps the whole
  loop boxed** -- and under `--gpu` the boxed path then pays a residency guard per ELEMENT. This is
  how the depthwise convolution of `examples/llm` came to cost 8.5 ms of a 45 ms forward: its
  kernel is F32 in the checkpoint and was being narrowed to the `-w bf16` weight width for no
  reason, which `.todo/723` fixed at the reader rather than here (a read-only bf16 array variant is
  a third array kind, and nothing measured wants one yet).
- A loop-carried fixnum accumulator on a let local or free variable (`(setq n (+ n 1))`) is rejected
  by the bound rule; a range analysis over the trip count admits it.
- Only `dotimes` is recognized; `do`/`loop ... below`/`while` could share the IR (`loop` expands to
  `tagbody`, not in the subset).
- `incf`/`decf`, `floor`/`mod`, `min`/`max`, `abs`, `and`/`or`/`not` tests, `the`, a let-bound ARRAY,
  `array-dimension`, comparisons of more than two operands -- each a small exact addition.
- **If the boxed helpers change semantics (`_fvAref*` bounds checks, the rank check, a `Float` box),
  the typed emission must change with them** -- `typedLoopsMatchTheBoxedPathAndTheSizeLevelDeclinesThem`
  and `aTypedLoopReportsAnOutOfRangeSubscriptAsTheBoxedPathDoes` say so.
