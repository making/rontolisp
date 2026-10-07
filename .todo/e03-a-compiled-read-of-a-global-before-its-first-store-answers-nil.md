# e03. A compiled read of a global before its first store answers NIL instead of signalling

Difficulty: High

```lisp
(defun g () *nb*)
(handler-case (g) (unbound-variable (c) (cell-error-name c))) ; SBCL, interpreter: *NB*
(setq *nb* 1)
```

The JVM, P1 and the component answer NIL. A global that is not special and that no literal
`boundp` probes has no unbound state on the compile paths: its variable starts as nil. A
special without a value signals there since d85 (`.kb/dynamic-special-variables.md`, "A read
of a special without a value"); a top-level `setq` global and a global only a function body
assigns do not.

## Plan

- Start such a global as the UNBOUND marker and check its reads only where a read can come
  before its first store. A top-level `setq` global read by nothing that runs before that
  form keeps the plain field and the plain read, so programs whose globals are all assigned
  before any read stay byte-identical. Count how many globals of size-report, bench-report
  and the examples each candidate rule leaves checked before choosing it.
- The read shape is d85's (`_bound` on the JVM, `emitCheckedRead` on wasm).
- Related, the same on all four backends and documented (`doc/*/reference/special-forms/progv.md`):
  a `progv` short of values binds the extra symbols to nil, where SBCL leaves them unbound. A
  marker bound there would need the read check on every special the `progv` can name.
