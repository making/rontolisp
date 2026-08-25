# 519. Every compiled program switches the eval runtime on, via a gate the shaker then undoes

Difficulty: Medium (the fix is where the gate is DECIDED, not what it decides;
the risk is the retry loop that exists to catch an under-prediction)

Child of `.todo/517`. Sibling of `.todo/518`.

## The defect

`JvmLispCompiler` decides `usesEval` before Pass 1 and, when it is wrong, the
finished class is scanned for own-class calls it never declared
(`JvmClassShaker.unresolvedSelfMethods`); an unresolved `_apply`/`_store`/
`_eval` throws `GateUnderpredicted` and the whole compile re-runs with
`GROUP_EVAL` forced on. That recovery is correct in itself. What is wrong is
what triggers it.

`BuiltinFunctionWrappers.generate(...)` injects a first-class wrapper defun for
essentially every built-in, minus an explicit exclude set. The wrappers for
`mapcar`/`mapc`/`mapcan`/`maplist`/`every`/`some`/`map`/... all call `_apply`.
They are injected into EVERY program, whether or not it ever mentions them, so
the scan always finds an unresolved `_apply` and the gate is always forced on.
Verified on the smallest program that has a variable at all:

```
$ echo '(let ((s 0)) (print s))' | ... -o T.class
[gate] underpredicted=[eval, arrays] unresolved=[_apply(...) (called from
  EVERY, SOME, NOTANY, NOTEVERY, MAPCAR, MAPC, MAPCAN, MAPLIST, MAPCON, MAPL, MAP), ...]
```

The `--optimize` shaker deletes the wrappers afterwards, because nothing
references them -- so for a program with no top-level `setq` the mistake is
invisible and free. The moment the program HAS one, the forced gate has already
made `evalStoreRef` non-null, `JvmSetqCompiler` has already emitted a real
`_store` call per assignment, and now the eval runtime is genuinely reachable
and cannot be shaken away. The gate's own recovery mechanism creates the
reference that justifies the gate.

Measured, same machine, 2026-08-25:

| program | class size |
| --- | --- |
| `(let ((s 0)) (print s))` | 3,988 B, 12 methods |
| `(let ((s 0)) (setq s 1) (print s))` | 33,992 B, 101 methods |
| `(dotimes (i 10) 1)` | 34,014 B |

**8.5x the artifact for one `setq`**, plus the per-assignment alist walk
`.todo/518` measures at up to 7x run time. `(dotimes (i 10) 1)` is in the table
because `expandDotimes` produces a `setq`: the trigger is not something a user
would connect to `eval`.

## What to build

Decide the eval gate against what SURVIVES tree-shaking, not against the
pre-shake class. Options, roughly in order of how much they change:

- Exclude the injected built-in wrappers from the unresolved-call scan. They are
  generated, unreferenced by construction unless the program spells the name,
  and the shaker's whole job is to delete them. This is the smallest change and
  the one that matches what the wrappers already are.
- Or make the wrapper injection demand-driven the way `wrapperExcludes` already
  is for `funcall`/`parse-integer`/the hash and array families -- generate a
  wrapper only when the program can reach the name as a value.
- Or run the gate decision after a shake pass, which is the general answer and
  the expensive one (the gate feeds Pass 1's arity table).

Whichever is chosen, `GateUnderpredicted` must stay: it is the backstop for a
gate that is genuinely under-predicted, and this item removes a spurious
trigger, not the mechanism. The wasm backend gates the same runtime through
`usesEval` (`.kb/eval-runtime.md`) -- check whether it has the same spurious
trigger before assuming it is JVM-only.

## Acceptance

- `(let ((s 0)) (setq s 1) (print s))` compiles to a class in the 4 KB class,
  not the 34 KB one, and declares no `_store`/`_envLookup`/`_apply`.
- A program that DOES call `eval` still gets the full runtime, and
  `.kb/eval-runtime.md`'s pinning tests stay green.
- `.todo/518`'s acceptance timings hold with this item alone, and vice versa --
  each is independently sufficient for the run-time cost; only this one also
  recovers the artifact size.
- `ci-spec.yaml` and `ExamplesE2eTest` byte-identical on all four backends.
