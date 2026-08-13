# A string output stream copies every write into linear memory, and never reclaims it

Difficulty: High

On the WASM backends a string output stream -- what `with-output-to-string`,
`format nil` and the whole print family write into -- is a record heading a
LINKED LIST of chunks, and every write appends one:

> since a string's bytes now live on the GC heap (no stable linear address),
> appending COPIES the content into a persistent linear buffer and the chunk
> references that copy
> -- `WasmStringStreamRuntimeBuilder`

So the cost is **per WRITE, not per stream**: a 12-byte chunk record plus a
persistent copy of the content, bump-allocated and reclaimed only when the
enclosing `__ronto_alloc_reset` runs (a whole request, on a reactor; never, in a
program that has no such bracket). A `write-char` loop -- the shape the
in-tree decoders take -- therefore costs **15 bytes of linear memory per
character**.

## Measured (2026-08-13)

A `--no-wasi --optimize=size` module pulling 4 x 64 KiB through a `:bytes`
import and building a string from each chunk; the arena mark is read at every
pull, so the growth column is what ONE 64 KiB chunk cost:

| how the 65536 characters are written | arena per chunk | per character |
| --- | --- | --- |
| `write-char` in a loop | 983,056 B | **15.0 B** |
| `write-string` of 1 KiB at a time | 66,448 B | 1.01 B |
| `concatenate` -- no stream at all | **0 B** | 0 B |
| the chunk never read (baseline) | 0 B | -- |

`concatenate` answers zero because a GC-array string never touches linear
memory. That is the whole gap: the stream is the only string builder that does.

## Where it is already paid

`http-server.lisp`'s `%http-utf8-decode-octets` is a per-character `write-char`
into a `with-output-to-string`, and it is what a reactor runs over every chunk
of every request body. Draining a body a chunk at a time -- a handler that
counts the octets and keeps NOTHING -- therefore costs 15x the body in linear
memory:

| body, streamed 64 KiB at a time | linear memory after |
| --- | --- |
| 256 KiB | 4,063,232 B |
| 1 MiB | 15,859,712 B |
| 4 MiB | 63,045,632 B |

against 262,144 B, flat, for the same body a handler never reads. The boundary
itself is flat either way (`.todo/341` Phase 2b, `WasmReactorBodyE2eTest` /
`WasmReactorStreamingHostE2eTest`): what grows is entirely this. **This is the
correction to `.todo/341`'s reading of that column** -- it blamed `read-all`
building the body as one string, and `read-all` is not what costs; the same 15x
is there for a drain that builds nothing.

Same shape, same file family: `%http-percent-decode` (per character),
`%http-utf8-decode` under it, and every `format nil` in a loop.

## The fix, and why it is not a one-liner

The chunk list exists because a chunk has to name a STABLE address and a GC
string does not have one. Two shapes, both real changes to the record layout in
`WasmStringStreamRuntimeBuilder`:

- **one growable linear buffer per stream** (`[kind=1][buf][len][cap]`,
  doubling): appends copy into it, `_str_stream_contents` is one copy out.
  Amortised ~2-4 bytes per character instead of 15, and the per-write record
  disappears. Still linear memory, still only reclaimed at the arena reset.
- **hold GC references instead** -- an `array (ref $str)` of the written
  strings, concatenated at `get-output-stream-string`. Then a string output
  stream costs NO linear memory at all, which is the answer that matches where
  strings already live. Needs a new GC type, so it must be GATED like every
  other type addition (byte-identity for modules that build no string stream).

The second is the essential one; the first is the cheap one. Whichever lands,
the gate is the table above re-measured, plus the `.todo/341` reactor table:
a chunk-at-a-time drain of a 4 MiB body must stop costing 63 MB.

## What must not change

- `get-output-stream-string`'s contract (it CLEARS the stream) and the bivalent
  print family's routing -- `_write_stream_str` is the sink for both.
- The interpreter and the JVM build these with a Java writer (`StringWriter` in
  `JvmIoRuntimeBuilder._makeStringOutputStream`) and pay none of this; the fix is
  WASM-side only, and the four-backend output must stay identical.
- `--no-gc` has no GC strings at all, so the second shape above cannot exist
  there -- whatever it keeps has to be the first one.
