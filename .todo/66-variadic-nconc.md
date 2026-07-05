# 66: Variadic `nconc`

Split off from `.todo/65-cl-utilities-support.md` (cl-utilities stdlib residue,
step 2). Single-session sized.

## Goal

Make `nconc` accept any arity (0, 1, 2, 3+), matching CL. Currently it is 2-arg
only on every backend.

CL semantics: `(nconc)` = nil; `(nconc x)` = x; `(nconc a b c ...)` destructively
links each non-nil list's last cdr to the next argument, returns the first
non-nil argument (later args are not copied; the LAST arg may be any object).

## Why

cl-utilities' own `split-sequence.lisp` calls variadic `nconc`. Broadly useful
CL primitive regardless.

## Current state

- **Interpreter**: `Environment.java` ~line 1411, `requireArgCount(NCONC, args, 2)`
  -- hard 2-arg.
- **Compile path (JVM + WASM)**: both route through
  `LispMacroExpander.expandNconc` (line ~3242), which reads `parts.get(1)` and
  `parts.get(2)` only -- also hard 2-arg. JVM `JvmExprCompiler` line ~361 and
  WASM `WasmExprCompiler` line ~361 call it.
- `BuiltinFunctionWrappers` has an `nconc` entry (check its arity; the wrapper
  must become variadic like the other naturally-variadic wrappers -- see commit
  "Make naturally-variadic builtin wrappers accept any arity").

## Plan

1. **Interpreter** (`Environment`): fold left over all args -- link each
   consecutive pair, skipping nil arguments, returning the first non-nil (or nil
   if none). Run `LispEvaluatorTest`.
2. **Compile path**: simplest is to make `expandNconc` right-associate into
   nested 2-arg lets: `(nconc a b c)` -> reuse the existing 2-arg body with
   `b := (nconc b c)` recursively; `(nconc)` -> nil; `(nconc x)` -> x. Then JVM
   and WASM need no change (they already call `expandNconc`). Run
   `JvmLispCompilerTest` + `WasmLispCompilerIntegrationTest`.
3. `BuiltinFunctionWrappers`: variadic wrapper so `(apply #'nconc lists)` and
   `#'nconc` work.
4. ci-spec case + docs (`doc/en,ja/reference/functions/nconc.md` -- update the
   signature/example to show 3+ args).

## Acceptance

`(nconc)`, `(nconc '(1))`, `(nconc '(1 2) '(3 4) '(5))`, `(nconc nil '(1) nil '(2))`,
`(apply #'nconc (list '(1) '(2) '(3)))` all correct on all four backends
(interpreter / JVM / WASM P1 / WASM component). Native E2E green.
