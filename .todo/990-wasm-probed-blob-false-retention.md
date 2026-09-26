# A probed-on-base data blob is pinned by an unrelated constant equal to its address

Difficulty: Medium

`WasmTreeShaker` keeps an `appendShakeableBlobProbedOnBase` blob when any live body holds an
`i32.const` equal to its base word (`.kb/error-handling.md`, "String accesses", cost). Measured
2026-09-26 on `size-report/programs/zlib`: 983 added 52 bytes of strings, which moved the
fdlibm reduction tables' base to 2048; chipz's code holds three `i32.const 2048` literals, so
~990 dead bytes (the tables plus padding) came back with no fdlibm function reachable. P1
117,008 -> 118,253 of which only ~250 B is 983's own code and strings.

Any string added before these blobs can pin or release them, so size deltas of unrelated
changes are noisy by up to ~1 KB. Goal: a citation the shaker can tell from a numeric literal
(e.g. the readers' base constant recorded as a relocation, or the blob reached only through
a reachable reader function), then re-measure the size report.
