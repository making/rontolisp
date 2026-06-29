# 26 - `mapcar` (and the map* family) should error on a non-list argument

## Problem

In Common Lisp the `map*` family (`mapcar`, `mapc`, `maplist`, `mapcan`,
`mapcon`) operate on **lists**; passing a non-list (e.g. a string) signals a
type error. rontolisp instead silently treats a non-list as the empty list and
returns `nil`:

```lisp
(mapcar (lambda (c) c) "abc")   ; rontolisp => nil ; CL => type error
```

Returning `nil` rather than erroring hides bugs: a caller that mistakenly passes
a string (expecting per-character mapping -- see the discussion that produced
`examples/rainbow.lisp`) gets an empty result with no diagnostic, instead of a
clear "not a list" error pointing at the mistake.

## Desired behavior

Each `map*` function should signal an error when any sequence argument is not a
proper list (nil is a valid empty list and must stay accepted). Message in the
style of the existing evaluator errors, e.g.
`mapcar: argument is not a list: "abc"`.

## Scope / where

- Interpreter: `Environment` (the `mapcar`/`mapc`/`maplist`/`mapcan`/`mapcon`
  builtins) — add the list-type check.
- JVM / WASM compilers: the corresponding `Jvm/Wasm*` map compilers should match
  the interpreter. Note these likely lower to a cons/cdr walk that simply
  terminates on a non-cons; emitting a runtime type check + trap/throw needs a
  small amount of codegen, so weigh cost vs. value (the interpreter check alone
  already catches the common case during development).
- Add a `ci-spec.yaml` (or per-backend test) case asserting the error, per the
  project's bug-fix workflow (write the failing/throwing test first).

## Caveat / ordering

If [[25-generic-map-over-sequences]] lands first, make sure the new errors point
users toward `map` for sequence mapping (e.g. "use map for strings/vectors"),
since that becomes the correct tool. Consider doing 25 and 26 together so the
error message can name the alternative.

Related: 25-generic-map-over-sequences.
