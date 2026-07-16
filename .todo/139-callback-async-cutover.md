# Callback-async cutover -- remaining follow-ups

The cutover itself is COMPLETE and committed (`6dc8d12` Phases 0-6, `9d4c05d`
Phase 7, `dbe4e2b` Phases 8-10 + final gates). The full plan, the Phase 0 spike
notes (byte encodings, callback protocol constants, scheduler design) and the
per-phase status log live in this file's pre-trim revision:
`git show dbe4e2b:.todo/139-callback-async-cutover.md`.

Landed after the cutover commit (2026-07-17, this trim's working tree):

- The `rontolisp:async` WRAPPER macro: `(async (defun ...))` == async-defun,
  `(async (lambda ...))` == async-lambda, anything else = a clear error. A pure
  frontend rewrite -- `LispMacroExpander.expandAsync` (single form) +
  `rewriteAsyncSugar` (deep pass), hooked in the CLI right after LoadInliner
  (definition scanners), both compilers' `compile()` right after flattenTopLevel
  (direct invocations + playground), `UserMacroExpander`'s expansion output,
  an evalCons case, and `LispAsync.check`/`lowerForm` cases; `--no-gc` rejects
  it by name; joins NO introspection listing (like async-defun/async-lambda).
  Tests (AsyncEvalTest/JvmAsyncCompilerTest/WasmLispCompilerIntegrationTest/
  NoGcWasmCompilerTest + ci-spec case), docs en+ja
  (special-forms/rontolisp-async.md + catalog + curated row + cross-links),
  .kb/async-await.md + CLAUDE.md updated.
- Old item 5 (dead-encoder deletion): the sync `canonStreamRead/Write`,
  `canonFutureRead` (both overloads), `canonFutureWrite`, `canonFutureWriteUtf8`
  and the stackful `canonLiftMemoryUtf8Async` deleted from
  `am.ik.wasm.ComponentWriter` + their golden pins.
- Old item 6 (stale example prose): examples/net/http-handler.lisp header now
  says wasmCloud hosts the component (`wash dev`, wash 2.5.2+); the wasmcloud
  README and app.lisp headers were already current.

## Remaining (enhancements, in priority order)

1. sockets/stdin async promotion (tcp-accept/recv + read-line as implicit
   suspension points in async context) -- an enhancement; blocking behavior is
   correct-if-sequential today.
2. `rontolisp:wait-for` on `--component`: lower to wasi:clocks monotonic-clock
   `wait-for`. Needs import-block.bin regeneration (the block's monotonic-clock
   instance type declares `now` only); regen shifts the block's type indices,
   so every T_* in WasmComponentBuilder must be re-derived via wasm-tools dump
   and the WIT fixtures regenerated.
3. Pending-future stream reads (the async built-in wrappers returning pending
   TYPE_FUTUREs + EVENT_STREAM_* dispatch in the scheduler) and the REAL serve
   callback (per-task waitable-sets + context slots + per-task doorbell
   streams -- spike findings 5/6 in the pre-trim revision). True intra-instance
   concurrency; unobservable today because hosts re-instantiate per request.
4. One mechanical cleanup pass for the two DELIBERATE keeps (documented in
   .kb/async-await.md): rename `TYPE_PROMISE`/`WasmPromiseRuntimeBuilder` to a
   P1-future spelling, and remove the JVM `_await` MARKER then-chain branch
   (dead code inside hand-assembled v50 bytecode -- re-verify the verifier
   carefully; the wait-for LSTORE lesson applies).
