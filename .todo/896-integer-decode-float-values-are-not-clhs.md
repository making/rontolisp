# `integer-decode-float` answers a stripped significand and a float sign

Difficulty: Low

Found by `.todo/888` (2026-09-19). The prelude `integer-decode-float`
(`LispPreludeLibrary`) strips factors of two from the significand and answers the sign as a
float:

| form | rontolisp | SBCL |
|---|---|---|
| `(integer-decode-float 1.0)` | `1 0 1.0` | `4503599627370496 -52 1` |
| `(integer-decode-float 2.0)` | `1 1 1.0` | `4503599627370496 -51 1` |
| `(integer-decode-float -0.5)` | `1 -1 -1.0` | `4503599627370496 -53 -1` |

CLHS: the sign is an INTEGER for `integer-decode-float` (a float only for `decode-float`),
and the significand is the float's significand scaled to an integer -- `float-digits` bits
for a normal double. `rationalize` binds the three values; check it after the change.
`LispEvaluatorTest`, `JvmLispCompilerTest` and `WasmLispCompilerIntegrationTest` pin the
current values and move with it.

## Test plan

- `ci-spec.yaml` case: normal, subnormal, zero, negative operands.
