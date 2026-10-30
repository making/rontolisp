# h58. Readtable dependents that still fail on the interpreter

Difficulty: Medium

Measured 2026-10-30 after h53 (interpreter, `ql:quickload`): named-readtables, cl-interpol,
cl-annot, cl-syntax and jonathan load. The rest fail for reasons that are not the reader:

- **parenscript**: `EXPORT: the symbol PS-DOM1-SYMBOLS:CLASS-NAME conflicts with CLASS-NAME,
  which is accessible in package PS-DOM2-SYMBOLS` -- `src/js-dom-symbol-exports.lisp`'s
  `re-export-symbols` (`shadowing-import` of the cl symbol, then `shadowing-import` + `export`
  into the next package). The `:invert` readtable reads it correctly (`#:className` stays
  mixed case).
- **cl-csv**: its `.asd` holds `(unless (find-package :cl-csv.system) (defpackage ...))`,
  outside the `.asd` subset (`.kb/asdf.md`).
- **jonathan**: `jonathan:parse` escapes `LispEvaluator$GoSignal` (a `go` leaving its
  `tagbody`'s extent); `to-json` prints `3` as `3.0`.
- **cl-redis**: not reached (the probe stopped at cl-csv).
