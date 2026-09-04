# The `gguf` package (reading a downloaded checkpoint)

`src/main/resources/am/ik/rontolisp/eval/gguf.lisp`, on the `geom.lisp` / `linalg.lisp` pattern
(`.kb/geom.md`) so one implementation runs identically on all four backends. Eight exported
functions; the record passed around is the internal `gguf::%file` defstruct. Uses only `cl` -- no
`linalg:`, `objc:`, `java:` -- and it does open a file (ANSI CL I/O, the same exception `geom`'s
model readers are).

## Format (v3), little-endian

```
"GGUF" | u32 version | u64 tensor count | u64 KV count
KV pairs:      string key, u32 value type, value
tensor infos:  string name, u32 n_dims, u64 dims[n_dims], u32 type, u64 offset
padding to general.alignment (default 32)
tensor data, each tensor at its own offset from the data start
```

Thirteen value types (u8, i8, u16, i16, u32, i32, f32, bool, string, array, u64, i64, f64); a
string is a `u64` length plus UTF-8 bytes; an array is a `u32` element type, a `u64` count and the
elements.

**ggml stores dims FASTEST-VARYING FIRST**: an info reading `[cols, rows]` is a `rows x cols`
matrix. `gguf:tensor-info` reverses them once on the way in, so downstream is row-major and the
on-disk bytes are then exactly row-major -- `read-sequence` fills a packed float array in one
transfer (`.kb/binary-sequence-io.md`).

## No seeking

**`file-position` repositions nothing** (interpreter, JVM, wasm preview 1): it answers `NIL` and
the next read continues where it left off. `file-length` is real. The `Environment` registration
says so ("Lite: streams do not support repositioning"); the only stream with a real position is
the buffered served-request body. Open item: `.todo/390`.

Data is therefore walked SEQUENTIALLY in ascending offset order, skipping alignment padding and
unwanted tensors through a 64 KB scratch buffer (skipping allocates nothing). Consequences:

- `(gguf:read path :metadata-only t)` is free: the KV block is at the FRONT of the file.
- `:only` saves memory and conversion, not I/O -- what it skips is still read past.
- Non-ascending tensor offsets are refused by name rather than read wrongly (a guard; no writer
  emits one).
- The reader counts its own byte position, and the ci-spec case that writes a GGUF counts the
  position of what it writes.

## What loads, what is refused

| ggml type | this reader |
| --- | --- |
| F32 (0) | straight into a packed float array -- one transfer, no conversion |
| F16 (1), BF16 (30) | an `(unsigned-byte 16)` staging vector + `rontolisp:widen-float-bits` |
| Q8_0 (8) | refused by name; needs a quantized weight matrix that does not exist yet |
| everything else | refused by name and type, pointing at the publisher's BF16 / F16 file |

- **A refusal fires when a tensor's BODY is asked for, never earlier**, so an all-Q4_K checkpoint
  can still be inspected, its shapes read and its tokenizer taken. `rontolisp:widen-float-bits`
  has all four arms.
- `gguf::%type-block` is only needed to report `:bytes`; the walk skips to the next tensor's
  DECLARED offset rather than past this one's size, so an unknown block shape still skips
  correctly.
- Staging is the `checkpoint` package's (`.kb/checkpoint-readers.md`), shared with the
  safetensors reader: `checkpoint:make-tensor` allocates and CHECKS the destination,
  `stage-float32` / `stage-float-bits` fill it, `skip-bytes` passes over what `:only` excluded.
  `gguf.lisp` keeps only the FORMAT.
- **Trap: a packed `(unsigned-byte 16)` staging vector is a `LispIntVector` stored as a `long[]`
  -- eight bytes an element, four times the tensor's on-disk size.** Stage in chunks through one
  reused buffer (`widen-float-bits`' `:start` placing each chunk); staging a tensor whole is the
  difference between loading a 1.1B model and not.
- A vocabulary in the metadata is a quarter of a million string reads and is far slower
  interpreted than compiled; the scratch buffers live on the reader record for this reason.

## Tests and fixtures

- `GgufLibraryTest` (interpreter) against `src/test/resources/gguf/synthetic.gguf`, **written by
  llama.cpp's own `gguf` Python writer** so agreement is a statement about GGUF, not about
  self-consistency: all thirteen value types, arrays of five, alignment 64, tensors of rank
  1/2/3, one per loadable width, a Q8_0 and a Q4_K to refuse, the tokenizer fields. Regenerating
  needs the Python `gguf` package; nothing in the build does.
- ci-spec `gguf-cross-backend` -- all four backends over a checkpoint the case WRITES itself:
  F32/F16/BF16 bodies, `:metadata-only`, `:only`, `:element-type`, both refusals.
- Hand-verified against `tinyllamas/stories15M-q4_0.gguf` and
  `bartowski/SmolLM2-135M-Instruct-GGUF` f16, field for field and offset for offset against the
  official `gguf` reader, with `tokenizer:make-bpe` ids identical to the Python `tokenizers`
  library's.
