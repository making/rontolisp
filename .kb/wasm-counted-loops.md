# WASM counted loops (`dotimes`, and a `let` + `while` induction variable)

**Invariant: a counted loop's induction variable is an `i64` slot with NO boxed shadow,
and the eligibility scan is the whole proof that nothing can observe it any other way.**

Two entry points, wasm-GC backend only (Preview 1 AND `--component`; interpreter, JVM and
`--no-gc` keep the `LispMacroExpander.expandDotimes` expansion):

- `WasmDotimesCompiler` — `dotimes` over a LITERAL bound, emitted whole.
- `WasmCountedLoopCompiler` — a `let` binding a `while` in the body steps, which is what
  `loop`'s numeric `for` head lowers to ([loop-iteration-heads.md](loop-iteration-heads.md));
  `WasmLetCompiler`/`WasmWhileCompiler` still emit the loop, only the REPRESENTATION changes.

**Not gated on `--optimize=size`** (unlike [wasm-int-fusion.md](wasm-int-fusion.md) and
[wasm-unboxed-locals.md](wasm-unboxed-locals.md)): the loop head always shrinks AND always
runs faster. `(dotimes (i 1000000))` is a 203-byte module, was 1,987; no overflow check on
the step and no range test on a read, the literal bound deciding both statically.

## The counted `RawLocal`
`ctx.rawLocals` holds `WasmIntFusionCompiler.RawLocal.counted(slot)`, whose `shadowSlot` is
`-1` (canonical constructor enforces "counted ⇔ no shadow"). Reusing that map is what makes
shadowing, `defvar` collision and Lisp-2 resolution work with no new rules
(`WasmExprCompiler.compileSymbolRef`, `WasmLetCompiler`).

Five sites branch on `counted()`: `evalLeaves` and `emitLeafUnboxes` do nothing (`snapI64`
IS the counted slot); `emitFallback` and `emitRawLocalBoxedRead` emit `i32.wrap_i64;
ref.i31`; `WasmSetqCompiler.compileForEffect` calls `WasmCountedLoopCompiler.compileStep`
(one `i64.add`). `compileRawStore` **throws** on a counted target and `compileStep` throws on
any value shape that is not the proven step, so an assignment the scan missed fails the
compile loudly. The i64 slot comes from `ctx.nextI64Local` with the watermark restored after
the body.

## Eligibility
Anything failing falls back to the ordinary representation.

- `dotimes`: the COUNT is a literal integer in `[0, 2^30 - 1]` — the count, not just the
  last index, since the result form sees the variable holding it.
- `let`+`while` (`WasmCountedLoopCompiler.analyze`, run on every `let` and looking at the
  WHOLE form, since `loop`'s head presents as the outermost binding with the `while` several
  bodies down): init a literal integer within `±(2^30 - 1)`; the only assignment is
  `(setq VAR (+ VAR n))`, `(+ n VAR)` or `(- VAR n)` with a literal non-zero `n`, as a DIRECT
  element of a `while` body list (statement position is the only assignable position); that
  `while`'s test bounds the variable against a literal integer, under any number of `not`s,
  possibly as one conjunct of an `and` (recursion into `and` only on the POSITIVE side); and
  both the init and ONE STEP PAST the bound must box exactly as an i31, that value being
  observable (`(loop for i from 1 to 4 finally (return i))` is 5).
- Both: non-keyword symbol, not special, not duplicated in the binding list, not captured by
  a nested lambda (`FreeVarAnalyzer` / `capturedInLet`).
- Both: **nothing else assigns it.** Every user macro is already expanded, so the
  writing-operator set is CLOSED: `setq`/`psetq`/`setf`/`psetf`/`multiple-value-setq`/`incf`/
  `decf`/`push`/`pushnew`/`pop`/`rotatef`/`shiftf`/`remf` plus the `defvar` family. The scan
  takes each PLACE argument; a place FORM writes its own sub-place (`(setf (aref s i) c)` ->
  `s`), the rest being pure subscript READS, which keeps `(dotimes (i 16) (setf (aref buf i)
  0))` eligible. Any other accessor is treated as writing everything it mentions. The scan is
  blind to lexical scope and quoting, which can only narrow eligibility.
- Both: not in an async body (`ctx.asyncResume` owns locals), not under `--dynamic`, and not
  at top level in a program using `eval` (the ordinary lowering MIRRORS each step into the
  eval global environment via `WasmSetqCompiler.mirrorTopLevelGlobal`). `WasmLetCompiler`
  decides the counted binding BEFORE the dual one; counted is stronger, so it wins.

`WasmComparisonCompiler.tryCompileConditionI32` takes a `negated` request and answers a `not`
by flipping it instead of emitting `i32.eqz`, so the head's `(not (> i limit))` becomes one
bare `i64.gt_s` feeding `br_if`; general win for every `while`/`if` over a negated comparison.

Open: a NON-literal bound (`for i from 0 below (length v)`) cannot reuse this shape — a
runtime limit needs an entry guard whose failing arm needs a generic loop, i.e. the body
emitted TWICE, so it would have to be gated on `WasmIntFusionCompiler.speedTradesEnabled`. A
counted read feeding a float coercion still round-trips through a box
([wasm-shared-coercion.md](wasm-shared-coercion.md)).

## Tests
- `WasmLispCompilerIntegrationTest.countedDotimesLoopsMatchTheExpandedLowering` + ci-spec
  `counted-dotimes`.
- `WasmLispCompilerIntegrationTest.countedNumericForHeadsMatchTheExpandedLowering` + ci-spec
  `counted-numeric-for` (with the five refusals: assigned, captured, non-integral limit,
  limit at the i31 ceiling, hand-written `let` + `while`). Both also run at `--optimize=size`.
