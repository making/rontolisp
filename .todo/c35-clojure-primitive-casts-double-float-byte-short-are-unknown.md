# c35. Clojure: the primitive casts `double`, `float`, `byte`, `short` are unknown names

Difficulty: Low

`(double 1)`, `(float 1/3)`, `(byte 200)`, `(short 1.9)` and `num` are unknown names (2026-10-03);
`int`, `long`, `char`, `boolean` exist. The oracle (`clj` 1.12.6): `(double 1/2)` -> `0.5`,
`(byte 1.9)` -> `1`, `(byte 200)` -> `Value out of range for byte: 200`, `(short 70000)` -> `Value out of
range for short: 70000`, `(float 1/3)` -> `0.33333334` (a float; doubles only here), `(num 1)` -> `1`.

`vector-of` already casts this way (`rontolisp::%clojure-vector-of-1` and its range refusals, in the
oracle's words), so each cast can be one call into it; `int`/`long` (`ClojureDispatchLowering.intForm`,
plain `truncate`) do not refuse a value out of range today (the oracle: `integer overflow`, `Value out
of range for long: ...`). Decide whether `int`/`long` should share it, measuring the size of a program
that only calls `int`.
