# c75. Zero to a negative non-integer power, and float `/` `mod` `rem` by an exact zero, answer IEEE where SBCL signals

Difficulty: Medium

Measured 2026-10-04, identical on all four backends (so a contract question, not a split):
`(expt 0 -1/2)`, `(expt 0.0 -1)`, `(expt 0 -1.5)`, `(expt 0.0 -1.5)` answer infinity;
`(/ 1.5 0)` answers infinity and `(mod 7.5 0)` NaN. SBCL signals `division-by-zero` for every
one of them. `(expt 0 -1)` and every integer/ratio division by an exact zero already signal
(`.kb/error-handling.md`, "A division by zero signals division-by-zero").

Decide, per operator, whether an exact-zero divisor (or a zero base to a negative power) signals
regardless of the other operand's type, keeping a zero FLOAT divisor IEEE (`(/ 1.5 0.0)`), and
change all four backends together with the pin in `DivisionByZeroFixture` / `ci-spec.yaml`.
Blast radius to measure first: typed double loops and the linalg/vec kernels, which divide by
literal zeros only through a mixed-type `(/ x 0)` (grep the corpus and `examples/` before
changing); the float-rounded-by-exact-zero case is already decided and pinned.
