# 692. `widen-float-bits` into a `--simd` wasm-GC destination traps with a cast failure

Difficulty: Medium

Found 2026-09-03 while adding the WASM legs of `examples/llama2/safetensors-check.lisp`
(`.todo/675`). The plain `wasm` and `wasm-component` outputs pass the whole fixture;
under `--simd` both trap. The smallest reproducer, compiled with `--simd` to either
output and run under wasmtime:

```lisp
(let ((dst (make-array 3 :element-type 'single-float :initial-element 0.0))
      (bits (make-array 3 :element-type '(unsigned-byte 16))))
  (setf (aref bits 0) 16256) (setf (aref bits 1) 49024) (setf (aref bits 2) 16384)
  (rontolisp:widen-float-bits bits :bfloat16 dst)   ; wasm trap: cast failure
  (format t "bf16: ~a~%" dst))
```

`read-sequence` into the same `dst` from a byte file works under `--simd` (the probe
prints `f32: #f(1.5 -2.25 0.125)` first), so the packed array itself is fine; it is the
widen's destination access. Under `--simd` a packed float array's data field is a
`TYPE_VBLOCK` over `(array (mut v128))` lane groups rather than a `$f32arr` /
`$f64arr` (`.kb/vec.md`, "Acceleration layer 3"), and `.todo/671`'s WASM arm
(`codegen/wasm/WasmFloat16Compiler`) casts the destination to the scalar array type.
Every other writer of a packed array under `--simd` -- `read-sequence`'s
`%read-sequence-packed`, `(setf aref)`, the `vec:` `-into` kernels -- goes through the
vblock layout; the widen (and, by construction, `narrow-float-bits`'s source read)
must do the same, or decline to the scalar element loop when the data field is a
vblock, the way the `linalg` kernels decline.

## Do

1. Make `_widen`/`_narrow` on the wasm-GC backend handle a vblock-backed array (write
   through the lane group + lane index, as the vec kernels do), on both the Preview 1
   and the component outputs.
2. Pin it: the reproducer above in `ci-spec.yaml` (which runs `--simd` on the WASM
   legs), and turn the `safetensors-check` `simd: true` entry's backends in
   `examples/examples.yaml` back to all four -- it lists `interpreter` and `jvm` until
   this lands, with a comment naming this item.
3. `.todo/675`'s last Remaining line before `.todo/485` goes with it.
