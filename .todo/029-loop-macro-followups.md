# 29 - `loop` macro follow-ups (extend the supported clause subset)

The extended subset is done on all four backends — numeric/list/`=` stepping,
`with` (parallel + destructuring), `for ... in/on/across/being` (hash tables),
the seven accumulators with `into`, `while`/`until`/`repeat`/`do`/`return`/
`initially`/`finally`, `when`/`if`/`unless` with `else`/`end`, parallel `and`,
anaphoric `it`, `thereis`/`always`/`never`, `loop-finish`, and `named` +
`return-from` (wraps the expansion in `(block name ...)`, `.kb/do-return-block.md`).
One shared expansion: `LispMacroExpander.LoopExpander` (wired in `LispEvaluator`,
`Jvm/WasmExprCompiler`, `NoGcWasmCompiler.expandMacro`, `FreeVarAnalyzer`),
mechanics in `.kb/loop-iteration-heads.md`, behavior in
`doc/{en,ja}/reference/macros/loop.md`. Tests: `LispEvaluatorTest#evalLoop*`,
`JvmLispCompilerTest#compileAndRunLoop*`,
`WasmLispCompilerIntegrationTest#loopExtendedClausesCompileAndRun`, ci-spec
`loop-macro-extended-clauses`.

Landed 2026-09-14 (all in `LispMacroExpander.LoopExpander`, one shared expansion,
measured as a diff of failing test NAMES on the `iteration` chapter: **113 fixed,
0 regressed**, chapter 63.6% -> 77.0%): uninterned (`#:`) keyword spellings (45,
`loop16.lsp`), NIL as "don't bind" in `for`/`with`/`using` (15), numeric
from/limit/by sub-clauses in any order with textual eval order (12, LOOP.1.11+
bonus LOOP.2.23/3.23 which pin list-before-`by` entry order), and the named loop's
implicit block -- a named loop establishes no NIL block of its own, so bare
`return` passes through to the outer NIL block while the loop's own exits
(`return` clause, `always`/`never`/`thereis`, epilogue) become
`(return-from name ...)` (39, `loop13.lsp`). Tests:
`evalLoopUninternedKeywords` / `evalLoopNilVariable` / `evalLoopNumericAnyOrder` /
`evalLoopNamedPassesReturnThrough` (+ JVM mirrors, WASM asserts, ci-spec
`loop-macro-uninterned-keywords` / `-nil-variable` / `-numeric-any-order` /
`-named-return`).

## Remaining (re-ranked 2026-09-14 from `ansi-test/results/logs/iteration.log`,
194 failing names left)

- **Missing `program-error`/`type-error` validation (~30, all `got (NIL) want (T)`):
  `for x in '(a . b)` must signal `type-error`, duplicate destructuring vars and
  `into`/`with` collisions must signal `program-error`.** Expansion-time checks
  are backend-free; runtime checks need per-backend error-seam agreement
  (`.kb/error-handling.md`).
- **Hash-`being` destructuring (~8) and `across` destructuring (1):**
  `(loop for (u . v) being the hash-keys ...)` binds key/value then destructures;
  needs SBCL-measured semantics for which component destructures.
- **Improper-tail `append`/`nconc` (~5):** want `(A B C . WHATEVER)`, the
  reverse-based accumulation drops the dotted tail.
- **Typed accumulator/`with` init (~6):** `sum ... of-type short-float` over empty
  wants `0.0s0`, `with (a b c) of-type (fixnum float t)` wants `(0 0.0 nil)` --
  the parsed-and-discarded type must pick the zero.
- NOT loop (do not bill here): the extra second-value `T` (~60, `.todo/213`),
  package-`being` enumeration (~18, `.todo/156` Phase 5), the 8 remaining
  `:BAD`/`:GOOD` (do-family special-var scope, `DoExpander`), driver `:NOTES`.
- **`being` over a PACKAGE**: `symbols`/`present-symbols`/`external-symbols` parse,
  but are a lite no-op: rontolisp has no runtime intern table
  (`.kb/symbol-runtime-api.md`), so the package form is evaluated once for effect
  and the EMPTY sequence is iterated — `VAR` binds to nil and the body never runs
  (`LispMacroExpander.parseForBeing`). Enough for cl-who's hyperdoc table; a real
  enumeration needs the intern table first — its go/no-go is `.todo/156` Phase 5 (A1).
