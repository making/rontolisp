# #'gcd / #'lcm are binary and #'numerator does not compile on the compiled backends

Difficulty: Low

Measured 2026-09-26: `(funcall #'gcd -6)` answers 6 interpreted and signals `Function
expects 2 arguments, got 1` on the JVM and wasm (`BuiltinFunctionWrappers` registers
`binary(GCD)`/`binary(LCM)`, while CL's are variadic, `(gcd)` = 0 and `(lcm)` = 1).
`(funcall #'numerator 1/2)` fails the compile on both (`Cannot compile: NUMERATOR`);
`denominator` likewise.

Goal: the function values answer what the calls answer on every backend, including the
`GCD:`/`NUMERATOR:` type-error reports the calls give since 972.
