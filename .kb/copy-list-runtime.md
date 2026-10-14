# `copy-list` keeps a dotted tail; a string's elements are characters

**Invariant: `(copy-list x)` answers a fresh spine whose last cdr is `x`'s final atom -- a
dotted list copies dotted (CLHS `copy-list`) -- and a non-list signals, on all four
backends. `(map 'string ...)`, and with it every `(coerce x 'string)` that builds, signals
on a non-character element instead of joining its printed text.** Pinned by ci-spec
`copy-list-keeps-a-dotted-tail-and-coerce-string-rejects-non-characters`.

## `copy-list`

Two homes, which must change together: `Environment`'s native `COPY-LIST` (interpreter) and
`LispMacroExpander.copyListRuntimeWrapper()` -- the `%copy-list-runtime` defun
`JvmLispCompiler`/`WasmLispCompiler` inject once per program naming `copy-list` (source or a
`#'copy-list` wrapper body; gate `programUsesCopyList`), the `%sort-runtime` pattern
([sort.md](sort.md)). Every compiled site is `(%copy-list-runtime x)`.

- The lowering it replaced, `(append x nil)`, walks its argument to nil: a dotted list was a
  `ClassCastException` on the JVM and a cast trap on wasm; the interpreter dropped the atom.
- `append` itself was NOT made lenient: `(append '(1 . 2) nil)` still fails, as SBCL does.
- A non-list is a string-datum `error` ("The value 5 is not of type LIST"): a
  `simple-error` on the compiled paths, `type-error` on the interpreter. An
  `(error 'type-error ...)` in the helper would pull the condition-instance runtime into
  every copying program, even a one-line one.
- `expandCopyList(cons, false)` (still `(append x nil)`) is reachable only by a program that
  defines `%copy-list-runtime` itself.

## Cost (2026-09-18)

A native `_copyList` per backend was weighed and not built: the Lisp helper is one spelling
for both compilers, and the gap is small -- a program with no `copy-list` is unchanged, and
the helper costs the copying programs little on either backend.

The wasm gap is the helper's `(car cur)`/`(cdr cur)` re-testing for nil after the loop's
`atom` test already proved a cons -- a compiler narrowing that would speed every such loop,
not a copy-list matter.

## `map 'string` element check

`expandMap`'s string accumulator wraps each element in
`(if (characterp e) e (error "The value ~s is not of type CHARACTER" e))` before
`%princ-piece`. `coerceToStringBody` is a `(map '%seq-string-result #'identity ...)`, so
the conversion trio, every inline `coerce` to string, `concatenate 'string`'s argument
normalizer and the interpreter's declined fast arm all inherit it. Same string-datum class
rule as above; the inherited check costs a program with a `coerce`, a
`map 'string` and string sequence operators only a few bytes.
