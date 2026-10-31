# h90. ci-spec `loop ... being the external-symbols` cases still pin the old empty iteration

Difficulty: Low

Native E2E (`CiSpecE2eTest`) at `17d050110`, 2026-11-01: 24 failures, all from three cases on
every backend -- `loop-being-symbols-lite`, `loop-being-symbols`, `loop-macro-compiles-and-runs`.
They expect `NIL` / `0` for

```lisp
(loop for s being the external-symbols of :cl collect s)
(loop for s being each present-symbols in :cl-user count s)
```

h86 (`0ad71deb2`..`e3ef886bf`) made package-symbol iteration walk the package, so the backends now
answer the full `cl` export list and `687`. Decide the right answers against SBCL (a fresh
`cl-user`'s PRESENT symbols are few, so `687` looks like inherited symbols counted as present),
fix the iteration if it is wrong, and update the three cases so every backend agrees. A full
`cl` export list is a brittle expectation; pin a property (length, membership) instead.
