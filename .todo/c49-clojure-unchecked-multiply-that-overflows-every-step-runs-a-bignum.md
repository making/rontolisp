# c49. Clojure: a 64-bit `unchecked-multiply` that overflows on every step runs through a bignum

Difficulty: Medium

Measured 2026-10-03 (`.kb/clojure-frontend.md`, the unchecked arithmetic measurement): 20M iterations
of `h = (unchecked-add (unchecked-multiply h 31) i)` took 3.3 s on the JVM and 12.7 s on wasm, against
0.67 s and 3.6 s for the same loop over the `-int` verbs and 0.19 s / 1.3 s for a plain `(+ s i)`: every
step leaves the long range, so the product is a bignum that `%clojure-wrap-long` masks back. The oracle
does it in one signed `imul`. Plan: a codegen-level signed-i64 wrapping multiply (and add/subtract) the
`%clojure-unchecked-*` workers can call on every backend, or the integer-fusion peephole
`emitFastWrapped` extended to a `(logand (* a b) #xFFFFFFFFFFFFFFFF)` mask reinterpreted as signed
(`.kb/wasm-int-fusion.md`); the interpreter needs the same primitive. Size the change against the
fused-tree budget first; the work touches codegen, so coordinate with whoever holds the tail-position
work there.
