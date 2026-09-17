# Scheme: the names the SICP corpus assumes -- `true`/`false`/`nil`, `(scheme cxr)`, list procedures, `random`, `runtime`

Difficulty: Low

Split off `.todo/826` (its `(scheme cxr)` row) for `.todo/828`. Files of the corpus that use
each name WITHOUT defining it (static scan, 2026-09-17; all 1,592 files):

| name | files | lowers to |
|---|---|---|
| `false` / `true` | 125 / 55 | the false value / `T`. Ordinary VARIABLES, not literals: the corpus rebinds neither, but `(define-variable! 'true true env)` passes them as values |
| `nil` | 45 | `NIL` (the empty list). The Scheme identifier `nil` is lowercase, so it is not the CL symbol today: "The variable nil is unbound" |
| `caddr` / `cadddr` | 33 / 9 | `caddr` / `cadddr`; add the whole `(scheme cxr)` set (24 names) as rows tagged `cxr` |
| `filter` | 11 | `(remove-if-not pred l)`; `reduce`, `fold-left`, `fold-right` (5), `delete`, `last-pair` (2), `append!`, `list-index`, `1+`, `-1+` beside it |
| `random` | 7 | `(random n)`: exact `n` -> exact in `[0,n)`, inexact -> inexact (`.kb/random.md`) |
| `runtime` | 4 | elapsed seconds as a real (`.kb/time-environment-builtins.md`) |

- Do NOT add `remove`: the book's own is `(remove item sequence)`, the usual library one
  `(remove pred list)`. The two corpus files that call it undefined are fragments either way.
- `fold-right`/`fold-left`/`reduce` take `(op initial list)`; the corpus never passes more
  than one list.
- Where they live: a library tag of their own in `SchemeBuiltins` (not `base`), visible
  when the program has no `(import ...)` -- `imports()` adds `base` and `write` only today,
  and the REPL's "everything visible" must follow.
- A user `define` of any of these (`filter` is defined by 2 corpus files, `last-pair` by
  12) must keep winning, as it does for `square` today.
- 99 `scheme`-category files use at least one of these names (`baseline.tsv`, last
  column); most fail today only when the path that reads the name runs.

Cases in `scheme-spec.yaml`, all four backends; rows in `doc/*/guides/scheme.md`.
