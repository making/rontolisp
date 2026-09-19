# `file-position`: real on the JVM backend for a binary file stream

Difficulty: Medium

The interpreter half landed (2026-09-18, `.todo/390`): the byte primitives advance a
per-handle position and the set re-opens the file at the offset. The JVM backend is
the remaining host side, and it is the one `uiop:parse-windows-shortcut` (`.todo/878`)
needs before that parser can be restored.

## What it takes

- `JvmIoRuntimeBuilder`:
  - A `STREAM_POSITIONS_FIELD` (`_streamPositions`) `Object[]` side table, parallel to
    `_streamPaths`, holding a boxed `Long` position per handle; growth and storage like
    `buildSetStreamPath`.
  - Bump it in `_readByte`, `_writeByte`, `_readSeqPacked` and `_writeSeqPacked` when the
    handle is a file stream (the `_streamPaths` entry is non-null), mirroring the
    interpreter's `streamPositions.merge`.
  - A `_filePosition(Object handle, Object posOrNull) -> Object` helper: `null` pos is the
    one-argument query (return the boxed position, or null for any other stream kind); a
    boxed Long is the set (re-open the file at the offset, dropping the buffered
    lookahead; flush an output stream first; no CREATE/TRUNCATE on the re-open so the file
    keeps everything before the offset) -- answers T on success, null where it cannot.
  - Gate it on a NEW `FileMeta.position` boolean (like `fileLength`) so an artifact that
    never calls `file-position` pays nothing; `NONE` and the constructor and the
    `setStreamPath`-style field/method refs all follow the file-length pattern.
- Wire `JvmExprCompiler` (currently `case FILE_POSITION -> expandConstantResult(nil)`) to
  emit a call to `_filePosition` with the handle and the optional position (null when the
  one-arg form). `JvmLispCompiler` adds the `_streamPositions` field + `filePosition()`
  gate, exactly as it already does for `_streamPaths`.
- The stream is a VALUE in the JVM too, so `(position)`/`(setf position)` on a `LIspInstance`
  Gray stream already dispatches through the Gray generics on all backends; this item is
  the native file-stream path only.

## Gate

`JvmLispCompilerTest` gains the twin of `LispEvaluatorTest#binaryFileStreamPositionQueriesAndSeeks`
(query + set over a binary input and output file stream; a character file stream still answers
nil). Two existing JVM pins currently assert `file-position` compiles to nil and must be
updated: `compileAndRun` around `(print (file-position t))` and the Gray rewrite case. The
corresponding `WasmLispCompilerIntegrationTest`/ci-spec rows stay as they are (WASM keeps
answering nil on the set -- `nil = cannot be determined` is CL-sanctioned).
