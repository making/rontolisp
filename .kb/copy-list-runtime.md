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
  every copying program: +45 KB of class for a one-line program (measured 2026-09-18).
- `expandCopyList(cons, false)` (still `(append x nil)`) is reachable only by a program that
  defines `%copy-list-runtime` itself.

## Cost (2026-09-18)

A native `_copyList` per backend was weighed and not built: the Lisp helper is one spelling
for both compilers, and the gap is small.

| | before (`append`) | after (helper) |
|---|---|---|
| 1,000-element `copy-list` + `length`, 100,000 times, JVM (5 runs avg) | 1.56 s | 1.60 s |
| the same, wasm | 0.99 s | 1.10 s |
| that program's size, JVM / wasm / component | 10,130 / 3,201 / 4,348 B | 10,672 / 3,232 / 4,379 B |
| `(print (+ 1 2))` + `append`, no `copy-list` | 4,272 / 1,337 / 2,504 B | unchanged |

The wasm gap is the helper's `(car cur)`/`(cdr cur)` re-testing for nil after the loop's
`atom` test already proved a cons -- a compiler narrowing that would speed every such loop,
not a copy-list matter.

## `map 'string` element check

`expandMap`'s string accumulator wraps each element in
`(if (characterp e) e (error "The value ~s is not of type CHARACTER" e))` before
`%princ-piece`. `coerceToStringBody` is a `(map '%seq-string-result #'identity ...)`, so
the conversion trio, every inline `coerce` to string, `concatenate 'string`'s argument
normalizer and the interpreter's declined fast arm all inherit it. Same string-datum class
rule as above. Size: +820 B of class / +26 B of wasm for a program with a `coerce`, a
`map 'string` and two string sequence operators.
