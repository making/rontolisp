# `linalg:erf` gets no `--simd` acceleration at all

Difficulty: Medium

Sibling of todo-467, from the same 2026-08-20 measurement session. Read
`.kb/linalg-simd.md` first.

## The case

`linalg:erf` is `(linalg:emap (function linalg::%la-erf-1) a)`, and `emap` is
**never** intercepted -- by design, since its callback is arbitrary Lisp. So erf
is the one member of the activation-primitive group (`relu`, `softmax`,
`log-softmax`, `erf`) that gets nothing from `--simd`: the other three are
either intercepted or composed entirely of intercepted members.

Interpreter, 40960 elements:

| | scalar | `--simd` |
| --- | --- | --- |
| `linalg:erf` | 3693 ms | 3530 ms |
| `linalg:tanh` | 142 ms | 3 ms |
| `linalg:exp` | 59 ms | 2 ms |

Roughly 1200x the cost of `exp` under `--simd`, for the same shape.

It matters because `torch:gelu`'s default (`:approximate :none`, matching
`nn.GELU`) is built on it -- every transformer feed-forward block. The `:tanh`
form is mul/add/tanh only and is already fully accelerated, so today the exact
GELU is the slow one, which is backwards.

Honest sizing: at the shapes `examples/llm-from-scratch/` actually tests, this is
NOT the bottleneck -- swapping the exact GELU for `:approximate :tanh` moves the
interpreter leg 1m42 -> 1m38 and the JVM leg not at all. todo-467 is where that
time goes. Do this one for correctness of the acceleration story and because it
scales with `n_embd`, not because it will move the example.

## What to intercept

`linalg:erf`, arity 1, as a 35th member. The kernel is a per-element loop
running `%la-erf-1`'s own arithmetic; it must be **bit-identical to the defun**,
which it is if it keeps the same order of operations:

- `|x| >= 6.0` -> `+-1.0` (no series),
- otherwise the A&S 7.1.6 all-positive-term series, `term = term * 2x^2 / (2n+1)`,
  break when `term < 1e-17 * total`, capped at n = 200,
- then `1.1283791670955126 * |x| * exp(-x^2) * total`, negated for `x < 0`.

Same-width `#d` / `#f` packed input only; anything else declines (null sentinel)
and runs the defun. Note the defun computes in double and narrows on store for
`#f`, which is `emap`'s rule -- the kernel must do the same, not accumulate in
single.

Whether the series is worth vectorizing at all is an open call: the per-element
iteration count is data-dependent (it grows with `x^2`), so a lane loop has to
run every lane to the max of its group's counts. A scalar de-boxed loop already
buys most of the win on the interpreter -- the `%la-im2col` precedent, where the
win is escaping the tree-walk and the boxing, not v128. Measure the scalar
kernel first and only add lanes if the numbers justify them.

wasm needs one new emitted function, but no new software builtin: `%la-erf-1`
uses only multiply, divide, compare and `exp`, and the software scalar `exp`
already exists from the todo-109 Phase 2 work.

## Mechanics

The three touch points and the argument-evaluated-once rule are todo-467's; this
member is simpler (fixed arity 1, no batch odometer, no broadcast).

## Acceptance

- Bit-identical to the scalar defun at both widths, on all three `--simd`
  backends, over a range that includes the `|x| >= 6` cutoff, `x = 0`, negatives,
  and the region around `|x| ~ 3` where the alternating series would have failed.
- `TorchGradcheck`'s `erf` / `gelu` rows and the ci-spec `torch-gpt-cross-backend`
  case stay byte-identical with and without `--simd`.

## Follow-ups that belong with this landing

Three places now state this gap outright, so landing the fix means DELETING
them:

- `doc/{en,ja}/guides/linear-algebra.md`: the caveat sentence at the end of the
  ufunc paragraph ("`erf` is an `emap` over a scalar series and is the one name
  here the flag does not accelerate at all").
- `doc/{en,ja}/guides/simd-acceleration.md`, "Accelerating linalg": the
  `linalg:erf` half of the "Two shapes fall outside the accelerated set today"
  paragraph (its batched-matmul half belongs to todo-467).
- `doc/{en,ja}/reference/functions/torch-gelu.md`: the "Accuracy is not the only
  axis" paragraph, which tells a reader to pick `:approximate :tanh` for speed.
