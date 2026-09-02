# A `--blas` build is silently 5.4x SLOWER when the library sizes its own thread pool

Difficulty: Medium

Measured 2026-09-02 while landing todo-471 (`vec:matvec` on `cblas_?gemv`). The numbers
and the reasoning behind the current posture are in `.kb/linalg-blas.md`, "The GEMV, and
what the hardware decides" and "The two contracts" -- read both first. This item does NOT
propose reversing that posture; it proposes closing the half of it that documentation
cannot reach.

## The case

`dorian` (Xeon E5-2697A v4, 64 cores, stock Debian OpenBLAS), llama2 stories15M on the JVM
backend, 150 tokens, greedy:

| build | tok/s |
|---|---|
| `--simd` | 101.8, 110.1 |
| `--simd --blas`, `OPENBLAS_NUM_THREADS=1` | 123.7, 120.8 |
| `--simd --blas`, 64 threads (the default) | **16.0** |

So a user who does exactly what `doc/{en,ja}/guides/blas-acceleration.md` tells them to do
-- install OpenBLAS, pass `--blas` -- gets a **5.4x REGRESSION** on the flagship example,
from a flag whose entire purpose is acceleration, with no diagnostic of any kind. A gemv is
short and memory-bound, so the library's per-call barrier swamps the call; a decode loop is
thousands of those.

The same trap is milder but present on the interpreter (`simd-gemv`: 131 ms at one thread,
371 ms at 64) and absent on the native binary at that shape (a tie), so it is not uniform
enough for a user to notice a pattern.

## Why the current answer is not enough

`.kb/linalg-blas.md` decides that **rontolisp does not set the thread count**, because "a
library's thread pool is the user's to size, and overriding it from inside would be a worse
surprise than the one it fixes". That reasoning is sound and this item does not dispute it.

But it answers only "should we OVERRIDE it", and the gap is "should we SAY something". The
docs leading with the variable is a fix for the user who reads the page before running;
it is not a fix for the user who reads it afterwards, wondering why the acceleration flag
made their program four times slower. Nothing in the binary mentions threads, and the
failure looks like the feature simply not working.

## What to do

Decide between these, in `.kb/linalg-blas.md`, with the measurement rather than by taste:

1. **Warn at flag time.** When `--blas` resolves a tuned library, ask it how many threads
   it will use (`openblas_get_num_threads`, `mkl_get_max_threads`; Accelerate has no such
   call and does not need one -- it is not the platform with the trap) and write one line
   to stderr when the answer is > 1, naming the variable. One downcall, once per process,
   on a path that has already opened the library.
2. **A flag that says the number.** `--blas-threads=N` setting the library's count for the
   process through `openblas_set_num_threads` / `mkl_set_num_threads`, defaulting to
   "leave it alone". This is an explicit user request rather than an override, so it does
   not contradict the posture above, and it makes the winning configuration reachable
   without the user knowing which env var their library reads.
3. **Both** -- the warning names the flag.

Whatever is chosen, `openblas_get_num_threads` and friends are optional symbols: a library
that lacks them must degrade to today's silence, not to a hard error, and the tuned-library
identification in "Identifying a TUNED library is the load-bearing part" is the place that
already knows how to look a symbol up and miss.

## Acceptance

- On a machine whose CBLAS reports more than one thread, a `--blas` run says so once, on
  stderr, and names what to do about it; a capped run says nothing.
- The message does not fire on Accelerate, and does not fire when the library exports no
  thread query.
- Nothing is written to stdout, so no example output and no `ci-spec.yaml` case moves.
- `.kb/linalg-blas.md`'s "Threads" contract records the decision and the reason, and the
  `doc/{en,ja}/guides/blas-acceleration.md` thread section points at whatever the binary
  now does rather than carrying the whole burden alone.

## References

- `.kb/linalg-blas.md` -- the binding, the tuned-library identification, both contracts.
- `.todo/471-vec-matvec-is-outside-the-blas-intercepted-set/` -- `GemvProbe.java` and its
  README, the re-runnable probe that produced the table above.
