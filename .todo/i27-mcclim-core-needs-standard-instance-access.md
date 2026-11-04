# i27. McCLIM's Core needs closer-mop:standard-instance-access

Difficulty: Medium

Measured 2026-11-04 after `.todo/h97`, interpreter. `.todo/i05` is the first stop of `(ql:quickload
"mcclim")`, through cluffer. Loading `Core/system` by hand past that (its dependencies, then `patch`,
`packages`, `singleprocessing`, `setf-star`, `dynamic-vars`), `clim-lisp` and its users read fine and
`dynamic-vars.lisp:374` stops:

```
error: The symbol STANDARD-INSTANCE-ACCESS is not external in the CLOSER-MOP package
```

It uses `mop:standard-instance-access` and its `setf` as a fast path over a slot. The name is not in
`PackageRegistry.CLOSER_MOP_EXTERNALS`, and `src/` has no such operator. What follows it is unmeasured.
