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

## Bulk f16/bf16 bit widening (`.todo/671`), and why it is a scalar loop

`rontolisp:widen-float-bits` / `rontolisp:narrow-float-bits` (`eval/FloatBitsWidening`,
interpreter arm) are the load-time-conversion half of the packed-bulk-transfer story
above: an IEEE f16 or bf16 checkpoint is read as `(unsigned-byte 16)` bits
(`read-sequence` into a `LispIntVector`, this file's own bulk path) and then widened
into an existing `#f`/`#d` array, chunk by chunk, rather than held as its own array
type (`.todo/482`'s round-2 measurement: a fused f16 GEMV loses to f32 on both JITs).

**Recommended chunk size: 1 Mi elements (2^20).** The bits vector is a
`LispIntVector`, whose interpreter/JVM representation is a `long[]` regardless of the
declared width (8 bytes/element, not 2 -- `.kb/packed-integer-vectors.md`, "small-
buffer-oriented, not scale-oriented"), so staging a whole 1.1B-element checkpoint's
bits at once costs 8.8 GB, not the 2.2 GB a real `short[]` would -- on top of the 4.4 GB
`#f` destination, that is 13.2 GB, which breaks the "a 1B-class checkpoint fits an
8 GB laptop at f32" premise `.todo/670` states. A 1 Mi-element chunk costs 8 MB of
`long[]` staging instead; `widen-float-bits`'s `dst`/`:start` (`narrow-float-bits`'s
`dst`/`:start` the other way) exist specifically so a caller can call this once per
chunk into successive offsets of one already-allocated destination, never holding the
whole tensor's bits vector at once. `.todo/673`/`675` (the GGUF/safetensors readers)
MUST chunk this way -- this is a memory requirement, not a style preference.

**Deliberately a plain scalar loop, not `jdk.incubator.vector`, on the interpreter and
JVM arms** -- a deviation from `.todo/671`'s own text, which cites `Load.java`'s
16 Gelem/s bf16 number (`convertShape(S2I)` + shift) as the target. Two things changed
that number once actually implemented against `LispIntVector`:

- `Load.java`'s vectorized numbers are all measured over a real `short[]` source.
  `LispIntVector`'s actual backing is `long[]` (see above), which has no
  `ShortVector.fromArray`/`convertShape(S2I)` path at all -- the Vector-API route would
  have to `LongVector`-load, narrow to int, then decode, which is a different (and
  unmeasured) kernel shape, not "the same trick, just typed differently".
- Measured instead (2026-09-03, 1 Mi-element chunks, scalar loop) on **host `dorian`**
  (Intel Xeon E5-2697A v4, Broadwell, x86-64, 64 threads, AVX2 256-bit / f32x8 preferred
  species), both the default GraalVM JIT and C2 via `-XX:-UseJVMCICompiler`. `.todo/482`'s
  own Round 1/2 numbers, including the 16 Gelem/s this deviates from, were measured on
  a DIFFERENT machine (the GB10 box orchestrator B works on: aarch64 Cortex-X925, NEON
  128-bit / f32x4 preferred species) -- so the table below separates machine, JIT and
  representation on purpose; do not compare its numbers to `.todo/482`'s directly across
  that machine boundary without saying so:

  | conversion | source | Graal Gelem/s (dorian) | C2 Gelem/s (dorian) |
  | --- | --- | --- | --- |
  | f16 -> f32 (`Float.float16ToFloat`) | `long[]` (real) | 1.68 | 1.59 |
  | f16 -> f32 (`Float.float16ToFloat`) | `short[]` (reference) | 1.77 | 4.02 |
  | bf16 -> f32 (`<< 16` shift) | `long[]` (real) | 2.59 | 2.08 |
  | bf16 -> f32 (`<< 16` shift) | `short[]` (reference) | 4.33 | 2.82 |

  **This measurement does NOT separate "16 Gelem/s vs ~4 Gelem/s" into a machine
  difference and a representation (`long[]` vs `short[]`) difference** -- that needs
  `Load.java` itself re-run on `dorian` (it is a standalone
  `.todo/482-bfloat16-a-narrow-width-that-pays/Load.java` source-launcher file; a few
  minutes), which was not done here. What IS isolated, because both rows came off the
  same host: the `long[]`-vs-`short[]` gap above (1.3-1.7x for bf16, negligible for f16)
  is real and not a machine artifact. A rough split IS available from a different probe
  (`.todo/482-.../README.md` section 8, `Acc.java`'s f32 GEMV measured on both machines):
  `dorian` runs 2.6-2.9x slower than the GB10 box on cache-resident f32 GEMV, which
  puts the 16 Gelem/s figure at roughly 16/2.6..2.9 = 5.5-6.2 Gelem/s of "if it were run
  on dorian" headroom -- leaving the remaining 6ish -> 4.33 gap to the Vector-API-vs-
  scalar-loop difference this section is actually about. **This is an inference from a
  different kernel's cross-machine ratio, not a direct measurement of `Load.java` on
  `dorian`** -- treat it as a plausibility check, not a substitute for re-running it.

  At the worst measured rate, 1.1B elements: **0.66 s (f16, `long[]`, Graal) / 0.53 s
  (bf16, `long[]`, C2)**, on `dorian` -- both still comfortably under a second, so
  `.todo/670`'s "every conversion of a 1.1B-parameter checkpoint is under a second,
  single-threaded" conclusion HOLDS regardless of which machine it is read against.
  Given that, a `jdk.incubator.vector` implementation was not written: it would need its
  own from-scratch verification (a different kernel shape than anything already
  measured) to buy a headroom this primitive does not need. Re-open this if a future
  caller measures itself bandwidth-bound at this primitive specifically.
- Oddity worth a line on its own: **f16's C2-vs-Graal gap, which is large on `short[]`
  (C2 4.02 vs Graal 1.77 Gelem/s on `dorian` -- C2 auto-vectorizes the scalar
  `Float.float16ToFloat` loop into an x86 vector conversion, matching round-2's Section 1
  finding on ARM's `FCVTL`), disappears on `long[]`** (1.59 vs 1.68) -- the extra
  narrow-then-decode step defeats whatever pattern C2's superword optimizer was matching
  on the plain `short[]` loop. Whether the same reversal happens on ARM (where round-2's
  numbers live) is unknown -- this is `dorian`-only. `.todo/670`'s "every kernel number
  is JIT-dependent" should read **"JIT- AND MACHINE-dependent"** from here on; a
  reminder that a JIT's auto-vectorization of one loop shape says nothing about a
  differently-typed loop that computes the same function.

**Verified correct without a vectorized arm**: the scalar loop above matches
`java.lang.Float.floatToFloat16`/`float16ToFloat` bit-for-bit over all 65536 f16 bit
patterns (NaN payload aside for the decode direction) -- see the `float16-bits`/
`bits-float16` and `widen-float-bits`/`narrow-float-bits` tests. `.todo/671`'s Verify
item asking a *vectorized* arm to agree with a scalar oracle over all 65536 patterns
does not apply here since there is only the one (scalar) arm on the interpreter/JVM;
the WASM arm (necessarily scalar -- no incubator module there) is the same oracle
loop, so the cross-backend pin is scalar-vs-scalar-vs-scalar bit-for-bit agreement
instead.

## Re-evaluation trigger

The primitive declines the standard input designator on no backend and sockets
on all three (a socket read of a float array goes through the loop). If a
consumer wants bulk socket reads, the interpreter arm is `Socket.getInputStream()`
and the JVM arm is the `sockets.socketGetInputStream()` branch `_readByte`
already has -- both fit the same declined-or-handled shape.
