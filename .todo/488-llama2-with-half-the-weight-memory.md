# 488. llama2 with half the weight memory

Difficulty: Medium

The payoff item of `.todo/482`. Depends on `.todo/484`, `.todo/485`, `.todo/487`.

`examples/llama2/llama2.lisp` holds every weight in a packed `single-float` array --
`stories15M.bin` is 60.8 MB on disk and the same in the heap, plus a KV cache of
`layers x kv-heads x (seq-len x head-size)` f32 for keys and the transposed same for
values. At `short-float` both halve. That is what decides whether a larger checkpoint
runs at all, which is the point of the width; the token rate is a separate axis and is
addressed below honestly.

## Do

1. A `short-float` load path (a `:element-type` parameter, not a new flag spelling): `read-sequence` a f16 checkpoint
   straight into `short-float` arrays through `.todo/487`'s bulk read. Support reading a
   f32 checkpoint and narrowing at load too, so no new file format is required to try it.
2. The KV cache at `short-float`. It is written every step and read by the attention
   GEMV, so it is the case where the widen-once policy is least favourable -- measure it
   separately from the weights and be prepared to keep the cache at f32 while the weights
   go to f16. Report both.
3. Report resident weight bytes and tok/s side by side for f32 and f16, single-thread and
   `--parallel`, on `stories15M`.

## What to expect, and what not to promise

`.todo/482`'s spike measured the GEMV directly: **f16 compute is 0.31x-0.67x of f32**, and
that is with the fastest exact decode available on this JDK. So per `.todo/482` the
kernels widen once into an f32 scratch rather than decoding per element -- which for a
weight matrix read once per token means the widening cost lands on **every** token, and
the honest expectation is that **tok/s goes down** while memory halves.

Do not ship this as a speed feature. The result to aim for and to report is: half the
weight bytes, output still coherent (the spike's f16 GEMV lands within 0.02% of the f64
reference on N(0, 0.02) weights, and llama2 weights are that scale), and a stated tok/s
cost. If the measured cost is small enough that a model twice the size becomes runnable
on the same box, the item has succeeded.

The variant worth measuring before concluding: widen each weight matrix to an f32 scratch
**once at load** and keep both -- that is strictly worse for memory and pointless. The
useful one is widening per *use* only for the matrices that are small enough to stay in
cache, and leaving the large ones f16 end to end. Measure the crossover; it is the same
shape as the `--gpu` matvec crossover in `.kb/gpu.md`.

## Verify

- `ExamplesE2eTest` with `-Drontolisp.examples.only=llama2` on every backend llama2
  declares -- and the f16 path only on the backends `.todo/486` lets carry it.
- Generated text from the f16 run is coherent English; it will not be token-identical to
  the f32 run and must not be asserted to be.
- The `README.md` numbers for `examples/llama2` gain the f16 row, both memory and tok/s.
