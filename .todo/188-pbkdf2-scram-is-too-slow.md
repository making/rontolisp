# PBKDF2 (SCRAM-SHA-256 authentication) is far too slow

`cl-postgres` authenticates with SCRAM-SHA-256 by running
`ironclad:pbkdf2-hash-password` for the server's iteration count (PostgreSQL's
default is 4096). That single call is what makes a SCRAM connection cost minutes
on the interpreter and tens of seconds on the compiled backends -- so slow that
the interpreter cannot finish inside PostgreSQL's default 60-second
`authentication_timeout` and the E2E has to raise it.

Everything below was measured 2026-07-27 on darwin/arm64 with the exec jar and
wasmtime 46.0.1, warm caches, against the same 4096-iteration 32-byte
PBKDF2-HMAC-SHA256 derivation.

## Finding 1 -- the raw cost

`ironclad:pbkdf2-hash-password`, alone in a program that quickloads only
ironclad:

| backend | 4096 iterations |
| --- | --- |
| interpreter | 130 s |
| JVM | 1.8 s |
| WASM component | 6.3 s |
| WASM Preview 1 | 5.4 s |

4096 iterations = 8192 HMAC-SHA256 = roughly 16k SHA-256 block compressions, so
the interpreter is spending ~8 ms per 64-byte block: the 32-bit rotate / xor /
add loop of SHA-256, interpreted, with every intermediate boxed.

## Finding 2 -- a hot loop is taxed for what ELSE is loaded (compile paths only)

The identical call, in a program that ALSO quickloads other libraries but does
not otherwise use them:

| program (JVM) | 4096 iterations | vs. ironclad alone |
| --- | --- | --- |
| ironclad only | 1.8 s | 1.0x |
| ironclad + alexandria | 1.9 s | 1.1x |
| ironclad + cl-ppcre | 8.9 s | 4.9x |
| ironclad + uax-15 | 10.6 s | 5.9x |
| the full cl-postgres stack | 14.0 s | 7.8x |

The WASM component shows the same shape (6.3 s -> 24.4 s, 3.9x). The interpreter
barely moves (130 s -> 154 s, 1.2x), which is the clue: this is a COMPILE-PATH
effect, not a library-code effect.

Two controls narrow it:

- A tight arithmetic loop (`logand`/`+`/`*`, 3M iterations, no CLOS) is NOT
  taxed: 0.15 s alone, 0.21 s with the whole cl-postgres stack loaded. So this
  is not "bigger artifact, slower everything".
- alexandria -- many defuns and macros, essentially no classes, and largely
  tree-shaken away -- costs nothing, while the class-defining libraries cost 5-8x.

Prime suspect, therefore: the compile paths' RUNTIME type dispatch, whose size
is proportional to the registered-layout count (`%typep-runtime`'s table scan,
`.kb/clos.md`; 165 layouts at cl-postgres scale), reached from ironclad's
generic `update-digest` / `digest-sequence` path once per block. Confirm before
fixing -- an unprofiled guess here is exactly how a day gets spent on the wrong
loop.

## Why it matters beyond SCRAM

Finding 2 is the bigger one. It says any hot loop that goes through generic
dispatch gets several times slower merely because the program also loads a
library with classes in it -- an invisible, superlinear tax on exactly the
"quickload a real library and use it" workflow the ASDF work exists to enable.
SCRAM is just the first workload big enough to expose it.

## Work

1. **Profile**, do not assume. Attribute the 12 extra JVM seconds to a call site
   (async-profiler on the emitted class, or a counter around `%typep-runtime`).
   Answer: how many times is it called per PBKDF2 iteration, and what does one
   call cost at 165 layouts?
2. **Make the runtime dispatch bounded** if step 1 confirms it: the table scan
   matching each arm by canonical AND member name should become a lookup keyed
   by the value's tag, so its cost stops tracking the registered-layout count.
   Both compile backends, same mechanism.
3. **The interpreter's raw arithmetic** (finding 1) is a separate axis: 8 ms per
   SHA-256 block is boxing plus generic-arithmetic dispatch on `logand`/`logxor`
   /`ash`/`+` over `(unsigned-byte 32)` values. Cheapest honest win is a fixnum
   fast path in the evaluator's arithmetic; the alternative -- intercepting
   ironclad's SHA-256 with a native primitive, the way `--simd` intercepts
   linalg call shapes (`.kb/linalg-simd-interception.md`) -- is faster to build
   but would be the FIRST performance-motivated shim (every existing one,
   `ShimLibraries` included, exists for portability). Decide that deliberately;
   it trades away part of "the real library runs verbatim".

## Acceptance

- A SCRAM-SHA-256 connection completes on the interpreter against a server with
  the DEFAULT `authentication_timeout` (60 s). Today it needs 600.
- The compile-path environment penalty is gone: PBKDF2 inside the cl-postgres
  stack costs what PBKDF2 alone costs, within noise.
- Then, in `ClPostgresE2eTest`: delete the `RONTOLISP_POSTGRES_SCRAM_E2E` gate
  from the three `scramAuth*` tests (they go back to running whenever the class
  runs) and drop `-c authentication_timeout=600` from the container command.
  Update the class Javadoc, `.kb/asdf.md` and `.todo/115` in the same pass.
