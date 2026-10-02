# b90. The Clojure REPL echoes the var a definition defines

Difficulty: Low

Measured 2026-10-02, oracle `clj` 1.12.6.1673 REPL vs the exec jar after b80
(`rontolisp --source-language clojure`):

- `(defn f "d" [a] a)` oracle `#'user/f`, ronto `f`.
- A redefinition `(defn f "e" [] 1)` oracle `#'user/f`, ronto `f%def2` (the
  b65 internal name leaks into the echo).
- `(def x 1)` oracle `#'user/x`, ronto `1`; `(defmacro m [] 1)` oracle
  `#'user/m`, ronto `nil`.

b80 made the var a value (`ClojureVarLowering.varOf`), so the echo can be the
oracle's.

## Plan

- In `ClojureLowering.interact` only (a file run prints nothing for a
  definition), append the var of a top-level `def`/`defn`/`defn-`/`defmacro`
  (`defmulti` too: oracle `#'user/name`) as the datum's last form, so the
  echo prints `#'ns/name`. `defonce` over a bound var echoes `nil` in the
  oracle -- measure before deciding.
- Update the pinned echoes (`RontoLispCliTest` REPL cases, `doc/*/clojure/repl.md`).

## Pin

- `ClojureSessionTest` echo shapes; `RontoLispCliTest` REPL transcript.
