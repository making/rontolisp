# e00. A bignum dimension or `random` limit answers a wrong value on the compiled backends

Difficulty: Medium

Found while closing the runaway-`ash` item. Measured 2026-10-07, the argument from a
variable, under `handler-case`:

| call | SBCL 2.2.9 | interpreter | JVM | P1, component |
|---|---|---|---|---|
| `(make-array (expt 2 100))` | `TYPE-ERROR` | `SIMPLE-ERROR`, `MAKE-ARRAY expects an integer or list of dimensions, got 1267650600228229401496703205376` | `#0ANIL` (a rank-0 array) | `#0ANIL` |
| `(random (expt 2 100))` | a bignum below the limit | a bignum below the limit | `9223372036854775807` | wasm trap (`_int_val`'s limb arm) |

Both compiled answers are silent wrong values; the trap is uncatchable.

Plan: `random` of a bignum limit draws a bignum on every backend (the interpreter's
answer, SBCL's); a dimension past the array size limit signals one condition and text
everywhere (SBCL's `type-error` naming the dimension). Pin both in a shared fixture on all
four backends.
