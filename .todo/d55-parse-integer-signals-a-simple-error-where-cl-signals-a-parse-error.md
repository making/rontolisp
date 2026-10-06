# d55. `parse-integer` signals a `simple-error` where CL signals a `parse-error`

Difficulty: Medium

CLHS `parse-integer`: a string that is no integer syntax (junk, no digits, an empty region) signals
an error of type `parse-error` when `:junk-allowed` is false. Every backend signals a plain
`simple-error` (`parse-integer: junk in string ...` / `no integer in string ...`), so a
`(handler-case ... (parse-error ...))` misses it. Measured 2026-10-06 over `(copy-seq "12a")`,
`(copy-seq "")`, call position and `#'parse-integer`:

| | SBCL 2.2.9 | interpreter / JVM / P1 / component |
|---|---|---|
| class | `sb-int:simple-parse-error` (a `parse-error`) | `simple-error` |

ANSI `numbers` chapter (suite `ca06bd9`, interpreter): `PARSE-INTEGER.ERROR.4` through `.15` and
`.5A` (13 tests) error out on it.

The text comes from the call's expansion (`LispMacroExpander.expandParseInteger`, `(error "..."
str)`) and the interpreter's first-class builtin (`Environment.parseInteger`, a
`LispEvalException`); both must signal the same class with the same report on all four backends.
Check how `read-from-string` raises its `reader-error` on the interpreter (a `parse-error`
subclass there) and whether the compiled paths can make a `parse-error` instance without pulling
the CLOS condition machinery into a program that never handles one (`.kb/error-handling.md`
measures what a `parse-integer` site costs).
