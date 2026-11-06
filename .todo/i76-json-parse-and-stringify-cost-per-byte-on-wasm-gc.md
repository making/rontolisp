# i76. `rontolisp:json-parse` and `json-stringify` on wasm-GC: per-byte time and garbage

Difficulty: High

Reported 2026-11-06 by the sluice session, measured there with the `0.1.0-SNAPSHOT` release (58f221883) on
Endive 1.2.0's interpreter, load about 5 on 64 cores. Not yet re-measured here.

- Plugin (read only, sluice f408d6e): `/nvme1n1-disk/sluice/sluice-client/src/test/guest/rontolisp/json_plugin.lisp`.
  At end of stream, `on-response-body` does `(rontolisp:json-parse (proxy-wasm:get-buffer :response-body))`, adds a
  `"sluice"` hash table, and `(proxy-wasm:set-buffer :response-body (rontolisp:json-stringify document))`.
  Built `--no-wasi --optimize=size`: 72,615 bytes (`lisp_plugin` without JSON: 5,348).
- Bench: `JsonBench.java` under `/tmp/claude-1000/-nvme1n1-disk-sluice/3dfe6d1d-ce33-4497-8db9-91c119351091/scratchpad/json-bench/`
  (`cp.txt` holds the classpath, plus `sluice-client/target/{test-classes,classes}`; `-Dmem=<MiB>`,
  `-Dtimeout=<s>` lift the limits; parse-only and stringify-only variants in `var/`). Body
  `{"items":[{"id":0,"name":"item-0"},...]}`, `application/json`.
- At sluice's defaults (`max-memory` 16 MiB, `call-timeout` 1 s): 1/2/4/8 KiB work (best 66/167/280/393 ms per
  response); 16 KiB fails on memory (16,803,832 bytes held, all allocation within one call counts); 64 KiB to
  1 MiB fail on the timeout; 4 MiB 38,033,408 bytes.
- Limits lifted: linear, about 55 ms and about 1 KB of allocation per KiB of JSON (8 KiB 0.66 s, 64 KiB 3.4 s,
  256 KiB 14.1 s). At 64 KiB parse alone takes 1.7 s and stringify alone 2.2 s: both halves are slow.
  `http_body` with `search-bytes` over 64 KiB takes about 20 ms on the same harness.
- Suspects the reporter named in `src/main/resources/am/ik/rontolisp/eval/json.lisp`, unmeasured: a `subseq` or
  `%princ-piece` string per character, the pairwise `%json-concat` passes, `%json-out-string` escaping one
  character at a time.

Goal: a 1 MiB JSON body parsed, changed and stringified within sluice's defaults (16 MiB, 1 s), so per-byte time
and per-byte garbage both drop by well over an order of magnitude on wasm-GC. Profile before choosing; fix what
the backend lowers badly in general where that is the cause, not only json.lisp. Keep the other backends' output
identical. If the API changes, say so in the report: sluice follows it. Do not edit sluice's checkout; clone it to
the scratchpad to build or run its tests.
