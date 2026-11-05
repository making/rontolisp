# i57. A reader label's sharing between two quote sites

Difficulty: Medium

One literal keeps a label's graph on all four backends (`.kb/quoted-data.md`, "A label's graph"). Two
quote sites of one form do not. Measured 2026-11-05:

| Form | SBCL | Interpreter | JVM, wasm P1, component |
|---|---|---|---|
| `(let ((a '(#1=(x))) (b '(#1#))) (eq (car a) (car b)))` | T | T | NIL |
| `(let ((a '(#1=(x))) (b '#1#)) (eq (car a) b))` | T | T | NIL |
| `(eq '#1=(x) '#1#)` in `cl-user` | T | T | T |
| `(eq '#1=(x) '#1#)` in another package | T | NIL | NIL |
| first row, in another package | T | NIL | NIL |

CLHS 3.2.4.4 asks `compile-file` for this within a file. Two causes:

- `QuotedGraph` walks one datum, and a site's slot is keyed by its root, so a node inside two roots is
  built once per root. A fix needs the sites before their bodies compile, and frontends reuse sublists
  between quote sites, so it changes the emission of programs that never wrote a label.
- `PackageResolver.resolveQuotedData` copies per quote: `LispTrees.rewriteDatumSymbols` needs a memo
  that lives for one top-level form.
