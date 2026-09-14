# 817: `--no-gc` prints a boolean as `1`/`0` where every other backend prints `T`/`NIL`

Difficulty: High

```lisp
(defun main (n)
  (princ (> n 0)) (terpri)
  (princ (< n 0)) (terpri)
  (princ (and (> n 0) (< n 100))) (terpri)
  (print (< n 0)) (terpri))
```

`(main 42)`, all four backends, `--optimize=off` and `=size`:

| backend | output |
| --- | --- |
| interpreter | `T` `NIL` `T` `NIL` |
| JVM | `T` `NIL` `T` `NIL` |
| wasm-GC | `T` `NIL` `T` `NIL` |
| **`--no-gc`** | **`1` `0` `1` `0`** |

`(princ (if (> n 0) t nil))` prints `1` too, so the `t`/`nil` literals themselves do not
survive the lowering either. This is the same class of defect as `.todo/813` -- a
`--no-gc` answer that differs from the other three -- and `.kb/no-gc-scalar-wasm.md`
does not record it as an accepted divergence.

## Why

`Ty` has no boolean. Its own comment says so:

```java
/** A 64-bit integer ({@code i64}); also the domain of booleans (0/1). */
INT,
```

A comparison yields `INT`, indistinguishable at the print site from the integer `1`, so
`princ` renders it through `__itoa`.

The intent was clearly otherwise: `runtimeLiterals` already ships `"T"` and `"NIL"` in
the literal pool of every printing module. **Nothing reaches them** -- see
`.todo/816`, which measures them as unreachable in every `--no-gc` module it compiled.
The renderer they were laid down for was never wired up.

## What has to be decided first

The backend's whole premise is that types are static and a value is one wasm value
(`.kb/no-gc-scalar-wasm.md`). A boolean that prints as `T` needs to be distinguishable
from the integer `1` at the print site, and there are only two honest ways:

1. **A `BOOL` rung in `Ty`**, below `INT` in the lattice, produced by the predicates and
   widened to `INT` the moment it meets arithmetic. `princ` of a `BOOL` writes `T`/`NIL`;
   `princ` of an `INT` keeps `__itoa`. Costs a lattice rung and a widening rule in the
   return fixpoint (`TC`), and has to answer what `(princ (if p t 1))` does -- the join
   of `BOOL` and `INT` is `INT`, so that prints `1`, and the interpreter prints `T`.
   That residual disagreement has to be stated, not discovered.
2. **A runtime discriminator**, which the backend does not have and should not grow.

Option 1 is the one that fits the design. Option 2 is listed only so the choice is on
the record.

Measure before choosing, per the `.kb` rule: a `BOOL` rung makes `T`/`NIL` live in
modules that print booleans and leaves them dead elsewhere, so the size answer moves in
both directions and `.todo/816` part 1 has to be settled in the same sitting.

## Also in scope

`(null nil)` is refused outright:

```
error: --no-gc: unsupported operation 'NULL' in function 'MAIN'
```

Decide whether `null` joins the supported set with this work or stays out; if it stays
out, the refusal message is right and nothing to do.

## Verification

Interpreter as oracle, all four backends, both `--optimize` levels, per
`.kb/running-backends.md`. The program above is the reproduction; a pinning case belongs
in `ci-spec.yaml` so the four stay tied together.
