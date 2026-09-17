# Scheme: `(scheme inexact)` -- `sqrt` `exp` `log` `sin` `cos` `tan` `atan` ..., and the digits all four backends print

Difficulty: Medium

Split off `.todo/826` (its `(scheme inexact)` row) for `.todo/828`. None of these is in
`SchemeBuiltins` today: `(sqrt 2)` in a `.scm` is "The function sqrt is undefined" (the
Scheme identifier is lowercase, so it never reaches the CL function). Corpus files using
each WITHOUT defining it: `sqrt` 24, `atan` 16 (one- and two-argument), `sin` 15, `cos` 15,
`exp` 2, `log` 1. 16 files define their own `sqrt` and 100 bind `exp` as a VARIABLE (the
evaluators' `(eval exp env)`) -- a user binding must keep winning.

Table rows are the easy half. Measured 2026-09-17, the same Common Lisp form on three
backends:

```
(list (sqrt 16) (exp 1) (log 100 10) (atan 1 1) (expt 2 0.5))
interpreter, JVM: (4.0 2.7182818284590455 2.0               0.7853981633974483 1.4142135623730951)
wasm:             (4.0 2.7182818284590455 2.000000000000151 0.7853981633974481 1.414213562373095)
```

1. **The wasm transcendentals differ from the JVM's in the last digits** (`log`, `atan`,
   float `expt`), so a corpus program printing `(fixed-point cos 1.0)` or a polar complex
   number cannot be byte-identical on all four. Find where each backend's implementation
   lives, measure which functions diverge and by how much, and decide: one shared
   algorithm, or a documented tolerance in the corpus test (`.todo/828`). This is the part
   that may turn out to be a finding rather than a change -- record it in `.kb/`.
2. **Exactness**: `(sqrt 16)` must be `4` and `(sqrt 1/4)` `1/2` (exact argument with an
   exact root), `(sqrt 2)` inexact; `(exp 0)` is `1`. `(sqrt -4)` is a complex in CL:
   refuse or answer by name, do not print `#C(0.0 2.0)`.
3. **Printing**: `%scheme-display` prints `1.0e21` and `1.23456789123e8` (the CL printer);
   Scheme writes `1e21` and `123456789.123`. `number->string` goes through `princ-to-string`
   and inherits it.
4. `exact-integer-sqrt`, `finite?`, `infinite?`, `nan?` complete the library; `+inf.0` /
   `+nan.0` reading stays with `.todo/826`.

Cases in `scheme-spec.yaml` on all four backends, chosen so the expected digits are the
ones every backend really prints.
