# h44. `defclass` rejects a non-keyword `:initarg`

Difficulty: Low

Found 2026-10-30 probing `vecto` (its dependency `cl-vectors`, `paths.lisp:649`:
`(rx :initarg rx)`): `DEFCLASS :initarg expects a keyword`. CLHS allows any symbol.

```lisp
(defclass arc () ((rx :initarg rx :reader rx)))
(rx (make-instance 'arc 'rx 1))   ; SBCL: 1
```

Accept any symbol as an initarg in `defclass`, in `make-instance`/`initialize-instance`
keyword parsing, and in `:default-initargs`, on all four backends (`.kb/clos.md`).
Add the failing case first.
