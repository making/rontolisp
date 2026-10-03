# c40. Clojure: `bigint`, `bigdec`, `biginteger`, `rationalize`, `numerator`, `denominator` and the `unchecked-*` casts are unknown names

Difficulty: Low

Found while adding `double`/`float`/`byte`/`short`/`num` (2026-10-03): `(bigint 1)`, `(bigdec 1)`,
`(biginteger 1)`, `(rationalize 0.5)`, `(numerator 1/2)`, `(denominator 1/2)`, `(unchecked-int 1)`,
`(unchecked-long 1)`, `(unchecked-short 1)`, `(unchecked-byte 1)`, `(unchecked-double 1)`,
`(unchecked-float 1)` and `(unchecked-char 97)` all fail with `unknown name`; `clojure.core` defines
every one (`ClojureCoreNames`). Plan: `numerator`/`denominator`/`rationalize` over the Lisp
primitives of the same name; `bigint`/`biginteger`/`bigdec` follow the literal rule
(`decimal-and-bigint-literals-are-plain-rationals`); the `unchecked-*` casts wrap rather than
refuse (the oracle: `(unchecked-byte 200)` is `-56`), so `unchecked-int`/`unchecked-long` are
the first to need a two's-complement wrap. Size a program calling each before deciding which
share a worker (`.kb/clojure-frontend.md`, the `int`/`long` measurement).
