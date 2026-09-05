# The `gguf` package (reading a downloaded checkpoint)

`src/main/resources/am/ik/rontolisp/eval/gguf.lisp`, on the `geom.lisp` / `linalg.lisp` pattern
(`.kb/geom.md`) so one implementation runs on all four backends. Eight exported functions; the
record is the internal `gguf::%file` defstruct. Uses only `cl`, but it does open a file.

## Format (v3), little-endian

```
"GGUF" | u32 version | u64 tensor count | u64 KV count
KV pairs:     string key, u32 value type, value
tensor infos: string name, u32 n_dims, u64 dims[n_dims], u32 type, u64 offset
padding to general.alignment (default 32), then tensor data at per-tensor offsets
```

Thirteen value types (u8, i8, u16, i16, u32, i32, f32, bool, string, array, u64, i64, f64); a
string is a u64 length plus UTF-8 bytes, an array a u32 element type, u64 count and elements.

**ggml stores dims FASTEST-VARYING FIRST**: `gguf:tensor-info` reverses them once, so downstream
is row-major and `read-sequence` fills a packed float array in one transfer
(`.kb/binary-sequence-io.md`).

## No seeking

**`file-position` repositions nothing** on any backend: it answers `NIL` and the next read
continues where it left off. `file-length` is real. Open item: `.todo/390`. Data is therefore
walked SEQUENTIALLY in ascending offset order through a 64 KB scratch buffer.

- `:metadata-only` is free (the KV block is at the FRONT); `:only` saves memory, not I/O.
- Non-ascending tensor offsets are refused by name rather than read wrongly.

## What loads, what is refused

F32 goes straight into a packed float array; F16/BF16 through an `(unsigned-byte 16)` staging
vector + `rontolisp:widen-float-bits`; Q8_0 and everything else are refused BY NAME, pointing at
the publisher's BF16/F16 file.

- **A refusal fires when a tensor's BODY is asked for, never earlier**, so an all-Q4_K checkpoint
  can still be inspected and its tokenizer taken.
- `gguf::%type-block` is only needed to report `:bytes`; the walk skips to the next tensor's
  DECLARED offset, so an unknown block shape still skips correctly.
- Staging is the `checkpoint` package's (`.kb/checkpoint-readers.md`): `checkpoint:make-tensor`,
  `stage-float32`, `stage-float-bits`, `skip-bytes`. `gguf.lisp` keeps only the FORMAT.
- **Trap: a packed `(unsigned-byte 16)` staging vector is a `LispIntVector` over `long[]` -- four
  times the tensor's on-disk size.** Stage in chunks through one reused buffer
  (`widen-float-bits`' `:start`); it is the difference between loading a 1.1B model and not.

## Tests and fixtures

- `GgufLibraryTest` against `src/test/resources/gguf/synthetic.gguf`, **written by llama.cpp's own
  `gguf` Python writer**: all thirteen value types, alignment 64, ranks 1/2/3, one tensor per
  loadable width, a Q8_0 and a Q4_K to refuse. Regenerating needs the Python `gguf` package;
  nothing in the build does.
- ci-spec `gguf-cross-backend` -- all four backends over a checkpoint the case WRITES itself.
- Hand-verified against `tinyllamas/stories15M-q4_0.gguf` and
  `bartowski/SmolLM2-135M-Instruct-GGUF` f16, offset for offset against the official reader.
