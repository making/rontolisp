> **Update 2026-07-05:** the position family now supports
> `:start`/`:end`/`:from-end` (+ `:test-not` on `position`) via the shared
> `buildPositionScan`, and `position-if-not` exists. The other sequence
> functions below still take :test/:key only.
>
> **Update 2026-07-06 (todo 65):** the position family's FIRST-CLASS values
> now take the full keyword set too — variadic `BuiltinFunctionWrappers`
> entries re-extract runtime keywords via getf and feed the call-position
> expansion, and the interpreter registrations moved into `LispEvaluator`
> (`positionScanValues`) so `(apply #'position item seq other-keys)` works
> (cl-utilities' split-sequence). `reduce` gained `:start`/`:end` (subseq
> lowering) and its `:key` now coerces a string sequence to a char list
> before the mapcar. `stable-sort` (with `:key`) and `copy-seq` exist.

# `:test` / `:key` keyword args for sequence & alist functions

**Status: DONE (2026-07-04)** -- `:test` and `:key` are supported on
`member`/`assoc`/`rassoc` and on `find`/`position`/`count`/`remove`/`delete`/
`remove-duplicates`/`union`/`intersection`/`set-difference`/`adjoin`/
`substitute`/`nsubstitute`, on all backends (interpreter / JVM / WASM Preview 1
/ WASM component; native E2E case `sequence-and-alist-test-key-keywords`).

Implementation shape:

- `LispMacroExpander` carries the whole compile path: each expander parses
  `:test`/`:key` (`keywordValue`) and builds the match via the shared
  `testMatchForm`/`keyedForm` helpers; the set-style functions
  (`adjoin`/`union`/`intersection`/`set-difference`/`remove-duplicates`)
  forward both keywords to their inner `member` (`memberCallForm`) with the
  item side pre-keyed, so `:key` applies to both operands (CL semantics).
- The interpreter routes the 12 sequence functions through the same expansions
  in `LispEvaluator.evalCons` (the pattern `rassoc` already used), so keyword
  behavior is backend-identical by construction; `member`/`assoc`/`rassoc`
  keep their `LispEvaluator` registrations (needed for first-class use) with
  `:key` applied via `apply`.
- Unknown keywords (`:from-end`, `:start`, `:count`, ...) are rejected loudly
  (`requireTestKeyKeywords`, both in the expander and the interpreter
  registrations) instead of being silently ignored.

## Remaining follow-ups (not planned, note-only)

- **Runtime `eval` on the compiled backends ignores `:test`/`:key`** (the
  emitted eval interpreter resolves these functions through the fixed-arity
  compiled registry, so extra keyword args are dropped and the compare stays
  `eql`). Pre-existing behavior -- it was already true for `member`/`assoc`
  `:test` -- now documented in `doc/*/guides/eval-limitations.md`. Fixing it
  means teaching `Jvm/WasmEvalRuntimeBuilder` keyword parsing per function.
- The `-if`/`-if-not` variants other than `position-if`/`position-if-not`
  (`find-if`, `remove-if`, `count-if`, `member-if`, `delete-if`, ...) take no
  `:key` (CL allows one); add only if real code needs it. The position pair got
  `:key` (plus `:start`/`:end`/`:from-end`) with the 2026-07-05 update above.
- First-class values (`#'find` etc. via `BuiltinFunctionWrappers` and the
  `Environment` registrations) stay fixed-arity -- keywords work in call
  position only, except `member`/`assoc`/`rassoc` whose interpreter
  registrations do parse them.
