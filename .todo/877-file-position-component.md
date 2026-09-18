# `file-position`: real on the `--component` backend (offset-based reads)

Difficulty: Medium

The `--component` backend reads files through `wasi:filesystem/types@0.3.0` with an explicit
OFFSET rather than a moveable cursor, so the byte position is a value the Lisp side already
has to carry -- the most self-contained of the two WASM backends (`.todo/876` is the
index-pinned one).

## What it takes

- The component's file reads (`adapter.wat`'s `$fd_read` over `descriptor.read`, or the
  higher-level wit import a file open resolves to) take an offset argument. Track a
  per-fd byte offset on the Lisp/component side and pass it to each read; the set stores the
  new offset.
- A `file-position` arm in the component splice that answers the tracked offset for a file
  descriptor and repositions by updating it (the next read goes there), so both the query
  and the set are pure state, with no host cursor involved.
- The `--component` I/O dispatchers (`.kb/wasi-component.md`) and the async stdin/`serve`
  splits must know it, since a file fd read there also carries an offset.
- Decide and pin the character-stream answer (interpreter/JVM answer nil for the set there,
  mirror that).

## Gate

The ci-spec `file-position` case gains a `--component` row. A binary file stream query + set
round-trips (write a known byte pattern, read to a midpoint, seek back, re-read) on the
component backend. **Dependency note**: `.todo/876` shifts the core function indices that the
adapter blobs are built against, so land this after or together with it.
