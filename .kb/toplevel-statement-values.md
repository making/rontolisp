# A top-level form is a STATEMENT: nothing may be emitted only to be dropped

Scope: both compile paths (`codegen.wasm`, `codegen.jvm`). The interpreter is unaffected — it discards a top-level form's value without building anything.

`_start` (wasm) and `main` (JVM) drop every top-level form's value, so a value produced only because a form has to produce something is code no run can observe. `am.ik.rontolisp.compiler.ToplevelStatements` recognizes the two such shapes once, for both backends:

| shape | what it is | what happens |
| --- | --- | --- |
| the form IS a constant | `'CHIPZ` (what `PackageResolver` leaves for `defpackage` and the `%push-package` marker; `in-package` leaves `(setq *package* :CHIPZ)`, which `LispMacroExpander.injectMvSpillGlobal` drops wholesale when nothing reads `*package*` — `.kb/packages.md`), `nil` (an unselected `eval-when`, a `declaim`), a stray docstring, a keyword | `ToplevelStatements.prune` deletes the form from the top-level list |
| name-valued definer | `defvar`/`defparameter`/`defconstant` — bind, then return the name symbol | the top-level emitter OFFERS the form the chance to emit no name; the defvar compiler takes it |

## The offer protocol
- The definer still compiles through the ordinary `compileExpr`, keeping source-position attribution (`SourceProvenance.noteFailure` inside `compileCons`) and, on wasm, async-spine handling.
- One context field changes: `Ctx.definerNameDropped`, set to the form itself just before the call. `Wasm`/`JvmDefvarCompiler` clears it and skips the name; the emitter reads it back and emits `drop`/`pop` only if the offer was NOT taken.
- **The read-back is load-bearing.** The offer is keyed on the operator name while the backends' special-form dispatch switches on the RAW symbol name, so a spelling that passes the offer's test but is not routed to the defvar compiler (package-qualified, a future user-macro interception) leaves its value on the stack — and the emitter still drops it. Getting it wrong emits an INVALID MODULE, not a mis-optimization.
- The identity key is `== cons`, not a boolean, so a definer nested inside an init expression cannot take an offer meant for its parent.

## Rules
- **Not behind `--optimize`.** Neither shape is a trade-off; the removed code cannot be observed at any level. Same standing as [pure-builtin-fold.md](pure-builtin-fold.md).
- **Only constants are pruned.** A bare non-keyword symbol at top level is NOT effect-free: evaluating it can signal an unbound-variable error, and signalling is an effect. Neither is a call whose arguments happen to be literals — that is `PureBuiltinFolder`'s question, and anything it folds arrives here already a constant.
- `prune` only DELETES, so the cons-identity rule source positions depend on ([source-positions.md](source-positions.md)) holds trivially.

## The dead `local.tee` that came with it
A top-level `defvar` staged its assigned value in a temp local so `WasmSetqCompiler.mirrorTopLevelGlobal` could read it back — but that mirror emits nothing unless the program uses `eval` at top level. The tee is now emitted only when `WasmSetqCompiler.mirrorsTopLevelGlobal(ctx)` says the mirror will be. `setq` keeps its tee unconditionally and must: there the staged value is the form's own result.

## Re-evaluation trigger
The prune list is deliberately short — constants and the three definers. Widen it only for a shape whose evaluation provably cannot signal; "the function looks pure" is not that. A NEW definer whose value is nothing but the name it bound belongs in `isNameValuedDefiner`, and its backend compiler has to take the offer the way the defvar compilers do, or its name will be built and dropped.

## Pinned by
- `ToplevelStatementsTest` (`compiler`) — what is pruned, what is not, that surviving forms keep their identity, and the definer/rebind classification.
- `WasmLispCompilerTest.aTopLevelFormThatIsNothingButAConstantEmitsNothing` — a program padded with constant top-level forms compiles BYTE-identically to one without them.
- `WasmLispCompilerTest.aTopLevelDefinerDoesNotBuildTheNameSymbolItReturns` — lengthening a top-level `defparameter`'s name does not change module size, and the name does not appear in the bytes.
- `JvmLispCompilerTest.aDefinerWhoseValueIsReadStillYieldsTheNameSymbol` — the for-effect path is the top-level statement position and nothing else.

## User-visible corollary: a run's EXIT CODE
The value of the last top-level form is dropped on every backend, the interpreter included, so `(rove:run-suite *package*)` at the end of a test file says nothing to the shell and a red suite exits 0 — exactly as `sbcl --script` behaves, and deliberately kept. A program wanting its own status calls `uiop:quit`; a rove suite gets one from the `rontolisp test` subcommand, which wraps the target in a runner that reads the verdict and quits with it ([asdf.md](asdf.md), "rontolisp test"). A stderr hint from the plain run path was weighed and rejected — do not re-propose a silent status change.

## Related
- [pure-builtin-fold.md](pure-builtin-fold.md) — the other always-on reduction; turns a constant-argument call into the constant this pass deletes.
- [optimize-dead-code-elimination.md](optimize-dead-code-elimination.md) — the `--optimize` shakers, which work by NAME reachability and could never see a value built and dropped inside one live body.
- [wasm-gc-strings.md](wasm-gc-strings.md) — why `_str_build` allocates per call site even when the bytes behind it are shared (the string table deduplicates data-section BYTES, not the build).
- [wasm-function-body-size.md](wasm-function-body-size.md) — the chunker whose one top-level function this shrinks.
- [packed-integer-vectors.md](packed-integer-vectors.md) — what remains in that chunk is chipz's own literal vectors built element by element; shrinking further means changing how a large literal vector is CONSTRUCTED, not removing dead code.
