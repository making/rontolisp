# 69: `multiple-value-setq` + `rotatef`

Split off from `.todo/65-cl-utilities-support.md` (cl-utilities stdlib residue,
step 2). Single-session sized. Two assignment macros -- no per-backend codegen.

## Goal

Two `LispMacroExpander` lowerings (shared by interpreter + both compilers):

- `(multiple-value-setq (v1 v2 ...) values-form)` -- bind the multiple values of
  `values-form` to the existing variables `v1 v2 ...` via `setq`, return the
  primary value. Reuse the syntactic multiple-value tier (`.kb/multiple-values.md`):
  the same producer recognition as `multiple-value-bind` (literal `(values ...)`,
  `floor`/`ceiling`/`round`/`truncate`, `gethash`), extra vars get nil.
  Lower to: evaluate the producer's value forms into temporaries, `setq` each
  target, return the primary. Simplest: `(multiple-value-bind (g1 g2 ...) form
  (setq v1 g1) (setq v2 g2) ... g1)`.
- `(rotatef place1 place2 ...)` -- rotate values left among the places (place1
  gets place2's old value, ..., last gets place1's old); returns nil. For the
  common 2-place case `(rotatef a b)` = swap. Must work over setf-able places
  (symbols, `car`/`cdr`, `nth`, `aref`, `gethash`, struct accessors), so build it
  on the existing `expandSetf` machinery -- read all places into gensym temps,
  then setf each place to the next temp (rotated).

## Why

cl-utilities `read-delimited` uses `multiple-value-setq`; `with-gensyms`/
`extremum` shapes use `rotatef`. Both are common CL and reusable.

## Current state

Neither name exists as a macro. Precedent:
- `multiple-value-bind` is already a `LispMacroExpander` macro (CL_MACROS) with
  producer recognition -- copy its parsing.
- `psetq`/`setf`/`push` show the read-into-temps-then-write pattern `rotatef`
  needs; `expandSetf` is the place-expansion entry point.
- Registration: `LispNames` + `PackageRegistry.CL_MACROS` (NOT CL_FUNCTIONS --
  these have no function value); `LispEvaluator.evalCons` case; JVM+WASM
  `compileCons` case -> `compileExpr(expand...(cons), ...)`. See "Adding a New
  Macro" in CLAUDE.md.

## Plan

1. `expandMultipleValueSetq` -> reuse multiple-value-bind lowering, add the
   trailing setqs. Interpreter -> JVM -> WASM tests.
2. `expandRotatef` -> gensym temps + `expandSetf` per place. Cover the 2-place
   swap plus a 3-place rotate and a non-symbol place (`(rotatef (car x) (cdr x))`).
3. `list-macros` pins move: `LispEvaluatorTest#listMacrosReturnsSortedClMacros`
   and the `rontolisp-package-introspection` ci-spec `(list-macros)` expected
   line (keep alphabetical).
4. ci-spec case + docs (`reference/macros/`).

## Acceptance

`(let (a b) (multiple-value-setq (a b) (floor 17 5)) (list a b))` => `(3 2)`;
`(let ((x 1) (y 2)) (rotatef x y) (list x y))` => `(2 1)`; a 3-place rotate and a
compound-place rotate; all four backends. Native E2E green.
