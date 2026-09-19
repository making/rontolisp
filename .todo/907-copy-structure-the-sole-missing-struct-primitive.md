# `copy-structure`: the sole missing struct primitive

Difficulty: Low (a single primitive over the existing struct instance representation)

Split out of `.todo/715` (2026-09-19). Previously billed to `.todo/043`, which
never contained it and closed 2026-09-16 without it --- so it is unowned.

## What fails (2026-09-19, interpreter, suite `ca06bd9`, test-level)

**31 tests, all one reason:** `The function COPY-STRUCTURE is undefined`, in the
`structures` chapter. The failing test names are `STRUCT-TEST-*/N` (e.g.
`STRUCT-TEST-03/20`, `.04/20`, `.08/20`, ...), not `COPY-STRUCTURE.*`, which is
why an operator-name census did not measure them.

## Notes

- `copy-structure` produces a fresh instance sharing the slot VALUES, not the
  slots themselves (CLHS 18.3). The struct instance representation is in
  `.kb/defstruct.md`; the built-in seam in `.kb/adding-primitives.md` (the two
  traps: a `rontolisp:` name in the wrong dispatch chain, and a macro registered
  in one of the two required places).
- It is a `CL_FUNCTIONS`-category function (a defstruct instance is not a special
  form or macro), so it must be registered in the one place `CL_FUNCTIONS` is
  built and reachable on all four backends after `defstruct`.
- A plain interpreter change moves these 31; the compiled backends need the same
  primitive (or a shared prelude defun over the instance accessors, the
  `decode-float`/`ldb-test` precedent in `LispPreludeLibrary`).
- Measure as a DIFF of failing test NAMES (`STRUCT-TEST-*`); report fixed AND
  regressed.
