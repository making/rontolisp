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
literals, symbols, `(aref a i [j])` with `a` a FREE symbol, `+ - * /`, and the twelve unary `Math`
functions `JvmMathFnCompiler` lowers (`sqrt exp log sin cos tan asin acos atan sinh cosh tanh`).
Anything else -- a call, `return`, `floor`, `incf`, `and`/`or`, a string, `nil` in value position, a
general array -- disqualifies it; an outer result form may only be a symbol or a number.

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
indexes exactly as `_fvAref1`/`_fvAref2` do -- `base + (int) i * cols + (int) j`, `int` arithmetic
with the same truncation (`Long.intValue()` = `L2I`) and the same association -- so the
subscript-count quirk reproduces bit for bit and an out-of-range index throws the same
`ArrayIndexOutOfBoundsException` from the same array. A store narrows with `D2F` for `float[]` and in
value position answers the value AS STORED; under `--gpu` every typed store calls `_gpuWritten`, as
`_fvAset*` does (`.kb/gpu.md`). A loop with no array and no assigned free variable is left alone
(byte-identical). Typed locals are `allocTemp` pairs (long/double take two slots) released at the
join; a loop pushing `nextLocal` past 250 stays boxed (one-byte slot operands), and
`StackMapAugmenter` merges the slot kinds to TOP at the join.

## Gates and tests

`Ctx.typedLoops` = `!optimize.prefersSizeOverSpeed()`, so **`--optimize=size` declines typed loops**
-- the JVM's first speed-for-size trade, a typed loop emitting the body up to three times
(`.kb/optimize-dead-code-elimination.md`). Off under `--dynamic`;
`-Drontolisp.debug.notypedloops=true` at COMPILE time force-disables them for A/B profiling. Arrays
require `Ctx.usesFloatArray`. Interpreter and both wasm backends are untouched. Pins:
`JvmLispCompilerTest.typedLoopsMatchTheBoxedPathAndTheSizeLevelDeclinesThem`,
`theSizeLevelChangesNothingWithoutATypedLoop`, ci-spec `jvm-typed-numeric-loops`, `ExamplesE2eTest`'s
llm `equals` stories.

## Not done

- A loop-carried fixnum accumulator on a let local or free variable (`(setq n (+ n 1))`) is rejected
  by the bound rule; a range analysis over the trip count admits it.
- Only `dotimes` is recognized; `do`/`loop ... below`/`while` could share the IR (`loop` expands to
  `tagbody`, not in the subset).
- `incf`/`decf`, `floor`/`mod`, `min`/`max`, `abs`, `and`/`or`/`not` tests, `the`, a let-bound ARRAY,
  comparisons of more than two operands -- each a small exact addition.
- **If the boxed helpers change semantics (`_fvAref*` bounds checks, the rank check, a `Float` box),
  the typed emission must change with them** -- the pinning test says so.
