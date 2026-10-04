# c74. A float rounded by an exact zero: the JVM signals division-by-zero, the others do not

Difficulty: Low

Measured 2026-10-04 (after c59): `(floor 7.5 0)` (any of the two-argument rounding family
with a float dividend or divisor and an exact zero divisor) signals `division-by-zero`
reporting `Division by zero` on the JVM, and on the interpreter and both wasm-GC backends
divides in IEEE (7.5/0 = infinity) and then signals the non-finite-rounding `simple-error`
(`rounding a non-finite float to an integer is undefined`). `(floor 7.5 0.0)`,
`(mod 7.5 0)` (NaN everywhere) and `(/ 1.5 0)` (infinity everywhere) are not split.
SBCL signals `division-by-zero` for all of them; rontolisp's float division is IEEE by
design (`.kb/error-handling.md`, "A division by zero signals division-by-zero").

Decide which one is the contract for a rounding with a zero divisor (likely
`division-by-zero` when the divisor is an exact zero, matching the integer path), make the
four backends agree, and pin it in `DivisionByZeroFixture` / ci-spec. Related: `(expt 0
-1/2)` answers infinity on every backend where SBCL signals `division-by-zero`.
