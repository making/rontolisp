# The reader's syntax-type surface

Difficulty: Medium

The second half of `.todo/797`, re-measured on 2026-09-13 AFTER `*read-suppress*`
and `read-from-string`'s stop index landed. `reader` is 47.8% (was 10.8%), and
the `SYNTAX.*` row is **141 tests**, down from the 192 that 797 priced -- 51 of
them were the missing second value and are gone.

## What the 141 are

The row is NOT one gap. Counted from `ansi-test/results/logs/reader.log`:

| tests | what | owner |
|---:|---|---|
| ~29 | `simple-bit-vector-p`, `simple-vector-p`, `upgraded-array-element-type`, `name-char` are undefined -- the test reads a literal and then asks a predicate rontolisp lacks | `.todo/043`, `.todo/180`, `.todo/037` |
| 21 | `(typep v 'simple-array)` fails for a `#A`/`#(` literal read at runtime | array types, not the reader |
| ~20 | `#+`/`#-` inside `read-from-string`: the branch AND the index are wrong (`SYNTAX.SHARP-PLUS.2 got (:BAD 14) want (:GOOD 10)`). The frontend's feature set is STATIC (`Features.INTERPRETER`), so a runtime `(push :x *features*)` does not reach the reader, and the whole guarded text is consumed | this item |
| ~12 | `set-syntax-from-char` / `get-macro-character` / `make-dispatch-macro-character` -- the readtable API | `.todo/041` |
| ~15 | radix/ratio token errors (`#b101/100`, `#o-1/2`), the `#S` slot checks, reader labels, escaped-token cases | this item |
| rest | one-offs across `#P`, `#C` (both want a `%read-eval` marker the suite builds with `#.`), rubout/backspace constituent traits | mixed |

**So the honest size of "the reader's own syntax-type gap" is ~35-40 tests, not
141.** The rest is billed to the reader chapter because that is where the test
lives.

## Where to start

`#+`/`#-` at RUNTIME is the largest single piece and the only one whose owner is
clearly the reader: `Features` is immutable for the duration of a read
(`.kb/reader-features.md`), and the runtime `read`/`read-from-string` built-ins
pass `Features.INTERPRETER` rather than a set derived from the live `*features*`
variable. The two must not be allowed to disagree -- `LispEvaluator` already
re-seeds `*features*` from the declared set, so the missing direction is the
other one.

Beside it, and separately owned: `read-preserving-whitespace` (19),
`readtable-case` (`.todo/041`), `read-delimited-list` (6).

## Before starting

Re-measure. Rank from `ansi-test/results/logs/`, by the QUESTION and not by the
test name, and report the effect as a DIFF of failing test names -- see
`.todo/715`, "How to count".
