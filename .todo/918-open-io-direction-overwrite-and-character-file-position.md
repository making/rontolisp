# `open`'s two missing MODES: `:direction :io`, `:if-exists :overwrite` -- and the character-stream `file-position` they both need

Difficulty: High (a new stream kind in the table on four backends, plus the
logical-offset tracking `.todo/876` left open)

Split out of `.todo/906` (2026-09-20), which landed the rest of `open`'s keyword
table as a guard over the existing modes. These two are the values a guard cannot
express: each needs the backend to open the file DIFFERENTLY.

## Measured (2026-09-20, interpreter, suite `ca06bd9`, ANSI `streams` after `.todo/906`)

| cluster | tests | current message |
|---|---:|---|
| `:direction :io` | 35 + 3 | `OPEN :direction :io is not implemented` |
| `:if-exists :overwrite` on an output open | ~3 | `OPEN :IF-EXISTS supports only the native default value` |
| character-stream `file-position` | 1 alone | `FILE-POSITION.5`: `Expected integer, got: NIL` |

The three belong together: every `OPEN.IO.*` test writes, then
`(file-position s :start)`, then reads back -- so `:io` is worthless without the
character offset, and the character offset is worth one test without `:io`.

## What each needs

- **`:io`** -- a bidirectional file stream. Interpreter/JVM: a `RandomAccessFile`
  (or a channel pair) as a NEW stream-table entry kind, which every operator that
  switches on the entry (`read-line`, `read-char`, `write-string`, `write-char`,
  `read-byte`, `write-byte`, `close`, `file-length`, `file-position`, `listen`,
  `force-output`) must learn. WASM: one `path_open` with both `FD_READ` and
  `FD_WRITE` rights -- reads and writes share the fd cursor, and `fd_seek` is
  already imported (`.todo/876`), so Preview 1 may be the cheapest of the four.
  `--component` reads at an offset the adapter tracks, so its `:io` is the
  adapter's offset plus a write path.
- **`:if-exists :overwrite`** -- open for writing WITHOUT truncating, positioned
  at 0. A new `OpenModes` bit (the mode space is 0..7 today: `OUTPUT_BIT`,
  `BINARY_BIT`, `APPEND_BIT`), the JVM `_open` mode branch, and WASI oflags with
  neither `O_CREAT` nor `O_TRUNC`. `open.error.11` also wants the missing-file
  `file-error`, which the existence guard already gives once the value is
  accepted.
- **Character-stream `file-position`** -- `.kb/read-load-streams.md` records the
  decision and the mechanism: a per-handle logical offset bumped by the character
  reads and writes (the `_bumpStreamPosition` shape), and on Preview 1 `fd_seek`
  minus the unread bytes still in `READ_CURSOR_ADDR`..`READ_END_ADDR`. Answer in
  BYTES, as SBCL does, so a multi-byte character advances it by its UTF-8 length
  (`file-string-length` already answers that number).

## Notes

- `.kb/read-load-streams.md`, "`open` / `with-open-file` / `%probe-file`" and
  "`file-position` is REAL on ALL FOUR backends" are the design homes; the
  existence guard the values hang off is "The `:if-exists` / `:if-does-not-exist`
  table is ONE lowering over `probe-file`" in the same file.
- Cross-backend: pin with a ci-spec case beside
  `open-if-exists-if-does-not-exist-and-probe`.
- Measure as a DIFF of failing test NAMES in the `streams` chapter; report fixed
  AND regressed.
