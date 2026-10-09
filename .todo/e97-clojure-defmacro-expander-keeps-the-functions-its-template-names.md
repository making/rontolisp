# e97. Clojure: a `defmacro`'s run-time expander keeps every function its template names

Difficulty: Medium

A macro in a named namespace whose syntax-quote names a function of that namespace keeps
that function, and everything it calls, in every program that loads the namespace, called
or not. Measured 2026-10-09, wasm P1, `(ns mac3 (:require [clojure.pprint :as pp]))` with
a never-called `defn-` that calls `pp/cl-format`, then `(prn 1)`: 451,461 B without a macro,
963,738 B with ``(defmacro m [x] `(helper-never-called ~x))`` beside it (the helper and
the whole format executor kept).

## Mechanism

`ClojureMacroLowering.defmacroForms` stores the expander at top level,
`(setq |c%ns/name%macro| expander)`, for `macroexpand-1`'s run-time table. Its template
quotes `ns/helper`, which mangles to the helper's defun name, and a quoted symbol spelling a
defun name arms the dispatch gate (`.kb/optimize-dead-code-elimination.md`, "the names a
runtime SYMBOL designator can resolve"), so the shake keeps it. `user` escapes only because
its defuns drop the namespace (`c%helper`) while the template spells `c%user/helper`.

`clojure.pprint`'s `formatter`/`formatter-out` work around it by building their symbol
(`(symbol "clojure.pprint" "formatter-fn")`); without that, requiring `clojure.pprint` cost
every program the format executor (446,930 -> 888,123 B).

## Plan

1. Store the run-time expander only when the program can expand at run time: a
   `macroexpand`/`macroexpand-1` call or value anywhere in the program, or a REPL session
   (a later input may expand). The decision is program-wide, so a pass over the whole
   lowered program (or the hub's final assembly) drops the `|...%macro|` stores otherwise.
   Alternative to weigh: quote a template's var symbols so they record no spelling (a
   Clojure quoted symbol is never a function designator by name).
2. Re-point the tests that read the store (`ClojureLoweringTest` `defmacro*`/`aCoreNamed*`,
   `ClojureSessionTest`) and pin the size: the measurement above, the helper dropped.
3. Return `formatter`/`formatter-out` to plain syntax-quote.
