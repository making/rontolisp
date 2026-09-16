# `#S` slot designators and `:allow-other-keys`

Difficulty: High

Split out of `.todo/807` (closed 2026-09-16 after its "read errors are conditions"
family landed 47 tests / 0 regressed): the five `SYNTAX.SHARP-S.*` tests that were
807's explicitly-kept remainder.

## The five tests

| test | asks |
|---|---|
| `SHARP-S.3` | a STRING slot designator: `#s(syntax-test-struct-1 "A" x)` fills slot `A` |
| `SHARP-S.4` | a CHARACTER slot designator: `#S(syntax-test-struct-1 #\A x)` fills slot `A` |
| `SHARP-S.6` | `:allow-other-keys` as a non-slot licensing unknown ones: `#S(syntax-test-struct-1 :a x :allow-other-keys 1)` |
| `SHARP-S.7` | same with a nil value: `:b z :allow-other-keys nil` |
| `SHARP-S.8` | same licensing a genuinely unknown slot: `:b z :allow-other-keys t :a x :foo bar` |

## What it needs

- **Reader half, three lines**: accept a `LispString`/`LispChar` slot designator in
  `LispReader.readStruct` and in `StructLiteralFolder` (the runtime-read half).
- **`:allow-other-keys` is a FOLD rule**, and the fold's rules are pinned across four
  backends: `StructLiteralFolder` covers the compile paths and the interpreter, while
  `JvmReadRuntimeBuilder`'s emitted reader spells the same slot lookup out in
  hand-written bytecode (`has no slot named`). Five tests against a four-backend edit.
- Take it with `.kb/instance-syntax.md` open, or not at all. Re-measure from
  `ansi-test/results/logs/` by failing test NAME (`.todo/715`, "How to count") and
  report the effect as a diff -- 807's close was measured that way (47 fixed / 0
  regressed, reader chapter 312/575 -> 359/575).

## Leftovers 807 named but did not home

These stay with their owners; only the five above move here: `simple-array`
`#A`/`#(` literals (`.todo/043`), `simple-vector-p`/`simple-bit-vector-p`
(`.todo/820`, `.todo/180`), `name-char` (`.todo/008`), `SYNTAX.ESCAPED.*`
(`.todo/156`), `#C`/`#P` expected values (`#.` in a macro form's argument --
re-check `UserMacroExpander`'s resolution point first), `upgraded-array-element-type`
(no owner -- file one if it reproduces), and the one-offs (`SHARP-LEFT-PAREN.9`,
`SHARP-BACKSLASH.3/6/7`, `SHARP-DOT.2`, `SHARP-BAR.2/8/9/10`, `DIGITS.ALPHABETIC.1`,
`NUMBER-TOKEN.5`). `READ.ERROR.8` (read arity) belongs with `.todo/031`;
`READ-FROM-STRING.ERROR.7/9-13` (the real lambda list) with `.todo/214`.
