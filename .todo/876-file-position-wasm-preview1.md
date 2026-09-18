# `file-position`: real on the WASM Preview 1 backend (fd_seek)

Difficulty: High

WASI Preview 1 imports eight fd-polymorphic functions into the index-pinned import block
(`.kb/time-environment-builtins.md` explains why the indices are pinned). `fd_seek` is not
among them, so adding it shifts `IMPORT_FUNC_COUNT` and `FUNC_START`, and with them every
emitted function index and the `--component` adapter blobs. This is the expensive backend.

## What it takes

- Add `fd_seek` as the NINTH imported preview1 function: `IMPORT_FUNC_COUNT` 15 -> 16,
  `FUNC_START` with it, `WasmLispCompiler`'s import section and the typed `wasi:filesystem`
  signatures (a new offset/whence type, or reuse an existing one if the layout matches).
- A `_file_position(fd, pos)` / `_file_position_set` runtime pair over `fd_seek`, read and
  write direction, so a file fd's current offset is known and settable. The Preview 1
  `fd_seek` (whence 0 = SET) repositions the fd's cursor, so both the query and the set can
  ride one import together with the `file-size` stat the other file helpers already use.
- `WasmExprCompiler` currently groups `FILE_POSITION` with `FILE_WRITE_DATE` as "cannot be
  determined" (both sides answer the nil constant). Split `FILE_POSITION` out to a real call
  for a file fd, and keep the two-argument form answering T after the seek / nil where the
  fd is not a file.
- The `--no-wasi` variant needs the EBADF trap stub. The interpreter/JVM answer a character
  file stream nil for the set; a WASI fd is element-type-agnostic, so decide what a
  character stream does here (likely the same nil for the set, mirroring `file-length`).

## Gate

The ci-spec case (`file-position` query + set over a binary file stream) gains a wasm row.
`WasmLispCompilerIntegrationTest`'s `(print (file-position t))` keeps answering nil (standard
output is not a file fd), and its Gray-rewrite pin around `stream-file-position` is
unchanged. **Risk**: the index shift must be verified against the `--component` adapter in
`.todo/877`, which depends on the same function indices.
