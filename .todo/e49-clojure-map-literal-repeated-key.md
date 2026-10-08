# e49. A Clojure map literal with a repeated key is accepted

Difficulty: Low

The source reader (`ClojureReader.readBraced`) reads `{:a 1 :a 2}` and the program keeps the
last value. The oracle (`clj` 1.12.6) refuses the read, measured 2026-10-08:
`Syntax error reading source at (dup.clj:1:21). Duplicate key: :a`. A set literal is already
refused (`readSet`, by spelling), the runtime reader refuses both (`%clojure-rd-map-of`), and
a `deps.edn` read checks its own maps (`ClojureDepsEdn.duplicateKey`).

## Plan

1. Measure the oracle's rule for literal keys: by spelling, by `=` of constants
   (`{1 :a 1N :b}`, `{[1] :a '(1) :b}`), and an unevaluated key (`{(f) 1 (f) 2}`).
2. Refuse in `readBraced` in the oracle's words, positioned like `readSet`'s refusal; the
   `deps.edn` check can then go.
3. A clojure-spec line; `doc/*/clojure/deviations.md` if a difference remains.
