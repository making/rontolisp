# e99. Clojure: a syntax-quote template spells no defun name

Difficulty: Medium

A program that expands at run time (`macroexpand`, `macroexpand-1`,
`clojure.walk/macroexpand-all`) keeps every macro expander, and a template's qualified
symbol `'|c%ns/f|` is the defun name of `ns/f`: the dispatch gate arms its registry row, so
`f` and everything it calls stay, called or not. Measured 2026-10-09, wasm P1:
`(ns x (:require [clojure.pprint])) (defmacro m [x] `(inc ~x)) (prn (macroexpand-1 '(m 1)))`
is 456,652 B with `clojure.pprint/formatter`'s built symbol, 894,319 B with the plain
template `` `(#'formatter-fn ~format-in) `` (the format executor kept).

A Clojure symbol is never a function designator by name (run-time `eval`/`resolve` are
refused, a symbol called as a function looks itself up), so the syntax-quote's var symbols
(`ClojureMacroLowering.syntaxQuotedSymbol`, the `lookupVar` and `unresolvedQualification`
arms) could ride in `%unspelled-quote` (`LispNames.UNSPELLED_QUOTE`). Tried 2026-10-09: the
compilers fail with `Cannot find variable for closure: c%clojure.core/inc` --
`FreeVarAnalyzer` (and other walkers with a `case LispNames.QUOTE`) read
`(%UNSPELLED-QUOTE sym)` as a call over a variable inside a lambda.

## Plan

1. Make `%unspelled-quote` a quote to every walker that special-cases `QUOTE` (grep
   `LispNames.QUOTE` under `compiler/`, `codegen/`, `macro/`, `eval/`), or normalize it in
   one place the walkers all run after.
2. Emit it for the syntax-quote's var symbols; re-point the lowering tests reading
   `'|c%...|` out of a template.
3. Return `clojure.pprint`'s `formatter`/`formatter-out` to plain syntax-quote and pin the
   measurement above (`.kb/clojure-frontend.md`, the pprint note).
