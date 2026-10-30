# h59. djula: funcallable instances and a `bit-vector` method specializer

Difficulty: High

Split from h49 (2026-10-30). With slot-definition metaobjects in place, `access` loads and
runs on the interpreter; `(ql:quickload "djula")` now stops earlier in its dependency chain:

1. `cl-slice` (`cl-slice-dev.lisp:111`) defines `(:method (axis (slice bit-vector)) ...)`:
   `DEFMETHOD: unknown specializer BIT-VECTOR`. `isSupportedTypeSpecializer` lacks
   `bit-vector` (and its dispatch order against `vector`/`array` on every backend).
2. djula's `compiled-template` (`src/compiler.lisp:25-68`) is
   `(:metaclass closer-mop:funcallable-standard-class)` and installs its renderer with
   `closer-mop:set-funcallable-instance-function`; `render-template*` then does
   `(funcall template stream)`. Needs a seeded `funcallable-standard-class` (under
   `standard-class`), `funcallable-standard-object`, and `funcall`/`apply` of an instance
   on all four backends.

Probe further after each; record the outcome in `.todo/152`.
