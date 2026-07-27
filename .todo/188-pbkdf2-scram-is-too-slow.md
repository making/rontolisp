# PBKDF2 (SCRAM-SHA-256 authentication) is far too slow

Original problem: `cl-postgres` authenticates with SCRAM-SHA-256 by running
`ironclad:pbkdf2-hash-password` for the server's iteration count (PostgreSQL's
default is 4096), and that single call cost minutes on the interpreter and tens
of seconds on the compiled backends -- so slow that the interpreter could not
finish inside PostgreSQL's default 60-second `authentication_timeout`.

**Three of the four backends are fixed.** What is left is one WASM-only finding
whose cause turned out to be something else entirely; it has its own section at
the end.

Measurements below are 2026-07-27 on linux/x86-64 with the exec jar and wasmtime
47.0.2, warm caches, against the same 4096-iteration 32-byte PBKDF2-HMAC-SHA256
derivation. (The original numbers in this file were darwin/arm64 and roughly 2.5x
faster in absolute terms; the ratios matched.)

| backend | before | after |
| --- | --- | --- |
| interpreter | 319 s | **43 s** |
| JVM | 4.9 s | **0.70 s** |
| WASM Preview 1 | 11.8 s | **7.3 s** |
| WASM component | 13.2 s | **7.7 s** |

And the environment tax -- the same call in a program that ALSO quickloads the
rest of the cl-postgres stack but never uses it:

| | before | after |
| --- | --- | --- |
| JVM | 20.4 s (4.2x) | **0.83 s (1.02x)** |
| WASM Preview 1 | 58.8 s (5.0x) | 31.1 s (4.3x) |

## What the profile said (and what the guess in this file got wrong)

This file's prime suspect was `%typep-runtime`'s registered-layout-proportional
table scan. **It is not involved at all** -- a JFR profile of the JVM-compiled
program has zero `typep` frames. Three unrelated causes, all confirmed by
measurement:

1. **73% of the taxed run was inside `_invoke_<arity>`**, the JVM backend's
   indirect-call dispatcher. Two defects: it was a LINEAR `if (id == funcId)`
   chain (255 cases for ironclad alone, 547 with cl-ppcre also loaded), and --
   the expensive one -- at that size it crossed HotSpot's
   `HugeMethodLimit`, so it was never JIT-compiled.
   `-XX:-DontCompileHugeMethods` alone recovered 2.8x.
2. **86% of the untaxed run was `java.math.BigInteger`.** Every `logand`/
   `logior`/`logxor`/`lognot`/`ash`/`integer-length`/`logbitp` compiled to an
   unconditional BigInteger call, so SHA-256's `rol32`/`mod32+` paid a
   Long -> BigInteger -> Long round trip per operation. The interpreter's
   `Environment` had the same shape.
3. **The interpreter's cost was not arithmetic at all** (BigInteger was under 1%
   of its profile, so the "fixnum fast path in the evaluator's arithmetic" this
   file proposed would have bought almost nothing). 57% of its samples were
   `LispEvaluator.evalCons` itself: at 8209 bytecodes it too sat past
   `HugeMethodLimit`, so the interpreter's innermost method ran in the bytecode
   interpreter. Splitting it was worth 2.7x on its own.

## What landed

- **`.kb/hot-path-method-size.md`** (new invariant): no method that runs per
  evaluated form or per indirect call may exceed 8000 bytecodes.
  - `LispEvaluator.evalCons` split into `evalCons` + `evalConsRareOperator`,
    pinned by `LispEvaluatorHotMethodSizeTest`.
  - `JvmRuntimeBuilder.DISPATCH_SEGMENT_BUDGET` 24000 -> 6000, plus a
    binary-search tree per segment and a segment router
    (`emitDispatchTree` / `emitSegmentRouter`) in place of the linear chain.
- **`.kb/integer-bitwise-fast-paths.md`** (new invariant): the bitwise built-ins
  answer with machine-word arithmetic when the operands fit.
  - JVM: seven `JvmNumericRuntimeBuilder` helpers, called from
    `JvmBitwiseCompiler` instead of inlining BigInteger at every call site.
  - Interpreter: the same guards in `Environment.createGlobal`.
  - WASM already had this (`_big_*` keeps an i64 fast path); unchanged.
  - Plus a shared expander fold: a LITERAL byte specifier makes
    `ldb`/`dpb`/`mask-field` emit a constant mask instead of rebuilding a
    bytespec list and two `let*` scopes per evaluation -- ~25 interpreted nodes
    down to 3, and it is what took the interpreter from 102 s to 43 s and WASM
    Preview 1 from 58.8 s to 31.1 s on the loaded stack.

## What is left: the WASM environment tax is a MODULE-SIZE effect

WASM Preview 1 still costs 4.3x more inside the loaded stack than alone. It is
NOT the dispatch -- its dispatcher is a `br_table` (O(1)) and it already had the
i64 arithmetic. Three controls, each 4096 iterations of the same PBKDF2 against a
program that only quickloads ironclad:

| control added to the program | PBKDF2 | pure arithmetic loop |
| --- | --- | --- |
| nothing | 7.3 s | 0.96 s |
| 1200 never-called defuns of arity 1/3/&rest | 14.3 s (2.0x) | -- |
| 1200 never-called defuns of arity **5** only | 14.9 s (2.0x) | 1.55 s (1.6x) |

Arity 5 is not a dispatch arity the hot loop uses, and `buildDispatchBody`
computes its `br_table` extent from THIS arity's targets, so those 1200 defuns
leave the hot dispatcher byte-identical -- yet the tax is the same. And a tight
`logand`/`+`/`*` loop with no indirect call at all is taxed too. So on WASM,
merely making the module bigger slows every hot loop; the "a tight arithmetic
loop is NOT taxed" control recorded earlier in this file held on the JVM (where
the cause was the dispatcher) and does not hold here.

The same JVM control is the contrast that makes it a WASM-specific finding: 1200
never-called defuns move the JVM from 838 ms to 795 ms, i.e. not at all.

Next step is to attribute it, not to guess: `perf` with
`wasmtime --profile=perfmap` needs `kernel.perf_event_paranoid <= 1`, which this
machine does not allow. Candidates worth testing first are wasmtime's code layout
/ i-cache locality and anything in the emitted module that scales with the
function count and is touched per iteration.

## Acceptance

- [x] A SCRAM-SHA-256 connection completes on the interpreter against a server
  with the DEFAULT `authentication_timeout` (60 s). Raw PBKDF2 is 43 s where it
  was 319 s.
- [x] The JVM compile-path environment penalty is gone (1.02x).
- [ ] The WASM compile-path environment penalty (4.3x) -- see above.
- [ ] `ClPostgresE2eTest`: the `RONTOLISP_POSTGRES_SCRAM_E2E` gate and
  `-c authentication_timeout=600`. **The gate is gone; the raised timeout stays
  until the WASM item lands** -- the component leg still carries the module-size
  tax, and dropping the timeout with only ~1.3x of margin would buy a flaky test.
  Update `.kb/asdf.md` and `.todo/115` in the same pass as that removal.
