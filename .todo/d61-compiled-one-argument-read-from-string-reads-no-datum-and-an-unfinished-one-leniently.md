# d61. The compiled one-argument `read-from-string` reads no datum and an unfinished one leniently

Difficulty: Medium

`(read-from-string string)` on JVM, P1 and the component answers instead of signalling when
the string holds no datum or ends inside one. Measured 2026-10-06 (`multiple-value-list`):

| string | SBCL 2.2.9 / interpreter | JVM / P1 / component |
|---|---|---|
| `""` | `end-of-file` | `(NIL 0)` |
| `"   "`, `"; c"` | `end-of-file` | `(NIL 3)` |
| `"(a b"` | `end-of-file` | `((A B) 4)` |
| `"\"ab"` | `end-of-file` | `("ab" 3)` |

A call passing more than the string (`%read-from-string-full`, over `read`'s scanner) already
answers SBCL's on all four. The stray `)` of the same family is `.todo/d56-read-from-string-of-a-stray-close-paren-answers-nil-compiled.md`; the two want one fix in
`_readFromString` / the WASM `_read_expr` entry (a typed `end-of-file` / `reader-error`, which
the emitted readers cannot raise today: their errors are `simple-error`).
