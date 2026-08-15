# `file-position` is nil on every file stream, so nothing can seek

Difficulty: Medium

`file-position` answers **nil for every stream but one** -- the buffered
served-request body (`HttpRequestBodyStream`, whose position is a real byte index
so circular-streams can rewind a body lack-request already parsed). A FILE
stream, the case the operator exists for, answers nil for the query and ignores
the set: the interpreter's `Environment` registration falls through to nil, the
JVM compiles the call to the nil constant (`expandConstantResult`), and WASM
groups it with `file-length` / `file-write-date` as "cannot be determined".

Portable code guards `file-position` with `ignore-errors` and takes a fallback
path, which is why this has cost nothing so far. It has a first real consumer
now: **`uiop:parse-windows-shortcut` and `uiop:parse-file-location-info`
(`.todo/356`, landed) signal `not-implemented-error` naming this primitive**,
because a `.lnk` is parsed by seeking (`(file-position s (+ start offset))`) and
a parser that cannot seek would misread rather than fail. They are upstream's
bodies otherwise -- pure stream reading -- so the day this works, both are a
copy-in.

## What it takes

- **Interpreter**: the stream table already holds the open handle; a file stream
  needs its byte (or character) position tracked and a seek that also drops the
  buffered reader's lookahead. The two directions differ: an input stream can
  seek freely, an output stream must flush first.
- **JVM**: the same, in the emitted I/O runtime rather than in `Environment`.
- **WASM Preview 1**: `fd_seek` is not among the eight imported preview1
  functions -- adding one moves the index-pinned import block
  (`.kb/time-environment-builtins.md` explains why those indices are pinned), so
  this is the expensive backend.
- **`--component`**: `wasi:filesystem/types@0.3.0` reads at an OFFSET rather
  than from a cursor, so the position is a value the Lisp side already has to
  carry -- likely the easiest of the four.

Decide up front whether the answer is "all four or none": the
`file-length`/`file-write-date` precedent (nil = "cannot be determined", which
CL sanctions) is a legitimate place to stop for a backend that cannot do it, but
a SEEK that silently does nothing is not -- it must stay nil-answering rather
than pretend, and a caller that seeks and reads would then get the wrong bytes.
The safe shape is: query and set work where they work, and the set answers nil
(never t) where it does not.

## Gate

`(file-position s)` and `(file-position s n)` over a binary and a character file
stream on every backend that claims support, pinned per backend plus a ci-spec
case; `uiop:parse-windows-shortcut` restored to upstream's body with a `.lnk`
fixture, and its `not-implemented-error` arm and the `.kb/uiop.md`
re-evaluation trigger removed.
