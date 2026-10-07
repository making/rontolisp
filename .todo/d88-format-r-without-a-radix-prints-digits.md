# d88. `format` `~r` without a radix prints digits instead of English or Roman numerals

Difficulty: Low

With no radix parameter, `~r` prints a cardinal, `~:r` an ordinal, `~@r` Roman numerals and
`~:@r` old Roman numerals (CLHS 22.3.2.1). Here every variant prints the integer in decimal
(`~@r` with a sign, as `~@d`). Measured 2026-10-07, the interpreter of the native binary
against SBCL 2.2.9, the argument read at run time:

| directive, argument | SBCL | here |
|---|---|---|
| `~r` 12 | `twelve` | `12` |
| `~:r` 12 | `twelfth` | `12` |
| `~@r` 12 | `XII` | `+12` |
| `~:@r` 14 | `XIIII` | `+14` |
| `~r` -1234567 | `negative one million two hundred thirty-four thousand five hundred sixty-seven` | `-1234567` |
| `~:r` 101 | `one hundred first` | `101` |
| `~r` 0 | `zero` | `0` |
| `~@r` 4000 | `simple-error` "Number too large to print in Roman numerals: 4,000" | `+4000` |

## Plan

- Measure the JVM, P1 and the component too; the rows above are the interpreter's.
- Implement the four no-radix forms in the shared format renderer
  (`src/main/resources/am/ik/rontolisp/macro/format-render.lisp` and whatever the compiled
  paths use), including SBCL's range limits (Roman numerals 1..3999, old Roman 1..4999,
  the cardinal's largest named power) and its error text.
- Pin the table on all four backends with a shared fixture; add a ci-spec case.
- Update `doc/en` and `doc/ja` for `format` in the same commit.
