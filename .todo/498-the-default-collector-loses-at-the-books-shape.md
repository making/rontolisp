# The default collector loses at the book's shape and wins at the notebook's

Difficulty: Medium

Filed 2026-08-23 night, from the measurements that closed `.todo/492`. Under `--gpu --simd`
(JVM class output, GB10):

| | default (G1) | `-XX:+UseParallelGC` |
|---|---|---|
| `train-gpt-soseki`, notebook width, 200 steps | **5.8 s** | 9.3 s (`-Xmn4g`) |
| the same at the book's shapes, 103 steps | 131 s | **101 s** (`-Xmn8g`) |

Both rows are the same build and the same flag; the README records them as they are.
With result stubs the heap's live set is ~170 MB at either shape and almost nothing is
allocated per step, so the difference is not collection work in the usual sense. Two
hypotheses, neither tested:

- At the notebook's width, a 4 GB young generation that never fills is 4 GB of pages the
  device has never touched, and every upload from a fresh host array pays the cold-page
  cost `.kb/gpu.md` measured (todo-474, `FreshPageCost.java`); G1's region recycling
  keeps the arrays the program does allocate on warm pages.
- At the book's shapes, the residency asks for `System.gc()` about every 1.7 steps
  (2888 over 5000 steps; 45 ms each under ParallelGC). G1 answers `System.gc()` with a
  FULL collection of a 64 GB heap unless `-XX:+ExplicitGCInvokesConcurrent`; if that is
  the 30 s, the library's collection request is what to make cheaper -- a smaller heap,
  the concurrent flag set by the README, or a request the library can make without a
  full collection at all.

Get the two `-Xlog:gc` logs side by side and settle it; then either the README says one
thing for both shapes, or `DeviceResidency`'s collection policy changes.
