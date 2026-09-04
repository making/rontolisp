# JVM typed numeric loops (`dotimes` over packed float arrays)

**Invariant: a typed loop must never change a result, an error shape, or an observable side
effect -- it is a speculation with the ordinary emission as its total fallback, and where
both paths run they produce the same bits.** The JVM analogue of
`.kb/wasm-unboxed-locals.md` + `.kb/wasm-int-fusion.md`, narrowed to a `dotimes` whose body
reads and writes packed single/double-float arrays through fixnum index math and float
arithmetic.

**The unit is the LOOP, not the expression**: a loop is not pure (its stores and `setq`s
have happened), so there is no mid-loop bail -- once entered the typed path must be total.
Hence every assumption is a guard at ENTRY, fixnum arithmetic is admitted only under a
static magnitude bound (no `addExact` recovery is possible), and one form outside the subset
rejects the WHOLE loop rather than boxing one node.

## The typed subset

`JvmTypedLoopCompiler.tryCompile` runs first at `JvmExprCompiler`'s `dotimes` case; on false
nothing was emitted and `expandDotimes` runs as before. The body may contain ONLY:

- `(let ((v init) ...) body)` / `let*`, `progn`, `(declare ...)` (a no-op);
- `(setq v e)` / `(setf v e)`, `(setf (aref a i [j]) e)` / `(%aset ...)`, multi-pair forms;
- `(if test a [b])`, `(when ...)`, `(unless ...)` whose test is ONE binary `< > <= >= =`; an
  `if` in value position needs both branches of one type;
- a nested `dotimes` with NO result form;
- integer/double literals, symbols, `(aref a i)` / `(aref a i j)` with `a` a FREE symbol,
  `+ - * /` (n-ary left fold as `JvmArithCompiler`; unary `-` and `/`), and the twelve unary
  `java.lang.Math` functions `JvmMathFnCompiler` lowers
  (`sqrt exp log sin cos tan asin acos atan sinh cosh tanh`).

Anything else (a call, `return`, `floor`, `incf`, `and`/`or`, a string, `nil` in value
position, a general array) disqualifies the loop. The OUTER `dotimes` may carry a result
form only when it is a symbol or a number (run after the loop with the counter at its final
value); a result form that is a CALL keeps the loop boxed.

Two static types, `LONG` and `DOUBLE`:

- counter is `LONG` in `[0, count)`; the count must type `LONG`;
- an `aref` is `DOUBLE` (single-float reads widen, as `_fvAref*` does);
- `+ - *` over two `LONG`s is `LONG` only under a **magnitude bound** (`BigInteger` bounds:
  literal `|v|`, guarded free fixnum `2^31`, counter = its count's bound, `+`/`-` add, `*`
  multiply; past `Long.MAX_VALUE` the loop is ineligible, since `_add` promotes to
  `BigInteger` there). Any `DOUBLE` operand makes it `DOUBLE` (a `LONG` converts with `L2D`,
  which is what `_dbl` does); `/` over two `LONG`s is a ratio on the boxed path, ineligible;
- a `let` local takes its init's type; a `setq` of a different type, or of a `LONG` with a
  larger bound, is ineligible (no dual representation, so a counter-shaped
  `(setq n (+ n 1))` on a let local is NOT typed today);
- two `LONG`s compare with `LCMP`; otherwise both convert and use javac's NaN rule --
  `DCMPG` for `<`/`<=`, `DCMPL` for the rest -- exactly the bitmask `_cmpb` answers (NaN
  fails every operator; `unless` keeps the compare and flips the jump).

## Free variables: speculation + guard

Read ONCE at entry through `compileSymbolRef` (locals, captures, globals all qualify --
nothing in the loop can change them, there are no calls) and guarded:

| speculated as | when | guard |
|---|---|---|
| array (rank 1/2) | it is an `aref`/`aset` array with one consistent subscript count | `instanceof float[]` (single variant) / `double[]` (double variant) |
| `LONG` | in a subscript or count expression, or feeding one through a `let`/`setq` (fixpoint over the body), or assigned and never assigned a double | `instanceof Long` AND `(int) v == v` |
| `DOUBLE`, strict | assigned a double expression anywhere | `instanceof Double` |
| `DOUBLE`, Long-accepting | read-only, every use a position where the boxed path converts a Long to double anyway | `instanceof Double`, else `instanceof Long` + `L2D` |
| `DOUBLE`, strict, IN PLACE | a raw double local (bound-declared float `let` binding in a `Ctx.rawDoubleLocals` slot, `.kb/jvm-double-arithmetic.md`) | NONE |

A raw double local needs no guard, no entry read, no write-back and no catch-all handler:
the slot IS the value, so the loop `dload`s/`dstore`s it in place, and an exception mid-loop
leaves exactly what the boxed path's `setq`s would have left -- so such a loop is eligible
even with an enclosing expression's operands live on the stack. Declined where a double
cannot be what the body wants: used as subscript, count or array (`freeVar`); a `setq` of a
`LONG`-typed value keeps the whole loop boxed.

A failed guard jumps to the bail label holding the ordinary `expandDotimes` emission, so a
wrong speculation costs speed only. An ASSIGNED free variable must be a plain JVM-slot local
(not captured, special or global); its typed copy is written back (`Long.valueOf` /
`Double.valueOf`) at loop exit AND from a catch-all handler that rethrows, so a
`handler-case` around a loop that dies of an out-of-bounds index still sees the accumulators
the boxed path would have left. A loop needing that handler while an enclosing expression's
operands are on the stack stays boxed (entering a handler discards the stack).

Speculation restarts: an assigned non-index variable starts `LONG` and promotes to `DOUBLE`
on the first double assignment; a Long-accepting variable demotes to strict on the first
non-forcing use. Each change re-runs the typing (bounded, monotone).

## Arrays: two variants

All arrays of one loop must be `float[]` or all `double[]`; mixed bails. Each variant hoists
per array the typed reference, the data offset `1 + rank` and (rank 2) the column count
`header[2]`, then indexes exactly as `_fvAref1`/`_fvAref2` do -- `base + (int) i` and
`base + (int) i * cols + (int) j`, `int` arithmetic with the same truncation
(`Long.intValue()` = `L2I`) and the same association -- so the subscript-count quirk
reproduces bit for bit and an out-of-range index throws the same
`ArrayIndexOutOfBoundsException` from the same array. A store narrows with `D2F` for
`float[]`; in value position it answers the value AS STORED (like `emitStoredValue`). Under
`--gpu` every typed store calls `_gpuWritten`, as `_fvAset*` does (`.kb/gpu.md`). A loop
with no array and no assigned free variable is left alone (byte-identical).

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

Typed locals are `allocTemp` pairs (long/double take two slots), released at the join. A
loop pushing `nextLocal` past 250 stays boxed (one-byte slot operands). `StackMapAugmenter`
merges the slot kinds to TOP at the join. Literals push raw through
`JvmEmitHelper.emitRawLong`/`emitRawDouble`.

## Gates

`Ctx.typedLoops` = `!optimize.prefersSizeOverSpeed()`, so **`--optimize=size` declines typed
loops** -- the JVM's first speed-for-size trade, since a typed loop emits the body up to
three times (`.kb/optimize-dead-code-elimination.md`, `doc/*/compiling/jvm.md`). Off under
`--dynamic`. `-Drontolisp.debug.notypedloops=true` at COMPILE time force-disables them for
A/B profiling. Arrays require `Ctx.usesFloatArray`. Interpreter and both wasm backends are
untouched.

## Pinning tests

`JvmLispCompilerTest.typedLoopsMatchTheBoxedPathAndTheSizeLevelDeclinesThem` (every shape,
the guard failures, the NaN rule, the exception write-back, and that `SIZE` compiles the
same program to different bytes), `theSizeLevelChangesNothingWithoutATypedLoop`, ci-spec
`jvm-typed-numeric-loops`, and `ExamplesE2eTest`'s llama2 `equals` stories.

## Not done / re-evaluation triggers

- A loop-carried fixnum accumulator on a let local or free variable (`(setq n (+ n 1))`) is
  rejected by the bound rule; a range analysis over the trip count (`count * step`) admits it.
- Only `dotimes` is recognized; `do`/`loop ... below`/`while` could share the IR (the
  analyzer works on unexpanded forms; `loop` expands to `tagbody`, not in the subset).
- `incf`/`decf`, `floor`/`mod`, `min`/`max`, `abs`, `and`/`or`/`not` tests, `the`, a
  let-bound ARRAY (`(let ((row (aref kc l h))) ...)`), comparisons of more than two operands
  -- each a small exact addition.
- **If the boxed helpers change semantics (`_fvAref*` bounds checks, the rank check, a
  `Float` box), the typed emission must change with them** -- the pinning test says so.
