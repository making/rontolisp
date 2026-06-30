# 29 - Add the `loop` macro (at least the simple iteration clauses)

## Motivation

`map` (`.todo/25`) covers index-free sequence mapping, but anything that needs a
running index or a numeric range still has to hand-roll an `iota`/`upto` helper
(see `examples/rainbow.lisp`, whose `rainbow-text` needs the per-character
position `i/(len-1)` for the gradient). Neither `iota` nor `map-with-index` is
ANSI Common Lisp; the standard, idiomatic way to express indexed iteration and
range generation in CL is the `loop` macro:

```lisp
(loop for i below n collect i)                  ; the range / iota
(loop for x in lst for i from 0 collect (f i x)) ; indexed map
```

Adding `loop` would let `examples/rainbow.lisp` drop its `upto`/`iota` helpers
and stay purely functional (no closed-over mutable counter), and would remove a
recurring source of boilerplate generally.

## Scope (suggested first cut -- the "simple loop" clauses)

`loop` is a huge macro; do NOT attempt the full ANSI grammar at once. A useful,
bounded first slice:

- numeric stepping: `for VAR from LO [to|below|downto|above HI] [by STEP]`
- list stepping: `for VAR in LIST` (and maybe `for VAR on LIST`)
- accumulation: `collect`, `append`, `sum`, `count`, `maximize`/`minimize`
- control: `while`/`until`, `do`, `when`/`unless ... <clause>`, `finally`,
  optional `repeat N`
- a terminating `return` value

Out of scope for the first cut: destructuring, `with`, `for ... =`/`then`,
`for ... across`/`being the hash-keys`, `loop` keywords as symbols in arbitrary
packages, parallel `and` bindings -- list these as limitations.

## Implementation (per CLAUDE.md "Adding a New Macro")

`loop` should be a macro in `LispMacroExpander` (shared by interpreter + both
compilers), expanding to the existing core (`do`/`do*`/`let`/`while`/`%block`/
`setq`/`return` + accumulator vars built with `cons`/`nreverse` etc.) -- exactly
how `do`/`dolist`/`dotimes` already lower. Steps:

1. `LispNames.LOOP` + add to `PackageRegistry.CL_SYMBOLS` (it is a CL macro, so
   put it in `CL_MACROS`, not `CL_FUNCTIONS`).
2. `LispMacroExpander.expandLoop(LispCons)` -- parse the clause sequence into a
   `do`-style binding/step/accumulation plan, then build a `%block`-wrapped
   `let`/`while` like `expandDo`/`expandDoStar`. Reuse `makeBlock`/`makeReturn`/
   `makeIf`/`listToCons` and the `do*` lowering where possible.
3. Wire the `LispNames.LOOP` case into `LispEvaluator.evalCons`,
   `JvmExprCompiler.compileCons`, `WasmExprCompiler.compileCons` (all three just
   `-> expand...(cons)` then recompile, like the other macros).
4. Tests: `LispEvaluatorTest` + `JvmLispCompilerTest` +
   `WasmLispCompilerIntegrationTest`; mind the `CiSpecE2eTest` 64 KB ceiling
   (`.todo/28`) before adding a ci-spec case.
5. Docs: a `loop` page under `reference/macros/` + `_catalog.yaml` in both
   `doc/en` and `doc/ja`; document the supported clause subset + limitations.

## Follow-on

Once `loop` exists, rewrite `examples/rainbow.lisp` to drop `upto`/`iota`
(`rainbow-text` via `loop for i below len ... collect`, `html-escape` either via
`loop` or the already-clean `(map 'list #'escape-char s)` + reduce-join), then
re-verify the example on all four backends.

Related: `.todo/25-generic-map-over-sequences`,
`.todo/28-improve-cispec-e2e-harness-method-size-limit`.
