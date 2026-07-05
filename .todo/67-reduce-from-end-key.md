# 67: `reduce` with `:from-end` (and `:key`)

Split off from `.todo/65-cl-utilities-support.md` (cl-utilities stdlib residue,
step 2). Single-session sized.

## Goal

Extend `reduce` to accept `:from-end t` (right fold) and `:key fn` (apply `key`
to each sequence element before folding). Today only `:initial-value` is
supported.

CL semantics with `:from-end t`: elements are combined right-to-left and the
accumulator is the RIGHT argument: `(reduce #'f '(a b c) :from-end t)` =
`(f a (f b c))`; with `:initial-value i`, `(f a (f b (f c i)))`. `:key` maps each
element (NOT the initial value).

## Why

cl-utilities `compose` and `with-collectors` use `(reduce ... :from-end t)`.

## Current state

- **Interpreter**: `LispEvaluator.java` ~line 358 -- handles arity 2 and the
  4-arg `:initial-value` form only; anything else throws "expects (reduce fn list)
  or (reduce fn list :initial-value init)". Core fold is `reduceValues(fn, acc,
  list)` (left fold).
- **JVM**: `JvmReduceCompiler` (wired at `JvmExprCompiler` ~line 432).
- **WASM**: `WasmReduceCompiler` (wired at `WasmExprCompiler` ~line 410).
- `LispNames.FROM_END_KEYWORD`, `KEY_KEYWORD`, `INITIAL_VALUE_KEYWORD` already
  exist (used by remove/position/find keyword parsing).

## Plan

Keyword args on `reduce` are position-independent (`:from-end`/`:key`/
`:initial-value` in any order), so parse them as a keyword plist after the
sequence. Decide per backend whether to:
  (a) keep `reduce` a real builtin and add keyword parsing + a right-fold path,
      or
  (b) lower the keyworded form via `LispMacroExpander` (reverse the list for
      `:from-end`, wrap the fn to swap args, map `:key`) into the existing
      2/4-arg builtin -- mirrors how remove/position keyword handling is done.
Option (b) likely keeps all three backends in sync with the least codegen.

1. Interpreter first (`LispEvaluatorTest`), then JVM (`JvmLispCompilerTest`),
   then WASM (`WasmLispCompilerIntegrationTest`).
2. Watch the `:from-end` accumulator-side flip -- it is the classic off-by-one/
   arg-order bug. Pin `(reduce #'cons '(1 2 3) :from-end t :initial-value nil)`
   => `(1 2 3)` and `(reduce #'- '(1 2 3 4))` => -8 vs `:from-end t` => -2.
3. ci-spec case + docs (`reduce.md`: add `:from-end`/`:key` to the signature and
   an example).

## Acceptance

Left/right fold, with and without `:initial-value`, with `:key`, in any keyword
order, correct on all four backends. Native E2E green. cl-utilities `compose`
runs.
