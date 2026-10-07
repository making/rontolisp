# d89. A runaway `ash` count signals three different ways

Difficulty: Medium

`(ash x n)` with a positive count past the `int` range cannot be built. Measured
2026-10-07 through a parameter, under `handler-case`:

| call | SBCL 2.2.9 | interpreter | JVM | P1, component |
|---|---|---|---|---|
| `(ash 1 (expt 2 40))` | `HEAP-EXHAUSTED-ERROR` (137 GB requested) | `SIMPLE-ERROR`, `ASH: shift count too large: 1099511627776` | `ARITHMETIC-ERROR`, `ash: shift count too large` | wasm trap |
| `(ash 1 (expt 2 70))` | `SIMPLE-ERROR`, `can't represent result of left shift` | `SIMPLE-ERROR`, `ASH: shift count too large: 1180591620717411303424` | `ARITHMETIC-ERROR`, `ash: shift count too large` | wasm trap |
| `(ash 0 (expt 2 40))` | `0` | `0` | `0` | wasm trap |

The trap is not catchable, so a handler never runs.

Plan: pick one condition and text for every backend (the interpreter's text names the
operator and the count, as the other operand reports do), make the WASM `ash` path answer
a zero value and signal that condition instead of trapping, and pin it in a shared fixture
on all four backends.
