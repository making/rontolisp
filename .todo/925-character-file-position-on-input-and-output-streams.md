# A real `file-position` for an ordinary `:input` / `:output` CHARACTER file stream

Difficulty: Medium (a per-handle byte offset on four backends; the read side's line
terminator is the hard part)

Split out of `.todo/918` (2026-09-22). `:direction :io` / `:if-exists :overwrite` landed as
their own stream kind that owns its cursor, so their `file-position` is real on all four
without any offset tracking -- which overturned the premise that the character offset was
a prerequisite for `:io` (`.kb/read-load-streams.md`, "`file-position` is REAL on ALL FOUR
backends"). What remains is the ORDINARY character stream, which still answers nil
everywhere (CL-sanctioned "cannot be determined"); SBCL answers the byte offset.

## Measured (2026-09-22, interpreter, ANSI `streams`, suite `ca06bd9`)

Worth exactly ONE test: `FILE-POSITION.5` (an `:output` character stream,
`(> (file-position os) 0)` after one `write-char`; `Expected integer, got: NIL`).
`FILE-POSITION.1`-`.4` need `file-position.txt`, which the suite does not ship.

## What it needs

- Output side (enough for the one test): a per-handle byte count bumped by every character
  write -- interpreter `emitTo`, JVM `_writeStr`, WASM `_write_stream_str` -- by the UTF-8
  length (`file-string-length`'s number). Preview 1 needs nothing: `fd_seek` already answers
  the descriptor offset, and writes go straight through; only the per-fd "position is real"
  flag byte has to say yes for a character output fd.
- Input side: `BufferedReader.readLine` hides whether the terminator was `\n` or `\r\n`, so
  a bumped counter can be off by one per CRLF line on the interpreter/JVM. Preview 1 reads
  one byte per `fd_read`, so its descriptor offset IS the logical position (except after
  `read-sequence`'s 64 KiB bulk character path).
- Decide whether to do both halves or only the output one; either way all four must give
  the same answer (pin with a ci-spec case beside `file-position-round-trips-on-a-binary-
  file-stream`, and change `FILE_POSITION_EXPECTED`'s trailing `NIL` on all three test
  classes together).
