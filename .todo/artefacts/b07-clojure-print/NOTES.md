# Spike b07: Clojure print wrapper in plain Common Lisp (2026-09-30)

Question: 印字形式がCLのままなのを、何かラップすることで Clojure 用に変えられるか。
Answer: YES -- a spliced `clojure.lisp` like `scheme.lisp`, no backend changes.

## What was built (this directory)

- `clj-print.lisp` -- prototype printer, portable CL, ~200 lines:
  `clj%print-datum` (readable/plain), `clj%to-string`, `clj%list-cycle-start`
  (Floyd), `clj%demangle`, string/char datum writers, keyword/set predicates.
- `t-clj-print.lisp` -- unit driver (hand-built values incl. wrappers).
- `e2e-values.clj` + `t-e2e.lisp` -- REAL lowered values: the `.clj` file defs
  values, the `.lisp` driver `(load)`s it and prints via `symbol-value` on the
  mangled (`|c%..|`) names, with `*clj-false*` bound to the real
  `rontolisp::%clojure-false` object.

Run from this directory: `(load "clj-print.lisp")` resolves against the
loading file's directory on rontolisp (so the driver says `"clj-print.lisp"`,
not `"spike/clj-print.lisp"`).

## Measured (all four backends + SBCL 2.2.9, byte-identical)

`java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar` at 425d206ee was reused
from the b05 worktree (same commit; zero maven runs consumed by this spike):

| program | interpreter | JVM jar | wasm P1 | wasm component | SBCL |
|---|---|---|---|---|---|
| t-clj-print.lisp | ok | - | - | - | ok, identical |
| t-e2e.lisp (+e2e-values.clj) | ok | ok | ok | ok | n/a (.clj) |

Sample (before -> after): `#(1 (C%KEYWORD a) s)` -> `[1 :a s]`,
`#<HASH-TABLE :TEST EQUAL :COUNT 1>` -> `{:a 1}`,
`(C%SET #<HASH-TABLE ..>)` -> `#{1}`, `(T false)`-nested -> `(true false)`,
`c%e2e-foo` -> `e2e-foo`, `#\a` -> `\a` (readable) / `a` (plain),
`"a\"b\\c"` readable, cdr-cycle `(1 . #)` exactly RenderCycleGuard's spelling.

## Findings that shape the real work

1. A depth cap alone CANNOT bound a cdr cycle (loops at one depth): the naive
   walk exhausted 500 MB on SBCL before the Floyd prewalk was added. The real
   printer needs Scheme-scale datum labels (`%scheme-print` ~130 lines), not
   the spike's cap+Floyd-`#`.
2. Tag checks must be what the lowering already does: `(eq (car x) :C%KEYWORD)`
   + string tail (a user list sharing the head is excluded the same way).
3. False needs no literal: compare EQ against the `rontolisp::%clojure-false`
   variable's value (same object on every backend by construction).
4. Demangle is case-sensitive `c%` strip + `%%`->`%`, `%c`->`:`; CL's upcased
   symbols never match the prefix, so they print untouched.
5. Bare `:kw` symbols: `symbol-name` drops the colon on SBCL AND on rontolisp,
   so the printer needs the `keywordp` arm (not a colon-prefix test).
6. `with-output-to-string` works on the interpreter but flips WASM modules
   into EH mode (`WasmExprCompiler`, `LispNames.WITH_OUTPUT_TO_STRING` gate).
   Scheme's `display`/`write` avoid it by writing to the port directly; the
   real `println`/`print`/`pr`/`prn` should too, with string building kept to
   `str`/`pr-str` (measure the EH cost there).
7. Deliberate non-goals kept: nil IS the empty list (stays `nil`, never `()`),
   map/set walk order unspecified (same as `keys`/`vals` today), unreadable
   fallback stays `#<..>`.

## Gaps the spike did NOT cover

car/element cycles (depth-capped, not labeled), `*print-length*`/`*print-level*`
(a routed println would stop honoring them -- implement or document),
`~S`/`~A` on Clojure values, `pr-str` (does not exist yet), multi-entry map
order, performance/size numbers for the spliced library.
