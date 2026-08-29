# JVM typed numeric loops (`dotimes` over packed float arrays)

**Invariant: a typed loop must never change a result, an error shape, or an observable
side effect -- it is a speculation with the ordinary emission as its total fallback, and
where the two paths both run they produce the same bits.** Introduced by `.todo/457`
(2026-08-22), the JVM analogue of the wasm backend's unboxed locals + fusion
(`.kb/wasm-unboxed-locals.md`, `.kb/wasm-int-fusion.md`) narrowed to the shape every
numeric program's hot loops have: a `dotimes` whose body reads and writes packed
single/double-float arrays through fixnum index math and float arithmetic.

## Why

A compiled Lisp numeric loop boxed every value: `aref` on a packed array called
`_fvAref1` and answered a fresh `Double`, `+`/`*` went through `_add(Object, Object)` and
allocated again, `let` temporaries and `setq` accumulators lived in `Object` slots, the
counter was a `Long` (`.todo/412` is the integer-fusion half of the same observation).
~60 ns an iteration where the JVM does the same work in ~2 ns. On `examples/llama2` that
glue -- softmax, RoPE, the attention copies, the KV append -- was ~2 ms of a 3.1 ms token
under `--simd --parallel`, more than the GEMVs.

## Why the unit is the loop

The wasm fusion is per EXPRESSION: every leaf is guarded, every `_fx_*` step checks
overflow, and a bail recomputes the pure tree from the same leaves. A loop is not pure
-- its stores and `setq`s have happened -- so there is no mid-loop bail: once the typed
path is entered it must be total. That is why every runtime assumption is a guard at
ENTRY (array kinds, `Long`/`Double` classes, the int range), why fixnum arithmetic is
admitted only under a static magnitude bound (no `addExact` recovery is possible), and
why one form outside the subset rejects the whole loop rather than boxing one node.

## What qualifies (the typed subset)

`JvmTypedLoopCompiler.tryCompile` is tried first at `JvmExprCompiler`'s `dotimes` case;
when it answers false nothing was emitted and `expandDotimes` runs as before. The body
must consist ONLY of:

- `(let ((v init) ...) body)` / `let*` (no `declare`-less special names; a `(declare ...)`
  body form is a no-op), `progn`, `(declare ...)`;
- `(setq v e)` / `(setf v e)`, `(setf (aref a i [j]) e)` / `(%aset ...)`, multi-pair forms;
- `(if test a [b])`, `(when test ...)`, `(unless test ...)` whose test is ONE binary
  `< > <= >= =`; an `if` in value position needs both branches of one type;
- a nested `dotimes` with no result form;
- expressions: integer and double literals, symbols, `(aref a i)` / `(aref a i j)` with
  `a` a FREE symbol (not bound inside the loop), `+ - * /` (n-ary, the left fold
  `JvmArithCompiler` uses; unary `-` and `/`), the twelve unary `java.lang.Math` functions
  `JvmMathFnCompiler` lowers (`sqrt exp log sin cos tan asin acos atan sinh cosh tanh`).

Anything else -- a call, `return`, `floor`, `incf`, `and`/`or`, a string, `nil` in value
position, a general array -- disqualifies the WHOLE loop (there is no mid-loop exit from
the typed path; see "Why the unit is the loop"). The outer `dotimes` may carry a result
form only when it is a symbol or a number (it runs after the loop with the counter bound
to its final value); a nested one must have none.

Two static types, `LONG` and `DOUBLE`, and the rules that type a body:

- the counter is `LONG` in `[0, count)`; the count must type `LONG`;
- an `aref` is `DOUBLE` (single-float reads widen, exactly as `_fvAref*` does);
- `+ - *` over two `LONG`s is `LONG` when a **magnitude bound** proves the result fits
  (`BigInteger` bounds: a literal is `|v|`, a guarded free fixnum is `2^31`, a counter
  is its count's bound, `+`/`-` add, `*` multiply; above `Long.MAX_VALUE` the loop is
  ineligible -- `_add` would promote to `BigInteger` there); anything with a `DOUBLE`
  operand is `DOUBLE` (float contagion: a `LONG` operand converts with `L2D`, which is
  `Long.doubleValue()`, which is what `_dbl` does); `/` over two `LONG`s is a ratio on
  the boxed path and ineligible here;
- a `let` local takes its init's type; a `setq` of a different type, or of a `LONG` with
  a larger bound, is ineligible (no dual representation -- a counter-shaped
  `(setq n (+ n 1))` on a let local is therefore NOT typed today);
- a comparison over two `LONG`s is `LCMP`; otherwise both sides convert and compare with
  javac's NaN rule -- `DCMPG` for `<`/`<=`, `DCMPL` for the rest -- which is exactly the
  bitmask `_cmpb` answers (NaN fails every operator; `unless` keeps the same compare and
  flips the jump, so NaN still runs its body on both paths).

## Free variables: speculation + guard

Every symbol the body reads that is not bound inside the loop is a FREE variable, read
ONCE at entry (through `compileSymbolRef`, so locals, captures and globals all qualify;
nothing in the loop can change them -- there are no calls) and GUARDED -- except a raw
double local, which is already raw and is used in place:

| speculated as | when | guard at entry |
|---|---|---|
| array (rank 1 or 2) | it is the array of an `aref`/`aset`, with one consistent subscript count | `instanceof float[]` for the single variant, `double[]` for the double variant |
| `LONG` | it appears inside a subscript or count expression, or feeds one through a `let` binding / `setq` (a fixpoint over the body), or it is assigned and never assigned a double | `instanceof Long` AND `(int) v == v` (the 2^31 bound the typing used) |
| `DOUBLE`, strict | it is assigned a double expression anywhere in the loop | `instanceof Double` |
| `DOUBLE`, Long-accepting | read-only, and every use is a position where the boxed path converts a Long to double anyway: beside a static-double operand of `+ - * /` or a comparison, the argument of a Math function, the value of an `aset` | `instanceof Double`, else `instanceof Long` + `L2D` |
| `DOUBLE`, strict, IN PLACE | it is a raw double local -- a bound-declared float `let` binding in a `Ctx.rawDoubleLocals` slot (`.kb/jvm-double-arithmetic.md`) | NONE |

A raw double local is the one free variable that needs no guard and no copy: the slot IS
the value (always authoritative, no flag and no boxed shadow), so the loop `dload`s and
`dstore`s it in place. Nothing is read at entry, nothing is written back at exit, and a
loop whose only assigned free variables are raw doubles needs no catch-all handler either
-- an exception mid-loop leaves the slot holding exactly what the boxed path's `setq`s
would have left there -- so such a loop is eligible even with operands of an enclosing
expression live on the stack. It is declined only where a double cannot be what the body
wants: used as a subscript, a count, or an array (`freeVar`); a `setq` of a `LONG`-typed
value falls out of the ordinary type rule and keeps the whole loop boxed.

A guard that fails jumps to the bail label, where the ordinary `expandDotimes` emission
sits -- exactly what the loop compiled to before, so a wrong speculation costs speed and
nothing else. An assigned free variable must be a plain JVM-slot local (not captured,
not special, not a global); its typed copy is written back (`Long.valueOf` /
`Double.valueOf`) at loop exit, and ALSO from a catch-all handler that rethrows, so a
`handler-case` around a loop that dies of an out-of-bounds index still sees the
accumulators the boxed path would have left (`typedLoopsMatchTheBoxedPathAndTheSizeLevelDeclinesThem`
pins it). A loop that needs that handler while the operand stack holds operands of an
enclosing expression stays boxed (entering a handler discards the stack).

Speculation restarts: an assigned non-index variable starts `LONG` and promotes to
`DOUBLE` on the first double assignment; a Long-accepting variable demotes to strict on
the first non-forcing use. Each change re-runs the typing (bounded, monotone).

## Arrays: two variants

All arrays of one loop must be `float[]` (single variant) or all `double[]` (double
variant); a mixed set bails. Each variant hoists per array the typed reference, the data
offset `1 + rank` and (rank 2) the column count `header[2]`, then indexes exactly as
`_fvAref1`/`_fvAref2` do -- `base + (int) i` and `base + (int) i * cols + (int) j`, `int`
arithmetic with the same truncation (`Long.intValue()` is `L2I`) and the same
association -- so the subscript-count quirk of `.todo/479` reproduces bit for bit, and an
out-of-range index throws the same `ArrayIndexOutOfBoundsException` from the same array.
A store narrows with `D2F` for `float[]`; a store in value position answers the value AS
STORED (narrowed, like `emitStoredValue`). Under `--gpu` every typed store calls
`_gpuWritten` on its array, as the `_fvAset*` helpers do (`.kb/gpu.md`, residency
invalidation).

A loop with no array and no assigned free variable is left alone (nothing to win, and
the emission stays byte-identical there).

## Shape of the emission

```
guards (free numerics: read, instanceof, unbox; arrays: read)      -- bail on failure
[arrays] instanceof float[] each? -> hoist, SINGLE variant, goto join
         instanceof double[] each? -> hoist, DOUBLE variant, goto join  -- else bail
[no arrays] the one variant, goto join
variant = { lim = count; ctr = 0; loop { LCMP exit; body; ctr++ } }   -- exception range
          write back assigned; value (nil / result form); goto join
          handler: write back; athrow
bail:  JvmExprCompiler.compileExpr(expandDotimes(cons))               -- the old code
join:
```

Typed locals are `allocTemp` pairs (long/double are two slots); everything is released
at the join. A loop that would push `nextLocal` past 250 stays boxed (one-byte slot
operands, `.todo/137`). The `StackMapAugmenter` merges the slot kinds to TOP at the join
on its own. Literals push raw through `JvmEmitHelper.emitRawLong`/`emitRawDouble`, the
unboxed halves `compileLong`/`compileDouble` were split into.

## Gates

`Ctx.typedLoops` -- `!optimize.prefersSizeOverSpeed()`, i.e. **`--optimize=size` declines
typed loops** (they are the JVM's first speed-for-size trade: a typed loop emits the body
up to three times, and `.kb/optimize-dead-code-elimination.md` / `doc/*/compiling/jvm.md`
say so); off under `--dynamic`; `-Drontolisp.debug.notypedloops=true` at COMPILE time
force-disables them for A/B profiling; arrays require `Ctx.usesFloatArray` (otherwise no
guard could pass). The interpreter and both wasm backends are untouched: same values, the
JVM class only gets faster.

## Numbers (GB10, 2026-08-22)

- `tick.lisp`'s softmax over 111 scores: 21 us -> 0.73 us a call.
- `examples/llama2` stories15M, the 222-token `-t 0` story, medians of three, story
  byte-identical: JVM `--simd` 221 -> 315 tok/s, `--simd --parallel` 319 -> ~575; with
  the GEMV row-threshold change that landed with it (`.kb/vec.md`,
  `MATVEC_ROW_THRESHOLD`) 336 / 637 (684 at `RONTOLISP_THREADS=10`), JVM scalar 66 ->
  104, `--gpu --simd` 278 -> 458 -- above kishida's Java Vector API port of run.c on
  the same thread counts (312 one thread, 513 as published on 20), measured beside it.
  The README of the example carries the table.

## Numbers: composing with a declared-float accumulator (todo-576)

Before todo-576 a `dotimes` whose free accumulator was a bound-declared float local was
INELIGIBLE (`resolvable` knew `locals`/`captures`/`globals` only), so the two features
cancelled: the whole loop fell back to the boxed emission -- correct, and slower than
either feature alone.

2M-iteration `(setq sum (+ sum (* (aref v i) (aref v i))))` over a `double-float` array,
`sum` a bound-declared float local, `-o Bench.class` under `java`, best of 5 (2026-08-29,
64-core linux/x86-64, GraalVM 25):

| ms per call | boxed fallback (before) | typed in place (after) |
| --- | ---: | ---: |
| first call (cold) | 20 | **8** |
| steady state (call 20) | 1-2 | 1-2 |
| C1-only, steady (`-XX:TieredStopAtLevel=1`) | 108 | **2** |

The steady-state claim is that there is NO steady-state difference: Graal's escape
analysis already removes every box the boxed emission allocates, exactly as
`.kb/jvm-double-arithmetic.md`'s todo-569 measurement found. The win is the tier a
short-lived run actually executes in -- C1 does no escape analysis, so the boxed
emission's `_fvAref1` + `_add` + `Long`/`Double` allocations are real there. The typed
emission for this shape is also the CHEAPEST the typed loop has: +305 bytes against the
same loop with an undeclared accumulator's +452, because there is no guard, no entry
read, no write-back and no handler.

`bench-report/programs/matmul.lisp`'s diagonal-sum loop -- the live example the item was
filed from -- is still boxed, for an unrelated reason: its result form `(round diagonal)`
is a call, and only a symbol or a number is taken along. 200 iterations, so it costs
nothing. Its `matmul` kernel was never affected: the typed path claims the whole
`i`/`k`/`j` nest from the outer `dotimes`, so `aik` is a typed LET variable there and
never becomes a raw double slot at all.

## Pinning tests

`JvmLispCompilerTest.typedLoopsMatchTheBoxedPathAndTheSizeLevelDeclinesThem` (every
shape above, the guard failures, the NaN rule, the exception write-back; and that `SIZE`
compiles the same program to different bytes, proving the default build emitted the
typed loops), `theSizeLevelChangesNothingWithoutATypedLoop` (a program without one stays
byte-identical across the levels), the `jvm-typed-numeric-loops` ci-spec case (all four
backends agree on the outputs), and `ExamplesE2eTest`'s llama2 `equals` stories.

## Re-evaluation triggers / not done

- A loop-carried fixnum accumulator on a let local or a free variable
  (`(setq n (+ n 1))`) is rejected by the bound rule; a proper range analysis over the
  trip count (`count * step`) would admit it. Do it when a profile shows such a loop.
- Only `dotimes` is recognized; `do`/`loop ... below`/`while` shapes could share the
  same IR (the analyzer works on the unexpanded forms -- `loop` expands to `tagbody`,
  which is not in the subset).
- `incf`/`decf`, `floor`/`mod`, `min`/`max`, `abs`, `and`/`or`/`not` tests, `the`, a
  let-bound ARRAY (`(let ((row (aref kc l h))) ...)`), comparisons of more than two
  operands: each is a small, exact addition to the subset; none was needed by llama2.
- If the boxed helpers ever change their semantics (`_fvAref*` bounds checks,
  `.todo/479`'s rank check, a `Float` box), the typed emission must change with them --
  the test above is what says so.
