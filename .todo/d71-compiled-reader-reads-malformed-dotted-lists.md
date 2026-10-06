# d71. The compiled runtime readers read a malformed dotted list as a list

Difficulty: Low

`( . a)` (nothing before the dot) and `(a . b c)` (more than one object after it) are
`reader-error`s in SBCL and the interpreter; the JVM, P1 and the component read them as lists.
Measured 2026-10-06 (`multiple-value-list (read-from-string s)`):

| string | SBCL 2.2.9 / interpreter | JVM / P1 / component |
|---|---|---|
| `"( . a)"` | `reader-error` | `((. A) 6)` |
| `"(a . b c)"` | `reader-error` | `((A . B) 7)` |

The one-argument read already records a stray `)` and refuses it at the call site
(`expandReadFromStringFailure`, `.kb/read-load-streams.md`); these two want the same record from
`_readList` / `_read_list` (and a report text per case, which the record does not carry today).
