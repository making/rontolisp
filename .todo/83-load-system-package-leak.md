# 83: `asdf:load-system` / `load` leaks `*package*` to the caller

Status: **known divergence, worked around in an example** (found 2026-07-06 while
adding `examples/http-handler-cl-who.lisp`). Low priority; a real fidelity gap.

## Symptom

After `(asdf:load-system :cl-who)` (or loading any system whose sources contain
`(in-package ...)`), the current package is left pointing at the *last* package
the loaded sources selected -- e.g. `cl-who` -- instead of being restored to the
caller's package:

```lisp
(asdf:load-system :cl-who)
(princ *package*)   ; => cl-who   (should stay cl-user)
```

In real Common Lisp, `load` binds `*package*` (and `*readtable*`) dynamically
for the duration of the load, so a file's internal `in-package` never leaks to
whoever called `load`/`load-system`.

## Why it has not bitten before

Every existing asdf demo (`split-sequence` / `parse-number` / `cl-utilities` /
`cl-who`) refers to library functions by **fully qualified** names
(`split-sequence:split-sequence`, `cl-who:with-html-output-to-string`), so the
leaked current package is irrelevant to how those symbols resolve.

The leak first became visible in `examples/http-handler-cl-who.lisp`: it defines
a top-level `handle` *after* the load and then hands `'handle` to
`rontolisp:http-handler`. With the package left as `cl-who`, `handle` is
defined/quoted as `cl-who::handle`, and at serve time `apply` on the handler
funcref fails with **"The function handle is undefined"** -> HTTP 500 on every
request, on all three serving backends (interpreter / JVM / WASM component).

## Current workaround

`examples/http-handler-cl-who.lisp` inserts an explicit `(in-package :cl-user)`
right after the `asdf:load-system` call. Harmless and CL-idiomatic, but it should
not be *necessary*.

## Proper fix (when picked up)

Make the loading machinery save/restore the "current package" around a loaded
file/system so a nested `in-package` cannot leak out. This spans:

- the interpreter `load` / `defsystem`+`load-system` special forms
  (`LispEvaluator`),
- the compile-path `PackageResolver` pass over `LoadInliner`/asdf-spliced forms
  (the resolver walks the whole program including inlined loads, so it must push
  the active package before descending into a spliced file and pop it after).

Add a reproducing case: a top-level function defined after a load and referenced
by unqualified quoted symbol must resolve. Verify on all four backends
(interpreter / JVM / WASM Preview 1 / component); the http-handler example above
is a ready end-to-end repro for the three serving backends.
