# i27. McCLIM's Core needs closer-mop:standard-instance-access

Difficulty: Medium

Measured 2026-11-05, interpreter: `(ql:quickload :clim)` reads cluffer and its package-local
nicknames, `clim-lisp` and its users, and `Core/system/dynamic-vars.lisp:374` stops:

```
error: The symbol STANDARD-INSTANCE-ACCESS is not external in the CLOSER-MOP package
```

It uses `mop:standard-instance-access` and its `setf` as a fast path over a slot. The name is not in
`PackageRegistry.CLOSER_MOP_EXTERNALS`, and `src/` has no such operator. What follows it is unmeasured.
