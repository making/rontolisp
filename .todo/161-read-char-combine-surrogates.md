# read-char: combine UTF-16 surrogate pairs into supplementary code points

Every backend now indexes and prints strings BY CODE POINT (todo 153,
`.kb/characters-code-points.md`), but the runtime `(read-char stream)` still
returns a raw UTF-16 code unit on the interpreter and the JVM compile path
(via `BufferedReader.read()`), and one UTF-8 byte on WASM's binary stream
adapter. Reading a supplementary code point via `read-char` therefore surfaces
as its high surrogate first / a UTF-8 lead byte first, not as one CHARACTER
holding the full code point.

The rest of the language treats characters as code points, so this is a
step-change one call away from the rest of the string API.

## Plan

- **Interpreter / JVM compile path** -- when the `BufferedReader.read()` yields
  a high-surrogate code unit, peek/consume the following unit and combine into
  a single supplementary code point before boxing as CHARACTER. `mark(1)` +
  conditional `reset()` on non-matching low half keeps the stream position
  aligned. Applies to the shared `_readChar` body in `JvmIoRuntimeBuilder` and
  the interpreter's `read-char` in `Environment.java`.
- **WASM** -- the binary stream `_read_char` decodes one UTF-8 sequence at a
  time (walk the lead byte's high bits like `_str_char_byte_offset` /
  `_str_char_at` already do): 1..4 bytes -> one code point returned as
  `TYPE_CHAR`. See `WasmIoRuntimeBuilder.buildReadCharBody` /
  `WasmReadRuntimeBuilder`.
- **Native binary + component** -- same as WASM P1; the underlying stream is
  already a byte stream via wasi:cli.

## Non-goals

- `read-char` on a Lisp source-form reader (the compile-time parser) already
  handles multi-byte source as UTF-8 verbatim (bytes carried across); nothing
  to change there.
- Peek-char is a smaller companion op; extend it in the same pass so
  `(peek-char t stream)` returns a code point too.

## Verification

- New ci-spec case: read a supplementary character back through a string
  stream (`with-input-from-string`) and check `char-code` returns the code
  point, not the surrogate half.
- Cross-backend byte-identical.
