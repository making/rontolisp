# Checkpoint readers: the `checkpoint` staging package and `safetensors:read`

**Invariant: a published checkpoint's tensors land in PACKED float arrays, staged in bounded chunks, through a file walked front to back — same arrays on every backend, with and without `--simd`.**

- `checkpoint` (`eval/checkpoint.lisp`, `eval.CheckpointLibrary`): `make-tensor`, `stage-float-bits`, `stage-float32`, `skip-bytes`. `safetensors` (`safetensors.lisp`, `eval.SafetensorsLibrary`): `read`, `header`, `entries`. Lisp-source libraries in the `geom` shape (`.kb/geom.md`), prunable by `LibraryDefunPruner`, in `PackageRegistry` as `CHECKPOINT_FUNCTIONS` / `SAFETENSORS_FUNCTIONS` (no `cl` use, all external — `safetensors:read` is the package's own `READ`) and in `resource-config.json`.
- **Splice order in `CompileFrontend`: `SafetensorsLibrary.process`, then `CheckpointLibrary.process`, both BEFORE `JsonLibrary` and the prelude** (which supply `rontolisp:json-parse` / `%octets-to-string`).
- `file-position` answers nil on every backend, so a reader WALKS front to back in tensor-offset order and passes unwanted tensors with `checkpoint:skip-bytes` (64 KB scratch); a sharded checkpoint walks each needed shard once. When seeking lands, the skip can become a seek.
- A packed `(unsigned-byte 16)` vector costs 8 bytes/element on interpreter and JVM (`.kb/binary-sequence-io.md`), so `checkpoint:stage-float-bits` takes the STREAM, not a staged vector, and widens 1M-element chunks with `widen-float-bits ... :start`. Trap: the last chunk needs a buffer of its own size, `widen-float-bits` widening the whole vector handed to it.
- Trap: `make-array :element-type` does not signal on an unknown element type — it silently answers a boxed general array. `checkpoint:make-tensor` is the ONE allocation path and asserts `(array-element-type a)` matches.

## safetensors
`u64` LE header length; that many bytes of JSON (`"<name>": {"dtype", "shape", "data_offsets": [begin, end]}` plus `__metadata__`); then tensor bytes at those offsets from the end of the header, row-major.
- `safetensors:header` -> parsed JSON (string-keyed hash table, `.kb/json.md`) + data start; `safetensors:entries` -> `(name dtype shape begin end)` sorted by `begin`.
- `safetensors:read` answers rank-1 for a 1-D shape, rank-N otherwise (a `[c, 1, k]` conv weight stays rank 3; the caller squeezes). F32 by one `read-sequence`, F16/BF16 through `stage-float-bits`, anything else an error naming tensor and dtype (`safetensors: ids is I64; supported dtypes: F32, F16, BF16`). `:only` is a name predicate. A directory argument probes `model.safetensors.index.json` then `model.safetensors`.
- Per-family name mapping into a model plist is the consumer's (`examples/llama2/llama2.lisp`'s `load-hf-checkpoint`); the reader is per FORMAT.

## Tests
`examples/llama2/safetensors-check.lisp` over `safetensors-check.safetensors` (built by `safetensors-fixture.py`), expected text `examples/.expected/safetensors-check.txt`, entry in `examples.yaml` — interpreter leg only until the JVM/WASM arms of `widen-float-bits` land.
