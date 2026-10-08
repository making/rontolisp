# e58. Clojure: `clojure.pprint/cl-format`, `formatter`, `formatter-out`

Difficulty: High

`clojure.pprint` ships with every var but these three, refused by name
(`ClojureBuiltinNamespaces`); a library naming one fails to lower. data.json's `pprint`
calls `formatter-out` (`"~<[~;~@{~w~^, ~:_~}~;]~:>"`, `"~<{~;~@{~<~w:~_~w~:>~^, ~_~}~;}~:>"`),
core.match calls `cl-format`.

## What decides the design

- cl-format is Common Lisp `format` over Clojure values: `~A`/`~S`/`~W` print with the
  Clojure printer, `~{` iterates vectors and seqs, `~:[` tests Clojure truthiness (`false`
  is no `nil`). The run-time library's own `format` (`.kb/format.md`) prints CL notation
  and iterates lists only, so a conversion layer cannot serve both; a format-string
  compiler and executor in Clojure source (`clojure.pprint`, kernels where text work is
  heavy) can, paid only by programs that use it.
- The pretty-printing directives (`~<...~:>`, `~_`, `~I`, `~W`) emit the events the
  pprint kernel already replays (`.kb/clojure-frontend.md`, "clojure.jar namespaces");
  a `~W` cut by `*print-length*` aborts the enclosing run of directives, as
  `code-dispatch`'s `write-run` reproduces.
- Floats (`~F ~E ~G ~$`), `~R` (English, Roman) and `~T` need the oracle's exact text.

## Plan

1. Directive by directive against clj 1.12.6, starting with what data.json and core.match
   use; the pretty-printing set first.
2. clojure-spec lines on all four backends; `doc/*/clojure/reference/clojure-pprint.md`.
