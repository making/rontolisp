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

## Feedback from todo-138 (2026-07-17, the nogc-print 0.3 purge)

Todo-138 executed the async-built-in + blocking `waitable-set.wait` pattern in
a FOURTH hand-written site (`bridge-nogc-print.wat`, alongside `adapter.wat` /
`adapter-sockets.wat` / `adapter-http-server-p1.wat`) and re-confirmed the
spike findings 1/8 end to end (plain 0x43 async lift, zero flags, wasmtime 46).
What that run teaches the remaining items:

- **Item 2 (wait-for regen)**: the regen + re-derive workflow is proven and
  cheaper than it sounds -- with wasm-tools 1.252.0, `regen.sh` left every
  UNTOUCHED blob byte-identical (only the edited world's block changed;
  `git status` on the resources tree is the quick check). But note the
  nogc-print block now derives from `deps/cli` too, and
  `NoGcWasmComponentBuilder` carries its own T_* constants -- an edit to
  `deps/cli`/`deps/clocks` (not just `uni.wit`) must re-derive BOTH builders.
  When dumping, watch for aliases INSIDE the block: the nogc-print block
  aliases `error-code` at component type 1 between its two imports, so "count
  the instance types" under-counts -- read the dump, don't infer.
- **Item 2 workflow gotcha**: after any git-stash byte-identity check, the
  exec jar in `target/` is stale (built from the stashed tree) -- rebuild
  before compiling manual verification artifacts, or you verify the OLD code
  (this bit todo-138 once; caught because the artifact still carried 0.2
  import strings).
- **Item 3 (per-task waitable-sets)**: `bridge-nogc-print.wat` makes the same
  single-task assumption as the adapters -- ONE cached waitable-set (a module
  global there) and a fixed per-call scratch (the core's 16-byte iov cell).
  Fine for the reactor + `--invoke` shape, but if item 3 ever generalizes the
  park to per-task waitable-sets/context slots, the nogc-print bridge is a
  fourth copy of the pattern to visit (or to explicitly exempt as
  single-task-by-design).
- **Item 4**: unaffected by 138; no new TYPE_PROMISE call sites were added
  (the nogc backend still rejects the whole async surface by name).
