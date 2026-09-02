# The ordinal list accessors stop at `fourth`, and chapter 32 stops with them

Difficulty: Low

Split out of `.todo/338` (2026-09-02), whose cons-family bullet is a ~600-test
cluster; this is the one piece of it with a named program behind it.

`first` / `second` / `third` / `fourth` exist -- `LispNames` has them, and
`JvmEvalRuntimeBuilder` line ~1388 spells the "k cdrs, set car" shape for the
family including the `setf` places. `fifth` through `tenth` do not exist at all:

```lisp
(fifth '(1 2 3 4 5 6 7 8 9 10))   ; The function FIFTH is undefined
```

The evidence that this is not only a test count: chapter 32 (`profiler`) of the
_Practical Common Lisp_ corpus is byte-identical to SBCL in every part EXCEPT
its own entry point. `compile-timing-data` sorts `:key #'fifth`, so
`show-timing-data` cannot run at all, and the chapter reads as broken
(`.kb/asdf.md`, the corpus table).

## What it takes

The four that exist are the template -- follow "Adding a Built-in Function" in
`CLAUDE.md` for each of `fifth` `sixth` `seventh` `eighth` `ninth` `tenth`:
`LispNames` + `PackageRegistry.CL_SYMBOLS`, `Environment.createGlobal()`, the
JVM and WASM compiler cases, `BuiltinFunctionWrappers.WRAPPER_DEFS` (`#'fifth`
is exactly how chapter 32 reaches it, so the wrapper is not optional), and the
`setf` place if `fourth` has one. Doc pages + `_catalog.yaml` + the `cl.md` row
per the same section.

Whether they are six separate compiler cases or one arity-parameterised case is
an implementation call -- take whichever the existing four already shape, and
say in the commit which and why.

## Verify

`(fifth '(1 2 3 4 5 6 7 8 9 10))` .. `(tenth ...)` and a short list answering
`nil`, on all four backends, plus chapter 32's `show-timing-data` running
against SBCL. Re-measure `.todo/338`'s cons chapter if the ANSI harness is
cheap to re-run, and update the corpus table in `.kb/asdf.md`.
