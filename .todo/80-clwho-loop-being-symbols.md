# 80: cl-who unit 4 -- loop `being the {external-}symbols of PACKAGE` (lite)

Parent: `.todo/76`. One session (small; isolated to `LoopExpander`).

## What cl-who uses

The hyperdoc block at the end of `who.lisp` runs at load time:

```lisp
(let ((exported-symbols-alist
        (loop for symbol being the external-symbols of :cl-who
              collect (cons symbol (concatenate 'string "#" (string-downcase symbol))))))
  (defun hyperdoc-lookup (symbol type) ...))
```

Today `LoopExpander.parseForPiece` throws `loop: unsupported for clause:
being`. This clause is niche hyperdoc plumbing; `hyperdoc-lookup` is never
called in normal cl-who use, so the load only needs the clause to PARSE and
iterate without error.

## Scope (lite)

Add the `being` for-clause to `LoopExpander`:

```
for VAR being {the|each} {external-symbols|present-symbols|symbols} {of|in} PACKAGE
```

- Accept the `the`/`each` filler and both `of`/`in`.
- Accept the three symbol kinds (`symbols`, `present-symbols`,
  `external-symbols`) -- parse all, treat identically.
- **Lite semantics**: iterate over the package's known symbols if cheaply
  available, otherwise **iterate an empty sequence** (rontolisp has no runtime
  intern table; `.kb/symbol-runtime-api.md`). An empty iteration makes
  `hyperdoc-lookup` return nil for every symbol, which is acceptable -- document
  it as a lite limitation. Do NOT invent a symbol table just for this.
- The `hash-key`/`hash-value` variants of `being` are out of scope (add only
  if a future library needs them).

Keep it to the macro-expander (shared by all backends); no per-backend codegen.

## Acceptance

All four backends -- the clause parses and the loop runs (empty result is the
expected lite outcome):

```lisp
(loop for s being the external-symbols of :cl collect s)   ; => NIL (lite)
```

and, embedded, the cl-who hyperdoc `let`+`defun` form loads without error
(covered end-to-end by `.todo/81`). `ci-spec.yaml` case + native
`CiSpecE2eTest`; extend the loop doc page + `.kb/` loop notes with the lite
`being` clause.
