# Catalog wrappers are narrower than the interpreter's built-ins

Difficulty: Medium

Found 2026-09-26 (`.kb/eval-runtime.md`, "Argument counts"). A function VALUE of these
built-ins takes fewer arguments than the interpreter accepts, so a keyword call through
`funcall`/`apply` or inside a compiled `eval` reports a wrong count where the interpreter
answers:

- `find`, `find-if`: no `:test`/`:key` (`FIND expects 2 arguments, got 4`); both are `binary(...)`
  in `BuiltinFunctionWrappers` while `member`/`position`/`remove` families already take keywords.
- `sort`: no `:key`. Keep the two-argument call cheap -- it is the hot path of every sort.
- `make-list`: no `:initial-element`.

Related, in compiled code outside `eval`: `(apply f 1 2)` with a non-list last argument through a
computed designator is `the value is not of the expected type` on the JVM and a cast-failure
trap on wasm; the interpreter signals the simple-error `APPLY: last argument must be a list`
(the `#'apply` wrapper already does).

Goal: the interpreter's answer on every backend, pinned in `ci-spec.yaml`.
