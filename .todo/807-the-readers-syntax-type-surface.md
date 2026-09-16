# The reader's syntax-type surface

Difficulty: Medium

Re-measured 2026-09-13 (second pass, after the `#+`/radix/label work below landed).
The `SYNTAX.*` row is **108 tests**, down from the 141 this item opened with; the
whole `reader` chapter is 267 failing tests and the suite is
**13,808 / 19,482 (70.9%)**, up from 13,774.

## What landed (measured as a diff of failing test NAMES, 35 fixed / 0 regressed)

| tests | what |
|---:|---|
| 10 | `#+`/`#-` in a RUNTIME `read-from-string` now test the live `*features*`, keyword distinction and all, and the STOP INDEX follows the branch the guard picks (`SYNTAX.SHARP-PLUS.2/3/4/5/9/10/11/12/13/15`) |
| 12 | `#b`/`#o`/`#x` read RATIONALS and take a `+` sign (`SHARP-B.5/7/8/9/10`, `SHARP-O.7/8/9/10`, `SHARP-X.13/14/15`) |
| 8 | `#<n>R`, which did not exist -- it lexed as a symbol (`SHARP-R.1..6`, `SHARP-T.7/8`) |
| 3 | reader labels: unbounded digits, and a label in scope inside its own datum, so `#1=(A B . #1#)` is circular (`SHARP-CIRCLE.3/4/5`) |
| 2 | `PRINT.INTEGERS.BASE.VARIOUS.3/4`, which print in a base and read back |

Mechanics are in `.kb/reader-features.md` ("A RUNTIME read tests the live
`*features*`" and the `#b`/`#<n>R`/`#n=` entries under "Other syntaxes") and
`.kb/instance-syntax.md` (the fold's back-edge rule).

The one other test that moved is `strings RANDOM-STRING-COMPARISON-TESTS`, which
flipped to `STRING=: invalid bounds 0, 11 for string of length 10`. It is the
suite's own randomized generator hitting a `string=` bounds check; nothing in this
change is on that path. **Worth its own item if it reproduces** -- CL wants a
type-error there, not a Java-shaped refusal.

## What the remaining 108 are, by the QUESTION

| tests | what | owner |
|---:|---|---|
| **28** | a read error must be a CONDITION the suite can catch: `reader-error` for a bad token (`.`, `..`, `,`, `1/0`, `#1*`, `#:a:b`, `#<`) and `end-of-file` for input that runs out mid-datum (`#'`, `#(`, `#.`, `\`, `\|`) | **the biggest single reader family left** -- see below |
| 21 | `(typep v 'simple-array)` for a `#A`/`#(` literal read at runtime | array types, `.todo/043` |
| 17 | `simple-vector-p` (8), `simple-bit-vector-p` (9) -- both DEFINED since `.todo/043` (closed 2026-09-16); remeasure whether the 17 still fail and how | `.todo/820` for the bit half, `.todo/180` closed |
| 9 | `#C`/`#P`: the suite builds the EXPECTED value with `#.(complex 1 1)` / `#.(parse-namestring ...)` and the marker survives unresolved into the comparison (`want ((%READ-EVAL (COMPLEX 1 1)) 7)`). A `#.` inside a macro form's argument, not a `#C` gap | `#.` resolution -- see below |
| 7 | `name-char` undefined | characters, `.todo/008` |
| 5 | `upgraded-array-element-type` undefined | arrays |
| 5 | `#S` slot checks: a STRING or CHARACTER slot designator (`.3`/`.4`) and `:allow-other-keys` as a non-slot that licenses unknown ones (`.6`/`.7`/`.8`) | this item -- see below |
| 4 | `SYNTAX.ESCAPED.*`: `\:` and `|:|` read as the keyword `:\|\|`, and `\|a:b\|` as `B` | `.todo/156` -- see below |
| 12 | one-offs: `SHARP-BACKSLASH.3/6/7` (`#\U+xxxx`, astral round trip), `SHARP-LEFT-PAREN.9` / `SHARP-ASTERISK.10` (the `#n(` / `#n*` fill count), `SHARP-DOT.2` (a `#.` datum's home package), `SHARP-BAR.2/8/9/10` (a `\|` inside a block comment), `DIGITS.ALPHABETIC.1`, `NUMBER-TOKEN.5` (`-.` is the symbol) | mixed |

## Take next: read errors are conditions (28 tests)

Every one of these is `(signals-error (read-from-string "...") reader-error)` or
`... end-of-file`. rontolisp throws `LispReadException`, which
`LispEvaluator.foldStructLiteralsOf` converts to a plain `LispEvalException`
(simple-error), so the suite's `handler-case` clause never fires; the seven
`got (NIL) want (T)` rows are ones where nothing is signalled at all.

What it needs: a `reader-error` condition class seeded beside the existing
`end-of-file` one (`ClosRegistry`), the reader distinguishing "input ran out
mid-datum" from "this token is bad", and the conversion site raising the typed
condition. The emitted runtime readers signal a simple-error by design
(`.kb/read-load-streams.md`), so the parity note there has to say what the
interpreter now does instead -- or the backends have to follow. **Settle that
with `.todo/039` (the condition system) before starting**: the class hierarchy
(`reader-error` is both a `parse-error` and a `stream-error`) is its call.

## Also still here, and cheap only at first sight

- **`#S` (5).** The reader half is three lines (accept a `LispString`/`LispChar`
  slot designator) but `:allow-other-keys` is a FOLD rule, and the fold's rules
  are pinned across four backends: `StructLiteralFolder` covers the compile paths
  and the interpreter, while `JvmReadRuntimeBuilder`'s emitted reader spells the
  same slot lookup out in hand-written bytecode (`has no slot named`). Five tests
  against a four-backend edit -- take it with `.kb/instance-syntax.md` open, or
  not at all.
- **`SYNTAX.ESCAPED.*` (4).** `\:` must name the symbol `:`, which means the
  token has to record WHICH characters were escaped: today `readSymbol` drops the
  escape and hands downstream a name in which a package marker and an escaped
  colon are the same character. This is not 4 tests' worth of reader work, it is
  `.todo/156`'s symbol model -- and the same bug reads `|a:b|` as `B`, which a
  library using `|...|` names hits for real.
- **`#C`/`#P` (9).** Not a `#C` gap at all: a `#.` in the ARGUMENT of a macro
  form is not resolved, so the suite's expected value stays a `(%read-eval ...)`
  marker. Check `UserMacroExpander`'s resolution point against a `#.` that
  reaches a macro call rather than a top-level form before filing it anywhere.

## Before starting

Re-measure. Rank from `ansi-test/results/logs/`, by the QUESTION and not by the
test name, and report the effect as a DIFF of failing test names -- see
`.todo/715`, "How to count". The ranking above IS such a diff; the `logs/` tree
is gitignored, so copy it aside before re-running `ansi-test/measure.sh`.
