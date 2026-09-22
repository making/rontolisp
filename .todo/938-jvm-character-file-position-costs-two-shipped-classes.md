# The JVM backend ships two runtime classes for character `file-position`

Difficulty: Medium

Split out of `.todo/925` (2026-09-22), which made `file-position` on an ordinary
character file stream answer the byte offset on all four backends.

## What it costs

A program that names `file-position` and may open a character file stream
(`LispMacroExpander.mayOpenCharacterFileStream`) now ships `runtime/RontoCharFileReader`
(4,788 B) and `runtime/RontoCharFileWriter` (3,267 B) beside `Prog.class` (+180 B): +8,235
B total, and the output goes from ONE file to THREE. Measured 2026-09-22 on a character
write+read program with two `file-position` calls: JVM 15,832 -> 24,067 B (P1 13,067 ->
13,073, component 18,681 -> 18,717 -- WASM barely moves; this is a JVM-only cost).

It reaches programs that never write `file-position` themselves: `httpbin-jzon.lisp` pays
the same +8.2 KB because the jzon library calls `file-position` internally, so every jzon
user pays for a feature they never asked for.

## Precedent

`.todo/918` measured a similar shape (+5 KB JVM, 2 output files) for a computed `:io` and
withdrew the change rather than pay it. `.todo/925` accepted this one for one ANSI test
(`FILE-POSITION.5`) plus cross-backend agreement -- but did not check whether the gate
could be narrower or the classes smaller.

## Directions to measure (not a decided plan)

- Do not ship the two classes when the only `file-position` reference in the program is
  inside a spliced library such as jzon, or narrow the gate to call sites that can
  actually reach a character FILE stream (as opposed to any stream at all).
- Fold `RontoCharFileReader` / `RontoCharFileWriter` into one class, or into the existing
  runtime, so the output stays one file instead of three.
- Emit the offset tracking as methods appended to `Prog.class` instead of two shipped
  classes.

Whatever the direction, the byte-offset answer must stay identical on all four backends
(`.kb/read-load-streams.md`, "file-position").

## How to measure

A size table (JVM / P1 / component) on: the `.todo/925` character write+read program, an
httpbin-jzon-shaped program, a binary-only `file-position` program (should be unaffected),
and hello_world (should be unaffected). Report the output file count alongside the byte
totals.

If the measurement says the cost cannot be cut without losing the byte-offset answer, that
IS the result: record the numbers and the date in `.kb/read-load-streams.md` and close this
item without forcing a change through.
