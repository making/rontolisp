# j49. Starlark: `load()`, freezing, and calls across languages

Difficulty: High

After `.todo/j46`. Not covered by the prototype (`.todo/artefacts/j45-starlark-front-end-prototype/`).

- `load("mod.star", "a", b = "c")`: the module is read through the seam's `SourceLoader`
  like a Scheme `include` or a Clojure `require` (`.kb/source-language.md`, "The files a
  source names travel with the read too"), evaluated once, and only its non-underscore
  globals are importable. A module's globals need their own namespace: two files may both
  define `helper`. The prototype's per-chunk prefix shows the simplest form.
- Freezing: when a module finishes loading, every value reachable from its globals is frozen
  and mutation is an error. The prototype's lists and dicts already carry the `frozen` flag
  and check it; what is missing is the walk and its trigger.
- Mutation during iteration is already an error in the prototype; keep it.
- Across languages: a Common Lisp file `(load "lib.star")` and calls a Starlark function,
  a Starlark file calls a function of a Common Lisp file. Decide what a foreign caller sees
  -- the direct `defun` entry, the function value, or both -- and how `None`, lists and
  dicts appear on the other side. Host-predeclared names (what Bazel's `struct` or `json`
  are to Starlark) go through the same door.

Verified on the four backends; a case per behavior in the corpus driver of `.todo/j47` or
`ci-spec.yaml`.
