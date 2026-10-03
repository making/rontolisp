# c41. Clojure: `print-str`, `prn-str` and `println-str` are unknown names

Difficulty: Low

`(print-str 1 "a")`, `(prn-str 1 "a")` and `(println-str 1 "a")` fail with `unknown name` on the
interpreter and every compile backend (2026-10-03); the oracle (`clj` 1.12.6) answers `1 a`, `1 "a"\n`
and `1 a\n`. `pr-str` exists; `with-out-str` around `print`/`prn`/`println` is the workaround.

Add the three as lowerings over the existing printer (`%clojure-str-of` per argument joined by a space,
readable for `prn-str`, a trailing newline for `prn-str`/`println-str`), next to `pr-str`, on all four
backends, with their arity as values, and pin them in `clojure-spec.yaml` and `doc/{en,ja}/clojure/reference`.
