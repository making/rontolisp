# The `floor` family's quotient with an INFINITE divisor does not compose with its own remainder

Difficulty: Low

Found while closing todo-660 (the exact float quotient, 2026-09-02), which left this one
regime alone deliberately.

todo-659 settled the REMAINDER for an infinite divisor by the CLHS formula rather than by
an upstream oracle (SBCL signals): the truncating quotient of a finite dividend is the
integer zero, so `(rem 3.0 inf)` is `3.0`, and `mod`'s divisor-sign correction makes
`(mod -3.0 inf)` an `Infinity` -- which is the limit of floor's quotient being `-1` out
there, not `0`. todo-660 then made the quotient exact everywhere EXCEPT here: the exact
path declines on a non-finite operand and the f64 route answers `floor(-0.0)` = `0`.

So on all four backends today:

```lisp
(multiple-value-bind (q r) (floor -3.0 (/ 1.0 0.0)) (list q r))  ; => (0 Infinity)
```

`q*b + r` is not `a` for that pair, and the two halves of one operator disagree about what
the quotient is. Mathematically `-3/inf` is an infinitesimal negative, whose floor is `-1`
and whose ceiling is `0`; `3/inf` floors to `0` and ceilings to `1`; `truncate` and
`round` are `0` either way.

## What to do

Decide the quotient the same way 659 decided the remainder -- by the formula, since there
is no oracle -- and make the two compose. The rule is small: with a finite nonzero
dividend and an infinite divisor, `truncate` and `round` are `0`, `floor` is `0` when the
operand signs agree and `-1` when they differ, `ceiling` is `1` when they agree and `0`
when they differ.

Four backends (`eval/ExactRounding`, the JVM's `_fdiv`, wasm-GC's `_f64_fdiv`; `--no-gc`
has no multiple values and keeps its own answer). Consider instead deciding that the
infinite-divisor quotient is not worth spelling and that the REMAINDER should decline
there too -- but that would reopen 659, whose sweep pins those rows, so read
`.kb/linalg-simd.md` ("mod/rem") before choosing.

## Acceptance

- `quotient*divisor + remainder = number` holds in the limit for every infinite-divisor
  pair, on the interpreter, the JVM, wasm-GC and the component.
- The rows join the `ci-spec.yaml` case `the-floor-family-quotient-is-exact-at-any-magnitude`.
- `.kb/linalg-simd.md`'s "Still open" note at the end of the mod/rem section is removed.
