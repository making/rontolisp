# d60. `read` ends at a `#+`/`#-` guard that fails in front of the datum

Difficulty: Medium

The prelude `read` scanner (`%rd-sharp`) collects `#+feature form` as one unit and hands the
text to the one-argument `read-from-string`, which on the interpreter skips the guarded form
and finds nothing behind it: `end-of-file`. SBCL reads the NEXT datum. Measured 2026-10-06:

| form | SBCL 2.2.9 | interpreter | JVM / P1 / component |
|---|---|---|---|
| `(with-input-from-string (s "#+nope (a b) c d") (read s))` | `C` | `end-of-file` | `simple-error` |
| `(read-from-string "#+nope (a b) c d" nil nil)` | `C` | `end-of-file` | `simple-error` |
| `(read-from-string "#+nope (a b) c d")` | `C` | `C` | `simple-error` |

The second row is `%read-from-string-full`, the same scanner. The compiled readers know no `#+`
at all (`.kb/reader-features.md`), so only the interpreter can answer `C` today; the scanner
would have to evaluate the feature expression against the live `*features*` and keep scanning
past a failed guard (the `#+f #+f A B` idiom included, `LispLexer.suppressed`).
