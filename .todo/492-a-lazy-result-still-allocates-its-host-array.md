# A lazy device result still allocates (and zeroes) its host array

Difficulty: High

Filed 2026-08-23 when `.todo/491` closed. Read `.kb/gpu.md` "A result comes home on first
host touch" first: since that round a device member's result stays on the device until the
host reads it, and the members whose operands are resident run as launches with no copy.
What is left of a training step at the book's shapes is the THIRD line of 491's profile,
now the first: every result still allocates a fresh, zeroed Java array on the host -- a
6 MB activation at the notebook's shapes, 25-100 MB at the book's (batch 64, T 256,
C 384, the MLP's 1536) -- whether or not anything ever reads it, and the collector pays
for each of them. (On Metal, where the same mode was built and measured in `.todo/494`,
this doubling is one of the three reasons it does not pay: a 58-60 GB pool of slabs beside
a 64 GB heap on a 128 GB machine puts the system under memory pressure, and the device's
reads slow with it. Halving the footprint is what this item would do for that backend too.)

## What it costs

- `train-gpt-soseki` at the book's shapes, `--gpu --simd`, JVM class output,
  `-Xmx64g -XX:+UseParallelGC -Xmn8g`: 9.9 s a step before 491, **6.3 s** after it. The
  arithmetic is ~0.2 s; the copies are gone (the 200-step notebook-shaped run moves 2.3 GB
  down against 44 GB); what remains is dominated by `new float[...]` of 25-100 MB per
  result and the pauses (before 491, 20 s of a 145 s 13-step run were collector pauses).
- At the notebook's shapes the same cost is why `-XX:+UseParallelGC -Xmn4g` takes the
  steady-state step from 0.038 to 0.024 s.

## The representation question

The JVM backend's packed array IS a bare `float[]` / `double[]` with the
`[rank, dim..., data...]` header inside it, so there is nowhere to put "no host storage
yet": a value that is a packed array must be that array. The interpreter's record
(`LispSingleFloatArray(float[] data, int[] dims)`) could hold a null `data` behind its
`storage()` / `data()` accessors and allocate on first `data()` -- but the interpreter is
not where the flag pays (26 s a step against 0.06 compiled), and a design that works on
one backend only is not this project's.

Options to weigh, none tried:
- a SENTINEL host array per shape that the bridge answers for a lazy result, with the
  real array allocated by `_gpuMaterialize` -- impossible as long as identity is the
  residency key and the value flows through the program as the array itself;
- a header-only array (`[rank, dim...]`, no data) as the lazy value, with every host
  reader (the enumerated seam) swapping it for the materialized array -- but the array
  has already been captured by the program's variables, so the swap cannot reach them;
- allocating WITHOUT zeroing (`Unsafe.allocateUninitializedArray`) for a lazy result,
  which removes the memset but not the allocation or the collector's share; measure it
  first, it may be most of the cost at 100 MB;
- reusing host arrays: a pool keyed by length, handed out for lazy results and returned
  when the weak residency key reports the array unreachable -- a collected array cannot
  be reused, so this needs the bridge to own the arrays' lifetime, which it does not.

## Acceptance

The book's-shape step measured again (`(t13 - t3) / 10`, the method and the JVM flags of
`examples/llm-from-scratch/README.md`), with the collector's share of it reported, and
the notebook-shaped steady state under the default collector closer to the ParallelGC
figure than to today's.
