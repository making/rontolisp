# m52. wasm: a `vector` call costs a store per element

Difficulty: Medium

`(vector a b c ...)` on wasm is `LispMacroExpander.expandVector`: `(let ((v (make-array n)))
(aset v 0 a) ... v)`, each `aset` the general one (the array's struct read, a null and a bounds
check, the store, a refusal's call). Measured 2026-11-25 (top level, defvars as elements):
134 B a element, against some 6 for `(coerce (list ...) 'simple-vector)`; `array.new_fixed`
over the elements would be some 3.

Where it showed: the Java front end's class objects listed their interfaces and `%jdk-types`
its class objects with `vector` (the shiori's 528 interfaces, some 69 KB of wasm). The lowering
now writes those from a list (`JavaLowering.classVector`); every other long `vector` call -- a
Java array initializer among them -- still pays it.

What to decide: a rank-1 simple vector of `T` built by `array.new_fixed` where the element count
is constant (at most 10,000 operands), what an argument that suspends (a resumable body) needs
of the operand stack, then `classVector` back to `vector`; measure the corpus and the examples.
