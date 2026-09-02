# The `--blas` thread note is silent on Accelerate and BLIS, and neither was measured

Difficulty: Low

todo-649 made `--blas` say one thing, once, when a program issues a loop of products too
small to pay for a threaded library's per-call barrier. It fires only when the library
will say how many threads it has: `openblas_get_num_threads`, `mkl_get_max_threads` /
`MKL_Get_Max_Threads`. Two tuned implementations the flag accepts export none of those,
so the note is silent for them **by construction rather than by measurement**:

- **Apple Accelerate**, which has no public thread-count call at all. 649 was measured on
  `dorian` (x86-64, OpenBLAS), which has no Accelerate, so nobody has checked whether an
  Apple machine has the same trap. The `--blas` GEMV table in `.kb/linalg-blas.md` says
  Accelerate is 6-9x the lane kernel at llama2's shapes, and that was measured back to
  back -- but **the first question about that column is what thread count produced it, not
  whether it is flattered.** The dorian columns beside it say "1 thread" and "64 threads";
  the Apple column says neither, and Accelerate picks a count by problem size on its own.
  A back-to-back loop flatters the LIBRARY side of the ratio only when that side has a pool
  to keep warm: single-threaded, the trap never reaches the column and 6-9x stands as
  measured; threaded, it is an over-estimate of unknown size. Measure the thread count
  first and the ratio second, and record BOTH in the table this time.
- **BLIS**, which exports `bli_thread_get_num_threads` -- a `dim_t` (int64) return, so it
  is one more downcall SHAPE (`jlong()`) and one more
  `reachability-metadata.json` entry, not just another row in the table. It was left out
  on those grounds and nothing else.

## What to do

1. On an Apple machine, run
   `.todo/649-a-blas-build-is-silently-5x-slower-when-the-library-is-threaded/ThreadBarrierProbe.java`
   (read its README: the numbers need a quiet machine) and `GemvProbe.java` beside it,
   then llama2 stories15M end to end under `--simd` and `--simd --blas`. If Accelerate
   pays the same barrier on short calls, `VECLIB_MAXIMUM_THREADS` is the variable to name
   and the note needs a way to fire without a thread query -- the honest one is probably
   "Accelerate, and this program is a loop of short products" with no count in it. If it
   does not, **that is the answer**: write the numbers into
   `.kb/linalg-blas.md`, "The two contracts", beside the x86 ones, and close this.
2. BLIS is a smaller question and can be done anywhere one is installed: add the symbol
   with a `jlong()` descriptor to `THREAD_QUERIES` in BOTH mirrors, register the shape,
   and confirm `LinalgBlasDeclineTest.everyDowncallShapeIsRegisteredForTheNativeImage`
   still passes. Only worth doing if a BLIS machine is at hand -- an unmeasured third
   entry is what this item exists to avoid.

## References

- `.kb/linalg-blas.md` -- "The two contracts", contract 2, has the whole decision, the
  cold-call table and the crossover the note is built on.
- `.todo/649-.../README.md` -- why a back-to-back probe cannot answer this.
