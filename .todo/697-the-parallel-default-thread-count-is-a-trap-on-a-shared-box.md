# 697. `--parallel`'s default thread count is a trap on a shared box: one busy core costs 10x

Difficulty: Medium

Found 2026-09-05 measuring `.todo/489`'s f32 rungs on dorian (Xeon E5-2697A v4, 64
threads, GraalVM 25.0.4, develop `2275c000`), JVM class output of `examples/llm`,
`--simd --parallel`, greedy, 64 tokens:

| model | threads | tok/s | load average before the run | what else ran |
| --- | --- | --- | --- | --- |
| Qwen3-0.6B | 64 (the default) | **0.62** | 0.94 | another lane's maven build, ~6 cores |
| Qwen3-0.6B | 32 (`RONTOLISP_THREADS=32`) | **9.88** | 15.0 | the same build |
| Qwen3-0.6B | 16 | 9.50 | 15.3 | the same build |
| Qwen3-0.6B | 1 (`--simd` alone) | 2.56 | 0.92 | idle |
| Qwen3.5-0.8B | 64 | **0.83** | 5.1 | the same build (8.56 on 2026-09-03, README) |
| stories15M | 64 / 32 / 16 / 8 / 4 | 139 / 220 / 232 / 227 / 203 | 18 | the same build |

**Sixteen times slower than half the threads, and four times slower than ONE thread.**
The mechanism is the pool's own design (`.kb/simd-parallel.md`): the workers spin on an
epoch, the rows of one GEMV are claimed in leaves, and the caller spins until the LAST
leaf is done. With `threads == availableProcessors` and anything else runnable on the
box, some worker is descheduled in the middle of its leaf on nearly every call, and the
whole GEMV waits a scheduler quantum for it. A 0.6B token is ~200 GEMVs; 1.6 s per token
is ~8 ms per GEMV, which is the quantum, not the arithmetic. The `Thread.yield()` every 64
spins (which the kb says "matters as much as the spin") helps the spinners give way; it
does nothing for a worker that is holding a leaf when it is preempted.

The user guide already says "size the count to what the machine's job allows"
(`doc/en/guides/simd-acceleration.md`, `--parallel`), which is true and not a defence:
the default is what runs when nobody read that line, and it is the one setting that
collapses. Every `--parallel` number in `examples/llm/README.md` was taken with the
default, and the 2026-09-03 rows (8.56 / 6.97 tok/s) happened to be taken in a quieter
minute than the 2026-09-05 ones.

## Do

1. **Pin the pathology before changing anything**: a `SimdParallelTest`-shaped
   measurement, not a unit test -- one GEMV loop under the default count with one busy
   thread pinned beside it, reported as a ratio against `threads / 2`. It is the number
   that has to move.
2. Decide the default. Candidates, to be measured against each other on both boxes:
   - `availableProcessors - k` for a small k (leaves the OS, the JIT and the GC a core;
     the GB10 numbers in the README already found `RONTOLISP_THREADS=10` better than the
     20 default, for a different reason -- the small cores).
   - Half the processors on an SMT box (`dorian`'s 64 are 32 cores x 2; the GEMV is
     bandwidth-bound, so the second hyperthread buys nothing and costs a spinner).
   - Work-stealing that lets the caller finish a preempted worker's leaf instead of
     waiting for it -- the real fix, and the one with the most surface.
3. Whatever lands, `RONTOLISP_THREADS` keeps overriding it, and the README rows in
   `examples/llm` are re-measured at the new default with the load average beside
   them.

Two lanes measuring `--parallel` on one box at the same time produce two garbage numbers
(each is the other's "busy core"); `.todo/670`'s rule 5 (never two device-touching runs at
once) extends to this flag.
