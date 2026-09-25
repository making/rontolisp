Raw runs behind `.kb/native-output.md`, Traps, "musl" (aarch64), 2026-09-25, NVIDIA GB10.

`bench.sh ROUNDS CPU VARIANT...` runs `b/<prog>.<variant>` (bench-report programs and
`precomp/tests/fixtures/gc.lisp`, each packed by `rlpack` with one stub) interleaved,
pinned to CPU (5 = Cortex-X925, `armv8_pmuv3_1`; 0 = Cortex-A725, `armv8_pmuv3_0`, the
PMU name edited to match). Columns: program, variant, round, user cycles, user
instructions, user seconds. Variants: `musl` (musl's routines), `own` (`memfns.rs`),
`glibc` (`aarch64-unknown-linux-gnu`, `+crt-static`, `relocation-model=static`).
`summarize.py FILE` prints min cycles per variant against glibc.
