# WASM condition catching: handler-case / ignore-errors / unwind-protect on the GC backend

**Status:** design agreed 2026-07-13 (spike DONE, implementation not started).
**Why:** `.todo/125` settled `result<T,E>` as option (c) — the error arm signals a
condition catchable with `handler-case` on EVERY backend (`.kb/wit.md` decision
record), which made a WASM catch mechanism the formal prerequisite of `.todo/128`
(component imports; all of wasi:keyvalue returns `result`). This todo de-risks that
prerequisite FIRST, before 126/127 stack up on it.

**Scope (agreed):** wasm-GC backend, BOTH Preview 1 and `--component` (incl. mode
`serve`). BOTH `handler-case`/`ignore-errors` AND `unwind-protect` cleanup
execution, staged handler-case first. `--no-gc` keeps rejecting (see below).

## Step 0 spike — option (A) wasm exception-handling proposal: PASSED ON EVERY PATH

Measured 2026-07-13 on wasmtime 46.0.1 / wasm-tools 1.252.0 / Node 22.16 (V8),
via the todo-92 method (`wasm-tools print` -> sed-inject -> `parse` -> wasmtime)
on real rontolisp output. Option (B) (data-path error propagation) is NOT needed;
no downgrade of the `result<T,E>` mapping to option (b) is required.

| measurement | result |
|---|---|
| `try_table`+`throw`, hand .wat | works under `wasmtime run -W exceptions=y`; wasmtime implements the EH proposal since **37.0.0**, off by default |
| EH x GC | tag `(param eqref)` carrying i31 / a `(rec)`-group cons struct; typed catch + `ref.cast` readback OK; `catch_all_ref` -> cleanup -> `throw_ref` -> outer typed catch OK (the unwind-protect + rethrow channel) |
| real P1 module, injected | throw/catch inside `_start` then normal output, `-W gc -W exceptions=y`. WITHOUT the flag the module fails to PARSE -> EH instructions must be emitted conditionally |
| real `--component`, injected | **the critical path**: `try_table` held ACTIVE across a print (cooperative suspension of the stackful async lift), `throw` after resume, caught, print again INSIDE the handler (re-suspension) — all correct. Flags = existing `-W gc=y -W component-model-more-async-builtins=y` plus `-W exceptions=y` |
| `wasmtime serve` | per-request throw/catch injected into `%http-dispatch`, response OK under `-W gc=y -W exceptions=y` |
| V8 (playground / jco / Node hosts) | Node 22 needs `--experimental-wasm-exnref`; default-on in current V8 (Chrome 137+ / Node 24+). Gated emission keeps existing programs unaffected |
| uncaught throw escaping `_start` | wasmtime reports `thrown Wasm exception`, exit 1 (current uncaught error = `unreachable` trap, exit 134) -> wrap the EH-mode top level in `catch_all` -> `unreachable` to preserve the trap shape |

## Design

- **EH-mode gate**: after expansion + library splicing, scan the program for
  `handler-case`/`ignore-errors`/`unwind-protect` (the `usesStringOp` /
  `exportNeedsReader` gating precedent). Only then emit the tag section and EH
  instructions. A program without them stays **byte-identical** (prove with the
  todo-92/93/125 stash-dance comparison across P1 / component base / http-client /
  sockets / http-server / --optimize / --dynamic).
- **One tag** `$lisp-cond (param eqref)`. Payload = a cons
  `(condition-instance . message-string)` — the JVM's `_condTl` + exception-message
  channels collapsed into one value. In EH mode `%error` / `%error-cond` evaluate
  the message (today `WasmErrorCompiler` emits a bare `unreachable` without
  evaluating it), build the instance, and `throw`; in non-EH mode they stay the
  bare `unreachable`.
- **handler-case** (`WasmHandlerCaseCompiler`, mirroring `JvmHandlerCaseCompiler`):
  `block $h (result eqref)` + `try_table (catch $lisp-cond $h)` over the protected
  expression; at the landing pad, synthesize a `simple-error` from the message when
  the instance is nil (quote-framed, JVM parity), then compile clause type tests
  (`makeHandlerTypeTest`) and bodies as ORDINARY Lisp forms over a pseudo-local
  (the `__hc_cond$<slot>` locals trick). No matching clause -> re-`throw $lisp-cond`
  with the same payload (the outer handler catches; single-tag world, no exnref
  needed here). `:no-error` runs on normal completion outside the region.
- **`%signal-cond` depth**: a mutable i32 global incremented/decremented around
  protected regions (JVM `_hcDepthTl` parity); `signal` with no established handler
  returns nil. The decrement on the `return`-exit channel rides the same trampoline
  as unwind-protect cleanups (JVM's `%hc-depth-dec` equivalent).
- **unwind-protect** (`WasmUnwindProtectCompiler`): `block $u (result exnref)` +
  `try_table (catch_all_ref $u)`; landing = compile cleanups, `throw_ref` (spike-
  proven; catch_all_ref also intercepts a `%signal-cond` rethrow passing through).
  Normal exit compiles cleanups after the region. The **`return` (`br`) channel**:
  a `br` out of the protected region must run cleanups — route it through an
  **exit trampoline placed lexically OUTSIDE the try_table** (`return` inside an
  escaped scope stores its value and `br`s to the trampoline label; the trampoline
  runs that scope's cleanups then `br`s onward to the real `%block` target,
  innermost scope first). Because the trampoline is outside the try_table region,
  a throw FROM a cleanup cannot re-enter its own handler — the structural
  equivalent of the JVM `holes` mechanism, without the bookkeeping. `return` is
  only legal at empty operand stack (`.kb/do-return-block.md`), so the `br` is
  always safe. Keep the JVM's lite limit for parity: a special-variable `let`
  binding is NOT restored when an unwind crosses it
  (`.kb/dynamic-special-variables.md`).
- **Top level**: in EH mode wrap `_start`/`run`/`%http-dispatch`/component export
  wrapper bodies in `try_table (catch_all)` -> `unreachable`, so an uncaught
  condition still traps exactly like today (exit code / host-visible behavior
  unchanged class-wise; the message is still not rendered — status quo).
- **Semantics parity + the documented lite divergence**: typed catching, condition
  objects, `:report` rendering, `:no-error`, nesting = interpreter/JVM semantics.
  Divergence: WASM catches **signaled conditions only** — runtime traps
  (`(car 5)`-style `ref.cast` failures, unreachable) remain uncatchable. This is
  the third point on an existing spectrum (interpreter catches `LispEvalException`
  only; JVM catches any `RuntimeException`); document it on the error-handling doc
  pages.
- **with-* retrofit**: flip the WASM call sites of `expandWithOpenFile` /
  `expandWithOutputToString` / `expandWithInputFromString` / the usocket `with-*`
  from `unwindProtect=false` to `true` (this changes those forms' output only when
  the whole program is EH-mode? NO — using `with-open-file` would then itself
  enable EH mode and new flags. DECIDE during implementation: keep `false` (old
  shape, no flag creep for programs that never signal) and only document that
  `unwind-protect` now works, OR flip and accept that every `with-open-file`
  program needs `-W exceptions=y`. Leaning KEEP `false`; revisit with user.)
  Same question for `usocket::%usock-guard` (WASM pass-through today).
- **`am.ik.wasm` additions** (language-independent, no rontolisp imports): tag
  section (id 13), `throw` 0x08, `throw_ref` 0x0a, `try_table` 0x1f + catch-clause
  immediates (catch/catch_ref/catch_all/catch_all_ref), `exnref` (0x69). Teach the
  code walkers the new opcodes: `WasmTreeShaker` (`--optimize`; P1 EH + --optimize
  must compose) and `WasmImportInjector` (immediates contain label + TAG indices —
  tags are their own index space, function remapping unaffected, but the decoder
  must skip the immediates correctly).
- **Component path**: core-module-internal only — no component-level sections
  change, blobs untouched, `--wit` output unchanged. The `run` stackful async lift
  and sync/async export lifts need no modification (spike-proven).

## `--no-gc`: stays a compile-time rejection (agreed, with a future-review note)

Condition objects are cons/CLOS-subset values, which `--no-gc` rejects by design,
and its contract is a zero-flag plain MVP module. Keep the clear compile error.
Future review hook (NOT scoped here): if a real embedding ever needs recoverable
errors in `--no-gc` exports, the viable shape is a scalar error-code / sentinel
protocol (option-(B)-style data path over unboxed values, no EH section, flags
stay zero) — record that reasoning here so the door is documented but closed.

## Flags and docs

- Programs that USE catching need `-W exceptions=y`: P1 `wasmtime run -W gc
  -W exceptions=y` (wasmtime **37+**, vs 14+ baseline), component `wasmtime run
  -W gc=y -W component-model-more-async-builtins=y -W exceptions=y` (46+
  unchanged), serve `wasmtime serve -W gc=y -W exceptions=y`. Programs that do
  not are byte-identical and keep their existing run lines — no doc churn outside
  the error-handling pages + a limitations note.
- Doc updates (en/ja, same commit): the error-handling / handler-case /
  ignore-errors / unwind-protect pages lose the "WASM rejects" limitation and gain
  the flag + wasmtime-version note + the traps-uncatchable divergence; README /
  component page run examples for catching programs.
- `.kb/error-handling.md` ("every WASM backend rejects" -> GC backends catch,
  `--no-gc` rejects), `.kb/wit.md` (the "temporary limitation" sentence of the
  result<T,E> decision record), `.todo/128` (prerequisite satisfied) all updated
  on completion.

## Definition of done

- `handler-case` / `ignore-errors` / `unwind-protect` compile and run on wasm-GC
  P1 AND `--component` (incl. serve), catching the same cases as interpreter/JVM
  (known tolerances like gensym naming excepted); `(handler-case (kv:get ...)
  (error (e) ...))`-shaped code is the working foundation for `.todo/128`.
- Programs without catching: byte-identical output (stash-dance proof).
- ci-spec gains catching cases (mind the shared-global in-order concatenation:
  once one case uses handler-case the whole concatenated program is EH-mode — the
  existing cases must still pass byte-identically is NOT required for the
  concatenated binary, only per-case sliced output equality; but ci-spec runs on
  all four backends, so cases must avoid `--no-gc`-rejected forms per the existing
  conventions) + native-image E2E per CLAUDE.md.
- `WasmLispCompilerTest`/`NoGcWasmCompilerTest` rejection pins updated (GC ones
  become behavior tests; `--no-gc` rejection pins stay).
- `rontolisp:list-special-forms`/`list-macros` pins unchanged (no new names).
- All tests + native E2E green; docs + kb updated as above.

## Implementation order (next session)

1. `am.ik.wasm`: tag section + EH opcodes + `exnref`, `WasmWriter` tests.
2. EH-mode gate + `%error`/`%error-cond` throw path + top-level catch_all wrapper;
   stash-dance byte-identity check FIRST.
3. `WasmHandlerCaseCompiler` (+ depth global, `%signal-cond`), integration tests
   mirroring the JVM Phase 3 pins.
4. `WasmUnwindProtectCompiler` + the return-channel exit trampoline
   (`WasmReturnCompiler` learns unwind scopes).
5. Walkers: `WasmTreeShaker` / `WasmImportInjector` opcode support (+ tests).
6. ci-spec cases, docs (en/ja), kb updates, native E2E.
7. Decide the with-*/usocket-guard retrofit question with the user (see above).
