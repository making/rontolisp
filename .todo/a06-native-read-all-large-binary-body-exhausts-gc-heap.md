# `--native` `read-all` of a 256 MiB binary body exhausts the GC heap

Difficulty: Medium

Found 2026-09-26 while measuring fetch throughput (`.kb/fetch-http.md`, "Throughput"): on a
`--native` output, `read-all` of a 256 MiB BINARY response body fails after ~65 s with the GC heap
exhausted. A 64 MiB text body reads in 0.67 s. The interpreter reads 256 MiB in 4.8 s wall.

First measure where it goes: the binary path's intermediate representation (per-chunk vectors
concatenated repeatedly? a list of bytes?) versus the text path's, and the wasmtime GC heap limit
the runner sets. Fix the representation if it is quadratic or boxed; otherwise record the limit
and the numbers in `.kb/fetch-http.md`. Reproduce with `FetchSpecE2eTest`'s local server.
