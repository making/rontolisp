# linalg --simd member extension: comparison masks + indexing members

Split out of todo-117 (deleted on completion 2026-07-13; its history lives in
git and the deep-learning-from-scratch-port memory). The todo-107/109
interception lineage's next tier.

## The case

After the todo-117 declined-shape follow-up (`2a12840`), the dominant
interpreter `--simd` cost of CNN inference is the UN-intercepted member
tier. Measured per call at CNN shapes:

- `(linalg:greater x 0)` (the relu mask): 137 ms at (10 30 24 24) =
  172,800 elements; ~1 s at the DeepConvNet's (100 16 28 28) = 1.25 M
  elements -- one per relu forward, eight relus per deep-convnet pass.
- `(linalg:take-rows x idx)`: 94 ms per batch at ch07 shapes.
- `(linalg:one-hot lab n)`: 32 ms.

`examples/deep-learning-from-scratch/ch08/misclassified-mnist.lisp` @1000
images runs 19 s on wasm-GC `--simd` but 1:24 on interpreter `--simd` --
most of the gap is this tier (bias-add/transpose/matmul/im2col are all
intercepted now; a per-op probe transcript is in the 2026-07-13 session).

## Candidate member set

- **Comparison masks** `greater` / `greater-equal` / `less` / `less-equal`
  / `equal` (the 0.0/1.0-mask family): lane compare + bitselect of
  0.0/1.0, the todo-109 Phase 3 maximum/minimum pattern; bit-identical at
  both widths (no reduction contract involvement). NOTE `array-equal`
  stays un-intercepted forever -- it legitimately returns nil, which
  collides with the null=declined sentinel.
- **Indexing members** `take-rows` / `gather` / `one-hot`: pure
  index-arithmetic copies/scatters -- scalar-loop kernels are fine (the
  `%la-im2col` precedent); the win is escaping the interpreter's
  per-element `row-major-aref` dispatch, not v128.

## Mechanics (the established pattern)

Per `.kb/linalg-simd.md`: partial kernels (null = declined -> scalar defun
over the same temps), three touch points -- `eval.LinalgSimd` +
`LinalgSimdKernels` (interpreter function binding),
`JvmLinalgSimdCompiler` -> `JvmSimdVectorTemplate` (call site), and
`WasmLinalgSimdCompiler` -> `WasmLinalgSimdRuntimeBuilder` (emitted
functions after the linalg block -- FUNC_COUNT grows, `userFuncBase()`
shifts; read bases via the accessors, never hardcode). Broadcast shapes of
the mask family should reuse the todo-117 BCAST walk. Interpreter natives
take arity ranges (`one-hot` has the optional element-type,
`take-rows`/`gather` are fixed). `eval` may not depend on `codegen.jvm`
(LinalgSimdKernels is a lane-for-lane mirror, keep them in lockstep).

Acceptance: the three ci-spec linalg cases stay byte-identical; unit tests
in all three suites; re-measure misclassified-mnist @1000 interpreter
--simd (expect ~1:24 -> well under 30 s).
