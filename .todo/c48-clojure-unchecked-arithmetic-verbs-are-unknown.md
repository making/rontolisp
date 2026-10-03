# c48. Clojure: `unchecked-inc`, `-dec`, `-negate`, `-subtract`, `-multiply` and the `unchecked-*-int` verbs are unknown names

Difficulty: Low

Found while adding the `unchecked-int`/`-long`/`-short`/`-byte`/`-char`/`-double`/`-float` casts (2026-10-03):
`(unchecked-inc 1)`, `(unchecked-dec 1)`, `(unchecked-negate 1)`, `(unchecked-subtract 3 1)`,
`(unchecked-multiply 3 4)`, `(unchecked-add-int 1 2)`, `(unchecked-subtract-int 1 2)`,
`(unchecked-multiply-int 2 3)`, `(unchecked-negate-int 1)`, `(unchecked-inc-int 1)`,
`(unchecked-dec-int 1)`, `(unchecked-divide-int 7 2)` and `(unchecked-remainder-int 7 2)` fail with
`unknown name`; `clojure.core` defines each (`ClojureCoreNames`). The oracle wraps the long verbs at
64 bits (`(unchecked-inc 9223372036854775807)` is `-9223372036854775808`) and the `-int` verbs at 32;
`unchecked-add` here never wraps (`doc/en/clojure/deviations.md`). Plan: the long verbs over the
same `%clojure-wrap-bits` the casts use (`unchecked-add` joins them, a behavior change on every
backend with its pinning test), the `-int` verbs wrapping at 32; size a program calling each first
(`.kb/clojure-frontend.md`, the number-conversion measurement), since a wrap on every `+` of a hot
loop costs more than the plain addition.
