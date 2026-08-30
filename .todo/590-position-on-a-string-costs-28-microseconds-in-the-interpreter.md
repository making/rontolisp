# `position` on a string costs 28 us a call in the interpreter, 50x `subseq` and 90x `char`

Difficulty: Medium

Measured 2026-08-30 while choosing a tokenizer for the `geom` model-file
readers (`.kb/geom.md`, "Reading a model file"). The readers ended up not using
either of the slow operators, so nothing is broken -- but the numbers are
absurd and were never investigated.

Interpreter, Apple M4 Max, 100,000 iterations each over the 46-character string
`"v -3.4101800e-003 1.3031957e-001 2.1754370e-002"`:

| form | per call |
|---|---|
| `(dotimes (i n) (setq k (+ k 1)))` -- the loop itself | 0.2 us |
| `(char *line* 3)` | 0.3 us |
| `(subseq *line* 2 17)` | 0.5 us |
| `(parse-integer *line* :start 2 :junk-allowed t)` | **5.4 us** |
| `(read-from-string "-3.4101800e-003")` | 0.68 us |
| `(position #\Space *line*)` | **27.6 us** |

`position` finding a space at index 1 of a 46-character string costs 90 times
what reading a single character costs, and 40 times what `read-from-string`
costs for a full float parse through the reader. `parse-integer` is 18x `char`
for a job that is a handful of digits. Both look like generic-sequence dispatch
running in Lisp -- an `elt`-per-element walk with a `:test` funcall and keyword
defaulting per call -- rather than a Java builtin over the string's own buffer.

## Why it is worth a look

`position`, `find`, `search` and `count` are the operators a text-handling
program reaches for first, and 28 us a call makes any per-line use of them the
dominant cost of that program. The `geom` readers walk with `char` and an index
because of this measurement; that is the right implementation for a shipped
library either way, but a program shouldn't have to discover it.

## Where to start

Find where `position` resolves in the interpreter -- it is not in
`Environment`'s builtin table under that name, which is itself the hint -- and
check whether the sequence operators have a fast arm for `LispString` /
`LispIntVector` / `LispFloatArray` the way `read-sequence` grew one
(`.kb/binary-sequence-io.md`: a declining primitive in front of the general
loop is the established shape). Re-measure the table above before and after;
the compiled backends were not measured here at all and should be, since a
`.class` may already be fine.
