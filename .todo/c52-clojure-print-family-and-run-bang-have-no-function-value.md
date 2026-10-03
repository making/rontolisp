# c52. Clojure `println`/`print`/`prn`/`pr` and `run!` have no function value

Difficulty: Low

`(dorun (map prn [1 2]))` and `(run! println [1 2])` fail at lower time with `unknown name:
prn` / `unknown name: run!` (2026-10-03); `print-str` and friends do have values. The oracle
(`clj` 1.12.6) prints `1` and `2` for both. Passing the print family as a function and
`run!` are common Clojure idioms. Since `#'x` of a core name reads its core value, the same
gap makes `#'println` refused (`var of a clojure.core var is not supported yet`).

Plan: give the four print functions a value (a variadic lambda over the same lowering the
call uses) and add `run!` (`reduce` for effect, answering nil), both through `valueOf`;
pin in clojure-spec on all four backends.
