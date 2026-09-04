# Bulk binary I/O: `read-sequence` / `write-sequence` over a packed buffer

**Invariant: `(read-sequence buf stream)` / `(write-sequence buf stream)` with a PACKED
buffer -- a packed float array of ANY rank (`LispFloatArray`: single-float 4 bytes,
double-float 8) or a packed `(unsigned-byte 8|16|32)` vector (`LispIntVector`: 1/2/4
bytes) -- moves the elements as raw LITTLE-ENDIAN IEEE-754 / two's-complement bytes in
ONE native transfer on every backend, with identical bytes everywhere: what
`write-sequence` writes from a `#f` matrix on the JVM, `read-sequence` reads back into a
`#f` matrix on wasm-GC.** Rank-n arrays fill/drain in row-major order; `:start`/`:end`
count elements, `:end` nil = total size; an out-of-buffer range signals (interpreter /
JVM) or traps (WASM); a trailing partial element at EOF is neither stored nor counted.
The general-array (`read-byte` loop) and character-vector (`read-char`) contracts are
UNTOUCHED -- the packed path sits in front and declines what it does not handle.

Pinned by the ci-spec case
`read-sequence-and-write-sequence-over-packed-buffers-move-raw-little-endian-elements`
(all four backends) and the `readWriteSequencePackedBuffers*` tests in
`LispEvaluatorTest` / `JvmLispCompilerTest` / `WasmLispCompilerIntegrationTest`.

## The seam: one expansion, one declining primitive per backend
`LispMacroExpander.expandReadSequence` / `expandWriteSequence` (shared by interpreter and
both compilers) bind the operands, then call `(%read-sequence-packed seq stream start
end)` / `(%write-sequence-packed ...)` inside an `or`: the primitive answers the fill
position / the sequence, or **NIL = declined**, and the `or` falls through to the old
element loop (whose default `end` is resolved INSIDE the loop's own `let` -- `(length
seq)` on a rank-2 packed array would signal, and the primitive takes nil as "total
size"). Gray-stream instances, string streams, sockets, text streams and general arrays
all decline and behave as before. Names: `LispNames.READ_SEQUENCE_PACKED` /
`WRITE_SEQUENCE_PACKED`, listed in `PackageRegistry.CL_INTERNALS`.

- **Interpreter** (`Environment`, beside `read-byte`/`write-byte`): the `PackedBuffer`
  record views the value as (width, size); an `InputStream`/`OutputStream` table entry or
  the standard-stream designator (raw process `in`/`out`, `System.err` for reserved
  handle 2) is handled, anything else declines.
  `InputStream.readNBytes((end-start)*width)` +
  `ByteBuffer.order(LITTLE_ENDIAN).asFloatBuffer().get(float[], start, n)` (double/int
  mirrors likewise); the write mirror `put`s and `write(byte[])`s in one call, updating
  `atLineStart` off the last byte for standard output.
- **JVM** (`JvmIoRuntimeBuilder.buildSeqPacked`, `_readSeqPacked` / `_writeSeqPacked
  (Object,Object,Object,Object) -> Object`, called by `JvmSequencePackedCompiler`):
  buffer is a bare `float[]`/`double[]` with the `[rank, dims..., data...]` header (data
  offset `1 + rank`) or a `long[]` with its width header (offset 1); stream is an
  `InputStream`/`OutputStream` table entry or `System.in`/`System.out` for a non-handle.
  Helpers are minted ONLY for a program with both a packed buffer AND a
  `read-sequence`/`write-sequence` (`usesPackedSequenceIo` in `JvmLispCompiler`, threaded
  into `Ctx`); otherwise the primitives compile to `aconst_null` (declined), so other
  artifacts keep their bytes. A stdout write sets `_col` off the last byte.
- **wasm-GC / `--component`** (`WasmPackedIoRuntimeBuilder`, `_read_packed` /
  `_write_packed`, fixed indices `FUNC_READ_PACKED`/`FUNC_WRITE_PACKED` appended after
  `FUNC_PATH_DIRFD` -- the last fixed helper, so no index shifts -- signature
  `TYPE_CALLABLE_BASE + 3`; called by `WasmSequencePackedCompiler`): buffer is a
  `TYPE_FARRAY` over `TYPE_F32ARR`/`TYPE_F64ARR` (or, under `--simd`, a `TYPE_VBLOCK`
  whose `count`/`kind` give size and width and whose lanes go through `_v_set`/`_v_get`
  at `FUNC_VEC_BASE + V_SET/V_GET`; body built per `simd` flag), or a bare
  `TYPE_I8ARR`/`TYPE_I16ARR`/`TYPE_I32ARR`; stream is a non-negative i31 fd (negative =
  string stream, declined) or the standard-stream designator (fd 0 in, fd 1 out).
  Elements stage through a 64 KiB chunk (`CHUNK_BYTES`, a multiple of every width)
  reserved at `HEAP_PTR` for the call and popped after (the `_open` discipline -- under
  `--component` the adapter may `cabi_realloc` at `HEAP_PTR` during the syscall); a short
  `fd_read` is refilled until the chunk is full or EOF, a short `fd_write` drained until
  every byte is out. A stdout write keeps `LINE_START` off the last byte. `--no-gc` has
  no streams.

## Why little-endian, why raw
Lets a Lisp buffer be a numpy / C / llama2.c file with no per-element decode: x86 and
AArch64 are little-endian, `float32` dumps and `.npy` payloads are little-endian. A
big-endian file (the MNIST idx headers, the RLW1 weight files of
`examples/deep-learning-from-scratch/`) keeps its `read-byte` decoder and says so in its
loader. There is no `:byte-order` knob; if one is needed it belongs on the primitive's
signature, not in a second loop.

## Bulk f16/bf16 bit widening
`rontolisp:widen-float-bits` / `rontolisp:narrow-float-bits` (`eval/FloatBitsWidening`,
interpreter arm) are the load-time-conversion half: an IEEE f16 or bf16 checkpoint is
read as `(unsigned-byte 16)` bits (`read-sequence` into a `LispIntVector`, this file's
bulk path) and then widened into an existing `#f`/`#d` array chunk by chunk, rather than
held as its own array type (a fused f16 GEMV loses to f32 on both JITs).

**Recommended chunk size: 1 Mi elements (2^20) -- a memory requirement, not style.**
`LispIntVector`'s interpreter/JVM backing is `long[]` regardless of declared width
(8 bytes/element, not 2 -- `.kb/packed-integer-vectors.md`), so staging a whole
1.1B-element checkpoint's bits at once costs 8.8 GB, plus the 4.4 GB `#f` destination =
13.2 GB, breaking the "a 1B-class checkpoint fits an 8 GB laptop at f32" premise. A 1 Mi
chunk costs 8 MB of staging; `widen-float-bits`'s `dst`/`:start` (and
`narrow-float-bits`'s, the other way) exist so a caller writes successive offsets of one
already-allocated destination. The GGUF/safetensors readers MUST chunk this way.

**Deliberately a plain scalar loop, not `jdk.incubator.vector`, on the interpreter and
JVM arms.** `LispIntVector`'s `long[]` backing has no
`ShortVector.fromArray`/`convertShape(S2I)` path at all -- a Vector-API route would have
to `LongVector`-load, narrow to int, then decode: a different, unmeasured kernel shape.
Measured scalar rates (1 Mi chunks, `long[]` source): f16->f32 1.6-1.7 Gelem/s,
bf16->f32 2.1-2.6 Gelem/s, i.e. ~0.5-0.7 s for 1.1B elements, so "every conversion of a
1.1B-parameter checkpoint is under a second, single-threaded" holds. Re-open only if a
caller measures itself bandwidth-bound at this primitive. Two facts from that run: the
`long[]`-vs-`short[]` representation gap is real (1.3-1.7x for bf16, negligible for
f16); and f16's large C2-vs-Graal gap on `short[]` (C2 auto-vectorizes the scalar
`Float.float16ToFloat` loop) DISAPPEARS on `long[]`, the narrow-then-decode step
defeating C2's superword pattern. Kernel numbers are JIT- AND MACHINE-dependent.

**Verified correct without a vectorized arm**: the scalar loop matches
`java.lang.Float.floatToFloat16`/`float16ToFloat` bit-for-bit over all 65536 f16 bit
patterns (NaN payload aside for decode) -- the `float16-bits`/`bits-float16` and
`widen-float-bits`/`narrow-float-bits` tests. The WASM arm is necessarily scalar (no
incubator module), so the cross-backend pin is scalar-vs-scalar bit-for-bit agreement.

## Re-evaluation trigger
The primitive declines sockets on all three backends (a socket read of a float array
goes through the loop). For bulk socket reads: the interpreter arm is
`Socket.getInputStream()` and the JVM arm is the `sockets.socketGetInputStream()` branch
`_readByte` already has -- both fit the same declined-or-handled shape.
