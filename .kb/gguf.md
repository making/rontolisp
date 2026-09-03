# The `gguf` package (reading a downloaded checkpoint)

One hand-written Lisp-source library,
`src/main/resources/am/ik/rontolisp/eval/gguf.lisp`, following the `geom.lisp` /
`linalg.lisp` pattern (`.kb/geom.md`) so a single implementation runs identically on
every backend: the header, the key/value block, the tensor directory and the tensor
bodies of a GGUF -- the single file a downloaded small language model most often IS,
carrying the hyperparameters, the tokenizer and the weights together in the width
the publisher chose. Eight exported functions; the record they pass around is the
internal `gguf::%file` defstruct.

It reaches for nothing but `cl` -- no `linalg:`, no `objc:`, no `java:` -- and it
DOES open a file, which is what it is for. That is the same exception `geom`'s five
model readers are, and it is ANSI CL I/O that runs on all four backends.

## The format (v3), all little-endian

```
"GGUF" | u32 version | u64 tensor count | u64 KV count
KV pairs:      string key, u32 value type, value
tensor infos:  string name, u32 n_dims, u64 dims[n_dims], u32 type, u64 offset
padding to general.alignment (default 32)
tensor data, each tensor at its own offset from the data start
```

Thirteen value types (u8, i8, u16, i16, u32, i32, f32, bool, string, array, u64,
i64, f64); a string is a `u64` length plus UTF-8 bytes; an array is a `u32` element
type, a `u64` count and the elements.

**ggml stores dims FASTEST-VARYING FIRST**, so an info reading `[cols, rows]` is a
`rows x cols` matrix. `gguf:tensor-info` reverses them once, on the way in, so
everything downstream sees row-major dimensions -- and the bytes on disk are then
exactly row-major, which is what lets `read-sequence` fill a packed float array in
one transfer (`.kb/binary-sequence-io.md`).

## No seeking, and that is a MEASUREMENT

`.todo/673` planned to `file-position` to each tensor's offset. **`file-position`
repositions nothing.** Measured 2026-09-03 on the interpreter, the JVM and wasm
preview 1 with a 16-byte file: after reading four bytes, `(file-position s)` answers
`NIL`, `(file-position s 8)` answers `NIL`, and the next four bytes read are `4 5 6
7` rather than `8 9 10 11`. `file-length` is real. The registration in
`Environment` says so in as many words -- "Lite: streams do not support
repositioning" -- and the only stream with a real position is the buffered
served-request body. `.todo/390` is the open item; this reader is its second real
consumer after `uiop:parse-windows-shortcut`.

So the data is walked SEQUENTIALLY in ascending offset order, skipping the alignment
padding and any tensor not asked for (chunked through a 64 KB scratch buffer, so
skipping half a gigabyte allocates nothing). Three consequences:

- **The tokenizer and the hyperparameters are free**: the KV block is at the FRONT
  of the file, so `(gguf:read path :metadata-only t)` stops before the data and never
  touches the gigabytes. This is the case `.todo/674`'s tokenizer needs, and the case
  a quantized checkpoint has to support.
- **`:only` saves memory and conversion, not I/O**: what it skips is still read past.
- **A file whose tensor offsets are not ascending is refused by name** rather than
  read wrongly. No writer emits one; the check is a guard, not a rearrangement.

The same absence is why the reader counts its own byte position, and why the
`ci-spec` case that writes a GGUF counts the position of what it writes.

## What loads, and what is refused

| ggml type | this reader |
| --- | --- |
| F32 (0) | straight into a packed float array -- one transfer, no conversion |
| F16 (1), BF16 (30) | a `(unsigned-byte 16)` staging vector + `rontolisp:widen-float-bits` |
| Q8_0 (8) | refused by name; it needs a quantized weight matrix that does not exist yet |
| everything else | refused by name and type, pointing at the publisher's BF16 / F16 file |

**A refusal fires when a tensor's BODY is asked for, never earlier.** The header, the
key/value block and the whole directory of a file full of Q4_K read fine, so a
quantized checkpoint can be inspected, its shapes read and its tokenizer taken --
which is what the Q8_0 work needs to start from, and what `:only` lets a caller work
around today.

The block table (`gguf::%type-block`) is only needed to report `:bytes`; the walk
skips to the next tensor's DECLARED offset rather than past this one's size, so a
type whose block shape this reader does not know still skips correctly.

**The staging is the `checkpoint` package's** (`.kb/checkpoint-readers.md`), shared
with the safetensors reader rather than written twice: `checkpoint:make-tensor`
allocates and CHECKS the destination, `stage-float32` / `stage-float-bits` fill it
and `skip-bytes` passes over what `:only` excluded. What is left in `gguf.lisp` is
the FORMAT -- the header, the thirteen value types, the directory and the walk.

The first cut of this reader staged a whole tensor's bit patterns in one vector and
this file said that cost "2 transient bytes per element". **That was wrong, and the
`checkpoint` package is what measured it**: a packed `(unsigned-byte 16)` vector is
a `LispIntVector`, stored as a `long[]`, so it costs EIGHT bytes an element and a
tensor staged whole costs four times its size on disk. The chunked loop (1M elements
through one reused buffer, `widen-float-bits`' `:start` placing each chunk) is not an
optimisation over the simple version, it is the difference between loading a 1.1B
model and not.

`rontolisp:widen-float-bits` has all four arms as of 2026-09-03, so F16 and BF16
bodies load everywhere; the `ci-spec` case reads one of each on all four backends.

## Pins, and what the fixtures are

- `GgufLibraryTest` -- the interpreter half, against
  `src/test/resources/gguf/synthetic.gguf`, **written by llama.cpp's own `gguf`
  Python writer**. That is the point of it: the fixture is the reference
  implementation's statement of the format, so agreeing with it is a statement about
  GGUF rather than about self-consistency. It carries all thirteen value types,
  arrays of five, an alignment of 64, tensors of rank 1/2/3, one per loadable width,
  a Q8_0 and a Q4_K to refuse, and the tokenizer fields. Regenerating it needs the
  Python `gguf` package; nothing in the build does.
- `ci-spec.yaml`'s `gguf-cross-backend` -- the same shapes on all four backends over
  a checkpoint the case WRITES itself, so it is one self-contained program: F32, F16
  and BF16 bodies, `:metadata-only`, `:only`, `:element-type` and both refusals.

Verified by hand against real checkpoints, 2026-09-03, agreeing with the official
`gguf` reader field for field, offset for offset and value for value:

| file | what it exercised |
| --- | --- |
| `ggml-org/models` `tinyllamas/stories15M-q4_0.gguf` (19 MB) | 57 tensors, the `llama` architecture keys, F32 norms to the last digit, Q4_0 / Q8_0 refused by name, and a 32000-piece SentencePiece vocabulary that drove `tokenizer:make-sentencepiece` to `(9038 2501 263 931)` for "Once upon a time" |
| `bartowski/SmolLM2-135M-Instruct-GGUF` f16 (271 MB) | 272 tensors, `tokenizer.ggml.pre` = `"smollm"`, F16 bodies, and 49152 tokens + 48900 merges that drove `tokenizer:make-bpe` to ids **identical to the Python `tokenizers` library's** |

That second row is the end-to-end claim: a checkpoint downloaded from Hugging Face,
read by this package, tokenized by `tokenizer:`, produces the reference
implementation's ids.

## The cost of the key/value block

A vocabulary in the metadata is a quarter of a million string reads. Measured on
SmolLM2-135M (49152 tokens + 48900 merges), 2026-09-03:

| | interpreter | JVM class |
| --- | --- | --- |
| `:metadata-only t` | 9.5 s | 446 ms |
| plus two tensors | 10.0 s | 1.0 s |

The scratch buffers live on the reader record for this reason (a fresh one-element
vector per primitive read is not free). A model run should compile; the interpreter
number is the one to quote when someone asks why the REPL takes ten seconds to open
a checkpoint.
