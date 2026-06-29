# 25 - Add a generic `map` over sequences ('list / 'string)

## Motivation

rontolisp has the list-only mapping family (`mapcar`, `mapc`, `mapcan`,
`mapcon`, `maplist`, `maphash`) but no Common Lisp `map`, which maps a function
over arbitrary **sequences** and builds a result of a requested type. Without it
there is no idiomatic way to walk a string's characters; callers fall back to
mapping over an index list, e.g. `examples/rainbow.lisp`:

```lisp
(defun upto (i n) (if (>= i n) nil (cons i (upto (1+ i) n))))
(defun iota (n) (upto 0 n))
(defun rainbow-text (s)
  (let ((len (length s)))
    (mapcar (lambda (i) (cons (char s i) (color-at ...))) (iota len))))
```

With `map` this would be a direct `(map 'list (lambda (ch) ...) s)`.

## Scope

Add `map` with the CL signature `(map result-type function &rest sequences)`.

- Result types to support first: `'list` and `'string` (and `nil` = call for
  effect, returning nil). `'vector` only if/when vectors are first-class enough.
- Accept any mix of supported sequence args (list and string), iterating up to
  the **shortest** sequence (CL semantics).
- `'string` result requires every returned element to be a character.
- `result-type` is given as a quoted symbol designator; the compilers already
  see a literal `(quote list)` etc., so it can be resolved at compile time per
  backend (mirror how `concatenate`'s result-type is handled).

## Implementation (per CLAUDE.md "Adding a New Built-in Function")

1. `LispNames` / `PackageRegistry.CL_SYMBOLS`: add `map`.
2. Interpreter: `Environment` — implement over the existing sequence accessors
   (`length` + element access), branching on the result-type designator.
3. JVM compiler: `JvmMapCompiler` + case in `JvmExprCompiler.compileCons()`.
4. WASM compiler: `WasmMapCompiler` + case in `WasmExprCompiler.compileCons()`
   (GC backend; out of scope for `--no-gc`, which has no per-char string access).
5. `BuiltinFunctionWrappers` entry only if `map` should be a first-class value
   (note the result-type arg makes a plain wrapper awkward, like `concatenate`).
6. Docs: per-operator page under `reference/functions/` + `_catalog.yaml`, in
   both `doc/en` and `doc/ja`.
7. `ci-spec.yaml` case (e.g. `(map 'string #'char-upcase "abc")`,
   `(map 'list #'+ '(1 2 3) '(10 20 30))`) so all four backends are checked.

## Notes

- `map-into` is a possible follow-up but not required here.
- Decide the variadic-arity story relative to `.todo/09-wasm-function-arity-cap`.
- Once `map` exists, `examples/rainbow.lisp` can drop `iota`/`upto` and use
  `(map 'list ...)` over the string directly.

Related: [[24-wasm-gc-float-mod-rem]] (also surfaced while writing that example),
26-mapcar-non-list-should-error.
