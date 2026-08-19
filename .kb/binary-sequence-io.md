# Bulk binary I/O: `read-sequence` / `write-sequence` over a packed buffer

**Invariant: `(read-sequence buf stream)` / `(write-sequence buf stream)` with a
PACKED buffer -- a packed float array of ANY rank (`LispFloatArray`: single-float
= 4 bytes, double-float = 8) or a packed `(unsigned-byte 8|16|32)` vector
(`LispIntVector`: 1 / 2 / 4 bytes) -- moves the elements as raw LITTLE-ENDIAN
IEEE-754 / two's-complement bytes in ONE native transfer, on every backend, and
the bytes are identical everywhere: what `write-sequence` writes from a `#f`
matrix on the JVM, `read-sequence` reads back into a `#f` matrix on wasm-GC.**
A rank-n float array is filled / drained in row-major order; `:start` / `:end`
count elements, `:end` nil (omitted) = the total size; a range outside the
buffer signals (interpreter / JVM) or traps (WASM); a trailing partial element at
EOF is neither stored nor counted. The general-array (`read-byte` loop) and
character-vector (`read-char`) contracts of the two operators are UNTOUCHED --
the packed path sits in front of them and declines everything it does not
handle.

Landed 2026-08-19 with the `examples/llama2/` port of llama2.c: a stories15M
checkpoint is 15 million float32s, and the pre-existing byte-at-a-time
`read-sequence` (one `read-byte` per byte -- one `fd_read` syscall per byte on
WASM) took ~2 min on the interpreter, ~75 s on wasm-GC, ~10 s on the JVM to
load it. The bulk path loads it in ~0.1-0.25 s on all four. Pinned by the
`read-sequence-and-write-sequence-over-packed-buffers-move-raw-little-endian-elements`
ci-spec case (all four backends) and the `readWriteSequencePackedBuffers*` unit
tests in `LispEvaluatorTest` / `JvmLispCompilerTest` /
`WasmLispCompilerIntegrationTest`.

## The seam: one expansion, one declining primitive per backend

`LispMacroExpander.expandReadSequence` / `expandWriteSequence` (shared by the
interpreter and both compilers) now bind the operands, then call
`(%read-sequence-packed seq stream start end)` /
`(%write-sequence-packed seq stream start end)` inside an `or`: the primitive
answers the fill position / the sequence, or **NIL = "declined"**, and the
`or` falls through to the old element loop (whose default `end` is now
resolved INSIDE the loop's own `let` -- `(length seq)` on a rank-2 packed array
would signal, and the primitive takes nil as "the total size"). The
declined-input protocol (the linalg-simd interception uses the same shape) is
what keeps this a pure fast path: a Gray-stream instance, a string stream, a
socket, a text stream, a general array -- each declines and behaves exactly as
before. Names in `LispNames.READ_SEQUENCE_PACKED` / `WRITE_SEQUENCE_PACKED`,
listed in `PackageRegistry.CL_INTERNALS` (a `%` internal owned by `cl`).

Per backend:

- **Interpreter** (`Environment`, beside `read-byte` / `write-byte`): the
  `PackedBuffer` record views the value as (width, size); an `InputStream` /
  `OutputStream` table entry or the standard-stream designator (the raw process
  `in` / `out`, `System.err` for the reserved handle 2) is handled, anything else
  declines. `InputStream.readNBytes((end-start)*width)` +
  `ByteBuffer.order(LITTLE_ENDIAN).asFloatBuffer().get(float[], start, n)`
  (and the double / int mirrors); the write mirror `put`s and `write(byte[])`s
  in one call, updating `atLineStart` off the last byte for standard output like
  `write-byte` does.
- **JVM** (`JvmIoRuntimeBuilder.buildSeqPacked`, `_readSeqPacked` /
  `_writeSeqPacked (Object,Object,Object,Object) -> Object`; called by
  `JvmSequencePackedCompiler`): the buffer is a bare `float[]` / `double[]` with
  the `[rank, dims..., data...]` header (data offset `1 + rank`) or the `long[]`
  with its width header (offset 1); the stream is an `InputStream` /
  `OutputStream` table entry or `System.in` / `System.out` for a non-handle. The
  helpers are minted ONLY for a program that has both a packed buffer AND a
  `read-sequence` / `write-sequence` (`usesPackedSequenceIo` in
  `JvmLispCompiler`, threaded into `Ctx`); otherwise the primitives compile to
  `aconst_null` -- declined -- so an artifact without them keeps its bytes. A
  stdout write sets `_col` off the last byte, as `_writeByte` does.
- **wasm-GC / `--component`** (`WasmPackedIoRuntimeBuilder`, `_read_packed` /
  `_write_packed`, fixed indices `FUNC_READ_PACKED` / `FUNC_WRITE_PACKED`
  appended after `FUNC_PATH_DIRFD` -- the last fixed helper, so no index above
  shifts -- signature `TYPE_CALLABLE_BASE + 3`; called by
  `WasmSequencePackedCompiler`): the buffer is a `TYPE_FARRAY` whose data is a
  `TYPE_F32ARR` / `TYPE_F64ARR` (or, under `--simd`, a `TYPE_VBLOCK` whose
  `count` / `kind` fields give size and width and whose lanes are written /
  read through `_v_set` / `_v_get` at `FUNC_VEC_BASE + V_SET/V_GET` -- the body
  is built per `simd` flag), or a bare `TYPE_I8ARR` / `TYPE_I16ARR` /
  `TYPE_I32ARR`; the stream is a non-negative i31 fd (a negative one is a string
  stream: declined) or the standard-stream designator (fd 0 in, fd 1 out).
  Elements are staged through a 64 KiB chunk (`CHUNK_BYTES`, a multiple of every
  width) reserved at `HEAP_PTR` for the call and popped after it (the `_open`
  discipline -- under `--component` the adapter may `cabi_realloc` at
  `HEAP_PTR` during the syscall); a short `fd_read` is refilled until the chunk
  is full or the fd is at EOF, a short `fd_write` is drained until every byte is
  out. A stdout write keeps `LINE_START` off the last byte, as `_write_byte`
  does. `--no-gc` has no streams, so nothing there.

## Why little-endian, why raw

The one contract that lets a Lisp buffer be a numpy / C / llama2.c file without a
per-element decode: x86 and AArch64 are little-endian, `float32` dumps and
`.npy` payloads are little-endian, and the checkpoint formats this exists for
are C structs `fwrite`n on such a machine. A big-endian file (the MNIST idx
headers, the RLW1 weight files of `examples/deep-learning-from-scratch/`) keeps
its `read-byte` decoder -- those are the exception, and they say so in their
loaders. There is no `:byte-order` knob; the day one is needed it belongs on the
primitive's signature, not in a second loop.

## Re-evaluation trigger

The primitive declines the standard input designator on no backend and sockets
on all three (a socket read of a float array goes through the loop). If a
consumer wants bulk socket reads, the interpreter arm is `Socket.getInputStream()`
and the JVM arm is the `sockets.socketGetInputStream()` branch `_readByte`
already has -- both fit the same declined-or-handled shape.
