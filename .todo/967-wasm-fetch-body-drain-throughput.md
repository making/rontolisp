# A fetched body drains at ~25 MB/s through a wasm module

Difficulty: Medium

Measured 2026-09-25 (x86_64, loopback origin): a 256 MiB body drained with `rontolisp:stream-read`
took 10.3-11.4 s user in a `--native` output, against 4.8 s wall on the interpreter and 2.9 s on
the JVM; `curl` fetched the same HTTPS body in 0.5 s. `perf` put nearly every sample in the
module's JIT-compiled code, not in the runner, i.e. ~40 ns per octet somewhere between the
`:bytes` import result (the runner writes into linear memory at the pointer the wrapper passes)
and the octet vector the stream answers (`%http-reactor-chunk`'s `subseq`, then the drain). The
`--host-fetch` reactor's body takes the same path.

## Goal

Find the loop that costs the time, make the copy a bulk one (or a tight word-at-a-time loop:
wasm-GC has no linear-memory -> array bulk instruction), and record the new throughput in
`.kb/fetch-http.md`, "--native" ("Throughput").
