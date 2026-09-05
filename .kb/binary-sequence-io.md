# Bulk binary I/O: `read-sequence` / `write-sequence` over a packed buffer

**Invariant: `(read-sequence buf stream)` / `(write-sequence buf stream)` with a PACKED buffer --
a packed float array of any rank (`LispFloatArray`: 4/8 bytes) or a packed
`(unsigned-byte 8|16|32)` vector (`LispIntVector`: 1/2/4) -- moves the elements as raw
LITTLE-ENDIAN IEEE-754 / two's-complement bytes in ONE native transfer on every backend, with
identical bytes everywhere.** Rank-n fills row-major; `:start`/`:end` count elements, `:end` nil =
total size; an out-of-buffer range signals or traps; a trailing partial element at EOF is neither
stored nor counted. The general-array and character-vector contracts are UNTOUCHED.

Little-endian and raw so a Lisp buffer can be a numpy / C / llama2.c file with no per-element
decode. No `:byte-order` knob; a big-endian file (MNIST idx) keeps its `read-byte` decoder.

## The seam: one expansion, one declining primitive per backend
- `LispMacroExpander.expandReadSequence` / `expandWriteSequence` (shared by all three) call
  `(%read-sequence-packed seq stream start end)` / `%write-sequence-packed` inside an `or`: the
  primitive answers the fill position / the sequence, or **NIL = declined**, and the `or` falls
  through to the old element loop. That loop resolves its default `end` INSIDE its own `let` --
  `(length seq)` on a rank-2 packed array would signal. Gray streams, string streams, sockets,
  text streams and general arrays decline. `LispNames.READ_SEQUENCE_PACKED` /
  `WRITE_SEQUENCE_PACKED`, in `PackageRegistry.CL_INTERNALS`.
- **Interpreter** (`Environment`, beside `read-byte`): the `PackedBuffer` record views the value
  as (width, size); an `InputStream`/`OutputStream` table entry or a standard-stream designator is
  handled. `readNBytes` + `ByteBuffer.order(LITTLE_ENDIAN)`; a stdout write updates `atLineStart`.
- **JVM** `JvmIoRuntimeBuilder.buildSeqPacked` -> `_readSeqPacked` / `_writeSeqPacked`, called by
  `JvmSequencePackedCompiler`. Buffer is a `float[]`/`double[]` with the `[rank, dims..., data...]`
  header (data offset `1 + rank`) or a `long[]` with its width header (offset 1). Helpers are
  minted only when the program has both a packed buffer and a `read-sequence`/`write-sequence`
  (`usesPackedSequenceIo` in `JvmLispCompiler`, threaded into `Ctx`); otherwise the primitives
  compile to `aconst_null`.
- **wasm-GC / `--component`** `WasmPackedIoRuntimeBuilder` -> `_read_packed` / `_write_packed`,
  indices `FUNC_READ_PACKED`/`FUNC_WRITE_PACKED` appended after `FUNC_PATH_DIRFD` (no index
  shifts), signature `TYPE_CALLABLE_BASE + 3`, called by `WasmSequencePackedCompiler`. Buffer is a
  `TYPE_FARRAY` (under `--simd` a `TYPE_VBLOCK` through `_v_set`/`_v_get`) or a bare
  `TYPE_I8ARR`/`TYPE_I16ARR`/`TYPE_I32ARR`; stream is a non-negative i31 fd. Elements stage through
  a 64 KiB `CHUNK_BYTES` reserved at `HEAP_PTR` and popped after (the `_open` discipline -- the
  component adapter may `cabi_realloc` there); short `fd_read`/`fd_write` are refilled/drained to
  completion. `--no-gc` has no streams.
- Sockets decline on all three backends; bulk socket reads would fit the same
  declined-or-handled shape (`Socket.getInputStream()`, `sockets.socketGetInputStream()`).

## Bulk f16/bf16 bit widening
- `rontolisp:widen-float-bits` / `narrow-float-bits` (`eval/FloatBitsWidening`): an f16/bf16
  checkpoint is read as `(unsigned-byte 16)` bits through the bulk path above, then widened into
  an existing `#f`/`#d` array chunk by chunk, not held as its own array type.
- **Chunk size 1 Mi elements (2^20) -- a memory requirement, not style.** `LispIntVector` backs
  with `long[]` regardless of declared width (`.kb/packed-integer-vectors.md`), so a whole
  1.1B-element checkpoint's bits cost 8.8 GB of staging + 4.4 GB destination. `dst`/`:start` exist
  so a caller writes successive offsets of one destination. GGUF/safetensors readers MUST chunk.
- **Deliberately a plain scalar loop, not `jdk.incubator.vector`**: `long[]` backing has no
  `ShortVector.fromArray`/`convertShape(S2I)` path. Measured 1.6-2.6 Gelem/s; re-open only if a
  caller measures itself bandwidth-bound here. Correctness pinned against
  `Float.floatToFloat16`/`float16ToFloat` over all 65536 f16 bit patterns.

## Tests
ci-spec `read-sequence-and-write-sequence-over-packed-buffers-move-raw-little-endian-elements`;
`readWriteSequencePackedBuffers*` in `LispEvaluatorTest` / `JvmLispCompilerTest` /
`WasmLispCompilerIntegrationTest`; `float16-bits`/`widen-float-bits` tests.
