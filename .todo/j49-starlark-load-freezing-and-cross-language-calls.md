# j49. Starlark: `load()`, freezing, and calls across languages

Difficulty: High

Not covered by the lowering of `.kb/starlark-frontend.md`, nor by the prototype before it
(`.todo/artefacts/j45-starlark-front-end-prototype/`): `load` is refused at its position.

- `load("mod.star", "a", b = "c")`: the module is read through the seam's `SourceLoader`
  like a Scheme `include` or a Clojure `require` (`.kb/source-language.md`, "The files a
  source names travel with the read too"), evaluated once, and only its non-underscore
  globals are importable. A module's globals need their own namespace: two files may both
  define `helper`. The prototype's per-chunk prefix shows the simplest form.
- Freezing: when a module finishes loading, every value reachable from its globals is frozen
  and mutation is an error. The lists and dicts of `starlark.lisp` already carry the `frozen`
  flag and check it; what is missing is the walk and its trigger. A frozen list is hashable.
- Mutation during iteration is already an error. A loop gives its count back on a normal exit
  only, so a caller that catches a Starlark error (a Common Lisp `handler-case`) keeps a
  list that still refuses mutation: decide whether the boundary resets it.
- Across languages: a Common Lisp file `(load "lib.star")` and calls a Starlark function,
  a Starlark file calls a function of a Common Lisp file. Decide what a foreign caller sees
  -- the direct `defun` entry, the function value, or both -- and how `None`, lists and
  dicts appear on the other side. Today both exist under one spelling (`|name|`, a `defun`
  and a variable), a Common Lisp function is no callable (`invalid call of non-function`)
  and a Common Lisp value's type is `"unknown"`. Host-predeclared names (what Bazel's `struct` or `json`
  are to Starlark) go through the same door.

Verified on the four backends; a case per behavior in `StarlarkSpecE2eTest#conformance` or
`ci-spec.yaml`.
