# Batched (rank >= 3) matmul is outside the `--simd` intercepted set

Difficulty: High

The todo-107/109/117/121 interception lineage's next tier, surfaced by the
`examples/llm-from-scratch/` GPT port (2026-08-20). Read `.kb/linalg-simd.md`
first; todo-121 is the sibling member-extension item.

## The case

`linalg:matmul` routes rank <= 2 to `linalg:dot` -- intercepted -- and rank >= 3
to `linalg::%la-matmul-nd`, a hand-written boxed `outer x M x K x N` walk that
nothing intercepts and that is built from no intercepted member. That is
`torch.bmm` / `torch.matmul`, which means **every** attention score, every
attention-weighted value, and every `torch:linear` over a `(B T C)` activation.
For a transformer it is essentially the whole forward and backward pass.

Measured, 64x64 operands, 4 batches, same total FLOPs on both rows:

| | scalar | `--simd` |
| --- | --- | --- |
| interpreter, rank 2 | 33.2 s | 46 ms (720x) |
| interpreter, rank 3 | 66.9 s | 71.2 s (**no effect**) |
| wasm-GC, rank 2 | 1880 ms | 69 ms (27x) |
| wasm-GC, rank 3 | 1758 ms | 1960 ms (**11% slower**) |

The wasm row is not noise: it is exactly the regression `.kb/linalg-simd.md`
opens with ("`--simd` used to make `linalg:` SLOWER on wasm-GC"). Under `--simd`
a packed array is a `TYPE_VBLOCK` and every scalar element access pays
`_v_get` / `_v_set`. Intercepting a member is what buys that cost back, and
`%la-matmul-nd` still pays it and gets nothing.

End to end, `examples/llm-from-scratch/chapter03/train-gpt-soseki.lisp`:

| backend | scalar | `--simd` |
| --- | --- | --- |
| interpreter | 2m46 | 1m42 (1.6x) |
| JVM | 5.5 s | 3.6 s (1.5x) |
| wasm-GC | 4.5 s | 3.4 s (1.3x) |

Against `examples/llama2/` at interpreter 11.3 s -> 20 ms, that is the wrong
order of magnitude, and the difference is this member. Swapping the exact GELU
for `:approximate :tanh` (i.e. removing the todo-468 cost entirely) moves the
interpreter leg only 1m42 -> 1m38, so matmul is where the time is.

Not an llm-from-scratch problem: `examples/ml/tiny-llm.lisp`, the chapter 2
Transformer and every future `torch:`-based program go through the same call.

## What to intercept

`linalg::%la-matmul-nd`, arity 2, as the THIRD internal (`%`-prefixed) member
after `%la-im2col` / `%la-col2im` -- that precedent already settles the
double-colon qualified spelling (`linalg::%la-matmul-nd`) that the interpreter
function binding, `ctx.functions` keys and the emit-gate symbol scan must
compose; `Jvm/WasmLinalgSimdCompiler.qualifiedName` and `LinalgSimd.define`
already branch on the `%` prefix.

Intercepting `%la-matmul-nd` rather than `linalg:matmul` keeps the rank <= 2
dispatch, the scalar rejection and the inner-dimension error message in the
library, where they are today.

Kernel shape: for each of the `%la-batch-shape` batches, an `ikj` lane loop over
the `M x K x N` slab -- the same lane loop `dot`'s M.M case already runs, called
once per batch offset. The batch offsets are the existing
`%la-batch-strides` odometer; a broadcast leading axis has stride 0 and needs no
special case. Reuse `dot`'s M.M kernel rather than writing a second one.

Declines (fall through to the scalar defun, null sentinel): a general boxed
operand, mixed widths, a rank-1 operand on either side (the numpy
promote-then-drop-the-axis rule -- cheap to add later, but it is not the hot
shape and keeping it in the defun keeps the kernel one shape), non-broadcastable
batch shapes, mismatched inner dimensions.

Precision: the per-output-element accumulation order must match `dot`'s M.M
kernel, which already differs from the scalar defun's plain `k` loop the same
way -- so state the contract as "identical to a per-batch `linalg:dot`", not
"identical to the defun". `#f` reductions accumulate in single precision on
every `--simd` backend, per the existing `.kb` contract.

## Mechanics

The established three touch points (`.kb/linalg-simd.md`): `eval.LinalgSimd` +
`eval.LinalgSimdKernels` (interpreter function binding),
`codegen.jvm.JvmLinalgSimdCompiler` -> `JvmSimdVectorTemplate` (call site), and
`codegen.wasm.WasmLinalgSimdCompiler` -> `WasmLinalgSimdRuntimeBuilder`
(FUNC_COUNT grows, `userFuncBase()` shifts -- read bases through the accessors,
never hardcode). Arity is fixed at 2, so `compiler.LinalgKernelCallLayout` needs
no new option handling. `eval` may not depend on `codegen.jvm`:
`LinalgSimdKernels` stays a lane-for-lane mirror of the template, kept in
lockstep.

Both compilers must still evaluate each argument form exactly once into a temp,
since the decline branch re-reads it.

## Acceptance

- Unit tests in all three suites, mirroring the existing
  `wasmGcSimdLinalgDeclinedInputsRunTheScalarDefun` /
  `anArgumentFormIsEvaluatedExactlyOnceEvenWhenTheKernelDeclines` pattern for the
  new member.
- The ci-spec linalg and `torch-gpt-cross-backend` cases stay byte-identical,
  with and without `--simd`, on all four backends.
- `examples/llm-from-scratch/` and `examples/ml/tiny-llm.lisp` produce
  byte-identical output with and without `--simd` (they do today -- keep it).
- Re-measure `train-gpt-soseki.lisp` on all three `--simd` backends and the
  rank-3 microbenchmark above; the wasm-GC rank-3 row must stop being slower
  than scalar.

## Follow-ups that belong with this landing

- `examples/llm-from-scratch/README.md` has no `--simd` section (the
  `deep-learning-from-scratch` and `llama2` READMEs do). Write it once the
  numbers are worth quoting, with the measured table.
- `doc/{en,ja}/guides/neural-networks.md` says a torch program "is accelerated
  under `--simd` for free". True only once this lands; until then it oversells
  the transformer case.
